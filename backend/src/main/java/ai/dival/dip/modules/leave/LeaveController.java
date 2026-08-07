package ai.dival.dip.modules.leave;

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
 * Leave: types, balances, requests and decisions.
 *
 * <p>Requests and balances are readable by any authenticated member, because leave is something
 * people manage for themselves and a portal they cannot read is a portal they will ring HR
 * about. Configuring leave types and correcting balances stays with HR.
 */
@RestController
@RequestMapping("/api/v1/leave")
public class LeaveController {

    private static final String AUTHENTICATED = "isAuthenticated()";


    private static final String HR_WRITE =
            "hasAnyRole('" + Roles.HR_ADMIN + "', '" + Roles.HR_MANAGER + "', '"
                    + Roles.TENANT_ADMIN + "')";

    private static final String DECIDE =
            "hasAnyRole('" + Roles.MANAGER + "', '" + Roles.HR_ADMIN + "', '"
                    + Roles.HR_MANAGER + "', '" + Roles.TENANT_ADMIN + "')";

    private final LeaveBalanceService balances;
    private final LeaveRequestService requests;
    private final PublicHolidayService holidays;
    private final CurrentUserService currentUser;

    public LeaveController(LeaveBalanceService balances, LeaveRequestService requests,
                           PublicHolidayService holidays, CurrentUserService currentUser) {
        this.balances = balances;
        this.requests = requests;
        this.holidays = holidays;
        this.currentUser = currentUser;
    }

    // --- types -------------------------------------------------------------

    @GetMapping("/types")
    @PreAuthorize(AUTHENTICATED)
    public List<LeaveTypeResponse> types() {
        return balances.activeTypes().stream().map(LeaveTypeResponse::from).toList();
    }

