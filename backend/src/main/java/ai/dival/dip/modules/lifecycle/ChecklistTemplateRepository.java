package ai.dival.dip.modules.lifecycle;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChecklistTemplateRepository extends JpaRepository<ChecklistTemplate, UUID> {

    List<ChecklistTemplate> findByTenantIdOrderByNameAsc(UUID tenantId);

    List<ChecklistTemplate> findByTenantIdAndChecklistTypeAndActiveTrueOrderByNameAsc(
            UUID tenantId, ChecklistType checklistType);

    Optional<ChecklistTemplate> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<ChecklistTemplate> findByTenantIdAndCode(UUID tenantId, String code);
}
