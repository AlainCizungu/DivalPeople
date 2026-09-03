package ai.dival.dip.modules.users;

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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisioning behaviour and the tenant boundary around user records.
 */
@Transactional
@RequiresDocker
class UserProvisioningTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private UserAccountRepository users;
    @Autowired
    private CurrentUserService currentUserService;
    @Autowired
    private UserAccountService accounts;

    private UUID operatorA;
    private UUID operatorB;

    @BeforeEach
    void setUp() {
        operatorA = tenants.save(new Tenant("Operator A", "op-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        operatorB = tenants.save(new Tenant("Operator B", "op-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a first authenticated request creates the local user record")
    void provisionsOnFirstRequest() {
        String subject = UUID.randomUUID().toString();
        authenticateAs(subject, "jean@example.test", "Jean Kabila", "TIX_INQUIRER");

        UserAccount user = TenantContext.runAsResult(operatorA, currentUserService::requireCurrentUser);

        assertThat(user.getId()).isNotNull();
        assertThat(user.getTenantId()).isEqualTo(operatorA);
        assertThat(user.getEmail()).isEqualTo("jean@example.test");
        assertThat(user.getDisplayName()).isEqualTo("Jean Kabila");
        assertThat(user.getRoleList()).containsExactly("TIX_INQUIRER");
        assertThat(user.getLastSeenAt()).isNotNull();
    }

    @Test
    @DisplayName("repeated requests reuse the same record rather than creating duplicates")
    void provisioningIsIdempotent() {
        String subject = UUID.randomUUID().toString();
        authenticateAs(subject, "jean@example.test", "Jean Kabila", "TIX_INQUIRER");

        UUID first = TenantContext.runAsResult(operatorA,
                () -> currentUserService.requireCurrentUser().getId());
        UUID second = TenantContext.runAsResult(operatorA,
                () -> currentUserService.requireCurrentUser().getId());

        assertThat(second).isEqualTo(first);
        assertThat(users.findByTenantIdOrderByDisplayNameAsc(operatorA)).hasSize(1);
    }

    @Test
    @DisplayName("profile changes at the provider are picked up on the next request")
    void refreshesProfileFromToken() {
        String subject = UUID.randomUUID().toString();

        authenticateAs(subject, "old@example.test", "Old Name", "EMPLOYEE");
        TenantContext.runAs(operatorA, currentUserService::requireCurrentUser);

        authenticateAs(subject, "new@example.test", "New Name", "EMPLOYEE", "TENANT_ADMIN");
        UserAccount refreshed = TenantContext.runAsResult(operatorA,
                currentUserService::requireCurrentUser);

        assertThat(refreshed.getEmail()).isEqualTo("new@example.test");
        assertThat(refreshed.getDisplayName()).isEqualTo("New Name");
        assertThat(refreshed.getRoleList()).containsExactlyInAnyOrder("EMPLOYEE", "TENANT_ADMIN");
    }

    @Test
    @DisplayName("a member list contains only the caller's tenant")
    void memberListIsTenantScoped() {
        authenticateAs(UUID.randomUUID().toString(), "a@example.test", "User A", "TENANT_ADMIN");
        TenantContext.runAs(operatorA, currentUserService::requireCurrentUser);

        authenticateAs(UUID.randomUUID().toString(), "b@example.test", "User B", "TENANT_ADMIN");
        TenantContext.runAs(operatorB, currentUserService::requireCurrentUser);

        List<UserAccount> membersOfA = users.findByTenantIdOrderByDisplayNameAsc(operatorA);
        List<UserAccount> membersOfB = users.findByTenantIdOrderByDisplayNameAsc(operatorB);

        assertThat(membersOfA).hasSize(1);
        assertThat(membersOfB).hasSize(1);
        assertThat(membersOfA.get(0).getEmail()).isEqualTo("a@example.test");
        assertThat(membersOfB.get(0).getEmail()).isEqualTo("b@example.test");
    }

    @Test
    @DisplayName("a token claiming a different tenant than the stored record is refused")
    void refusesTenantMismatch() {
        String subject = UUID.randomUUID().toString();
        authenticateAs(subject, "jean@example.test", "Jean Kabila", "TIX_INQUIRER");
        TenantContext.runAs(operatorA, currentUserService::requireCurrentUser);

        // Same identity, but the request now claims to be acting for the other operator.
        assertThatThrownBy(() ->
                TenantContext.runAs(operatorB, currentUserService::requireCurrentUser))
                .isInstanceOf(CurrentUserService.TenantMismatchException.class);
    }

    @Test
    @DisplayName("provisioning without a tenant context fails rather than creating an orphan")
    void refusesWithoutTenantContext() {
        authenticateAs(UUID.randomUUID().toString(), "x@example.test", "X", "EMPLOYEE");
        TenantContext.clear();

        assertThatThrownBy(() -> currentUserService.requireCurrentUser())
                .isInstanceOf(TenantContext.TenantContextMissingException.class);
    }

    @Test
    @DisplayName("suspending somebody stops them being notified, and is visible without Keycloak")
    void suspensionIsRecordedLocallyAndNotOnlyAtTheProvider() {
        String subject = UUID.randomUUID().toString();
        authenticateAs(subject, "olivier@example.test", "Olivier", "TIX_DECLARANT");
        TenantContext.runAs(operatorA, currentUserService::requireCurrentUser);

        assertThat(TenantContext.runAsResult(operatorA,
                () -> accounts.findByRole(operatorA, "TIX_DECLARANT")))
                .as("before suspension he is somebody the platform would write to")
                .hasSize(1);

        boolean written = TenantContext.runAsResult(operatorA,
                () -> accounts.recordActive(operatorA, subject, false));

        assertThat(written).isTrue();
        assertThat(TenantContext.runAsResult(operatorA,
                () -> accounts.findByRole(operatorA, "TIX_DECLARANT")))
                .as("a suspended account should not be told about disputes or corrections; the "
                        + "active column was never written by anything before this, so it was")
                .isEmpty();

        // And back. Suspension is reversible in the product, so it has to be reversible here.
        TenantContext.runAs(operatorA, () -> accounts.recordActive(operatorA, subject, true));
        assertThat(TenantContext.runAsResult(operatorA,
                () -> accounts.findByRole(operatorA, "TIX_DECLARANT")))
                .hasSize(1);
    }

    @Test
    @DisplayName("recording suspension against another institution's member changes nothing")
    void suspensionWillNotReachAcrossTheTenantBoundary() {
        String subject = UUID.randomUUID().toString();
        authenticateAs(subject, "ally@example.test", "Ally", "TIX_INQUIRER");
        TenantContext.runAs(operatorA, currentUserService::requireCurrentUser);

        // Operator B naming operator A's member. The caller has already been checked upstream;
        // this asserts the write refuses on its own rather than on somebody else's promise.
        boolean written = TenantContext.runAsResult(operatorB,
                () -> accounts.recordActive(operatorB, subject, false));

        assertThat(written).isFalse();
        assertThat(TenantContext.runAsResult(operatorA,
                () -> accounts.findByRole(operatorA, "TIX_INQUIRER"))).hasSize(1);
    }

    @Test
    @DisplayName("suspending an invited account that has never signed in is not an error")
    void anAccountWithNoLocalRowIsNotAFailure() {
        // The local row is written on first sign-in, so somebody invited yesterday has none. The
        // provider still suspends them; there is simply nothing here to keep in step.
        boolean written = TenantContext.runAsResult(operatorA,
                () -> accounts.recordActive(operatorA, UUID.randomUUID().toString(), false));

        assertThat(written).isFalse();
    }

    /** Puts a JWT principal with the given realm roles into the security context. */
    private void authenticateAs(String subject, String email, String name, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("email", email)
                .claim("name", name)
                .build();

        // CurrentUserService reads roles from the authorities, which carry the ROLE_ prefix that
        // KeycloakRealmRoleConverter adds in production.
        String[] authorities = java.util.Arrays.stream(roles)
                .map(role -> "ROLE_" + role)
                .toArray(String[]::new);

        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(jwt, "n/a", authorities));
    }
}
