package ai.dival.dip.modules.attendance;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * Attendance: clocking, corrections and timesheets.
 *
 * <p>Clocking is open to any authenticated member — a person recording their own arrival should
 * not need a role. Corrections and decisions are not: amending somebody's hours changes what they
 * are paid, and that is a supervisor's act.
 */
@RestController
@RequestMapping("/api/v1/attendance")
public class AttendanceController {

    private static final String SUPERVISE =
            "hasAnyRole('" + Roles.MANAGER + "', '" + Roles.HR_ADMIN + "', '"
                    + Roles.HR_MANAGER + "', '" + Roles.TENANT_ADMIN + "')";

    private final AttendanceService attendance;
    private final TimesheetService timesheets;
    private final CurrentUserService currentUser;

    public AttendanceController(AttendanceService attendance, TimesheetService timesheets,
                                CurrentUserService currentUser) {
        this.attendance = attendance;
        this.timesheets = timesheets;
        this.currentUser = currentUser;
    }

    // --- clocking ----------------------------------------------------------

    @GetMapping("/employees/{employeeId}/entries")
    public List<EntryResponse> entries(
            @PathVariable UUID employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return attendance.between(employeeId, from, to).stream()
                .map(EntryResponse::from).toList();
    }

    /** Everything recorded on a day, corrections included, so the trail can be read. */
    @GetMapping("/employees/{employeeId}/entries/{day}")
    @PreAuthorize(SUPERVISE)
    public List<EntryResponse> dayHistory(
            @PathVariable UUID employeeId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return attendance.historyFor(employeeId, day).stream()
                .map(EntryResponse::from).toList();
    }

