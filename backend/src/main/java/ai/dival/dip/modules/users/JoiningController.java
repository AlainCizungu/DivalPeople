package ai.dival.dip.modules.users;

import ai.dival.dip.common.error.PolicyRefusedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two endpoints somebody can reach before they belong to anything.
 *
 * <p><strong>Authenticated, and pointedly not tenant-scoped.</strong> Every other endpoint on this
 * platform needs a bound tenant and refuses without one, which is right and is exactly what makes
 * these two necessary: a person who has just registered has no tenant, so under the normal rule
 * they would sign in successfully and receive "Authentication is required" from every screen,
 * including any screen that could have told them what to do about it.
 *
 * <p>{@code isAuthenticated()} rather than a role, because a person with no institution has no
 * roles either — that is what pending means. The protection here is not a role: it is that neither
 * endpoint reads or writes anything belonging to any institution, and that the tenant is looked up
 * from a verified address rather than taken from the request.
 */
@RestController
@RequestMapping("/api/v1/users/me")
public class JoiningController {

    private final JoiningService joining;
    private final CurrentUserService currentUser;

    public JoiningController(JoiningService joining, CurrentUserService currentUser) {
        this.joining = joining;
        this.currentUser = currentUser;
    }

    /**
     * Where the caller stands, so the screen can say something true before offering a button.
     *
     * <p>Says nothing about which institution a domain belongs to. The person is a click away from
     * finding out for their own address; answering it for any address would make this a way to ask
     * which company owns which domain.
     */
    @GetMapping("/standing")
    @PreAuthorize("isAuthenticated()")
    public JoiningService.Standing standing() {
        return joining.standing();
    }

    /**
     * Joins the institution that owns the caller's verified address, with no roles.
     *
     * <p>Unavailable on a deployment with no identity-provider service account, because joining
     * writes the institution onto the account and there is nothing to write it with. Said plainly
     * rather than failing, in the same shape as the invite endpoints.
     */
    @PostMapping("/join")
    @PreAuthorize("isAuthenticated()")
    public JoiningService.Outcome join() {
        if (!joining.available()) {
            throw new PolicyRefusedException(
                    "This deployment does not let people join by themselves. Ask an administrator "
                            + "at your organisation to invite you.");
        }
        return joining.join(currentUser.currentUserIdOrNull());
    }
}
