package ai.dival.dip.modules.risk;

/**
 * The sentence that goes beside the number.
 *
 * <p>Bands exist because a number invites a precision this does not have. Sixty-seven and
 * sixty-four are not different findings, and anybody who treats them as different has read more
 * into the indicator than it says. Two subjects in the same band are, as far as this platform can
 * tell, the same answer.
 *
 * <p><strong>The scale runs the risk way up.</strong> Zero is no adverse information and one
 * hundred is the most this platform can observe, which is the opposite of a credit score and the
 * point: anybody who mistakes this for one reads it backwards immediately and finds out, rather
 * than quietly lending to the wrong company. The screen says so in words above the figure.
 */
public enum RiskBand {

    LOW,
    MODERATE,
    ELEVATED,
    HIGH;

    /**
     * The band a score falls in.
     *
     * <p>The bands are uneven on purpose. Everything below twenty is one answer — nothing much
     * was found — and the model does not pretend to rank inside it. The interesting resolution is
     * in the middle, where an assessment actually changes a decision.
     */
    public static RiskBand of(int score) {
        if (score < 20) {
            return LOW;
        }
        if (score < 40) {
            return MODERATE;
        }
        if (score < 70) {
            return ELEVATED;
        }
        return HIGH;
    }
}
