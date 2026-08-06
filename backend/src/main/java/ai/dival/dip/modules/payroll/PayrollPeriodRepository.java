package ai.dival.dip.modules.payroll;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayrollPeriodRepository extends JpaRepository<PayrollPeriod, UUID> {

    @EntityGraph(attributePaths = {"approver"})
    List<PayrollPeriod> findByTenantIdOrderByPeriodStartDesc(UUID tenantId);

    @EntityGraph(attributePaths = {"approver"})
    Optional<PayrollPeriod> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<PayrollPeriod> findByTenantIdAndPeriodStart(UUID tenantId, LocalDate periodStart);
}
