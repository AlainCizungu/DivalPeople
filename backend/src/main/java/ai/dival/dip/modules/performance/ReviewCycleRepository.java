package ai.dival.dip.modules.performance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewCycleRepository extends JpaRepository<ReviewCycle, UUID> {

    List<ReviewCycle> findByTenantIdOrderByPeriodStartDesc(UUID tenantId);

    Optional<ReviewCycle> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<ReviewCycle> findByTenantIdAndName(UUID tenantId, String name);
}
