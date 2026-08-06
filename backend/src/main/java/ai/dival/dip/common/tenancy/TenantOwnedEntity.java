package ai.dival.dip.common.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Base class for every row that belongs to a tenant.
 *
 * <p>The tenant identifier is populated from {@link TenantContext} on persist and must never be
 * set from client input. Each concrete table additionally carries a row-level security policy,
 * declared in the same migration that creates it.
 */
@MappedSuperclass
public abstract class TenantOwnedEntity {

    @Id
    @GeneratedValue
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    void applyTenantAndTimestamp() {
        if (tenantId == null) {
            tenantId = TenantContext.require();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }
}
