package ai.dival.dip.modules.performance;

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
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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

    // --- cycles ------------------------------------------------------------

    @GetMapping("/cycles")
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
    public List<GoalResponse> goals(@PathVariable UUID employeeId) {
        return performance.goalsFor(employeeId).stream().map(GoalResponse::from).toList();
    }

    @PostMapping("/goals")
    public ResponseEntity<GoalResponse> createGoal(@Valid @RequestBody CreateGoalRequest r) {
        Goal created = performance.createGoal(r.employeeId(), r.title(), r.description(),
                r.measure(), r.weight(), r.targetDate(), r.cycleId(), r.supportsGoalId(),
                actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(GoalResponse.from(created));
    }

    @PostMapping("/goals/{id}/activate")
    public GoalResponse activateGoal(@PathVariable UUID id) {
        return GoalResponse.from(performance.activateGoal(id, actorId()));
    }

    @PostMapping("/goals/{id}/progress")
    public GoalResponse recordProgress(@PathVariable UUID id,
                                       @Valid @RequestBody ProgressRequest r) {
        return GoalResponse.from(performance.recordProgress(id, r.progressPercent(), actorId()));
    }

    @PostMapping("/goals/{id}/close")
    public GoalResponse closeGoal(@PathVariable UUID id, @Valid @RequestBody CloseGoalRequest r) {
        return GoalResponse.from(performance.closeGoal(id, r.outcome(), r.notes(), actorId()));
    }

    /** A null parent detaches the goal from whatever it was supporting. */
    @PostMapping("/goals/{id}/supports")
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
    public List<ReviewResponse> reviewsFor(@PathVariable UUID employeeId) {
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
    public ReviewResponse review(@PathVariable UUID id) {
        PerformanceReview found = performance.review(id);
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
    public ReviewResponse saveSelf(@PathVariable UUID id, @RequestBody AssessmentRequest r) {
        return ReviewResponse.from(
                performance.saveSelfAssessment(id, r.text(), actorId()), true);
    }

    @PostMapping("/reviews/{id}/self/submit")
    public ReviewResponse submitSelf(@PathVariable UUID id) {
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
    public ReviewResponse acknowledge(@PathVariable UUID id,
                                      @RequestBody AcknowledgeRequest r) {
        return ReviewResponse.from(
                performance.acknowledge(id, r.response(), r.disagrees(), actorId()), true);
    }

    // --- feedback ----------------------------------------------------------

    @GetMapping("/reviews/{id}/feedback")
    public List<FeedbackResponse> feedback(@PathVariable UUID id) {
        // Anonymity is decided by who is reading, so it is read from the token like everything
        // else. A colleague who asked not to be named must not be named by a query string.
        boolean asSubject = currentEmployee.isSelf(performance.review(id).getEmployee().getId());
        return performance.feedbackFor(id).stream()
                .map(given -> FeedbackResponse.from(given, asSubject)).toList();
    }

    @PostMapping("/reviews/{id}/feedback")
    public ResponseEntity<FeedbackResponse> addFeedback(
            @PathVariable UUID id, @Valid @RequestBody FeedbackRequest r) {
        ReviewFeedback added = performance.addFeedback(id, r.authorEmployeeId(),
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
            @NotNull UUID authorEmployeeId,
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
