package ai.dival.dip.modules.attendance;

public enum TimesheetStatus {

    /** Being assembled. Figures move as entries are added. */
    DRAFT,

    /** Submitted with its figures frozen, awaiting a decision. */
    SUBMITTED,
    APPROVED,
    REJECTED;

    public boolean isOpen() {
        return this == DRAFT || this == REJECTED;
    }

    public boolean isDecided() {
        return this == APPROVED || this == REJECTED;
    }
}
