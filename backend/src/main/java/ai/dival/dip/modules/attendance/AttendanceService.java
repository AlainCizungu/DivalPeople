package ai.dival.dip.modules.attendance;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clocking in and out, and correcting the record afterwards.
 *
 * <p>Entries are never edited. A correction writes a new entry that points at the one it replaces,
 * and the original stays visible marked superseded. Attendance is what people are paid from and
 * disciplined against, so the question "what did it say before?" has to have an answer.
 */
@Service
public class AttendanceService {

    private final TimeEntryRepository entries;
    private final TimesheetRepository timesheets;
    private final EmployeeService employees;
    private final AuditService audit;
    private final ZoneId zone;

    public AttendanceService(TimeEntryRepository entries, TimesheetRepository timesheets,
                             EmployeeService employees, AuditService audit,
                             @Value("${dip.hr.timezone:Africa/Kinshasa}") String zone) {
        this.entries = entries;
        this.timesheets = timesheets;
        this.employees = employees;
        this.audit = audit;
        this.zone = ZoneId.of(zone);
    }

    @Transactional(readOnly = true)
    public TimeEntry get(UUID id) {
        return entries.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new TimeEntryNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<TimeEntry> between(UUID employeeId, LocalDate from, LocalDate to) {
        employees.get(employeeId);
        return entries.findLiveBetween(TenantContext.require(), employeeId, from, to);
    }

    /** Everything recorded on a day, superseded rows included, so the trail can be read. */
    @Transactional(readOnly = true)
    public List<TimeEntry> historyFor(UUID employeeId, LocalDate day) {
        employees.get(employeeId);
        return entries.findByTenantIdAndEmployeeIdAndWorkDateOrderByStartedAtAsc(
                TenantContext.require(), employeeId, day);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<TimeEntry> openEntry(UUID employeeId) {
        return entries.findOpen(TenantContext.require(), employeeId);
    }

    /**
     * Starts a shift.
     *
     * <p>Refuses if one is already running. A second clock-in is how somebody ends up paid twice
     * for the same hour, and the database refuses it too — this check exists to produce a sentence
     * rather than a constraint violation.
     */
    @Transactional
    public TimeEntry clockIn(UUID employeeId, Instant at, TimeEntrySource source, UUID actorId) {
        UUID tenantId = TenantContext.require();
        Employee employee = employees.get(employeeId);
        Instant startedAt = at == null ? Instant.now() : at;

        entries.findOpen(tenantId, employeeId).ifPresent(open -> {
            throw new ConflictException(
                    "Already clocked in since " + open.getStartedAt());
        });

        LocalDate workDate = startedAt.atZone(zone).toLocalDate();
        requireOpenPeriod(employeeId, workDate);

        TimeEntry entry = entries.save(
                new TimeEntry(employee, workDate, startedAt, null, 0, source, null));
        audit.recordSuccess("CLOCKED_IN", "TimeEntry", entry.getId().toString(), actorId);
        return entry;
    }

    @Transactional
    public TimeEntry clockOut(UUID employeeId, Instant at, int breakMinutes, UUID actorId) {
        UUID tenantId = TenantContext.require();
        employees.get(employeeId);

        TimeEntry open = entries.findOpen(tenantId, employeeId)
                .orElseThrow(() -> new ConflictException("Not currently clocked in"));

        open.clockOut(at == null ? Instant.now() : at, breakMinutes);
        audit.recordSuccess("CLOCKED_OUT", "TimeEntry", open.getId().toString(), actorId);
        return open;
    }

    /**
     * Records a shift after the fact, for the days somebody forgot to clock.
     *
     * <p>Refuses to overlap anything already recorded. Two entries covering the same hour is the
     * single most expensive mistake this table can hold.
     */
    @Transactional
    public TimeEntry record(UUID employeeId, LocalDate workDate, Instant startedAt,
                            Instant endedAt, int breakMinutes, TimeEntrySource source,
                            String notes, UUID actorId) {
        UUID tenantId = TenantContext.require();
        Employee employee = employees.get(employeeId);

        if (workDate == null) {
            throw new IllegalArgumentException("A time entry needs a work date");
        }
        if (endedAt == null) {
            throw new IllegalArgumentException(
                    "A recorded shift needs an end; use clock-in for one still running");
        }
        requireOpenPeriod(employeeId, workDate);
        requireNoOverlap(tenantId, employeeId, startedAt, endedAt, null);

        TimeEntry entry = entries.save(new TimeEntry(
                employee, workDate, startedAt, endedAt, breakMinutes, source, notes));
        audit.recordSuccess("TIME_RECORDED", "TimeEntry", entry.getId().toString(), actorId);
        return entry;
    }

    /**
     * Corrects an entry by replacing it.
     *
     * <p>The original keeps its row and is marked superseded, so the history reads as a sequence.
     * The reason is required: an amendment with no explanation is indistinguishable from
     * tampering, and this is exactly the table somebody would tamper with.
     */
    @Transactional
    public TimeEntry amend(UUID entryId, Instant startedAt, Instant endedAt, int breakMinutes,
                           String reason, UUID actorId) {
        UUID tenantId = TenantContext.require();
        TimeEntry original = get(entryId);

        if (original.isSuperseded()) {
            throw new ConflictException("This entry has already been corrected");
        }
        if (endedAt == null) {
            throw new IllegalArgumentException("A correction needs a complete shift");
        }
        requireOpenPeriod(original.getEmployee().getId(), original.getWorkDate());
        requireNoOverlap(tenantId, original.getEmployee().getId(), startedAt, endedAt, entryId);

        TimeEntry replacement = new TimeEntry(
                original.getEmployee(), original.getWorkDate(), startedAt, endedAt,
                breakMinutes, original.getSource(), original.getNotes());
        replacement.recordAmendment(original, reason);
        original.supersede();

        TimeEntry saved = entries.save(replacement);
        audit.recordSuccess("TIME_AMENDED", "TimeEntry", saved.getId().toString(), actorId);
        return saved;
    }

    /**
     * Refuses to touch a day inside an approved timesheet.
     *
     * <p>Once a period is signed off it is the basis of a payslip. Changing the hours underneath
     * it would leave the approval attached to figures nobody agreed to; the correct move is to
     * reopen or adjust in payroll, deliberately.
     */
    private void requireOpenPeriod(UUID employeeId, LocalDate day) {
        timesheets.findByTenantIdAndEmployeeIdOrderByPeriodStartDesc(
                        TenantContext.require(), employeeId).stream()
                .filter(sheet -> sheet.getStatus() == TimesheetStatus.APPROVED)
                .filter(sheet -> sheet.covers(day))
                .findFirst()
                .ifPresent(sheet -> {
                    throw new ConflictException(
                            "The timesheet covering " + day + " has been approved; "
                                    + "reopen it before changing the hours");
                });
    }

    private void requireNoOverlap(UUID tenantId, UUID employeeId, Instant start, Instant end,
                                  UUID ignoringId) {
        List<TimeEntry> clashes = entries.findOverlapping(tenantId, employeeId, start, end).stream()
                .filter(existing -> !existing.getId().equals(ignoringId))
                .toList();
        if (!clashes.isEmpty()) {
            throw new ConflictException(
                    "This overlaps time already recorded from " + clashes.get(0).getStartedAt());
        }
    }

    public ZoneId getZone() {
        return zone;
    }

    public static class TimeEntryNotFoundException extends ResourceNotFoundException {
        public TimeEntryNotFoundException(UUID id) {
            super("Time entry not found: " + id);
        }
    }
}
