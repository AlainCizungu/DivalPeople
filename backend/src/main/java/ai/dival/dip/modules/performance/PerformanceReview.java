package ai.dival.dip.modules.performance;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import ai.dival.dip.modules.employees.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One person's review for one cycle.
 *
 * <p>Written blind. Both assessments are stored as they are typed, but {@link #selfAssessmentFor}
 * and {@link #reviewerAssessmentFor} refuse to hand either side the other's words until both have
 * been submitted. A manager who reads the self-assessment first is anchored by it; an employee
 * who can see the manager's draft writes to it rather than about their year.
 *
 * <p>Nothing here means anything until it is shared. A rating that reaches a pay decision without
 * the person having read it is indefensible, and the status makes that a rule rather than a habit.
 */
@Entity
@Table(name = "performance_review")
public class PerformanceReview extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cycle_id", nullable = false)
    private ReviewCycle cycle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reviewer_id", nullable = false)
    private Employee reviewer;

    @Column(name = "self_assessment", length = 8000)
    private String selfAssessment;

    @Column(name = "self_submitted_at")
    private Instant selfSubmittedAt;

    @Column(name = "reviewer_assessment", length = 8000)
    private String reviewerAssessment;

    @Column(name = "reviewer_submitted_at")
    private Instant reviewerSubmittedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "proposed_rating", length = 20)
    private Rating proposedRating;

    @Enumerated(EnumType.STRING)
    @Column(name = "calibrated_rating", length = 20)
    private Rating calibratedRating;

    @Column(name = "calibration_notes", length = 2000)
    private String calibrationNotes;

    @Column(name = "calibrated_at")
    private Instant calibratedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReviewStatus status = ReviewStatus.PENDING;

    @Column(name = "shared_at")
    private Instant sharedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "employee_response", length = 4000)
    private String employeeResponse;

    @Column(name = "employee_disagrees", nullable = false)
    private boolean employeeDisagrees;

    protected PerformanceReview() {
        // for JPA
    }

    public PerformanceReview(ReviewCycle cycle, Employee employee, Employee reviewer) {
        if (reviewer.getId() != null && reviewer.getId().equals(employee.getId())) {
            // A self-assessment is a field on this row, not a review. Somebody reviewing
            // themselves is the absence of a review dressed as one.
            throw new IllegalArgumentException("Nobody reviews themselves");
        }
        this.cycle = cycle;
        this.employee = employee;
        this.reviewer = reviewer;
        this.status = ReviewStatus.PENDING;
    }

    // --- writing -----------------------------------------------------------

    public void saveSelfAssessment(String text) {
        requireOpenForWriting();
        if (selfSubmittedAt != null) {
            throw new ConflictException("Your assessment has already been submitted");
        }
        this.selfAssessment = text;
        advanceToInProgress();
    }

    public void submitSelfAssessment() {
        requireOpenForWriting();
        if (selfSubmittedAt != null) {
            throw new ConflictException("Your assessment has already been submitted");
        }
        if (selfAssessment == null || selfAssessment.isBlank()) {
            throw new IllegalArgumentException("There is nothing to submit");
        }
        this.selfSubmittedAt = Instant.now();
        settleIfBothIn();
    }

    public void saveReviewerAssessment(String text, Rating rating) {
        requireOpenForWriting();
        if (reviewerSubmittedAt != null) {
            throw new ConflictException("This assessment has already been submitted");
        }
        this.reviewerAssessment = text;
        this.proposedRating = rating;
        advanceToInProgress();
    }

    public void submitReviewerAssessment() {
        requireOpenForWriting();
        if (reviewerSubmittedAt != null) {
            throw new ConflictException("This assessment has already been submitted");
        }
        if (reviewerAssessment == null || reviewerAssessment.isBlank()) {
            throw new IllegalArgumentException("There is nothing to submit");
        }
        if (proposedRating == null) {
            // A rating with no words behind it is unarguable, and an assessment with no rating
            // leaves the calibration conversation with nothing to work from.
            throw new IllegalArgumentException("An assessment needs a rating");
        }
        this.reviewerSubmittedAt = Instant.now();
        settleIfBothIn();
    }

    // --- reading -----------------------------------------------------------

    /**
     * The self-assessment, if the caller is allowed it yet.
     *
     * @param askingAsSubject true when the employee is reading their own
     * @return null when the other side has not submitted, which the caller renders as "not yet"
     */
    public String selfAssessmentFor(boolean askingAsSubject) {
        if (askingAsSubject) {
            return selfAssessment;
        }
        return bothSubmitted() ? selfAssessment : null;
    }

    /** The reviewer's words, hidden from the subject until both are in and it has been shared. */
    public String reviewerAssessmentFor(boolean askingAsSubject) {
        if (!askingAsSubject) {
            return reviewerAssessment;
        }
        return status.isVisibleToEmployee() ? reviewerAssessment : null;
    }

    /** The rating that counts: the calibrated one where there is one. */
    public Rating effectiveRating() {
        return calibratedRating != null ? calibratedRating : proposedRating;
    }

    public boolean bothSubmitted() {
        return selfSubmittedAt != null && reviewerSubmittedAt != null;
    }

    // --- deciding ----------------------------------------------------------

    /**
     * Adjusts the rating across a cohort.
     *
     * <p>The proposed rating is kept. An adjustment that overwrites what the reviewer actually
     * said leaves nobody able to see that calibration happened, which is precisely what makes
     * calibration worth auditing.
     */
    public void calibrate(Rating rating, String notes) {
        if (!bothSubmitted()) {
            throw new ConflictException("Both assessments must be in before calibration");
        }
        if (status.isVisibleToEmployee()) {
            throw new ConflictException("This review has already been shared");
        }
        if (rating == null) {
            throw new IllegalArgumentException("Calibration needs a rating");
        }
        if (rating != proposedRating && (notes == null || notes.isBlank())) {
            throw new IllegalArgumentException(
                    "Changing somebody's rating has to say why");
        }

        this.calibratedRating = rating;
        this.calibrationNotes = notes;
        this.calibratedAt = Instant.now();
        this.status = ReviewStatus.CALIBRATED;
    }

    /** Hands it to the employee. Until this, it must inform no decision about them. */
    public void share() {
        if (!bothSubmitted()) {
            throw new ConflictException("Both assessments must be in before sharing");
        }
        if (status.isVisibleToEmployee()) {
            throw new ConflictException("This review has already been shared");
        }
        this.sharedAt = Instant.now();
        this.status = ReviewStatus.SHARED;
    }

    /**
     * Records that the employee has read it.
     *
     * <p>Acknowledgement is not agreement, which is why disagreement is a separate flag and a
     * response of their own. A record that can only say "accepted" is a record that cannot hold
     * the case where somebody did not.
     */
    public void acknowledge(String response, boolean disagrees) {
        if (status != ReviewStatus.SHARED) {
            throw new ConflictException("This review has not been shared yet");
        }
        if (disagrees && (response == null || response.isBlank())) {
            throw new IllegalArgumentException("Recording disagreement needs a response");
        }
        this.acknowledgedAt = Instant.now();
        this.employeeResponse = response;
        this.employeeDisagrees = disagrees;
        this.status = ReviewStatus.ACKNOWLEDGED;
    }

    private void requireOpenForWriting() {
        if (!cycle.getStatus().acceptsReviews()) {
            throw new ConflictException("This review cycle is not open");
        }
        if (!status.isOpenForWriting()) {
            throw new ConflictException("This review is no longer open for writing");
        }
    }

    private void advanceToInProgress() {
        if (status == ReviewStatus.PENDING) {
            this.status = ReviewStatus.IN_PROGRESS;
        }
    }

    private void settleIfBothIn() {
        if (bothSubmitted()) {
            this.status = ReviewStatus.BOTH_SUBMITTED;
        } else {
            advanceToInProgress();
        }
    }

    public ReviewCycle getCycle() {
        return cycle;
    }

    public Employee getEmployee() {
        return employee;
    }

    public Employee getReviewer() {
        return reviewer;
    }

    public Instant getSelfSubmittedAt() {
        return selfSubmittedAt;
    }

    public Instant getReviewerSubmittedAt() {
        return reviewerSubmittedAt;
    }

    public Rating getProposedRating() {
        return proposedRating;
    }

    public Rating getCalibratedRating() {
        return calibratedRating;
    }

    public String getCalibrationNotes() {
        return calibrationNotes;
    }

    public Instant getCalibratedAt() {
        return calibratedAt;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public Instant getSharedAt() {
        return sharedAt;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public String getEmployeeResponse() {
        return employeeResponse;
    }

    public boolean isEmployeeDisagrees() {
        return employeeDisagrees;
    }
}
