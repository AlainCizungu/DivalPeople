package ai.dival.dip.modules.performance;

import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.employees.CurrentEmployee;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Goals, cycles, reviews and feedback.
 *
 * <p>Every response that carries a review goes through the entity's own visibility methods rather
 * than reading the fields directly, so the blind-write rule cannot be bypassed by adding an
 * endpoint. Which side is asking decides how much of it is filled in.
 *
 * <p>That used to be a request parameter the caller set. It no longer is: {@link CurrentEmployee}
 * resolves it from the token by comparing the caller's own employee record to the review's
 * subject. An access rule the caller chooses is not an access rule, and anybody who wanted a
 * reviewer's unshared rating only had to ask for it with the flag turned off.
 */
@RestController
@RequestMapping("/api/v1/performance")
public class PerformanceController {

    private static final String HR_WRITE =
            "hasAnyRole('" + Roles.HR_ADMIN + "', '" + Roles.HR_MANAGER + "', '"
                    + Roles.TENANT_ADMIN + "')";

    private static final String MANAGE =
            "hasAnyRole('" + Roles.MANAGER + "', '" + Roles.HR_ADMIN + "', '"
                    + Roles.HR_MANAGER + "', '" + Roles.TENANT_ADMIN + "')";

    /**
     * Any signed-in employee may reach these, because the subject and the reviewer are ordinary
     * employees. <strong>The role is not the check.</strong> Ownership is enforced in
     * {@link PerformanceService}, which compares the caller to the review's subject and reviewer.
     *
     * <p>Written out rather than left off, because leaving it off is how the August 2026 review
     * found any employee reading and rewriting a colleague's appraisal.
     */
    private static final String SUBJECT_OR_REVIEWER = "isAuthenticated()";

    private final PerformanceService performance;
    private final CurrentUserService currentUser;
    private final CurrentEmployee currentEmployee;

    public PerformanceController(PerformanceService performance,
                                 CurrentUserService currentUser,
                                 CurrentEmployee currentEmployee) {
        this.performance = performance;
        this.currentUser = currentUser;
        this.currentEmployee = currentEmployee;
    }


    /**
     * Refuses a caller who is neither the person the review is about, nor their reviewer, nor
     * management.
     *
     * <p>This is the check that was missing. {@code @PreAuthorize} can express "an employee" but
     * not "<em>this</em> employee", so the role annotation on these endpoints admits the whole
     * tenant and this narrows it to the two people involved.
     *
     * <p>It lives in the controller rather than the service on purpose: a caller only exists at
     * the HTTP boundary. Seeders and scheduled work call the service directly and legitimately
     * act for nobody, and a service-layer check would either refuse them or need a "no
     * authenticated user means allow" branch — which is the wrong default to write down anywhere.
     *
     * <p>The refusal does not say whether the review exists.
     */
    private PerformanceReview requireSubjectOrReviewer(UUID reviewId) {
        PerformanceReview review = performance.review(reviewId);
        UUID reviewerId = review.getReviewer() == null ? null : review.getReviewer().getId();

        if (managesPeople()
                || currentEmployee.isSelf(review.getEmployee().getId())
                || currentEmployee.isSelf(reviewerId)) {
            return review;
        }
        throw new PerformanceService.ReviewNotFoundException(reviewId);
    }

    /** As above, for endpoints that name an employee rather than a review. */
    private void requireSelfOrManager(UUID employeeId) {
        if (!managesPeople() && !currentEmployee.isSelf(employeeId)) {
            throw new AccessRefusedException("Not the subject and not a manager");
        }
    }

