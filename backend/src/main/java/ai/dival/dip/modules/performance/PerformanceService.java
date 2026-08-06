package ai.dival.dip.modules.performance;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.notifications.Notification;
import ai.dival.dip.modules.notifications.NotificationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goals, review cycles, reviews and feedback.
 *
 * <p>The rule the module exists to enforce: nobody reads the other side's assessment until both
 * have submitted, and nothing informs a decision about somebody until it has been shared with
 * them. Both live on {@link PerformanceReview}; this service is what makes sure every path goes
 * through them.
 */
@Service
public class PerformanceService {

    private final ReviewCycleRepository cycles;
    private final GoalRepository goals;
    private final PerformanceReviewRepository reviews;
    private final ReviewFeedbackRepository feedback;
    private final EmployeeService employees;
    private final NotificationService notifications;
    private final AuditService audit;

    public PerformanceService(ReviewCycleRepository cycles, GoalRepository goals,
                              PerformanceReviewRepository reviews,
                              ReviewFeedbackRepository feedback, EmployeeService employees,
                              NotificationService notifications, AuditService audit) {
        this.cycles = cycles;
        this.goals = goals;
        this.reviews = reviews;
        this.feedback = feedback;
        this.employees = employees;
        this.notifications = notifications;
        this.audit = audit;
    }

