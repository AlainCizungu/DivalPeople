package ai.dival.dip.modules.tenants;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The mapping from an email domain to an institution.
 *
 * <p>This is the smallest table on the platform and one of the two or three most consequential. It
 * decides, for every person who ever registers, whose credit records they are eventually able to
 * read. A wrong row here is not a wrong row: it is an institution's book opened to somebody at
 * another company, and it would look like the product working.
 *
 * <p>So it is written by the platform operator alone. A tenant administrator claiming their own
 * domain sounds harmless and is not — nothing stops them claiming a competitor's, and the reward
 * for doing so is every future joiner from that competitor landing inside their own book. Proving
 * control of a domain properly means DNS, and until institutions onboard themselves without the
 * operator, one row added when a contract is signed is both less work and a stronger guarantee.
 *
 * <p><strong>Nothing here is {@code @Transactional}, and that is deliberate.</strong>
 * {@code TenantAwareDataSource} binds the tenant as each connection is handed out, so the context
 * has to be set <em>before</em> a transaction begins. A method annotated {@code @Transactional}
 * starts its transaction before its body runs, which would take a connection bound to no tenant and
 * then fail every policy check inside {@code TenantContext.runAs}. Each step below is a single
 * statement in its own transaction, opened after the binding — which costs atomicity across the
 * check and the write, and the worst that can happen there is a unique-index violation the operator
 * reads and retries.
 */
@Service
public class EmailDomainService {

    public static final String DOMAIN_MAPPED = "TENANT_DOMAIN_MAPPED";
    public static final String DOMAIN_UNMAPPED = "TENANT_DOMAIN_UNMAPPED";

    private final TenantEmailDomainRepository domains;
    private final DomainDirectory directory;
    private final TenantRepository tenants;
    private final AuditService audit;

    public EmailDomainService(TenantEmailDomainRepository domains, DomainDirectory directory,
                              TenantRepository tenants, AuditService audit) {
        this.domains = domains;
        this.directory = directory;
        this.tenants = tenants;
        this.audit = audit;
    }

    /**
     * Which institution a domain belongs to, across all of them.
     *
     * <p>Delegates rather than doing the work, because the work needs its own transaction and a
     * bean cannot start one by calling itself. See {@link DomainDirectory}, where the version that
     * lived here was silently returning nothing.
     */
    public Optional<UUID> institutionFor(String normalisedDomain) {
        return directory.institutionFor(normalisedDomain);
    }

    /** An institution's own domains, for the screen that shows them. */
    public List<String> forTenant(UUID tenantId) {
        return TenantContext.runAsResult(tenantId,
                () -> domains.findByTenantIdOrderByDomain(tenantId).stream()
                        .map(TenantEmailDomain::getDomain)
                        .toList());
    }

    /**
     * Maps a domain to an institution.
     *
     * <p>The write is bound to the target tenant rather than the caller's, because the caller is a
     * platform administrator and has no tenant of their own — and because the row's policy insists
     * on {@code tenant_id = app_current_tenant()} for writes. Binding it explicitly is what lets
     * that check stay strict instead of being widened to accommodate an operator.
     *
     * <p>The clash is checked here as well as by the unique index. Not because the index needs
     * help, but because reaching it produces a constraint-violation stack trace, and the person
     * reading it is onboarding a customer and needs to know <em>which</em> institution already
     * holds the domain — something this can say and a database error cannot.
     */
    public String map(UUID tenantId, String rawDomain) {
        tenants.findById(tenantId).orElseThrow(
                () -> new ResourceNotFoundException("No institution with id " + tenantId));

        String domain = JoiningRules.normaliseDomain(rawDomain).orElseThrow(
                () -> new PolicyRefusedException(
                        "\"" + rawDomain + "\" is not a domain. Give the part after the at-sign of "
                                + "a work address, such as vodacom.cd."));

        if (!JoiningRules.canIdentifyAnInstitution(domain)) {
            throw new PolicyRefusedException(
                    domain + " is a free mail provider, so it identifies nobody. Mapping it would "
                            + "put every person on earth who has an address there inside this "
                            + "institution's records. Staff who use a free address have to be "
                            + "invited individually.");
        }

        // Across institutions, which is the only way to see a clash — and the reason this call has
        // to go through another bean rather than a private method.
        Optional<UUID> holder = directory.institutionFor(domain);
        if (holder.isPresent()) {
            String name = tenants.findById(holder.get()).map(Tenant::getName).orElse(null);
            throw new ConflictException(name == null
                    ? domain + " is already mapped to another institution."
                    : domain + " is already mapped to " + name + ".");
        }

        TenantContext.runAs(tenantId, () -> domains.save(new TenantEmailDomain(domain)));

        audit.record(DOMAIN_MAPPED, "Tenant", tenantId.toString(), AuditService.OUTCOME_SUCCESS,
                null, "Domain: " + domain);
        return domain;
    }

    /**
     * Stops a domain identifying an institution.
     *
     * <p>Nobody already inside loses anything. The mapping decides who may join, and a person who
     * joined last year has their institution recorded against their account at the identity
     * provider — this row is not what keeps them there. Removing it is how an institution that
     * leaves the network stops acquiring new people; suspending the ones it has is a separate and
     * deliberate act.
     */
    public void unmap(UUID tenantId, String rawDomain) {
        String domain = JoiningRules.normaliseDomain(rawDomain).orElse(rawDomain);

        TenantEmailDomain held = TenantContext.runAsResult(tenantId,
                () -> domains.findByTenantIdOrderByDomain(tenantId).stream()
                        .filter(row -> row.getDomain().equals(domain))
                        .findFirst()
                        .orElse(null));

        if (held == null) {
            // Deliberately the same refusal whether the domain is mapped elsewhere or nowhere at
            // all. This endpoint takes a tenant, so an operator could otherwise walk the mapping of
            // every institution by asking to unmap guesses from their own.
            throw new ResourceNotFoundException(
                    domain + " is not one of this institution's domains.");
        }

        TenantContext.runAs(tenantId, () -> domains.delete(held));

        audit.record(DOMAIN_UNMAPPED, "Tenant", tenantId.toString(), AuditService.OUTCOME_SUCCESS,
                null, "Domain: " + domain);
    }
}
