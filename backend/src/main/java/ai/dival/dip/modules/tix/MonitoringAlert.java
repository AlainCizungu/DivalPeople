package ai.dival.dip.modules.tix;

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
 * Something about a watched company changed, and here is what it was before.
 *
 * <p>Monitoring used to produce a notification: a sentence sent once, unfindable afterwards, with
 * nothing recording what the figures had been. That is a nudge. An institution that <em>acts</em> on
 * a change — pauses a facility, calls the customer, declines a renewal — has to be able to show
 * later what it was told and when, and a notification cannot be produced as evidence.
 *
 * <p><strong>Before and after, both stored.</strong> The whole content of an alert is the movement.
 * A row keeping only the new figures would leave "42 to 61" reconstructable only by trusting that
 * nothing else moved in between, which is exactly the assumption an investigation exists to avoid.
 *
 * <p><strong>Severity is stored, not recomputed.</strong> The grading rule will be tuned — it is a
 * judgement about what deserves attention, and those get revised. An alert re-graded on read by a
 * later rule would quietly rewrite what somebody was told at the time, so the grade is fixed at the
 * moment it was raised and travels with the row.
 *
 * <p><strong>What it does not say.</strong> Which institution began reporting, and how much they are
 * owed. Both are the exchange's standing refusal and neither becomes disclosable because it arrived
 * as a change rather than as an answer — a deployment that has switched
 * {@link DisclosureProperties} on gets them on the profile, and this row still carries the count.
 */
@Entity
@Table(name = "tix_monitoring_alert")
public class MonitoringAlert extends TenantOwnedEntity {

    /**
     * How loudly this should ask for attention.
     *
     * <p>Three levels, because two is not enough to be useful and five is not enough to be
     * distinguishable. The distinction that matters is between "somebody must look at this today"
     * and "this is worth knowing at the weekly review", and a queue where everything is urgent is
     * a queue nobody works.
     */
    public enum Severity {
        /** Now unpaid where it was not, or the indicator moved a long way. */
        MATERIAL,
        /** Another institution began reporting, or the indicator moved noticeably. */
        NOTABLE,
        /** Something differs from last night and none of the above applies. */
        INFORMATIONAL
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "entry_id", nullable = false, updatable = false)
    private WatchlistEntry entry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false, updatable = false)
    private Subject subject;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private Instant raisedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_outcome")
    private InquiryResult.Outcome previousOutcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "current_outcome", nullable = false)
    private InquiryResult.Outcome currentOutcome;

    @Column(name = "previous_institutions")
    private Integer previousInstitutions;

    @Column(name = "current_institutions", nullable = false)
    private int currentInstitutions;

    @Column(name = "previous_score")
    private Integer previousScore;

    @Column(name = "current_score")
    private Integer currentScore;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Severity severity;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by")
    private UUID acknowledgedBy;

    @Column(name = "acknowledgement_note")
    private String acknowledgementNote;

    protected MonitoringAlert() {
    }

    public MonitoringAlert(WatchlistEntry entry, Subject subject, Instant raisedAt,
                           InquiryResult.Outcome previousOutcome,
                           InquiryResult.Outcome currentOutcome,
                           Integer previousInstitutions, int currentInstitutions,
                           Integer previousScore, Integer currentScore, Severity severity) {
        this.entry = entry;
        this.subject = subject;
        this.raisedAt = raisedAt;
        this.previousOutcome = previousOutcome;
        this.currentOutcome = currentOutcome;
        this.previousInstitutions = previousInstitutions;
        this.currentInstitutions = currentInstitutions;
        this.previousScore = previousScore;
        this.currentScore = currentScore;
        this.severity = severity;
    }

    /**
     * Somebody looked, and said what they concluded.
     *
     * <p>The note is required by the service rather than by this method, in the same place the
     * dispute and the resolution decision require theirs: an alert closed with no reason is an
     * alert whose closing tells a later reader nothing except that the queue got shorter.
     *
     * <p>Acknowledging does not undo anything. The figures stay as they were raised, because the
     * alert is the record of what the platform said, not of what turned out to be true.
     */
    public void acknowledge(UUID actorId, String note, Instant when) {
        this.acknowledgedBy = actorId;
        this.acknowledgementNote = note;
        this.acknowledgedAt = when;
    }

    public boolean isOpen() {
        return acknowledgedAt == null;
    }

    public WatchlistEntry getEntry() {
        return entry;
    }

    public Subject getSubject() {
        return subject;
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }

    public InquiryResult.Outcome getPreviousOutcome() {
        return previousOutcome;
    }

    public InquiryResult.Outcome getCurrentOutcome() {
        return currentOutcome;
    }

    public Integer getPreviousInstitutions() {
        return previousInstitutions;
    }

    public int getCurrentInstitutions() {
        return currentInstitutions;
    }

    public Integer getPreviousScore() {
        return previousScore;
    }

    public Integer getCurrentScore() {
        return currentScore;
    }

    public Severity getSeverity() {
        return severity;
    }

    public Instant getAcknowledgedAt() {
        return acknowledgedAt;
    }

    public UUID getAcknowledgedBy() {
        return acknowledgedBy;
    }

    public String getAcknowledgementNote() {
        return acknowledgementNote;
    }
}
