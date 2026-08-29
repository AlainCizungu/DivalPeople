package ai.dival.dip.modules.users;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * An institution managing its own people.
 *
 * <p>Three operations and one rule underneath all of them: <strong>the tenant is the caller's, and
 * there is no way to say otherwise.</strong> It is read from the bound context, never from a
 * request, and every account this touches is checked against it at the identity provider before
 * anything changes.
 *
 * <p>That check reads Keycloak rather than the local {@code user_account} row, and the difference
 * matters. The local row is written on first sign-in, so somebody invited yesterday and not yet
 * arrived does not have one — which is precisely the window in which a cross-tenant edit would
 * find no record to contradict it.
 *
 * <p>Everything is audited, in the same shape as every other consequential act on this platform.
 * An account created with no trail is an account nobody can explain later — and an account
 * <em>not</em> created, with a trail that says it was, is worse.
 */
@Service
public class MembershipService {

    /**
     * Recorded before the identity provider is called, so a failure that never returns still
     * leaves a trace of who asked for what.
     */
    public static final String INVITE_ATTEMPTED = "MEMBER_INVITE_ATTEMPTED";

    public static final String INVITED = "MEMBER_INVITED";
    public static final String ROLES_CHANGED = "MEMBER_ROLES_CHANGED";
    public static final String ACCESS_CHANGED = "MEMBER_ACCESS_CHANGED";

    private final IdentityAdminClient identity;
    private final UserAccountService users;
    private final AuditService audit;

    public MembershipService(IdentityAdminClient identity, UserAccountService users,
                             AuditService audit) {
        this.identity = identity;
        this.users = users;
        this.audit = audit;
    }

    public boolean available() {
        return identity.configured();
    }

    /** Roles this institution may hand out, for the form. Never includes the platform's own. */
    public List<String> grantableRoles() {
        return MembershipRules.grantable();
    }

    /**
     * Creates an account in the caller's own institution and returns its first password once.
     *
     * <p>The password is returned and never stored. It is temporary at the identity provider, so
     * it stops working the moment it is used, and DIP has no mail server to send it with — the
     * administrator passes it on however they already talk to their colleague.
     *
     * <p>Not {@code @Transactional}. The work happens at the identity provider and cannot be
     * rolled back by a database transaction; wrapping it in one would suggest otherwise.
     *
     * <p><strong>Two audit rows, not one.</strong> An attempt is recorded before anything is
     * tried, and the outcome after. The first version wrote a single row saying {@code SUCCESS}
     * before calling Keycloak at all, on the reasoning that a trail of attempts is the honest
     * record — which is right, except that a row saying an invitation succeeded when it did not is
     * not a record of an attempt, it is a false statement. Three refused invitations wrote three
     * successes. An audit trail that reports outcomes it never observed is worse than none,
     * because it will be believed.
     */
    public Invitation invite(String email, String displayName, List<String> roles, UUID actorId) {
        UUID tenant = TenantContext.require();
        String address = MembershipRules.normaliseEmail(email);
        Set<String> granted = MembershipRules.validate(roles);

        refuseIfKnown(address);

        audit.record(INVITE_ATTEMPTED, "UserAccount", address, AuditService.OUTCOME_SUCCESS,
                actorId, "Roles: " + String.join(", ", granted));

        UUID created;
        String password;
        try {
            String token = identity.token();
            created = identity.createUser(token, address, displayName, tenant);
            identity.setRoles(token, created, granted);

            password = identity.freshPassword();
            identity.setTemporaryPassword(token, created, password);
        } catch (IdentityAdminClient.IdentityAdminException refused) {
            audit.record(INVITED, "UserAccount", address, AuditService.OUTCOME_FAILURE, actorId,
                    "Status " + refused.status());
            throw explain(refused, address);
        }

        audit.record(INVITED, "UserAccount", address, AuditService.OUTCOME_SUCCESS, actorId,
                "Roles: " + String.join(", ", granted));

        return new Invitation(created, address, List.copyOf(granted), password);
    }

    /**
     * The identity provider's refusal, said in terms of what the person can do about it.
     *
     * <p>Every one of these used to escape as a plain runtime exception, so the web layer had
     * nothing to map and answered 500 with a stack trace. The screen then showed whatever a 500
     * body contains, which is to say nothing, and the form looked as though pressing the button
     * did not work.
     *
     * <p>The two cases differ in who can act. A conflict is the caller's to resolve and they need
     * the address named. Anything else is this deployment's problem — a service account whose
     * secret has been rotated out from under it, a Keycloak that is down — and the person in front
     * of the form can do nothing but tell somebody, so it says so rather than inviting them to
     * retry.
     */
    private RuntimeException explain(IdentityAdminClient.IdentityAdminException refused,
                                     String address) {
        // The address is null on the paths that change an existing account, where a conflict
        // cannot arise and where naming the resource would produce a sentence about a user id
        // being "already in use" — true of every account that exists, and meaningless to read.
        if (refused.isConflict() && address != null) {
            // Deliberately does not say whose. See refuseIfKnown, and the limit noted there.
            return new ConflictException(
                    address + " is already in use on this platform. If they are not in your list "
                            + "below, they may already have an account with another institution, "
                            + "or have been invited and not yet signed in.");
        }
        return new PolicyRefusedException(
                "The identity provider did not accept the request, so nothing was changed. This "
                        + "is a problem with the deployment rather than with what you typed; the "
                        + "status it answered with is in the server log.");
    }

