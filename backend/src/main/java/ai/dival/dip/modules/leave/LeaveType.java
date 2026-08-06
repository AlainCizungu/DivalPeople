package ai.dival.dip.modules.leave;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

/** A kind of leave, and the policy attached to it. */
@Entity
@Table(name = "leave_type")
public class LeaveType extends TenantOwnedEntity {

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Unpaid leave still consumes a balance and still needs approval; payroll is what differs. */
    @Column(name = "paid", nullable = false)
    private boolean paid = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "accrual_method", nullable = false, length = 20)
    private AccrualMethod accrualMethod = AccrualMethod.ANNUAL_GRANT;

    @Column(name = "entitlement_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal entitlementDays = BigDecimal.ZERO;

    @Column(name = "carryover_max_days", nullable = false, precision = 5, scale = 2)
    private BigDecimal carryoverMaxDays = BigDecimal.ZERO;

    /** Sick leave beyond a few days usually needs a certificate. Null means never. */
    @Column(name = "document_after_days", precision = 5, scale = 2)
    private BigDecimal documentAfterDays;

    @Column(name = "allows_half_day", nullable = false)
    private boolean allowsHalfDay = true;

    /**
     * Whether the balance may go below zero.
     *
     * <p>True for leave that is an entitlement rather than a budget — statutory sick leave in most
     * jurisdictions cannot be refused for want of balance.
     */
    @Column(name = "allows_negative", nullable = false)
    private boolean allowsNegative;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected LeaveType() {
        // for JPA
    }

    public LeaveType(String code, String name, BigDecimal entitlementDays,
                     AccrualMethod accrualMethod) {
        this.code = normalizeCode(code);
        this.name = name == null ? null : name.trim();
        this.entitlementDays = entitlementDays == null ? BigDecimal.ZERO : entitlementDays;
        this.accrualMethod = accrualMethod == null ? AccrualMethod.ANNUAL_GRANT : accrualMethod;
        this.active = true;
    }

    public static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    public void configure(boolean paid, BigDecimal carryoverMaxDays, BigDecimal documentAfterDays,
                          boolean allowsHalfDay, boolean allowsNegative) {
        if (carryoverMaxDays != null && carryoverMaxDays.signum() < 0) {
            throw new IllegalArgumentException("Carryover cannot be negative");
        }
        if (documentAfterDays != null && documentAfterDays.signum() <= 0) {
            throw new IllegalArgumentException(
                    "A document threshold must be positive, or absent to mean never");
        }
        this.paid = paid;
        this.carryoverMaxDays = carryoverMaxDays == null ? BigDecimal.ZERO : carryoverMaxDays;
        this.documentAfterDays = documentAfterDays;
        this.allowsHalfDay = allowsHalfDay;
        this.allowsNegative = allowsNegative;
    }

    /** Retired rather than deleted: balances and requests already reference it. */
    public void retire() {
        this.active = false;
    }

    /**
     * What one month of accrual is worth.
     *
     * <p>Rounded to two places at the last step rather than each month, so twelve months of a
     * 20-day entitlement come to 20 days and not 19.96.
     */
    public BigDecimal accrualForMonths(int months) {
        if (accrualMethod == AccrualMethod.ANNUAL_GRANT) {
            return entitlementDays;
        }
        return entitlementDays
                .multiply(BigDecimal.valueOf(months))
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }

    public boolean requiresDocumentFor(BigDecimal days) {
        return documentAfterDays != null && days.compareTo(documentAfterDays) > 0;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public boolean isPaid() {
        return paid;
    }

    public AccrualMethod getAccrualMethod() {
        return accrualMethod;
    }

    public BigDecimal getEntitlementDays() {
        return entitlementDays;
    }

    public BigDecimal getCarryoverMaxDays() {
        return carryoverMaxDays;
    }

    public BigDecimal getDocumentAfterDays() {
        return documentAfterDays;
    }

    public boolean isAllowsHalfDay() {
        return allowsHalfDay;
    }

    public boolean isAllowsNegative() {
        return allowsNegative;
    }

    public boolean isActive() {
        return active;
    }
}
