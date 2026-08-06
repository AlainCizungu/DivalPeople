package ai.dival.dip.modules.payroll;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CompensationRepository extends JpaRepository<Compensation, UUID> {

    @EntityGraph(attributePaths = {"employee"})
    Optional<Compensation> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = {"employee"})
    List<Compensation> findByTenantIdAndEmployeeIdOrderByEffectiveFromDesc(
            UUID tenantId, UUID employeeId);

    /** The open-ended record, if there is one. There can be at most one. */
    @EntityGraph(attributePaths = {"employee"})
    @Query("""
            select c from Compensation c
            where c.tenantId = :tenantId
              and c.employee.id = :employeeId
              and c.effectiveTo is null
            """)
    Optional<Compensation> findCurrent(@Param("tenantId") UUID tenantId,
                                       @Param("employeeId") UUID employeeId);

    /**
     * The salary in force on a given day.
     *
     * <p>This is the query payroll runs on, and the reason compensation is never updated in
     * place: a run for March has to find March's figure, not today's.
     */
    @EntityGraph(attributePaths = {"employee"})
    @Query("""
            select c from Compensation c
            where c.tenantId = :tenantId
              and c.employee.id = :employeeId
              and c.effectiveFrom <= :on
              and (c.effectiveTo is null or c.effectiveTo >= :on)
            """)
    Optional<Compensation> findEffectiveOn(@Param("tenantId") UUID tenantId,
                                           @Param("employeeId") UUID employeeId,
                                           @Param("on") LocalDate on);
}
