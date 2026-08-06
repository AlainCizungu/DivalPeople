package ai.dival.dip.modules.tix;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.tenancy.TenantContext;
import jakarta.persistence.EntityManager;
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

    public ExchangeService(SubjectRepository subjects,
                           SubjectIdentifierRepository identifiers,
                           DebtRecordRepository debtRecords,
                           IdentityMatcher matcher,
                           AuditService audit,
                           EntityManager entityManager) {
        this.subjects = subjects;
        this.identifiers = identifiers;
        this.debtRecords = debtRecords;
        this.matcher = matcher;
        this.audit = audit;
        this.entityManager = entityManager;
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
    public InquiryResult inquire(InquiryRequest request, UUID actorId) {
        UUID tenantId = TenantContext.require();

        Optional<Subject> match = resolveSubject(request);
        if (match.isEmpty()) {
            audit.record("TIX_INQUIRY", "Subject", null, AuditService.OUTCOME_SUCCESS, actorId);
            return InquiryResult.noMatch();
        }

        Subject subject = match.get();
        double confidence = matcher.confidence(subject, request);
        audit.record("TIX_INQUIRY", "Subject", subject.getId().toString(), AuditService.OUTCOME_SUCCESS, actorId);

        if (confidence < AUTOMATIC_MATCH_THRESHOLD) {
            return InquiryResult.reviewRequired(subject.getId(), confidence);
        }

        enterExchangeMode();
        List<DebtRecord> records = debtRecords.findAcrossOperators(subject.getId(), EXCHANGEABLE_STATUSES);
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
                confidence,
                subject.getId(),
                List.copyOf(statuses),
                detectFraudSignals(subject, tenantId));
    }

    private Optional<Subject> resolveSubject(InquiryRequest request) {
        // Strong identifiers first: a national ID match is worth more than a name match.
        List<InquiryRequest.SubmittedIdentifier> ordered = new ArrayList<>(request.identifiers());
        ordered.sort((a, b) -> Boolean.compare(b.type().isStrong(), a.type().isStrong()));

        for (InquiryRequest.SubmittedIdentifier submitted : ordered) {
            Optional<Subject> found = subjects.findByIdentifier(
                    submitted.type(), SubjectIdentifier.normalizeValue(submitted.value()));
            if (found.isPresent()) {
                return found;
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
