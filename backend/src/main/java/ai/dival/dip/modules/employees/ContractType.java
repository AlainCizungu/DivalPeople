package ai.dival.dip.modules.employees;

/** The shape of an engagement. */
public enum ContractType {

    PERMANENT,

    /** Must carry an end date — that is what makes it fixed term, and what expiry alerts watch. */
    FIXED_TERM,

    PART_TIME,
    INTERNSHIP,

    /** Not an employee in the legal sense, but managed through the same records. */
    CONSULTANT;

    public boolean requiresEndDate() {
        return this == FIXED_TERM;
    }
}
