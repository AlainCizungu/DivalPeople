package ai.dival.dip.common.security;

/**
 * Role names as used in {@code @PreAuthorize} expressions.
 *
 * <p>Kept as constants so a rename is a compile error rather than a silently unenforced rule.
 */
public final class Roles {

    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String TENANT_ADMIN = "TENANT_ADMIN";
    public static final String HR_ADMIN = "HR_ADMIN";
    public static final String HR_MANAGER = "HR_MANAGER";
    public static final String PAYROLL_OFFICER = "PAYROLL_OFFICER";
    public static final String FINANCE_OFFICER = "FINANCE_OFFICER";
    public static final String COMPLIANCE_OFFICER = "COMPLIANCE_OFFICER";
    public static final String RECRUITER = "RECRUITER";
    public static final String MANAGER = "MANAGER";
    public static final String EMPLOYEE = "EMPLOYEE";
    public static final String AUDITOR = "AUDITOR";

    /** TIX: may submit verification inquiries. */
    public static final String TIX_INQUIRER = "TIX_INQUIRER";
    /** TIX: may declare and settle debt records on behalf of the operator. */
    public static final String TIX_DECLARANT = "TIX_DECLARANT";

    private Roles() {
    }
}
