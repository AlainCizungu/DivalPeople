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
     * Resolves the submitted identifiers to a subject and reports the statuses held against it.
     */
    @Transactional(readOnly = true)
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

        Optional<Match> match = resolveSubject(request);
        if (match.isEmpty()) {
            // The purpose is recorded even when nothing matched. A sweep looking for which
            // identifiers exist is exactly the pattern an auditor needs to see, and it produces
            // nothing but no-matches.
            audit.record(INQUIRY_ACTION, "Subject", null, AuditService.OUTCOME_SUCCESS, actorId,
                    request.purpose());
            return InquiryResult.noMatch();
        }

        Subject subject = match.get().subject();
        // Scored against the identifier that actually resolved the subject. Scoring the strongest
        // identifier *submitted* let a caller add an invented passport number to a real phone
        // number and have a weak match treated as a strong one.
        double confidence = matcher.confidence(subject, request, match.get().identifier());
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

    /** A subject and the identifier that found it. The second half is what the score rests on. */
    private record Match(Subject subject, InquiryRequest.SubmittedIdentifier identifier) {
    }

    private Optional<Match> resolveSubject(InquiryRequest request) {
        // Strong identifiers first: a national ID match is worth more than a name match.
        List<InquiryRequest.SubmittedIdentifier> ordered = new ArrayList<>(request.identifiers());
        ordered.sort((a, b) -> Boolean.compare(b.type().isStrong(), a.type().isStrong()));

        for (InquiryRequest.SubmittedIdentifier submitted : ordered) {
            Optional<Subject> found = subjects.findByIdentifier(
                    submitted.type(), SubjectIdentifier.normalizeValue(submitted.value()));
            if (found.isPresent()) {
                return Optional.of(new Match(found.get(), submitted));
            }
        }
        return Optional.empty();
    }

    /**
     * Advisory indicators only. These are surfaced for review and never treated as findings.
     */
    private List<String> detectFraudSignals(Subject subject, UUID tenantId) {
        List<String> signals = new ArrayList<>();
        for (SubjectIdentifier identifier : subject.getIdentifiers()) {
            List<SubjectIdentifier> sharing = identifiers.findAllByIdentifierTypeAndNormalizedValue(
                    identifier.getIdentifierType(), identifier.getNormalizedValue());
            boolean sharedAcrossSubjects = sharing.stream()
                    .anyMatch(other -> !other.getSubject().getId().equals(subject.getId()));
            if (sharedAcrossSubjects) {
                signals.add("REUSED_IDENTIFIER_" + identifier.getIdentifierType().name());
            }
        }
        return List.copyOf(signals);
    }
}
