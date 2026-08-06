package ai.dival.dip.modules.recruitment;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One interviewer meeting one candidate.
 *
 * <p>A panel is several of these at the same time rather than one row with a shared verdict, so
 * each interviewer records their own view. Averaging opinions before they are written down is how
 * a dissenting voice disappears.
 */
@Entity
@Table(name = "interview")
public class Interview extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private JobApplication application;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 30)
    private InterviewStage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private InterviewMode mode = InterviewMode.VIDEO;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Column(name = "interviewer_id")
    private UUID interviewerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InterviewStatus status = InterviewStatus.SCHEDULED;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation", length = 20)
    private InterviewRecommendation recommendation;

    @Column(name = "score")
    private Integer score;

    @Column(name = "comments", length = 4000)
    private String comments;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    protected Interview() {
        // for JPA
    }

    public Interview(JobApplication application, InterviewStage stage, InterviewMode mode,
                     Instant scheduledAt, UUID interviewerId) {
        this.application = application;
        this.stage = stage;
        this.mode = mode == null ? InterviewMode.VIDEO : mode;
        this.scheduledAt = scheduledAt;
        this.interviewerId = interviewerId;
        this.status = InterviewStatus.SCHEDULED;
    }

    public void reschedule(Instant newTime) {
        if (status != InterviewStatus.SCHEDULED) {
            throw new ConflictException("Only a scheduled interview can be rescheduled");
        }
        this.scheduledAt = newTime;
    }

    public void cancel() {
        if (status == InterviewStatus.COMPLETED) {
            throw new ConflictException("A completed interview cannot be cancelled");
        }
        this.status = InterviewStatus.CANCELLED;
    }

    public void markNoShow() {
        if (status != InterviewStatus.SCHEDULED) {
            throw new ConflictException("Only a scheduled interview can be marked as a no-show");
        }
        this.status = InterviewStatus.NO_SHOW;
    }

    /**
     * Records the interviewer's feedback and completes the interview.
     *
     * <p>Feedback is what completes it. An interview marked done with nothing written down is
     * indistinguishable later from one that never happened.
     */
    public void submitFeedback(InterviewRecommendation recommendation, Integer score,
                               String comments) {
        if (status == InterviewStatus.CANCELLED || status == InterviewStatus.NO_SHOW) {
            throw new ConflictException("This interview did not take place");
        }
        if (recommendation == null) {
            throw new IllegalArgumentException("Feedback needs a recommendation");
        }
        if (score != null && (score < 1 || score > 5)) {
            throw new IllegalArgumentException("A score must be between 1 and 5");
        }

        this.recommendation = recommendation;
        this.score = score;
        this.comments = comments;
        this.submittedAt = Instant.now();
        this.status = InterviewStatus.COMPLETED;
    }

    public boolean hasFeedback() {
        return submittedAt != null;
    }

    public JobApplication getApplication() {
        return application;
    }

    public InterviewStage getStage() {
        return stage;
    }

    public InterviewMode getMode() {
        return mode;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public UUID getInterviewerId() {
        return interviewerId;
    }

    public InterviewStatus getStatus() {
        return status;
    }

    public InterviewRecommendation getRecommendation() {
        return recommendation;
    }

    public Integer getScore() {
        return score;
    }

    public String getComments() {
        return comments;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }
}
