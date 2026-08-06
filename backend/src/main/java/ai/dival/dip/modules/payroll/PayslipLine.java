package ai.dival.dip.modules.payroll;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * One figure on a payslip.
 *
 * <p>The component's code and name are copied rather than referenced. Renaming "Transport
 * allowance" next year must not rewrite what somebody was told they were paid last year.
 *
 * <p>{@code basis} records how the amount was arrived at in words, so a query about a figure has
 * an answer that does not require re-running the calculation — which is the only kind of answer
 * useful to somebody looking at a payslip from eight months ago.
 */
@Entity
@Table(name = "payslip_line")
public class PayslipLine extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payslip_id", nullable = false)
    private Payslip payslip;

    @Column(name = "component_code", nullable = false, length = 50)
    private String componentCode;

    @Column(name = "component_name", nullable = false, length = 200)
    private String componentName;

    @Enumerated(EnumType.STRING)
    @Column(name = "component_type", nullable = false, length = 30)
    private ComponentType componentType;

    @Column(name = "basis", length = 200)
    private String basis;

    @Column(name = "quantity", precision = 12, scale = 2)
    private BigDecimal quantity;

    @Column(name = "rate", precision = 18, scale = 4)
    private BigDecimal rate;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 100;

    protected PayslipLine() {
        // for JPA
    }

    PayslipLine(Payslip payslip, String componentCode, String componentName, ComponentType type,
                String basis, BigDecimal quantity, BigDecimal rate, BigDecimal amount,
                int sortOrder) {
        this.payslip = payslip;
        this.componentCode = componentCode;
        this.componentName = componentName;
        this.componentType = type;
        this.basis = basis;
        this.quantity = quantity;
        this.rate = rate;
        this.amount = amount;
        this.sortOrder = sortOrder;
    }

    public Payslip getPayslip() {
        return payslip;
    }

    public String getComponentCode() {
        return componentCode;
    }

    public String getComponentName() {
        return componentName;
    }

    public ComponentType getComponentType() {
        return componentType;
    }

    public String getBasis() {
        return basis;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public int getSortOrder() {
        return sortOrder;
    }
}
