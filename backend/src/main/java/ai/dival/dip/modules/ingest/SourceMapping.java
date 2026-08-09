package ai.dival.dip.modules.ingest;

import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * What the columns of one operator's export mean, as that operator says they do.
 *
 * <p><strong>DIP does not decide which column is the amount.</strong> It cannot. The profiled
 * Vodacom export has {@code Balance} and nine aging buckets, all numeric, and only somebody at the
 * operator knows the buckets sum to the balance rather than the reverse. What the platform can do
 * is put the evidence in front of them — which columns are unique, which are constant, which are
 * entirely numeric — and record the choice made while looking at it.
 *
 * <p>Superseded, never edited. A published delivery was derived through whichever mapping was
 * current at the time, and rewriting one in place would leave that batch untraceable to the rules
 * that produced it. Immutable raw rows would then be provenance pointing at nothing.
 *
 * <p>Column names are stored exactly as the header spells them, without normalisation. The header
 * is what the operator sees in their own spreadsheet, and a mapping naming something they cannot
 * find is a mapping they cannot check.
 */
@Entity
@Table(name = "source_mapping")
public class SourceMapping {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_source_id", nullable = false, updatable = false)
    private SourceDataset dataSource;

    @Column(name = "identifier_column", nullable = false, updatable = false, length = 200)
    private String identifierColumn;

    @Column(name = "identifier_type", nullable = false, updatable = false, length = 30)
    private String identifierType;

    @Column(name = "name_column", nullable = false, updatable = false, length = 200)
    private String nameColumn;

    @Column(name = "amount_column", nullable = false, updatable = false, length = 200)
    private String amountColumn;

    @Column(name = "currency", nullable = false, updatable = false, length = 3)
    private String currency;

    @Column(name = "service_category", nullable = false, updatable = false, length = 60)
    private String serviceCategory;

    @Column(name = "subject_type", nullable = false, updatable = false, length = 20)
    private String subjectType;

    @Column(name = "version_number", nullable = false, updatable = false)
    private int versionNumber;

    @Column(name = "defined_by", updatable = false)
    private UUID definedBy;

    @Column(name = "defined_at", nullable = false, updatable = false)
    private Instant definedAt;

    /** The one mutable field, and the only reason UPDATE is granted on this table. */
    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SourceMapping() {
        // for JPA
    }

    public SourceMapping(SourceDataset dataSource, String identifierColumn, String identifierType,
                         String nameColumn, String amountColumn, String currency,
                         String serviceCategory, String subjectType, int versionNumber,
                         UUID definedBy) {
        this.tenantId = TenantContext.require();
        this.dataSource = dataSource;
        this.identifierColumn = required(identifierColumn, "identifier column");
        this.identifierType = required(identifierType, "identifier type");
        this.nameColumn = required(nameColumn, "name column");
        this.amountColumn = required(amountColumn, "amount column");
        this.currency = required(currency, "currency").toUpperCase(java.util.Locale.ROOT);
        this.serviceCategory = required(serviceCategory, "service category");
        this.subjectType = required(subjectType, "subject type");
        this.versionNumber = versionNumber;
        this.definedBy = definedBy;
        this.definedAt = Instant.now();
        this.createdAt = this.definedAt;

        // Checked here and again by V25. A mapping naming one column as both the identifier and
        // the amount would derive records that look entirely plausible and are nonsense — and
        // finding that out from a constraint name at flush time helps nobody.
        if (this.identifierColumn.equals(this.nameColumn)
                || this.identifierColumn.equals(this.amountColumn)
                || this.nameColumn.equals(this.amountColumn)) {
            throw new PolicyRefusedException(
                    "The identifier, name and amount must be three different columns. "
                            + "One column cannot be all of them.");
        }
    }

    private static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new PolicyRefusedException("A mapping needs a " + what + ".");
        }
        return value.trim();
    }

    /**
     * The three values this mapping defines, read out of a delivered row.
     *
     * <p>Public and specific, rather than a general "read column X". A caller cannot ask this
     * mapping for a column the mapping does not define, which is the point: the deriver in
     * {@code tix} consumes these across a module boundary, and the narrower the surface it reaches
     * through the less there is to get wrong.
     *
     * <p>They were briefly a single package-private {@code cell(row, column)}, written when the
     * derivation was expected to live in this package. It does not — {@code tix} decides what
     * enters the registry — and the compiler said so.
     */
    public String identifierIn(Map<String, String> row) {
        return cell(row, identifierColumn);
    }

    public String nameIn(Map<String, String> row) {
        return cell(row, nameColumn);
    }

    /** As it appears in the file. Parsing it is the caller's problem, and its error message. */
    public String rawAmountIn(Map<String, String> row) {
        return cell(row, amountColumn);
    }

    /**
     * Reads one cell of a row through this mapping.
     *
     * <p>Refuses a column the delivery does not have, rather than returning empty. A mapping
     * written against last quarter's header and applied to a file where somebody renamed a column
     * would otherwise derive every record with a blank identifier — thousands of rows, silently
     * wrong, all of them refused later for a reason that says nothing about the cause.
     */
    private String cell(Map<String, String> row, String column) {
        if (!row.containsKey(column)) {
            throw new PolicyRefusedException(
                    "This delivery has no column named \"" + column + "\". The mapping for this "
                            + "source expects it — either the export changed, or the mapping "
                            + "needs redefining against the new header.");
        }
        String value = row.get(column);
        return value == null ? "" : value.trim();
    }

    /** Stamped when a newer mapping replaces this one. Nothing else about a mapping ever changes. */
    void supersede() {
        if (supersededAt != null) {
            throw new PolicyRefusedException("This mapping has already been superseded.");
        }
        this.supersededAt = Instant.now();
    }

    public boolean isCurrent() {
        return supersededAt == null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public SourceDataset getDataSource() {
        return dataSource;
    }

    public String getIdentifierColumn() {
        return identifierColumn;
    }

    public String getIdentifierType() {
        return identifierType;
    }

    public String getNameColumn() {
        return nameColumn;
    }

    public String getAmountColumn() {
        return amountColumn;
    }

    public String getCurrency() {
        return currency;
    }

    public String getServiceCategory() {
        return serviceCategory;
    }

    public String getSubjectType() {
        return subjectType;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public UUID getDefinedBy() {
        return definedBy;
    }

    public Instant getDefinedAt() {
        return definedAt;
    }

    public Instant getSupersededAt() {
        return supersededAt;
    }
}
