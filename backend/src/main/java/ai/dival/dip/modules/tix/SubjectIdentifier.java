package ai.dival.dip.modules.tix;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Locale;
import java.util.UUID;

/**
 * A single identifying attribute belonging to a {@link Subject}.
 *
 * <p>No {@code uniqueConstraints} here any more, and the absence is deliberate rather than an
 * omission: uniqueness now depends on the kind of identifier. A national document is unique across
 * the exchange, an account reference only within the operator that issued it, and that is two
 * partial indexes in {@code V26__account_reference.sql} — a shape this annotation cannot express.
 * Writing the old constraint here anyway would describe a rule the database no longer has.
 */
@Entity
@Table(name = "tix_subject_identifier")
public class SubjectIdentifier {

    @Id
    @GeneratedValue
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subject_id", nullable = false)
    private Subject subject;

    @Enumerated(EnumType.STRING)
    @Column(name = "identifier_type", nullable = false, length = 30)
    private IdentifierType identifierType;

    /** Normalised for lookup: trimmed, upper-cased, separators removed. */
    @Column(name = "normalized_value", nullable = false, length = 200)
    private String normalizedValue;

    /**
     * The operator this identifier is meaningful inside, or null for a national document.
     *
     * <p>Not an audit field. It is part of what makes this identifier resolve one subject rather
     * than another, and the database indexes it that way.
     */
    @Column(name = "owner_tenant_id")
    private UUID ownerTenantId;

    protected SubjectIdentifier() {
        // for JPA
    }

    /**
     * @param ownerTenantId the operator that issued the reference, required for an operator-scoped
     *                      type and forbidden for any other
     * @throws IllegalArgumentException when the scope does not match the type. The database says
     *                                  the same thing in a CHECK constraint; this says it in the
     *                                  language of whoever wrote the call, before a row exists
     */
    public SubjectIdentifier(IdentifierType identifierType, String rawValue, UUID ownerTenantId) {
        if (identifierType.isOperatorScoped() != (ownerTenantId != null)) {
            throw new IllegalArgumentException(identifierType.isOperatorScoped()
                    ? identifierType + " belongs to the operator that issued it and cannot be "
                            + "recorded without one."
                    : identifierType + " is a national document and does not belong to any one "
                            + "operator. Recording it against one would hide it from every other.");
        }
        this.identifierType = identifierType;
        this.normalizedValue = normalizeValue(rawValue);
        this.ownerTenantId = ownerTenantId;
    }

    /**
     * Normalises an identifier so that "AB-123 456" and "ab123456" resolve to the same subject.
     * Documents are compared on this value, never on the raw input.
     */
    public static String normalizeValue(String rawValue) {
        if (rawValue == null) {
            return "";
        }
        return rawValue.replaceAll("[\\s\\-./]", "").toUpperCase(Locale.ROOT);
    }

    void attachTo(Subject owner) {
        this.subject = owner;
    }

    public UUID getId() {
        return id;
    }

    public Subject getSubject() {
        return subject;
    }

    public IdentifierType getIdentifierType() {
        return identifierType;
    }

    public String getNormalizedValue() {
        return normalizedValue;
    }

    /** Null for a national document, which belongs to no operator. */
    public UUID getOwnerTenantId() {
        return ownerTenantId;
    }
}
