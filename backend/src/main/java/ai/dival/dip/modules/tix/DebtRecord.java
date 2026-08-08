package ai.dival.dip.modules.tix;

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
import java.time.Instant;
import java.time.LocalDate;

/**
 * An obligation declared by one operator against a subject.
 *
 * <p>Tenant-owned: the declaring operator owns the record and is the only party able to settle it.
 * Other operators observe it only through the exchange, and only as a {@link DebtStatus}.
 */
@Entity
@Table(name = "tix_debt_record")
public class DebtRecord extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private Subject subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DebtStatus status;

    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "service_category", nullable = false, length = 60)
    private String serviceCategory;

    @Column(name = "default_date", nullable = false)
    private LocalDate defaultDate;

    /** Evidence that the contractual dunning process ran before declaration. */
    @Column(name = "dunning_evidence", nullable = false)
    private boolean dunningEvidence;

    @Column(name = "settled_at")
    private Instant settledAt;

    /**
     * The day after which this record must not exist.
     *
     * <p>Set once, at declaration, from the default date — never from the declaration date, or an
     * operator could keep somebody listed by declaring an old debt late. Settlement may bring it
     * forward and nothing may push it back.
     */
    @Column(name = "retention_until", nullable = false)
    private LocalDate retentionUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DebtRecord() {
        // for JPA
    }

    public DebtRecord(Subject subject, BigDecimal amount, String currency, String serviceCategory,
                      LocalDate defaultDate, boolean dunningEvidence) {
        this.subject = subject;
        this.amount = amount;
        this.currency = currency;
        this.serviceCategory = serviceCategory;
        this.defaultDate = defaultDate;
        this.dunningEvidence = dunningEvidence;
        this.status = DebtStatus.OUTSTANDING;
        this.updatedAt = Instant.now();
    }

    /**
     * Fixes when this record expires.
     *
     * <p>Package-private and callable once: retention is set by {@link DebtRecordService} at
     * declaration and by settlement, and there is no path for a caller to extend it. A public
     * setter here would be an endpoint away from becoming a way to keep somebody in a national
     * registry indefinitely.
     */
    void retainUntil(LocalDate expiry) {
        this.retentionUntil = expiry;
    }

    /** Marks the obligation resolved. Only the declaring operator may call this. */
    public void settle() {
        if (status == DebtStatus.SETTLED) {
            return;
        }
        this.status = DebtStatus.SETTLED;
        this.settledAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Whether this record is past its retention period as at {@code today}.
     *
     * <p>The date is passed in rather than read from a clock so that the entity stays free of
     * ambient time — and so a test can ask "what does this look like in four years" without
     * waiting or mocking statics.
     */
    public boolean isExpiredAsOf(LocalDate today) {
        return retentionUntil != null && retentionUntil.isBefore(today);
    }

    /** Suspends the record from inquiry results while the subject's dispute is open. */
    public void dispute() {
        this.status = DebtStatus.DISPUTED;
        this.updatedAt = Instant.now();
    }

    public void flagForInvestigation() {
        this.status = DebtStatus.UNDER_INVESTIGATION;
        this.updatedAt = Instant.now();
    }

    /** A disputed or investigated record must not influence another operator's decision. */
    public boolean isVisibleToOtherOperators() {
        return status == DebtStatus.OUTSTANDING || status == DebtStatus.SETTLED;
    }

    public Subject getSubject() {
        return subject;
    }

    public DebtStatus getStatus() {
        return status;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getServiceCategory() {
        return serviceCategory;
    }

    public LocalDate getDefaultDate() {
        return defaultDate;
    }

    public boolean hasDunningEvidence() {
        return dunningEvidence;
    }

    public Instant getSettledAt() {
        return settledAt;
    }

    public LocalDate getRetentionUntil() {
        return retentionUntil;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
