package ai.dival.dip.modules.leave;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.files.FileService;
import ai.dival.dip.modules.notifications.Notification;
import ai.dival.dip.modules.notifications.NotificationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Requesting leave, and deciding on it.
 *
 * <p>The rule that shapes everything here: days are reserved when a request is submitted, not
 * when it is approved. Two requests that each fit the balance could otherwise both be approved,
 * and the person would find out months later that they had been overdrawn all along.
 *
 * <p>Every path that moves days does so through {@link LeaveBalanceService}, which writes a
 * ledger entry in the same transaction. There is no way to change a balance quietly.
 */
@Service
public class LeaveRequestService {

    private final LeaveRequestRepository requests;
    private final LeaveBalanceService balances;
    private final WorkingDayCalculator calculator;
    private final EmployeeService employees;
    private final FileService files;
    private final NotificationService notifications;
    private final AuditService audit;

    public LeaveRequestService(LeaveRequestRepository requests, LeaveBalanceService balances,
                               WorkingDayCalculator calculator, EmployeeService employees,
                               FileService files, NotificationService notifications,
                               AuditService audit) {
        this.requests = requests;
        this.balances = balances;
        this.calculator = calculator;
        this.employees = employees;
        this.files = files;
        this.notifications = notifications;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public LeaveRequest get(UUID id) {
        return requests.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new LeaveRequestNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> forEmployee(UUID employeeId) {
        employees.get(employeeId);
        return requests.findByTenantIdAndEmployeeIdOrderByStartDateDesc(
                TenantContext.require(), employeeId);
    }

    @Transactional(readOnly = true)
    public List<LeaveRequest> awaitingDecision() {
        return requests.findByTenantIdAndStatusOrderByStartDateAsc(
                TenantContext.require(), LeaveRequestStatus.SUBMITTED);
    }

    /** Who is off between two dates. Drives the team calendar and cover planning. */
    @Transactional(readOnly = true)
    public List<LeaveRequest> approvedBetween(LocalDate from, LocalDate to) {
        return requests.findApprovedBetween(TenantContext.require(), from, to);
    }

    /**
     * Submits a request and reserves the days.
     *
     * <p>Counts working days against the holiday calendar as it stands now, and stores the
     * result. A holiday declared later must not silently change what somebody was charged.
     */
    @Transactional
    public LeaveRequest submit(UUID employeeId, UUID leaveTypeId, LocalDate start, LocalDate end,
                               boolean halfDayStart, boolean halfDayEnd, String reason,
                               UUID documentId, UUID actorId) {
        UUID tenantId = TenantContext.require();
        Employee employee = employees.get(employeeId);
        LeaveType type = balances.type(leaveTypeId);

        if (!type.isActive()) {
            throw new ConflictException("This leave type has been retired");
        }
        if ((halfDayStart || halfDayEnd) && !type.isAllowsHalfDay()) {
            throw new ConflictException(type.getName() + " cannot be taken as a half day");
        }
        // A leave year is a calendar year here, and a request that straddles New Year would have
        // to draw on two balances. Splitting it is the caller's job, and refusing says so.
        if (start.getYear() != end.getYear()) {
            throw new ConflictException(
                    "Split a request that crosses the year end into one per year");
        }

        List<LeaveRequest> clashes =
                requests.findLiveOverlapping(tenantId, employeeId, start, end);
        if (!clashes.isEmpty()) {
            throw new ConflictException(
                    "This overlaps a request already in flight from "
                            + clashes.get(0).getStartDate());
        }

        // Charged against this person's own week. Somebody on four days must not be billed
        // five for a week off.
        BigDecimal days = calculator.countDays(
                employee.getWorkPattern(), start, end, halfDayStart, halfDayEnd);
        if (days.signum() <= 0) {
            // Every day in the range was a holiday, a weekend, or a day they do not work.
            // Recording a zero-day absence
            // would leave a request nobody can approve and a balance nobody charged.
            throw new ConflictException(
                    "That range contains no working days");
        }
        if (type.requiresDocumentFor(days) && documentId == null) {
            throw new ConflictException(
                    type.getName() + " beyond " + type.getDocumentAfterDays()
                            + " day(s) needs a supporting document");
        }

        LeaveRequest request = new LeaveRequest(
                employee, type, start, end, halfDayStart, halfDayEnd, days, reason);
        if (documentId != null) {
            request.attach(files.metadata(documentId));
        }
        LeaveRequest saved = requests.save(request);

        LeaveBalance balance = balances.balanceFor(employeeId, leaveTypeId, start.getYear());
        balance.reserve(days);

        notifyApprover(employee, saved);
        audit.recordSuccess("LEAVE_REQUESTED", "LeaveRequest", saved.getId().toString(), actorId);
        return saved;
    }

    /**
     * Approves a request and turns the reservation into consumption.
     *
     * @param approverEmployeeId who allowed it, recorded separately from whoever typed it in
     */
    @Transactional
    public LeaveRequest approve(UUID id, UUID approverEmployeeId, String notes, UUID actorId) {
        LeaveRequest request = get(id);
        Employee approver = requireApprover(request, approverEmployeeId);

        request.approve(approver, notes);

        LeaveBalance balance = balanceOf(request);
        balance.consume(request.getDays());
        balances.record(balance, LedgerEntryType.TAKEN, request.getDays().negate(), request,
                null, actorId);

        notifyEmployee(request, "leaveApproved", Notification.Severity.INFO);
        audit.recordSuccess("LEAVE_APPROVED", "LeaveRequest", id.toString(), actorId);
        return request;
    }

    /** Refuses a request and gives the days back. A refusal must say why. */
    @Transactional
    public LeaveRequest reject(UUID id, UUID approverEmployeeId, String notes, UUID actorId) {
        LeaveRequest request = get(id);
        Employee approver = requireApprover(request, approverEmployeeId);

        request.reject(approver, notes);
        balanceOf(request).release(request.getDays());

        notifyEmployee(request, "leaveRejected", Notification.Severity.WARNING);
        audit.recordSuccess("LEAVE_REJECTED", "LeaveRequest", id.toString(), actorId);
        return request;
    }

    /**
     * Withdraws a request, before or after approval.
     *
     * <p>A pending request only ever held a reservation, so nothing reaches the ledger — days
     * that were never spent do not need explaining. An approved one is refunded, and that does.
     */
    @Transactional
    public LeaveRequest cancel(UUID id, UUID actorId) {
        LeaveRequest request = get(id);
        boolean wasApproved = request.getStatus() == LeaveRequestStatus.APPROVED;

        request.cancel(LocalDate.now());

        LeaveBalance balance = balanceOf(request);
        if (wasApproved) {
            balance.refund(request.getDays());
            balances.record(balance, LedgerEntryType.RETURNED, request.getDays(), request,
                    "Approved leave cancelled", actorId);
        } else {
            balance.release(request.getDays());
        }

        audit.recordSuccess("LEAVE_CANCELLED", "LeaveRequest", id.toString(), actorId);
        return request;
    }

    private LeaveBalance balanceOf(LeaveRequest request) {
        return balances.balanceFor(
                request.getEmployee().getId(),
                request.getLeaveType().getId(),
                request.getLeaveYear());
    }

    /**
     * Checks that the approver is somebody who may decide this.
     *
     * <p>Nobody approves their own leave. It is the oldest control there is, and the one people
     * are most tempted by when they are in a hurry.
     */
    private Employee requireApprover(LeaveRequest request, UUID approverEmployeeId) {
        if (approverEmployeeId == null) {
            throw new IllegalArgumentException("A decision needs a named approver");
        }
        if (approverEmployeeId.equals(request.getEmployee().getId())) {
            throw new SelfApprovalException();
        }
        return employees.get(approverEmployeeId);
    }

    private void notifyApprover(Employee employee, LeaveRequest request) {
        Employee manager = employee.getManager();
        if (manager == null || manager.getUserAccountId() == null) {
            // Nobody to tell. The request still stands and still shows in the HR queue; sending
            // it into the void would be worse than leaving it to be found there.
            return;
        }
        notifications.notify(
                manager.getUserAccountId(),
                "leaveRequested",
                Map.of(
                        "employee", employee.displayName(),
                        "days", request.getDays().toPlainString(),
                        "from", request.getStartDate().toString()),
                Notification.Severity.INFO,
                "LeaveRequest",
                request.getId().toString());
    }

    private void notifyEmployee(LeaveRequest request, String messageKey,
                                Notification.Severity severity) {
        UUID recipient = request.getEmployee().getUserAccountId();
        if (recipient == null) {
            return;
        }
        notifications.notify(
                recipient,
                messageKey,
                Map.of(
                        "days", request.getDays().toPlainString(),
                        "from", request.getStartDate().toString()),
                severity,
                "LeaveRequest",
                request.getId().toString());
    }

    public static class LeaveRequestNotFoundException extends ResourceNotFoundException {
        public LeaveRequestNotFoundException(UUID id) {
            super("Leave request not found: " + id);
        }
    }

    public static class SelfApprovalException extends AccessRefusedException {
        public SelfApprovalException() {
            super("Nobody may approve their own leave");
        }
    }
}
