package ai.dival.dip.modules.leave;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The accrual job runs its own transactions, so this test does not open one.
 *
 * <p>That means rows survive the test and each run works in its own tenant. The alternative — a
 * transactional test — would roll back before the job could commit anything worth asserting on.
 */
@RequiresDocker
class LeaveAccrualJobTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private LeaveBalanceService balances;
    @Autowired
    private LeaveAccrualJob job;

    private UUID tenantId;
    private LeaveType monthly;

    @BeforeEach
    void setUp() {
        tenantId = tenants.save(new Tenant("A A", "acc-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);

        monthly = balances.createType("ANNUAL", "Annual leave", new BigDecimal("24"),
                AccrualMethod.MONTHLY_ACCRUAL, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Employee hire(String number, LocalDate hireDate) {
        return employees.hire(number, "Didier", "Lokwa", hireDate, null, null);
    }

    private BigDecimal accrued(Employee employee, int year) {
        return balances.balancesFor(employee.getId(), year).stream()
                .filter(b -> b.getLeaveType().getId().equals(monthly.getId()))
                .map(LeaveBalance::getAccruedDays)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("somebody employed all year accrues a month at a time")
    void accruesMonthly() {
        Employee employee = hire("EMP-600", LocalDate.of(2020, 1, 6));

        // End of March: three months of a 24-day entitlement is 6 days.
        job.accrueAsOf(LocalDate.of(2026, 3, 31));

        assertThat(accrued(employee, 2026)).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("running the job twice does not pay twice")
    void isIdempotent() {
        Employee employee = hire("EMP-601", LocalDate.of(2020, 1, 6));

        job.accrueAsOf(LocalDate.of(2026, 3, 31));
        job.accrueAsOf(LocalDate.of(2026, 3, 31));

        assertThat(accrued(employee, 2026)).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("a missed month is caught up rather than lost")
    void catchesUp() {
        Employee employee = hire("EMP-602", LocalDate.of(2020, 1, 6));

        job.accrueAsOf(LocalDate.of(2026, 2, 28));
        // March and April never ran. June should still bring them to six months' worth.
        job.accrueAsOf(LocalDate.of(2026, 6, 30));

        assertThat(accrued(employee, 2026)).isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("a mid-year joiner accrues from their hire date, not from January")
    void prorataForMidYearJoiner() {
        Employee employee = hire("EMP-603", LocalDate.of(2026, 4, 15));

        // April to June inclusive is three months, not six.
        job.accrueAsOf(LocalDate.of(2026, 6, 30));

        assertThat(accrued(employee, 2026)).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("somebody who has left does not keep accruing")
    void stopsAtTermination() {
        Employee employee = hire("EMP-604", LocalDate.of(2020, 1, 6));
        employees.terminate(employee.getId(), LocalDate.of(2026, 2, 27), null);

        job.accrueAsOf(LocalDate.of(2026, 6, 30));

        assertThat(accrued(employee, 2026)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a whole-year grant is not touched by the monthly job")
    void ignoresAnnualGrantTypes() {
        Employee employee = hire("EMP-605", LocalDate.of(2020, 1, 6));
        LeaveType granted = balances.createType("SICK", "Sick leave", new BigDecimal("12"),
                AccrualMethod.ANNUAL_GRANT, null);

        job.accrueAsOf(LocalDate.of(2026, 6, 30));

        assertThat(balances.balancesFor(employee.getId(), 2026).stream()
                .filter(b -> b.getLeaveType().getId().equals(granted.getId()))
                .findFirst())
                .isEmpty();
    }
}
