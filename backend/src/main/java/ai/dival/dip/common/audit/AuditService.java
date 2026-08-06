package ai.dival.dip.common.audit;

import ai.dival.dip.common.tenancy.TenantContext;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes audit events.
 *
 * <p>Events are written in their own transaction so that an audit entry survives the rollback of
 * the operation that produced it. A denied or failed attempt is exactly the thing an auditor
 * most wants to see.
 */
@Service
public class AuditService {

    public static final String OUTCOME_SUCCESS = "SUCCESS";
    public static final String OUTCOME_DENIED = "DENIED";
    public static final String OUTCOME_FAILURE = "FAILURE";

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String resourceType, String resourceId, String outcome, UUID actorId) {
        repository.save(new AuditEvent(
                TenantContext.find().orElse(null),
                actorId,
                action,
                resourceType,
                resourceId,
                outcome,
                null,
                null,
                Instant.now()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String action, String resourceType, String resourceId, UUID actorId) {
        record(action, resourceType, resourceId, OUTCOME_SUCCESS, actorId);
    }
}
