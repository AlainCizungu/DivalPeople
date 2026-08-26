package ai.dival.dip.modules.tenants;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating and retiring customer organizations.
 *
 * <p>Deliberately the only way a tenant comes into existence. Local seeding goes through
 * {@link #provision} rather than writing rows directly, so validation, uniqueness and audit
 * cannot be skipped by whichever path happens to be convenient.
 *
 * <p>Everything here is cross-tenant by nature and is restricted to platform administrators at
 * the API boundary. Note that a platform administrator has no tenant of their own, so nothing in
 * this class may depend on a bound tenant context.
 */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    /** Lowercase words separated by single hyphens: safe in URLs, subdomains and config keys. */
    private static final Pattern SLUG = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");
    private static final int SLUG_MIN = 2;
    private static final int SLUG_MAX = 100;

    private static final List<String> SUPPORTED_LOCALES = List.of("en", "fr");

    private final TenantRepository tenants;
    private final AuditService audit;

    public TenantService(TenantRepository tenants, AuditService audit) {
        this.tenants = tenants;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Tenant> list() {
        return tenants.findAll();
    }

    @Transactional(readOnly = true)
    public Tenant get(UUID id) {
        return tenants.findById(id).orElseThrow(() -> new TenantNotFoundException(id));
    }

    /**
     * What an operator calls itself, for showing that operator its own name.
     *
     * <p>Deliberately not routed through {@code TenantDirectory}, which exists to be the single
     * chokepoint for naming <em>other</em> operators inside the exchange. Telling somebody the
     * name of the organisation they signed into is not a disclosure in that sense at all, and
     * putting it through the same door would add a caller to the list of "places an operator can
     * be named" that has nothing to do with the rule that list exists to police.
     *
     * <p>Empty rather than throwing. The header calls this on every page load, and a tenant row
     * that has been tidied up under a live session should degrade to a bar without an
     * organisation name — not to a five hundred on the screen somebody lands on after signing in.
     */
    @Transactional(readOnly = true)
    public Optional<String> nameOf(UUID id) {
        return tenants.findById(id).map(Tenant::getName);
    }

    /** Creates a tenant with a generated identifier. */
    @Transactional
    public Tenant create(String name, String slug, Tenant.Edition edition, String defaultLocale,
                         UUID actorId) {
        return provision(UUID.randomUUID(), name, slug, edition, defaultLocale, actorId);
    }

    /**
     * Creates a tenant with a specific identifier, or returns the existing one unchanged.
     *
     * <p>Idempotent so that bootstrapping and migration can honour an identifier decided
     * elsewhere — one already issued to the identity provider, say — and can be re-run safely.
     */
    @Transactional
    public Tenant provision(UUID id, String name, String slug, Tenant.Edition edition,
                            String defaultLocale, UUID actorId) {
        var existing = tenants.findById(id);
        if (existing.isPresent()) {
            return existing.get();
        }

        String normalizedSlug = normalizeSlug(slug);
        validate(name, normalizedSlug, defaultLocale);

        if (tenants.findBySlug(normalizedSlug).isPresent()) {
            throw new SlugAlreadyUsedException(normalizedSlug);
        }

        Tenant tenant = tenants.save(
                new Tenant(id, name.trim(), normalizedSlug, edition, defaultLocale));

        audit.recordSuccess("TENANT_CREATED", "Tenant", tenant.getId().toString(), actorId);
        log.info("Provisioned tenant '{}' ({})", tenant.getSlug(), tenant.getId());
        return tenant;
    }

    /**
     * Retires a tenant. The row is kept: its users, audit trail and declared records all still
     * reference it, and deleting it would leave that history dangling.
     */
    @Transactional
    public Tenant deactivate(UUID id, UUID actorId) {
        Tenant tenant = get(id);
        tenant.deactivate();
        audit.recordSuccess("TENANT_DEACTIVATED", "Tenant", id.toString(), actorId);
        return tenant;
    }

    @Transactional
    public Tenant activate(UUID id, UUID actorId) {
        Tenant tenant = get(id);
        tenant.activate();
        audit.recordSuccess("TENANT_ACTIVATED", "Tenant", id.toString(), actorId);
        return tenant;
    }

    /** Accepts what a human would type and stores what the system needs. */
    static String normalizeSlug(String slug) {
        if (slug == null) {
            return "";
        }
        return slug.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    private void validate(String name, String slug, String defaultLocale) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tenant name is required");
        }
        if (slug.length() < SLUG_MIN || slug.length() > SLUG_MAX || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException(
                    "Slug must be lowercase letters, digits and single hyphens, "
                            + SLUG_MIN + "-" + SLUG_MAX + " characters");
        }
        if (!SUPPORTED_LOCALES.contains(defaultLocale)) {
            throw new IllegalArgumentException(
                    "Default locale must be one of " + SUPPORTED_LOCALES);
        }
    }

    public static class TenantNotFoundException extends ResourceNotFoundException {
        public TenantNotFoundException(UUID id) {
            super("Tenant not found: " + id);
        }
    }

    public static class SlugAlreadyUsedException extends ConflictException {
        public SlugAlreadyUsedException(String slug) {
            super("Slug already in use: " + slug);
        }
    }
}
