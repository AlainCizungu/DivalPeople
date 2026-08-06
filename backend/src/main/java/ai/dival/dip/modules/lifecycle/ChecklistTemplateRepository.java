package ai.dival.dip.modules.lifecycle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistTemplateRepository extends JpaRepository<ChecklistTemplate, UUID> {

    @EntityGraph(attributePaths = {"items"})
    List<ChecklistTemplate> findByTenantIdOrderByNameAsc(UUID tenantId);

    List<ChecklistTemplate> findByTenantIdAndChecklistTypeAndActiveTrueOrderByNameAsc(
            UUID tenantId, ChecklistType checklistType);

    @EntityGraph(attributePaths = {"items"})
    Optional<ChecklistTemplate> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<ChecklistTemplate> findByTenantIdAndCode(UUID tenantId, String code);
}
