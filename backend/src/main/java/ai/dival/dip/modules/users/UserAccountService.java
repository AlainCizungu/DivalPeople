package ai.dival.dip.modules.users;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The users module's published interface for other modules.
 *
 * <p>Separate from {@link CurrentUserService}, which is about the caller of the current request.
 * This is for code asking about users in general — who should be told about something, who a
 * record belongs to. Other modules use this rather than the repository, which is the boundary
 * {@code scripts/check_architecture.py} enforces.
 */
@Service
public class UserAccountService {

    private final UserAccountRepository users;

    public UserAccountService(UserAccountRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Optional<UserAccount> findById(UUID tenantId, UUID id) {
        return users.findByIdAndTenantId(id, tenantId);
    }

    @Transactional(readOnly = true)
    public List<UserAccount> findMembers(UUID tenantId) {
        return users.findByTenantIdOrderByDisplayNameAsc(tenantId);
    }

    /**
     * Active members whose stored role snapshot contains a role.
     *
     * <p>Routing only. Permissions are always decided from the access token; this answers "who
     * should hear about this", which is a different question with a different tolerance for being
     * slightly stale.
     */
    @Transactional(readOnly = true)
    public List<UserAccount> findByRole(UUID tenantId, String role) {
        return users.findByTenantIdAndRolesContainingAndActiveTrue(tenantId, role);
    }
}