    private boolean managesPeople() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(MANAGEMENT_AUTHORITIES::contains);
    }

    private static final Set<String> MANAGEMENT_AUTHORITIES = Set.of(
            "ROLE_" + Roles.MANAGER, "ROLE_" + Roles.HR_ADMIN,
            "ROLE_" + Roles.HR_MANAGER, "ROLE_" + Roles.TENANT_ADMIN);


    /**
     * Refuses anyone but the person the review is about.
     *
     * <p>Narrower than {@link #requireSubjectOrReviewer} on purpose: a self-assessment is the
     * employee's own account of their year, and a manager writing it for them — or a colleague
     * filing a disagreement in their name — is precisely the abuse this exists to stop. Being
     * senior does not make it your text.
     */
    private void requireSubject(UUID reviewId) {
        if (!currentEmployee.isSelf(performance.review(reviewId).getEmployee().getId())) {
            throw new AccessRefusedException("Only the subject may write their own assessment");
        }
    }

    // --- cycles ------------------------------------------------------------

    @GetMapping("/cycles")
    @PreAuthorize(MANAGE)
    public List<CycleResponse> cycles() {
        return performance.listCycles().stream().map(CycleResponse::from).toList();
    }

    @PostMapping("/cycles")
    @PreAuthorize(HR_WRITE)
    public ResponseEntity<CycleResponse> createCycle(@Valid @RequestBody CreateCycleRequest r) {
        ReviewCycle created = performance.createCycle(r.name(), r.periodStart(), r.periodEnd(),
                r.dueOn(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(CycleResponse.from(created));
    }

    @PostMapping("/cycles/{id}/open")
    @PreAuthorize(HR_WRITE)
    public CycleResponse openCycle(@PathVariable UUID id) {
        return CycleResponse.from(performance.openCycle(id, actorId()));
    }

    @PostMapping("/cycles/{id}/close")
    @PreAuthorize(HR_WRITE)
    public CycleResponse closeCycle(@PathVariable UUID id) {
        return CycleResponse.from(performance.closeCycle(id, actorId()));
    }

    // --- goals -------------------------------------------------------------

    @GetMapping("/employees/{employeeId}/goals")
    @PreAuthorize(SUBJECT_OR_REVIEWER)
    public List<GoalResponse> goals(@PathVariable UUID employeeId) {
        requireSelfOrManager(employeeId);
        return performance.goalsFor(employeeId).stream().map(GoalResponse::from).toList();
    }

    @PostMapping("/goals")
    @PreAuthorize(MANAGE)
    public ResponseEntity<GoalResponse> createGoal(@Valid @RequestBody CreateGoalRequest r) {
        Goal created = performance.createGoal(r.employeeId(), r.title(), r.description(),
                r.measure(), r.weight(), r.targetDate(), r.cycleId(), r.supportsGoalId(),
                actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(GoalResponse.from(created));
    }

    @PostMapping("/goals/{id}/activate")
    @PreAuthorize(MANAGE)
    public GoalResponse activateGoal(@PathVariable UUID id) {
        return GoalResponse.from(performance.activateGoal(id, actorId()));
    }

    @PostMapping("/goals/{id}/progress")
    @PreAuthorize(SUBJECT_OR_REVIEWER)
    public GoalResponse recordProgress(@PathVariable UUID id,
                                       @Valid @RequestBody ProgressRequest r) {
        return GoalResponse.from(performance.recordProgress(id, r.progressPercent(), actorId()));
    }

    @PostMapping("/goals/{id}/close")
    @PreAuthorize(MANAGE)
    public GoalResponse closeGoal(@PathVariable UUID id, @Valid @RequestBody CloseGoalRequest r) {
        return GoalResponse.from(performance.closeGoal(id, r.outcome(), r.notes(), actorId()));
    }

    /** A null parent detaches the goal from whatever it was supporting. */
    @PostMapping("/goals/{id}/supports")
    @PreAuthorize(MANAGE)
    public GoalResponse supportGoal(@PathVariable UUID id, @RequestBody SupportsRequest r) {
        return GoalResponse.from(performance.supportGoal(id, r.supportsGoalId(), actorId()));
    }

    // --- reviews -----------------------------------------------------------

    @GetMapping("/cycles/{cycleId}/reviews")
    @PreAuthorize(MANAGE)
    public List<ReviewResponse> reviewsInCycle(@PathVariable UUID cycleId) {
        return performance.reviewsInCycle(cycleId).stream()
                .map(review -> ReviewResponse.from(review, false)).toList();
    }

    @GetMapping("/employees/{employeeId}/reviews")
    @PreAuthorize(SUBJECT_OR_REVIEWER)
    public List<ReviewResponse> reviewsFor(@PathVariable UUID employeeId) {
        requireSelfOrManager(employeeId);
        boolean asSubject = currentEmployee.isSelf(employeeId);
        return performance.reviewsFor(employeeId).stream()
                .map(review -> ReviewResponse.from(review, asSubject)).toList();
    }

    @GetMapping("/reviewers/{reviewerId}/reviews")
    @PreAuthorize(MANAGE)
    public List<ReviewResponse> reviewsToWrite(@PathVariable UUID reviewerId) {
        return performance.reviewsToWrite(reviewerId).stream()
                .map(review -> ReviewResponse.from(review, false)).toList();
    }

    @GetMapping("/reviews/{id}")
    @PreAuthorize(SUBJECT_OR_REVIEWER)
    public ReviewResponse review(@PathVariable UUID id) {
        PerformanceReview found = requireSubjectOrReviewer(id);
        return ReviewResponse.from(found, currentEmployee.isSelf(found.getEmployee().getId()));
    }

    @PostMapping("/reviews")
    @PreAuthorize(MANAGE)
    public ResponseEntity<ReviewResponse> openReview(@Valid @RequestBody OpenReviewRequest r) {
        PerformanceReview opened = performance.openReview(r.cycleId(), r.employeeId(),
                r.reviewerId(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ReviewResponse.from(opened, false));
    }

    @PostMapping("/reviews/{id}/self")
    @PreAuthorize(SUBJECT_OR_REVIEWER)
    public ReviewResponse saveSelf(@PathVariable UUID id, @RequestBody AssessmentRequest r) {
        requireSubject(id);
        return ReviewResponse.from(
                performance.saveSelfAssessment(id, r.text(), actorId()), true);
    }

    @PostMapping("/reviews/{id}/self/submit")
    @PreAuthorize(SUBJECT_OR_REVIEWER)
    public ReviewResponse submitSelf(@PathVariable UUID id) {
        requireSubject(id);
        return ReviewResponse.from(performance.submitSelfAssessment(id, actorId()), true);
    }

    @PostMapping("/reviews/{id}/reviewer")
    @PreAuthorize(MANAGE)
    public ReviewResponse saveReviewer(@PathVariable UUID id,
                                       @RequestBody ReviewerAssessmentRequest r) {
        return ReviewResponse.from(
                performance.saveReviewerAssessment(id, r.text(), r.rating(), actorId()), false);
    }

    @PostMapping("/reviews/{id}/reviewer/submit")
    @PreAuthorize(MANAGE)
    public ReviewResponse submitReviewer(@PathVariable UUID id) {
        return ReviewResponse.from(performance.submitReviewerAssessment(id, actorId()), false);
    }

    @PostMapping("/reviews/{id}/calibrate")
    @PreAuthorize(HR_WRITE)
    public ReviewResponse calibrate(@PathVariable UUID id,
                                    @Valid @RequestBody CalibrateRequest r) {
        return ReviewResponse.from(
                performance.calibrate(id, r.rating(), r.notes(), actorId()), false);
    }

    @PostMapping("/reviews/{id}/share")
    @PreAuthorize(MANAGE)
    public ReviewResponse share(@PathVariable UUID id) {
        return ReviewResponse.from(performance.share(id, actorId()), false);
    }

    /** The employee's own act, so it sits behind no management role. */
    @PostMapping("/reviews/{id}/acknowledge")
    @PreAuthorize(SUBJECT_OR_REVIEWER)
    public ReviewResponse acknowledge(@PathVariable UUID id,
                                      @RequestBody AcknowledgeRequest r) {
        requireSubject(id);
        return ReviewResponse.from(
                performance.acknowledge(id, r.response(), r.disagrees(), actorId()), true);
    }

    // --- feedback ----------------------------------------------------------

    @GetMapping("/reviews/{id}/feedback")
    @PreAuthorize(SUBJECT_OR_REVIEWER)
    public List<FeedbackResponse> feedback(@PathVariable UUID id) {
        requireSubjectOrReviewer(id);
        // Anonymity is decided by who is reading, so it is read from the token like everything
        // else. A colleague who asked not to be named must not be named by a query string.
        boolean asSubject = currentEmployee.isSelf(performance.review(id).getEmployee().getId());
        return performance.feedbackFor(id).stream()
                .map(given -> FeedbackResponse.from(given, asSubject)).toList();
    }

    @PostMapping("/reviews/{id}/feedback")
    @PreAuthorize(SUBJECT_OR_REVIEWER)
    public ResponseEntity<FeedbackResponse> addFeedback(
            @PathVariable UUID id, @Valid @RequestBody FeedbackRequest r) {
        requireSubjectOrReviewer(id);
        // The author is the caller, never a field in the request. Trusting the body here let
        // anyone put words in a colleague's mouth, attributed and on the record.
        ReviewFeedback added = performance.addFeedback(id, currentEmployee.requireId(),
                r.relationship(), r.comments(), r.attributed(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(FeedbackResponse.from(added, false));
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    // --- requests ----------------------------------------------------------

    public record CreateCycleRequest(
            @NotBlank String name,
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd,
            LocalDate dueOn) {
    }

    public record CreateGoalRequest(
            @NotNull UUID employeeId,
            @NotBlank String title,
            String description,
            String measure,
            BigDecimal weight,
            LocalDate targetDate,
            UUID cycleId,
            UUID supportsGoalId) {
    }

    public record ProgressRequest(int progressPercent) {
    }

    public record CloseGoalRequest(@NotNull GoalStatus outcome, String notes) {
    }

    public record SupportsRequest(UUID supportsGoalId) {
    }

    public record OpenReviewRequest(
            @NotNull UUID cycleId,
            @NotNull UUID employeeId,
            UUID reviewerId) {
    }

    public record AssessmentRequest(String text) {
    }

    public record ReviewerAssessmentRequest(String text, Rating rating) {
    }

    public record CalibrateRequest(@NotNull Rating rating, String notes) {
    }

    public record AcknowledgeRequest(String response, boolean disagrees) {
    }

    public record FeedbackRequest(
            @NotNull FeedbackRelationship relationship,
            @NotBlank String comments,
            boolean attributed) {
    }

    // --- responses ---------------------------------------------------------

    public record CycleResponse(
            UUID id,
            String name,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate dueOn,
            CycleStatus status) {

        static CycleResponse from(ReviewCycle cycle) {
            return new CycleResponse(cycle.getId(), cycle.getName(), cycle.getPeriodStart(),
                    cycle.getPeriodEnd(), cycle.getDueOn(), cycle.getStatus());
        }
    }

    public record GoalResponse(
            UUID id,
            UUID employeeId,
            String employeeName,
            UUID cycleId,
            String cycleName,
            String title,
            String description,
            String measure,
            BigDecimal weight,
            UUID supportsGoalId,
            LocalDate targetDate,
            int progressPercent,
            GoalStatus status,
            String outcomeNotes) {

        static GoalResponse from(Goal goal) {
            return new GoalResponse(
                    goal.getId(),
                    goal.getEmployee().getId(),
                    goal.getEmployee().displayName(),
                    goal.getCycle() == null ? null : goal.getCycle().getId(),
                    goal.getCycle() == null ? null : goal.getCycle().getName(),
                    goal.getTitle(),
                    goal.getDescription(),
                    goal.getMeasure(),
                    goal.getWeight(),
                    goal.getSupports() == null ? null : goal.getSupports().getId(),
                    goal.getTargetDate(),
                    goal.getProgressPercent(),
                    goal.getStatus(),
                    goal.getOutcomeNotes());
        }
    }

    /**
     * A review, showing only what this side may see.
     *
     * <p>Both assessments come from the entity's visibility methods. A null is not "empty", it is
     * "not yet" — and the client renders it as such.
     */
    public record ReviewResponse(
            UUID id,
            UUID cycleId,
            String cycleName,
            UUID employeeId,
            String employeeName,
            UUID reviewerId,
            String reviewerName,
            String selfAssessment,
            Instant selfSubmittedAt,
            String reviewerAssessment,
            Instant reviewerSubmittedAt,
            Rating proposedRating,
            Rating calibratedRating,
            Rating effectiveRating,
            String calibrationNotes,
            ReviewStatus status,
            Instant sharedAt,
            Instant acknowledgedAt,
            String employeeResponse,
            boolean employeeDisagrees) {

        static ReviewResponse from(PerformanceReview review, boolean asSubject) {
            // The subject sees a rating only once the review has been shared with them.
            boolean ratingVisible = !asSubject || review.getStatus().isVisibleToEmployee();
            return new ReviewResponse(
                    review.getId(),
                    review.getCycle().getId(),
                    review.getCycle().getName(),
                    review.getEmployee().getId(),
                    review.getEmployee().displayName(),
                    review.getReviewer().getId(),
                    review.getReviewer().displayName(),
                    review.selfAssessmentFor(asSubject),
                    review.getSelfSubmittedAt(),
                    review.reviewerAssessmentFor(asSubject),
                    review.getReviewerSubmittedAt(),
                    ratingVisible ? review.getProposedRating() : null,
                    ratingVisible ? review.getCalibratedRating() : null,
                    ratingVisible ? review.effectiveRating() : null,
                    // Calibration notes are a management conversation, never the subject's.
                    asSubject ? null : review.getCalibrationNotes(),
                    review.getStatus(),
                    review.getSharedAt(),
                    review.getAcknowledgedAt(),
                    review.getEmployeeResponse(),
                    review.isEmployeeDisagrees());
        }
    }

    public record FeedbackResponse(
            UUID id,
            String authorName,
            FeedbackRelationship relationship,
            String comments,
            boolean attributed,
            Instant submittedAt) {

        static FeedbackResponse from(ReviewFeedback given, boolean asSubject) {
            return new FeedbackResponse(
                    given.getId(),
                    given.authorNameFor(asSubject),
                    given.getRelationship(),
                    given.getComments(),
                    given.isAttributed(),
                    given.getSubmittedAt());
        }
    }
}
