package ai.dival.dip.modules.payroll;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.attendance.TimesheetService;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.leave.LeaveRequest;
import ai.dival.dip.modules.leave.LeaveRequestService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a person, a period and their configuration into a payslip.
 *
 * <p>Contains no tax rates and no statutory thresholds. It applies whatever components are
 * configured, in a defined order, and records on every line how the figure was reached. What the
 * rates should be is an accountant's decision — see {@code docs/PAYROLL_SCOPE.md} for why that
 * boundary is where it is.
 *
 * <p>Order is the part worth understanding. Earnings are applied first, then deductions, and
 * within each group by the component's own order. A percentage-of-gross deduction therefore sees
 * every earning and no other deduction, which makes the result the same however the rows happened
 * to be inserted.
 */
@Component
public class PayrollCalculator {

    private final CompensationRepository compensation;
    private final EmployeePayComponentRepository assignments;
    private final LeaveRequestService leave;
    private final TimesheetService timesheets;

    public PayrollCalculator(CompensationRepository compensation,
                             EmployeePayComponentRepository assignments,
                             LeaveRequestService leave, TimesheetService timesheets) {
        this.compensation = compensation;
        this.assignments = assignments;
        this.leave = leave;
        this.timesheets = timesheets;
    }

    /**
     * Builds one payslip.
     *
     * <p>The salary used is the one in force on the last day of the period, which is the
     * convention most payrolls follow. A mid-period raise is therefore paid in full for that
     * period; proration is not implemented and that limitation is recorded in the scope document
     * rather than approximated here.
     */
    @Transactional
    public Payslip calculate(PayrollPeriod period, Employee employee, PayFrequency frequency) {
        UUID tenantId = TenantContext.require();
        LocalDate on = period.getPeriodEnd();

        Compensation salary = compensation
                .findEffectiveOn(tenantId, employee.getId(), on)
                .orElseThrow(() -> new NoCompensationException(employee.displayName(), on));

        BigDecimal base = salary.amountForPeriod(frequency);
        Payslip payslip = new Payslip(period, employee, base, salary.getCurrency());

        // One preview, not two: it queries entries and leave for the whole period, and
        // calling it per figure doubles the cost of every payroll run.
        TimesheetService.Totals worked = timesheets.preview(
                employee.getId(), period.getPeriodStart(), period.getPeriodEnd());
        payslip.setInputs(unpaidLeaveDays(employee, period), worked.absent(), worked.overtime());

        // Base pay first, so a percentage-of-gross component has something to work from.
        payslip.addLine("BASE", "Basic pay", ComponentType.EARNING,
                "Salary in force on " + on, null, null, base, 0);

        List<EmployeePayComponent> effective =
                assignments.findEffectiveOn(tenantId, employee.getId(), on);

        // Earnings before deductions, so percentage-of-gross sees all earnings and no deductions.
        applyAll(payslip, effective, base, ComponentType.EARNING);
        applyAll(payslip, effective, base, ComponentType.EMPLOYER_CONTRIBUTION);
        applyAll(payslip, effective, base, ComponentType.DEDUCTION);

        return payslip;
    }

    private void applyAll(Payslip payslip, List<EmployeePayComponent> effective, BigDecimal base,
                          ComponentType type) {
        for (EmployeePayComponent assignment : effective) {
            PayComponent component = assignment.getComponent();
            if (component.getComponentType() != type) {
                continue;
            }

            Line line = amountFor(assignment, component, base, payslip);
            if (line == null) {
                continue;
            }
            payslip.addLine(component.getCode(), component.getName(), type, line.basis(),
                    line.quantity(), line.rate(), line.amount(), component.getSortOrder());
        }
    }

    /**
     * Works out one component's amount, and says in words how it got there.
     *
     * @return null when the component contributes nothing this period, which is different from
     *         contributing zero and is left off the payslip rather than shown as a zero line
     */
    private Line amountFor(EmployeePayComponent assignment, PayComponent component,
                           BigDecimal base, Payslip payslip) {
        return switch (component.getCalculation()) {
            case FIXED, MANUAL -> {
                BigDecimal amount = assignment.effectiveAmount();
                yield amount == null || amount.signum() == 0
                        ? null
                        : new Line("Fixed amount", null, null, amount);
            }
            case PERCENT_OF_BASE -> {
                BigDecimal rate = assignment.effectivePercentage();
                yield rate == null
                        ? null
                        : new Line(rate.stripTrailingZeros().toPlainString() + "% of basic pay",
                                base, rate, percentOf(base, rate));
            }
            case PERCENT_OF_GROSS -> {
                BigDecimal rate = assignment.effectivePercentage();
                BigDecimal gross = payslip.taxableGrossSoFar();
                yield rate == null
                        ? null
                        : new Line(rate.stripTrailingZeros().toPlainString() + "% of gross",
                                gross, rate, percentOf(gross, rate));
            }
            case PER_HOUR -> {
                BigDecimal rate = assignment.effectiveAmount();
                BigDecimal hours = BigDecimal.valueOf(payslip.getOvertimeMinutes())
                        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
                yield rate == null || hours.signum() == 0
                        ? null
                        : new Line(hours.toPlainString() + " hours", hours, rate,
                                rate.multiply(hours).setScale(2, RoundingMode.HALF_UP));
            }
        };
    }

    /**
     * Rounds at the line, once.
     *
     * <p>Half-up at each line rather than at the total, so every figure on the payslip is the one
     * that was added up. Rounding only the total would produce a document whose lines do not sum
     * to it, which is the one thing a payslip must never do.
     */
    private BigDecimal percentOf(BigDecimal amount, BigDecimal percentage) {
        return amount.multiply(percentage)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    /** Days of approved unpaid leave falling in the period. */
    private BigDecimal unpaidLeaveDays(Employee employee, PayrollPeriod period) {
        return leave.approvedBetween(period.getPeriodStart(), period.getPeriodEnd()).stream()
                .filter(request -> request.getEmployee().getId().equals(employee.getId()))
                .filter(request -> !request.getLeaveType().isPaid())
                .map(LeaveRequest::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private record Line(String basis, BigDecimal quantity, BigDecimal rate, BigDecimal amount) {
    }

    /** Refused rather than assumed: guessing a salary is how somebody gets paid the wrong figure. */
    public static class NoCompensationException extends ConflictException {
        public NoCompensationException(String employee, LocalDate on) {
            super("No salary on record for " + employee + " as at " + on);
        }
    }
}
