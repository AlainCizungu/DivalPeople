package ai.dival.dip.modules.performance;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PerformanceReviewRepository extends JpaRepository<PerformanceReview, UUID> {

    @EntityGraph(attributePaths = {"cycle", "employee", "reviewer"})
    Optional<PerformanceReview> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = {"cycle", "employee", "reviewer"})
    List<PerformanceReview> findByTenantIdAndCycleIdOrderByCreatedAtAsc(
            UUID tenantId, UUID cycleId);

    @EntityGraph(attributePaths = {"cycle", "employee", "reviewer"})
    List<PerformanceReview> findByTenantIdAndEmployeeIdOrderByCreatedAtDesc(
            UUID tenantId, UUID employeeId);

    @EntityGraph(attributePaths = {"cycle", "employee", "reviewer"})
    List<PerformanceReview> findByTenantIdAndReviewerIdOrderByCreatedAtDesc(
            UUID tenantId, UUID reviewerId);

    Optional<PerformanceReview> findByTenantIdAndCycleIdAndEmployeeId(
            UUID tenantId, UUID cycleId, UUID employeeId);
}
