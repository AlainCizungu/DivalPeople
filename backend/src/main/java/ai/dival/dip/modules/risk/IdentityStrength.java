package ai.dival.dip.modules.risk;

/**
 * How firmly the subject was matched, expressed in terms this module can weigh.
 *
 * <p>Three values rather than the platform's nine identifier types, and the collapse is
 * deliberate. This module must not know what an RCCM is, or that account references collide
 * between operators, or that a phone number gets reassigned. It needs to know how much the answer
 * can be leaned on. Mapping the nine onto these three is the caller's job, and keeping it there
 * is what stops the risk model acquiring opinions about telecom document formats.
 *
 * <p>Named for strength rather than for mechanism, for the same reason. {@link #PARTIAL} covers
 * an operator's own account number and a mobile number, which are nothing like each other and are
 * worth the same here: each pins the subject down inside one institution's records and neither is
 * evidence that this is the company another institution knows.
 */
public enum IdentityStrength {

    /**
     * Matched on something an authority issued — a national ID, a passport, an RCCM.
     *
     * <p>The same document whoever presents it, so a match on one means the same thing at every
     * institution in the exchange.
     */
    STRONG,

    /**
     * Matched on something real but local: an account number, a phone number.
     *
     * <p>Enough to be sure which of one institution's own customers this is. Not enough to say
     * the company reported by that institution is the company reported by another.
     */
    PARTIAL,

    /**
     * Matched on a name and nothing else.
     *
     * <p>The weakest thing the platform does, and it does it because a real operator export
     * arrived carrying 342 customers and no identifier of any kind. Two companies trading under
     * one name are not distinguishable here, and an assessment built on such a match should read
     * as less certain rather than as equally certain.
     */
    NAME_ONLY
}
