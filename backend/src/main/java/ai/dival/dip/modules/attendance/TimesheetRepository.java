package ai.dival.dip.modules.attendance;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimesheetRepository extends JpaRepository<Timesheet, UUID> {

    Optional<Timesheet> findByIdAndTenantId(UUID id, UUID tenantId);

    List<Timesheet> findByTenantIdAndEmployeeIdOrderByPeriodStartDesc(
            UUID tenantId, UUID employeeId);

    Optional<Timesheet> findByTenantIdAndEmployeeIdAndPeriodStart(
            UUID tenantId, UUID employeeId, LocalDate periodStart);

    List<Timesheet> findByTenantIdAndStatusOrderByPeriodStartAsc(
            UUID tenantId, TimesheetStatus status);
}
