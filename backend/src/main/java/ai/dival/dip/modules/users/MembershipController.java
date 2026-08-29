package ai.dival.dip.modules.users;

import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.security.Roles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An institution's administrator, managing its own staff.
 *
 * <p>Every endpoint here is {@code TENANT_ADMIN}, and none of them takes a tenant. That is not an
 * omission: the tenant is read from the caller's bound context inside
 * {@link MembershipService}, so there is no field an administrator could set to reach into another
 * institution. A parameter that had to be validated would be a parameter somebody could forget to
 * validate.
 *
 * <p>Ships unavailable. {@link IdentityAdminProperties} has no credentials by default, so on a
 * deployment that has not configured a service account these endpoints refuse with a sentence
 * saying so, and accounts are still made by hand.
 */
@RestController
@RequestMapping("/api/v1/users/members")
public class MembershipController {

    private static final String TENANT_ADMIN = "hasRole('" + Roles.TENANT_ADMIN + "')";

    private final MembershipService membership;
    private final CurrentUserService currentUser;

    public MembershipController(MembershipService membership, CurrentUserService currentUser) {
        this.membership = membership;
        this.currentUser = currentUser;
    }

    /**
     * What the screen needs before it draws a form.
     *
     * <p>Asked first so a deployment without a service account says the feature is unavailable,
     * rather than offering a form that fails at the moment somebody uses it. The roles come from
     * the server because the list of what may be granted is a rule, and a rule duplicated in the
     * browser is a rule that will disagree with the server eventually.
     */
    @GetMapping("/options")
    @PreAuthorize(TENANT_ADMIN)
    public Options options() {
        return new Options(membership.available(), membership.grantableRoles(),
                membership.invitesByEmail());
    }

    /**
     * Creates an account in the caller's own institution.
     *
     * <p>Returns a temporary password <strong>once</strong>. It is never stored and never
     * retrievable: an administrator who loses it disables the account and invites again. That is
     * worse than an email and better than a password this platform could be asked to produce
     * later.
     */
    @PostMapping
    @PreAuthorize(TENANT_ADMIN)
    public MembershipService.Invitation invite(@Valid @RequestBody InviteRequest request) {
        mustBeAvailable();
        return membership.invite(request.email(), request.displayName(), request.roles(),
                currentUser.currentUserIdOrNull());
    }

    /** Replaces somebody's roles with exactly the set given. */
    @PutMapping("/{userId}/roles")
    @PreAuthorize(TENANT_ADMIN)
    public void setRoles(@PathVariable UUID userId, @Valid @RequestBody RolesRequest request) {
        mustBeAvailable();
        membership.setRoles(userId, request.roles(), currentUser.currentUserIdOrNull());
    }

    /**
     * Lets somebody in, or stops them.
     *
     * <p>There is no delete. A leaver is the actor on every audit row they wrote, and removing the
     * account would make that history unattributable.
     */
    @PutMapping("/{userId}/active")
    @PreAuthorize(TENANT_ADMIN)
    public void setActive(@PathVariable UUID userId, @Valid @RequestBody ActiveRequest request) {
        mustBeAvailable();
        membership.setActive(userId, request.active(), currentUser.currentUserIdOrNull());
    }

    /**
     * Refused with an explanation rather than a stack trace.
     *
     * <p>A deployment without a configured service account is a deployment decision, not a fault,
     * and the person reading this has an administrator who can act on it.
     */
    private void mustBeAvailable() {
        if (!membership.available()) {
            throw new PolicyRefusedException(
                    "This deployment does not let institutions manage their own accounts. It "
                            + "needs an identity-provider service account, which is a "
                            + "configuration change on the server.");
        }
    }

    /**
     * @param available     false when the deployment has no service account configured
     * @param grantable     every role this institution may assign; never the platform's own
     * @param emailInvites  true when the invitation travels as a link the person follows, so the
     *                      form can say what will happen before it happens rather than surprising
     *                      the administrator with a password they were not expecting to handle
     */
    public record Options(boolean available, List<String> grantable, boolean emailInvites) {
    }

    public record InviteRequest(
            @NotBlank @Size(max = 320) String email,
            @NotBlank @Size(max = 200) String displayName,
            @NotNull @Size(min = 1) List<@NotBlank String> roles) {
    }

    public record RolesRequest(@NotNull @Size(min = 1) List<@NotBlank String> roles) {
    }

    public record ActiveRequest(@NotNull Boolean active) {
    }
}
