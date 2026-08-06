package ai.dival.dip.modules.payroll;

public enum ComponentType {

    /** Adds to gross. */
    EARNING,

    /** Comes off gross to reach net. */
    DEDUCTION,

    /**
     * Paid by the employer on top, and never taken off anybody's net.
     *
     * <p>Kept on the payslip because the cost of employing somebody is a real figure that finance
     * needs, and because leaving it off makes an employer pension look like an employee one.
     */
    EMPLOYER_CONTRIBUTION;

    public boolean addsToGross() {
        return this == EARNING;
    }

    public boolean reducesNet() {
        return this == DEDUCTION;
    }
}
