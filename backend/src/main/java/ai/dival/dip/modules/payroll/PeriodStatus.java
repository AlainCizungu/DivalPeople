package ai.dival.dip.modules.payroll;

public enum PeriodStatus {

    DRAFT,

    /** Payslips exist and can be read, but nothing is committed. */
    CALCULATED,

    /** Signed off. Payslips are frozen; recalculating requires reopening, which is audited. */
    APPROVED,

    /** Payment recorded. This module does not move money — see docs/PAYROLL_SCOPE.md. */
    PAID,

    CANCELLED;

    public boolean isOpenForCalculation() {
        return this == DRAFT || this == CALCULATED;
    }

    public boolean isFrozen() {
        return this == APPROVED || this == PAID;
    }

    /**
     * Whether the person being paid may see their own payslip for this run.
     *
     * <p>Only once it is signed off. A calculated run is still being checked, and showing
     * somebody a net figure that is about to be corrected starts a conversation that cannot be
     * taken back. Payroll staff can see it; the employee sees it when it is real.
     */
    public boolean isVisibleToEmployee() {
        return isFrozen();
    }
}
