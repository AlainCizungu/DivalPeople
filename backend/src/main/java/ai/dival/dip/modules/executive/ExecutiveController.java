package ai.dival.dip.modules.executive;

import ai.dival.dip.common.security.Roles;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The briefing, section by section, according to what the caller may see.
 *
 * <p>Guarded exactly as the front door is, and deliberately not more tightly. There is a temptation
 * to put an "executive" screen behind an executive role, and it is the wrong instinct twice over:
 * this platform has no such role, and inventing one would mean the person who answers the rights
 * queue cannot see whether the institution is answering it on time. The figures are the operator's
 * own; the roles that already say who may see the register and the case queue say it here too.
 *
 * <p>Sections the caller may not see arrive as null and are never queried, which is the front
 * door's rule and holds for the same reason: a zero and a refusal must not render as the same
 * digit.
 */
@RestController
@RequestMapping("/api/v1/executive")
public class ExecutiveController {

    private final ExecutiveService executive;

    public ExecutiveController(ExecutiveService executive) {
        this.executive = executive;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ExecutiveService.Briefing briefing(Authentication authentication) {
        return executive.forCaller(
                hasRole(authentication, Roles.TIX_DECLARANT),
                hasRole(authentication, Roles.COMPLIANCE_OFFICER)
                        || hasRole(authentication, Roles.TIX_DECLARANT));
    }

    /**
     * Whether the caller holds a role.
     *
     * <p>Spring stores them prefixed with {@code ROLE_}; comparing against the bare name would
     * quietly match nothing and every section would come back empty — a failure that looks exactly
     * like an institution having done no work yet.
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
