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
import jakarta.persistence.UniqueConstraint;
import java.util.Locale;
import java.util.UUID;

/** A single identifying attribute belonging to a {@link Subject}. */
@Entity
@Table(name = "tix_subject_identifier",
        uniqueConstraints = @UniqueConstraint(columnNames = {"identifier_type", "normalized_value"}))
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

    protected SubjectIdentifier() {
        // for JPA
    }

    public SubjectIdentifier(IdentifierType identifierType, String rawValue) {
        this.identifierType = identifierType;
        this.normalizedValue = normalizeValue(rawValue);
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
}
