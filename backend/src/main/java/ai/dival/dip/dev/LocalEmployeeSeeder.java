package ai.dival.dip.dev;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.ContractType;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeRepository;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.employees.EmploymentContract;
import ai.dival.dip.modules.employees.EmploymentContractService;
import ai.dival.dip.modules.organizations.OrgUnit;
import ai.dival.dip.modules.organizations.OrgUnitRepository;
import java.time.LocalDate;
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
 * Seeds a small team for operator A.
 *
 * <p>Includes one contract deliberately ending soon, so the expiry scan has something to find and
 * the notification path can be seen working end to end rather than only in a test.
 *
 * <p>Local profile only.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-employees", havingValue = "true")
@Order(18) // after organization units, before TIX
public class LocalEmployeeSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalEmployeeSeeder.class);

    private final EmployeeService employees;
    private final EmploymentContractService contracts;
    private final EmployeeRepository employeeRepository;
    private final OrgUnitRepository orgUnits;
    private final TransactionTemplate transactionTemplate;

    public LocalEmployeeSeeder(EmployeeService employees, EmploymentContractService contracts,
                               EmployeeRepository employeeRepository, OrgUnitRepository orgUnits,
                               TransactionTemplate transactionTemplate) {
        this.employees = employees;
        this.contracts = contracts;
        this.employeeRepository = employeeRepository;
        this.orgUnits = orgUnits;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = LocalTenantSeeder.OPERATOR_A;

        TenantContext.runAs(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!employeeRepository.findByTenantIdOrderByLastNameAscFirstNameAsc(tenantId).isEmpty()) {
                return;
            }

            List<OrgUnit> units = orgUnits.findByTenantIdOrderByDepthAscNameAsc(tenantId);
            UUID operations = units.stream()
                    .filter(unit -> unit.getCode().equals("KIN-OPS"))
                    .map(OrgUnit::getId)
                    .findFirst()
                    .orElse(null);
            UUID finance = units.stream()
                    .filter(unit -> unit.getCode().equals("KIN-FIN"))
                    .map(OrgUnit::getId)
                    .findFirst()
                    .orElse(null);

            Employee director = employees.hire(
                    "EMP-001", "Marie", "Ilunga", LocalDate.of(2019, 3, 1), operations, null);
            Employee engineer = employees.hire(
                    "EMP-002", "Jean", "Kabila", LocalDate.of(2023, 9, 15), operations, null);
            Employee analyst = employees.hire(
                    "EMP-003", "Paul", "Mukendi", LocalDate.of(2025, 2, 1), finance, null);

            employees.setManager(engineer.getId(), director.getId(), null);
            employees.setManager(analyst.getId(), director.getId(), null);

            // Permanent, no end date: never appears in an expiry scan.
            contracts.activate(contracts.draft(director.getId(), ContractType.PERMANENT,
                    "Operations Director", LocalDate.of(2019, 3, 1), null,
                    operations, null, null).getId(), null);

            contracts.activate(contracts.draft(engineer.getId(), ContractType.PERMANENT,
                    "Network Engineer", LocalDate.of(2023, 9, 15), null,
                    operations, null, null).getId(), null);

            // Ends within the notice window on purpose, so the expiry alert has something to find.
            EmploymentContract expiring = contracts.draft(analyst.getId(), ContractType.FIXED_TERM,
                    "Financial Analyst", LocalDate.of(2025, 2, 1),
                    LocalDate.now().plusDays(21), finance, null, null);
            contracts.activate(expiring.getId(), null);

            log.info("Seeded 3 employees for operator A, one contract expiring in 21 days");
        }));
    }
}
