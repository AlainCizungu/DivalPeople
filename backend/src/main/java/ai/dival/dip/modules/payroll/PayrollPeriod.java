package ai.dival.dip.modules.payroll;

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
 * A pay run: the window being paid for, and how far through sign-off it is.
 *
 * <p>Each transition is deliberate. A period is calculated, reviewed, approved and marked paid,
 * and an approved period cannot be recalculated without an explicit reopening that says so in the
 * audit log — because "the numbers changed after I approved them" is the failure that makes an
 * approval worthless.
 */
@Entity
@Table(name = "payroll_period")
public class PayrollPeriod extends TenantOwnedEntity {

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    /** When money actually leaves. Most payrolls pay in arrears, so this is not period end. */
    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PeriodStatus status = PeriodStatus.DRAFT;

    @Column(name = "calculated_at")
    private Instant calculatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approver_id")
    private Employee approver;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "notes", length = 2000)
    private String notes;

    protected PayrollPeriod() {
        // for JPA
    }

    public PayrollPeriod(String name, LocalDate periodStart, LocalDate periodEnd,
                         LocalDate paymentDate) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A payroll period needs a name");
        }
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("A period cannot end before it starts");
        }
        if (paymentDate == null) {
            throw new IllegalArgumentException("A payroll period needs a payment date");
        }
        this.name = name.trim();
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.paymentDate = paymentDate;
        this.status = PeriodStatus.DRAFT;
    }

    void markCalculated() {
        if (!status.isOpenForCalculation()) {
            throw new ConflictException("This period is not open for calculation");
        }
        this.status = PeriodStatus.CALCULATED;
        this.calculatedAt = Instant.now();
    }

    /**
     * Signs the run off.
     *
     * <p>The approver is recorded because "who authorised this payroll" is the first question in
     * any financial review, and an approval inferred from an audit log is not an approval.
     */
    public void approve(Employee approver, String notes) {
        if (status != PeriodStatus.CALCULATED) {
            throw new ConflictException("Only a calculated period can be approved");
        }
        if (approver == null) {
            throw new IllegalArgumentException("An approval needs a named approver");
        }
        this.status = PeriodStatus.APPROVED;
        this.approver = approver;
        this.approvedAt = Instant.now();
        this.notes = notes;
    }

    /**
     * Returns an approved period to draft.
     *
     * <p>Deliberately clears the approval. Recalculating under a signature somebody already gave
     * is precisely the thing this module exists to prevent, so reopening takes the signature with
     * it and the run has to be approved again.
     */
    public void reopen() {
        if (status != PeriodStatus.APPROVED) {
            throw new ConflictException("Only an approved period can be reopened");
        }
        this.status = PeriodStatus.DRAFT;
        this.approver = null;
        this.approvedAt = null;
        this.calculatedAt = null;
    }

    /** Records that payment was made. This module does not move money. */
    public void markPaid() {
        if (status != PeriodStatus.APPROVED) {
            throw new ConflictException("Only an approved period can be marked paid");
        }
        this.status = PeriodStatus.PAID;
        this.paidAt = Instant.now();
    }

    public void cancel() {
        if (status == PeriodStatus.PAID) {
            throw new ConflictException("A period that has been paid cannot be cancelled");
        }
        this.status = PeriodStatus.CANCELLED;
    }

    public boolean covers(LocalDate day) {
        return !day.isBefore(periodStart) && !day.isAfter(periodEnd);
    }

    public String getName() {
        return name;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public LocalDate getPaymentDate() {
        return paymentDate;
    }

    public PeriodStatus getStatus() {
        return status;
    }

    public Instant getCalculatedAt() {
        return calculatedAt;
    }

    public Employee getApprover() {
        return approver;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public String getNotes() {
        return notes;
    }
}
