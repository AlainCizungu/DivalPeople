package ai.dival.dip.modules.lifecycle;

public enum ItemStatus {

    PENDING,
    DONE,

    /** Stuck on something outside the owner's control. Requires an explanation. */
    BLOCKED,

    /**
     * Does not apply to this person — no company car to return, no visa to cancel. Also requires
     * an explanation, so a skipped step is a decision somebody made rather than a gap.
     */
    NOT_APPLICABLE;

    public boolean isSettled() {
        return this == DONE || this == NOT_APPLICABLE;
    }

    public boolean needsExplanation() {
        return this == BLOCKED || this == NOT_APPLICABLE;
    }
}
