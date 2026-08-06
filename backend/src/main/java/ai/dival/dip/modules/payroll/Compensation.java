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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;

/**
 * What somebody is paid, over a period of time.
 *
 * <p>Effective-dated and never overwritten. A payroll run for March must use the salary that was
 * in force in March, however many raises have happened since — a row updated in place makes every
 * historical payslip unexplainable, and pay history is exactly what a pay-equity review reads.
 */
@Entity
@Table(name = "compensation")
public class Compensation extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Null means still in force. Only one row per person may be open-ended. */
    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Column(name = "base_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal baseAmount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "pay_frequency", nullable = false, length = 20)
    private PayFrequency payFrequency = PayFrequency.MONTHLY;

    @Column(name = "reason", length = 500)
    private String reason;

    protected Compensation() {
        // for JPA
    }

    public Compensation(Employee employee, LocalDate effectiveFrom, BigDecimal baseAmount,
                        String currency, PayFrequency payFrequency, String reason) {
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("Compensation needs a start date");
        }
        if (baseAmount == null || baseAmount.signum() <= 0) {
            throw new IllegalArgumentException("A salary must be a positive amount");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("An amount without a currency is not a salary");
        }

        this.employee = employee;
        this.effectiveFrom = effectiveFrom;
        this.baseAmount = baseAmount.setScale(2, RoundingMode.HALF_UP);
        this.currency = currency.trim().toUpperCase(Locale.ROOT);
        this.payFrequency = payFrequency == null ? PayFrequency.MONTHLY : payFrequency;
        this.reason = reason;
    }

    /**
     * Closes this record the day before a new one starts.
     *
     * <p>Called by the service as it opens the replacement, so the two are never both open.
     */
    void closeOn(LocalDate lastDay) {
        if (effectiveTo != null) {
            throw new ConflictException("This compensation record is already closed");
        }
        if (lastDay.isBefore(effectiveFrom)) {
            throw new IllegalArgumentException(
                    "A salary cannot end before it started; back-date the new one further");
        }
        this.effectiveTo = lastDay;
    }

    public boolean coversDate(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    public boolean isCurrent() {
        return effectiveTo == null;
    }

    /**
     * What this salary is worth for one pay period of a given frequency.
     *
     * <p>Converted through the annual figure rather than directly, so a monthly salary paid
     * weekly and a weekly salary paid monthly agree with each other.
     */
    public BigDecimal amountForPeriod(PayFrequency periodFrequency) {
        if (periodFrequency == payFrequency) {
            return baseAmount;
        }
        BigDecimal annual = baseAmount.multiply(
                BigDecimal.valueOf(payFrequency.periodsPerYear()));
        return annual.divide(BigDecimal.valueOf(periodFrequency.periodsPerYear()), 2,
                RoundingMode.HALF_UP);
    }

    public Employee getEmployee() {
        return employee;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public BigDecimal getBaseAmount() {
        return baseAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public PayFrequency getPayFrequency() {
        return payFrequency;
    }

    public String getReason() {
        return reason;
    }
}
