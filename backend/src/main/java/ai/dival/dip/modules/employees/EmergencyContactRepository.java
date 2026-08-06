package ai.dival.dip.modules.employees;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmergencyContactRepository extends JpaRepository<EmergencyContact, UUID> {

    /** Ordered by priority: whoever should be called first comes first. */
    List<EmergencyContact> findByTenantIdAndEmployeeIdOrderByPriorityAsc(
            UUID tenantId, UUID employeeId);

    Optional<EmergencyContact> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndEmployeeIdAndPriority(UUID tenantId, UUID employeeId, int priority);
}
