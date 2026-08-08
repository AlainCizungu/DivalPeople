package ai.dival.dip.modules.tix;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Declaration and settlement of obligations by the operator that owns them.
 *
 * <p>Everything here is tenant-scoped: an operator may only see and change its own records.
 * Reads across operators belong to {@link ExchangeService}.
 */
@Service
public class DebtRecordService {

    private final DebtRecordRepository debtRecords;
    private final AuditService audit;
    private final SubjectResolver subjects;
    private final ReportingThreshold threshold;

    public DebtRecordService(DebtRecordRepository debtRecords, AuditService audit,
                             SubjectResolver subjects, ReportingThreshold threshold) {
        this.debtRecords = debtRecords;
        this.audit = audit;
        this.subjects = subjects;
        this.threshold = threshold;
    }

    @Transactional(readOnly = true)
    public List<DebtRecord> listOwn() {
        return debtRecords.findByTenantId(TenantContext.require());
    }

    /**
     * Declares a default from an operator's submission.
     *
     * <p>The order of the checks below is not arbitrary. The threshold is evaluated <em>before</em>
     * the subject is resolved, so that a debt too small to belong in the registry never causes a
     * person to be created in it. Resolving first and refusing afterwards would leave the subject
     * behind on a rolled-back transaction in some configurations and, worse, would mean a caller
     * could populate the registry's spine with people by submitting amounts it knows will be
     * refused — learning nothing itself, but writing names into a national database as a
     * side-effect of a rejected request.
     *
     * @param actorId who is declaring; recorded on the audit trail
     */
    @Transactional
    public Declaration declare(DeclarationRequest request, UUID actorId) {
        UUID tenantId = TenantContext.require();

        threshold.requireDeclarable(request.amount(), request.currency());

        if (!request.dunningEvidence()) {
            throw new PolicyRefusedException(
                    "A default may not be declared without evidence that the contractual dunning "
                            + "process ran first.");
        }
        if (request.defaultDate().isAfter(LocalDate.now())) {
            // A future default date would start the retention clock in the future, which keeps a
            // record alive past the period the law allows.
            throw new PolicyRefusedException("The default date cannot be in the future.");
        }

        SubjectResolver.Resolution resolution = subjects.resolve(request);

        // Checked rather than left to uq_tix_debt_open_per_operator. The partial unique index is
        // the real guarantee and stays, but a constraint violation surfaces as a 500 at flush
        // time with a Postgres message in it, long after this method returned. An operator
        // re-sending a declaration deserves an answer it can read.
        debtRecords.findByTenantIdAndSubjectId(tenantId, resolution.subject().getId()).stream()
                .filter(existing -> existing.getStatus() == DebtStatus.OUTSTANDING)
                .findFirst()
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "This operator already has an open record against this subject. "
                                    + "Settle it or raise the amount on the existing record "
                                    + "rather than declaring a second one.");
                });

        DebtRecord saved = debtRecords.save(new DebtRecord(
                resolution.subject(), request.amount(), request.currency(),
                request.serviceCategory(), request.defaultDate(), request.dunningEvidence()));

        audit.record("TIX_DEBT_DECLARED", "DebtRecord", saved.getId().toString(),
                AuditService.OUTCOME_SUCCESS, actorId,
                "Declared " + request.amount().toPlainString() + " " + request.currency()
                        + (resolution.created() ? "; subject new to the exchange" : ""));

        return new Declaration(saved, resolution.created(), resolution.identifiersLearned());
    }

    /**
     * @param record             what was written
     * @param subjectWasCreated  true when this declaration put a new person into the exchange —
     *                           worth surfacing, because it is the moment somebody who was not in
     *                           a national registry becomes somebody who is
     * @param identifiersLearned documents the exchange had not seen before
     */
    public record Declaration(DebtRecord record, boolean subjectWasCreated,
                              int identifiersLearned) {
    }

    /** Only the declaring operator may settle. The tenant predicate is what enforces that. */
    @Transactional
    public DebtRecord settle(UUID recordId, UUID actorId) {
        UUID tenantId = TenantContext.require();
        DebtRecord record = debtRecords.findByIdAndTenantId(recordId, tenantId)
                .orElseThrow(() -> new DebtRecordNotFoundException(recordId));
        record.settle();
        audit.recordSuccess("TIX_DEBT_SETTLED", "DebtRecord", recordId.toString(), actorId);
        return record;
    }

    @Transactional
    public DebtRecord dispute(UUID recordId, UUID actorId) {
        UUID tenantId = TenantContext.require();
        DebtRecord record = debtRecords.findByIdAndTenantId(recordId, tenantId)
                .orElseThrow(() -> new DebtRecordNotFoundException(recordId));
        record.dispute();
        audit.recordSuccess("TIX_DEBT_DISPUTED", "DebtRecord", recordId.toString(), actorId);
        return record;
    }

    /** Deliberately does not reveal whether the record exists under another tenant. */
    public static class DebtRecordNotFoundException extends ResourceNotFoundException {
        public DebtRecordNotFoundException(UUID id) {
            super("Debt record not found: " + id);
        }
    }
}
