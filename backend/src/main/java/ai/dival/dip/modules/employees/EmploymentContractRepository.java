package ai.dival.dip.modules.employees;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmploymentContractRepository extends JpaRepository<EmploymentContract, UUID> {

    @EntityGraph(attributePaths = {"employee", "orgUnit", "probationDecidedBy"})
    Optional<EmploymentContract> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = {"employee", "orgUnit", "probationDecidedBy"})
    List<EmploymentContract> findByTenantIdAndEmployeeIdOrderByStartDateDesc(
            UUID tenantId, UUID employeeId);

    Optional<EmploymentContract> findByTenantIdAndEmployeeIdAndStatus(
            UUID tenantId, UUID employeeId, ContractStatus status);

    List<EmploymentContract> findByTenantIdAndStatus(UUID tenantId, ContractStatus status);

    /**
     * Running contracts whose end date falls on or before a cutoff and that have not already had
     * an alert raised.
     *
     * <p>The {@code expiry_notified_at} check is what stops a daily scan re-notifying the same
     * contract every morning until somebody deals with it.
     */
    @Query("""
            select c from EmploymentContract c
            where c.tenantId = :tenantId
              and c.status = ai.dival.dip.modules.employees.ContractStatus.ACTIVE
              and c.endDate is not null
              and c.endDate <= :cutoff
              and c.expiryNotifiedAt is null
            """)
    List<EmploymentContract> findExpiringWithoutAlert(
            @Param("tenantId") UUID tenantId, @Param("cutoff") LocalDate cutoff);

    /**
     * Running contracts whose probation is ending with no decision recorded, not yet alerted.
     *
     * <p>The window is deliberately narrower than the contract-expiry one. A probation decision
     * has to land before the period ends: after it, in most jurisdictions, the answer has already
     * been given by silence.
     */
    @Query("""
            select c from EmploymentContract c
            where c.tenantId = :tenantId
              and c.status = ai.dival.dip.modules.employees.ContractStatus.ACTIVE
              and c.probationEndDate is not null
              and c.probationEndDate <= :cutoff
              and c.probationOutcome is null
              and c.probationNotifiedAt is null
            """)
    List<EmploymentContract> findProbationEndingWithoutAlert(
            @Param("tenantId") UUID tenantId, @Param("cutoff") LocalDate cutoff);
}
