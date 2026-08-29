package ai.dival.dip.modules.users;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.common.security.Roles;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The privilege boundary an institution's administrator may not cross.
 *
 * <p>Plain JUnit, no Spring, no Keycloak — which is the whole reason the rules were separated from
 * the client that talks to the identity provider. A boundary exercisable only by standing up
 * Keycloak is a boundary nobody tests at its edges, and the edges are the entire subject here.
 *
 * <p>The escalation test is the one that matters. Everything else is manners.
 */
class MembershipRulesTest {

    @Nested
    @DisplayName("escalation")
    class Escalation {

        @Test
        @DisplayName("an institution cannot grant the role that runs the network")
        void platformAdminIsNeverGrantable() {
            // The failure this prevents is not one institution over-privileging its own staff. It
            // is the end of the tenant boundary for everybody: PLATFORM_ADMIN provisions
            // participants and reads across the whole registry.
            assertThatThrownBy(() -> MembershipRules.validate(List.of(Roles.PLATFORM_ADMIN)))
                    .isInstanceOf(MembershipRules.UnassignableRoleException.class)
                    .hasMessageContaining(Roles.PLATFORM_ADMIN);
        }

        @Test
        @DisplayName("nor by lower case, padding, or hiding it among legitimate roles")
        void theRefusalSurvivesTheObviousDodges() {
            for (List<String> attempt : List.of(
                    List.of("platform_admin"),
                    List.of("  PLATFORM_ADMIN  "),
                    List.of("PlAtFoRm_AdMiN"),
                    List.of(Roles.TIX_INQUIRER, Roles.PLATFORM_ADMIN),
                    List.of(Roles.PLATFORM_ADMIN, Roles.TIX_INQUIRER))) {
                assertThatThrownBy(() -> MembershipRules.validate(attempt))
                        .as("attempt: %s", attempt)
                        .isInstanceOf(MembershipRules.UnassignableRoleException.class);
            }
        }

        @Test
        @DisplayName("it is absent from the list a screen would offer")
        void theScreenCannotOfferWhatCannotBeGranted() {
            assertThat(MembershipRules.grantable())
                    .doesNotContain(Roles.PLATFORM_ADMIN)
                    .isNotEmpty();
        }

        @Test
        @DisplayName("a successor can be appointed, so a departure is not an outage")
        void tenantAdminIsGrantable() {
            // Deliberately allowed, and the reason is operational rather than security: an
            // administrator who cannot appoint a replacement puts the platform operator back in
            // the loop this whole feature exists to remove it from.
            assertThat(MembershipRules.validate(List.of(Roles.TENANT_ADMIN)))
                    .containsExactly(Roles.TENANT_ADMIN);
        }
    }

    @Nested
    @DisplayName("the whole request, or none of it")
    class AllOrNothing {

        @Test
        @DisplayName("an unknown role refuses the request rather than being dropped")
        void unknownRolesAreNotSilentlyDiscarded() {
            // Dropping it would create the account with less access than the administrator
            // believed they granted. They find out days later, when a colleague cannot do their
            // job, with no error anywhere to search for.
            assertThatThrownBy(() ->
                    MembershipRules.validate(List.of(Roles.TIX_INQUIRER, "CREDIT_MANAGER")))
                    .isInstanceOf(MembershipRules.UnassignableRoleException.class)
                    .hasMessageContaining("CREDIT_MANAGER");
        }

        @Test
        @DisplayName("no roles at all is refused")
        void anAccountMustBeAbleToDoSomething() {
            for (List<String> nothing : List.of(List.<String>of(), List.of("   "))) {
                assertThatThrownBy(() -> MembershipRules.validate(nothing))
                        .isInstanceOf(MembershipRules.UnassignableRoleException.class);
            }
            assertThatThrownBy(() -> MembershipRules.validate(null))
                    .isInstanceOf(MembershipRules.UnassignableRoleException.class);
        }
    }

    @Nested
    @DisplayName("normalisation")
    class Normalisation {

        @Test
        @DisplayName("a role typed from a form is the role it means")
        void caseAndPaddingDoNotDecideAnything() {
            assertThat(MembershipRules.validate(List.of(" tix_inquirer ")))
                    .containsExactly(Roles.TIX_INQUIRER);
        }

        @Test
        @DisplayName("the same role twice is the same role once")
        void duplicatesCollapse() {
            assertThat(MembershipRules.validate(
                    List.of(Roles.TIX_INQUIRER, "tix_inquirer", Roles.TIX_DECLARANT)))
                    .containsExactly(Roles.TIX_INQUIRER, Roles.TIX_DECLARANT);
        }

        @Test
        @DisplayName("an address is lower-cased, because Keycloak does not care and this does")
        void emailIsNormalised() {
            // One person in the identity provider and two rows here is a member list with a ghost
            // in it, and nobody would think to look for the capital letter.
            assertThat(MembershipRules.normaliseEmail("  Jean.Kabila@Rawbank.CD "))
                    .isEqualTo("jean.kabila@rawbank.cd");
        }

        @Test
        @DisplayName("something has to be either side of the at-sign, and no more than that")
        void theAddressCheckIsDeliberatelyShallow() {
            for (String bad : List.of("", "   ", "nobody", "@rawbank.cd", "jean@", "a b@c.cd")) {
                assertThatThrownBy(() -> MembershipRules.normaliseEmail(bad))
                        .as("address: '%s'", bad)
                        .isInstanceOf(MembershipRules.UnassignableRoleException.class);
            }
            // Deliberately accepted. Stricter patterns reject addresses that exist, and the
            // address is proven by whether somebody can sign in, not by a regular expression.
            assertThat(MembershipRules.normaliseEmail("o'brien+dip@sub.domain.museum"))
                    .isEqualTo("o'brien+dip@sub.domain.museum");
        }
    }

    @Nested
    @DisplayName("the catalogue it draws from")
    class Catalogue {

        @Test
        @DisplayName("every declared role is grantable except the one that is not")
        void grantableIsTheDeclaredListMinusOne() {
            assertThat(MembershipRules.grantable())
                    .as("a role added to Roles must appear here without anybody remembering to "
                            + "add it, which is why the list is read off the constants")
                    .hasSize(Roles.all().size() - 1)
                    .allMatch(role -> Roles.all().contains(role));
        }

        @Test
        @DisplayName("Roles.all() finds the constants and nothing else")
        void theReflectiveListIsSane() {
            // It reads static String fields off the class, and the class now also holds a cached
            // list of its own. If that ever became a String this would quietly gain a role.
            assertThat(Roles.all())
                    .contains(Roles.PLATFORM_ADMIN, Roles.TENANT_ADMIN, Roles.TIX_INQUIRER)
                    .doesNotHaveDuplicates()
                    .allMatch(role -> role.equals(role.toUpperCase(java.util.Locale.ROOT)));
        }
    }
}
