package ai.dival.dip.modules.anomalies;

/**
 * What looks unusual about how somebody has been using the exchange.
 *
 * <p>DIP watching DIP. Every other check in the platform asks whether a record is right; this asks
 * whether the asking is right, and it is the only one an operator cannot perform for itself — the
 * audit trail was built to make a sweep legible after the fact, and until now nothing read it back.
 *
 * <p><strong>None of these is an accusation.</strong> Every one has an innocent explanation that is
 * usually the true one: a collections team working through a list, an integration retrying, a new
 * member of staff mistyping. The screen puts the figures in front of somebody who can ask, which
 * is the whole of what a platform can honestly do here.
 */
public enum BehaviourFlag {

    /**
     * Far more inquiries than anybody else at this institution.
     *
     * <p>Measured against the operator's own busiest ordinary user rather than a fixed number,
     * because a bank's call centre and a two-person microfinance have nothing in common and a
     * constant would be wrong for both.
     */
    HIGH_VOLUME,

    /**
     * Most of what they asked about was not in the registry.
     *
     * <p>The strongest of the three and the hardest to explain away. Somebody checking customers
     * finds them; somebody walking an identifier format to see which values exist finds almost
     * nobody, and that is what a sweep looks like from the inside.
     */
    MOSTLY_NO_MATCH,

    /**
     * The rate limiter turned them away.
     *
     * <p>A person doing their job reaches an hourly cap approximately never. Something automated
     * reaches it immediately, and whether that is a broken integration or a deliberate sweep is
     * exactly the question worth asking.
     */
    HIT_THE_RATE_LIMIT
}
