package ai.dival.dip.modules.leave;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaveBalanceRepository extends JpaRepository<LeaveBalance, UUID> {

    @EntityGraph(attributePaths = {"employee", "leaveType"})
    Optional<LeaveBalance> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = {"employee", "leaveType"})
    List<LeaveBalance> findByTenantIdAndEmployeeIdAndLeaveYear(
            UUID tenantId, UUID employeeId, int leaveYear);

    Optional<LeaveBalance> findByTenantIdAndEmployeeIdAndLeaveTypeIdAndLeaveYear(
            UUID tenantId, UUID employeeId, UUID leaveTypeId, int leaveYear);

    List<LeaveBalance> findByTenantIdAndLeaveYear(UUID tenantId, int leaveYear);

    /**
     * The same lookup, taking a write lock.
     *
     * <p>Used on the path that spends days. Optimistic locking would surface the race as a failure
     * the user has to retry; taking the row lock up front means two people submitting at once
     * queue rather than collide.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select b from LeaveBalance b
            where b.tenantId = :tenantId
              and b.employee.id = :employeeId
              and b.leaveType.id = :leaveTypeId
              and b.leaveYear = :leaveYear
            """)
    Optional<LeaveBalance> lockFor(@Param("tenantId") UUID tenantId,
                                   @Param("employeeId") UUID employeeId,
                                   @Param("leaveTypeId") UUID leaveTypeId,
                                   @Param("leaveYear") int leaveYear);
}
