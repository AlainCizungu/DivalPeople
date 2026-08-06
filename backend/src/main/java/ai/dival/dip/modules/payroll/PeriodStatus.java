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
}
