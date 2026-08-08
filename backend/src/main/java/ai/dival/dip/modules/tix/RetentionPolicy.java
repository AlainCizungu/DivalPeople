package ai.dival.dip.modules.tix;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * How long a person stays in the registry.
 *
 * <p>The arithmetic is trivial and the decisions inside it are not, so they are written down
 * rather than left implicit in a date calculation.
 *
 * <p><strong>The clock starts at the default date, not the declaration date.</strong> Otherwise an
 * operator could keep somebody listed indefinitely by settling and re-declaring, or simply by
 * declaring an old debt late — the retention period would measure how long the operator had been
 * getting round to it rather than how old the default is. Tying it to the event is also why
 * declaration refuses a future default date: that would push the expiry out past the period the
 * law allows without anybody touching a retention setting.
 *
 * <p><strong>Récidive is judged once, at declaration.</strong> A record's expiry is fixed when it
 * is written and never recalculated. If somebody defaults again later, the new record gets the
 * longer period and the older ones keep theirs. The alternative — extending existing records when
 * a person defaults again — means a decision made today reaches back and lengthens how long
 * already-recorded facts are held, which is retroactive punishment implemented as a cron job.
 *
 * <p><strong>Settlement can only shorten.</strong> Regularising a debt takes the earlier of the
 * two dates, so paying always brings erasure closer and never pushes it away.
 */
@Component
public class RetentionPolicy {

    private final TixProperties.Retention periods;
    private final Clock clock;

    public RetentionPolicy(TixProperties properties, Clock clock) {
        this.periods = properties.retention();
        this.clock = clock;
    }

    /**
     * Expiry for a newly declared default.
     *
     * @param defaultDate when the obligation fell due
     * @param repeat      whether the exchange has seen this subject default before
     */
    public LocalDate expiryFor(LocalDate defaultDate, boolean repeat) {
        int years = repeat ? periods.repeatYears() : periods.simpleYears();
        return defaultDate.plusYears(years);
    }

    /**
     * Expiry once a debt has been regularised.
     *
     * <p>Returns the earlier of the settlement window and whatever the record already had, so a
     * long-standing record that gets settled is erased on the settlement clock rather than
     * continuing to its original date — and a record already near expiry is not given a fresh
     * thirty days by the act of paying it off.
     */
    public LocalDate expiryOnSettlement(LocalDate current) {
        LocalDate onSettlement = LocalDate.now(clock).plusDays(periods.settledDays());
        return current != null && current.isBefore(onSettlement) ? current : onSettlement;
    }

    /** True when the record should no longer be visible to anybody, and is due for erasure. */
    public boolean hasExpired(LocalDate retentionUntil) {
        // Exclusive: a record expires at the end of its last day, so today == retentionUntil is
        // still within the period. An inclusive comparison would quietly shorten every retention
        // period in the system by one day, which nobody would ever notice.
        return retentionUntil != null && retentionUntil.isBefore(LocalDate.now(clock));
    }
}
