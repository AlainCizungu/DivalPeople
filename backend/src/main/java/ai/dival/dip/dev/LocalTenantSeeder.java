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
 * drift away from the validation and audit that the API applies.
 *
 * <p><strong>Local and demo profiles only.</strong> {@code demo} is activated as {@code prod,demo}
 * on the shared testing instance, where an empty registry makes every screen look broken rather
 * than new. It seeds data and never seeds an identity: the tenant ids below are fixed constants,
 * so a Keycloak account created by hand carrying one of them lands in a populated book. Nothing
 * here creates a login, and the realm fixture in {@code infra/keycloak/} — which has published
 * passwords — still must never be imported anywhere reachable from the internet.
 */
@Component
@Profile({"local", "demo"})
@ConditionalOnProperty(name = "dip.local.seed-tenants", havingValue = "true")
@Order(10) // before LocalTixSeeder, which needs these tenants to exist
public class LocalTenantSeeder implements ApplicationRunner {

    public static final UUID OPERATOR_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID OPERATOR_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /**
     * Participants with no sign-in of their own.
     *
     * <p>They exist so that the participants screen shows a network rather than two rows called
     * "Operator A" and "Operator B", and so that the six editions are visible as something other
     * than a dropdown. Nobody logs in as these: a tenant is a row, and a user is a Keycloak
     * account carrying a tenant attribute. Adding a tenant here costs nothing and adding a user
     * would mean editing the realm.
     *
     * <p>Names are marked "(demo)" for the same reason the other two are. A screenshot of demo
     * data that names a real bank without qualification is a screenshot that will circulate.
     */
    private static final UUID BANK = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID SECOND_BANK = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID UTILITY = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID MINISTRY = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final UUID MICROFINANCE =
            UUID.fromString("77777777-7777-7777-7777-777777777777");
    private static final UUID SUSPENDED = UUID.fromString("88888888-8888-8888-8888-888888888888");

    private final TenantService tenants;

    public LocalTenantSeeder(TenantService tenants) {
        this.tenants = tenants;
    }

    @Override
    public void run(ApplicationArguments args) {
        // provision() is idempotent, so a restart against an existing database is a no-op.
        tenants.provision(OPERATOR_A, "Operator A (demo)", "operator-a",
                Tenant.Edition.TELECOM, "fr", null);
        tenants.provision(OPERATOR_B, "Operator B (demo)", "operator-b",
                Tenant.Edition.TELECOM, "fr", null);

        tenants.provision(BANK, "Banque de Kinshasa (demo)", "banque-kinshasa",
                Tenant.Edition.BANKING, "fr", null);
        tenants.provision(SECOND_BANK, "Crédit du Fleuve (demo)", "credit-fleuve",
                Tenant.Edition.BANKING, "fr", null);
        tenants.provision(UTILITY, "Énergie du Congo (demo)", "energie-congo",
                Tenant.Edition.ENTERPRISE, "fr", null);
        tenants.provision(MINISTRY, "Direction Générale des Impôts (demo)", "dgi",
                Tenant.Edition.GOVERNMENT, "fr", null);
        tenants.provision(MICROFINANCE, "Microfinance Solidarité (demo)", "microfinance-solidarite",
                Tenant.Edition.NGO, "fr", null);
        // Left active here and suspended by the TIX seeder, so the participants screen has both
        // states on it. A status column where every row says the same thing teaches nobody what
        // the other state looks like.
        tenants.provision(SUSPENDED, "Télécom Régional (demo)", "telecom-regional",
                Tenant.Edition.TELECOM, "fr", null);
    }

    /** The participant the TIX seeder suspends, so the screen shows more than one status. */
    static UUID suspendedParticipant() {
        return SUSPENDED;
    }
}
