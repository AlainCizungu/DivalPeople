package ai.dival.dip.modules.payroll;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Compensation, pay components, periods and the run itself.
 *
 * <p>Read {@code docs/PAYROLL_SCOPE.md} before extending this. In particular: this module
 * contains no tax rates, calculates nothing statutory, and does not move money.
 */
@Service
public class PayrollService {

    private static final Logger log = LoggerFactory.getLogger(PayrollService.class);

    private final CompensationRepository compensation;
    private final PayComponentRepository components;
    private final EmployeePayComponentRepository assignments;
    private final PayrollPeriodRepository periods;
    private final PayslipRepository payslips;
    private final PayrollCalculator calculator;
    private final EmployeeService employees;
    private final AuditService audit;

    public PayrollService(CompensationRepository compensation, PayComponentRepository components,
                          EmployeePayComponentRepository assignments,
                          PayrollPeriodRepository periods, PayslipRepository payslips,
                          PayrollCalculator calculator, EmployeeService employees,
                          AuditService audit) {
        this.compensation = compensation;
        this.components = components;
        this.assignments = assignments;
        this.periods = periods;
        this.payslips = payslips;
        this.calculator = calculator;
        this.employees = employees;
        this.audit = audit;
    }

    // --- compensation ------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Compensation> compensationHistory(UUID employeeId) {
        employees.get(employeeId);
        return compensation.findByTenantIdAndEmployeeIdOrderByEffectiveFromDesc(
                TenantContext.require(), employeeId);
    }

    @Transactional(readOnly = true)
    public Compensation compensationOn(UUID employeeId, LocalDate on) {
        return compensation.findEffectiveOn(TenantContext.require(), employeeId, on)
                .orElseThrow(() -> new PayrollCalculator.NoCompensationException(
                        employees.get(employeeId).displayName(), on));
    }

    /**
     * Records a new salary from a date.
     *
     * <p>Closes the previous record the day before rather than overwriting it. The old figure is
     * what every payslip already issued was calculated from, and a salary history that can be
     * edited is one nobody can defend in a pay-equity review.
     */
    @Transactional
    public Compensation setCompensation(UUID employeeId, LocalDate effectiveFrom,
                                        BigDecimal amount, String currency,
                                        PayFrequency frequency, String reason, UUID actorId) {
        UUID tenantId = TenantContext.require();
        Employee employee = employees.get(employeeId);

        boolean closedOne = compensation.findCurrent(tenantId, employeeId).map(current -> {
            if (!effectiveFrom.isAfter(current.getEffectiveFrom())) {
                throw new ConflictException(
                        "A new salary must start after the one it replaces, which began "
                                + current.getEffectiveFrom());
            }
            current.closeOn(effectiveFrom.minusDays(1));
            return true;
        }).orElse(false);

        if (closedOne) {
            // Flushed deliberately. Hibernate orders inserts before updates at flush, so without
            // this the new open-ended row is written while the old one is still open and
            // uq_compensation_current rejects it. The index is doing its job; the write order
            // was wrong. A deferrable constraint would hide the ordering instead of fixing it.
            compensation.flush();
        }

        Compensation saved = compensation.save(new Compensation(
                employee, effectiveFrom, amount, currency, frequency, reason));
        audit.recordSuccess("COMPENSATION_SET", "Compensation",
                saved.getId().toString(), actorId);
        return saved;
    }

    // --- components --------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PayComponent> listComponents() {
        return components.findByTenantIdOrderBySortOrderAscNameAsc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public PayComponent component(UUID id) {
        return components.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new ComponentNotFoundException(id));
    }

    @Transactional
    public PayComponent createComponent(String code, String name, ComponentType type,
                                        CalculationMethod calculation, BigDecimal defaultAmount,
                                        BigDecimal percentage, boolean taxable, int sortOrder,
                                        UUID actorId) {
        UUID tenantId = TenantContext.require();
        String normalized = PayComponent.normalizeCode(code);

        if (normalized.isBlank()) {
            throw new IllegalArgumentException("A component code is required");
        }
        if (normalized.equals("BASE")) {
            // Basic pay is added by the calculator itself. A second component with the same code
            // would appear twice on the payslip and double somebody's salary.
            throw new ConflictException("BASE is reserved for basic pay");
        }
        if (components.findByTenantIdAndCode(tenantId, normalized).isPresent()) {
            throw new ConflictException("Component code already in use: " + normalized);
        }

        PayComponent component = new PayComponent(normalized, name, type, calculation);
        component.configure(defaultAmount, percentage, taxable, sortOrder);

        PayComponent saved = components.save(component);
        audit.recordSuccess("PAY_COMPONENT_CREATED", "PayComponent",
                saved.getId().toString(), actorId);
        return saved;
    }

    @Transactional
    public PayComponent retireComponent(UUID id, UUID actorId) {
        PayComponent component = component(id);
        component.retire();
        audit.recordSuccess("PAY_COMPONENT_RETIRED", "PayComponent", id.toString(), actorId);
        return component;
    }

    @Transactional(readOnly = true)
    public List<EmployeePayComponent> assignmentsFor(UUID employeeId) {
        employees.get(employeeId);
        return assignments.findByTenantIdAndEmployeeIdOrderByEffectiveFromDesc(
                TenantContext.require(), employeeId);
    }

    /** Attaches a component to somebody from a date, closing any existing assignment of it. */
    @Transactional
    public EmployeePayComponent assign(UUID employeeId, UUID componentId,
                                       LocalDate effectiveFrom, BigDecimal amount,
                                       BigDecimal percentage, String notes, UUID actorId) {
        UUID tenantId = TenantContext.require();
        Employee employee = employees.get(employeeId);
        PayComponent component = component(componentId);

        if (!component.isActive()) {
            throw new ConflictException("This component has been retired");
        }
        assignments.findCurrent(tenantId, employeeId, componentId).ifPresent(current -> {
            if (!effectiveFrom.isAfter(current.getEffectiveFrom())) {
                throw new ConflictException(
                        "A new assignment must start after the one it replaces");
            }
            current.closeOn(effectiveFrom.minusDays(1));
        });

        EmployeePayComponent saved = assignments.save(new EmployeePayComponent(
                employee, component, effectiveFrom, amount, percentage, notes));
        audit.recordSuccess("PAY_COMPONENT_ASSIGNED", "EmployeePayComponent",
                saved.getId().toString(), actorId);
        return saved;
    }

    // --- periods -----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<PayrollPeriod> listPeriods() {
        return periods.findByTenantIdOrderByPeriodStartDesc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public PayrollPeriod period(UUID id) {
        return periods.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new PeriodNotFoundException(id));
    }

    @Transactional
    public PayrollPeriod createPeriod(String name, LocalDate periodStart, LocalDate periodEnd,
                                      LocalDate paymentDate, UUID actorId) {
        UUID tenantId = TenantContext.require();

        periods.findByTenantIdAndPeriodStart(tenantId, periodStart).ifPresent(existing -> {
            throw new ConflictException(
                    "A period already starts on " + periodStart + ": " + existing.getName());
        });

        PayrollPeriod saved = periods.save(
                new PayrollPeriod(name, periodStart, periodEnd, paymentDate));
        audit.recordSuccess("PAYROLL_PERIOD_CREATED", "PayrollPeriod",
                saved.getId().toString(), actorId);
        return saved;
    }

    // --- the run -----------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Payslip> payslipsIn(UUID periodId) {
        period(periodId);
        return payslips.findByTenantIdAndPeriodIdOrderByEmployeeNumberAsc(
                TenantContext.require(), periodId);
    }

    @Transactional(readOnly = true)
    public List<Payslip> payslipsFor(UUID employeeId) {
        employees.get(employeeId);
        return payslips.findByTenantIdAndEmployeeIdOrderByCreatedAtDesc(
                TenantContext.require(), employeeId);
    }

    @Transactional(readOnly = true)
    public Payslip payslip(UUID id) {
        return payslips.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new PayslipNotFoundException(id));
    }

    /**
     * Calculates every payslip in a period.
     *
     * <p>Recalculating discards the previous payslips and builds them again, which is safe only
     * because an approved period refuses to be calculated at all. Somebody without a salary on
     * record is skipped and reported rather than guessed at — inventing a figure is how a person
     * gets paid the wrong amount.
     *
     * @return how many payslips were produced
     */
    @Transactional
    public CalculationResult calculate(UUID periodId, PayFrequency frequency, UUID actorId) {
        UUID tenantId = TenantContext.require();
        PayrollPeriod period = period(periodId);

        if (!period.getStatus().isOpenForCalculation()) {
            throw new ConflictException(
                    "This period has been approved; reopen it before recalculating");
        }

        // Flushed deliberately. Without it Hibernate may order the inserts before the
        // deletes and hit the one-payslip-per-person index on a recalculation.
        payslips.deleteAll(
                payslips.findByTenantIdAndPeriodIdOrderByEmployeeNumberAsc(tenantId, periodId));
        payslips.flush();

        int produced = 0;
        List<String> skipped = new java.util.ArrayList<>();

        for (Employee employee : employees.list()) {
            if (!employee.getStatus().isEmployed()) {
                continue;
            }
            // Asked before calling, not caught afterwards. The calculator refuses to invent a
            // salary, and that refusal is correct — but it used to reach here as an exception
            // thrown across a transactional boundary, which marks the whole run rollback-only.
            // The catch then swallowed it, so calculate() returned a tidy result and the commit
            // failed afterwards with nothing to explain why. One person without a salary took
            // the entire payroll down. Reported, not guessed at, and not thrown either.
            if (compensation.findEffectiveOn(tenantId, employee.getId(), period.getPeriodEnd())
                    .isEmpty()) {
                skipped.add(employee.displayName());
                log.warn("Skipped {} in payroll {}: no salary on record as at {}",
                        employee.displayName(), period.getName(), period.getPeriodEnd());
                continue;
            }

            payslips.save(calculator.calculate(period, employee,
                    frequency == null ? PayFrequency.MONTHLY : frequency));
            produced++;
        }

        period.markCalculated();
        audit.recordSuccess("PAYROLL_CALCULATED", "PayrollPeriod", periodId.toString(), actorId);
        return new CalculationResult(produced, List.copyOf(skipped));
    }

    /**
     * Signs off the run.
     *
     * <p>Nobody approves a payroll they are paid by. It is the oldest financial control there is,
     * and the one most easily lost when the person running payroll is also on it.
     */
    @Transactional
    public PayrollPeriod approve(UUID periodId, UUID approverEmployeeId, String notes,
                                 UUID actorId) {
        UUID tenantId = TenantContext.require();
        PayrollPeriod period = period(periodId);
        Employee approver = employees.get(approverEmployeeId);

        payslips.findByTenantIdAndPeriodIdAndEmployeeId(tenantId, periodId, approverEmployeeId)
                .ifPresent(own -> {
                    throw new SelfApprovalException();
                });

        period.approve(approver, notes);
        audit.recordSuccess("PAYROLL_APPROVED", "PayrollPeriod", periodId.toString(), actorId);
        return period;
    }

    /** Returns an approved period to draft. The approval goes with it. */
    @Transactional
    public PayrollPeriod reopen(UUID periodId, UUID actorId) {
        PayrollPeriod period = period(periodId);
        period.reopen();
        audit.recordSuccess("PAYROLL_REOPENED", "PayrollPeriod", periodId.toString(), actorId);
        return period;
    }

    /** Records that payment was made. This module does not move money. */
    @Transactional
    public PayrollPeriod markPaid(UUID periodId, UUID actorId) {
        PayrollPeriod period = period(periodId);
        period.markPaid();
        audit.recordSuccess("PAYROLL_PAID", "PayrollPeriod", periodId.toString(), actorId);
        return period;
    }

    /** What a run produced, including who it could not pay. */
    public record CalculationResult(int payslipsProduced, List<String> skippedForNoSalary) {
    }

    public static class ComponentNotFoundException extends ResourceNotFoundException {
        public ComponentNotFoundException(UUID id) {
            super("Pay component not found: " + id);
        }
    }

    public static class PeriodNotFoundException extends ResourceNotFoundException {
        public PeriodNotFoundException(UUID id) {
            super("Payroll period not found: " + id);
        }
    }

    public static class PayslipNotFoundException extends ResourceNotFoundException {
        public PayslipNotFoundException(UUID id) {
            super("Payslip not found: " + id);
        }
    }

    public static class SelfApprovalException extends AccessRefusedException {
        public SelfApprovalException() {
            super("Nobody may approve a payroll they are paid by");
        }
    }
}
