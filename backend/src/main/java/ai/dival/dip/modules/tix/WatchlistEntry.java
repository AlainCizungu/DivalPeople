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
     * The group this watch belongs to, or none.
     *
     * <p>Nullable and staying that way. Every watch opened before groups existed belongs to no
     * list, and inventing a "Default" group to sweep them into would write a name nobody chose
     * into somebody's workspace. The screen calls them unfiled and offers to move them.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "watchlist_id")
    private Watchlist watchlist;

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

    /**
     * The DIP Risk Indicator at the last sweep, or null.
     *
     * <p>Null both before a subject has ever been swept and whenever the exchange withheld the
     * indicator — it does that for any answer it is not confident about — so a null here means
     * "not known then" and never "was zero".
     */
    @Column(name = "last_score")
    private Integer lastScore;

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
    /**
     * Records tonight's answer and says what moved since the last one.
     *
     * <p>Returns the movement rather than a boolean, because the movement is the product. "Something
     * changed" is a notification; "the indicator went 42 to 61 and a second institution began
     * reporting" is what somebody acts on, and only this method is in a position to say it — a
     * moment later the previous figures are gone.
     *
     * <p><strong>The first look is never a change.</strong> A watch opened today has nothing to
     * differ from, and raising an alert on it would tell the watcher that the state they just
     * looked at is news. That teaches people the first alert means nothing, and then that the rest
     * might not either.
     *
     * <p>The score joins the comparison here. Outcome and institution count are coarse — a company
     * can fall a long way without either moving, because both are already at their worst — and the
     * indicator is the one figure the exchange returns that has room to move.
     */
    Change observe(InquiryResult.Outcome outcome, int institutions, Integer score, Instant when) {
        boolean firstLook = lastCheckedAt == null;
        Change change = firstLook
                ? Change.none()
                : new Change(lastOutcome, outcome, lastInstitutions, institutions, lastScore, score);

        this.lastOutcome = outcome;
        this.lastInstitutions = institutions;
        this.lastScore = score;
        this.lastCheckedAt = when;
        return change;
    }

    /**
     * What moved between two sweeps.
     *
     * <p>A record rather than a set of flags, so that whatever raises an alert has the before and
     * the after in one place and cannot report one without the other.
     *
     * @param previousOutcome the last answer, or null on the first look
     * @param previousScore   the last indicator, or null on the first look and whenever the
     *                        exchange withheld one
     */
    public record Change(InquiryResult.Outcome previousOutcome, InquiryResult.Outcome currentOutcome,
                  Integer previousInstitutions, int currentInstitutions,
                  Integer previousScore, Integer currentScore) {

        /** The first look, and every sweep that found the world exactly as it left it. */
        public static Change none() {
            return new Change(null, null, null, 0, null, null);
        }

        public boolean isFirstLook() {
            return currentOutcome == null;
        }

        public boolean outcomeMoved() {
            return !isFirstLook() && currentOutcome != previousOutcome;
        }

        public boolean institutionsMoved() {
            return !isFirstLook() && currentInstitutions != orZero(previousInstitutions);
        }

        /**
         * How far the indicator moved, or zero when either end is missing.
         *
         * <p>A withheld indicator is not a fall to zero. The exchange declines to score a subject
         * whose identity it will not confirm, and treating that as a drop of forty points would
         * raise an alert saying a company improved when what happened is that DIP stopped being
         * sure who they were.
         */
        public int scoreMovement() {
            if (previousScore == null || currentScore == null) {
                return 0;
            }
            return currentScore - previousScore;
        }

        public boolean isSomething() {
            return outcomeMoved() || institutionsMoved() || scoreMovement() != 0;
        }
    }

    /** Extends the watch, which is a fresh decision and so takes a fresh reason. */
    void renew(String reason, Instant until) {
        this.purpose = reason;
        this.expiresAt = until;
    }

    /** Moves this watch into a group, or out of every group when given null. */
    void fileUnder(Watchlist group) {
        this.watchlist = group;
    }

    public Watchlist getWatchlist() {
        return watchlist;
    }

    public Integer getLastScore() {
        return lastScore;
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
