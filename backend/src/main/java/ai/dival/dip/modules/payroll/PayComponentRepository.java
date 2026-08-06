package ai.dival.dip.modules.payroll;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayComponentRepository extends JpaRepository<PayComponent, UUID> {

    List<PayComponent> findByTenantIdOrderBySortOrderAscNameAsc(UUID tenantId);

    Optional<PayComponent> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<PayComponent> findByTenantIdAndCode(UUID tenantId, String code);
}
