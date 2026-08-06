package ai.dival.dip.modules.performance;

public enum GoalStatus {

    DRAFT,
    ACTIVE,
    ACHIEVED,

    /**
     * Some of it. Kept distinct from missed because most real years look like this, and a scale
     * with only success and failure pushes everybody into arguing about which one applies.
     */
    PARTIALLY_MET,

    MISSED,

    /** No longer relevant — priorities moved. Not a failure, and recorded as such. */
    CANCELLED;

    public boolean isClosed() {
        return this != DRAFT && this != ACTIVE;
    }

    /** Anything other than achievement has to be explained. */
    public boolean needsExplanation() {
        return this == PARTIALLY_MET || this == MISSED || this == CANCELLED;
    }
}
