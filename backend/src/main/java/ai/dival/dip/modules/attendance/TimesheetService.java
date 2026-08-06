package ai.dival.dip.modules.attendance;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.leave.LeaveRequest;
import ai.dival.dip.modules.leave.LeaveRequestService;
import ai.dival.dip.modules.leave.WorkingDayCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A period of attendance, totalled and signed off.
 *
 * <p>Leans on the leave module for two things it must not guess at: the holiday calendar, and who
 * was legitimately away. A timesheet that counted approved leave as absence would turn every
 * holiday into a disciplinary conversation.
 *
 * <p>Overtime is stated in minutes and deliberately not priced. What an hour is worth is a payroll
 * decision; putting a multiplier here would mean two systems disagreeing about pay the first time
 * a rate changed.
 */
@Service
public class TimesheetService {

    private final TimesheetRepository timesheets;
    private final TimeEntryRepository entries;
    private final EmployeeService employees;
    private final LeaveRequestService leave;
    private final WorkingDayCalculator calendar;
    private final AuditService audit;

    /** A full working day, in minutes. Scaled by the employee's pattern for each weekday. */
    private final int standardDayMinutes;

    /**
     * Hours beyond which a week counts as overtime regardless of the daily pattern.
     *
     * <p>Statutory in most jurisdictions, and separate from the daily figure because somebody can
     * work five ordinary days and still cross a weekly ceiling.
     */
    private final int weeklyThresholdMinutes;

    public TimesheetService(TimesheetRepository timesheets, TimeEntryRepository entries,
                            EmployeeService employees, LeaveRequestService leave,
                            WorkingDayCalculator calendar, AuditService audit,
                            @Value("${dip.hr.standard-day-minutes:480}") int standardDayMinutes,
                            @Value("${dip.hr.weekly-overtime-threshold-minutes:2700}")
                            int weeklyThresholdMinutes) {
        this.timesheets = timesheets;
        this.entries = entries;
        this.employees = employees;
        this.leave = leave;
        this.calendar = calendar;
        this.audit = audit;
        this.standardDayMinutes = standardDayMinutes;
        this.weeklyThresholdMinutes = weeklyThresholdMinutes;
    }

