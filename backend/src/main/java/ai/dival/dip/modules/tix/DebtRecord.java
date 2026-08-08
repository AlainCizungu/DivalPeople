package ai.dival.dip.modules.tix;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import ai.dival.dip.modules.ingest.RecordOrigin;
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
import java.util.UUID;

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

    /**
     * How this record entered the platform.
     *
     * <p>NOT NULL, and V20's check constraint ties it to {@link #rawRecordId}: an IMPORT must name
     * the row it came from and an API_DECLARATION must not pretend to have one. Modelling this as
     * an origin rather than as a nullable foreign key is what stops "imported, source unknown"
     * from existing as a silent third state — which is the state rule 5 exists to prevent.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, updatable = false, length = 20)
    private RecordOrigin origin;

    /**
     * The raw row this was derived from, when there is one.
     *
     * <p>A plain UUID rather than a {@code @ManyToOne} to the ingest module's entity. The foreign
     * key is real and enforced by the database; what is avoided is a compile-time dependency from
     * tix on ingest's persistence, which would make every schema change in one a change in the
     * other. The architecture check forbids importing another module's repository, and the same
     * reasoning applies a level down.
     */
    @Column(name = "raw_record_id", updatable = false)
    private UUID rawRecordId;

    /**
     * The rights case currently suppressing this record, if any.
     *
     * <p>Recorded so that closing a case lifts the suppression it caused and nothing else, and so
     * that an auditor asking "why is this record invisible" gets an answer rather than a status.
     */
    @Column(name = "suppressed_by_request_id")
    private UUID suppressedByRequestId;

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
        // Declared through the API unless something says otherwise. The alternative — leaving it
        // null and setting it at the call site — would put a NOT NULL column one forgotten line
        // away from a constraint violation at flush time.
        this.origin = RecordOrigin.API_DECLARATION;
    }

    /**
     * Marks this record as derived from an imported row.
     *
     * <p>Package-private and one-way: a record can be told where it came from while it is being
     * built, and nothing can later claim an API declaration came from a file. Provenance that can
     * be rewritten is not provenance.
     */
    void derivedFrom(UUID rawRecordId) {
        if (rawRecordId == null) {
            throw new IllegalArgumentException("An imported record must name the row it came from");
        }
        this.origin = RecordOrigin.IMPORT;
        this.rawRecordId = rawRecordId;
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

    /**
     * Suppresses this record because the person it describes is contesting it.
     *
     * <p>Deliberately available while a case is merely open, before it has been decided. Somebody
     * who says "that is not my debt" should stop being reported to other operators while the
     * claim is examined, not after — the harm of being wrongly listed accrues daily, and the
     * alternative is a person losing a contract during the weeks their case is considered.
     */
    void suppressFor(UUID requestId) {
        this.suppressedByRequestId = requestId;
        this.status = DebtStatus.DISPUTED;
        this.updatedAt = Instant.now();
    }

    /**
     * Restores a record once the case that suppressed it closes.
     *
     * <p>Returns to SETTLED if it was settled and OUTSTANDING otherwise, derived from
     * {@code settledAt} rather than remembered — a "previous status" column would be one more
     * thing that can disagree with the facts.
     */
    void liftSuppression() {
        this.suppressedByRequestId = null;
        this.status = settledAt == null ? DebtStatus.OUTSTANDING : DebtStatus.SETTLED;
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

    public RecordOrigin getOrigin() {
        return origin;
    }

    public UUID getRawRecordId() {
        return rawRecordId;
    }

    public UUID getSuppressedByRequestId() {
        return suppressedByRequestId;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