    /**
     * Replaces somebody's roles.
     *
     * <p>Replaces rather than adds, because a screen that could only add is a screen that cannot
     * take anything away, and the reason an administrator opens this is usually that somebody
     * changed jobs.
     */
    public void setRoles(UUID userId, List<String> roles, UUID actorId) {
        Set<String> granted = MembershipRules.validate(roles);
        String token = mustOwn(userId);

        audit.record(ROLES_CHANGED, "UserAccount", userId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId, "Roles: " + String.join(", ", granted));

        try {
            identity.setRoles(token, userId, granted);
        } catch (IdentityAdminClient.IdentityAdminException refused) {
            throw explain(refused, null);
        }
    }

    /**
     * Lets somebody in, or stops them.
     *
     * <p>Disabled, never deleted. A leaver's account is the actor on every audit row they wrote,
     * and deleting it would make a year of the trail unattributable — which is the opposite of
     * what an audit trail is for. Disabling ends the access and keeps the history.
     */
    public void setActive(UUID userId, boolean active, UUID actorId) {
        String token = mustOwn(userId);

        audit.record(ACCESS_CHANGED, "UserAccount", userId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId, active ? "Enabled" : "Disabled");

        try {
            identity.setEnabled(token, userId, active);
        } catch (IdentityAdminClient.IdentityAdminException refused) {
            throw explain(refused, null);
        }
    }

    /**
     * A token, and proof that the account belongs to the caller's institution.
     *
     * <p>The refusal is deliberately identical whether the account is in another tenant or does
     * not exist. Distinguishing them would let an administrator learn which user ids are real
     * across the whole network, one guess at a time.
     */
    private String mustOwn(UUID userId) {
        UUID tenant = TenantContext.require();
        String token = identity.token();

        Optional<UUID> owner;
        try {
            owner = identity.tenantOf(token, userId);
        } catch (IdentityAdminClient.IdentityAdminException unreadable) {
            // Including the 404 for an id that does not exist. Treated as "not yours" rather than
            // reported, because reporting it would answer the question this method exists to
            // refuse: whether a given user id is real.
            owner = Optional.empty();
        }

        if (owner.isEmpty() || !owner.get().equals(tenant)) {
            audit.record(ACCESS_CHANGED, "UserAccount", userId.toString(),
                    AuditService.OUTCOME_DENIED, null,
                    "Attempted to change an account outside the caller's institution");
            // AccessRefusedException, which the web layer turns into a 403 with a generic body:
            // the message is logged and never returned, so the refusal cannot become the oracle
            // this method exists to avoid being.
            throw new AccessRefusedException(
                    "Account " + userId + " is not in the caller's tenant");
        }
        return token;
    }

    /**
     * Refuses an address this institution already knows.
     *
     * <p>Only its own, because only its own is knowable here without reading another institution's
     * member list.
     *
     * <p><strong>An earlier version of this comment claimed the fallback was safe. It is
     * not.</strong> It said Keycloak's realm-wide duplicate check "says in use without saying by
     * whom, so an administrator cannot enumerate a competitor's staff" — but confirming that an
     * address is in use IS the enumeration. An administrator who wants to know whether a named
     * person works at a participating institution can find out by trying to invite them, one
     * address at a time.
     *
     * <p>It cannot be closed by wording. Usernames are unique across the realm because sign-in
     * needs them to be, so the refusal has to happen, and a refusal that lied about its reason
     * would leave the administrator unable to act on a genuine collision. What can be done is
     * make the guessing visible and expensive: every attempt writes {@code MEMBER_INVITE_ATTEMPTED}
     * with the address and the actor, so a run of them is a pattern somebody can see. That is a
     * detection control, not a prevention one, and the honest description of the residual risk is
     * that a determined tenant administrator can test addresses one at a time and be caught
     * afterwards rather than stopped.
     */
    private void refuseIfKnown(String address) {
        boolean known = users.findMembers(TenantContext.require()).stream()
                .anyMatch(member -> address.equalsIgnoreCase(member.getEmail()));
        if (known) {
            throw new ConflictException("Somebody at your organisation already uses " + address);
        }
    }

    /**
     * @param password shown once, stored nowhere, and useless after first sign-in
     */
    public record Invitation(UUID userId, String email, List<String> roles, String password) {
    }
}
