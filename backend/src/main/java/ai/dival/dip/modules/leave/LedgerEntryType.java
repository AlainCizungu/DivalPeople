package ai.dival.dip.modules.leave;

/**
 * Why a balance moved.
 *
 * <p>Deliberately finer-grained than "credit and debit". When somebody asks why they have 12.5
 * days, "opening 5, accrued 15, taken 7.5" is an answer; "+20 -7.5" is not.
 */
public enum LedgerEntryType {

    /** Carried in from last year, already capped. */
    OPENING,

    /** A month's worth, from the accrual job. */
    ACCRUAL,

    /** A whole year's entitlement, granted at once. */
    GRANT,

    /** Spent on approved leave. */
    TAKEN,

    /** Given back when approved leave is cancelled. */
    RETURNED,

    /** A correction, TOIL, or goodwill. Signed either way. */
    ADJUSTMENT,

    /** Carryover above the cap, expired at year end. */
    LAPSED
}
