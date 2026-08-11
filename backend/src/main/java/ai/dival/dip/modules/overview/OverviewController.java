package ai.dival.dip.modules.overview;

import ai.dival.dip.common.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The platform's front door, counted server-side.
 *
 * <p>Open to anybody signed in, and that is not a gap. The response carries only sections the
 * caller's roles allow, and a caller with none of them gets a shape with nothing in it rather than
 * a refusal — the home screen is where somebody lands after signing in, and answering it with 403
 * would tell a legitimate user their account is broken.
 *
 * <p>The role check happens here rather than inside the service, because roles are a web concern
 * and the service should be callable by anything that already knows what the caller may see. What
 * the service will not do is decide that for itself from a security context it cannot see.
 */
@RestController
@RequestMapping("/api/v1/overview")
public class OverviewController {

    private final OverviewService overview;

    public OverviewController(OverviewService overview) {
        this.overview = overview;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public OverviewService.Overview overview(Authentication authentication) {
        return overview.forCaller(
                // Declaring is the right that makes the register yours to see: these figures count
                // records this operator declared, so somebody who cannot declare has nothing to
                // look at rather than a zero to misread.
                hasRole(authentication, Roles.TIX_DECLARANT),
                hasRole(authentication, Roles.COMPLIANCE_OFFICER)
                        || hasRole(authentication, Roles.TIX_DECLARANT));
    }

    /**
     * Whether the caller holds a role.
     *
     * <p>Spring stores them prefixed with {@code ROLE_}; comparing against the bare name would
     * quietly match nothing and every section would come back empty — a failure that looks exactly
     * like an operator having done no work yet.
     */
    private static boolean hasRole(Authentication authentication, String role) {
        if (authentication == null) {
            return false;
        }
        String prefixed = "ROLE_" + role;
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(prefixed::equals);
    }
}
