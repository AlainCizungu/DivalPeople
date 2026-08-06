package ai.dival.dip.modules.leave;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Adds each month's worth of leave to the people entitled to it.
 *
 * <p>Idempotent by design. Rather than adding a month's slice and hoping the job ran exactly
 * once, it works out what somebody should have accrued by now and tops them up to that figure.
 * A job that runs twice adds nothing the second time, and a job that missed a month catches up
 * on its own — which matters, because scheduled jobs do get run twice and do get missed.
 */
@Component
public class LeaveAccrualJob {

    private static final Logger log = LoggerFactory.getLogger(LeaveAccrualJob.class);

    private final TenantService tenants;
    private final LeaveBalanceService balances;
    private final LeaveTypeRepository types;
    private final LeaveBalanceRepository balanceRepository;
    private final EmployeeService employees;
    private final TransactionTemplate transactionTemplate;

    public LeaveAccrualJob(TenantService tenants, LeaveBalanceService balances,
                           LeaveTypeRepository types, LeaveBalanceRepository balanceRepository,
                           EmployeeService employees, TransactionTemplate transactionTemplate) {
        this.tenants = tenants;
        this.balances = balances;
        this.types = types;
        this.balanceRepository = balanceRepository;
        this.employees = employees;
        this.transactionTemplate = transactionTemplate;
    }

    /** First of the month, early. */
    @Scheduled(cron = "${dip.hr.leave-accrual-cron:0 30 5 1 * *}")
    public void accrue() {
        accrueAsOf(LocalDate.now());
    }

    /** Separated from the schedule so a test can ask for any date without waiting a month. */
    public int accrueAsOf(LocalDate asOf) {
        int credited = 0;

        for (Tenant tenant : tenants.list()) {
            if (!tenant.isActive()) {
                continue;
            }
            try {
                credited += accrueTenant(tenant.getId(), asOf);
            } catch (RuntimeException ex) {
                // One tenant's bad data must not stop every other tenant's accrual.
                log.error("Leave accrual failed for tenant {}", tenant.getId(), ex);
            }
        }

        if (credited > 0) {
            log.info("Leave accrual credited {} balances", credited);
        }
        return credited;
    }

    private int accrueTenant(UUID tenantId, LocalDate asOf) {
        return TenantContext.runAsResult(tenantId, () ->
                transactionTemplate.execute(status -> {
                    List<LeaveType> accruing =
                            types.findByTenantIdAndActiveTrueOrderByNameAsc(tenantId).stream()
                                    .filter(t -> t.getAccrualMethod() == AccrualMethod.MONTHLY_ACCRUAL)
                                    .filter(t -> t.getEntitlementDays().signum() > 0)
                                    .toList();
                    if (accruing.isEmpty()) {
                        return 0;
                    }

                    int credited = 0;
                    for (Employee employee : employees.list()) {
                        if (!employee.getStatus().isEmployed()) {
                            continue;
                        }
                        for (LeaveType type : accruing) {
                            if (topUp(employee, type, asOf)) {
                                credited++;
                            }
                        }
                    }
                    return credited;
                }));
    }

    /**
     * Brings one balance up to what it should be by now.
     *
     * <p>Somebody hired in March has earned nine months by December, not twelve. Counting from
     * the later of the hire date and the start of the year is what makes a mid-year joiner's
     * first balance honest.
     */
    private boolean topUp(Employee employee, LeaveType type, LocalDate asOf) {
        int year = asOf.getYear();
        LocalDate yearStart = LocalDate.of(year, 1, 1);
        LocalDate from = employee.getHireDate().isAfter(yearStart)
                ? employee.getHireDate()
                : yearStart;

        if (from.getYear() > year) {
            // Hired next year already. Nothing has been earned.
            return false;
        }

        // Whole months completed, plus the month in progress: somebody is credited for the month
        // they are living through, which is what people expect when they check in mid-month.
        int months = Math.min(12, asOf.getMonthValue() - from.getMonthValue() + 1);
        if (months <= 0) {
            return false;
        }

        BigDecimal earned = type.accrualForMonths(months);
        LeaveBalance balance =
                balanceRepository.findByTenantIdAndEmployeeIdAndLeaveTypeIdAndLeaveYear(
                                TenantContext.require(), employee.getId(), type.getId(), year)
                        .orElse(null);
        BigDecimal already = balance == null ? BigDecimal.ZERO : balance.getAccruedDays();

        BigDecimal owed = earned.subtract(already);
        if (owed.signum() <= 0) {
            return false;
        }

        balances.grant(employee.getId(), type.getId(), year, owed, LedgerEntryType.ACCRUAL,
                months + " month(s) of " + type.getName(), null);
        return true;
    }
}
