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
import java.time.Instant;
import java.time.LocalDate;

/**
 * A period of attendance, signed off.
 *
 * <p>The figures are frozen at submission rather than recomputed on read. Payroll needs something
 * a human agreed to: a live query would let a payslip and the screen that justified it quietly
 * disagree the moment somebody amended an entry.
 */
@Entity
@Table(name = "timesheet")
public class Timesheet extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "worked_minutes", nullable = false)
    private int workedMinutes;

    /** What this person's pattern says they owed over the period. */
    @Column(name = "expected_minutes", nullable = false)
    private int expectedMinutes;

    /** Time covered by approved leave. Owed, but not absence. */
    @Column(name = "leave_minutes", nullable = false)
    private int leaveMinutes;

    /** Time the office was closed. Also owed, also not absence. */
    @Column(name = "holiday_minutes", nullable = false)
    private int holidayMinutes;

    @Column(name = "overtime_minutes", nullable = false)
    private int overtimeMinutes;

    /** Expected, not worked, and not explained by leave or a holiday. */
    @Column(name = "absent_minutes", nullable = false)
    private int absentMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TimesheetStatus status = TimesheetStatus.DRAFT;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_id")
    private Employee approver;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_notes", length = 2000)
    private String decisionNotes;

    protected Timesheet() {
        // for JPA
    }

    public Timesheet(Employee employee, LocalDate periodStart, LocalDate periodEnd) {
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("A period cannot end before it starts");
        }
        this.employee = employee;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.status = TimesheetStatus.DRAFT;
    }

    /**
     * Writes the computed figures.
     *
     * <p>Only while the sheet is open. Once submitted the numbers are what was agreed, and a
     * recalculation that moved them would make the approval meaningless.
     */
    void setTotals(int workedMinutes, int expectedMinutes, int leaveMinutes, int holidayMinutes,
                   int overtimeMinutes, int absentMinutes) {
        if (!status.isOpen()) {
            throw new ConflictException("A submitted timesheet cannot be recalculated");
        }
        this.workedMinutes = workedMinutes;
        this.expectedMinutes = expectedMinutes;
        this.leaveMinutes = leaveMinutes;
        this.holidayMinutes = holidayMinutes;
        this.overtimeMinutes = overtimeMinutes;
        this.absentMinutes = absentMinutes;
    }

    public void submit() {
        if (!status.isOpen()) {
            throw new ConflictException("This timesheet has already been submitted");
        }
        this.status = TimesheetStatus.SUBMITTED;
        this.submittedAt = Instant.now();
        // A refused sheet that is corrected and resubmitted starts a fresh decision.
        this.decidedAt = null;
        this.decisionNotes = null;
        this.approver = null;
    }

    public void approve(Employee approver, String notes) {
        requireSubmitted();
        this.status = TimesheetStatus.APPROVED;
        this.approver = approver;
        this.decisionNotes = notes;
        this.decidedAt = Instant.now();
    }

    /** Refusing a timesheet decides what somebody is paid, so it has to say why. */
    public void reject(Employee approver, String notes) {
        requireSubmitted();
        if (notes == null || notes.isBlank()) {
            throw new IllegalArgumentException("A refusal needs a reason");
        }
        this.status = TimesheetStatus.REJECTED;
        this.approver = approver;
        this.decisionNotes = notes;
        this.decidedAt = Instant.now();
    }

    public boolean covers(LocalDate day) {
        return !day.isBefore(periodStart) && !day.isAfter(periodEnd);
    }

    private void requireSubmitted() {
        if (status != TimesheetStatus.SUBMITTED) {
            throw new ConflictException("Only a submitted timesheet can be decided");
        }
    }

    public Employee getEmployee() {
        return employee;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public int getWorkedMinutes() {
        return workedMinutes;
    }

    public int getExpectedMinutes() {
        return expectedMinutes;
    }

    public int getLeaveMinutes() {
        return leaveMinutes;
    }

    public int getHolidayMinutes() {
        return holidayMinutes;
    }

    public int getOvertimeMinutes() {
        return overtimeMinutes;
    }

    public int getAbsentMinutes() {
        return absentMinutes;
    }

    public TimesheetStatus getStatus() {
        return status;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Employee getApprover() {
        return approver;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecisionNotes() {
        return decisionNotes;
    }
}
