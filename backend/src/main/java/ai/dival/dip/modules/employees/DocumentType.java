package ai.dival.dip.modules.employees;

/**
 * What a stored file represents for an employee.
 *
 * <p>The type drives retention and, for the ones that expire, whether anybody is warned. An
 * expired work permit is a compliance problem rather than an administrative one, which is why
 * these are enumerated instead of being free text.
 */
public enum DocumentType {
    CONTRACT,
    IDENTITY,
    WORK_PERMIT,
    VISA,
    CERTIFICATION,
    QUALIFICATION,
    MEDICAL,
    PAYSLIP,
    OTHER;

    /** Types where an expiry date is expected and its passing matters. */
    public boolean expiryMatters() {
        return this == WORK_PERMIT || this == VISA
                || this == CERTIFICATION || this == IDENTITY;
    }
}
