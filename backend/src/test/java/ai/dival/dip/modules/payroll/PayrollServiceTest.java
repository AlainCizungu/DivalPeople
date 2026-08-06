package ai.dival.dip.modules.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
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
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

/**
 * The arithmetic a payslip rests on, and the controls around signing it off.
 *
 * <p>There are no tax rates here because there are none in the module. The percentages used are
 * arbitrary test values, and that is the point: the calculation is exercised, the rates are
 * configuration. See docs/PAYROLL_SCOPE.md.
 */
@Transactional
@RequiresDocker
class PayrollServiceTest extends AbstractIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 6, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 6, 30);
    private static final LocalDate PAY_DAY = LocalDate.of(2026, 7, 5);

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private PayrollService payroll;

    private Employee employee;
    private Employee financeOfficer;

    @BeforeEach
    void setUp() {
        UUID tenantId = tenants.save(new Tenant("PR", "pr-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);

        employee = employees.hire("EMP-001", "Didier", "Lokwa", LocalDate.of(2024, 2, 5),
                null, null);
        financeOfficer = employees.hire("EMP-002", "Sylvie", "Mbala", LocalDate.of(2019, 1, 7),
                null, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void payThem(BigDecimal amount, LocalDate from) {
        payroll.setCompensation(employee.getId(), from, amount, "USD",
                PayFrequency.MONTHLY, "Test salary", null);
    }

    private PayrollPeriod june() {
        return payroll.createPeriod("June 2026", PERIOD_START, PERIOD_END, PAY_DAY, null);
    }

    private Payslip onlyPayslip(PayrollPeriod period) {
        return payroll.payslipsIn(period.getId()).stream()
                .filter(slip -> slip.getEmployee().getId().equals(employee.getId()))
                .findFirst()
                .orElseThrow();
    }

    // --- compensation ------------------------------------------------------

    @Test
    @DisplayName("a new salary closes the previous one rather than overwriting it")
    void salaryHistoryIsPreserved() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        payThem(new BigDecimal("1200"), LocalDate.of(2026, 1, 1));

        var history = payroll.compensationHistory(employee.getId());

        assertThat(history).hasSize(2);
        // The old row survives, closed the day before the new one starts.
        assertThat(history.get(1).getEffectiveTo()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(history.get(0).getEffectiveTo()).isNull();
    }

    @Test
    @DisplayName("a payroll run uses the salary in force then, not the one in force now")
    void payrollUsesHistoricalSalary() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        payThem(new BigDecimal("1200"), LocalDate.of(2026, 7, 1));

        // June is paid at the old rate even though a raise has already been recorded for July.
        assertThat(payroll.compensationOn(employee.getId(), PERIOD_END).getBaseAmount())
                .isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("a salary cannot start before the one it replaces")
    void refusesBackdatedSalaryBeforeCurrent() {
        payThem(new BigDecimal("1000"), LocalDate.of(2026, 1, 1));

        assertThatThrownBy(() -> payThem(new BigDecimal("1200"), LocalDate.of(2025, 6, 1)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("an amount without a currency is not a salary")
    void refusesSalaryWithoutCurrency() {
        assertThatThrownBy(() -> payroll.setCompensation(employee.getId(),
                LocalDate.of(2026, 1, 1), new BigDecimal("1000"), "  ",
                PayFrequency.MONTHLY, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- components --------------------------------------------------------

    @Test
    @DisplayName("BASE is reserved, because the calculator adds it itself")
    void refusesReservedComponentCode() {
        assertThatThrownBy(() -> payroll.createComponent("base", "Something else",
                ComponentType.EARNING, CalculationMethod.FIXED, new BigDecimal("50"),
                null, true, 10, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a percentage calculation without a percentage is refused, not silently zero")
    void refusesPercentageComponentWithoutRate() {
        assertThatThrownBy(() -> payroll.createComponent("PENSION", "Pension",
                ComponentType.DEDUCTION, CalculationMethod.PERCENT_OF_BASE, null,
                null, false, 200, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- the calculation ---------------------------------------------------

    @Test
    @DisplayName("a payslip with only basic pay is gross, no deductions, net the same")
    void basicPayOnly() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        PayrollPeriod period = june();

        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);
        Payslip slip = onlyPayslip(period);

        assertThat(slip.getGrossEarnings()).isEqualByComparingTo("1000");
        assertThat(slip.getTotalDeductions()).isEqualByComparingTo("0");
        assertThat(slip.getNetPay()).isEqualByComparingTo("1000");
        assertThat(slip.getLines()).hasSize(1);
    }

    @Test
    @DisplayName("the totals are always the sum of the lines")
    void totalsReconcileWithLines() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));

        PayComponent transport = payroll.createComponent("TRANSPORT", "Transport allowance",
                ComponentType.EARNING, CalculationMethod.FIXED, new BigDecimal("150"),
                null, true, 10, null);
        PayComponent pension = payroll.createComponent("PENSION", "Pension",
                ComponentType.DEDUCTION, CalculationMethod.PERCENT_OF_BASE, null,
                new BigDecimal("5"), false, 200, null);

        payroll.assign(employee.getId(), transport.getId(), LocalDate.of(2024, 2, 5),
                null, null, null, null);
        payroll.assign(employee.getId(), pension.getId(), LocalDate.of(2024, 2, 5),
                null, null, null, null);

        PayrollPeriod period = june();
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);
        Payslip slip = onlyPayslip(period);

        // 1000 base + 150 transport = 1150 gross; 5% of base = 50 deduction; net 1100.
        assertThat(slip.getGrossEarnings()).isEqualByComparingTo("1150");
        assertThat(slip.getTotalDeductions()).isEqualByComparingTo("50");
        assertThat(slip.getNetPay()).isEqualByComparingTo("1100");

        BigDecimal summedEarnings = slip.getLines().stream()
                .filter(line -> line.getComponentType() == ComponentType.EARNING)
                .map(PayslipLine::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(summedEarnings).isEqualByComparingTo(slip.getGrossEarnings());
    }

    @Test
    @DisplayName("a percentage of gross sees every earning and no deduction")
    void percentOfGrossSeesEarningsOnly() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));

        PayComponent transport = payroll.createComponent("TRANSPORT", "Transport allowance",
                ComponentType.EARNING, CalculationMethod.FIXED, new BigDecimal("200"),
                null, true, 10, null);
        PayComponent levy = payroll.createComponent("LEVY", "Payroll levy",
                ComponentType.DEDUCTION, CalculationMethod.PERCENT_OF_GROSS, null,
                new BigDecimal("10"), false, 300, null);
        PayComponent union = payroll.createComponent("UNION", "Union dues",
                ComponentType.DEDUCTION, CalculationMethod.FIXED, new BigDecimal("25"),
                null, false, 100, null);

        payroll.assign(employee.getId(), transport.getId(), LocalDate.of(2024, 2, 5),
                null, null, null, null);
        payroll.assign(employee.getId(), levy.getId(), LocalDate.of(2024, 2, 5),
                null, null, null, null);
        payroll.assign(employee.getId(), union.getId(), LocalDate.of(2024, 2, 5),
                null, null, null, null);

        PayrollPeriod period = june();
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);
        Payslip slip = onlyPayslip(period);

        // Gross is 1200. The levy is 10% of that = 120, unaffected by the union dues, which is
        // what makes the figure independent of the order the rows were inserted in.
        assertThat(slip.getGrossEarnings()).isEqualByComparingTo("1200");
        assertThat(slip.getTotalDeductions()).isEqualByComparingTo("145");
        assertThat(slip.getNetPay()).isEqualByComparingTo("1055");
    }

    @Test
    @DisplayName("an employer contribution is a cost, never taken off anybody's net")
    void employerContributionDoesNotReduceNet() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));

        PayComponent employerPension = payroll.createComponent("EMP-PENSION",
                "Employer pension", ComponentType.EMPLOYER_CONTRIBUTION,
                CalculationMethod.PERCENT_OF_BASE, null, new BigDecimal("8"), false, 400, null);
        payroll.assign(employee.getId(), employerPension.getId(), LocalDate.of(2024, 2, 5),
                null, null, null, null);

        PayrollPeriod period = june();
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);
        Payslip slip = onlyPayslip(period);

        assertThat(slip.getEmployerCost()).isEqualByComparingTo("80");
        assertThat(slip.getNetPay()).isEqualByComparingTo("1000");
        assertThat(slip.getGrossEarnings()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("a line records how its figure was reached")
    void linesShowTheirWorking() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        PayComponent pension = payroll.createComponent("PENSION", "Pension",
                ComponentType.DEDUCTION, CalculationMethod.PERCENT_OF_BASE, null,
                new BigDecimal("5"), false, 200, null);
        payroll.assign(employee.getId(), pension.getId(), LocalDate.of(2024, 2, 5),
                null, null, null, null);

        PayrollPeriod period = june();
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);

        PayslipLine line = onlyPayslip(period).getLines().stream()
                .filter(l -> l.getComponentCode().equals("PENSION"))
                .findFirst().orElseThrow();

        assertThat(line.getBasis()).isEqualTo("5% of basic pay");
        assertThat(line.getRate()).isEqualByComparingTo("5");
        assertThat(line.getQuantity()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("a component assigned after the period does not appear on it")
    void assignmentsAreEffectiveDated() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        PayComponent bonus = payroll.createComponent("HOUSING", "Housing allowance",
                ComponentType.EARNING, CalculationMethod.FIXED, new BigDecimal("300"),
                null, true, 10, null);
        // Starts in July; June must not see it.
        payroll.assign(employee.getId(), bonus.getId(), LocalDate.of(2026, 7, 1),
                null, null, null, null);

        PayrollPeriod period = june();
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);

        assertThat(onlyPayslip(period).getGrossEarnings()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("somebody with no salary on record is reported, not guessed at")
    void missingSalaryIsReported() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        PayrollPeriod period = june();

        var result = payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);

        assertThat(result.payslipsProduced()).isEqualTo(1);
        assertThat(result.skippedForNoSalary()).containsExactly(financeOfficer.displayName());

        // The assertion this test was missing, and the only form of it that works. Skipping
        // somebody used to leave the transaction marked rollback-only, so calculate() returned
        // this tidy result and the commit failed afterwards with nothing pointing at why.
        //
        // A rolled-back test cannot see that: the damage only shows at commit, which is why the
        // whole suite passed while payroll was broken. So this one test commits, deliberately.
        // Each test builds its own tenant, so the rows it leaves behind are nobody else's
        // business.
        assertThatCode(() -> {
            TestTransaction.flagForCommit();
            TestTransaction.end();
        }).as("a skipped employee must not poison the run").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the payslip snapshots the name and number, not a live reference")
    void payslipIsASnapshot() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        PayrollPeriod period = june();
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);

        Payslip slip = onlyPayslip(period);
        assertThat(slip.getEmployeeName()).isEqualTo("Didier Lokwa");

        employees.updateDetails(employee.getId(), "Didier", "Kabongo", null, null, null,
                null, null, null);

        // The payslip still says what it said the day it was issued.
        assertThat(slip.getEmployeeName()).isEqualTo("Didier Lokwa");
    }

    // --- sign-off ----------------------------------------------------------

    @Test
    @DisplayName("only a calculated period can be approved")
    void refusesApprovingDraft() {
        PayrollPeriod period = june();

        assertThatThrownBy(() ->
                payroll.approve(period.getId(), financeOfficer.getId(), null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("nobody approves a payroll they are paid by")
    void refusesSelfApproval() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        PayrollPeriod period = june();
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);

        assertThatThrownBy(() ->
                payroll.approve(period.getId(), employee.getId(), null, null))
                .isInstanceOf(PayrollService.SelfApprovalException.class);
    }

    @Test
    @DisplayName("somebody not on the payroll may approve it")
    void approverNotOnPayrollIsAllowed() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        PayrollPeriod period = june();
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);

        payroll.approve(period.getId(), financeOfficer.getId(), "Checked against the register",
                null);

        assertThat(period.getStatus()).isEqualTo(PeriodStatus.APPROVED);
        assertThat(period.getApprover().getId()).isEqualTo(financeOfficer.getId());
        assertThat(period.getApprovedAt()).isNotNull();
    }

    @Test
    @DisplayName("an approved period cannot be recalculated underneath its approval")
    void refusesRecalculatingApprovedPeriod() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        PayrollPeriod period = june();
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);
        payroll.approve(period.getId(), financeOfficer.getId(), null, null);

        assertThatThrownBy(() ->
                payroll.calculate(period.getId(), PayFrequency.MONTHLY, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("reopening takes the approval with it")
    void reopeningClearsTheApproval() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        PayrollPeriod period = june();
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);
        payroll.approve(period.getId(), financeOfficer.getId(), null, null);

        payroll.reopen(period.getId(), null);

        assertThat(period.getStatus()).isEqualTo(PeriodStatus.DRAFT);
        // The signature does not survive, so the run has to be approved again.
        assertThat(period.getApprover()).isNull();
        assertThat(period.getApprovedAt()).isNull();
    }

    @Test
    @DisplayName("only an approved period can be marked paid")
    void refusesPayingUnapprovedPeriod() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        PayrollPeriod period = june();
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);

        assertThatThrownBy(() -> payroll.markPaid(period.getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a paid period cannot be cancelled")
    void refusesCancellingPaidPeriod() {
        payThem(new BigDecimal("1000"), LocalDate.of(2024, 2, 5));
        PayrollPeriod period = june();
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);
        payroll.approve(period.getId(), financeOfficer.getId(), null, null);
        payroll.markPaid(period.getId(), null);

        assertThatThrownBy(period::cancel).isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("two periods cannot start on the same day")
    void refusesDuplicatePeriodStart() {
        june();

        assertThatThrownBy(() ->
                payroll.createPeriod("June again", PERIOD_START, PERIOD_END, PAY_DAY, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("one tenant's payroll is invisible to another")
    void payrollDoesNotCrossTenants() {
        PayrollPeriod period = june();

        UUID tenantB = tenants.save(new Tenant("PR B", "pr-b-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantB);

        assertThatThrownBy(() -> payroll.period(period.getId()))
                .isInstanceOf(PayrollService.PeriodNotFoundException.class);
    }
}
