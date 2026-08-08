package ai.dival.dip.modules.tix;

/** What a person is asking the exchange to do about the data it holds on them. */
public enum SubjectRequestType {

    /** Tell me what you hold about me, and who put it there. */
    ACCESS,


    /**
     * This is wrong.
     *
     * <p>Suppresses the affected records while the operator corrects them. The correction itself
     * goes through the normal path — settle the wrong record, declare the right one — because raw
     * records are immutable and rewriting history to make a correction would destroy the evidence
     * that the error happened.
     */
    RECTIFICATION,

    /**
     * Remove it.
     *
     * <p>Granted for records that are settled; refused, with reasons, for those that are not. An
     * erasure right in a debt registry cannot be absolute — if it were, anybody could delete their
     * own debts and no operator would contribute to the exchange at all.
     */
    ERASURE,

    /** I do not accept this debt. Suppresses the affected records while the case is open. */
    DISPUTE;

    /**
     * How long the Code du numérique allows to answer this.
     *
     * <p>Sixty days for access — article 210 gives that long to supply a copy of what is held —
     * and thirty for everything else, from articles 213, 214 and 215. The periods are here rather
     * than in a configuration file on purpose: a reporting threshold is policy and belongs to a
     * deployment, but a statutory deadline belongs to the statute, and making it tunable would
     * invite somebody to tune it.
     *
     * <p>Missing one is not merely late. Article 214 makes it grounds in itself for a complaint to
     * the Autorité de protection des données.
     */
    public int answerWithinDays() {
        return this == ACCESS ? 60 : 30;
    }
}
