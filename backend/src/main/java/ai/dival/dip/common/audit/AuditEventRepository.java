package ai.dival.dip.common.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByTenantIdOrderByOccurredAtDesc(UUID tenantId);

    List<AuditEvent> findByTenantIdAndResourceTypeOrderByOccurredAtDesc(UUID tenantId, String resourceType);
}
