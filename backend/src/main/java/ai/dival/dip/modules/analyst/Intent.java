package ai.dival.dip.modules.analyst;

/**
 * The questions the analyst can answer, and there are only these.
 *
 * <p><strong>A closed list is the safety property.</strong> The model chooses one of these and
 * extracts its parameters; it never writes a query and never computes a figure. So the worst a
 * misreading can do is answer a different question of the user's own — visibly, since the screen
 * prints which intent it understood — rather than reach data the caller may not see.
 *
 * <p>The alternative, letting a model generate SQL against the registry, would put every disclosure
 * rule in this platform at the mercy of a sentence somebody typed. It would also work beautifully
 * in a demo, which is why it is worth naming as the thing deliberately not done.
 */
public enum Intent {

    /**
     * Companies in your own book owing more than a threshold.
     *
     * <p>Your own records only, so it costs nothing and is instant. "Your exposure" is stated on
     * the answer in those words: this platform cannot total what other institutions are owed, and
     * a figure captioned "combined exposure" would be read as the market's.
     */
    EXPOSURE_ABOVE,

    /**
     * The same, narrowed to companies more than one institution reports.
     *
     * <p><strong>Costs one inquiry per company screened.</strong> How many institutions report a
     * company is exactly what an inquiry discloses, so asking it about forty companies is forty
     * inquiries against the same hourly allowance as everybody else. The answer quotes the price
     * before spending it.
     */
    EXPOSURE_ABOVE_MULTI_INSTITUTION,

    /**
     * Why one company reads as risky.
     *
     * <p>Answered from the evidence pack: the operator's own file, the exchange's verdict, the risk
     * indicator with every factor behind it, and the list of what is deliberately not there. One
     * inquiry.
     */
    WHY_RISKY,

    /** What entered or left your book inside a window. Your own records; free and instant. */
    WHAT_CHANGED,

    /**
     * Which unpaid accounts to work first.
     *
     * <p>A sort, and the answer says so. Amount and age, both of which the operator already has;
     * nothing here predicts recovery, because this platform holds no outcome data and a ranking
     * presented as a prediction is the most quietly dishonest thing an analyst screen can do.
     */
    PRIORITISE,

    /**
     * Understood as nothing on this list.
     *
     * <p>Answered with the list rather than with a guess. An analyst that half-answers an
     * unsupported question teaches people to trust an answer nobody checked.
     */
    UNSUPPORTED
}
