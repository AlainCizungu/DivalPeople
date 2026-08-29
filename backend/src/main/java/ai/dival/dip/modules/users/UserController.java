package ai.dival.dip.modules.users;

import ai.dival.dip.common.security.Roles;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * User and membership endpoints.
 *
 * <p>Read-only, and it stays that way. Creating an account is a different act with a different
 * guard, and it lives in {@link MembershipController} — which holds the credentials that can
 * change who exists, so that this controller does not.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private static final String AUTHENTICATED = "isAuthenticated()";


    private final CurrentUserService currentUser;

    public UserController(CurrentUserService currentUser) {
        this.currentUser = currentUser;
    }

    /** The signed-in user. Provisions the local record if this is their first request. */
    @GetMapping("/me")
    @PreAuthorize(AUTHENTICATED)
    public UserResponse me() {
        return UserResponse.from(currentUser.requireCurrentUser());
    }

    /** Members of the caller's tenant. Tenant-scoped by {@link CurrentUserService}. */
    @GetMapping
    @PreAuthorize("hasRole('" + Roles.TENANT_ADMIN + "')")
    public List<UserResponse> members() {
        return currentUser.listTenantMembers().stream().map(UserResponse::from).toList();
    }

    /**
     * Response projection. The entity is never serialised directly, and the OIDC subject is not
     * exposed — it is an internal join key, not something clients need.
     */
    public record UserResponse(
            UUID id,
            UUID tenantId,
            String email,
            String displayName,
            List<String> roles,
            boolean active,
            Instant lastSeenAt) {

        static UserResponse from(UserAccount user) {
            return new UserResponse(
                    user.getId(),
                    user.getTenantId(),
                    user.getEmail(),
                    user.getDisplayName(),
                    user.getRoleList(),
                    user.isActive(),
                    user.getLastSeenAt());
        }
    }
}
