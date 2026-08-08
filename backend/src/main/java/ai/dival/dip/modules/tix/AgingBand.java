package ai.dival.dip.modules.tix;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * How long an obligation has been overdue.
 *
 * <p>The band edges deliberately mirror the columns of the real telecom export profiled in
 * {@code docs/TIX_SOURCE_PROFILE.md} — {@code Not Due, 30, 60, 90, 120, 150, 180, 270, 360 +} —
 * so that a declared record and an imported one will land in the same vocabulary when the mapping
 * is built. Inventing our own bands now would guarantee a reconciliation problem later.
 *
 * <p><strong>One edge is ours rather than the file's.</strong> The source has {@code 270 days}
 * followed by {@code 360 + days} and nothing between, so where a 300-day debt belongs is not
 * stated anywhere. This treats everything past 270 as {@link #OVER_270} and names the band after
 * that boundary rather than after 360, because a band called "360+" that in fact starts at 271
 * would be a label that lies. The profile records this as a question for Vodacom; when they answer
 * it, only this class changes.
 *
 * <p>Ages are measured from the default date, which is when the obligation fell due — never from
 * the declaration date. An operator that reports a debt late does not thereby make it younger.
 */
public enum AgingBand {

    /**
     * Not yet overdue.
     *
     * <p>Unreachable through declaration today, which refuses a future default date, and kept
     * anyway: the source file has the column, so imported rows will need somewhere to go. A band
     * that is always empty is honest; a row silently pushed into "0–30 days" is not.
     */
    NOT_DUE,

    DAYS_30,
    DAYS_60,
    DAYS_90,
    DAYS_120,
    DAYS_150,
    DAYS_180,
    DAYS_270,

    /** Everything older. Where essentially all of the money sits in the real export. */
    OVER_270;

    /**
     * The band an obligation falls in.
     *
     * <p>{@code today} is a parameter rather than a call to {@code LocalDate.now()} so that a test
     * can ask what a portfolio looks like in four years without waiting or mocking a clock — the
     * same reason {@code DebtRecord.isExpiredAsOf} takes one.
     */
    public static AgingBand of(LocalDate defaultDate, LocalDate today) {
        long days = ChronoUnit.DAYS.between(defaultDate, today);
        if (days < 0) {
            return NOT_DUE;
        }
        // Upper-inclusive throughout: a debt exactly 30 days overdue is in the 30-day band, not
        // the 60. The first band therefore spans 0..30, which is 31 days rather than 30 — the day
        // a debt falls due has to belong somewhere, and pushing it into a band named for a longer
        // period would overstate the age of every fresh default.
        if (days <= 30) {
            return DAYS_30;
        }
        if (days <= 60) {
            return DAYS_60;
        }
        if (days <= 90) {
            return DAYS_90;
        }
        if (days <= 120) {
            return DAYS_120;
        }
        if (days <= 150) {
            return DAYS_150;
        }
        if (days <= 180) {
            return DAYS_180;
        }
        if (days <= 270) {
            return DAYS_270;
        }
        return OVER_270;
    }
}