    // --- cycles ------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ReviewCycle> listCycles() {
        return cycles.findByTenantIdOrderByPeriodStartDesc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public ReviewCycle cycle(UUID id) {
        return cycles.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new CycleNotFoundException(id));
    }

    @Transactional
    public ReviewCycle createCycle(String name, LocalDate periodStart, LocalDate periodEnd,
                                   LocalDate dueOn, UUID actorId) {
        UUID tenantId = TenantContext.require();
        String trimmed = name == null ? "" : name.trim();

        if (cycles.findByTenantIdAndName(tenantId, trimmed).isPresent()) {
            throw new ConflictException("A cycle with that name already exists: " + trimmed);
        }

        ReviewCycle saved = cycles.save(new ReviewCycle(trimmed, periodStart, periodEnd, dueOn));
        audit.recordSuccess("REVIEW_CYCLE_CREATED", "ReviewCycle",
                saved.getId().toString(), actorId);
        return saved;
    }

    @Transactional
    public ReviewCycle openCycle(UUID id, UUID actorId) {
        ReviewCycle cycle = cycle(id);
        cycle.open();
        audit.recordSuccess("REVIEW_CYCLE_OPENED", "ReviewCycle", id.toString(), actorId);
        return cycle;
    }

    @Transactional
    public ReviewCycle closeCycle(UUID id, UUID actorId) {
        ReviewCycle cycle = cycle(id);
        cycle.close();
        audit.recordSuccess("REVIEW_CYCLE_CLOSED", "ReviewCycle", id.toString(), actorId);
        return cycle;
    }

    // --- goals -------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Goal> goalsFor(UUID employeeId) {
        employees.get(employeeId);
        return goals.findByTenantIdAndEmployeeIdOrderByCreatedAtDesc(
                TenantContext.require(), employeeId);
    }

    @Transactional(readOnly = true)
    public List<Goal> goalsInCycle(UUID cycleId) {
        cycle(cycleId);
        return goals.findByTenantIdAndCycleIdOrderByCreatedAtDesc(TenantContext.require(), cycleId);
    }

    @Transactional(readOnly = true)
    public Goal goal(UUID id) {
        return goals.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new GoalNotFoundException(id));
    }

    @Transactional
    public Goal createGoal(UUID employeeId, String title, String description, String measure,
                           BigDecimal weight, LocalDate targetDate, UUID cycleId,
                           UUID supportsGoalId, UUID actorId) {
        Employee employee = employees.get(employeeId);

        Goal created = new Goal(employee, title, measure, targetDate);
        created.describe(description, weight);
        if (cycleId != null) {
            created.placeInCycle(cycle(cycleId));
        }
        if (supportsGoalId != null) {
            created.supportGoal(requireNoCycle(created, goal(supportsGoalId)));
        }

        Goal saved = goals.save(created);
        audit.recordSuccess("GOAL_CREATED", "Goal", saved.getId().toString(), actorId);
        return saved;
    }

    /**
     * Re-points a goal at the one it contributes to, or detaches it.
     *
     * <p>This is the operation that makes a loop possible, which is why the chain is walked
     * rather than checked one level deep: goals get re-parented as priorities move, and a loop
     * three goals long is just as fatal to anything that traverses it.
     */
    @Transactional
    public Goal supportGoal(UUID id, UUID parentGoalId, UUID actorId) {
        Goal goal = goal(id);
        goal.supportGoal(parentGoalId == null ? null : requireNoCycle(goal, goal(parentGoalId)));
        audit.recordSuccess("GOAL_REPARENTED", "Goal", id.toString(), actorId);
        return goal;
    }

    @Transactional
    public Goal activateGoal(UUID id, UUID actorId) {
        Goal goal = goal(id);
        goal.activate();
        audit.recordSuccess("GOAL_ACTIVATED", "Goal", id.toString(), actorId);
        return goal;
    }

    @Transactional
    public Goal recordProgress(UUID id, int percent, UUID actorId) {
        Goal goal = goal(id);
        goal.recordProgress(percent);
        audit.recordSuccess("GOAL_PROGRESS", "Goal", id.toString(), actorId);
        return goal;
    }

    @Transactional
    public Goal closeGoal(UUID id, GoalStatus outcome, String notes, UUID actorId) {
        Goal goal = goal(id);
        goal.close(outcome, notes);
        audit.recordSuccess("GOAL_" + outcome, "Goal", id.toString(), actorId);
        return goal;
    }

    /**
     * Refuses a chain of supporting goals that closes on itself.
     *
     * <p>Walked rather than checked one level deep, because a loop three goals long is just as
     * fatal to anything that traverses it and just as invisible row by row.
     */
    private Goal requireNoCycle(Goal child, Goal parent) {
        Set<UUID> seen = new HashSet<>();
        if (child.getId() != null) {
            seen.add(child.getId());
        }
        for (Goal current = parent; current != null; current = current.getSupports()) {
            if (!seen.add(current.getId())) {
                throw new IllegalArgumentException(
                        "That would make a loop of supporting goals");
            }
        }
        return parent;
    }

    // --- reviews -----------------------------------------------------------

    @Transactional(readOnly = true)
    public PerformanceReview review(UUID id) {
        return reviews.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new ReviewNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<PerformanceReview> reviewsInCycle(UUID cycleId) {
        cycle(cycleId);
        return reviews.findByTenantIdAndCycleIdOrderByCreatedAtAsc(
                TenantContext.require(), cycleId);
    }

    @Transactional(readOnly = true)
    public List<PerformanceReview> reviewsFor(UUID employeeId) {
        employees.get(employeeId);
        return reviews.findByTenantIdAndEmployeeIdOrderByCreatedAtDesc(
                TenantContext.require(), employeeId);
    }

    @Transactional(readOnly = true)
    public List<PerformanceReview> reviewsToWrite(UUID reviewerId) {
        return reviews.findByTenantIdAndReviewerIdOrderByCreatedAtDesc(
                TenantContext.require(), reviewerId);
    }

    /**
     * Opens a review for somebody in a cycle.
     *
     * <p>The reviewer defaults to their manager, because that is who it almost always is and
     * making it explicit every time invites mistakes.
     */
    @Transactional
    public PerformanceReview openReview(UUID cycleId, UUID employeeId, UUID reviewerId,
                                        UUID actorId) {
        UUID tenantId = TenantContext.require();
        ReviewCycle cycle = cycle(cycleId);
        Employee employee = employees.get(employeeId);

        if (!cycle.getStatus().acceptsReviews()) {
            throw new ConflictException("This cycle is not open");
        }
        reviews.findByTenantIdAndCycleIdAndEmployeeId(tenantId, cycleId, employeeId)
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "This person already has a review in this cycle");
                });

        Employee reviewer = reviewerId != null
                ? employees.get(reviewerId)
                : employee.getManager();
        if (reviewer == null) {
            throw new ConflictException(
                    "No reviewer given and this employee has no manager");
        }

        PerformanceReview saved = reviews.save(new PerformanceReview(cycle, employee, reviewer));
        notify(reviewer, "reviewAssigned", employee, saved);
        notify(employee, "reviewOpened", employee, saved);
        audit.recordSuccess("REVIEW_OPENED", "PerformanceReview",
                saved.getId().toString(), actorId);
        return saved;
    }

    @Transactional
    public PerformanceReview saveSelfAssessment(UUID id, String text, UUID actorId) {
        PerformanceReview review = review(id);
        review.saveSelfAssessment(text);
        return review;
    }

    @Transactional
    public PerformanceReview submitSelfAssessment(UUID id, UUID actorId) {
        PerformanceReview review = review(id);
        review.submitSelfAssessment();
        audit.recordSuccess("REVIEW_SELF_SUBMITTED", "PerformanceReview", id.toString(), actorId);
        notifyIfBothIn(review);
        return review;
    }

    @Transactional
    public PerformanceReview saveReviewerAssessment(UUID id, String text, Rating rating,
                                                    UUID actorId) {
        PerformanceReview review = review(id);
        review.saveReviewerAssessment(text, rating);
        return review;
    }

    @Transactional
    public PerformanceReview submitReviewerAssessment(UUID id, UUID actorId) {
        PerformanceReview review = review(id);
        review.submitReviewerAssessment();
        audit.recordSuccess("REVIEW_REVIEWER_SUBMITTED", "PerformanceReview",
                id.toString(), actorId);
        notifyIfBothIn(review);
        return review;
    }

    @Transactional
    public PerformanceReview calibrate(UUID id, Rating rating, String notes, UUID actorId) {
        PerformanceReview review = review(id);
        review.calibrate(rating, notes);
        audit.recordSuccess("REVIEW_CALIBRATED", "PerformanceReview", id.toString(), actorId);
        return review;
    }

    @Transactional
    public PerformanceReview share(UUID id, UUID actorId) {
        PerformanceReview review = review(id);
        review.share();
        notify(review.getEmployee(), "reviewShared", review.getEmployee(), review);
        audit.recordSuccess("REVIEW_SHARED", "PerformanceReview", id.toString(), actorId);
        return review;
    }

    @Transactional
    public PerformanceReview acknowledge(UUID id, String response, boolean disagrees,
                                         UUID actorId) {
        PerformanceReview review = review(id);
        review.acknowledge(response, disagrees);

        if (disagrees) {
            // A disagreement that only the employee can see is not a disagreement anybody has to
            // answer, so the reviewer hears about it.
            notify(review.getReviewer(), "reviewDisputed", review.getEmployee(), review);
        }
        audit.recordSuccess(disagrees ? "REVIEW_DISPUTED" : "REVIEW_ACKNOWLEDGED",
                "PerformanceReview", id.toString(), actorId);
        return review;
    }

    // --- feedback ----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ReviewFeedback> feedbackFor(UUID reviewId) {
        review(reviewId);
        return feedback.findByTenantIdAndReviewIdOrderBySubmittedAtAsc(
                TenantContext.require(), reviewId);
    }

    /**
     * Adds feedback from somebody other than the reviewer.
     *
     * <p>One piece per author per review: a second submission is an edit, not a second voice, and
     * counting it twice would weight one colleague above the rest.
     */
    @Transactional
    public ReviewFeedback addFeedback(UUID reviewId, UUID authorEmployeeId,
                                      FeedbackRelationship relationship, String comments,
                                      boolean attributed, UUID actorId) {
        UUID tenantId = TenantContext.require();
        PerformanceReview review = review(reviewId);
        Employee author = employees.get(authorEmployeeId);

        if (author.getId().equals(review.getEmployee().getId())) {
            // The self-assessment is where they say it. Feedback on yourself, counted alongside
            // colleagues', would let somebody pad their own review.
            throw new SelfFeedbackException();
        }
        feedback.findByTenantIdAndReviewIdAndAuthorId(tenantId, reviewId, authorEmployeeId)
                .ifPresent(existing -> {
                    throw new ConflictException("You have already given feedback on this review");
                });

        ReviewFeedback saved = feedback.save(
                new ReviewFeedback(review, author, relationship, comments, attributed));
        audit.recordSuccess("REVIEW_FEEDBACK_ADDED", "ReviewFeedback",
                saved.getId().toString(), actorId);
        return saved;
    }

    private void notifyIfBothIn(PerformanceReview review) {
        if (!review.bothSubmitted()) {
            return;
        }
        // Both sides can now read each other, which is the moment the conversation becomes
        // possible. Telling only one of them would give that one a head start.
        notify(review.getEmployee(), "reviewBothSubmitted", review.getEmployee(), review);
        notify(review.getReviewer(), "reviewBothSubmitted", review.getEmployee(), review);
    }

    private void notify(Employee recipient, String messageKey, Employee subject,
                        PerformanceReview review) {
        if (recipient == null || recipient.getUserAccountId() == null) {
            // Nobody to tell. The review still stands and still appears in the queue.
            return;
        }
        notifications.notify(
                recipient.getUserAccountId(),
                messageKey,
                Map.of("employee", subject.displayName(),
                        "cycle", review.getCycle().getName()),
                Notification.Severity.INFO,
                "PerformanceReview",
                review.getId().toString());
    }

    public static class CycleNotFoundException extends ResourceNotFoundException {
        public CycleNotFoundException(UUID id) {
            super("Review cycle not found: " + id);
        }
    }

    public static class GoalNotFoundException extends ResourceNotFoundException {
        public GoalNotFoundException(UUID id) {
            super("Goal not found: " + id);
        }
    }

    public static class ReviewNotFoundException extends ResourceNotFoundException {
        public ReviewNotFoundException(UUID id) {
            super("Review not found: " + id);
        }
    }

    public static class SelfFeedbackException extends AccessRefusedException {
        public SelfFeedbackException() {
            super("Feedback on your own review is the self-assessment, not a colleague's voice");
        }
    }
}
