package ai.dival.dip.modules.employees;

/**
 * How a probation ended.
 *
 * <p>Recorded rather than inferred from the date passing. In most jurisdictions an unconfirmed
 * probation quietly becomes a confirmed one, so the absence of a decision is itself a decision —
 * just one nobody can be shown to have made.
 */
public enum ProbationOutcome {

    CONFIRMED,

    /** A second look, with a new end date. Common; not a failure. */
    EXTENDED,

    /** Employment ends. The one outcome that must carry a reason. */
    FAILED
}
