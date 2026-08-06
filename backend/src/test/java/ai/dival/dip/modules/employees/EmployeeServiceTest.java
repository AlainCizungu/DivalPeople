package ai.dival.dip.modules.employees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.organizations.OrgUnit;
import ai.dival.dip.modules.organizations.OrgUnitService;
import ai.dival.dip.modules.organizations.OrgUnitType;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@RequiresDocker
class EmployeeServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private EmployeeRepository employeeRepository;
    @Autowired
    private OrgUnitService orgUnits;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = tenants.save(new Tenant("E A", "e-a-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        tenantB = tenants.save(new Tenant("E B", "e-b-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Employee hire(String number, String first, String last) {
        return employees.hire(number, first, last, LocalDate.of(2024, 1, 15), null, null);
    }

    @Test
    @DisplayName("a new hire is active and numbered")
    void hires() {
        Employee hired = hire("emp 001", "Jean", "Kabila");

        assertThat(hired.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
        assertThat(hired.getEmployeeNumber()).isEqualTo("EMP-001");
        assertThat(hired.displayName()).isEqualTo("Jean Kabila");
    }

    @Test
    @DisplayName("the preferred name is used for display when given")
    void prefersPreferredName() {
        Employee hired = hire("EMP-001", "Jean", "Kabila");
        employees.updateDetails(hired.getId(), "Jean", "Kabila", "JK",
                LocalDate.of(1990, 5, 12), "jk@example.test", "+243900000000", "CD123", null);

        assertThat(hired.displayName()).isEqualTo("JK Kabila");
    }

    @Test
    @DisplayName("employee numbers are unique within a tenant but not across them")
    void numbersAreUniquePerTenant() {
        hire("EMP-001", "Jean", "Kabila");

        assertThatThrownBy(() -> hire("emp-001", "Marie", "Ilunga"))
                .isInstanceOf(EmployeeService.EmployeeNumberAlreadyUsedException.class);

        TenantContext.runAs(tenantB, () -> hire("EMP-001", "Other", "Person"));
    }

    @Test
    @DisplayName("an employee can be placed in an organization unit")
    void assignsToOrgUnit() {
        OrgUnit unit = orgUnits.create(null, OrgUnitType.LEGAL_ENTITY, "HQ", "Head office", null);
        Employee hired = hire("EMP-001", "Jean", "Kabila");

        employees.assignToOrgUnit(hired.getId(), unit.getId(), null);

        assertThat(hired.getOrgUnit()).isNotNull();
        assertThat(hired.getOrgUnit().getId()).isEqualTo(unit.getId());
    }

    @Test
    @DisplayName("reporting lines cannot close a loop")
    void refusesReportingCycle() {
        Employee manager = hire("EMP-001", "Marie", "Ilunga");
        Employee report = hire("EMP-002", "Jean", "Kabila");
        employees.setManager(report.getId(), manager.getId(), null);
        employeeRepository.flush();

        assertThatThrownBy(() -> employees.setManager(manager.getId(), report.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> employees.setManager(manager.getId(), manager.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a loop is refused several levels down, not just directly")
    void refusesIndirectCycle() {
        Employee top = hire("EMP-001", "Top", "Person");
        Employee middle = hire("EMP-002", "Middle", "Person");
        Employee bottom = hire("EMP-003", "Bottom", "Person");

        employees.setManager(middle.getId(), top.getId(), null);
        employees.setManager(bottom.getId(), middle.getId(), null);
        employeeRepository.flush();

        assertThatThrownBy(() -> employees.setManager(top.getId(), bottom.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("direct reports are listed")
    void listsDirectReports() {
        Employee manager = hire("EMP-001", "Marie", "Ilunga");
        Employee first = hire("EMP-002", "Jean", "Kabila");
        Employee second = hire("EMP-003", "Paul", "Mukendi");
        employees.setManager(first.getId(), manager.getId(), null);
        employees.setManager(second.getId(), manager.getId(), null);
        employeeRepository.flush();

        assertThat(employees.directReports(manager.getId())).hasSize(2);
    }

    @Test
    @DisplayName("termination records a leaving date and keeps the record")
    void terminates() {
        Employee hired = hire("EMP-001", "Jean", "Kabila");

        employees.terminate(hired.getId(), LocalDate.of(2026, 3, 31), null);

        assertThat(hired.getStatus()).isEqualTo(EmployeeStatus.TERMINATED);
        assertThat(hired.getTerminationDate()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(employees.get(hired.getId())).isNotNull();
    }

    @Test
    @DisplayName("termination cannot precede the hire date")
    void refusesTerminationBeforeHire() {
        Employee hired = hire("EMP-001", "Jean", "Kabila");

        assertThatThrownBy(() ->
                employees.terminate(hired.getId(), LocalDate.of(2020, 1, 1), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("status cannot be set to terminated without a leaving date")
    void refusesTerminatedViaStatusChange() {
        Employee hired = hire("EMP-001", "Jean", "Kabila");

        assertThatThrownBy(() ->
                employees.changeStatus(hired.getId(), EmployeeStatus.TERMINATED, null))
                .isInstanceOf(IllegalArgumentException.class);

        employees.changeStatus(hired.getId(), EmployeeStatus.ON_LEAVE, null);
        assertThat(hired.getStatus()).isEqualTo(EmployeeStatus.ON_LEAVE);
    }

    @Test
    @DisplayName("headcount counts only active people")
    void headcountExcludesLeavers() {
        hire("EMP-001", "Jean", "Kabila");
        Employee leaver = hire("EMP-002", "Marie", "Ilunga");
        employees.terminate(leaver.getId(), LocalDate.of(2026, 3, 31), null);
        employeeRepository.flush();

        assertThat(employees.headcount()).isEqualTo(1);
    }

    @Test
    @DisplayName("one tenant's people are invisible to another")
    void employeesAreTenantScoped() {
        Employee mine = hire("EMP-001", "Jean", "Kabila");

        assertThat(TenantContext.runAsResult(tenantB, () -> employees.list())).isEmpty();
        assertThatThrownBy(() -> TenantContext.runAs(tenantB, () -> employees.get(mine.getId())))
                .isInstanceOf(EmployeeService.EmployeeNotFoundException.class);
    }
}
