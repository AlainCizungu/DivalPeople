package ai.dival.dip.modules.payroll;

/** How a component's amount is arrived at. */
public enum CalculationMethod {

    /** A flat sum each period. */
    FIXED,

    /**
     * A percentage of base salary. What most statutory deductions use, which is why the rate is
     * configuration rather than code — see docs/PAYROLL_SCOPE.md.
     */
    PERCENT_OF_BASE,

    /**
     * A percentage of taxable gross, so it depends on the earnings above it. This is why
     * components carry an order: the result must not depend on insertion order.
     */
    PERCENT_OF_GROSS,

    /** A rate multiplied by hours, used for overtime. */
    PER_HOUR,

    /** Entered by hand for one period. A bonus, a correction, a one-off deduction. */
    MANUAL;

    public boolean needsPercentage() {
        return this == PERCENT_OF_BASE || this == PERCENT_OF_GROSS;
    }

    /** Whether this depends on other lines, and so must be applied after them. */
    public boolean dependsOnGross() {
        return this == PERCENT_OF_GROSS;
    }
}
