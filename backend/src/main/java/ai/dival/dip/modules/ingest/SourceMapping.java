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

    /**
     * Null when the delivery carries no identifier and identity comes from the name instead.
     *
     * <p>Nullable together with {@link #identifierType}, and V27 holds them to that: a column with
     * no type cannot be applied, and a type with no column is a setting that does nothing. Either
     * would be a mapping that looks complete on the screen and fails at derivation.
     */
    @Column(name = "identifier_column", updatable = false, length = 200)
    private String identifierColumn;

    @Column(name = "identifier_type", updatable = false, length = 30)
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

    /**
     * Where the sector, the city and the street live in this operator's file, if anywhere.
     *
     * <p>All three optional. The two real deliveries carry none of them, so requiring them would
     * make every existing source undefinable; an operator who adopts the published template names
     * them and the resolution queue gains three signals that have read <em>never available</em>
     * since it was built.
     */
    @Column(name = "sector_column", updatable = false, length = 200)
    private String sectorColumn;

    @Column(name = "city_column", updatable = false, length = 200)
    private String cityColumn;

    @Column(name = "address_column", updatable = false, length = 200)
    private String addressColumn;

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

    /**
     * A mapping that names no profile columns, which is every mapping written before August 2026.
     *
     * <p>Kept as an overload rather than making the fourteen existing call sites pass three nulls,
     * for the same reason {@code DeclarationRequest} keeps one: three trailing nulls read as an
     * oversight, and a shorter signature says "this delivery carries none of that" out loud.
     */
    public SourceMapping(SourceDataset dataSource, String identifierColumn, String identifierType,
                         String nameColumn, String amountColumn, String currency,
                         String serviceCategory, String subjectType, int versionNumber,
                         UUID definedBy) {
        this(dataSource, identifierColumn, identifierType, nameColumn, amountColumn, currency,
                serviceCategory, subjectType, versionNumber, definedBy, null, null, null);
    }

    public SourceMapping(SourceDataset dataSource, String identifierColumn, String identifierType,
                         String nameColumn, String amountColumn, String currency,
                         String serviceCategory, String subjectType, int versionNumber,
                         UUID definedBy, String sectorColumn, String cityColumn,
                         String addressColumn) {
        this.tenantId = TenantContext.require();
        this.dataSource = dataSource;
        // Both or neither. Blank is treated as absent because a form submits an empty string
        // where a caller would pass null, and the two mean the same thing to an operator who
        // cleared the box.
        boolean hasColumn = identifierColumn != null && !identifierColumn.isBlank();
        boolean hasType = identifierType != null && !identifierType.isBlank();
        if (hasColumn != hasType) {
            throw new PolicyRefusedException(
                    "Say both which column holds the identifier and what kind of identifier it "
                            + "is, or neither — in which case the name column identifies the "
                            + "subject, inside this organisation only.");
        }
        this.identifierColumn = hasColumn ? identifierColumn.trim() : null;
        this.identifierType = hasType ? identifierType.trim() : null;
        this.nameColumn = required(nameColumn, "name column");
        this.amountColumn = required(amountColumn, "amount column");
        this.currency = required(currency, "currency").toUpperCase(java.util.Locale.ROOT);
        this.serviceCategory = required(serviceCategory, "service category");
        this.subjectType = required(subjectType, "subject type");
        this.sectorColumn = optional(sectorColumn);
        this.cityColumn = optional(cityColumn);
        this.addressColumn = optional(addressColumn);
        this.versionNumber = versionNumber;
        this.definedBy = definedBy;
        this.definedAt = Instant.now();
        this.createdAt = this.definedAt;

        // Checked here and again by V25. A mapping naming one column as both the identifier and
        // the amount would derive records that look entirely plausible and are nonsense — and
        // finding that out from a constraint name at flush time helps nobody.
        if (this.nameColumn.equals(this.amountColumn)
                || this.nameColumn.equals(this.identifierColumn)
                || this.amountColumn.equals(this.identifierColumn)) {
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
     * Whether this mapping describes a delivery that identifies nobody by number.
     *
     * <p>Asked rather than inferred from a null, so the question reads the way the operator
     * answered it on the form.
     */
    public boolean identifiesByName() {
        return identifierColumn == null;
    }

    /**
     * The values this mapping defines, read out of a delivered row.
     *
     * <p>Public and specific, rather than a general "read column X". A caller cannot ask this
     * mapping for a column the mapping does not define, which is the point: the deriver in
     * {@code tix} consumes these across a module boundary, and the narrower the surface it reaches
     * through the less there is to get wrong.
     *
     * <p>They were briefly a single package-private {@code cell(row, column)}, written when the
     * derivation was expected to live in this package. It does not — {@code tix} decides what
     * enters the registry — and the compiler said so.
     *
     * @throws IllegalStateException when this mapping identifies by name and has no such column.
     *         A caller that reached here without asking {@link #identifiesByName()} has a bug, and
     *         a null return would push it somewhere harder to find
     */
    public String identifierIn(Map<String, String> row) {
        if (identifierColumn == null) {
            throw new IllegalStateException(
                    "This mapping identifies subjects by name and names no identifier column.");
        }
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

    public String getSectorColumn() {
        return sectorColumn;
    }

    public String getCityColumn() {
        return cityColumn;
    }

    public String getAddressColumn() {
        return addressColumn;
    }

    /**
     * What this row says about the company, or null when the mapping names no such columns.
     *
     * <p>Read through {@link #cell} like everything else, so a mapping naming a column the delivery
     * lacks is refused here too — for the same reason it is refused for the identifier. A profile
     * column that silently produced blanks would look exactly like a file that carries no sector,
     * and the operator would never learn their header had changed.
     */
    public Profile profileFrom(Map<String, String> row) {
        if (sectorColumn == null && cityColumn == null && addressColumn == null) {
            return null;
        }
        return new Profile(
                sectorColumn == null ? null : cell(row, sectorColumn),
                cityColumn == null ? null : cell(row, cityColumn),
                addressColumn == null ? null : cell(row, addressColumn));
    }

    /**
     * This module's own triple, not the telecom module's.
     *
     * <p>{@code DeclarationRequest.Profile} would have done and would have made {@code ingest}
     * import {@code tix} while {@code tix} already imports {@code ingest} — a cycle between two
     * modules whose whole arrangement is that one reads files and the other decides what they mean.
     * The architecture check does not forbid it, which is not the same as it being right.
     * {@code ImportDeriver} converts, which is one line in the module that already depends on both.
     */
    public record Profile(String sector, String city, String streetAddress) {
    }

    /** Blank is absent: a form submits an empty string where a caller would pass null. */
    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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
