package ai.dival.dip.modules.learning;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Courses, enrolments and the compliance view.
 *
 * <p>The catalogue and somebody's own record are open to members — people should be able to see
 * what training exists and what they hold. Creating courses and recording outcomes is HR's, and
 * the compliance report is deliberately narrower still: a list of who is unqualified is a list
 * that gets misread if it travels.
 */
@RestController
@RequestMapping("/api/v1/learning")
public class LearningController {

    private static final String HR_WRITE =
            "hasAnyRole('" + Roles.HR_ADMIN + "', '" + Roles.HR_MANAGER + "', '"
                    + Roles.TENANT_ADMIN + "')";

    private static final String COMPLIANCE =
            "hasAnyRole('" + Roles.HR_ADMIN + "', '" + Roles.COMPLIANCE_OFFICER + "', '"
                    + Roles.TENANT_ADMIN + "')";

    private final LearningService learning;
    private final CurrentUserService currentUser;

    public LearningController(LearningService learning, CurrentUserService currentUser) {
        this.learning = learning;
        this.currentUser = currentUser;
    }

    // --- courses -----------------------------------------------------------

    @GetMapping("/courses")
    public List<CourseResponse> courses(
            @RequestParam(defaultValue = "true") boolean activeOnly) {
        return (activeOnly ? learning.activeCourses() : learning.listCourses())
                .stream().map(CourseResponse::from).toList();
    }

    @GetMapping("/courses/{id}")
    public CourseResponse course(@PathVariable UUID id) {
        return CourseResponse.from(learning.course(id));
    }

