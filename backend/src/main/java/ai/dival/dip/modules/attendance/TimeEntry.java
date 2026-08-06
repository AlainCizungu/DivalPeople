package ai.dival.dip.modules.attendance;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import ai.dival.dip.modules.employees.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One stretch of time somebody was at work.
 *
 * <p>Never edited. A correction creates a new entry pointing back at this one, and this one is
 * marked superseded. Attendance is the record people are paid from and disciplined against, so
 * "it always said that" has to be answerable — and an entry that can be quietly rewritten cannot
 * answer it.
 */
@Entity
@Table(name = "time_entry")
public class TimeEntry extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    /**
     * The day this shift counts against.
     *
     * <p>Not always the calendar date of the clock-in: a night shift starting at 22:00 belongs to
     * the day it started, and splitting it across midnight would make every night worker's
     * timesheet wrong in two places at once.
     */
    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** Null while somebody is still clocked in. The only legitimate null here. */
    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "break_minutes", nullable = false)
    private int breakMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private TimeEntrySource source = TimeEntrySource.MANUAL;

    @Column(name = "notes", length = 2000)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supersedes_id")
    private TimeEntry supersedes;

    @Column(name = "superseded", nullable = false)
    private boolean superseded;

    @Column(name = "amend_reason", length = 500)
    private String amendReason;

    protected TimeEntry() {
        // for JPA
    }

    public TimeEntry(Employee employee, LocalDate workDate, Instant startedAt, Instant endedAt,
                     int breakMinutes, TimeEntrySource source, String notes) {
        if (startedAt == null) {
            throw new IllegalArgumentException("A time entry needs a start");
        }
        if (endedAt != null && !endedAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("A shift cannot end before it starts");
        }
        if (breakMinutes < 0) {
            throw new IllegalArgumentException("A break cannot be negative");
        }

        this.employee = employee;
        this.workDate = workDate;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.breakMinutes = breakMinutes;
        this.source = source == null ? TimeEntrySource.MANUAL : source;
        this.notes = notes;

        requireBreakWithinSpan();
    }

    /** Closes an open entry. The one mutation allowed, because clocking out is not a correction. */
    public void clockOut(Instant endedAt, int breakMinutes) {
        if (this.endedAt != null) {
            throw new ConflictException("This entry is already closed");
        }
        if (superseded) {
            throw new ConflictException("This entry has been superseded");
        }
        if (endedAt == null || !endedAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("A shift cannot end before it starts");
        }
        if (breakMinutes < 0) {
            throw new IllegalArgumentException("A break cannot be negative");
        }

        this.endedAt = endedAt;
        this.breakMinutes = breakMinutes;
        requireBreakWithinSpan();
    }

    /** Marks this entry replaced. Called by the service as it writes the replacement. */
    void supersede() {
        if (superseded) {
            throw new ConflictException("This entry has already been superseded");
        }
        this.superseded = true;
    }

    void recordAmendment(TimeEntry original, String reason) {
        if (reason == null || reason.isBlank()) {
            // An amendment with no explanation is indistinguishable from tampering.
            throw new IllegalArgumentException("An amendment needs a reason");
        }
        this.supersedes = original;
        this.amendReason = reason;
    }

    /** Time actually worked: the span, less unpaid breaks. Zero while still clocked in. */
    public int workedMinutes() {
        if (endedAt == null) {
            return 0;
        }
        return (int) Duration.between(startedAt, endedAt).toMinutes() - breakMinutes;
    }

    /** How long somebody was on site, breaks included. What a safety question asks. */
    public int spanMinutes() {
        return endedAt == null ? 0 : (int) Duration.between(startedAt, endedAt).toMinutes();
    }

    public boolean isOpen() {
        return endedAt == null && !superseded;
    }

    /** True when this entry's span touches another's. Used to refuse double-counted hours. */
    public boolean overlaps(Instant otherStart, Instant otherEnd) {
        Instant end = endedAt == null ? Instant.MAX : endedAt;
        Instant theirEnd = otherEnd == null ? Instant.MAX : otherEnd;
        return startedAt.isBefore(theirEnd) && otherStart.isBefore(end);
    }

    private void requireBreakWithinSpan() {
        if (endedAt != null && breakMinutes > spanMinutes()) {
            // Negative worked time becomes a negative payslip line.
            throw new IllegalArgumentException("A break cannot be longer than the shift");
        }
    }

    public Employee getEmployee() {
        return employee;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public int getBreakMinutes() {
        return breakMinutes;
    }

    public TimeEntrySource getSource() {
        return source;
    }

    public String getNotes() {
        return notes;
    }

    public TimeEntry getSupersedes() {
        return supersedes;
    }

    public boolean isSuperseded() {
        return superseded;
    }

    public String getAmendReason() {
        return amendReason;
    }
}
