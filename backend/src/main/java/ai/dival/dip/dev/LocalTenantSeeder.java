package ai.dival.dip.dev;

import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantService;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Creates the two demo tenants used by local development.
 *
 * <p>Their IDs are fixed and must match the {@code tenant_id} attributes of the operator users in
 * {@code infra/keycloak/realm-dip.json}: a token carrying a tenant that has no matching row would
 * authenticate happily and then fail on the first write, which is a confusing way to start.
 *
 * <p>Goes through {@link TenantService} rather than writing rows directly, so seeding cannot
 * drift away from the validation and audit that the API applies. Restricted to the {@code local}
 * profile; this never runs in a deployed environment.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-tenants", havingValue = "true")
@Order(10) // before LocalTixSeeder, which needs these tenants to exist
public class LocalTenantSeeder implements ApplicationRunner {

    public static final UUID OPERATOR_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID OPERATOR_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final TenantService tenants;

    public LocalTenantSeeder(TenantService tenants) {
        this.tenants = tenants;
    }

    @Override
    public void run(ApplicationArguments args) {
        // provision() is idempotent, so a restart against an existing database is a no-op.
        tenants.provision(OPERATOR_A, "Operator A (local)", "operator-a",
                Tenant.Edition.TELECOM, "fr", null);
        tenants.provision(OPERATOR_B, "Operator B (local)", "operator-b",
                Tenant.Edition.TELECOM, "fr", null);
    }
}
