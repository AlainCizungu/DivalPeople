package ai.dival.dip.modules.risk;

/**
 * Why a factor was left out.
 *
 * <p>Two reasons and they are not the same kind of reason, which is the whole argument for
 * naming them separately. One is a gap that will close when somebody answers a question; the
 * other is a decision that will not change. A reader who cannot tell those apart cannot tell
 * whether waiting will improve the assessment.
 */
public enum NotAssessedReason {

    /**
     * The amounts on record are not all in one currency.
     *
     * <p>No longer the standing state of the exposure factor. Counsel confirmed in August 2026
     * that both operator exports are in USD, and the deployment accepts no other currency for
     * declaration — so in practice this fires only if a record predating that answer, or one
     * declared under a floor added later, sits in the same subject's file in a second currency.
     *
     * <p>Kept rather than deleted, and kept as a refusal rather than a conversion. Adding two
     * currencies needs a rate; a rate moves, somebody has to own it, and a number invented here
     * would be a stale exchange rate wearing the costume of a risk factor. Declining to weigh a
     * mixed file is the honest answer and it is a rare one.
     */
    MIXED_CURRENCY,

    /**
     * The data exists and disclosing it would defeat a protection.
     *
     * <p>Records under dispute are already withheld from every answer the exchange gives.
     * Reporting the disputes themselves would restore the disclosure by another route and make
     * contesting a record something that costs the person who contests it. This does not close.
     */
    DISPUTES_ARE_NOT_DISCLOSED,

    /**
     * There is no signal to weigh, and there cannot be.
     *
     * <p>The fraud signal this factor was built on — one identifier under two subjects — can never
     * fire. Both unique indexes on the identifier table forbid it: a national document resolves to
     * one subject by construction, which is how identity resolution works at all. So the factor
     * read LOW on every assessment ever produced, which is the most reassuring possible way to
     * report having looked at nothing.
     *
     * <p>This closes when a signal exists that can fire. The behaviour anomalies now computed from
     * the audit trail are about the institution asking rather than the subject asked about, so
     * they do not belong in a subject's risk assessment; something that does will come from
     * elsewhere.
     */
    NO_FRAUD_SIGNAL_IS_COMPUTABLE
}
