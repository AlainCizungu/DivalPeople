package ai.dival.dip.modules.users;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountRepository extends JpaRepository<UserAccount, UUID> {

    /**
     * Lookup by OIDC subject, without a tenant predicate.
     *
     * <p>The subject is globally unique, and provisioning has to find an existing record before a
     * tenant is necessarily known to be correct. The caller must verify the returned record's
     * tenant matches the request context — {@link CurrentUserService} does exactly that.
     */
    Optional<UserAccount> findBySubject(String subject);

    List<UserAccount> findByTenantIdOrderByDisplayNameAsc(UUID tenantId);

    Optional<UserAccount> findByIdAndTenantId(UUID id, UUID tenantId);

    long countByTenantId(UUID tenantId);

    /**
     * Members whose stored role snapshot contains a role.
     *
     * <p>For routing only — deciding who should be told about something. The snapshot is not
     * authoritative for permissions, which are always read from the access token; using it to
     * pick notification recipients is a display concern, not an authorization one.
     */
    List<UserAccount> findByTenantIdAndRolesContainingAndActiveTrue(UUID tenantId, String role);
}