    @PostMapping("/courses")
    @PreAuthorize(HR_WRITE)
    public ResponseEntity<CourseResponse> createCourse(
            @Valid @RequestBody CreateCourseRequest r) {
        Course created = learning.createCourse(r.code(), r.title(), r.deliveryMode(),
                r.description(), r.provider(), r.durationMinutes(), r.mandatory(),
                r.validityMonths(), r.passScore(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(CourseResponse.from(created));
    }

    @PostMapping("/courses/{id}/retire")
    @PreAuthorize(HR_WRITE)
    public CourseResponse retireCourse(@PathVariable UUID id) {
        return CourseResponse.from(learning.retireCourse(id, actorId()));
    }

    // --- enrolments --------------------------------------------------------

    @GetMapping("/employees/{employeeId}/enrolments")
    public List<EnrolmentResponse> enrolmentsFor(@PathVariable UUID employeeId) {
        return learning.enrolmentsFor(employeeId).stream().map(EnrolmentResponse::from).toList();
    }

    @GetMapping("/courses/{courseId}/enrolments")
    @PreAuthorize(HR_WRITE)
    public List<EnrolmentResponse> enrolmentsOn(@PathVariable UUID courseId) {
        return learning.enrolmentsOn(courseId).stream().map(EnrolmentResponse::from).toList();
    }

    @PostMapping("/enrolments")
    @PreAuthorize(HR_WRITE)
    public ResponseEntity<EnrolmentResponse> enrol(@Valid @RequestBody EnrolRequest r) {
        CourseEnrolment created = learning.enrol(r.employeeId(), r.courseId(), r.enrolledOn(),
                actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(EnrolmentResponse.from(created));
    }

    /** Starting is the learner's own act, so it sits behind no administrative role. */
    @PostMapping("/enrolments/{id}/start")
    public EnrolmentResponse start(@PathVariable UUID id) {
        return EnrolmentResponse.from(learning.start(id, actorId()));
    }

    @PostMapping("/enrolments/{id}/complete")
    @PreAuthorize(HR_WRITE)
    public EnrolmentResponse complete(@PathVariable UUID id,
                                      @RequestBody CompleteRequest r) {
        return EnrolmentResponse.from(
                learning.complete(id, r.completedOn(), r.score(), r.notes(), actorId()));
    }

    @PostMapping("/enrolments/{id}/withdraw")
    @PreAuthorize(HR_WRITE)
    public EnrolmentResponse withdraw(@PathVariable UUID id, @RequestBody ReasonRequest r) {
        return EnrolmentResponse.from(learning.withdraw(id, r.reason(), actorId()));
    }

    @PostMapping("/enrolments/{id}/certificate")
    @PreAuthorize(HR_WRITE)
    public EnrolmentResponse attachCertificate(@PathVariable UUID id,
                                               @Valid @RequestBody CertificateRequest r) {
        return EnrolmentResponse.from(learning.attachCertificate(id, r.fileId(), actorId()));
    }

    @PostMapping("/enrolments/{id}/renew")
    @PreAuthorize(HR_WRITE)
    public EnrolmentResponse renew(@PathVariable UUID id, @Valid @RequestBody RenewRequest r) {
        return EnrolmentResponse.from(learning.renew(id, r.expiresOn(), actorId()));
    }

    // --- compliance --------------------------------------------------------

    /**
     * Who does not currently hold a mandatory course.
     *
     * <p>Held to compliance and HR roles. A list of who is unqualified is a list that gets
     * misread the moment it travels beyond the people whose job it is to close the gaps.
     */
    @GetMapping("/compliance")
    @PreAuthorize(COMPLIANCE)
    public List<GapResponse> compliance(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        return learning.whoIsMissing(on).stream().map(GapResponse::from).toList();
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    // --- requests ----------------------------------------------------------

    public record CreateCourseRequest(
            @NotBlank String code,
            @NotBlank String title,
            @NotNull DeliveryMode deliveryMode,
            String description,
            String provider,
            Integer durationMinutes,
            boolean mandatory,
            Integer validityMonths,
            Integer passScore) {
    }

    public record EnrolRequest(
            @NotNull UUID employeeId,
            @NotNull UUID courseId,
            LocalDate enrolledOn) {
    }

    public record CompleteRequest(LocalDate completedOn, Integer score, String notes) {
    }

    public record ReasonRequest(String reason) {
    }

    public record CertificateRequest(@NotNull UUID fileId) {
    }

    public record RenewRequest(@NotNull LocalDate expiresOn) {
    }

    // --- responses ---------------------------------------------------------

    public record CourseResponse(
            UUID id,
            String code,
            String title,
            String description,
            String provider,
            DeliveryMode deliveryMode,
            Integer durationMinutes,
            boolean mandatory,
            Integer validityMonths,
            Integer passScore,
            boolean active) {

        static CourseResponse from(Course course) {
            return new CourseResponse(
                    course.getId(),
                    course.getCode(),
                    course.getTitle(),
                    course.getDescription(),
                    course.getProvider(),
                    course.getDeliveryMode(),
                    course.getDurationMinutes(),
                    course.isMandatory(),
                    course.getValidityMonths(),
                    course.getPassScore(),
                    course.isActive());
        }
    }

    public record EnrolmentResponse(
            UUID id,
            UUID employeeId,
            String employeeName,
            UUID courseId,
            String courseTitle,
            boolean mandatory,
            EnrolmentStatus status,
            LocalDate enrolledOn,
            Instant startedAt,
            LocalDate completedOn,
            Integer score,
            LocalDate expiresOn,
            UUID certificateFileId,
            String notes) {

        static EnrolmentResponse from(CourseEnrolment enrolment) {
            return new EnrolmentResponse(
                    enrolment.getId(),
                    enrolment.getEmployee().getId(),
                    enrolment.getEmployee().displayName(),
                    enrolment.getCourse().getId(),
                    enrolment.getCourse().getTitle(),
                    enrolment.getCourse().isMandatory(),
                    enrolment.getStatus(),
                    enrolment.getEnrolledOn(),
                    enrolment.getStartedAt(),
                    enrolment.getCompletedOn(),
                    enrolment.getScore(),
                    enrolment.getExpiresOn(),
                    enrolment.getCertificate() == null
                            ? null
                            : enrolment.getCertificate().getId(),
                    enrolment.getNotes());
        }
    }

    /** A mandatory course and the people who do not currently hold it. */
    public record GapResponse(
            UUID courseId,
            String courseTitle,
            int missingCount,
            List<MissingPerson> missing) {

        static GapResponse from(LearningService.ComplianceGap gap) {
            return new GapResponse(
                    gap.course().getId(),
                    gap.course().getTitle(),
                    gap.missing().size(),
                    gap.missing().stream()
                            .map(employee -> new MissingPerson(
                                    employee.getId(),
                                    employee.getEmployeeNumber(),
                                    employee.displayName()))
                            .toList());
        }
    }

    public record MissingPerson(UUID employeeId, String employeeNumber, String displayName) {
    }
}
