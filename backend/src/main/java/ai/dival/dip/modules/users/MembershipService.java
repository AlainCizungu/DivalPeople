package ai.dival.dip.modules.users;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
     * The only place the identity provider's own status reaches the log.
     *
     * <p>It has to be written here because {@link #explain} replaces the exception, and whatever
     * replaces it is what the web layer sees. The message shown to the administrator says the
     * details are in the server log, and until this existed that sentence was false — the
     * exception carrying the status was caught, translated and dropped, so the log had nothing.
     * Telling somebody where to look and putting nothing there is worse than saying nothing.
     */
    private static final Logger log = LoggerFactory.getLogger(MembershipService.class);

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

    /** Whether an invitation travels as an emailed link rather than a password on screen. */
    public boolean invitesByEmail() {
        return identity.invitesByEmail();
    }

    /**
     * Which of these accounts may currently sign in, according to the identity provider.
     *
     * <p>Empty when this deployment has no service account. The caller then has no answer rather
     * than a wrong one, which is the same shape as every other thing that needs the provider.
     *
     * <p>No tenant check here, deliberately, and it is worth saying why that is safe: the caller
     * passes ids it already holds, and it only holds ids for its own members. A tenant check would
     * be a second round trip per account to confirm something the caller could not have got wrong.
     */
    public Map<UUID, Boolean> signInAllowed(Collection<UUID> userIds) {
        if (!identity.configured() || userIds.isEmpty()) {
            return Map.of();
        }
        try {
            return identity.enabledFor(identity.token(), userIds);
        } catch (IdentityAdminClient.IdentityAdminException unreachable) {
            log.warn("Could not read sign-in state from the identity provider: {}",
                    unreachable.getMessage());
            return Map.of();
        }
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

        boolean byEmail = identity.invitesByEmail();
        String token = identity.token();
        UUID created = null;
        String password = null;
        try {
            created = identity.createUser(token, address, displayName, tenant);
            identity.setRoles(token, created, granted);

            if (byEmail) {
                // No password is ever set. Until the person follows the link there is nothing to
                // steal and no way in, which is the whole difference between this path and the
                // other one.
                try {
                    identity.sendInvitation(token, created);
                } catch (IdentityAdminClient.IdentityAdminException notSent) {
                    // Named separately because it is the failure this path will actually hit, and
                    // the generic "a problem with the deployment" would cost somebody an hour.
                    //
                    // The log carries Keycloak's own sentence, not just the status. "User email
                    // missing" and "Invalid redirect uri" are two entirely different problems that
                    // both arrive as 400, and a bare 400 says only that something was wrong with a
                    // request nobody can see.
                    log.warn("Could not send an invitation to {}: {}", address,
                            notSent.getMessage());
                    undo(token, created, address, actorId);
                    throw new PolicyRefusedException(
                            "The account could not be created because the invitation email could "
                                    + "not be sent, so nothing was left behind. Either this "
                                    + "deployment's mail server is not working, or the provider "
                                    + "refused this address — a new sending account often will "
                                    + "not deliver to addresses it has not been told about yet.");
                }
            } else {
                password = identity.freshPassword();
                identity.setTemporaryPassword(token, created, password);
            }
        } catch (IdentityAdminClient.IdentityAdminException refused) {
            audit.record(INVITED, "UserAccount", address, AuditService.OUTCOME_FAILURE, actorId,
                    "Status " + refused.status());
            throw explain(refused, address, undo(token, created, address, actorId));
        }

        audit.record(INVITED, "UserAccount", address, AuditService.OUTCOME_SUCCESS, actorId,
                "Roles: " + String.join(", ", granted)
                        + (byEmail ? "; invitation emailed" : "; password shown once"));

        return new Invitation(created, address, List.copyOf(granted), password, byEmail);
    }

    /**
     * Removes an account that was created and could not be finished.
     *
     * <p>This is the failure that produced three unusable accounts on the first deployment: the
     * create succeeded, something after it did not, and what remained was a user with no roles and
     * no password — invisible on the members screen, which only lists people who have signed in,
     * and holding the address, so every retry came back "already in use". The administrator sees a
     * feature that refuses to create an account that does not appear to exist.
     *
     * <p>Deleting is right here and nowhere else. The rule against deletion protects audit
     * history, and an account created ninety milliseconds ago has none — nothing was attributed to
     * it, nobody signed in as it, and it never became a person.
     *
     * <p>If the delete itself fails, that is said out loud rather than swallowed. The address stays
     * held and somebody has to clear it by hand; a message that pretends otherwise sends the
     * administrator into a loop.
     *
     * @return true when the half-made account was cleared away
     */
    private boolean undo(String token, UUID created, String address, UUID actorId) {
        if (created == null) {
            return true;
        }
        try {
            identity.deleteUser(token, created);
            return true;
        } catch (IdentityAdminClient.IdentityAdminException stuck) {
            audit.record(INVITED, "UserAccount", address, AuditService.OUTCOME_FAILURE, actorId,
                    "Created but unfinished, and could not be removed: status " + stuck.status());
            return false;
        }
    }

    /**
     * The identity provider's refusal, said in terms of what the person can do about it.
     *
     * <p>Every one of these used to escape as a plain runtime exception, so the web layer had
     * nothing to map and answered 500 with a stack trace. The screen then showed whatever a 500
     * body contains, which is to say nothing, and the form looked as though pressing the button
     * did not work.
     *
     * <p>Three cases, separated by who can act. A conflict is the caller's to resolve and they
     * need the address named. A half-made account nobody could remove is theirs to escalate, and
     * the sentence has to say so or they will retry forever. Anything else is the deployment's
     * problem — a rotated secret, a Keycloak that is down — and the person at the form can do
     * nothing but tell somebody.
     *
     * @param address    the address invited, or null on the paths that change an existing account
     * @param cleanedUp  false when an account was created and could not be removed again
     */
    private RuntimeException explain(IdentityAdminClient.IdentityAdminException refused,
                                     String address, boolean cleanedUp) {
        // Status and message only. Never the exception, whose stack can carry the request, and
        // the request carries the bearer token.
        log.warn("Identity provider refused a membership change: status={} cleanedUp={} — {}",
                refused.status(), cleanedUp, refused.getMessage());

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
        if (!cleanedUp) {
            return new PolicyRefusedException(
                    "The account was created and could not be finished, and could not be removed "
                            + "either. " + address + " is held at the identity provider by an "
                            + "account that cannot sign in. Somebody with server access has to "
                            + "delete it before this address can be used.");
        }
        return new PolicyRefusedException(
                "The identity provider did not accept the request, so nothing was changed and no "
                        + "account was left behind. This is a problem with the deployment rather "
                        + "than with what you typed; the status it answered with is in the server "
                        + "log.");
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
            throw explain(refused, null, true);
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
            throw explain(refused, null, true);
        }

        // The provider first, then the local row, and in that order deliberately. If the provider
        // refuses, nothing local should claim the change happened; if the local write fails after
        // the provider succeeded, the person really is suspended and the screen still says so,
        // because AccessService reads the provider. The failure that costs something is the other
        // way round.
        users.recordActive(TenantContext.require(), userId.toString(), active);
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
     * What happened, and what the administrator now has to do about it.
     *
     * @param password shown once, stored nowhere, useless after first sign-in — and <b>null</b>
     *                 when the invitation was emailed, because in that case no password was ever
     *                 set on the account at all. The screen must branch on {@code emailed} rather
     *                 than on this being present, so that a bug producing a null password on the
     *                 other path shows as a fault instead of quietly reading as "check your mail"
     * @param emailed  true when a link was sent and the administrator has nothing to pass on
     */
    public record Invitation(UUID userId, String email, List<String> roles, String password,
                             boolean emailed) {
    }
}
