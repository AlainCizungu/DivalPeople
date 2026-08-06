package ai.dival.dip.modules.tix;

/**
 * Status of a declared obligation, as returned to an enquiring operator.
 *
 * <p>These values are the entire vocabulary the exchange exposes about another operator's
 * relationship with a subject. Balances, tariffs, and consumption never cross the boundary.
 */
public enum DebtStatus {

    /** A confirmed unpaid obligation exists. */
    OUTSTANDING,

    /** A previously declared obligation was fully resolved. */
    SETTLED,

    /** Contested by the subject; provisional and suppressed from inquiry results while open. */
    DISPUTED,

    /** Associated with an open fraud investigation. */
    UNDER_INVESTIGATION,

    /** No adverse record is held. */
    CLEARED
}
