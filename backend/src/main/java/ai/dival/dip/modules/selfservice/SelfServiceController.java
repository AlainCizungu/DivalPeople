package ai.dival.dip.modules.selfservice;

import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.modules.attendance.AttendanceService;
import ai.dival.dip.modules.attendance.TimeEntry;
import ai.dival.dip.modules.attendance.TimeEntrySource;
import ai.dival.dip.modules.attendance.Timesheet;
import ai.dival.dip.modules.attendance.TimesheetService;
import ai.dival.dip.modules.attendance.TimesheetStatus;
import ai.dival.dip.modules.employees.CurrentEmployee;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.employees.EmployeeStatus;
import ai.dival.dip.modules.learning.CourseEnrolment;
import ai.dival.dip.modules.learning.EnrolmentStatus;
import ai.dival.dip.modules.learning.LearningService;
import ai.dival.dip.modules.leave.LeaveBalance;
import ai.dival.dip.modules.leave.LeaveBalanceService;
import ai.dival.dip.modules.leave.LeaveRequest;
import ai.dival.dip.modules.leave.LeaveRequestService;
import ai.dival.dip.modules.leave.LeaveRequestStatus;
import ai.dival.dip.modules.leave.LeaveType;
import ai.dival.dip.modules.payroll.PayrollService;
import ai.dival.dip.modules.payroll.Payslip;
import ai.dival.dip.modules.payroll.PayslipLine;
import ai.dival.dip.modules.performance.Goal;
import ai.dival.dip.modules.performance.GoalStatus;
import ai.dival.dip.modules.performance.PerformanceReview;
import ai.dival.dip.modules.performance.PerformanceService;
import ai.dival.dip.modules.performance.Rating;
import ai.dival.dip.modules.performance.ReviewStatus;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
 * What a person can see and do about themselves.
 *
 * <p>Two rules hold this together, and they are the reason the module exists rather than a set of
 * extra parameters on the HR endpoints.
 *
 * <p><strong>No endpoint here takes an employee id.</strong> The subject is always resolved from
 * the token by {@link CurrentEmployee}. Where an action must name a record — cancelling a leave
 * request, submitting a timesheet — the id names the record, and {@link #mine} then checks the
 * record belongs to the caller before anything happens to it. That check is in exactly one place
 * so that a new endpoint either goes through it or is obviously missing it.
 *
 * <p><strong>The responses are narrower than the HR ones on purpose.</strong> They are not the
 * same records with a different route in front. A person reading their own payslip does not need
 * their own employee id echoed back, and more to the point, a self-service response should never
 * grow a field simply because an HR screen wanted one. Separate shapes make that impossible by
 * accident rather than by review.
 *
 * <p>Two things are deliberately not shown: a payslip for a run that has not been signed off, and
 * a performance review that has not been shared. Both are figures still being worked on, and both
 * are enforced by the domain rather than here.
 */
@RestController
@RequestMapping("/api/v1/me")
@PreAuthorize("isAuthenticated()")
public class SelfServiceController {

    private final CurrentEmployee currentEmployee;
    private final CurrentUserService currentUser;
    private final EmployeeService employees;
    private final PayrollService payroll;
    private final LeaveBalanceService balances;
    private final LeaveRequestService leaveRequests;
    private final AttendanceService attendance;
    private final TimesheetService timesheets;
    private final PerformanceService performance;
    private final LearningService learning;

    public SelfServiceController(CurrentEmployee currentEmployee, CurrentUserService currentUser,
                                 EmployeeService employees, PayrollService payroll,
                                 LeaveBalanceService balances, LeaveRequestService leaveRequests,
                                 AttendanceService attendance, TimesheetService timesheets,
                                 PerformanceService performance, LearningService learning) {
        this.currentEmployee = currentEmployee;
        this.currentUser = currentUser;
        this.employees = employees;
        this.payroll = payroll;
        this.balances = balances;
        this.leaveRequests = leaveRequests;
        this.attendance = attendance;
        this.timesheets = timesheets;
        this.performance = performance;
        this.learning = learning;
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    /**
     * The one place a caller-supplied id is checked against the caller.
     *
     * <p>Reached only by actions on a record the person already has. The refusal says nothing
     * about whether the record exists, because "not yours" and "not there" should look identical
     * from outside — otherwise the endpoint enumerates other people's leave requests.
     */
    private <T> T mine(T record, UUID ownerId) {
        if (!ownerId.equals(currentEmployee.requireId())) {
            throw new AccessRefusedException("Record does not belong to the caller");
        }
        return record;
    }

    // --- who am I ----------------------------------------------------------

    @GetMapping
    public MeResponse me() {
        return MeResponse.from(currentEmployee.require());
    }

    /** Direct reports. Empty for most people, which is the correct answer, not an error. */
    @GetMapping("/team")
    public List<TeamMemberResponse> team() {
        return employees.directReports(currentEmployee.requireId()).stream()
                .map(TeamMemberResponse::from).toList();
    }

    // --- pay ---------------------------------------------------------------

    /**
     * My payslips, from signed-off runs only.
     *
     * <p>The filter is here rather than in a query so the rule is visible: an employee sees a
     * payslip once payroll has approved the run it belongs to, and not before.
     */
    @GetMapping("/payslips")
    public List<MyPayslipResponse> payslips() {
        return payroll.payslipsFor(currentEmployee.requireId()).stream()
                .filter(slip -> slip.getPeriod().getStatus().isVisibleToEmployee())
                .map(MyPayslipResponse::from)
                .toList();
    }

    @GetMapping("/payslips/{id}")
    public MyPayslipResponse payslip(@PathVariable UUID id) {
        Payslip slip = payroll.payslip(id);
        mine(slip, slip.getEmployee().getId());
        if (!slip.getPeriod().getStatus().isVisibleToEmployee()) {
            throw new AccessRefusedException("Payslip belongs to a run that is not signed off");
        }
        return MyPayslipResponse.from(slip);
    }

    // --- leave -------------------------------------------------------------

    @GetMapping("/leave/balances")
    public List<MyBalanceResponse> leaveBalances(
            @RequestParam(required = false) Integer year) {
        int leaveYear = year == null ? LocalDate.now().getYear() : year;
        return balances.balancesFor(currentEmployee.requireId(), leaveYear).stream()
                .map(MyBalanceResponse::from).toList();
    }

    @GetMapping("/leave/types")
    public List<LeaveTypeOption> leaveTypes() {
        return balances.activeTypes().stream().map(LeaveTypeOption::from).toList();
    }

    @GetMapping("/leave/requests")
    public List<MyLeaveRequestResponse> leaveRequests() {
        return leaveRequests.forEmployee(currentEmployee.requireId()).stream()
                .map(MyLeaveRequestResponse::from).toList();
    }

    /** Booking leave for myself. There is no employee id to get wrong. */
    @PostMapping("/leave/requests")
    public ResponseEntity<MyLeaveRequestResponse> bookLeave(@Valid @RequestBody BookLeave r) {
        LeaveRequest submitted = leaveRequests.submit(currentEmployee.requireId(), r.leaveTypeId(),
                r.startDate(), r.endDate(), r.halfDayStart(), r.halfDayEnd(), r.reason(),
                r.documentId(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(MyLeaveRequestResponse.from(submitted));
    }

    @PostMapping("/leave/requests/{id}/cancel")
    public MyLeaveRequestResponse cancelLeave(@PathVariable UUID id) {
        LeaveRequest request = leaveRequests.get(id);
        mine(request, request.getEmployee().getId());
        return MyLeaveRequestResponse.from(leaveRequests.cancel(id, actorId()));
    }

    // --- time --------------------------------------------------------------

    @GetMapping("/attendance")
    public List<MyEntryResponse> attendance(@RequestParam LocalDate from,
                                            @RequestParam LocalDate to) {
        return attendance.between(currentEmployee.requireId(), from, to).stream()
                .map(MyEntryResponse::from).toList();
    }

    @PostMapping("/attendance/clock-in")
    public MyEntryResponse clockIn() {
        return MyEntryResponse.from(attendance.clockIn(currentEmployee.requireId(),
                Instant.now(), TimeEntrySource.WEB, actorId()));
    }

    @PostMapping("/attendance/clock-out")
    public MyEntryResponse clockOut(@RequestBody(required = false) ClockOut r) {
        int breakMinutes = r == null ? 0 : Math.max(0, r.breakMinutes());
        return MyEntryResponse.from(attendance.clockOut(currentEmployee.requireId(),
                Instant.now(), breakMinutes, actorId()));
    }

    @GetMapping("/timesheets")
    public List<MyTimesheetResponse> timesheets() {
        return timesheets.forEmployee(currentEmployee.requireId()).stream()
                .map(MyTimesheetResponse::from).toList();
    }

    @PostMapping("/timesheets/{id}/submit")
    public MyTimesheetResponse submitTimesheet(@PathVariable UUID id) {
        Timesheet sheet = timesheets.get(id);
        mine(sheet, sheet.getEmployee().getId());
        return MyTimesheetResponse.from(timesheets.submit(id, actorId()));
    }

    // --- performance and learning ------------------------------------------

    @GetMapping("/goals")
    public List<MyGoalResponse> goals() {
        return performance.goalsFor(currentEmployee.requireId()).stream()
                .map(MyGoalResponse::from).toList();
    }

    /**
     * My reviews, as the subject sees them.
     *
     * <p>The reviewer's rating is withheld until the review is shared. That is the entity's rule,
     * not this endpoint's — {@code selfAssessmentFor} and the visibility checks live on
     * {@link PerformanceReview}, so a route that forgot about them could not leak anything.
     */
    @GetMapping("/reviews")
    public List<MyReviewResponse> reviews() {
        return performance.reviewsFor(currentEmployee.requireId()).stream()
                .map(MyReviewResponse::from).toList();
    }

    @GetMapping("/training")
    public List<MyTrainingResponse> training() {
        return learning.enrolmentsFor(currentEmployee.requireId()).stream()
                .map(MyTrainingResponse::from).toList();
    }

    // --- requests ----------------------------------------------------------

    public record BookLeave(
            @NotNull UUID leaveTypeId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            boolean halfDayStart,
            boolean halfDayEnd,
            String reason,
            UUID documentId) {
    }

    public record ClockOut(int breakMinutes) {
    }

    // --- responses ---------------------------------------------------------

    public record MeResponse(
            UUID employeeId,
            String employeeNumber,
            String displayName,
            LocalDate hireDate,
            EmployeeStatus status,
            String managerName,
            String orgUnitName,
            String personalEmail,
            String phone) {

        static MeResponse from(Employee employee) {
            return new MeResponse(
                    employee.getId(),
                    employee.getEmployeeNumber(),
                    employee.displayName(),
                    employee.getHireDate(),
                    employee.getStatus(),
                    employee.getManager() == null ? null : employee.getManager().displayName(),
                    employee.getOrgUnit() == null ? null : employee.getOrgUnit().getName(),
                    employee.getPersonalEmail(),
                    employee.getPhone());
        }
    }

    /** Enough to recognise a colleague. Deliberately not their pay, contract or date of birth. */
    public record TeamMemberResponse(
            UUID employeeId,
            String employeeNumber,
            String displayName,
            EmployeeStatus status) {

        static TeamMemberResponse from(Employee employee) {
            return new TeamMemberResponse(employee.getId(), employee.getEmployeeNumber(),
                    employee.displayName(), employee.getStatus());
        }
    }

    public record MyPayslipResponse(
            UUID id,
            String periodName,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate paymentDate,
            String currency,
            BigDecimal baseAmount,
            BigDecimal grossEarnings,
            BigDecimal totalDeductions,
            BigDecimal netPay,
            BigDecimal unpaidLeaveDays,
            int overtimeMinutes,
            List<MyPayslipLine> lines) {

        static MyPayslipResponse from(Payslip payslip) {
            return new MyPayslipResponse(
                    payslip.getId(),
                    payslip.getPeriod().getName(),
                    payslip.getPeriod().getPeriodStart(),
                    payslip.getPeriod().getPeriodEnd(),
                    payslip.getPeriod().getPaymentDate(),
                    payslip.getCurrency(),
                    payslip.getBaseAmount(),
                    payslip.getGrossEarnings(),
                    payslip.getTotalDeductions(),
                    payslip.getNetPay(),
                    payslip.getUnpaidLeaveDays(),
                    payslip.getOvertimeMinutes(),
                    payslip.getLines().stream().map(MyPayslipLine::from).toList());
        }
    }

    /**
     * A line, with how it was worked out.
     *
     * <p>Employer contributions are carried, because what an employer pays on somebody's behalf
     * is part of what they are owed and hiding it makes the total look arbitrary. The employer
     * cost total is not, because it is a company figure rather than a personal one.
     */
    public record MyPayslipLine(
            String componentName,
            String componentType,
            String basis,
            BigDecimal amount) {

        static MyPayslipLine from(PayslipLine line) {
            return new MyPayslipLine(line.getComponentName(),
                    line.getComponentType().name(), line.getBasis(), line.getAmount());
        }
    }

    public record MyBalanceResponse(
            UUID leaveTypeId,
            String leaveTypeName,
            int leaveYear,
            BigDecimal accruedDays,
            BigDecimal takenDays,
            BigDecimal pendingDays,
            BigDecimal availableDays) {

        static MyBalanceResponse from(LeaveBalance balance) {
            return new MyBalanceResponse(
                    balance.getLeaveType().getId(),
                    balance.getLeaveType().getName(),
                    balance.getLeaveYear(),
                    balance.getAccruedDays(),
                    balance.getTakenDays(),
                    balance.getPendingDays(),
                    balance.available());
        }
    }

    public record LeaveTypeOption(
            UUID id,
            String code,
            String name,
            boolean allowsHalfDay,
            boolean paid) {

        static LeaveTypeOption from(LeaveType type) {
            return new LeaveTypeOption(type.getId(), type.getCode(), type.getName(),
                    type.isAllowsHalfDay(), type.isPaid());
        }
    }

    public record MyLeaveRequestResponse(
            UUID id,
            UUID leaveTypeId,
            String leaveTypeName,
            LocalDate startDate,
            LocalDate endDate,
            boolean halfDayStart,
            boolean halfDayEnd,
            BigDecimal days,
            String reason,
            LeaveRequestStatus status,
            String approverName,
            Instant decidedAt,
            String decisionNotes) {

        static MyLeaveRequestResponse from(LeaveRequest request) {
            return new MyLeaveRequestResponse(
                    request.getId(),
                    request.getLeaveType().getId(),
                    request.getLeaveType().getName(),
                    request.getStartDate(),
                    request.getEndDate(),
                    request.isHalfDayStart(),
                    request.isHalfDayEnd(),
                    request.getDays(),
                    request.getReason(),
                    request.getStatus(),
                    request.getApprover() == null ? null : request.getApprover().displayName(),
                    request.getDecidedAt(),
                    request.getDecisionNotes());
        }
    }

    public record MyEntryResponse(
            UUID id,
            LocalDate workDate,
            Instant startedAt,
            Instant endedAt,
            int breakMinutes,
            int workedMinutes,
            String notes) {

        static MyEntryResponse from(TimeEntry entry) {
            return new MyEntryResponse(entry.getId(), entry.getWorkDate(), entry.getStartedAt(),
                    entry.getEndedAt(), entry.getBreakMinutes(), entry.workedMinutes(),
                    entry.getNotes());
        }
    }

    public record MyTimesheetResponse(
            UUID id,
            LocalDate periodStart,
            LocalDate periodEnd,
            int workedMinutes,
            int expectedMinutes,
            int overtimeMinutes,
            int absentMinutes,
            TimesheetStatus status,
            Instant submittedAt,
            String decisionNotes) {

        static MyTimesheetResponse from(Timesheet sheet) {
            return new MyTimesheetResponse(sheet.getId(), sheet.getPeriodStart(),
                    sheet.getPeriodEnd(), sheet.getWorkedMinutes(), sheet.getExpectedMinutes(),
                    sheet.getOvertimeMinutes(), sheet.getAbsentMinutes(), sheet.getStatus(),
                    sheet.getSubmittedAt(), sheet.getDecisionNotes());
        }
    }

    public record MyGoalResponse(
            UUID id,
            String cycleName,
            String title,
            String description,
            String measure,
            LocalDate targetDate,
            int progressPercent,
            GoalStatus status) {

        static MyGoalResponse from(Goal goal) {
            return new MyGoalResponse(goal.getId(),
                    goal.getCycle() == null ? null : goal.getCycle().getName(),
                    goal.getTitle(), goal.getDescription(), goal.getMeasure(),
                    goal.getTargetDate(), goal.getProgressPercent(), goal.getStatus());
        }
    }

    /**
     * A review as its subject sees it.
     *
     * <p>The reviewer's words and rating are read through the entity's visibility methods, which
     * withhold them until the review is shared. Nothing here decides that; this record only
     * asks.
     */
    public record MyReviewResponse(
            UUID id,
            String cycleName,
            String reviewerName,
            String selfAssessment,
            Instant selfSubmittedAt,
            String reviewerAssessment,
            Rating effectiveRating,
            ReviewStatus status,
            Instant sharedAt,
            Instant acknowledgedAt,
            String employeeResponse,
            boolean employeeDisagrees) {

        static MyReviewResponse from(PerformanceReview review) {
            boolean shared = review.getStatus().isVisibleToEmployee();
            return new MyReviewResponse(
                    review.getId(),
                    review.getCycle().getName(),
                    review.getReviewer() == null ? null : review.getReviewer().displayName(),
                    review.selfAssessmentFor(true),
                    review.getSelfSubmittedAt(),
                    review.reviewerAssessmentFor(true),
                    shared ? review.effectiveRating() : null,
                    review.getStatus(),
                    review.getSharedAt(),
                    review.getAcknowledgedAt(),
                    review.getEmployeeResponse(),
                    review.isEmployeeDisagrees());
        }
    }

    public record MyTrainingResponse(
            UUID id,
            String courseTitle,
            boolean mandatory,
            EnrolmentStatus status,
            LocalDate enrolledOn,
            LocalDate completedOn,
            Integer score,
            LocalDate expiresOn,
            UUID certificateFileId) {

        static MyTrainingResponse from(CourseEnrolment enrolment) {
            return new MyTrainingResponse(enrolment.getId(),
                    enrolment.getCourse().getTitle(), enrolment.getCourse().isMandatory(),
                    enrolment.getStatus(), enrolment.getEnrolledOn(), enrolment.getCompletedOn(),
                    enrolment.getScore(), enrolment.getExpiresOn(),
                    enrolment.getCertificate() == null ? null : enrolment.getCertificate().getId());
        }
    }
}
