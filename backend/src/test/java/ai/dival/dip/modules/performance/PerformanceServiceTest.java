package ai.dival.dip.modules.performance;

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
import java.math.BigDecimal;
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
class PerformanceServiceTest extends AbstractIntegrationTest {

    private static final boolean AS_SUBJECT = true;
    private static final boolean AS_REVIEWER = false;

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private PerformanceService performance;

    private Employee employee;
    private Employee manager;
    private Employee peer;
    private ReviewCycle cycle;

    @BeforeEach
    void setUp() {
        UUID tenantId = tenants.save(new Tenant("PF", "pf-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);

        manager = employees.hire("EMP-001", "Sylvie", "Mbala", LocalDate.of(2019, 1, 7),
                null, null);
        employee = employees.hire("EMP-002", "Didier", "Lokwa", LocalDate.of(2024, 2, 5),
                null, null);
        peer = employees.hire("EMP-003", "Grâce", "Tshibangu", LocalDate.of(2023, 3, 6),
                null, null);
        employees.setManager(employee.getId(), manager.getId(), null);

        cycle = performance.createCycle("2026 annual", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 31), null);
        performance.openCycle(cycle.getId(), null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private PerformanceReview openReview() {
        return performance.openReview(cycle.getId(), employee.getId(), null, null);
    }

    // --- cycles ------------------------------------------------------------

    @Test
    @DisplayName("a cycle starts as a draft and accepts nothing until opened")
    void draftCycleAcceptsNothing() {
        ReviewCycle draft = performance.createCycle("2027 annual", LocalDate.of(2027, 1, 1),
                LocalDate.of(2027, 12, 31), null, null);

        assertThat(draft.getStatus()).isEqualTo(CycleStatus.DRAFT);
        assertThatThrownBy(() ->
                performance.openReview(draft.getId(), employee.getId(), null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("cycle names are unique within a tenant")
    void refusesDuplicateCycleName() {
        assertThatThrownBy(() -> performance.createCycle("2026 annual",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null))
                .isInstanceOf(ConflictException.class);
    }

    // --- reviews: who may see what -----------------------------------------

    @Test
    @DisplayName("the reviewer cannot read the self-assessment until they have submitted")
    void reviewerCannotReadSelfAssessmentEarly() {
        PerformanceReview review = openReview();
        performance.saveSelfAssessment(review.getId(), "I shipped the tower rollout", null);
        performance.submitSelfAssessment(review.getId(), null);

        // The employee is in; the reviewer has not written a word. Reading now would anchor them.
        assertThat(review.selfAssessmentFor(AS_REVIEWER)).isNull();
        // The employee can always see their own words.
        assertThat(review.selfAssessmentFor(AS_SUBJECT)).isEqualTo("I shipped the tower rollout");
    }

    @Test
    @DisplayName("the employee cannot read the reviewer's assessment until it is shared")
    void employeeCannotReadReviewerAssessmentEarly() {
        PerformanceReview review = openReview();
        performance.saveReviewerAssessment(review.getId(), "A strong year", Rating.EXCEEDS, null);
        performance.submitReviewerAssessment(review.getId(), null);
        performance.saveSelfAssessment(review.getId(), "I shipped the rollout", null);
        performance.submitSelfAssessment(review.getId(), null);

        // Both are in, so the reviewer can read the self-assessment.
        assertThat(review.selfAssessmentFor(AS_REVIEWER)).isNotNull();
        // But the employee still cannot see the reviewer's words: sharing is a separate act.
        assertThat(review.reviewerAssessmentFor(AS_SUBJECT)).isNull();

        performance.share(review.getId(), null);
        assertThat(review.reviewerAssessmentFor(AS_SUBJECT)).isEqualTo("A strong year");
    }

    @Test
    @DisplayName("both assessments in place moves the review on by itself")
    void bothSubmittedSettlesTheStatus() {
        PerformanceReview review = openReview();
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.PENDING);

        performance.saveSelfAssessment(review.getId(), "My year", null);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.IN_PROGRESS);

        performance.submitSelfAssessment(review.getId(), null);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.IN_PROGRESS);

        performance.saveReviewerAssessment(review.getId(), "Their year", Rating.MEETS, null);
        performance.submitReviewerAssessment(review.getId(), null);

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.BOTH_SUBMITTED);
        assertThat(review.bothSubmitted()).isTrue();
    }

    @Test
    @DisplayName("an assessment cannot be submitted twice")
    void refusesSecondSubmission() {
        PerformanceReview review = openReview();
        performance.saveSelfAssessment(review.getId(), "My year", null);
        performance.submitSelfAssessment(review.getId(), null);

        assertThatThrownBy(() -> performance.submitSelfAssessment(review.getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("an empty assessment is not a submission")
    void refusesEmptySubmission() {
        PerformanceReview review = openReview();

        assertThatThrownBy(() -> performance.submitSelfAssessment(review.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a reviewer's assessment needs a rating behind it")
    void reviewerMustRate() {
        PerformanceReview review = openReview();
        performance.saveReviewerAssessment(review.getId(), "Some words", null, null);

        assertThatThrownBy(() -> performance.submitReviewerAssessment(review.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nobody reviews themselves")
    void refusesSelfReview() {
        assertThatThrownBy(() -> performance.openReview(cycle.getId(), employee.getId(),
                employee.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("one review per person per cycle")
    void refusesSecondReviewInCycle() {
        openReview();

        assertThatThrownBy(this::openReview).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("the reviewer defaults to the manager")
    void reviewerDefaultsToManager() {
        PerformanceReview review = openReview();

        assertThat(review.getReviewer().getId()).isEqualTo(manager.getId());
    }

    @Test
    @DisplayName("somebody with no manager needs a reviewer named")
    void refusesReviewWithoutReviewer() {
        assertThatThrownBy(() ->
                performance.openReview(cycle.getId(), peer.getId(), null, null))
                .isInstanceOf(ConflictException.class);
    }

    // --- calibration -------------------------------------------------------

    private PerformanceReview submittedReview(Rating rating) {
        PerformanceReview review = openReview();
        performance.saveSelfAssessment(review.getId(), "My year", null);
        performance.submitSelfAssessment(review.getId(), null);
        performance.saveReviewerAssessment(review.getId(), "Their year", rating, null);
        performance.submitReviewerAssessment(review.getId(), null);
        return review;
    }

    @Test
    @DisplayName("calibration keeps what the reviewer originally proposed")
    void calibrationPreservesTheProposal() {
        PerformanceReview review = submittedReview(Rating.EXCEEDS);

        performance.calibrate(review.getId(), Rating.MEETS,
                "Moderated against the rest of the engineering cohort", null);

        assertThat(review.getProposedRating()).isEqualTo(Rating.EXCEEDS);
        assertThat(review.getCalibratedRating()).isEqualTo(Rating.MEETS);
        assertThat(review.effectiveRating()).isEqualTo(Rating.MEETS);
        assertThat(review.getCalibratedAt()).isNotNull();
    }

    @Test
    @DisplayName("changing somebody's rating has to say why")
    void calibrationChangeNeedsAReason() {
        PerformanceReview review = submittedReview(Rating.EXCEEDS);

        assertThatThrownBy(() -> performance.calibrate(review.getId(), Rating.MEETS, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("confirming the same rating needs no explanation")
    void calibrationConfirmationNeedsNoReason() {
        PerformanceReview review = submittedReview(Rating.MEETS);

        performance.calibrate(review.getId(), Rating.MEETS, null, null);

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.CALIBRATED);
    }

    @Test
    @DisplayName("calibration cannot happen before both assessments are in")
    void refusesEarlyCalibration() {
        PerformanceReview review = openReview();
        performance.saveReviewerAssessment(review.getId(), "Their year", Rating.MEETS, null);
        performance.submitReviewerAssessment(review.getId(), null);

        assertThatThrownBy(() ->
                performance.calibrate(review.getId(), Rating.MEETS, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a shared review cannot be recalibrated behind the employee")
    void refusesCalibrationAfterSharing() {
        PerformanceReview review = submittedReview(Rating.MEETS);
        performance.share(review.getId(), null);

        assertThatThrownBy(() -> performance.calibrate(review.getId(), Rating.DEVELOPING,
                "Second thoughts", null))
                .isInstanceOf(ConflictException.class);
    }

    // --- sharing and acknowledgement ---------------------------------------

    @Test
    @DisplayName("nothing can be shared before both sides have written")
    void refusesEarlySharing() {
        PerformanceReview review = openReview();

        assertThatThrownBy(() -> performance.share(review.getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("acknowledgement is not agreement")
    void acknowledgementIsNotAgreement() {
        PerformanceReview review = submittedReview(Rating.DEVELOPING);
        performance.share(review.getId(), null);

        performance.acknowledge(review.getId(), "I do not accept the rating", true, null);

        assertThat(review.getStatus()).isEqualTo(ReviewStatus.ACKNOWLEDGED);
        assertThat(review.getAcknowledgedAt()).isNotNull();
        assertThat(review.isEmployeeDisagrees()).isTrue();
        assertThat(review.getEmployeeResponse()).isEqualTo("I do not accept the rating");
    }

    @Test
    @DisplayName("recording disagreement needs the disagreement written down")
    void disagreementNeedsWords() {
        PerformanceReview review = submittedReview(Rating.DEVELOPING);
        performance.share(review.getId(), null);

        assertThatThrownBy(() ->
                performance.acknowledge(review.getId(), null, true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a review that has not been shared cannot be acknowledged")
    void refusesAcknowledgingUnsharedReview() {
        PerformanceReview review = submittedReview(Rating.MEETS);

        assertThatThrownBy(() -> performance.acknowledge(review.getId(), "Fine", false, null))
                .isInstanceOf(ConflictException.class);
    }

    // --- feedback ----------------------------------------------------------

    @Test
    @DisplayName("feedback records its author even when the subject will not see the name")
    void anonymousFeedbackStillHasAnAuthor() {
        PerformanceReview review = openReview();

        ReviewFeedback given = performance.addFeedback(review.getId(), peer.getId(),
                FeedbackRelationship.PEER, "Reliable under pressure", false, null);

        assertThat(given.getAuthor().getId()).isEqualTo(peer.getId());
        // HR sees the name; the subject does not.
        assertThat(given.authorNameFor(AS_REVIEWER)).isEqualTo(peer.displayName());
        assertThat(given.authorNameFor(AS_SUBJECT)).isNull();
    }

    @Test
    @DisplayName("attributed feedback shows the name to the subject too")
    void attributedFeedbackNamesTheAuthor() {
        PerformanceReview review = openReview();

        ReviewFeedback given = performance.addFeedback(review.getId(), peer.getId(),
                FeedbackRelationship.PEER, "Reliable under pressure", true, null);

        assertThat(given.authorNameFor(AS_SUBJECT)).isEqualTo(peer.displayName());
    }

    @Test
    @DisplayName("nobody gives feedback on their own review")
    void refusesSelfFeedback() {
        PerformanceReview review = openReview();

        assertThatThrownBy(() -> performance.addFeedback(review.getId(), employee.getId(),
                FeedbackRelationship.PEER, "I was excellent", false, null))
                .isInstanceOf(PerformanceService.SelfFeedbackException.class);
    }

    @Test
    @DisplayName("one voice per colleague")
    void refusesSecondFeedbackFromSameAuthor() {
        PerformanceReview review = openReview();
        performance.addFeedback(review.getId(), peer.getId(), FeedbackRelationship.PEER,
                "First thoughts", false, null);

        assertThatThrownBy(() -> performance.addFeedback(review.getId(), peer.getId(),
                FeedbackRelationship.PEER, "More thoughts", false, null))
                .isInstanceOf(ConflictException.class);
    }

    // --- goals -------------------------------------------------------------

    @Test
    @DisplayName("a goal can exist without a cycle")
    void goalsNeedNoCycle() {
        Goal goal = performance.createGoal(employee.getId(), "Cut mean repair time",
                null, "From 6 hours to 4", null, LocalDate.of(2026, 6, 30), null, null, null);

        assertThat(goal.getCycle()).isNull();
        assertThat(goal.getStatus()).isEqualTo(GoalStatus.DRAFT);
    }

    @Test
    @DisplayName("progress is a percentage, and only while the goal is open")
    void progressIsBounded() {
        Goal goal = performance.createGoal(employee.getId(), "Cut mean repair time",
                null, null, null, null, null, null, null);
        performance.activateGoal(goal.getId(), null);

        performance.recordProgress(goal.getId(), 60, null);
        assertThat(goal.getProgressPercent()).isEqualTo(60);

        assertThatThrownBy(() -> performance.recordProgress(goal.getId(), 140, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("anything short of achievement has to be explained")
    void closingShortOfSuccessNeedsAReason() {
        Goal goal = performance.createGoal(employee.getId(), "Cut mean repair time",
                null, null, null, null, null, null, null);
        performance.activateGoal(goal.getId(), null);

        assertThatThrownBy(() ->
                performance.closeGoal(goal.getId(), GoalStatus.MISSED, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);

        performance.closeGoal(goal.getId(), GoalStatus.MISSED, "Parts never arrived", null);
        assertThat(goal.getOutcomeNotes()).isEqualTo("Parts never arrived");
        assertThat(goal.getClosedAt()).isNotNull();
    }

    @Test
    @DisplayName("achieving a goal takes it to a hundred percent")
    void achievementCompletesProgress() {
        Goal goal = performance.createGoal(employee.getId(), "Cut mean repair time",
                null, null, null, null, null, null, null);
        performance.activateGoal(goal.getId(), null);
        performance.recordProgress(goal.getId(), 70, null);

        performance.closeGoal(goal.getId(), GoalStatus.ACHIEVED, null, null);

        assertThat(goal.getProgressPercent()).isEqualTo(100);
    }

    @Test
    @DisplayName("a goal cascade holds several levels deep")
    void goalsCascade() {
        Goal top = performance.createGoal(manager.getId(), "Improve network uptime",
                null, null, null, null, null, null, null);
        Goal middle = performance.createGoal(employee.getId(), "Cut mean repair time",
                null, null, null, null, null, top.getId(), null);
        Goal bottom = performance.createGoal(peer.getId(), "Stock the spares van",
                null, null, null, null, null, middle.getId(), null);

        assertThat(bottom.getSupports().getId()).isEqualTo(middle.getId());
        assertThat(middle.getSupports().getId()).isEqualTo(top.getId());
    }

    @Test
    @DisplayName("re-pointing a goal cannot close a loop, however long the chain")
    void refusesLoopOfSupportingGoals() {
        Goal top = performance.createGoal(manager.getId(), "Improve network uptime",
                null, null, null, null, null, null, null);
        Goal middle = performance.createGoal(employee.getId(), "Cut mean repair time",
                null, null, null, null, null, top.getId(), null);
        Goal bottom = performance.createGoal(peer.getId(), "Stock the spares van",
                null, null, null, null, null, middle.getId(), null);

        // top -> bottom would close top -> bottom -> middle -> top: invisible one level at a
        // time, fatal to anything that walks it.
        assertThatThrownBy(() ->
                performance.supportGoal(top.getId(), bottom.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);

        // A goal cannot support itself either.
        assertThatThrownBy(() ->
                performance.supportGoal(middle.getId(), middle.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);

        // Detaching is always allowed.
        performance.supportGoal(bottom.getId(), null, null);
        assertThat(bottom.getSupports()).isNull();
    }

    @Test
    @DisplayName("one tenant's reviews are invisible to another")
    void reviewsDoNotCrossTenants() {
        PerformanceReview review = openReview();

        UUID tenantB = tenants.save(new Tenant("PF B", "pf-b-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantB);

        assertThatThrownBy(() -> performance.review(review.getId()))
                .isInstanceOf(PerformanceService.ReviewNotFoundException.class);
    }
}
