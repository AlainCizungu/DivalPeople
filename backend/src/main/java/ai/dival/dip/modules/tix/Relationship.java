package ai.dival.dip.modules.tix;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An account an operator holds with a subject.
 *
 * <p>The obligation itself, whether or not anything has gone wrong with it. {@link DebtRecord}
 * remains the adverse <em>declaration</em> about one, and the two are deliberately separate: a
 * declared debt carries a retention clock, dispute rights and an Article 214 duty to tell everyone
 * who enquired if it turns out to be wrong. "Paid as agreed" carries none of that, and pushing
 * every routine monthly event through the rights machinery would make that machinery a hundred
 * times larger for no gain.
 *
 * <p><strong>This entity holds no status.</strong> What is true of the account right now is derived
 * by reading {@link RelationshipEvent}s, which are append-only. A status column can be quietly
 * edited to say a company always paid on time; an event log has to be contradicted in the open, by
 * another dated row that stays there. That is the property that makes a payment history worth
 * anything to a lender.
 *
 * <p>{@code closedOn} is the one exception and it is not a status: it is a denormalisation for the
 * retention purge, which has to find dormant accounts without replaying every event of every
 * account in the network.
 */
@Entity
@Table(name = "tix_relationship")
public class Relationship extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false, updatable = false)
    private Subject subject;

    /**
     * The operator's own reference for this account.
     *
     * <p>Scoped to the operator by a unique index on {@code (tenant_id, account_reference)},
     * exactly as account references on debt records are. Two operators may legitimately both call
     * an account "0001", and an index that forgot the tenant would make the second one to import
     * a book fail on a collision with a competitor it cannot see.
     */
    @Column(name = "account_reference", nullable = false, updatable = false)
    private String accountReference;

    /** A telecom's POSTPAID, a bank's TERM LOAN. Free text; there is no shared taxonomy. */
    @Column(nullable = false)
    private String product;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "opened_on", nullable = false, updatable = false)
    private LocalDate openedOn;

    @Column(name = "closed_on")
    private LocalDate closedOn;

    @Column(name = "retention_until", nullable = false)
    private LocalDate retentionUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * The account's history, for reading.
     *
     * <p>No cascade and no orphan removal, and that is not an oversight. Events are appended
     * through {@link RelationshipService}, which inserts them directly — routing writes through
     * this collection would mean loading an account's entire history to add one row to it, and a
     * telecom's monthly book is a great many rows.
     *
     * <p>More importantly, a mapped collection with {@code orphanRemoval} would give the
     * application a way to delete a single event by removing it from a list. The database withholds
     * DELETE on this table on purpose; the mapping should not quietly offer what the grant refuses.
     *
     * <p>Ordered by when things happened, not by when they were inserted. A book delivered out of
     * sequence still reads as a timeline.
     */
    @OneToMany(mappedBy = "relationship", fetch = FetchType.LAZY)
    @OrderBy("occurredOn asc, createdAt asc")
    private List<RelationshipEvent> events = new ArrayList<>();

    protected Relationship() {
    }

    public Relationship(Subject subject, String accountReference, String product, String currency,
                        LocalDate openedOn, LocalDate retentionUntil) {
        this.subject = subject;
        this.accountReference = accountReference;
        this.product = product;
        this.currency = currency;
        this.openedOn = openedOn;
        this.retentionUntil = retentionUntil;
    }

    /**
     * Records that the account has ended.
     *
     * <p>Package-private and one-way. Closing is a consequence of an event arriving, never
     * something a caller decides directly — {@link RelationshipService} sets it when it appends a
     * CLOSED or SETTLED event, so the flag cannot come to disagree with the log it summarises.
     *
     * <p>The earliest closing date wins. An operator that sends SETTLED in March and CLOSED in
     * April has described one ending twice, and the second should not move the retention clock.
     */
    void closeOn(LocalDate date) {
        if (closedOn == null || date.isBefore(closedOn)) {
            closedOn = date;
        }
    }

    /** Extends how long this account stays visible. Never shortens it; see RetentionPolicy. */
    void keepUntil(LocalDate date) {
        if (date.isAfter(retentionUntil)) {
            retentionUntil = date;
        }
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Subject getSubject() {
        return subject;
    }

    public String getAccountReference() {
        return accountReference;
    }

    public String getProduct() {
        return product;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getOpenedOn() {
        return openedOn;
    }

    public LocalDate getClosedOn() {
        return closedOn;
    }

    public LocalDate getRetentionUntil() {
        return retentionUntil;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    /** Unmodifiable: appending goes through RelationshipService, which inserts the row. */
    public List<RelationshipEvent> getEvents() {
        return Collections.unmodifiableList(events);
    }
}
