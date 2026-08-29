package ai.dival.dip.modules.users;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.common.error.ConflictException;
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
 * <p>Everything is audited before it happens, in the same shape as every other consequential act
 * on this platform. An account created with no trail is an account nobody can explain later.
 */
@Service
public class MembershipService {

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
     * rolled back by a database transaction; wrapping it in one would suggest otherwise. The audit
     * row is written first for that reason: if Keycloak then fails, the trail says an attempt was
     * made, which is the honest record.
     */
    public Invitation invite(String email, String displayName, List<String> roles, UUID actorId) {
        UUID tenant = TenantContext.require();
        String address = MembershipRules.normaliseEmail(email);
        Set<String> granted = MembershipRules.validate(roles);

        refuseIfKnown(address);

        audit.record(INVITED, "UserAccount", address, AuditService.OUTCOME_SUCCESS, actorId,
                "Roles: " + String.join(", ", granted));

        String token = identity.token();
        UUID created = identity.createUser(token, address, displayName, tenant);
        identity.setRoles(token, created, granted);

        String password = identity.freshPassword();
        identity.setTemporaryPassword(token, created, password);

        return new Invitation(created, address, List.copyOf(granted), password);
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

        identity.setRoles(token, userId, granted);
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

        identity.setEnabled(token, userId, active);
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

        Optional<UUID> owner = identity.tenantOf(token, userId);
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
     * <p>Only its own. Keycloak would refuse a duplicate username across the realm anyway, and
     * that refusal is the right one to reach — it says "in use" without saying by whom, so an
     * administrator cannot enumerate a competitor's staff by inviting addresses and reading the
     * error.
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
