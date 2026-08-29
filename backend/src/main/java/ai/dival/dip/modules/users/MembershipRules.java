package ai.dival.dip.modules.users;

import ai.dival.dip.common.security.Roles;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What an institution's own administrator may do to its own people.
 *
 * <p>Today every account at every participating institution is created by hand, with
 * {@code kcadm} on the server. That works for two operators and one demonstration account. It does
 * not survive four telecoms, and it certainly does not survive fifteen banks — the platform
 * operator becomes a helpdesk for other companies' staff turnover.
 *
 * <p>So a tenant administrator gets to invite their own colleagues. The rules for that are here,
 * static and dependency-free, for the same reason {@code ChangeGrading} and
 * {@code ObligationHistory} are: this decides a privilege boundary, and a privilege boundary that
 * can only be exercised by standing up Keycloak is a boundary nobody tests at its edges. The
 * transport that talks to the identity provider is deliberately thin and stupid; every decision it
 * could get wrong is made here first.
 *
 * <p><strong>Two things this class exists to refuse.</strong>
 *
 * <ol>
 *   <li><strong>Creating somebody in another institution's tenant.</strong> DIP is one Keycloak
 *       realm and the tenant boundary rides on a {@code tenant_id} attribute on the account.
 *       Handing an operator Keycloak's own user management would let it mint a user carrying a
 *       competitor's tenant id, and the guard that catches a mismatched claim only fires once a
 *       local record already exists — the first provisioning trusts what the token says. So the
 *       tenant is taken from the caller's bound context and the request is never consulted. There
 *       is no parameter for it, which is stronger than validating one.
 *   <li><strong>Granting the platform's own role.</strong> {@code PLATFORM_ADMIN} runs the network:
 *       it provisions participants and reads across the whole registry. An institution's
 *       administrator escalating to it — deliberately or by pasting the wrong string — would end
 *       the tenant boundary for everybody, not only for themselves.
 * </ol>
 *
 * <p>Everything else is grantable, {@code TENANT_ADMIN} included. An administrator who cannot
 * appoint a successor is an administrator whose departure is an outage, and the platform operator
 * ends up back in the loop it was removed from.
 */
public final class MembershipRules {

    /**
     * The one role an institution can never hand out.
     *
     * <p>A set rather than an equality check, because the next one is a matter of time — a support
     * role that can read across tenants, say — and the day it arrives this should be a one-line
     * edit next to the reasoning rather than a new condition somewhere else.
     */
    private static final Set<String> NEVER_GRANTABLE = Set.of(Roles.PLATFORM_ADMIN);

    private MembershipRules() {
    }

    /** Every role an institution's administrator may assign, in a stable order for the screen. */
    public static List<String> grantable() {
        return Roles.all().stream().filter(role -> !NEVER_GRANTABLE.contains(role)).toList();
    }

    /**
     * Checks a requested set of roles, or refuses with a sentence naming what was wrong.
     *
     * <p>Normalised before it is judged: trimmed and upper-cased, because a role arriving as
     * {@code " tix_inquirer "} from a form is the same intent as {@code TIX_INQUIRER} and refusing
     * it teaches nobody anything. Duplicates collapse.
     *
     * <p><strong>Refuses the whole request rather than dropping what it does not like.</strong>
     * Silently discarding an unrecognised role would create the account with less access than the
     * administrator believed they granted, and they would find out when their colleague could not
     * do their job — days later, with no error to search for. An empty set is refused for the same
     * reason: an account with no roles can sign in and see nothing, which reads as the platform
     * being broken.
     */
    public static Set<String> validate(List<String> requested) {
        if (requested == null || requested.isEmpty()) {
            throw new UnassignableRoleException(
                    "Choose at least one role. An account with none can sign in and do nothing, "
                            + "which is indistinguishable from the platform being broken.");
        }

        Set<String> cleaned = new LinkedHashSet<>();
        for (String role : requested) {
            String normalised = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);

            if (normalised.isEmpty()) {
                throw new UnassignableRoleException("A role cannot be blank.");
            }
            if (NEVER_GRANTABLE.contains(normalised)) {
                throw new UnassignableRoleException(
                        normalised + " runs the network itself — it provisions participants and "
                                + "reads across the whole registry — so it cannot be granted by an "
                                + "institution to its own staff. Ask the platform operator.");
            }
            if (!Roles.all().contains(normalised)) {
                throw new UnassignableRoleException(
                        "There is no role called " + normalised + ". Nothing was created.");
            }
            cleaned.add(normalised);
        }
        return cleaned;
    }

    /**
     * The email an account will be created under, or a refusal.
     *
     * <p>Lower-cased and trimmed. Keycloak treats usernames case-insensitively and the local user
     * record is keyed on the address, so two spellings of one address would be one person in the
     * identity provider and two rows here — a discrepancy that surfaces as a member list with a
     * ghost in it.
     *
     * <p>The check is deliberately shallow: an at-sign with something either side. Anything
     * stricter rejects addresses that exist, and the address is proven by whether the person can
     * sign in, not by a regular expression.
     */
    public static String normaliseEmail(String email) {
        String cleaned = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        int at = cleaned.indexOf('@');
        if (at <= 0 || at == cleaned.length() - 1 || cleaned.contains(" ")) {
            throw new UnassignableRoleException(
                    "An address is needed that somebody could actually be reached at.");
        }
        return cleaned;
    }

    /** Refused before anything is created, anywhere. */
    public static class UnassignableRoleException extends IllegalArgumentException {
        public UnassignableRoleException(String message) {
            super(message);
        }
    }
}
