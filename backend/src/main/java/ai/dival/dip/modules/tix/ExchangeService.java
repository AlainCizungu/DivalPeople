package ai.dival.dip.modules.tix;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.RateLimitExceededException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.risk.IdentityStrength;
import ai.dival.dip.modules.risk.RiskIndicatorService;
import ai.dival.dip.modules.risk.RiskInputs;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only component permitted to read across tenant boundaries.
 *
 * <p>Every entry point here authorizes the caller, records an audit event, and returns a
 * normalised indicator rather than another operator's data. Adding a method to this class is a
 * security-relevant change and requires review.
 */
@Service
public class ExchangeService {

    /** Below this confidence a match is returned for human review rather than acted upon. */
    static final double AUTOMATIC_MATCH_THRESHOLD = 0.90;

    private static final List<DebtStatus> EXCHANGEABLE_STATUSES =
            List.of(DebtStatus.OUTSTANDING, DebtStatus.SETTLED);

    private final SubjectRepository subjects;
    private final SubjectIdentifierRepository identifiers;
    private final DebtRecordRepository debtRecords;
    private final IdentityMatcher matcher;
    private final AuditService audit;
    private final EntityManager entityManager;
    private final InquiryRateLimiter rateLimiter;
    private final RiskIndicatorService riskIndicator;
    private final Clock clock;

    public ExchangeService(SubjectRepository subjects,
                           SubjectIdentifierRepository identifiers,
                           DebtRecordRepository debtRecords,
                           IdentityMatcher matcher,
                           AuditService audit,
                           EntityManager entityManager,
                           InquiryRateLimiter rateLimiter,
                           RiskIndicatorService riskIndicator,
                           Clock clock) {
        this.subjects = subjects;
        this.identifiers = identifiers;
        this.debtRecords = debtRecords;
        this.matcher = matcher;
        this.audit = audit;
        this.entityManager = entityManager;
        this.rateLimiter = rateLimiter;
        this.riskIndicator = riskIndicator;
        this.clock = clock;
    }

    /**
     * Opts this transaction into reading debt records across operators.
     *
     * <p>{@code SET LOCAL} scopes the flag to the transaction, so it is discarded at commit or
     * rollback and cannot survive onto a pooled connection. The matching policy admits the flag
     * only for reads — a transaction in exchange mode still cannot write outside its own tenant.
     */
    private void enterExchangeMode() {
        entityManager
                .createNativeQuery("SELECT set_config('app.exchange', 'on', true)")
                .getSingleResult();
    }

    /**
     * The audit action every inquiry is recorded under.
     *
     * <p>A constant rather than a literal because it is now a join key. Article 214 of the Code du
     * numérique requires that a correction reach whoever was told the incorrect thing, and
     * {@link SubjectRightsService} finds those people by looking for audit rows carrying this
     * action. Two spellings of the same string in two files would not fail a build, would not fail
     * a test that only checks writing, and would silently mean nobody is ever notified.
     *
     * <p>Public since the anomaly module began reading the same rows to find inquiry behaviour
     * that looks like enumeration. That module must not spell the string itself: two spellings
     * would not fail a build, would not fail a test, and would silently mean it watches nothing.
     */
    /**
     * The one currency the risk model will add up.
     *
     * <p>Not configuration. A deployment can add a reporting floor for another currency, and the
     * day it does the exposure factor must stop answering rather than start converting — so this
     * is deliberately not read from {@code TixProperties}, where somebody extending the floors
     * would reasonably expect to be extending this too.
     */
    private static final String RISK_CURRENCY = "USD";

    public static final String INQUIRY_ACTION = "TIX_INQUIRY";

