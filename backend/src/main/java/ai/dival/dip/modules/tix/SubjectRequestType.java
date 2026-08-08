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
    DISPUTE
}
