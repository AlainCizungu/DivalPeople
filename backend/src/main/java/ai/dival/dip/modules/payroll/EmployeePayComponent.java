package ai.dival.dip.modules.payroll;

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
import java.time.LocalDate;

/**
 * A component that applies to one person every period.
 *
 * <p>Effective-dated for the same reason compensation is: a benefit that started in June must not
 * appear on May's payslip when somebody reprints it.
 */
@Entity
@Table(name = "employee_pay_component")
public class EmployeePayComponent extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "component_id", nullable = false)
    private PayComponent component;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    /** Overrides the component's default for this person, where set. */
    @Column(name = "amount", precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "percentage", precision = 7, scale = 4)
    private BigDecimal percentage;

    @Column(name = "notes", length = 500)
    private String notes;

    protected EmployeePayComponent() {
        // for JPA
    }

    public EmployeePayComponent(Employee employee, PayComponent component,
                                LocalDate effectiveFrom, BigDecimal amount,
                                BigDecimal percentage, String notes) {
        if (effectiveFrom == null) {
            throw new IllegalArgumentException("An assignment needs a start date");
        }
        if (amount != null && amount.signum() < 0) {
            throw new IllegalArgumentException("An amount cannot be negative");
        }
        this.employee = employee;
        this.component = component;
        this.effectiveFrom = effectiveFrom;
        this.amount = amount;
        this.percentage = percentage;
        this.notes = notes;
    }

    void closeOn(LocalDate lastDay) {
        if (effectiveTo != null) {
            throw new ConflictException("This assignment is already closed");
        }
        this.effectiveTo = lastDay;
    }

    public boolean coversDate(LocalDate day) {
        return !day.isBefore(effectiveFrom) && (effectiveTo == null || !day.isAfter(effectiveTo));
    }

    /** The amount to use, preferring this person's override over the component's default. */
    public BigDecimal effectiveAmount() {
        return amount != null ? amount : component.getDefaultAmount();
    }

    public BigDecimal effectivePercentage() {
        return percentage != null ? percentage : component.getPercentage();
    }

    public Employee getEmployee() {
        return employee;
    }

    public PayComponent getComponent() {
        return component;
    }

    public LocalDate getEffectiveFrom() {
        return effectiveFrom;
    }

    public LocalDate getEffectiveTo() {
        return effectiveTo;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public BigDecimal getPercentage() {
        return percentage;
    }

    public String getNotes() {
        return notes;
    }
}