    @PostMapping("/clock-in")
    public ResponseEntity<EntryResponse> clockIn(@Valid @RequestBody ClockInRequest request) {
        TimeEntry entry = attendance.clockIn(
                request.employeeId(), request.at(), request.source(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(EntryResponse.from(entry));
    }

    @PostMapping("/clock-out")
    public EntryResponse clockOut(@Valid @RequestBody ClockOutRequest request) {
        return EntryResponse.from(attendance.clockOut(
                request.employeeId(), request.at(), request.breakMinutes(), actorId()));
    }

    @PostMapping("/entries")
    @PreAuthorize(SUPERVISE)
    public ResponseEntity<EntryResponse> record(@Valid @RequestBody RecordRequest request) {
        TimeEntry entry = attendance.record(request.employeeId(), request.workDate(),
                request.startedAt(), request.endedAt(), request.breakMinutes(),
                request.source(), request.notes(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(EntryResponse.from(entry));
    }

    /** Replaces an entry. The original stays, marked superseded. */
    @PostMapping("/entries/{id}/amend")
    @PreAuthorize(SUPERVISE)
    public EntryResponse amend(@PathVariable UUID id, @Valid @RequestBody AmendRequest request) {
        return EntryResponse.from(attendance.amend(id, request.startedAt(), request.endedAt(),
                request.breakMinutes(), request.reason(), actorId()));
    }

    // --- timesheets --------------------------------------------------------

    @GetMapping("/employees/{employeeId}/timesheets")
    public List<TimesheetResponse> timesheets(@PathVariable UUID employeeId) {
        return timesheets.forEmployee(employeeId).stream().map(TimesheetResponse::from).toList();
    }

    @GetMapping("/timesheets/pending")
    @PreAuthorize(SUPERVISE)
    public List<TimesheetResponse> pending() {
        return timesheets.awaitingDecision().stream().map(TimesheetResponse::from).toList();
    }

    /** The figures for a period without persisting a sheet, so a screen can show them live. */
    @GetMapping("/employees/{employeeId}/preview")
    public TimesheetService.Totals preview(
            @PathVariable UUID employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return timesheets.preview(employeeId, from, to);
    }

    @PostMapping("/timesheets/week")
    public ResponseEntity<TimesheetResponse> buildWeek(
            @Valid @RequestBody BuildWeekRequest request) {
        Timesheet sheet = timesheets.buildWeek(
                request.employeeId(), request.anyDayInWeek(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(TimesheetResponse.from(sheet));
    }

    @PostMapping("/timesheets/{id}/submit")
    public TimesheetResponse submit(@PathVariable UUID id) {
        return TimesheetResponse.from(timesheets.submit(id, actorId()));
    }

    @PostMapping("/timesheets/{id}/approve")
    @PreAuthorize(SUPERVISE)
    public TimesheetResponse approve(@PathVariable UUID id,
                                     @Valid @RequestBody DecisionRequest request) {
        return TimesheetResponse.from(timesheets.approve(
                id, request.approverEmployeeId(), request.notes(), actorId()));
    }

    @PostMapping("/timesheets/{id}/reject")
    @PreAuthorize(SUPERVISE)
    public TimesheetResponse reject(@PathVariable UUID id,
                                    @Valid @RequestBody DecisionRequest request) {
        return TimesheetResponse.from(timesheets.reject(
                id, request.approverEmployeeId(), request.notes(), actorId()));
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    // --- requests ----------------------------------------------------------

    public record ClockInRequest(
            @NotNull UUID employeeId,
            Instant at,
            TimeEntrySource source) {
    }

    public record ClockOutRequest(
            @NotNull UUID employeeId,
            Instant at,
            int breakMinutes) {
    }

    public record RecordRequest(
            @NotNull UUID employeeId,
            @NotNull LocalDate workDate,
            @NotNull Instant startedAt,
            @NotNull Instant endedAt,
            int breakMinutes,
            TimeEntrySource source,
            String notes) {
    }

    public record AmendRequest(
            @NotNull Instant startedAt,
            @NotNull Instant endedAt,
            int breakMinutes,
            @NotBlank String reason) {
    }

    public record BuildWeekRequest(
            @NotNull UUID employeeId,
            @NotNull LocalDate anyDayInWeek) {
    }

    public record DecisionRequest(
            @NotNull UUID approverEmployeeId,
            String notes) {
    }

    // --- responses ---------------------------------------------------------

    public record EntryResponse(
            UUID id,
            UUID employeeId,
            String employeeName,
            LocalDate workDate,
            Instant startedAt,
            Instant endedAt,
            int breakMinutes,
            int workedMinutes,
            TimeEntrySource source,
            String notes,
            UUID supersedesId,
            boolean superseded,
            String amendReason) {

        static EntryResponse from(TimeEntry entry) {
            return new EntryResponse(
                    entry.getId(),
                    entry.getEmployee().getId(),
                    entry.getEmployee().displayName(),
                    entry.getWorkDate(),
                    entry.getStartedAt(),
                    entry.getEndedAt(),
                    entry.getBreakMinutes(),
                    entry.workedMinutes(),
                    entry.getSource(),
                    entry.getNotes(),
                    entry.getSupersedes() == null ? null : entry.getSupersedes().getId(),
                    entry.isSuperseded(),
                    entry.getAmendReason());
        }
    }

    public record TimesheetResponse(
            UUID id,
            UUID employeeId,
            String employeeName,
            LocalDate periodStart,
            LocalDate periodEnd,
            int workedMinutes,
            int expectedMinutes,
            int leaveMinutes,
            int holidayMinutes,
            int overtimeMinutes,
            int absentMinutes,
            TimesheetStatus status,
            Instant submittedAt,
            UUID approverId,
            String approverName,
            Instant decidedAt,
            String decisionNotes) {

        static TimesheetResponse from(Timesheet sheet) {
            return new TimesheetResponse(
                    sheet.getId(),
                    sheet.getEmployee().getId(),
                    sheet.getEmployee().displayName(),
                    sheet.getPeriodStart(),
                    sheet.getPeriodEnd(),
                    sheet.getWorkedMinutes(),
                    sheet.getExpectedMinutes(),
                    sheet.getLeaveMinutes(),
                    sheet.getHolidayMinutes(),
                    sheet.getOvertimeMinutes(),
                    sheet.getAbsentMinutes(),
                    sheet.getStatus(),
                    sheet.getSubmittedAt(),
                    sheet.getApprover() == null ? null : sheet.getApprover().getId(),
                    sheet.getApprover() == null ? null : sheet.getApprover().displayName(),
                    sheet.getDecidedAt(),
                    sheet.getDecisionNotes());
        }
    }
}
