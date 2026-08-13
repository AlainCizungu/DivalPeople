package ai.dival.dip.modules.access;

import ai.dival.dip.common.security.Roles;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Who may do what here.
 *
 * <p>Open to anybody signed in, and the two halves behind it are not. The role catalogue is the
 * platform's own rules and everybody subject to them should be able to read them — it is the
 * answer to "why can I not open Data imports", which the product currently answers with generic
 * wording on five screens. The list of people in the organisation is a different matter and needs
 * the tenant administrator's role.
 *
 * <p>Read-only. Roles are held in the identity provider and changing one here would mean this
 * application holding Keycloak administrative credentials — a large amount of new authority to
 * acquire for a convenience, and the wrong place for the decision to be recorded.
 */
@RestController
@RequestMapping("/api/v1/access")
public class AccessController {

    private final AccessService access;

    public AccessController(AccessService access) {
        this.access = access;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public AccessService.Access access(Authentication authentication) {
        return access.forCaller(hasRole(authentication, Roles.TENANT_ADMIN),
                rolesOf(authentication));
    }

    /**
     * The caller's own roles, stripped of Spring's prefix.
     *
     * <p>Spring stores them as {@code ROLE_TENANT_ADMIN}; the catalogue and the identity provider
     * both use the bare name. Comparing the two forms would match nothing and every role would
     * render as one the caller does not hold — a failure that looks exactly like correct output.
     */
    private static List<String> rolesOf(Authentication authentication) {
        if (authentication == null) {
            return List.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .map(authority -> authority.substring("ROLE_".length()))
                .toList();
    }

    private static boolean hasRole(Authentication authentication, String role) {
        return rolesOf(authentication).contains(role);
    }
}
