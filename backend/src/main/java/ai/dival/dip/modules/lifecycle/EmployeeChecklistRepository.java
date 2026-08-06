package ai.dival.dip.modules.lifecycle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeChecklistRepository extends JpaRepository<EmployeeChecklist, UUID> {

    @EntityGraph(attributePaths = {"employee", "items", "items.assignee"})
    List<EmployeeChecklist> findByTenantIdAndEmployeeIdOrderByCreatedAtDesc(
            UUID tenantId, UUID employeeId);

    @EntityGraph(attributePaths = {"employee", "items", "items.assignee"})
    List<EmployeeChecklist> findByTenantIdAndStatusOrderByAnchorDateAsc(
            UUID tenantId, ChecklistStatus status);

    @EntityGraph(attributePaths = {"employee", "items", "items.assignee"})
    Optional<EmployeeChecklist> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<EmployeeChecklist> findByTenantIdAndEmployeeIdAndChecklistTypeAndStatus(
            UUID tenantId, UUID employeeId, ChecklistType checklistType, ChecklistStatus status);
}
