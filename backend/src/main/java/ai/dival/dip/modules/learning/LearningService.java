package ai.dival.dip.modules.learning;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.files.FileService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Courses, enrolments, and the compliance question underneath them.
 *
 * <p>The catalogue is the easy half. The half that earns the module its place is
 * {@link #whoIsMissing}, which answers "who is not allowed to climb a tower next week" — and that
 * needs to count a lapsed certificate as missing, because a certificate that has expired is not a
 * qualification, however good the training was at the time.
 */
@Service
public class LearningService {

    private final CourseRepository courses;
    private final CourseEnrolmentRepository enrolments;
    private final EmployeeService employees;
    private final FileService files;
    private final AuditService audit;

    public LearningService(CourseRepository courses, CourseEnrolmentRepository enrolments,
                           EmployeeService employees, FileService files, AuditService audit) {
        this.courses = courses;
        this.enrolments = enrolments;
        this.employees = employees;
        this.files = files;
        this.audit = audit;
    }

    // --- courses -----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Course> listCourses() {
        return courses.findByTenantIdOrderByTitleAsc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public List<Course> activeCourses() {
        return courses.findByTenantIdAndActiveTrueOrderByTitleAsc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public Course course(UUID id) {
        return courses.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new CourseNotFoundException(id));
    }

    @Transactional
    public Course createCourse(String code, String title, DeliveryMode mode, String description,
                               String provider, Integer durationMinutes, boolean mandatory,
                               Integer validityMonths, Integer passScore, UUID actorId) {
        UUID tenantId = TenantContext.require();
        String normalized = Course.normalizeCode(code);

        if (normalized.isBlank()) {
            throw new IllegalArgumentException("A course code is required");
        }
        if (courses.findByTenantIdAndCode(tenantId, normalized).isPresent()) {
            throw new ConflictException("Course code already in use: " + normalized);
        }

        Course course = new Course(normalized, title, mode);
        course.describe(description, provider, durationMinutes);
        course.setPolicy(mandatory, validityMonths, passScore);

        Course saved = courses.save(course);
        audit.recordSuccess("COURSE_CREATED", "Course", saved.getId().toString(), actorId);
        return saved;
    }

    @Transactional
    public Course retireCourse(UUID id, UUID actorId) {
        Course course = course(id);
        course.retire();
        audit.recordSuccess("COURSE_RETIRED", "Course", id.toString(), actorId);
        return course;
    }

    // --- enrolments --------------------------------------------------------

    @Transactional(readOnly = true)
    public CourseEnrolment enrolment(UUID id) {
        return enrolments.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new EnrolmentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<CourseEnrolment> enrolmentsFor(UUID employeeId) {
        employees.get(employeeId);
        return enrolments.findByTenantIdAndEmployeeIdOrderByEnrolledOnDesc(
                TenantContext.require(), employeeId);
    }

    @Transactional(readOnly = true)
    public List<CourseEnrolment> enrolmentsOn(UUID courseId) {
        course(courseId);
        return enrolments.findByTenantIdAndCourseIdOrderByEnrolledOnDesc(
                TenantContext.require(), courseId);
    }

    /**
     * Books somebody onto a course.
     *
     * <p>Refuses a second live attempt, but not a fresh one after a failure or a lapse — those
     * are exactly the cases where somebody needs to sit it again.
     */
    @Transactional
    public CourseEnrolment enrol(UUID employeeId, UUID courseId, LocalDate enrolledOn,
                                 UUID actorId) {
        UUID tenantId = TenantContext.require();
        Employee employee = employees.get(employeeId);
        Course course = course(courseId);

        if (!course.isActive()) {
            throw new ConflictException("This course has been retired");
        }
        enrolments.findLive(tenantId, employeeId, courseId).ifPresent(existing -> {
            throw new ConflictException("This person is already booked on this course");
        });

        CourseEnrolment saved = enrolments.save(
                new CourseEnrolment(employee, course, enrolledOn));
        audit.recordSuccess("COURSE_ENROLLED", "CourseEnrolment",
                saved.getId().toString(), actorId);
        return saved;
    }

    @Transactional
    public CourseEnrolment start(UUID id, UUID actorId) {
        CourseEnrolment enrolment = enrolment(id);
        enrolment.start();
        audit.recordSuccess("COURSE_STARTED", "CourseEnrolment", id.toString(), actorId);
        return enrolment;
    }

    /**
     * Records the outcome.
     *
     * <p>Whether the score is a pass is the course's decision. Letting the caller declare it would
     * mean the same mark meaning different things depending on who typed it in.
     */
    @Transactional
    public CourseEnrolment complete(UUID id, LocalDate completedOn, Integer score, String notes,
                                    UUID actorId) {
        CourseEnrolment enrolment = enrolment(id);
        enrolment.complete(completedOn, score, notes);
        audit.recordSuccess("COURSE_" + enrolment.getStatus(), "CourseEnrolment",
                id.toString(), actorId);
        return enrolment;
    }

    @Transactional
    public CourseEnrolment withdraw(UUID id, String reason, UUID actorId) {
        CourseEnrolment enrolment = enrolment(id);
        enrolment.withdraw(reason);
        audit.recordSuccess("COURSE_WITHDRAWN", "CourseEnrolment", id.toString(), actorId);
        return enrolment;
    }

    @Transactional
    public CourseEnrolment attachCertificate(UUID id, UUID fileId, UUID actorId) {
        CourseEnrolment enrolment = enrolment(id);
        enrolment.attachCertificate(files.metadata(fileId));
        audit.recordSuccess("COURSE_CERTIFICATE_ATTACHED", "CourseEnrolment",
                id.toString(), actorId);
        return enrolment;
    }

    @Transactional
    public CourseEnrolment renew(UUID id, LocalDate newExpiry, UUID actorId) {
        CourseEnrolment enrolment = enrolment(id);
        enrolment.renewUntil(newExpiry);
        audit.recordSuccess("COURSE_RENEWED", "CourseEnrolment", id.toString(), actorId);
        return enrolment;
    }

    /**
     * Moves lapsed qualifications to expired.
     *
     * <p>Swept rather than derived on read, so a lapsed certificate reads the same on every screen
     * instead of depending on which code path asked.
     */
    @Transactional
    public int expireLapsed(LocalDate today) {
        List<CourseEnrolment> lapsed = enrolments.findLapsed(TenantContext.require(), today);
        lapsed.forEach(CourseEnrolment::expire);
        return lapsed.size();
    }

    // --- compliance --------------------------------------------------------

    /**
     * Who does not currently hold a mandatory course.
     *
     * <p>Counts a lapsed certificate as missing, which is the entire point: somebody whose
     * high-voltage ticket expired last month is exactly as unqualified as somebody who never sat
     * the course, and a report that showed them as trained would be worse than no report.
     *
     * <p>Only employed people are counted. Chasing a leaver for a refresher is noise that makes
     * the real gaps harder to see.
     */
    @Transactional(readOnly = true)
    public List<ComplianceGap> whoIsMissing(LocalDate on) {
        UUID tenantId = TenantContext.require();
        LocalDate day = on == null ? LocalDate.now() : on;

        List<Employee> workforce = employees.list().stream()
                .filter(employee -> employee.getStatus().isEmployed())
                .toList();

        List<ComplianceGap> gaps = new ArrayList<>();
        for (Course course : courses.findByTenantIdAndMandatoryTrueAndActiveTrueOrderByTitleAsc(
                tenantId)) {
            Set<UUID> holders = Set.copyOf(
                    enrolments.findEmployeeIdsHolding(tenantId, course.getId(), day));

            List<Employee> missing = workforce.stream()
                    .filter(employee -> !holders.contains(employee.getId()))
                    .toList();

            if (!missing.isEmpty()) {
                gaps.add(new ComplianceGap(course, missing));
            }
        }
        return gaps;
    }

    /** A mandatory course, and the people who do not currently hold it. */
    public record ComplianceGap(Course course, List<Employee> missing) {
    }

    public static class CourseNotFoundException extends ResourceNotFoundException {
        public CourseNotFoundException(UUID id) {
            super("Course not found: " + id);
        }
    }

    public static class EnrolmentNotFoundException extends ResourceNotFoundException {
        public EnrolmentNotFoundException(UUID id) {
            super("Enrolment not found: " + id);
        }
    }
}
