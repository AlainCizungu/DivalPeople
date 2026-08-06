package ai.dival.dip.dev;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeRepository;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.users.UserAccount;
import ai.dival.dip.modules.users.UserAccountRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Links the fixture sign-ins to seeded employees, so the portal has somebody to be.
 *
 * <p>An account is normally created on its owner's first authenticated request, which means
 * nothing can be seeded against a particular person unless their identifier is known in advance.
 * The Keycloak realm fixture therefore gives each user a fixed id, and those ids are the subjects
 * written here. When the real sign-in arrives, {@code CurrentUserService} finds this record by
 * subject and refreshes it rather than creating a second one.
 *
 * <p><strong>Local profile only, and it must stay that way.</strong> Writing a user account that
 * nobody has authenticated as is exactly the thing an identity provider exists to prevent. It is
 * acceptable here because the accounts correspond to fixture logins whose passwords are in a
 * development realm file, and for no other reason.
 *
 * <p>Editing the realm ids means recreating the container:
 * {@code docker compose -f infra/docker-compose.yml up -d --force-recreate keycloak}
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-self-service", havingValue = "true")
@Order(26) // after payroll, so the portal has a payslip on its first load
public class LocalSelfServiceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalSelfServiceSeeder.class);

    /** Matches the {@code id} fields in infra/keycloak/realm-dip.json. */
    private static final String OPERATOR_A_SUBJECT = "aaaaaaaa-0000-4000-8000-00000000000a";
    private static final String NO_ROLES_SUBJECT = "cccccccc-0000-4000-8000-00000000000c";

    private final UserAccountRepository users;
    private final EmployeeRepository employees;
    private final EmployeeService employeeService;
    private final TransactionTemplate transactionTemplate;

    public LocalSelfServiceSeeder(UserAccountRepository users, EmployeeRepository employees,
                                  EmployeeService employeeService,
                                  TransactionTemplate transactionTemplate) {
        this.users = users;
        this.employees = employees;
        this.employeeService = employeeService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = LocalTenantSeeder.OPERATOR_A;

        TenantContext.runAs(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            // The director, so the portal shows a team as well as a payslip.
            link(tenantId, OPERATOR_A_SUBJECT, "operator-a@example.test", "Marie Ilunga",
                    List.of("TENANT_ADMIN", "EMPLOYEE", "TIX_INQUIRER", "TIX_DECLARANT"),
                    "EMP-001");

            // Somebody with no administrative roles at all, which is the case the portal has to
            // work for and the one an admin account will never exercise.
            link(tenantId, NO_ROLES_SUBJECT, "no-roles@example.test", "Jean Kabila",
                    List.of("EMPLOYEE"), "EMP-002");
        }));
    }

    private void link(UUID tenantId, String subject, String email, String displayName,
                      List<String> roles, String employeeNumber) {
        Employee employee = employees
                .findByTenantIdAndEmployeeNumber(tenantId, employeeNumber)
                .orElse(null);
        if (employee == null) {
            log.info("No {} to link a sign-in to", employeeNumber);
            return;
        }
        if (employee.getUserAccountId() != null) {
            return;
        }

        UserAccount account = users.findBySubject(subject)
                .orElseGet(() -> users.save(new UserAccount(subject, email, displayName, roles)));

        employeeService.linkUserAccount(employee.getId(), account.getId(), null);
        log.info("Linked sign-in {} to {}", email, employee.displayName());
    }
}
