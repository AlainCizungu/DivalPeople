package ai.dival.dip.modules.performance;

public enum CycleStatus {

    DRAFT,

    /** Reviews can be written. */
    OPEN,

    /** Finished. Nothing further can be submitted against it. */
    CLOSED,

    CANCELLED;

    public boolean acceptsReviews() {
        return this == OPEN;
    }
}