    @PostMapping("/types")
    @PreAuthorize(HR_WRITE)
    public ResponseEntity<LeaveTypeResponse> createType(
            @Valid @RequestBody CreateTypeRequest request) {
        LeaveType created = balances.createType(request.code(), request.name(),
                request.entitlementDays(), request.accrualMethod(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(LeaveTypeResponse.from(created));
    }

    @PostMapping("/types/{id}/retire")
    @PreAuthorize(HR_WRITE)
    public LeaveTypeResponse retireType(@PathVariable UUID id) {
        return LeaveTypeResponse.from(balances.retireType(id, actorId()));
    }

    // --- balances ----------------------------------------------------------

    @GetMapping("/employees/{employeeId}/balances")
    @PreAuthorize(DECIDE)
    public List<BalanceResponse> balances(@PathVariable UUID employeeId,
                                          @RequestParam(required = false) Integer year) {
        int leaveYear = year == null ? LocalDate.now().getYear() : year;
        return balances.balancesFor(employeeId, leaveYear).stream()
                .map(BalanceResponse::from).toList();
    }

    /** The ledger behind a balance: why it says what it says. */
    @GetMapping("/balances/{id}/ledger")
    @PreAuthorize(DECIDE)
    public List<LedgerResponse> ledger(@PathVariable UUID id) {
        return balances.ledgerFor(id).stream().map(LedgerResponse::from).toList();
    }

    @PostMapping("/balances/adjust")
    @PreAuthorize(HR_WRITE)
    public BalanceResponse adjust(@Valid @RequestBody AdjustRequest request) {
        LeaveBalance adjusted = balances.adjust(request.employeeId(), request.leaveTypeId(),
                request.leaveYear(), request.days(), request.reason(), actorId());
        // Re-read rather than map the locked row: the write path takes a pessimistic lock, and a
        // fetch graph under FOR UPDATE is an outer join Postgres refuses to lock.
        return BalanceResponse.from(balances.balance(adjusted.getId()));
    }

    @PostMapping("/balances/carry-over")
    @PreAuthorize(HR_WRITE)
    public BalanceResponse carryOver(@Valid @RequestBody CarryOverRequest request) {
        LeaveBalance opened = balances.carryOver(request.employeeId(),
                request.leaveTypeId(), request.fromYear(), actorId());
        return BalanceResponse.from(balances.balance(opened.getId()));
    }

    // --- requests ----------------------------------------------------------

    @GetMapping("/requests/pending")
    @PreAuthorize(DECIDE)
    @PreAuthorize(DECIDE)
    public List<RequestResponse> pending() {
        return requests.awaitingDecision().stream().map(RequestResponse::from).toList();
    }

    @GetMapping("/employees/{employeeId}/requests")
    @PreAuthorize(DECIDE)
    public List<RequestResponse> forEmployee(@PathVariable UUID employeeId) {
        return requests.forEmployee(employeeId).stream().map(RequestResponse::from).toList();
    }

    /**
     * Who is off. Open to any member: cover planning is everybody's problem.
     *
     * <p>Deliberately the one leave endpoint the whole tenant can read, and the response carries
     * dates and names only — {@code RequestResponse} also exposes the reason and the supporting
     * document id, which is why every other read here is behind DECIDE.
     */
    @GetMapping("/calendar")
    @PreAuthorize(AUTHENTICATED)
    public List<RequestResponse> calendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return requests.approvedBetween(from, to).stream().map(RequestResponse::from).toList();
    }

    @PostMapping("/requests")
    @PreAuthorize(HR_WRITE)
    public ResponseEntity<RequestResponse> submit(@Valid @RequestBody SubmitRequest request) {
        LeaveRequest submitted = requests.submit(
                request.employeeId(), request.leaveTypeId(), request.startDate(),
                request.endDate(), request.halfDayStart(), request.halfDayEnd(),
                request.reason(), request.documentId(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(RequestResponse.from(submitted));
    }

    @PostMapping("/requests/{id}/approve")
    @PreAuthorize(DECIDE)
    public RequestResponse approve(@PathVariable UUID id,
                                   @Valid @RequestBody DecisionRequest request) {
        return RequestResponse.from(requests.approve(
                id, request.approverEmployeeId(), request.notes(), actorId()));
    }

    @PostMapping("/requests/{id}/reject")
    @PreAuthorize(DECIDE)
    public RequestResponse reject(@PathVariable UUID id,
                                  @Valid @RequestBody DecisionRequest request) {
        return RequestResponse.from(requests.reject(
                id, request.approverEmployeeId(), request.notes(), actorId()));
    }

    /** Withdrawing is the employee's own action, so it is not held behind a manager role. */
    @PostMapping("/requests/{id}/cancel")
    @PreAuthorize(DECIDE)
    public RequestResponse cancel(@PathVariable UUID id) {
        return RequestResponse.from(requests.cancel(id, actorId()));
    }

    // --- holidays ----------------------------------------------------------

    @GetMapping("/holidays")
    @PreAuthorize(AUTHENTICATED)
    public List<HolidayResponse> holidays() {
        return holidays.list().stream().map(HolidayResponse::from).toList();
    }

    @PostMapping("/holidays")
    @PreAuthorize(HR_WRITE)
    public ResponseEntity<HolidayResponse> addHoliday(
            @Valid @RequestBody HolidayRequest request) {
        PublicHoliday added = holidays.add(request.holidayDate(), request.name(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(HolidayResponse.from(added));
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    // --- requests ----------------------------------------------------------

    public record CreateTypeRequest(
            @NotBlank String code,
            @NotBlank String name,
            @NotNull BigDecimal entitlementDays,
            @NotNull AccrualMethod accrualMethod) {
    }

    public record SubmitRequest(
            @NotNull UUID employeeId,
            @NotNull UUID leaveTypeId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            boolean halfDayStart,
            boolean halfDayEnd,
            String reason,
            UUID documentId) {
    }

    public record DecisionRequest(
            @NotNull UUID approverEmployeeId,
            String notes) {
    }

    public record AdjustRequest(
            @NotNull UUID employeeId,
            @NotNull UUID leaveTypeId,
            int leaveYear,
            @NotNull BigDecimal days,
            @NotBlank String reason) {
    }

    public record CarryOverRequest(
            @NotNull UUID employeeId,
            @NotNull UUID leaveTypeId,
            int fromYear) {
    }

    public record HolidayRequest(
            @NotNull LocalDate holidayDate,
            @NotBlank String name) {
    }

    // --- responses ---------------------------------------------------------

    public record LeaveTypeResponse(
            UUID id,
            String code,
            String name,
            boolean paid,
            AccrualMethod accrualMethod,
            BigDecimal entitlementDays,
            BigDecimal carryoverMaxDays,
            BigDecimal documentAfterDays,
            boolean allowsHalfDay,
            boolean allowsNegative,
            boolean active) {

        static LeaveTypeResponse from(LeaveType type) {
            return new LeaveTypeResponse(
                    type.getId(),
                    type.getCode(),
                    type.getName(),
                    type.isPaid(),
                    type.getAccrualMethod(),
                    type.getEntitlementDays(),
                    type.getCarryoverMaxDays(),
                    type.getDocumentAfterDays(),
                    type.isAllowsHalfDay(),
                    type.isAllowsNegative(),
                    type.isActive());
        }
    }

    /** Every figure that goes into the total, so the number never has to be taken on trust. */
    public record BalanceResponse(
            UUID id,
            UUID employeeId,
            UUID leaveTypeId,
            String leaveTypeName,
            int leaveYear,
            BigDecimal openingDays,
            BigDecimal accruedDays,
            BigDecimal takenDays,
            BigDecimal pendingDays,
            BigDecimal adjustmentDays,
            BigDecimal availableDays) {

        static BalanceResponse from(LeaveBalance balance) {
            return new BalanceResponse(
                    balance.getId(),
                    balance.getEmployee().getId(),
                    balance.getLeaveType().getId(),
                    balance.getLeaveType().getName(),
                    balance.getLeaveYear(),
                    balance.getOpeningDays(),
                    balance.getAccruedDays(),
                    balance.getTakenDays(),
                    balance.getPendingDays(),
                    balance.getAdjustmentDays(),
                    balance.available());
        }
    }

    public record LedgerResponse(
            UUID id,
            LedgerEntryType entryType,
            BigDecimal days,
            UUID requestId,
            String reason,
            Instant createdAt) {

        static LedgerResponse from(LeaveLedgerEntry entry) {
            return new LedgerResponse(
                    entry.getId(),
                    entry.getEntryType(),
                    entry.getDays(),
                    entry.getRequest() == null ? null : entry.getRequest().getId(),
                    entry.getReason(),
                    entry.getCreatedAt());
        }
    }

    public record RequestResponse(
            UUID id,
            UUID employeeId,
            String employeeName,
            UUID leaveTypeId,
            String leaveTypeName,
            LocalDate startDate,
            LocalDate endDate,
            boolean halfDayStart,
            boolean halfDayEnd,
            BigDecimal days,
            String reason,
            LeaveRequestStatus status,
            UUID approverId,
            String approverName,
            Instant decidedAt,
            String decisionNotes) {

        static RequestResponse from(LeaveRequest request) {
            return new RequestResponse(
                    request.getId(),
                    request.getEmployee().getId(),
                    request.getEmployee().displayName(),
                    request.getLeaveType().getId(),
                    request.getLeaveType().getName(),
                    request.getStartDate(),
                    request.getEndDate(),
                    request.isHalfDayStart(),
                    request.isHalfDayEnd(),
                    request.getDays(),
                    request.getReason(),
                    request.getStatus(),
                    request.getApprover() == null ? null : request.getApprover().getId(),
                    request.getApprover() == null ? null : request.getApprover().displayName(),
                    request.getDecidedAt(),
                    request.getDecisionNotes());
        }
    }

    public record HolidayResponse(UUID id, LocalDate holidayDate, String name) {

        static HolidayResponse from(PublicHoliday holiday) {
            return new HolidayResponse(holiday.getId(), holiday.getHolidayDate(),
                    holiday.getName());
        }
    }
}