    /**
     * Resolves the submitted identifiers to a subject and reports the statuses held against it.
     */
    @Transactional(readOnly = true)
    public InquiryResult inquire(InquiryRequest request, UUID actorId) {
        UUID tenantId = TenantContext.require();

        // Charged first, and the refusal is audited. A throttled sweep that left no trace would
        // simply be a slower invisible sweep; the point of the limit is to make the attempt
        // legible, not merely slow.
        try {
            rateLimiter.charge(tenantId);
        } catch (RateLimitExceededException refused) {
            audit.record(INQUIRY_ACTION, "Subject", null, AuditService.OUTCOME_DENIED, actorId,
                    request.purpose());
            throw refused;
        }

        Resolution resolution = resolveSubject(request);

        if (resolution.ambiguous()) {
            // Several subjects carry that name. Choosing one would be a guess presented as an
            // answer, and saying how many would be the first sentence of an enumeration. The
            // caller is told a human must look, which is true and carries nothing.
            audit.record(INQUIRY_ACTION, "Subject", null, AuditService.OUTCOME_SUCCESS, actorId,
                    request.purpose());
            return InquiryResult.reviewRequired();
        }
        if (!resolution.found()) {
            // The purpose is recorded even when nothing matched. A sweep looking for which
            // identifiers exist is exactly the pattern an auditor needs to see, and it produces
            // nothing but no-matches.
            audit.record(INQUIRY_ACTION, "Subject", null, AuditService.OUTCOME_SUCCESS, actorId,
                    request.purpose());
            return InquiryResult.noMatch();
        }

        Subject subject = resolution.subject();
        // Scored against the identifier that actually resolved the subject. Scoring the strongest
        // identifier *submitted* let a caller add an invented passport number to a real phone
        // number and have a weak match treated as a strong one.
        //
        // With no identifier at all the name did the resolving, and it is scored as what it is:
        // strong for a registered trading name, and below the threshold for a personal name.
        double confidence = resolution.identifier() == null
                ? matcher.confidenceByName(subject)
                : matcher.confidence(subject, request, resolution.identifier());
        audit.record(INQUIRY_ACTION, "Subject", subject.getId().toString(),
                AuditService.OUTCOME_SUCCESS, actorId, request.purpose());

        if (confidence < AUTOMATIC_MATCH_THRESHOLD) {
            // The score stays on this side of the wire, and so does the subject id.
            return InquiryResult.reviewRequired();
        }

        enterExchangeMode();
        // Filtered in the query rather than in the loop below. Both would work today, and only
        // one of them stays correct: the loop is defence in depth against a status leaking, and
        // if expiry were enforced only there, any future caller of findAcrossOperators would get
        // records the retention period says must not exist. Erasure is not a rendering concern.
        LocalDate today = LocalDate.now(clock);
        List<DebtRecord> records =
                debtRecords.findAcrossOperators(subject.getId(), EXCHANGEABLE_STATUSES, today);
        Set<DebtStatus> statuses = new LinkedHashSet<>();
        // How many operators, not how many records and not how many statuses. Counted from the
        // same filtered pass so that a record excluded from the answer is excluded from the count
        // — a suppressed dispute must not raise the number any more than it may show its status.
        Set<UUID> institutions = new LinkedHashSet<>();
        // Counted separately from the institutions above, because the indicator asks a narrower
        // question: how many participants are owed money now. An operator whose only record here
        // was settled belongs in the disclosed count and not in this one.
        Set<UUID> institutionsOwed = new LinkedHashSet<>();
        boolean outstanding = false;
        boolean settled = false;
        long longestOverdueDays = -1;
        // Totalled here and nowhere else, and never disclosed. The exchange answers with an
        // outcome, a set of statuses and a count of institutions; this figure exists only to be
        // turned into one of four bands inside the risk model, and no caller can read it back.
        //
        // Summed only over one currency. Every declaration today is USD — counsel confirmed both
        // operator files are, and the deployment configures a floor for no other currency, so
        // nothing else can be declared — but a second currency appearing later must not silently
        // become a wrong total. It becomes a refusal to assess instead, which the screen prints.
        BigDecimal outstandingUsd = BigDecimal.ZERO;
        boolean mixedCurrency = false;
        for (DebtRecord record : records) {
            // Defence in depth: disputed or investigated records must never leak through.
            if (!record.isVisibleToOtherOperators()) {
                continue;
            }
            statuses.add(record.getStatus());
            institutions.add(record.getTenantId());
            if (record.getStatus() == DebtStatus.OUTSTANDING) {
                outstanding = true;
                institutionsOwed.add(record.getTenantId());
                // The age of the oldest unpaid obligation, and only of unpaid ones. A settled
                // record keeps its default date and that date can be years old; weighing it would
                // report a company that fell behind in 2023 and paid in 2023 as still behind.
                longestOverdueDays = Math.max(longestOverdueDays,
                        ChronoUnit.DAYS.between(record.getDefaultDate(), today));
                if (RISK_CURRENCY.equalsIgnoreCase(record.getCurrency())) {
                    outstandingUsd = outstandingUsd.add(record.getAmount());
                } else {
                    mixedCurrency = true;
                }
            } else {
                settled = true;
            }
        }

        InquiryResult.Outcome outcome = outstanding
                ? InquiryResult.Outcome.OUTSTANDING_DEBT
                : InquiryResult.Outcome.CLEAR;

        List<String> fraudSignals = detectFraudSignals(subject, tenantId);

        return new InquiryResult(
                outcome,
                subject.getId(),
                List.copyOf(statuses),
                institutions.size(),
                fraudSignals,
                riskIndicator.assess(new RiskInputs(
                        outstanding,
                        settled,
                        institutionsOwed.size(),
                        longestOverdueDays,
                        // How the subject was found, not how strong the identifiers it happens to
                        // carry are. A company with an RCCM on file that was matched on its name
                        // was still matched on its name.
                        strengthOf(resolution.identifier()),
                        fraudSignals.size(),
                        // Null rather than a partial sum. Handing the model the dollars and
                        // dropping the francs would report a smaller exposure than the subject
                        // has, which is the direction of error that costs a lender money.
                        mixedCurrency ? null : outstandingUsd)));
    }

