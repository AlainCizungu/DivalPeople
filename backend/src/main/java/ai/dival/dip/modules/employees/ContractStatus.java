package ai.dival.dip.modules.employees;

/** Lifecycle of a contract, from drafting to however it finished. */
public enum ContractStatus {

    /** Being prepared. Not in force, and invisible to expiry alerts. */
    DRAFT,

    ACTIVE,

    /** Ran to its natural end date. */
    ENDED,

    /** Stopped early — resignation, dismissal, termination of the engagement. */
    TERMINATED;

    public boolean isCurrent() {
        return this == ACTIVE;
    }
}
