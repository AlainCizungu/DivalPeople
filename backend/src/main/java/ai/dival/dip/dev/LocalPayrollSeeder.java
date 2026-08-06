package ai.dival.dip.dev;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeRepository;
import ai.dival.dip.modules.payroll.CalculationMethod;
import ai.dival.dip.modules.payroll.ComponentType;
import ai.dival.dip.modules.payroll.PayComponent;
import ai.dival.dip.modules.payroll.PayFrequency;
import ai.dival.dip.modules.payroll.PayrollPeriod;
import ai.dival.dip.modules.payroll.PayrollPeriodRepository;
import ai.dival.dip.modules.payroll.PayrollService;
import java.math.BigDecimal;
import java.time.LocalDate;
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
 * Seeds salaries, pay components and one calculated payroll run.
 *
 * <p><strong>The percentages here are invented.</strong> They are plausible-looking round numbers
 * chosen to exercise the calculation, and they are not the DRC's statutory rates. Nothing in this
 * platform contains a real tax table, and this seeder must never become the place somebody looks
 * one up — see docs/PAYROLL_SCOPE.md.
 *
 * <p>The run is left calculated but unapproved, so the sign-off path is something a person can
 * exercise rather than something already done for them.
 *
 * <p>Local profile only.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-payroll", havingValue = "true")
@Order(25) // after learning, before TIX
public class LocalPayrollSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalPayrollSeeder.class);

    private final PayrollService payroll;
    private final PayrollPeriodRepository periods;
    private final EmployeeRepository employees;
    private final TransactionTemplate transactionTemplate;

    public LocalPayrollSeeder(PayrollService payroll, PayrollPeriodRepository periods,
                              EmployeeRepository employees,
                              TransactionTemplate transactionTemplate) {
        this.payroll = payroll;
        this.periods = periods;
        this.employees = employees;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = LocalTenantSeeder.OPERATOR_A;

        TenantContext.runAs(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!periods.findByTenantIdOrderByPeriodStartDesc(tenantId).isEmpty()) {
                return;
            }

            Employee director = employee(tenantId, "EMP-001");
            Employee engineer = employee(tenantId, "EMP-002");
            Employee analyst = employee(tenantId, "EMP-003");
            if (director == null || engineer == null) {
                log.info("No employees to seed payroll for");
                return;
            }

            // Salaries, with a raise in history so the effective-dating is visible rather than
            // theoretical: the engineer was on 1,600 before January.
            pay(director, new BigDecimal("3200"), LocalDate.of(2019, 3, 1), "Appointment");
            pay(engineer, new BigDecimal("1600"), LocalDate.of(2023, 9, 15), "Appointment");
            pay(engineer, new BigDecimal("1850"), LocalDate.of(LocalDate.now().getYear(), 1, 1),
                    "Annual review");
            if (analyst != null) {
                pay(analyst, new BigDecimal("1400"), LocalDate.of(2025, 2, 1), "Appointment");
            }

            PayComponent transport = payroll.createComponent("TRANSPORT", "Transport allowance",
                    ComponentType.EARNING, CalculationMethod.FIXED, new BigDecimal("120"),
                    null, true, 10, null);
            PayComponent housing = payroll.createComponent("HOUSING", "Housing allowance",
                    ComponentType.EARNING, CalculationMethod.PERCENT_OF_BASE, null,
                    new BigDecimal("15"), true, 20, null);

            // INVENTED RATES. Placeholders that exercise the arithmetic, not legal figures.
            PayComponent pension = payroll.createComponent("PENSION", "Pension contribution",
                    ComponentType.DEDUCTION, CalculationMethod.PERCENT_OF_BASE, null,
                    new BigDecimal("5"), false, 200, null);
            PayComponent employerPension = payroll.createComponent("EMP-PENSION",
                    "Employer pension", ComponentType.EMPLOYER_CONTRIBUTION,
                    CalculationMethod.PERCENT_OF_BASE, null, new BigDecimal("8"), false, 400,
                    null);

            LocalDate from = LocalDate.of(2019, 1, 1);
            for (Employee employee : new Employee[] {director, engineer, analyst}) {
                if (employee == null) {
                    continue;
                }
                payroll.assign(employee.getId(), transport.getId(), from, null, null, null, null);
                payroll.assign(employee.getId(), housing.getId(), from, null, null, null, null);
                payroll.assign(employee.getId(), pension.getId(), from, null, null, null, null);
                payroll.assign(employee.getId(), employerPension.getId(), from, null, null,
                        null, null);
            }

            LocalDate monthStart = LocalDate.now().withDayOfMonth(1).minusMonths(1);
            LocalDate monthEnd = monthStart.plusMonths(1).minusDays(1);
            PayrollPeriod period = payroll.createPeriod(
                    monthStart.getMonth() + " " + monthStart.getYear(),
                    monthStart, monthEnd, monthEnd.plusDays(5), null);

            var result = payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);

            // Left unapproved on purpose, so somebody can walk the sign-off themselves.
            log.info("Seeded payroll for {}: {} payslips, {} skipped for want of a salary",
                    period.getName(), result.payslipsProduced(),
                    result.skippedForNoSalary().size());
        }));
    }

    private void pay(Employee employee, BigDecimal amount, LocalDate from, String reason) {
        payroll.setCompensation(employee.getId(), from, amount, "USD", PayFrequency.MONTHLY,
                reason, null);
    }

    private Employee employee(UUID tenantId, String number) {
        return employees.findByTenantIdAndEmployeeNumber(tenantId, number).orElse(null);
    }
}
