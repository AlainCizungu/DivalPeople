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
    DISPUTES_ARE_NOT_DISCLOSED
}
