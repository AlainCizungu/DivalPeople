package ai.dival.dip.modules.leave;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import ai.dival.dip.modules.employees.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One person's entitlement to one kind of leave in one year.
 *
 * <p>Kept as running totals rather than derived from the ledger on every read, so the overdraft
 * check does not have to sum a year of history while holding a lock. The ledger explains these
 * numbers; this row is what the check enforces against, and the two move in the same transaction.
 *
 * <p>Optimistic locking on {@code version} is what makes that safe: two requests submitted at the
 * same moment cannot both read 5 days remaining and both succeed.
 */
@Entity
@Table(name = "leave_balance")
public class LeaveBalance extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leave_type_id", nullable = false)
    private LeaveType leaveType;

    @Column(name = "leave_year", nullable = false)
    private int leaveYear;

    @Column(name = "opening_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal openingDays = BigDecimal.ZERO;

    @Column(name = "accrued_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal accruedDays = BigDecimal.ZERO;

    @Column(name = "taken_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal takenDays = BigDecimal.ZERO;

    /** Submitted but undecided. Held so a second request cannot spend the same days. */
    @Column(name = "pending_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal pendingDays = BigDecimal.ZERO;

    @Column(name = "adjustment_days", nullable = false, precision = 6, scale = 2)
    private BigDecimal adjustmentDays = BigDecimal.ZERO;

    protected LeaveBalance() {
        // for JPA
    }

    public LeaveBalance(Employee employee, LeaveType leaveType, int leaveYear) {
        this.employee = employee;
        this.leaveType = leaveType;
        this.leaveYear = leaveYear;
    }

    /** Everything earned, less everything spent or spoken for. */
    public BigDecimal available() {
        return openingDays
                .add(accruedDays)
                .add(adjustmentDays)
                .subtract(takenDays)
                .subtract(pendingDays);
    }

    /** Earned less taken, ignoring requests still awaiting a decision. */
    public BigDecimal entitled() {
        return openingDays.add(accruedDays).add(adjustmentDays).subtract(takenDays);
    }

    /**
     * Reserves days for a submitted request.
     *
     * <p>Reserved on submission rather than approval on purpose. Two pending requests that each
     * fit the balance could otherwise both be approved, and the person finds out months later
     * that they were overdrawn all along.
     */
    void reserve(BigDecimal days) {
        requirePositive(days);
        if (!leaveType.isAllowsNegative() && available().compareTo(days) < 0) {
            throw new InsufficientLeaveException(leaveType.getName(), available(), days);
        }
        this.pendingDays = pendingDays.add(days);
    }

    /** Turns a reservation into consumption when the request is approved. */
    void consume(BigDecimal days) {
        requirePositive(days);
        releaseReservation(days);
        this.takenDays = takenDays.add(days);
    }

    /** Gives back a reservation when a submitted request is rejected or withdrawn. */
    void release(BigDecimal days) {
        requirePositive(days);
        releaseReservation(days);
    }

    /** Gives back days already consumed, when approved leave is cancelled. */
    void refund(BigDecimal days) {
        requirePositive(days);
        if (takenDays.compareTo(days) < 0) {
            throw new ConflictException("Cannot return more days than were taken");
        }
        this.takenDays = takenDays.subtract(days);
    }

    void credit(BigDecimal days, LedgerEntryType type) {
        switch (type) {
            case OPENING -> this.openingDays = openingDays.add(days);
            case ACCRUAL, GRANT -> this.accruedDays = accruedDays.add(days);
            case ADJUSTMENT, LAPSED -> this.adjustmentDays = adjustmentDays.add(days);
            default -> throw new IllegalArgumentException(
                    "Not a crediting entry type: " + type);
        }
    }

    private void releaseReservation(BigDecimal days) {
        if (pendingDays.compareTo(days) < 0) {
            // Reaching here means a request was decided twice, or decided without ever having
            // been reserved. Better to fail loudly than to quietly hand somebody free days.
            throw new ConflictException("No matching reservation to release");
        }
        this.pendingDays = pendingDays.subtract(days);
    }

    private static void requirePositive(BigDecimal days) {
        if (days == null || days.signum() <= 0) {
            throw new IllegalArgumentException("A leave movement must be a positive number of days");
        }
    }

    public Employee getEmployee() {
        return employee;
    }

    public LeaveType getLeaveType() {
        return leaveType;
    }

    public int getLeaveYear() {
        return leaveYear;
    }

    public BigDecimal getOpeningDays() {
        return openingDays;
    }

    public BigDecimal getAccruedDays() {
        return accruedDays;
    }

    public BigDecimal getTakenDays() {
        return takenDays;
    }

    public BigDecimal getPendingDays() {
        return pendingDays;
    }

    public BigDecimal getAdjustmentDays() {
        return adjustmentDays;
    }

    /** Refused because the balance will not cover it, with the numbers people will ask for. */
    public static class InsufficientLeaveException extends ConflictException {
        public InsufficientLeaveException(String leaveType, BigDecimal available,
                                          BigDecimal requested) {
            super("Not enough " + leaveType + ": " + available + " day(s) available, "
                    + requested + " requested");
        }
    }
}
