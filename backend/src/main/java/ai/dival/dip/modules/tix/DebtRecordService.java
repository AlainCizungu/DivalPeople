package ai.dival.dip.modules.tix;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
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

    public DebtRecordService(DebtRecordRepository debtRecords, AuditService audit) {
        this.debtRecords = debtRecords;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DebtRecord> listOwn() {
        return debtRecords.findByTenantId(TenantContext.require());
    }

    @Transactional
    public DebtRecord declare(DebtRecord record, UUID actorId) {
        if (!record.hasDunningEvidence()) {
            throw new IllegalArgumentException("A debt record may not be declared without dunning evidence");
        }
        DebtRecord saved = debtRecords.save(record);
        audit.recordSuccess("TIX_DEBT_DECLARED", "DebtRecord", saved.getId().toString(), actorId);
        return saved;
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
