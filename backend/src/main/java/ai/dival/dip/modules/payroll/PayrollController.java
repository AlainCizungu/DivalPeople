package ai.dival.dip.modules.payroll;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Payroll: compensation, components, periods and payslips.
 *
 * <p>Everything here is held to payroll and finance roles. Salary is the most sensitive field in
 * the platform — an HR administrator who can see a directory has no business seeing what everybody
 * earns, which is why this controller does not use the wider HR role set the rest of the modules
 * do.
 *
 * <p>Read {@code docs/PAYROLL_SCOPE.md} before extending. There are no tax rates in this module
 * and adding some would be the most dangerous change anybody could make to this codebase.
 */
@RestController
@RequestMapping("/api/v1/payroll")
public class PayrollController {

    /** Payroll, finance and the tenant administrator. Deliberately narrower than HR_WRITE. */
    private static final String PAYROLL =
            "hasAnyRole('" + Roles.PAYROLL_OFFICER + "', '" + Roles.FINANCE_OFFICER + "', '"
                    + Roles.TENANT_ADMIN + "')";

    /** Approving a run is a financial control, so it excludes the person who prepared it. */
    private static final String APPROVE =
            "hasAnyRole('" + Roles.FINANCE_OFFICER + "', '" + Roles.TENANT_ADMIN + "')";

    private final PayrollService payroll;
    private final CurrentUserService currentUser;

    public PayrollController(PayrollService payroll, CurrentUserService currentUser) {
        this.payroll = payroll;
        this.currentUser = currentUser;
    }

    // --- compensation ------------------------------------------------------

    @GetMapping("/employees/{employeeId}/compensation")
    @PreAuthorize(PAYROLL)
    public List<CompensationResponse> compensation(@PathVariable UUID employeeId) {
        return payroll.compensationHistory(employeeId).stream()
                .map(CompensationResponse::from).toList();
    }

