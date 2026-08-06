package ai.dival.dip.modules.recruitment;

/**
 * Lifecycle of a job requisition.
 *
 * <p>Approval sits between drafting and advertising deliberately: a requisition is a budget
 * commitment, and letting recruiters open roles nobody signed off is how headcount plans drift.
 */
public enum RequisitionStatus {

    DRAFT,
    PENDING_APPROVAL,

    /** Signed off, not yet advertised. */
    APPROVED,

    /** Advertised and accepting applications. */
    OPEN,

    /** Paused — a hiring freeze, a reorganisation. Applications stay, nothing progresses. */
    ON_HOLD,

    FILLED,
    CANCELLED;

    public boolean acceptsApplications() {
        return this == OPEN;
    }

    public boolean isClosed() {
        return this == FILLED || this == CANCELLED;
    }
}