    @Transactional(readOnly = true)
    public Timesheet get(UUID id) {
        return timesheets.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new TimesheetNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Timesheet> forEmployee(UUID employeeId) {
        employees.get(employeeId);
        return timesheets.findByTenantIdAndEmployeeIdOrderByPeriodStartDesc(
                TenantContext.require(), employeeId);
    }

    @Transactional(readOnly = true)
    public List<Timesheet> awaitingDecision() {
        return timesheets.findByTenantIdAndStatusOrderByPeriodStartAsc(
                TenantContext.require(), TimesheetStatus.SUBMITTED);
    }

    /**
     * Builds or refreshes the sheet for the week containing a date, and totals it.
     *
     * <p>Weeks run Monday to Sunday. A fortnightly or monthly period is a different anchor over
     * the same arithmetic, and is left until somebody needs it rather than guessed at now.
     */
    @Transactional
    public Timesheet buildWeek(UUID employeeId, LocalDate anyDayInWeek, UUID actorId) {
        LocalDate start = anyDayInWeek.with(DayOfWeek.MONDAY);
        return build(employeeId, start, start.plusDays(6), actorId);
    }

    @Transactional
    public Timesheet build(UUID employeeId, LocalDate periodStart, LocalDate periodEnd,
                           UUID actorId) {
        UUID tenantId = TenantContext.require();
        Employee employee = employees.get(employeeId);

        Timesheet sheet = timesheets
                .findByTenantIdAndEmployeeIdAndPeriodStart(tenantId, employeeId, periodStart)
                .orElseGet(() -> timesheets.save(
                        new Timesheet(employee, periodStart, periodEnd)));

        if (!sheet.getStatus().isOpen()) {
            throw new ConflictException(
                    "This timesheet has been submitted; reopen it before recalculating");
        }

        Totals totals = total(employee, periodStart, periodEnd);
        sheet.setTotals(totals.worked(), totals.expected(), totals.leave(), totals.holiday(),
                totals.overtime(), totals.absent());

        audit.recordSuccess("TIMESHEET_BUILT", "Timesheet", sheet.getId().toString(), actorId);
        return sheet;
    }

    /** The arithmetic, exposed so a screen can preview a period without persisting a sheet. */
    @Transactional(readOnly = true)
    public Totals preview(UUID employeeId, LocalDate periodStart, LocalDate periodEnd) {
        return total(employees.get(employeeId), periodStart, periodEnd);
    }

    private Totals total(Employee employee, LocalDate periodStart, LocalDate periodEnd) {
        UUID tenantId = TenantContext.require();

        int worked = entries.findLiveBetween(tenantId, employee.getId(), periodStart, periodEnd)
                .stream()
                .mapToInt(TimeEntry::workedMinutes)
                .sum();

        Set<LocalDate> closed = calendar.holidayDates(periodStart, periodEnd);
        Set<LocalDate> onLeave = leave.approvedBetween(periodStart, periodEnd).stream()
                .filter(request -> request.getEmployee().getId().equals(employee.getId()))
                .flatMap(request -> daysOf(request).stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        int expected = 0;
        int holiday = 0;
        int onLeaveMinutes = 0;

        for (LocalDate day = periodStart; !day.isAfter(periodEnd); day = day.plusDays(1)) {
            // What this person owed that day, before anything excused them.
            int owed = minutesFor(employee, day, Set.of());
            if (owed == 0) {
                continue;
            }
            expected += owed;

            if (closed.contains(day)) {
                holiday += owed;
            } else if (onLeave.contains(day)) {
                onLeaveMinutes += owed;
            }
        }

        // Leave and holidays are owed but not absence. Counting them as absence would turn every
        // approved holiday into a disciplinary conversation.
        int excused = holiday + onLeaveMinutes;
        int absent = Math.max(0, expected - excused - worked);

        return new Totals(worked, expected, onLeaveMinutes, holiday,
                overtime(worked, expected - excused, periodStart, periodEnd), absent);
    }

    /**
     * Minutes beyond what was owed, or beyond the weekly ceiling, whichever is greater.
     *
     * <p>Both are checked because they catch different things: somebody can stay late on two days
     * and still be under the weekly ceiling, and somebody can work five ordinary days across a
     * long week and cross it without a single late evening.
     */
    private int overtime(int worked, int owedAfterExcuses, LocalDate start, LocalDate end) {
        int beyondOwed = Math.max(0, worked - Math.max(0, owedAfterExcuses));

        long weeks = Math.max(1, (end.toEpochDay() - start.toEpochDay() + 1 + 6) / 7);
        int ceiling = (int) (weeklyThresholdMinutes * weeks);
        int beyondCeiling = Math.max(0, worked - ceiling);

        return Math.max(beyondOwed, beyondCeiling);
    }

    /** What one calendar day is worth to this person, in minutes. */
    private int minutesFor(Employee employee, LocalDate day, Set<LocalDate> closed) {
        BigDecimal fraction = calendar.fractionOn(employee.getWorkPattern(), day, closed);
        return fraction.multiply(BigDecimal.valueOf(standardDayMinutes))
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    private List<LocalDate> daysOf(LeaveRequest request) {
        return request.getStartDate().datesUntil(request.getEndDate().plusDays(1)).toList();
    }

    @Transactional
    public Timesheet submit(UUID id, UUID actorId) {
        Timesheet sheet = get(id);
        sheet.submit();
        audit.recordSuccess("TIMESHEET_SUBMITTED", "Timesheet", id.toString(), actorId);
        return sheet;
    }

    /** Nobody approves their own hours. The oldest control there is. */
    @Transactional
    public Timesheet approve(UUID id, UUID approverEmployeeId, String notes, UUID actorId) {
        Timesheet sheet = get(id);
        sheet.approve(requireApprover(sheet, approverEmployeeId), notes);
        audit.recordSuccess("TIMESHEET_APPROVED", "Timesheet", id.toString(), actorId);
        return sheet;
    }

    @Transactional
    public Timesheet reject(UUID id, UUID approverEmployeeId, String notes, UUID actorId) {
        Timesheet sheet = get(id);
        sheet.reject(requireApprover(sheet, approverEmployeeId), notes);
        audit.recordSuccess("TIMESHEET_REJECTED", "Timesheet", id.toString(), actorId);
        return sheet;
    }

    private Employee requireApprover(Timesheet sheet, UUID approverEmployeeId) {
        if (approverEmployeeId == null) {
            throw new IllegalArgumentException("A decision needs a named approver");
        }
        if (approverEmployeeId.equals(sheet.getEmployee().getId())) {
            throw new SelfApprovalException();
        }
        return employees.get(approverEmployeeId);
    }

    /**
     * The figures for a period.
     *
     * @param worked   time actually recorded, breaks removed
     * @param expected what the work pattern says was owed, before any excuse
     * @param leave    of the expected time, how much was approved leave
     * @param holiday  of the expected time, how much the office was closed
     * @param overtime minutes beyond what was owed or beyond the weekly ceiling
     * @param absent   expected, unworked, and explained by neither
     */
    public record Totals(int worked, int expected, int leave, int holiday, int overtime,
                         int absent) {
    }

    public static class TimesheetNotFoundException extends ResourceNotFoundException {
        public TimesheetNotFoundException(UUID id) {
            super("Timesheet not found: " + id);
        }
    }

    public static class SelfApprovalException extends AccessRefusedException {
        public SelfApprovalException() {
            super("Nobody may approve their own timesheet");
        }
    }
}
