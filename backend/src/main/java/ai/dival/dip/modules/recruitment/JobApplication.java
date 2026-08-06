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
import java.time.LocalDate;

/**
 * One candidate against one requisition.
 *
 * <p>Status changes go through {@link #moveTo}, which refuses transitions that make no sense. A
 * pipeline where any state can follow any other cannot be reported on, and quietly loses people
 * who were moved backwards by a mis-click.
 */
@Entity
@Table(name = "job_application")
public class JobApplication extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "requisition_id", nullable = false)
    private JobRequisition requisition;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "candidate_id", nullable = false)
    private Candidate candidate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private ApplicationStatus status = ApplicationStatus.APPLIED;

    @Column(name = "applied_on", nullable = false)
    private LocalDate appliedOn;

    /** Always recorded for a rejection: a pipeline that cannot say why cannot be reviewed. */
    @Column(name = "outcome_reason", length = 1000)
    private String outcomeReason;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected JobApplication() {
        // for JPA
    }

    public JobApplication(JobRequisition requisition, Candidate candidate, LocalDate appliedOn) {
        this.requisition = requisition;
        this.candidate = candidate;
        this.appliedOn = appliedOn == null ? LocalDate.now() : appliedOn;
        this.status = ApplicationStatus.APPLIED;
    }

    /**
     * Moves the application along.
     *
     * @param reason required when rejecting, so every turned-down candidate has a recorded why
     */
    public void moveTo(ApplicationStatus next, String reason) {
        if (next == status) {
            return;
        }
        if (!next.canFollow(status)) {
            throw new ConflictException(
                    "An application cannot move from " + status + " to " + next);
        }
        if (next == ApplicationStatus.REJECTED && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("A rejection needs a reason");
        }

        this.status = next;
        this.outcomeReason = reason;
        if (next.isFinal()) {
            this.decidedAt = Instant.now();
        }
    }

    public JobRequisition getRequisition() {
        return requisition;
    }

    public Candidate getCandidate() {
        return candidate;
    }

    public ApplicationStatus getStatus() {
        return status;
    }

    public LocalDate getAppliedOn() {
        return appliedOn;
    }

    public String getOutcomeReason() {
        return outcomeReason;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
