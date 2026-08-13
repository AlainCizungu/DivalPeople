package ai.dival.dip.modules.tix;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A person or business that may be verified through the exchange.
 *
 * <p>A subject is deliberately <em>not</em> tenant-owned. Subjects are the shared spine of the
 * exchange: several operators may hold records against the same person. Everything an operator
 * asserts about a subject — debt, disputes, fraud signals — is tenant-owned and scoped normally.
 * Reaching a subject is therefore only possible through the audited exchange services.
 */
@Entity
@Table(name = "tix_subject")
public class Subject {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private SubjectType subjectType;

    @Column(name = "full_name", nullable = false, length = 300)
    private String fullName;

    /** Normalised for matching: case-folded, accent-stripped, whitespace-collapsed. */
    @Column(name = "normalized_name", nullable = false, length = 300)
    private String normalizedName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "nationality", length = 2)
    private String nationality;

    /**
     * The subject this one turned out to be, once somebody decided they were the same.
     *
     * <p>A pointer rather than a delete. Erasing the absorbed row would erase the candidate case
     * that recorded the decision — the one action most in need of an audit trail destroying its
     * own — would stop the old identifiers resolving to anything, and would make a merge decided
     * in error unrecoverable. The row survives, keeps its history, and stops being an answer.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "merged_into_subject_id")
    private Subject mergedInto;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "subject", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SubjectIdentifier> identifiers = new ArrayList<>();

    protected Subject() {
        // for JPA
    }

    public Subject(SubjectType subjectType, String fullName, LocalDate dateOfBirth, String nationality) {
        this.subjectType = subjectType;
        this.fullName = fullName;
        this.normalizedName = normalizeName(fullName);
        this.dateOfBirth = dateOfBirth;
        this.nationality = nationality;
        this.createdAt = Instant.now();
    }

    public enum SubjectType {
        INDIVIDUAL,
        BUSINESS
    }

    /**
     * Normalises a name for matching. Kept deliberately simple and deterministic; probabilistic
     * comparison belongs in the matching service, not in the entity.
     */
    public static String normalizeName(String value) {
        if (value == null) {
            return "";
        }
        String withoutAccents = java.text.Normalizer
                .normalize(value, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return withoutAccents.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Records that this subject is the same as another.
     *
     * <p>Package-private: merging is {@code SubjectRegistryService}'s to perform, because it also
     * has to move the records and the identifiers, and a subject that points at a survivor while
     * still holding its own records is a worse state than either end of the operation.
     */
    void mergeInto(Subject survivor) {
        if (survivor == null || survivor.getId().equals(this.id)) {
            throw new IllegalArgumentException("A subject cannot be merged into itself");
        }
        this.mergedInto = survivor;
    }

    /** The subject that answers for this one, following the chain to its end. */
    public Subject surviving() {
        Subject current = this;
        // Bounded rather than while(true): a cycle here would hang a request thread, and the
        // constraint that forbids self-reference does not forbid A -> B -> A across two merges.
        for (int hops = 0; hops < 10 && current.mergedInto != null; hops++) {
            current = current.mergedInto;
        }
        return current;
    }

    public boolean isMerged() {
        return mergedInto != null;
    }

    public void addIdentifier(SubjectIdentifier identifier) {
        identifier.attachTo(this);
        identifiers.add(identifier);
    }

    public UUID getId() {
        return id;
    }

    public SubjectType getSubjectType() {
        return subjectType;
    }

    public String getFullName() {
        return fullName;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public LocalDate getDateOfBirth() {
        return dateOfBirth;
    }

    public String getNationality() {
        return nationality;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<SubjectIdentifier> getIdentifiers() {
        return List.copyOf(identifiers);
    }
}
