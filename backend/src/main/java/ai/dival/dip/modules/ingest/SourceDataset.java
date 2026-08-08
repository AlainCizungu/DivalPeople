package ai.dival.dip.modules.ingest;

import ai.dival.dip.common.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * A dataset an operator contributes: "Vodacom postpaid receivables, monthly export".
 *
 * <p>Named {@code SourceDataset} while the table is {@code data_source}, which is a deliberate
 * divergence from DATABASE_DESIGN.md and the only one in this migration. A JPA entity called
 * {@code DataSource} sitting in a Spring application is a trap: {@code javax.sql.DataSource} is
 * everywhere in this codebase's tenancy configuration, the two would differ only by import, and
 * the failure mode is a constructor parameter silently resolving to the wrong type. The table
 * keeps the documented name; the class avoids the collision.
 *
 * <p>Tenant-owned. One operator's source definitions are no business of another's, and the
 * row-level security policy in V20 is what enforces that rather than a predicate somebody
 * remembers to add.
 */
@Entity
@Table(name = "data_source")
public class SourceDataset {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Stable across deliveries, so successive months of the same export share one source. */
    @Column(name = "code", nullable = false, length = 60)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 30)
    private SourceKind kind;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SourceDataset() {
        // for JPA
    }

    public SourceDataset(String code, String name, SourceKind kind) {
        // Bound at construction rather than in @PrePersist, so an attempt to build one outside a
        // tenant context fails where the mistake is rather than at flush time, several frames
        // later, with a constraint violation that names a column instead of a cause.
        this.tenantId = TenantContext.require();
        this.code = normalizeCode(code);
        this.name = name;
        this.kind = kind;
        this.active = true;
        this.createdAt = Instant.now();
    }

    /** Upper-cased and trimmed, so "vodacom-postpaid" and "VODACOM_POSTPAID " do not become two. */
    public static String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public void deactivate() {
        this.active = false;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public SourceKind getKind() {
        return kind;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
