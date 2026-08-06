package ai.dival.dip.modules.payroll;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.Locale;

/**
 * Something added to or taken off a payslip.
 *
 * <p>Statutory deductions are configured here rather than written in code, because their rates
 * are jurisdiction-specific and change by decree. See docs/PAYROLL_SCOPE.md for why this module
 * refuses to contain a tax table.
 */
@Entity
@Table(name = "pay_component")
public class PayComponent extends TenantOwnedEntity {

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "component_type", nullable = false, length = 30)
    private ComponentType componentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "calculation", nullable = false, length = 30)
    private CalculationMethod calculation = CalculationMethod.FIXED;

    @Column(name = "default_amount", precision = 18, scale = 2)
    private BigDecimal defaultAmount;

    @Column(name = "percentage", precision = 7, scale = 4)
    private BigDecimal percentage;

    /**
     * Whether this earning forms part of the base a percentage deduction is taken from.
     *
     * <p>Getting this wrong is the quietest way to compute the wrong deduction: the payslip still
     * reconciles, and only the total is off.
     */
    @Column(name = "taxable", nullable = false)
    private boolean taxable = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected PayComponent() {
        // for JPA
    }

    public PayComponent(String code, String name, ComponentType componentType,
                        CalculationMethod calculation) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A pay component needs a name");
        }
        this.code = normalizeCode(code);
        this.name = name.trim();
        this.componentType = componentType;
        this.calculation = calculation == null ? CalculationMethod.FIXED : calculation;
        this.active = true;
    }

    public static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    public void configure(BigDecimal defaultAmount, BigDecimal percentage, boolean taxable,
                          int sortOrder) {
        if (defaultAmount != null && defaultAmount.signum() < 0) {
            throw new IllegalArgumentException("A default amount cannot be negative");
        }
        if (percentage != null && (percentage.signum() < 0
                || percentage.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new IllegalArgumentException("A percentage is between 0 and 100");
        }
        if (calculation.needsPercentage() && percentage == null) {
            // Without this the component contributes nothing and the payslip still balances,
            // which is worse than failing: the money is simply absent and nothing says so.
            throw new IllegalArgumentException(
                    "A percentage calculation needs a percentage");
        }

        this.defaultAmount = defaultAmount;
        this.percentage = percentage;
        this.taxable = taxable;
        this.sortOrder = sortOrder;
    }

    /** Retired rather than deleted: payslips already reference it by code. */
    public void retire() {
        this.active = false;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public ComponentType getComponentType() {
        return componentType;
    }

    public CalculationMethod getCalculation() {
        return calculation;
    }

    public BigDecimal getDefaultAmount() {
        return defaultAmount;
    }

    public BigDecimal getPercentage() {
        return percentage;
    }

    public boolean isTaxable() {
        return taxable;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public boolean isActive() {
        return active;
    }
}
