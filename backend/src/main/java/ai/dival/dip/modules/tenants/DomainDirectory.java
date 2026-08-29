package ai.dival.dip.modules.tenants;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one question about domains that has to be asked across every institution: whose is this?
 *
 * <p>Its own class, and that is not tidiness. {@code REQUIRES_NEW} is delivered by a Spring proxy,
 * and a proxy is bypassed entirely when a bean calls its own method — so this living on
 * {@link EmailDomainService} alongside the code that needs it meant the internal call ran in the
 * caller's transaction, with no exchange mode, and returned nothing. Silently. The clash check that
 * stops two institutions claiming one domain would have found no clash, every time, and the first
 * anyone would have known is a constraint-violation stack trace during a customer onboarding.
 *
 * <p>Separating it makes the mistake unavailable rather than merely fixed: there is no longer a
 * same-bean call to accidentally write.
 *
 * <p>It is also the right shape for what this is. Reading across every institution's rows is a
 * deliberate hole in tenant isolation, and a hole deserves a file of its own with its reasons in
 * it, rather than a private method three screens down inside something else.
 */
@Service
public class DomainDirectory {

    private final TenantEmailDomainRepository domains;

    @PersistenceContext
    private EntityManager entityManager;

    public DomainDirectory(TenantEmailDomainRepository domains) {
        this.domains = domains;
    }

    /**
     * Which institution owns a domain, looking past tenant isolation to find out.
     *
     * <p><strong>{@code REQUIRES_NEW}, for the reason {@code NetworkService} documents at
     * length.</strong> Exchange mode is {@code SET LOCAL}: it belongs to a transaction, not to a
     * method. Joining a caller's transaction would either fail to set the flag or set it on a
     * transaction that goes on to do something else, so this always starts its own and always ends
     * it.
     *
     * <p>Read-only, and enforced rather than intended: exchange mode appears only in the policy's
     * {@code USING} clause, so this transaction could not write outside a tenant if it tried.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<UUID> institutionFor(String normalisedDomain) {
        if (normalisedDomain == null || normalisedDomain.isBlank()) {
            return Optional.empty();
        }
        enterExchangeMode();
        return domains.findByDomain(normalisedDomain).map(TenantEmailDomain::getTenantId);
    }

    /**
     * Opts this transaction into reading domains across institutions.
     *
     * <p>A third copy of the same three lines, and deliberately not a shared helper — for the
     * reason {@code NetworkService} gives about its second copy. A utility that relaxes tenant
     * isolation, importable and three characters to autocomplete, is exactly the shape of thing
     * that ends up called from a write path by somebody in a hurry.
     *
     * <p>{@code SET LOCAL}: scoped to the transaction, discarded at commit or rollback, so it
     * cannot survive onto a pooled connection and leak into the next request.
     */
    private void enterExchangeMode() {
        entityManager
                .createNativeQuery("SELECT set_config('app.exchange', 'on', true)")
                .getSingleResult();
    }
}
