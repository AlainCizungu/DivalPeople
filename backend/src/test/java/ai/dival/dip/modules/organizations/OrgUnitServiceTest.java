package ai.dival.dip.modules.organizations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@RequiresDocker
class OrgUnitServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private OrgUnitService service;
    @Autowired
    private OrgUnitRepository units;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = tenants.save(new Tenant("Org A", "org-a-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        tenantB = tenants.save(new Tenant("Org B", "org-b-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private OrgUnit root(String code) {
        return service.create(null, OrgUnitType.LEGAL_ENTITY, code, "Root " + code, null);
    }

    @Test
    @DisplayName("a root legal entity has no parent and sits at depth zero")
    void createsRoot() {
        OrgUnit created = root("HQ");

        assertThat(created.isRoot()).isTrue();
        assertThat(created.getDepth()).isZero();
        assertThat(created.getCode()).isEqualTo("HQ");
        assertThat(created.isActive()).isTrue();
    }

    @Test
    @DisplayName("only a legal entity may sit at the root")
    void refusesNonLegalEntityRoot() {
        assertThatThrownBy(() ->
                service.create(null, OrgUnitType.DEPARTMENT, "DEPT", "Orphan", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("codes are normalised and unique within a tenant")
    void normalisesAndEnforcesUniqueCodes() {
        root("HQ");

        assertThatThrownBy(() -> root("  hq  "))
                .isInstanceOf(OrgUnitService.CodeAlreadyUsedException.class);

        // The same code in another tenant is fine — they are separate organisations.
        TenantContext.runAs(tenantB, () -> root("HQ"));
    }

    @Test
    @DisplayName("depth follows the parent chain")
    void depthFollowsParent() {
        OrgUnit hq = root("HQ");
        OrgUnit branch = service.create(hq.getId(), OrgUnitType.BRANCH, "KIN", "Kinshasa", null);
        OrgUnit dept = service.create(branch.getId(), OrgUnitType.DEPARTMENT, "OPS", "Ops", null);

        assertThat(branch.getDepth()).isEqualTo(1);
        assertThat(dept.getDepth()).isEqualTo(2);
    }

    @Test
    @DisplayName("descendants are found at any depth")
    void findsDescendants() {
        OrgUnit hq = root("HQ");
        OrgUnit branch = service.create(hq.getId(), OrgUnitType.BRANCH, "KIN", "Kinshasa", null);
        OrgUnit dept = service.create(branch.getId(), OrgUnitType.DEPARTMENT, "OPS", "Ops", null);
        units.flush();

        List<UUID> descendants = units.findDescendantIds(tenantA, hq.getId());

        assertThat(descendants).containsExactlyInAnyOrder(branch.getId(), dept.getId());
    }

    @Test
    @DisplayName("a unit cannot be moved beneath its own descendant")
    void refusesCycle() {
        OrgUnit hq = root("HQ");
        OrgUnit branch = service.create(hq.getId(), OrgUnitType.BRANCH, "KIN", "Kinshasa", null);
        units.flush();

        assertThatThrownBy(() -> service.move(hq.getId(), branch.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> service.move(hq.getId(), hq.getId(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("moving a unit rewrites the depth of everything beneath it")
    void moveRewritesSubtreeDepth() {
        OrgUnit hq = root("HQ");
        OrgUnit branch = service.create(hq.getId(), OrgUnitType.BRANCH, "KIN", "Kinshasa", null);
        OrgUnit dept = service.create(branch.getId(), OrgUnitType.DEPARTMENT, "OPS", "Ops", null);
        OrgUnit second = root("HQ2");
        units.flush();

        service.move(branch.getId(), second.getId(), null);

        assertThat(branch.getDepth()).isEqualTo(1);
        assertThat(dept.getDepth()).isEqualTo(2);
    }

    @Test
    @DisplayName("deactivating a unit deactivates its whole branch")
    void deactivateCascades() {
        OrgUnit hq = root("HQ");
        OrgUnit branch = service.create(hq.getId(), OrgUnitType.BRANCH, "KIN", "Kinshasa", null);
        OrgUnit dept = service.create(branch.getId(), OrgUnitType.DEPARTMENT, "OPS", "Ops", null);
        units.flush();

        service.deactivate(branch.getId(), null);

        assertThat(branch.isActive()).isFalse();
        assertThat(dept.isActive()).isFalse();
        assertThat(hq.isActive()).isTrue();
    }

    @Test
    @DisplayName("a unit cannot be reactivated while its parent is inactive")
    void refusesReactivationUnderInactiveParent() {
        OrgUnit hq = root("HQ");
        OrgUnit branch = service.create(hq.getId(), OrgUnitType.BRANCH, "KIN", "Kinshasa", null);
        units.flush();

        service.deactivate(hq.getId(), null);

        assertThatThrownBy(() -> service.activate(branch.getId(), null))
                .isInstanceOf(ai.dival.dip.common.error.ConflictException.class);
    }

    @Test
    @DisplayName("one tenant's tree is invisible to another")
    void treesAreTenantScoped() {
        root("HQ");
        TenantContext.runAs(tenantB, () -> root("HQ"));

        assertThat(service.list()).hasSize(1);
        assertThat(TenantContext.runAsResult(tenantB, () -> service.list())).hasSize(1);

        UUID foreign = TenantContext.runAsResult(tenantB,
                () -> service.list().get(0).getId());
        assertThatThrownBy(() -> service.get(foreign))
                .isInstanceOf(OrgUnitService.OrgUnitNotFoundException.class);
    }
}
