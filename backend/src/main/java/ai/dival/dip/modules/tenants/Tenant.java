package ai.dival.dip.modules.tenants;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer organisation. The tenant table is the one place a tenant row is not itself
 * tenant-scoped, so it does not extend {@code TenantOwnedEntity}.
 */
@Entity
@Table(name = "tenant")
public class Tenant {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Stable machine-readable key used in configuration and support tooling. */
    @Column(name = "slug", nullable = false, unique = true, length = 100)
    private String slug;

    @Enumerated(EnumType.STRING)
    @Column(name = "edition", nullable = false, length = 40)
    private Edition edition;

    @Column(name = "default_locale", nullable = false, length = 10)
    private String defaultLocale;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Tenant() {
        // for JPA
    }

    public Tenant(String name, String slug, Edition edition, String defaultLocale) {
        this.name = name;
        this.slug = slug;
        this.edition = edition;
        this.defaultLocale = defaultLocale;
        this.active = true;
        this.createdAt = Instant.now();
    }

    public enum Edition {
        BANKING,
        NGO,
        TELECOM,
        GOVERNMENT,
        HEALTHCARE,
        ENTERPRISE
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public Edition getEdition() {
        return edition;
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** TIX is available to telecom-edition tenants only. */
    public boolean supportsTix() {
        return edition == Edition.TELECOM;
    }
}
