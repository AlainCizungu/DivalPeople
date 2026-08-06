package ai.dival.dip.modules.employees;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeDependentRepository extends JpaRepository<EmployeeDependent, UUID> {

    List<EmployeeDependent> findByTenantIdAndEmployeeIdOrderByFullNameAsc(
            UUID tenantId, UUID employeeId);

    Optional<EmployeeDependent> findByIdAndTenantId(UUID id, UUID tenantId);
}
