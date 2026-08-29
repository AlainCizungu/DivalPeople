package ai.dival.dip.modules.tenants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The mapping that decides which institution a stranger joins.
 *
 * <p>Run as {@code dip_app} so row-level security is actually enforced. The ordinary integration
 * tests connect as the schema owner and would pass whether or not the policies exist — and two of
 * the properties defended here are policy and index rather than code: that an institution cannot
 * read another's domains, and that two institutions cannot both claim one.
 *
 * <p>The lookup itself has to work with <em>no</em> tenant bound, because the person asking has
 * just registered and belongs to nobody. That is the case with the least margin for error in this
 * file: it is a deliberate hole in tenant isolation, and the test below is what says the hole is
 * exactly the shape it was meant to be.
 */
@RequiresDocker
@TestPropertySource(properties = {
        "spring.datasource.username=dip_app",
        "spring.datasource.password=dip_app",
})
class EmailDomainUnderRlsTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmailDomainService domains;

    private UUID bankA;
    private UUID bankB;
    private String domainA;

    @BeforeEach
    void setUp() {
        bankA = tenants.save(new Tenant("Domain Bank A", "dom-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        bankB = tenants.save(new Tenant("Domain Bank B", "dom-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        domainA = "bank-" + UUID.randomUUID().toString().substring(0, 8) + ".cd";
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a domain resolves to its institution with no tenant bound at all")
    void resolvesWithoutATenant() {
        domains.map(bankA, domainA);

        // Exactly the situation of somebody who has just registered: authenticated, and belonging
        // to nothing. Every other read on this platform would return empty here, and this one has
        // to work, which is why it is the first test in the file.
        TenantContext.clear();

        assertThat(domains.institutionFor(domainA)).contains(bankA);
    }

    @Test
    @DisplayName("an unmapped domain resolves to nobody rather than to somebody")
    void unmappedResolvesToNobody() {
        domains.map(bankA, domainA);
        TenantContext.clear();

        assertThat(domains.institutionFor("not-mapped-" + domainA)).isEmpty();
        assertThat(domains.institutionFor("")).isEmpty();
        assertThat(domains.institutionFor(null)).isEmpty();
    }

    @Test
    @DisplayName("an institution sees its own domains and not another's")
    void seesOnlyItsOwn() {
        domains.map(bankA, domainA);
        String domainB = "other-" + domainA;
        domains.map(bankB, domainB);

        assertThat(domains.forTenant(bankA)).containsExactly(domainA);
        assertThat(domains.forTenant(bankB)).containsExactly(domainB);
    }

    /**
     * The most consequential line in the migration, asked directly.
     *
     * <p>Without the global unique index, two institutions could both hold one domain and the
     * question "whose credit records may this person read" would be answered by whichever row the
     * database returned first. The refusal names the institution that already holds it, because the
     * only person who can see this message is the platform operator, and they cannot resolve the
     * clash without knowing.
     */
    @Test
    @DisplayName("two institutions cannot both claim one domain")
    void oneDomainOneInstitution() {
        domains.map(bankA, domainA);

        assertThatThrownBy(() -> domains.map(bankB, domainA))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Domain Bank A");
    }

    @Test
    @DisplayName("the same domain typed differently is still the same domain")
    void normalisesBeforeClashing() {
        domains.map(bankA, domainA);

        assertThatThrownBy(() -> domains.map(bankB, "  @" + domainA.toUpperCase() + ". "))
                .isInstanceOf(ConflictException.class);
    }

    /**
     * Mapping a free mail provider is not a slightly wrong configuration. It puts every person on
     * earth with an address there inside one institution's records, and it looks like the product
     * working.
     */
    @Test
    @DisplayName("a free mail provider identifies nobody and is refused")
    void refusesFreeMail() {
        assertThatThrownBy(() -> domains.map(bankA, "gmail.com"))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("invited");
    }

    @Test
    @DisplayName("something that is not a domain is refused before it reaches the database")
    void refusesRubbish() {
        assertThatThrownBy(() -> domains.map(bankA, "not a domain"))
                .isInstanceOf(PolicyRefusedException.class);
        assertThatThrownBy(() -> domains.map(bankA, "alice@" + domainA))
                .isInstanceOf(PolicyRefusedException.class);
    }

    @Test
    @DisplayName("unmapping stops new joiners and is scoped to the institution that holds it")
    void unmapping() {
        domains.map(bankA, domainA);

        // B does not hold it, so B cannot remove it — and the refusal is the same one B would get
        // for a domain that does not exist anywhere, so B cannot use this to discover A's mapping.
        assertThatThrownBy(() -> domains.unmap(bankB, domainA))
                .hasMessageContaining("not one of this institution's domains");
        assertThat(domains.institutionFor(domainA)).contains(bankA);

        domains.unmap(bankA, domainA);
        TenantContext.clear();
        assertThat(domains.institutionFor(domainA)).isEmpty();
    }

    @Test
    @DisplayName("a domain freed by one institution can be claimed by another")
    void freedDomainCanBeReclaimed() {
        domains.map(bankA, domainA);
        domains.unmap(bankA, domainA);

        domains.map(bankB, domainA);

        TenantContext.clear();
        assertThat(domains.institutionFor(domainA)).contains(bankB);
    }
}
