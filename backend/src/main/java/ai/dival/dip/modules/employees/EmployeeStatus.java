package ai.dival.dip.modules.employees;

/** Where someone stands with the employer right now. */
public enum EmployeeStatus {

    ACTIVE,

    /** Away but still employed — parental, sick, sabbatical. Still counts in headcount. */
    ON_LEAVE,

    /** Employed but barred from working, typically during an investigation. */
    SUSPENDED,

    /** No longer employed. The record stays: payroll history and audit still reference it. */
    TERMINATED;

    public boolean isEmployed() {
        return this != TERMINATED;
    }
}
