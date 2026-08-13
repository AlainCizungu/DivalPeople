package ai.dival.dip.modules.access;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.security.Roles;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The permission catalogue, and the reason it is generated rather than written.
 *
 * <p>These assertions are about the platform's authorisation model, not about this class. A guard
 * that stops guarding fails a test here, which is the property that makes the screen worth
 * trusting: it cannot describe permissions the application does not enforce, because it has no
 * source of truth other than the enforcement.
 */
@RequiresDocker
class AccessServiceTest extends AbstractIntegrationTest {

    @Autowired
    private AccessService access;

    @Test
    @DisplayName("the catalogue is read through the constants controllers hide their rules behind")
    void namedConstantsAreResolved() {
        // The reason this is a runtime scan. A text search over the source finds nothing for
        // PAYROLL_OFFICER: PayrollController hoists its expression into a constant and the
        // annotation reads @PreAuthorize(PAYROLL). Grep concludes the role guards nothing, which
        // is false — and a permissions page repeating that would be confidently wrong.
        assertThat(role(Roles.PAYROLL_OFFICER).endpoints())
                .as("PAYROLL_OFFICER guards payroll through a named constant, not a literal")
                .isPositive();
        assertThat(role(Roles.FINANCE_OFFICER).endpoints()).isPositive();
        assertThat(role(Roles.MANAGER).endpoints()).isPositive();
        assertThat(role(Roles.RECRUITER).endpoints()).isPositive();
    }

    @Test
    @DisplayName("declaring a debt and delivering a file are the same role's work")
    void theDeclarantOwnsBothHalvesOfGettingDataIn() {
        List<String> areas = role(Roles.TIX_DECLARANT).areas().stream()
                .map(AccessService.Area::name)
                .toList();

        // Worth asserting because it is a product decision rather than an accident: an operator
        // that may declare a debt by hand may also deliver a file of them, and the reverse would
        // be a hole — bulk is the easier route, not the more privileged one.
        assertThat(areas).contains("tix", "ingest");
    }

    @Test
    @DisplayName("the platform administrator's reach is narrow, and stays narrow")
    void platformAdministrationIsNotAMasterKey() {
        AccessService.RoleAccess admin = role(Roles.PLATFORM_ADMIN);

        // This did read containsExactly("platform"), with a note saying that if a future endpoint
        // quietly handed this role a second area the test would fail. One did, and it did — which
        // is the test working rather than the test being wrong, and the widening is worth naming
        // rather than waving through.
        //
        // Identity resolution puts one operator's record beside another's, both names visible, so
        // it cannot belong to a participant: the exchange spends its whole design making sure an
        // inquiry discloses a count and a status and nothing else. The registry resolves. That is
        // a real increase in what PLATFORM_ADMIN can see — from tenant administration, which
        // touches no subject, to subject names across operators — and it is the bureau model
        // rather than an oversight.
        //
        // A third area appearing without a decision behind it fails here, as this one did.
        assertThat(admin.areas().stream().map(AccessService.Area::name))
                .containsExactlyInAnyOrder("platform", "resolution");
    }

    @Test
    @DisplayName("every declared role appears, including any that guards nothing")
    void aRoleThatGrantsNothingIsStillListed() {
        List<String> listed = access.forCaller(false, List.of()).roles().stream()
                .map(AccessService.RoleAccess::role)
                .toList();

        // A role missing from the page reads as a page that has not loaded. A role present with a
        // count of nought reads as what it is: something a tenant administrator can assign to a
        // member of staff that will change nothing for them.
        assertThat(listed).contains(Roles.EMPLOYEE, Roles.AUDITOR, Roles.HR_MANAGER);
        assertThat(listed).contains(AccessService.AUTHENTICATED);
    }

    @Test
    @DisplayName("endpoints open to anybody signed in are counted, not quietly dropped")
    void theAuthenticatedOnlySurfaceIsVisible() {
        // A third of the API is reachable by any signed-in user, every one of those a deliberate
        // decision. Omitting them would leave a reader to conclude those paths are unreachable.
        assertThat(role(AccessService.AUTHENTICATED).endpoints()).isPositive();
    }

    @Test
    @DisplayName("the caller's own roles are marked, so a refusal can be acted on")
    void theCallerSeesWhichRolesAreTheirs() {
        AccessService.Access mine =
                access.forCaller(false, List.of(Roles.TIX_DECLARANT));

        assertThat(role(mine, Roles.TIX_DECLARANT).held()).isTrue();
        assertThat(role(mine, Roles.PLATFORM_ADMIN).held()).isFalse();
    }

    @Test
    @DisplayName("a caller who may not see the members list gets absent, not empty")
    void theMemberListIsAbsentRatherThanEmpty() {
        AccessService.Access limited = access.forCaller(false, List.of());

        // Empty would say "your organisation has nobody in it", which is a claim about the data
        // rather than about the reader's permissions — and the reassuring one to get wrong.
        assertThat(limited.members()).isNull();
        assertThat(limited.roles())
                .as("and every role still comes back without a holder count")
                .allSatisfy(role -> assertThat(role.heldBy()).isNull());
    }

    private AccessService.RoleAccess role(String name) {
        return role(access.forCaller(false, List.of()), name);
    }

    private AccessService.RoleAccess role(AccessService.Access from, String name) {
        Optional<AccessService.RoleAccess> found = from.roles().stream()
                .filter(entry -> entry.role().equals(name))
                .findFirst();
        assertThat(found).as("%s must appear in the catalogue", name).isPresent();
        return found.orElseThrow();
    }
}
