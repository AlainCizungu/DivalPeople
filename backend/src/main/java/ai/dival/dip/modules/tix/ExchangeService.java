package ai.dival.dip.modules.tix;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.RateLimitExceededException;
import ai.dival.dip.common.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
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
    private final Clock clock;

    public ExchangeService(SubjectRepository subjects,
                           SubjectIdentifierRepository identifiers,
                           DebtRecordRepository debtRecords,
                           IdentityMatcher matcher,
                           AuditService audit,
                           EntityManager entityManager,
                           InquiryRateLimiter rateLimiter,
                           Clock clock) {
        this.subjects = subjects;
        this.identifiers = identifiers;
        this.debtRecords = debtRecords;
        this.matcher = matcher;
        this.audit = audit;
        this.entityManager = entityManager;
        this.rateLimiter = rateLimiter;
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
     */
    static final String INQUIRY_ACTION = "TIX_INQUIRY";

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
        boolean outstanding = false;
        for (DebtRecord record : records) {
            // Defence in depth: disputed or investigated records must never leak through.
            if (!record.isVisibleToOtherOperators()) {
                continue;
            }
            statuses.add(record.getStatus());
            if (record.getStatus() == DebtStatus.OUTSTANDING) {
                outstanding = true;
            }
        }

        InquiryResult.Outcome outcome = outstanding
                ? InquiryResult.Outcome.OUTSTANDING_DEBT
                : InquiryResult.Outcome.CLEAR;

        return new InquiryResult(
                outcome,
                subject.getId(),
                List.copyOf(statuses),
                detectFraudSignals(subject, tenantId));
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
