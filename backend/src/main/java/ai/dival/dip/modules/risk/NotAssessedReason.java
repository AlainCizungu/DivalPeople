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
     * The data exists and cannot be trusted yet.
     *
     * <p>Neither operator export states the currency of its amount column. This closes the day
     * one of them confirms it, and then exposure gets weighed like anything else.
     */
    CURRENCY_UNCONFIRMED,

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
