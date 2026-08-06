package ai.dival.dip.modules.payroll;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayslipRepository extends JpaRepository<Payslip, UUID> {

    @EntityGraph(attributePaths = {"period", "employee", "lines"})
    Optional<Payslip> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = {"period", "employee", "lines"})
    List<Payslip> findByTenantIdAndPeriodIdOrderByEmployeeNumberAsc(UUID tenantId, UUID periodId);

    @EntityGraph(attributePaths = {"period", "employee", "lines"})
    List<Payslip> findByTenantIdAndEmployeeIdOrderByCreatedAtDesc(UUID tenantId, UUID employeeId);

    Optional<Payslip> findByTenantIdAndPeriodIdAndEmployeeId(
            UUID tenantId, UUID periodId, UUID employeeId);
}
