package ai.dival.dip.modules.leave;

public enum LeaveRequestStatus {

    /** Submitted and awaiting a decision. The days are already reserved. */
    SUBMITTED,
    APPROVED,
    REJECTED,

    /** Withdrawn by the employee, or called back after approval. */
    CANCELLED;

    public boolean isFinal() {
        return this != SUBMITTED;
    }

    /** Whether this status is holding days out of the balance. */
    public boolean holdsDays() {
        return this == SUBMITTED || this == APPROVED;
    }
}
