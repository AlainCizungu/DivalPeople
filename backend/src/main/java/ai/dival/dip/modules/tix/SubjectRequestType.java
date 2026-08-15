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
     * <p><strong>Ten days for access, twenty for everything else.</strong> Counsel advised these
     * in August 2026, replacing the sixty and thirty this file previously read off articles 210,
     * 213, 214 and 215. Being wrong in the earlier direction is the expensive kind — every case
     * answered on day forty under the old numbers was already a month late — so the shorter
     * periods are applied as given rather than held pending a citation.
     *
     * <p>Note which way round they are. Access is the <em>shortest</em>, which reads oddly beside
     * a regime where supplying a copy of a file is more work than acknowledging a dispute, and
     * that is the point: telling somebody what is held about them is the right the others depend
     * on, and a person who cannot see their record cannot know there is anything to rectify.
     *
     * <p>The periods are here rather than in configuration on purpose. A reporting threshold is
     * policy and belongs to a deployment; a statutory deadline belongs to the statute, and making
     * it tunable would invite somebody to tune it.
     *
     * <p>Missing one is not merely late. Article 214 makes it grounds in itself for a complaint to
     * the Autorité de protection des données. Existing open cases keep the deadline computed when
     * they were raised — {@code due_at} is written once and V23 forbids updating it — so this
     * change binds new requests rather than retroactively making yesterday's queue overdue.
     */
    public int answerWithinDays() {
        return this == ACCESS ? 10 : 20;
    }
}
