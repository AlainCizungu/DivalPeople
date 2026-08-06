package ai.dival.dip.modules.lifecycle;

public enum ChecklistStatus {

    IN_PROGRESS,
    COMPLETED,

    /** Abandoned — a hire that fell through, a resignation withdrawn. */
    CANCELLED;

    public boolean isOpen() {
        return this == IN_PROGRESS;
    }
}
