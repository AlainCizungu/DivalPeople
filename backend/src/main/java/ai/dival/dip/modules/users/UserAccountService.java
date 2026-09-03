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

    /**
     * Records locally that an account has been suspended or restored at the identity provider.
     *
     * <p>The provider decides; this row follows. It is written because the {@code active} column is
     * read by things that cannot ask Keycloak — {@link #findByRole} is the one that matters, since
     * it picks who gets told about a dispute or a corrected record, and it filters on this column.
     * Until this existed the column had never been written by anything, so a suspended person went
     * on being notified about their former employer's credit records indefinitely.
     *
     * <p>Returns false when there is no local row, which is the ordinary state of somebody invited
     * and not yet arrived: the row is created on first sign-in. Nothing to correct, and not an
     * error.
     *
     * <p>The tenant is checked here rather than trusted. The caller has checked it too; this is
     * the row being written, and a write that finds a record in another institution should refuse
     * rather than proceed on the strength of somebody else's check.
     */
    @Transactional
    public boolean recordActive(UUID tenantId, String subject, boolean active) {
        Optional<UserAccount> found = users.findBySubject(subject)
                .filter(user -> tenantId.equals(user.getTenantId()));
        found.ifPresent(user -> {
            if (active) {
                user.reactivate();
            } else {
                user.deactivate();
            }
            users.save(user);
        });
        return found.isPresent();
    }
}
