package ai.dival.dip.modules.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@RequiresDocker
class LearningServiceTest extends AbstractIntegrationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 6, 1);

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private LearningService learning;

    private Employee employee;
    private Employee colleague;

    @BeforeEach
    void setUp() {
        UUID tenantId = tenants.save(new Tenant("LN", "ln-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);

        employee = employees.hire("EMP-001", "Didier", "Lokwa", LocalDate.of(2024, 2, 5),
                null, null);
        colleague = employees.hire("EMP-002", "Grâce", "Tshibangu", LocalDate.of(2023, 3, 6),
                null, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    /** A safety ticket: mandatory, expires after two years, and has a pass mark. */
    private Course safetyTicket() {
        return learning.createCourse("TOWER-SAFETY", "Tower climbing safety",
                DeliveryMode.CLASSROOM, null, "National Safety Board", 480,
                true, 24, 70, null);
    }

    private Course optionalCourse() {
        return learning.createCourse("SQL-101", "Introduction to SQL", DeliveryMode.ONLINE,
                null, null, 120, false, null, null, null);
    }

    // --- courses -----------------------------------------------------------

    @Test
    @DisplayName("course codes are normalised and unique within a tenant")
    void normalisesCourseCode() {
        Course course = learning.createCourse("  tower safety ", "Tower climbing safety",
                DeliveryMode.CLASSROOM, null, null, null, true, 24, null, null);

        assertThat(course.getCode()).isEqualTo("TOWER-SAFETY");
        assertThatThrownBy(() -> learning.createCourse("tower-safety", "Another",
                DeliveryMode.ONLINE, null, null, null, false, null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a retired course takes no new bookings")
    void refusesEnrolmentOnRetiredCourse() {
        Course course = optionalCourse();
        learning.retireCourse(course.getId(), null);

        assertThatThrownBy(() ->
                learning.enrol(employee.getId(), course.getId(), TODAY, null))
                .isInstanceOf(ConflictException.class);
    }

    // --- attempts ----------------------------------------------------------

    @Test
    @DisplayName("a pass sets an expiry from the course's validity")
    void passSetsExpiry() {
        Course course = safetyTicket();
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(), TODAY, null);

        learning.complete(enrolment.getId(), TODAY, 85, null, null);

        assertThat(enrolment.getStatus()).isEqualTo(EnrolmentStatus.COMPLETED);
        // Two years from completion, not from enrolment.
        assertThat(enrolment.getExpiresOn()).isEqualTo(TODAY.plusMonths(24));
    }

    @Test
    @DisplayName("a course with no validity period never expires")
    void noValidityMeansNoExpiry() {
        Course course = optionalCourse();
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(), TODAY, null);

        learning.complete(enrolment.getId(), TODAY, null, null, null);

        assertThat(enrolment.getStatus()).isEqualTo(EnrolmentStatus.COMPLETED);
        assertThat(enrolment.getExpiresOn()).isNull();
    }

    @Test
    @DisplayName("the course decides what counts as a pass, not the caller")
    void courseDecidesThePass() {
        Course course = safetyTicket();
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(), TODAY, null);

        learning.complete(enrolment.getId(), TODAY, 65, "Missed the rescue module", null);

        assertThat(enrolment.getStatus()).isEqualTo(EnrolmentStatus.FAILED);
        assertThat(enrolment.getExpiresOn()).isNull();
    }

    @Test
    @DisplayName("a failed attempt stays and does not block a second one")
    void failureIsKeptAndRetryable() {
        Course course = safetyTicket();
        CourseEnrolment first = learning.enrol(employee.getId(), course.getId(), TODAY, null);
        learning.complete(first.getId(), TODAY, 40, "Did not pass", null);

        CourseEnrolment second = learning.enrol(employee.getId(), course.getId(),
                TODAY.plusMonths(1), null);
        learning.complete(second.getId(), TODAY.plusMonths(1), 90, null, null);

        // Both rows survive, which is what lets somebody tell first-time from fourth-time.
        assertThat(learning.enrolmentsFor(employee.getId())).hasSize(2);
        assertThat(first.getStatus()).isEqualTo(EnrolmentStatus.FAILED);
        assertThat(second.getStatus()).isEqualTo(EnrolmentStatus.COMPLETED);
    }

    @Test
    @DisplayName("nobody is booked on the same course twice at once")
    void refusesSecondLiveEnrolment() {
        Course course = optionalCourse();
        learning.enrol(employee.getId(), course.getId(), TODAY, null);

        assertThatThrownBy(() ->
                learning.enrol(employee.getId(), course.getId(), TODAY, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a course cannot be completed before it was begun")
    void refusesCompletionBeforeEnrolment() {
        Course course = optionalCourse();
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(), TODAY, null);

        assertThatThrownBy(() ->
                learning.complete(enrolment.getId(), TODAY.minusDays(1), null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a finished attempt cannot be completed again")
    void refusesSecondCompletion() {
        Course course = optionalCourse();
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(), TODAY, null);
        learning.complete(enrolment.getId(), TODAY, null, null, null);

        assertThatThrownBy(() ->
                learning.complete(enrolment.getId(), TODAY, null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    // --- expiry ------------------------------------------------------------

    @Test
    @DisplayName("a lapsed qualification is swept to expired")
    void lapsedIsSwept() {
        Course course = safetyTicket();
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(), TODAY, null);
        learning.complete(enrolment.getId(), TODAY, 85, null, null);

        // Still valid the day before it lapses.
        assertThat(learning.expireLapsed(TODAY.plusMonths(24))).isZero();
        assertThat(enrolment.getStatus()).isEqualTo(EnrolmentStatus.COMPLETED);

        assertThat(learning.expireLapsed(TODAY.plusMonths(25))).isEqualTo(1);
        assertThat(enrolment.getStatus()).isEqualTo(EnrolmentStatus.EXPIRED);
    }

    @Test
    @DisplayName("renewing a lapsed certificate puts it back and lets the alert fire again")
    void renewalRestoresTheQualification() {
        Course course = safetyTicket();
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(), TODAY, null);
        learning.complete(enrolment.getId(), TODAY, 85, null, null);
        enrolment.markExpiryNotified();
        learning.expireLapsed(TODAY.plusMonths(25));

        learning.renew(enrolment.getId(), TODAY.plusMonths(48), null);

        assertThat(enrolment.getStatus()).isEqualTo(EnrolmentStatus.COMPLETED);
        assertThat(enrolment.getExpiresOn()).isEqualTo(TODAY.plusMonths(48));
        assertThat(enrolment.getExpiryNotifiedAt()).isNull();
    }

    @Test
    @DisplayName("shortening a course's validity does not invalidate certificates already held")
    void validityChangeIsNotRetrospective() {
        Course course = safetyTicket();
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(), TODAY, null);
        learning.complete(enrolment.getId(), TODAY, 85, null, null);

        // The policy tightens to one year.
        course.setPolicy(true, 12, 70);

        // Somebody who already passed keeps the two years they were told they had.
        assertThat(enrolment.getExpiresOn()).isEqualTo(TODAY.plusMonths(24));
    }

    // --- compliance --------------------------------------------------------

    @Test
    @DisplayName("everybody without a mandatory course shows as missing")
    void mandatoryCourseListsEverybodyMissing() {
        safetyTicket();

        var gaps = learning.whoIsMissing(TODAY);

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).missing()).hasSize(2);
    }

    @Test
    @DisplayName("an optional course never appears in the compliance report")
    void optionalCoursesAreNotCompliance() {
        optionalCourse();

        assertThat(learning.whoIsMissing(TODAY)).isEmpty();
    }

    @Test
    @DisplayName("completing a mandatory course clears the gap")
    void completionClearsTheGap() {
        Course course = safetyTicket();
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(), TODAY, null);
        learning.complete(enrolment.getId(), TODAY, 85, null, null);

        var gaps = learning.whoIsMissing(TODAY);

        assertThat(gaps).hasSize(1);
        assertThat(gaps.get(0).missing()).extracting(Employee::getId)
                .containsExactly(colleague.getId());
    }

    @Test
    @DisplayName("a lapsed certificate counts as missing, not as trained")
    void lapsedCountsAsMissing() {
        Course course = safetyTicket();
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(), TODAY, null);
        learning.complete(enrolment.getId(), TODAY, 85, null, null);

        // On the day it lapses they still hold it; a month later they do not.
        assertThat(learning.whoIsMissing(TODAY.plusMonths(24)).get(0).missing())
                .extracting(Employee::getId).containsExactly(colleague.getId());

        // Somebody whose ticket expired is exactly as unqualified as somebody who never sat it.
        assertThat(learning.whoIsMissing(TODAY.plusMonths(25)).get(0).missing())
                .extracting(Employee::getId)
                .containsExactlyInAnyOrder(employee.getId(), colleague.getId());
    }

    @Test
    @DisplayName("a failed attempt does not count as holding the qualification")
    void failureIsNotCompliance() {
        Course course = safetyTicket();
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(), TODAY, null);
        learning.complete(enrolment.getId(), TODAY, 30, "Did not pass", null);

        assertThat(learning.whoIsMissing(TODAY).get(0).missing()).hasSize(2);
    }

    @Test
    @DisplayName("people who have left are not chased for refreshers")
    void leaversAreNotCounted() {
        safetyTicket();
        employees.terminate(colleague.getId(), TODAY, null);

        var gaps = learning.whoIsMissing(TODAY);

        assertThat(gaps.get(0).missing()).extracting(Employee::getId)
                .containsExactly(employee.getId());
    }

    @Test
    @DisplayName("one tenant's training records are invisible to another")
    void enrolmentsDoNotCrossTenants() {
        Course course = optionalCourse();
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(), TODAY, null);

        UUID tenantB = tenants.save(new Tenant("LN B", "ln-b-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantB);

        assertThatThrownBy(() -> learning.enrolment(enrolment.getId()))
                .isInstanceOf(LearningService.EnrolmentNotFoundException.class);
    }
}
