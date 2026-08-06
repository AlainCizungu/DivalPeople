package ai.dival.dip.modules.payroll;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeePayComponentRepository
        extends JpaRepository<EmployeePayComponent, UUID> {

    @EntityGraph(attributePaths = {"employee", "component"})
    Optional<EmployeePayComponent> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = {"employee", "component"})
    List<EmployeePayComponent> findByTenantIdAndEmployeeIdOrderByEffectiveFromDesc(
            UUID tenantId, UUID employeeId);

    @Query("""
            select a from EmployeePayComponent a
            where a.tenantId = :tenantId
              and a.employee.id = :employeeId
              and a.component.id = :componentId
              and a.effectiveTo is null
            """)
    Optional<EmployeePayComponent> findCurrent(@Param("tenantId") UUID tenantId,
                                               @Param("employeeId") UUID employeeId,
                                               @Param("componentId") UUID componentId);

    /**
     * Assignments in force on a day, ordered so the calculation is deterministic.
     *
     * <p>Order matters because a percentage-of-gross deduction depends on the earnings above it;
     * without an explicit order the result would depend on insertion order.
     */
    @EntityGraph(attributePaths = {"employee", "component"})
    @Query("""
            select a from EmployeePayComponent a
            where a.tenantId = :tenantId
              and a.employee.id = :employeeId
              and a.effectiveFrom <= :on
              and (a.effectiveTo is null or a.effectiveTo >= :on)
              and a.component.active = true
            order by a.component.sortOrder asc, a.component.code asc
            """)
    List<EmployeePayComponent> findEffectiveOn(@Param("tenantId") UUID tenantId,
                                               @Param("employeeId") UUID employeeId,
                                               @Param("on") LocalDate on);
}
