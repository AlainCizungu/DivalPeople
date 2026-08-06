package ai.dival.dip.dev;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeRepository;
import ai.dival.dip.modules.leave.AccrualMethod;
import ai.dival.dip.modules.leave.LeaveBalanceService;
import ai.dival.dip.modules.leave.LeaveRequest;
import ai.dival.dip.modules.leave.LeaveRequestService;
import ai.dival.dip.modules.leave.LeaveType;
import ai.dival.dip.modules.leave.LeaveTypeRepository;
import ai.dival.dip.modules.leave.LedgerEntryType;
import ai.dival.dip.modules.leave.PublicHolidayService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
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
 * Seeds leave types, a DRC public holiday calendar, entitlements and a couple of requests.
 *
 * <p>One request is left awaiting a decision so the approval queue is not empty, and one is
 * approved so a balance shows days actually taken. A screen where every number is the starting
 * number demonstrates nothing.
 *
 * <p>Local profile only.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-leave", havingValue = "true")
@Order(21) // after lifecycle, before TIX
public class LocalLeaveSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalLeaveSeeder.class);

    private final LeaveBalanceService balances;
    private final LeaveRequestService requests;
    private final PublicHolidayService holidays;
    private final LeaveTypeRepository types;
    private final EmployeeRepository employees;
    private final TransactionTemplate transactionTemplate;

    public LocalLeaveSeeder(LeaveBalanceService balances, LeaveRequestService requests,
                            PublicHolidayService holidays, LeaveTypeRepository types,
                            EmployeeRepository employees,
                            TransactionTemplate transactionTemplate) {
        this.balances = balances;
        this.requests = requests;
        this.holidays = holidays;
        this.types = types;
        this.employees = employees;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = LocalTenantSeeder.OPERATOR_A;

        TenantContext.runAs(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!types.findByTenantIdOrderByNameAsc(tenantId).isEmpty()) {
                return;
            }

            int year = LocalDate.now().getYear();

            LeaveType annual = balances.createType("ANNUAL", "Annual leave",
                    new BigDecimal("24"), AccrualMethod.MONTHLY_ACCRUAL, null);
            annual.configure(true, new BigDecimal("5"), null, true, false);

            LeaveType sick = balances.createType("SICK", "Sick leave",
                    new BigDecimal("12"), AccrualMethod.ANNUAL_GRANT, null);
            // Beyond two days a certificate is required, and the balance may go negative:
            // statutory sick leave is an entitlement, not a budget.
            sick.configure(true, BigDecimal.ZERO, new BigDecimal("2"), false, true);

            LeaveType unpaid = balances.createType("UNPAID", "Unpaid leave",
                    BigDecimal.ZERO, AccrualMethod.ANNUAL_GRANT, null);
            unpaid.configure(false, BigDecimal.ZERO, null, true, true);

            // Public holidays observed in the DRC. A leave request over one of these must not be
            // charged for it.
            holidays.add(LocalDate.of(year, 1, 1), "New Year's Day", null);
            holidays.add(LocalDate.of(year, 1, 4), "Martyrs' Day", null);
            holidays.add(LocalDate.of(year, 1, 16), "National Heroes' Day", null);
            holidays.add(LocalDate.of(year, 1, 17), "Lumumba Day", null);
            holidays.add(LocalDate.of(year, 5, 1), "Labour Day", null);
            holidays.add(LocalDate.of(year, 5, 17), "Liberation Day", null);
            holidays.add(LocalDate.of(year, 6, 30), "Independence Day", null);
            holidays.add(LocalDate.of(year, 8, 1), "Parents' Day", null);
            holidays.add(LocalDate.of(year, 12, 25), "Christmas Day", null);

            Employee director = employee(tenantId, "EMP-001");
            Employee engineer = employee(tenantId, "EMP-002");
            Employee analyst = employee(tenantId, "EMP-003");
            if (director == null || engineer == null) {
                log.info("Seeded leave types and holidays; no employees to grant entitlements to");
                return;
            }

            // Annual leave accrues monthly, so credit what has actually been earned by now
            // rather than the whole year. Granting twelve months in August would show everybody
            // holding days they have not worked for, which is exactly what the accrual method
            // exists to avoid.
            int monthsSoFar = LocalDate.now().getMonthValue();
            BigDecimal earned = annual.accrualForMonths(monthsSoFar);

            for (Employee employee : new Employee[] {director, engineer, analyst}) {
                if (employee == null) {
                    continue;
                }
                balances.grant(employee.getId(), annual.getId(), year, earned,
                        LedgerEntryType.ACCRUAL, monthsSoFar + " month(s) accrued", null);
                balances.grant(employee.getId(), sick.getId(), year, new BigDecimal("12"),
                        LedgerEntryType.GRANT, year + " entitlement", null);
            }

            // Next Monday to Friday, awaiting a decision: the approval queue should not be empty.
            LocalDate pendingFrom = LocalDate.now().plusWeeks(2).with(DayOfWeek.MONDAY);
            requests.submit(engineer.getId(), annual.getId(), pendingFrom,
                    pendingFrom.plusDays(4), false, false, "Family visit to Lubumbashi",
                    null, null);

            // Six weeks ago, approved by the engineer's manager: a balance that shows days
            // actually taken, and a half day at the end so the arithmetic is visible.
            LocalDate takenFrom = LocalDate.now().minusWeeks(6).with(DayOfWeek.MONDAY);
            LeaveRequest taken = requests.submit(engineer.getId(), annual.getId(), takenFrom,
                    takenFrom.plusDays(2), false, true, "Long weekend", null, null);
            requests.approve(taken.getId(), director.getId(), null, null);

            log.info("Seeded 3 leave types, 9 holidays and 2 requests for operator A");
        }));
    }

    private Employee employee(UUID tenantId, String number) {
        return employees.findByTenantIdAndEmployeeNumber(tenantId, number).orElse(null);
    }
}
