package ai.dival.dip.common.security;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Every role the platform declares, read off the constants above.
     *
     * <p>Reflective so that adding a constant is enough, and public because two callers now need
     * it: the access catalogue, which lists what each role permits, and the membership rules,
     * which refuse to grant a role that does not exist. A second hand-written list is a role
     * invented on a Friday afternoon that one of them never hears about.
     *
     * <p>Computed once. The set cannot change while the process runs, and a screen that lists
     * roles should not pay for reflection on every load.
     */
    private static final List<String> DECLARED = declare();

    public static List<String> all() {
        return DECLARED;
    }

    private static List<String> declare() {
        List<String> names = new ArrayList<>();
        for (Field field : Roles.class.getDeclaredFields()) {
            // The cached list itself is a static field, and it is not a role.
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
                try {
                    names.add((String) field.get(null));
                } catch (IllegalAccessException unreachable) {
                    throw new IllegalStateException(
                            "Roles constants are public; this cannot happen", unreachable);
                }
            }
        }
        return List.copyOf(names);
    }

    private Roles() {
    }
}
