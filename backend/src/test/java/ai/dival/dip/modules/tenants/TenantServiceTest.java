package ai.dival.dip.modules.tenants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@RequiresDocker
class TenantServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantRepository tenants;

    private String uniqueSlug() {
        return "acme-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("a created tenant is active and persisted")
    void createsTenant() {
        String slug = uniqueSlug();

        Tenant created = tenantService.create(
                "Acme Telecom", slug, Tenant.Edition.TELECOM, "fr", null);

        assertThat(created.getId()).isNotNull();
        assertThat(created.isActive()).isTrue();
        assertThat(created.getSlug()).isEqualTo(slug);
        assertThat(tenants.findBySlug(slug)).isPresent();
    }

    @Test
    @DisplayName("slugs are normalised before they are stored")
    void normalisesSlug() {
        String slug = uniqueSlug();

        Tenant created = tenantService.create(
                "  Acme Telecom  ", "  " + slug.toUpperCase() + "  ",
                Tenant.Edition.TELECOM, "en", null);

        assertThat(created.getSlug()).isEqualTo(slug);
        assertThat(created.getName()).isEqualTo("Acme Telecom");
    }

    @Test
    @DisplayName("a whitespace slug becomes hyphenated rather than rejected")
    void convertsSpacesToHyphens() {
        assertThat(TenantService.normalizeSlug("  Acme   Telecom ")).isEqualTo("acme-telecom");
    }

    @Test
    @DisplayName("a duplicate slug is refused")
    void refusesDuplicateSlug() {
        String slug = uniqueSlug();
        tenantService.create("First", slug, Tenant.Edition.TELECOM, "fr", null);

        assertThatThrownBy(() ->
                tenantService.create("Second", slug, Tenant.Edition.BANKING, "en", null))
                .isInstanceOf(TenantService.SlugAlreadyUsedException.class);
    }

    @Test
    @DisplayName("an invalid slug or locale is refused")
    void validatesInput() {
        assertThatThrownBy(() ->
                tenantService.create("Bad", "Not_A_Slug!", Tenant.Edition.TELECOM, "fr", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                tenantService.create("Bad", uniqueSlug(), Tenant.Edition.TELECOM, "de", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                tenantService.create("  ", uniqueSlug(), Tenant.Edition.TELECOM, "fr", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("provisioning a known id twice returns the existing tenant")
    void provisioningIsIdempotent() {
        UUID id = UUID.randomUUID();
        String slug = uniqueSlug();

        Tenant first = tenantService.provision(
                id, "Fixed Id", slug, Tenant.Edition.TELECOM, "fr", null);
        Tenant second = tenantService.provision(
                id, "Different Name", uniqueSlug(), Tenant.Edition.NGO, "en", null);

        assertThat(second.getId()).isEqualTo(first.getId());
        // The second call must not overwrite: it is a no-op, not an update.
        assertThat(second.getName()).isEqualTo("Fixed Id");
        assertThat(second.getSlug()).isEqualTo(slug);
    }

    @Test
    @DisplayName("deactivating keeps the row so history stays resolvable")
    void deactivateKeepsTheRow() {
        Tenant created = tenantService.create(
                "Retiring", uniqueSlug(), Tenant.Edition.ENTERPRISE, "en", null);

        Tenant deactivated = tenantService.deactivate(created.getId(), null);

        assertThat(deactivated.isActive()).isFalse();
        assertThat(tenants.findById(created.getId())).isPresent();

        assertThat(tenantService.activate(created.getId(), null).isActive()).isTrue();
    }

    @Test
    @DisplayName("an unknown tenant is reported as not found")
    void unknownTenant() {
        assertThatThrownBy(() -> tenantService.get(UUID.randomUUID()))
                .isInstanceOf(TenantService.TenantNotFoundException.class);
    }
}
