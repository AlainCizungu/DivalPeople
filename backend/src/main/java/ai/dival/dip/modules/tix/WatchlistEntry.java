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
 * A standing inquiry about one company.
 *
 * <p>An operator can already ask the exchange about a subject whenever it likes. This is that same
 * question asked on a schedule, and the whole design is about not letting it become more than that:
 * a watch is answered by a nightly sweep rather than the moment something happens, it charges the
 * rate limiter like any other inquiry, and it reports exactly what an inquiry would have reported
 * that morning.
 *
 * <p><strong>Why not a live feed.</strong> Telling a watcher the afternoon a rival declares would
 * disclose timing, and timing plus a count of two is an attribution by elimination — the watcher
 * knows the second institution is not itself. The count alone, arriving on a schedule nobody
 * controls, carries no such inference.
 */
@Entity
@Table(name = "tix_watchlist_entry")
public class WatchlistEntry extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false, updatable = false)
    private Subject subject;

    /**
     * Why this company is being monitored, in the watcher's own words.
     *
     * <p>Required, exactly as on a single inquiry. Monitoring somebody indefinitely for no stated
     * reason is what a regulator objects to, and "we always have" is not a purpose.
     */
    @Column(name = "purpose", nullable = false)
    private String purpose;

    @Column(name = "added_by", updatable = false)
    private UUID addedBy;

    /**
     * When the watch stops on its own.
     *
     * <p>Renewable by somebody who still needs it and says why again. A watchlist with no expiry
     * only ever grows, which is surveillance by accretion rather than by decision.
     */
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** What the exchange last said, so a sweep can tell a change from a repetition. */
    @Enumerated(EnumType.STRING)
    @Column(name = "last_outcome", length = 30)
    private InquiryResult.Outcome lastOutcome;

    @Column(name = "last_institutions")
    private Integer lastInstitutions;

    @Column(name = "last_checked_at")
    private Instant lastCheckedAt;

    protected WatchlistEntry() {
        // for JPA
    }

    public WatchlistEntry(Subject subject, String purpose, UUID addedBy, Instant expiresAt) {
        this.subject = subject;
        this.purpose = purpose;
        this.addedBy = addedBy;
        this.expiresAt = expiresAt;
    }

    /**
     * Records what the sweep saw.
     *
     * @return true when this differs from the last answer, which is what makes it worth telling
     *         somebody. The first sweep of a new watch is not a change — it is the baseline, and
     *         announcing it would mean every watch fired a notification on the night it was
     *         created, teaching whoever reads them that the first one means nothing.
     */
    boolean observe(InquiryResult.Outcome outcome, int institutions, Instant when) {
        boolean firstLook = lastCheckedAt == null;
        boolean changed = !firstLook
                && (outcome != lastOutcome || institutions != orZero(lastInstitutions));

        this.lastOutcome = outcome;
        this.lastInstitutions = institutions;
        this.lastCheckedAt = when;
        return changed;
    }

    /** Extends the watch, which is a fresh decision and so takes a fresh reason. */
    void renew(String reason, Instant until) {
        this.purpose = reason;
        this.expiresAt = until;
    }

    public boolean hasExpired(Instant now) {
        return expiresAt.isBefore(now);
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }

    public Subject getSubject() {
        return subject;
    }

    public String getPurpose() {
        return purpose;
    }

    public UUID getAddedBy() {
        return addedBy;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public InquiryResult.Outcome getLastOutcome() {
        return lastOutcome;
    }

    public Integer getLastInstitutions() {
        return lastInstitutions;
    }

    public Instant getLastCheckedAt() {
        return lastCheckedAt;
    }
}
