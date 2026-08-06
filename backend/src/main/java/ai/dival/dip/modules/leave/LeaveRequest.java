package ai.dival.dip.modules.leave;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.files.StoredFile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Somebody asking to be away, and what was decided about it. */
@Entity
@Table(name = "leave_request")
public class LeaveRequest extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "half_day_start", nullable = false)
    private boolean halfDayStart;

    @Column(name = "half_day_end", nullable = false)
    private boolean halfDayEnd;

    /**
     * Working days, computed at submission against the holiday calendar of that moment.
     *
     * <p>Stored rather than recomputed on read: a public holiday declared afterwards must not
     * silently change what somebody was charged, in either direction.
     */
    @Column(name = "days", nullable = false, precision = 5, scale = 2)
    private BigDecimal days;

    @Column(name = "leave_year", nullable = false)
    private int leaveYear;

    @Column(name = "reason", length = 2000)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "document_id")
    private StoredFile document;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LeaveRequestStatus status = LeaveRequestStatus.SUBMITTED;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_id")
    private Employee approver;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_notes", length = 2000)
    private String decisionNotes;

    protected LeaveRequest() {
        // for JPA
    }

    public LeaveRequest(Employee employee, LeaveType leaveType, LocalDate startDate,
                        LocalDate endDate, boolean halfDayStart, boolean halfDayEnd,
                        BigDecimal days, String reason) {
        this.employee = employee;
        this.leaveType = leaveType;
        this.startDate = startDate;
        this.endDate = endDate;
        this.halfDayStart = halfDayStart;
        this.halfDayEnd = halfDayEnd;
        this.days = days;
        this.leaveYear = startDate.getYear();
        this.reason = reason;
        this.status = LeaveRequestStatus.SUBMITTED;
    }

    public void attach(StoredFile document) {
        this.document = document;
    }

    /**
     * Approves the request.
     *
     * @param approver the person who allowed it, recorded separately from whoever typed it in —
     *                 "who approved my leave" is the first question asked when it is queried
     */
    public void approve(Employee approver, String notes) {
        requirePending();
        this.status = LeaveRequestStatus.APPROVED;
        this.approver = approver;
        this.decisionNotes = notes;
        this.decidedAt = Instant.now();
    }

    /** Refusing somebody's leave without saying why is the kind of silence that gets disputed. */
    public void reject(Employee approver, String notes) {
        requirePending();
        if (notes == null || notes.isBlank()) {
            throw new IllegalArgumentException("A refusal needs a reason");
        }
        this.status = LeaveRequestStatus.REJECTED;
        this.approver = approver;
        this.decisionNotes = notes;
        this.decidedAt = Instant.now();
    }

    /**
     * Withdrawn, before or after approval.
     *
     * <p>Approved leave that has already started cannot be cancelled here: the days were lived,
     * and unwinding them is a correction somebody has to make deliberately.
     */
    public void cancel(LocalDate today) {
        if (status.isFinal() && status != LeaveRequestStatus.APPROVED) {
            throw new ConflictException("This request is already closed");
        }
        if (status == LeaveRequestStatus.APPROVED && !startDate.isAfter(today)) {
            throw new ConflictException(
                    "Leave that has already begun cannot be cancelled; record an adjustment");
        }
        this.status = LeaveRequestStatus.CANCELLED;
        this.decidedAt = Instant.now();
    }

    public boolean overlaps(LocalDate otherStart, LocalDate otherEnd) {
        return !startDate.isAfter(otherEnd) && !endDate.isBefore(otherStart);
    }

    private void requirePending() {
        if (status != LeaveRequestStatus.SUBMITTED) {
            throw new ConflictException("This request has already been decided");
        }
    }

    public Employee getEmployee() {
        return employee;
    }

    public LeaveType getLeaveType() {
        return leaveType;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public boolean isHalfDayStart() {
        return halfDayStart;
    }

    public boolean isHalfDayEnd() {
        return halfDayEnd;
    }

    public BigDecimal getDays() {
        return days;
    }

    public int getLeaveYear() {
        return leaveYear;
    }

    public String getReason() {
        return reason;
    }

    public StoredFile getDocument() {
        return document;
    }

    public LeaveRequestStatus getStatus() {
        return status;
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
