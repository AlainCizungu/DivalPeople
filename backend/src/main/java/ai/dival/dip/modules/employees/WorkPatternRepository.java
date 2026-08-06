package ai.dival.dip.modules.employees;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkPatternRepository extends JpaRepository<WorkPattern, UUID> {

    List<WorkPattern> findByTenantIdOrderByNameAsc(UUID tenantId);

    Optional<WorkPattern> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<WorkPattern> findByTenantIdAndCode(UUID tenantId, String code);
}
