package ai.dival.dip.modules.learning;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseEnrolmentRepository extends JpaRepository<CourseEnrolment, UUID> {

    @EntityGraph(attributePaths = {"employee", "course"})
    Optional<CourseEnrolment> findByIdAndTenantId(UUID id, UUID tenantId);

    @EntityGraph(attributePaths = {"employee", "course"})
    List<CourseEnrolment> findByTenantIdAndEmployeeIdOrderByEnrolledOnDesc(
            UUID tenantId, UUID employeeId);

    @EntityGraph(attributePaths = {"employee", "course"})
    List<CourseEnrolment> findByTenantIdAndCourseIdOrderByEnrolledOnDesc(
            UUID tenantId, UUID courseId);

    /** The live attempt, if there is one. Used to refuse a second enrolment on the same course. */
    @Query("""
            select e from CourseEnrolment e
            where e.tenantId = :tenantId
              and e.employee.id = :employeeId
              and e.course.id = :courseId
              and e.status in (ai.dival.dip.modules.learning.EnrolmentStatus.ENROLLED,
                               ai.dival.dip.modules.learning.EnrolmentStatus.IN_PROGRESS)
            """)
    Optional<CourseEnrolment> findLive(@Param("tenantId") UUID tenantId,
                                       @Param("employeeId") UUID employeeId,
                                       @Param("courseId") UUID courseId);

    /**
     * Qualifications past their date that have not been alerted yet.
     *
     * <p>The {@code expiryNotifiedAt} check is what stops a daily sweep chasing the same lapsed
     * certificate every morning until somebody books the refresher.
     */
    @EntityGraph(attributePaths = {"employee", "course"})
    @Query("""
            select e from CourseEnrolment e
            where e.tenantId = :tenantId
              and e.status = ai.dival.dip.modules.learning.EnrolmentStatus.COMPLETED
              and e.expiresOn is not null
              and e.expiresOn <= :cutoff
              and e.expiryNotifiedAt is null
            """)
    List<CourseEnrolment> findExpiringWithoutAlert(@Param("tenantId") UUID tenantId,
                                                   @Param("cutoff") LocalDate cutoff);

    /** Lapsed on a given day, for the sweep that moves them to EXPIRED. */
    @Query("""
            select e from CourseEnrolment e
            where e.tenantId = :tenantId
              and e.status = ai.dival.dip.modules.learning.EnrolmentStatus.COMPLETED
              and e.expiresOn is not null
              and e.expiresOn < :today
            """)
    List<CourseEnrolment> findLapsed(@Param("tenantId") UUID tenantId,
                                     @Param("today") LocalDate today);

    /**
     * Who currently holds a given course.
     *
     * <p>Currently, not ever: an expiry date in the past means the certificate no longer counts,
     * and the whole point of the compliance question is that those two are different.
     */
    @Query("""
            select e.employee.id from CourseEnrolment e
            where e.tenantId = :tenantId
              and e.course.id = :courseId
              and e.status = ai.dival.dip.modules.learning.EnrolmentStatus.COMPLETED
              and (e.expiresOn is null or e.expiresOn >= :on)
            """)
    List<UUID> findEmployeeIdsHolding(@Param("tenantId") UUID tenantId,
                                      @Param("courseId") UUID courseId,
                                      @Param("on") LocalDate on);
}
