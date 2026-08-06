package ai.dival.dip.modules.employees;

import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.modules.users.CurrentUserService;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers "who is asking" from the token, and from nothing else.
 *
 * <p>This is the whole security argument for self-service, so it is worth stating plainly. Every
 * endpoint under {@code /api/v1/me} resolves the employee here and passes that id to the
 * underlying service. None of them accept an employee id from the caller.
 *
 * <p>The alternative — take an id from the request and check it belongs to the caller — is the
 * pattern that produces broken object-level authorisation. It works until somebody adds a second
 * endpoint, or moves the check behind a condition, or writes a new one by copying an old one and
 * dropping the check. A parameter that must be validated is a parameter that will eventually be
 * used without validation. Not having the parameter cannot fail that way.
 *
 * <p>It lives in this module rather than with self-service on purpose. "Which employee is this
 * sign-in" is a fact about employees, and performance needs the same answer; putting it in the
 * self-service module would have made performance depend on self-service and self-service depend
 * on performance.
 */
@Component
public class CurrentEmployee {

    private final CurrentUserService currentUser;
    private final EmployeeService employees;

    public CurrentEmployee(CurrentUserService currentUser, EmployeeService employees) {
        this.currentUser = currentUser;
        this.employees = employees;
    }

    /**
     * The employee record behind the current sign-in.
     *
     * @throws NotAnEmployeeException when the sign-in is not linked to one, which is an ordinary
     *         situation rather than an attack: administrators, recruiters and platform staff can
     *         all authenticate without being on the payroll. They are told so, plainly, instead
     *         of being shown an empty portal that looks broken.
     */
    @Transactional(readOnly = true)
    public Employee require() {
        UUID userAccountId = currentUser.requireCurrentUser().getId();
        return employees.forUserAccount(userAccountId).orElseThrow(NotAnEmployeeException::new);
    }

    /** The current employee's id. The only id self-service endpoints are allowed to act on. */
    @Transactional(readOnly = true)
    public UUID requireId() {
        return require().getId();
    }

    /**
     * Whether the current sign-in belongs to a given employee.
     *
     * <p>For endpoints shared between the person and somebody reading about them: a performance
     * review shows the employee less than it shows their reviewer, and the server has to decide
     * which of them is at the other end. It used to ask the caller.
     *
     * <p>Answers false rather than throwing when nobody is signed in or the sign-in has no
     * employee record, because in both cases the caller is certainly not the subject, and the
     * cautious answer is the correct one.
     */
    @Transactional(readOnly = true)
    public boolean isSelf(UUID employeeId) {
        if (employeeId == null) {
            return false;
        }
        UUID userAccountId = currentUser.currentUserIdOrNull();
        return userAccountId != null
                && employees.forUserAccount(userAccountId)
                        .map(employee -> employee.getId().equals(employeeId))
                        .orElse(false);
    }

    /** A valid sign-in with no employee record behind it. Refused, and told why. */
    public static class NotAnEmployeeException extends AccessRefusedException {
        public NotAnEmployeeException() {
            super("This sign-in is not linked to an employee record, so there is no personal "
                    + "information to show. An HR administrator can link it.");
        }
    }
}
