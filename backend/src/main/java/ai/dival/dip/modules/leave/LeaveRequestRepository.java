package ai.dival.dip.modules.leave;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID> {

    Optional<LeaveRequest> findByIdAndTenantId(UUID id, UUID tenantId);

    List<LeaveRequest> findByTenantIdAndEmployeeIdOrderByStartDateDesc(
            UUID tenantId, UUID employeeId);

    List<LeaveRequest> findByTenantIdAndStatusOrderByStartDateAsc(
            UUID tenantId, LeaveRequestStatus status);

    /**
     * Live requests for one person that touch a date range.
     *
     * <p>Used to refuse a request that overlaps one already in flight. Booking the same week
     * twice is almost always a mistake, and catching it at submission is kinder than catching it
     * in payroll.
     */
    @Query("""
            select r from LeaveRequest r
            where r.tenantId = :tenantId
              and r.employee.id = :employeeId
              and r.status in (ai.dival.dip.modules.leave.LeaveRequestStatus.SUBMITTED,
                               ai.dival.dip.modules.leave.LeaveRequestStatus.APPROVED)
              and r.startDate <= :end
              and r.endDate >= :start
            """)
    List<LeaveRequest> findLiveOverlapping(@Param("tenantId") UUID tenantId,
                                           @Param("employeeId") UUID employeeId,
                                           @Param("start") LocalDate start,
                                           @Param("end") LocalDate end);

    /** Who is off, across the whole tenant. Drives the team calendar and cover planning. */
    @Query("""
            select r from LeaveRequest r
            where r.tenantId = :tenantId
              and r.status = ai.dival.dip.modules.leave.LeaveRequestStatus.APPROVED
              and r.startDate <= :end
              and r.endDate >= :start
            order by r.startDate asc
            """)
    List<LeaveRequest> findApprovedBetween(@Param("tenantId") UUID tenantId,
                                           @Param("start") LocalDate start,
                                           @Param("end") LocalDate end);
}