    /**
     * What the submitted details resolved to.
     *
     * @param identifier the identifier that found the subject, or null when the name did — which
     *                   is what the score rests on, so the difference has to survive to the caller
     * @param ambiguous  more than one subject matched. Distinct from "nothing matched", because
     *                   the answers differ: nothing found is an answer, and several found is a
     *                   refusal to guess
     */
    private record Resolution(Subject subject, InquiryRequest.SubmittedIdentifier identifier,
                              boolean ambiguous) {

        static Resolution none() {
            return new Resolution(null, null, false);
        }

        static Resolution tooMany() {
            return new Resolution(null, null, true);
        }

        boolean found() {
            return subject != null;
        }
    }

    /**
     * Identifiers first, then the name.
     *
     * <p>The order is not a preference, it is the accuracy ranking. A national ID that matches is
     * proof of very nearly the strength the exchange needs; a name is a hypothesis. Falling back
     * to the name only when no identifier resolved means a caller cannot use a name to override a
     * document that disagrees with it.
     *
     * <p><strong>Exact match on the normalised name, never a prefix or a substring.</strong> A
     * prefix search would answer "how many businesses begin with these letters", one letter at a
     * time, and that is enumeration wearing a lookup's clothes. Exact matching answers only the
     * question the caller already knew how to ask.
     */
    private Resolution resolveSubject(InquiryRequest request) {
        // Strong identifiers first: a national ID match is worth more than a name match.
        List<InquiryRequest.SubmittedIdentifier> ordered = new ArrayList<>(request.identifiers());
        ordered.sort((a, b) -> Boolean.compare(b.type().isStrong(), a.type().isStrong()));

        for (InquiryRequest.SubmittedIdentifier submitted : ordered) {
            // Through the identifier repository rather than a subject query, so that an account
            // reference is matched only against the asking operator's own numbering. Asked by
            // anybody else it finds nothing, which is the correct answer: their account 100234 is
            // not this account 100234.
            Optional<Subject> found = identifiers.locate(
                    submitted.type(), SubjectIdentifier.normalizeValue(submitted.value()),
                    TenantContext.require())
                    .map(SubjectIdentifier::getSubject);
            if (found.isPresent()) {
                return new Resolution(found.get(), submitted, false);
            }
        }

        if (!request.hasUsableName()) {
            return Resolution.none();
        }

        List<Subject> byName = subjects.findByNormalizedName(
                Subject.normalizeName(request.fullName()));
        if (byName.isEmpty()) {
            return Resolution.none();
        }
        if (byName.size() > 1) {
            return Resolution.tooMany();
        }
        return new Resolution(byName.get(0), null, false);
    }

    /**
     * The nine identifier types, collapsed onto the three the risk model reasons about.
     *
     * <p>The mapping lives here rather than in the risk module, and that is the boundary the whole
     * arrangement rests on: {@code risk} knows nothing about RCCMs or MSISDNs, so it can be handed
     * to somebody as one self-contained thing. What travels across is a judgement about strength,
     * made by the module that knows what these documents are.
     *
     * <p>A null identifier means the name resolved the subject, which is the weakest answer the
     * exchange gives — and {@code REPORTED_NAME} is the same answer arriving by the other route,
     * through a delivery that named its customers and numbered none of them. Both are NAME_ONLY,
     * because in both cases nothing but a name says who this is.
     */
    private static IdentityStrength strengthOf(
            InquiryRequest.SubmittedIdentifier matched) {
        if (matched == null || matched.type() == IdentifierType.REPORTED_NAME) {
            return IdentityStrength.NAME_ONLY;
        }
        // Scoped before strong, and the order is the whole correctness of this method.
        // ACCOUNT_REFERENCE answers isStrong() with true — it deterministically identifies one
        // customer, which is what that flag is about — and it is issued by the operator, so
        // account 100234 exists at every participant and means a different company at each.
        // Testing strength first would have graded the entire Vodacom book as firmly identified.
        if (matched.type().isOperatorScoped() || !matched.type().isStrong()) {
            return IdentityStrength.PARTIAL;
        }
        return IdentityStrength.STRONG;
    }

    /**
     * Advisory indicators only. These are surfaced for review and never treated as findings.
     */
    private List<String> detectFraudSignals(Subject subject, UUID tenantId) {
        List<String> signals = new ArrayList<>();
        for (SubjectIdentifier identifier : subject.getIdentifiers()) {
            List<SubjectIdentifier> sharing = identifiers.reuses(
                    identifier.getIdentifierType(), identifier.getNormalizedValue(), tenantId);
            boolean sharedAcrossSubjects = sharing.stream()
                    .anyMatch(other -> !other.getSubject().getId().equals(subject.getId()));
            if (sharedAcrossSubjects) {
                signals.add("REUSED_IDENTIFIER_" + identifier.getIdentifierType().name());
            }
        }
        return List.copyOf(signals);
    }
}