    @PostMapping("/employees/{employeeId}/compensation")
    @PreAuthorize(PAYROLL)
    public ResponseEntity<CompensationResponse> setCompensation(
            @PathVariable UUID employeeId, @Valid @RequestBody SetCompensationRequest r) {
        Compensation saved = payroll.setCompensation(employeeId, r.effectiveFrom(), r.amount(),
                r.currency(), r.payFrequency(), r.reason(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CompensationResponse.from(saved));
    }

    // --- components --------------------------------------------------------

    @GetMapping("/components")
    @PreAuthorize(PAYROLL)
    public List<ComponentResponse> components() {
        return payroll.listComponents().stream().map(ComponentResponse::from).toList();
    }

    @PostMapping("/components")
    @PreAuthorize(PAYROLL)
    public ResponseEntity<ComponentResponse> createComponent(
            @Valid @RequestBody CreateComponentRequest r) {
        PayComponent created = payroll.createComponent(r.code(), r.name(), r.componentType(),
                r.calculation(), r.defaultAmount(), r.percentage(), r.taxable(), r.sortOrder(),
                actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ComponentResponse.from(created));
    }

    @PostMapping("/components/{id}/retire")
    @PreAuthorize(PAYROLL)
    public ComponentResponse retireComponent(@PathVariable UUID id) {
        return ComponentResponse.from(payroll.retireComponent(id, actorId()));
    }

    @GetMapping("/employees/{employeeId}/components")
    @PreAuthorize(PAYROLL)
    public List<AssignmentResponse> assignments(@PathVariable UUID employeeId) {
        return payroll.assignmentsFor(employeeId).stream()
                .map(AssignmentResponse::from).toList();
    }

    @PostMapping("/employees/{employeeId}/components")
    @PreAuthorize(PAYROLL)
    public ResponseEntity<AssignmentResponse> assign(
            @PathVariable UUID employeeId, @Valid @RequestBody AssignRequest r) {
        EmployeePayComponent saved = payroll.assign(employeeId, r.componentId(),
                r.effectiveFrom(), r.amount(), r.percentage(), r.notes(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(AssignmentResponse.from(saved));
    }

    // --- periods and the run -----------------------------------------------

    @GetMapping("/periods")
    @PreAuthorize(PAYROLL)
    public List<PeriodResponse> periods() {
        return payroll.listPeriods().stream().map(PeriodResponse::from).toList();
    }

    @PostMapping("/periods")
    @PreAuthorize(PAYROLL)
    public ResponseEntity<PeriodResponse> createPeriod(@Valid @RequestBody CreatePeriodRequest r) {
        PayrollPeriod created = payroll.createPeriod(r.name(), r.periodStart(), r.periodEnd(),
                r.paymentDate(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(PeriodResponse.from(created));
    }

    /** Returns what the run produced, including who it could not pay and why. */
    @PostMapping("/periods/{id}/calculate")
    @PreAuthorize(PAYROLL)
    public PayrollService.CalculationResult calculate(
            @PathVariable UUID id,
            @RequestParam(required = false) PayFrequency frequency) {
        return payroll.calculate(id, frequency, actorId());
    }

    @PostMapping("/periods/{id}/approve")
    @PreAuthorize(APPROVE)
    public PeriodResponse approve(@PathVariable UUID id,
                                  @Valid @RequestBody ApproveRequest r) {
        return PeriodResponse.from(
                payroll.approve(id, r.approverEmployeeId(), r.notes(), actorId()));
    }

    @PostMapping("/periods/{id}/reopen")
    @PreAuthorize(APPROVE)
    public PeriodResponse reopen(@PathVariable UUID id) {
        return PeriodResponse.from(payroll.reopen(id, actorId()));
    }

    @PostMapping("/periods/{id}/paid")
    @PreAuthorize(APPROVE)
    public PeriodResponse markPaid(@PathVariable UUID id) {
        return PeriodResponse.from(payroll.markPaid(id, actorId()));
    }

    @GetMapping("/periods/{id}/payslips")
    @PreAuthorize(PAYROLL)
    public List<PayslipResponse> payslipsIn(@PathVariable UUID id) {
        return payroll.payslipsIn(id).stream().map(PayslipResponse::from).toList();
    }

    @GetMapping("/employees/{employeeId}/payslips")
    @PreAuthorize(PAYROLL)
    public List<PayslipResponse> payslipsFor(@PathVariable UUID employeeId) {
        return payroll.payslipsFor(employeeId).stream().map(PayslipResponse::from).toList();
    }

    @GetMapping("/payslips/{id}")
    @PreAuthorize(PAYROLL)
    public PayslipResponse payslip(@PathVariable UUID id) {
        return PayslipResponse.from(payroll.payslip(id));
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    // --- requests ----------------------------------------------------------

    public record SetCompensationRequest(
            @NotNull LocalDate effectiveFrom,
            @NotNull BigDecimal amount,
            @NotBlank String currency,
            @NotNull PayFrequency payFrequency,
            String reason) {
    }

    public record CreateComponentRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull ComponentType componentType,
            @NotNull CalculationMethod calculation,
            BigDecimal defaultAmount,
            BigDecimal percentage,
            boolean taxable,
            int sortOrder) {
    }

    public record AssignRequest(
            @NotNull UUID componentId,
            @NotNull LocalDate effectiveFrom,
            BigDecimal amount,
            BigDecimal percentage,
            String notes) {
    }

    public record CreatePeriodRequest(
            @NotBlank String name,
            @NotNull LocalDate periodStart,
            @NotNull LocalDate periodEnd,
            @NotNull LocalDate paymentDate) {
    }

    public record ApproveRequest(@NotNull UUID approverEmployeeId, String notes) {
    }

    // --- responses ---------------------------------------------------------

    public record CompensationResponse(
            UUID id,
            UUID employeeId,
            String employeeName,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            BigDecimal baseAmount,
            String currency,
            PayFrequency payFrequency,
            String reason,
            boolean current) {

        static CompensationResponse from(Compensation compensation) {
            return new CompensationResponse(
                    compensation.getId(),
                    compensation.getEmployee().getId(),
                    compensation.getEmployee().displayName(),
                    compensation.getEffectiveFrom(),
                    compensation.getEffectiveTo(),
                    compensation.getBaseAmount(),
                    compensation.getCurrency(),
                    compensation.getPayFrequency(),
                    compensation.getReason(),
                    compensation.isCurrent());
        }
    }

    public record ComponentResponse(
            UUID id,
            String code,
            String name,
            ComponentType componentType,
            CalculationMethod calculation,
            BigDecimal defaultAmount,
            BigDecimal percentage,
            boolean taxable,
            int sortOrder,
            boolean active) {

        static ComponentResponse from(PayComponent component) {
            return new ComponentResponse(
                    component.getId(),
                    component.getCode(),
                    component.getName(),
                    component.getComponentType(),
                    component.getCalculation(),
                    component.getDefaultAmount(),
                    component.getPercentage(),
                    component.isTaxable(),
                    component.getSortOrder(),
                    component.isActive());
        }
    }

    public record AssignmentResponse(
            UUID id,
            UUID employeeId,
            UUID componentId,
            String componentCode,
            String componentName,
            ComponentType componentType,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            BigDecimal amount,
            BigDecimal percentage,
            String notes) {

        static AssignmentResponse from(EmployeePayComponent assignment) {
            return new AssignmentResponse(
                    assignment.getId(),
                    assignment.getEmployee().getId(),
                    assignment.getComponent().getId(),
                    assignment.getComponent().getCode(),
                    assignment.getComponent().getName(),
                    assignment.getComponent().getComponentType(),
                    assignment.getEffectiveFrom(),
                    assignment.getEffectiveTo(),
                    assignment.getAmount(),
                    assignment.getPercentage(),
                    assignment.getNotes());
        }
    }

    public record PeriodResponse(
            UUID id,
            String name,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate paymentDate,
            PeriodStatus status,
            Instant calculatedAt,
            UUID approverId,
            String approverName,
            Instant approvedAt,
            Instant paidAt,
            String notes) {

        static PeriodResponse from(PayrollPeriod period) {
            return new PeriodResponse(
                    period.getId(),
                    period.getName(),
                    period.getPeriodStart(),
                    period.getPeriodEnd(),
                    period.getPaymentDate(),
                    period.getStatus(),
                    period.getCalculatedAt(),
                    period.getApprover() == null ? null : period.getApprover().getId(),
                    period.getApprover() == null ? null : period.getApprover().displayName(),
                    period.getApprovedAt(),
                    period.getPaidAt(),
                    period.getNotes());
        }
    }

    public record PayslipResponse(
            UUID id,
            UUID periodId,
            UUID employeeId,
            String employeeNumber,
            String employeeName,
            BigDecimal baseAmount,
            String currency,
            BigDecimal grossEarnings,
            BigDecimal totalDeductions,
            BigDecimal employerCost,
            BigDecimal netPay,
            BigDecimal unpaidLeaveDays,
            int absentMinutes,
            int overtimeMinutes,
            List<LineResponse> lines) {

        static PayslipResponse from(Payslip payslip) {
            return new PayslipResponse(
                    payslip.getId(),
                    payslip.getPeriod().getId(),
                    payslip.getEmployee().getId(),
                    payslip.getEmployeeNumber(),
                    payslip.getEmployeeName(),
                    payslip.getBaseAmount(),
                    payslip.getCurrency(),
                    payslip.getGrossEarnings(),
                    payslip.getTotalDeductions(),
                    payslip.getEmployerCost(),
                    payslip.getNetPay(),
                    payslip.getUnpaidLeaveDays(),
                    payslip.getAbsentMinutes(),
                    payslip.getOvertimeMinutes(),
                    payslip.getLines().stream().map(LineResponse::from).toList());
        }
    }

    /** {@code basis} is the line's own explanation of how it reached its figure. */
    public record LineResponse(
            UUID id,
            String componentCode,
            String componentName,
            ComponentType componentType,
            String basis,
            BigDecimal quantity,
            BigDecimal rate,
            BigDecimal amount) {

        static LineResponse from(PayslipLine line) {
            return new LineResponse(
                    line.getId(),
                    line.getComponentCode(),
                    line.getComponentName(),
                    line.getComponentType(),
                    line.getBasis(),
                    line.getQuantity(),
                    line.getRate(),
                    line.getAmount());
        }
    }
}
