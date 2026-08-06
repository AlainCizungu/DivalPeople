package ai.dival.dip.modules.leave;

/** How an entitlement arrives. */
public enum AccrualMethod {

    /** The whole year's entitlement lands on day one. Simple, and generous to leavers. */
    ANNUAL_GRANT,

    /**
     * Builds up month by month, which is what most contracts actually say. Someone who leaves in
     * March has earned three months of leave, not a year of it.
     */
    MONTHLY_ACCRUAL
}
