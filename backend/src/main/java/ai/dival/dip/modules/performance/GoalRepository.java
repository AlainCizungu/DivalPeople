package ai.dival.dip.modules.performance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoalRepository extends JpaRepository<Goal, UUID> {

    @EntityGraph(attributePaths = {"employee", "cycle", "supports"})
    List<Goal> findByTenantIdAndEmployeeIdOrderByCreatedAtDesc(UUID tenantId, UUID employeeId);

    @EntityGraph(attributePaths = {"employee", "cycle", "supports"})
    List<Goal> findByTenantIdAndCycleIdOrderByCreatedAtDesc(UUID tenantId, UUID cycleId);

    @EntityGraph(attributePaths = {"employee", "cycle", "supports"})
    Optional<Goal> findByIdAndTenantId(UUID id, UUID tenantId);
}
