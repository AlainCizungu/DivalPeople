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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Something that happened to an account, dated.
 *
 * <p><strong>Append-only, and the database is what enforces it.</strong> {@code dip_app} is granted
 * SELECT and INSERT on this table and nothing else — no UPDATE, no DELETE. A correction is a new
 * dated event that contradicts the old one, and the old one stays. That is not a convention
 * somebody has to remember; it is a privilege the application does not hold.
 *
 * <p>Every field is final after insert for the same reason. There is no setter here and no
 * lifecycle callback that changes anything: an event is a claim about a moment, and a claim that
 * can be revised in place is not evidence.
 *
 * <p>Deletion happens only with the parent account, by cascade, when retention expires. Withholding
 * DELETE even from the purge is deliberate: erasure happens at the account, so nobody can quietly
 * remove the one event that made a history look bad while leaving the account standing.
 */
@Entity
@Table(name = "tix_relationship_event")
public class RelationshipEvent extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "relationship_id", nullable = false, updatable = false)
    private Relationship relationship;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private ObligationEvent code;

    /**
     * When it happened, which is not when it was reported.
     *
     * <p>A telecom sending March's book in May must be able to say so. The difference between the
     * two dates is most of what a payment history is: an account reported late is not an account
     * that paid late, and a model that conflated them would penalise the operator's filing habits.
     */
    @Column(name = "occurred_on", nullable = false, updatable = false)
    private LocalDate occurredOn;

    /** Whether {@link #occurredOn} came from the file or was inferred. Same rule as debt records. */
    @Enumerated(EnumType.STRING)
    @Column(name = "date_source", nullable = false, updatable = false, length = 20)
    private DateSource dateSource;

    /** The delivered row this came from, when it came from a delivery rather than a form. */
    @Column(name = "raw_record_id", updatable = false)
    private UUID rawRecordId;

    protected RelationshipEvent() {
    }

    public RelationshipEvent(Relationship relationship, ObligationEvent code, LocalDate occurredOn,
                             DateSource dateSource, UUID rawRecordId) {
        this.relationship = relationship;
        this.code = code;
        this.occurredOn = occurredOn;
        this.dateSource = dateSource;
        this.rawRecordId = rawRecordId;
    }

    public Relationship getRelationship() {
        return relationship;
    }

    public ObligationEvent getCode() {
        return code;
    }

    public LocalDate getOccurredOn() {
        return occurredOn;
    }

    public DateSource getDateSource() {
        return dateSource;
    }

    public UUID getRawRecordId() {
        return rawRecordId;
    }
}
