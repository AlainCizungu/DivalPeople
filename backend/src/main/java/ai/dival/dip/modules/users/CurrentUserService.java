package ai.dival.dip.modules.users;

import ai.dival.dip.common.tenancy.TenantContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the authenticated principal to a local {@link UserAccount}, creating one on first use.
 *
 * <p>Provisioning happens here rather than in a servlet filter so that unauthenticated and health
 * endpoints never touch the database, and so the write only happens when something actually needs
 * an actor.
 */
@Service
public class CurrentUserService {

    private static final Logger log = LoggerFactory.getLogger(CurrentUserService.class);
    private static final String ROLE_PREFIX = "ROLE_";

    private final UserAccountRepository users;

    public CurrentUserService(UserAccountRepository users) {
        this.users = users;
    }

    /**
     * The current user, provisioned if this is their first authenticated request.
     *
     * @throws IllegalStateException if there is no authenticated JWT principal
     */
    @Transactional
    public UserAccount requireCurrentUser() {
        Jwt jwt = currentJwt().orElseThrow(
                () -> new IllegalStateException("No authenticated principal bound to the request"));
        UUID tenantId = TenantContext.require();

        String subject = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        String displayName = displayNameOf(jwt);
        List<String> roles = currentRoles();

        Optional<UserAccount> existing = users.findBySubject(subject);
        if (existing.isPresent()) {
            UserAccount user = existing.get();

            // The subject is globally unique, so a mismatch means the token's tenant claim
            // disagrees with what we stored. Refuse rather than silently serve another tenant.
            if (!user.getTenantId().equals(tenantId)) {
                throw new TenantMismatchException(subject);
            }
            if (user.refreshFrom(email, displayName, roles)) {
                users.save(user);
            }
            return user;
        }

        return provision(subject, email, displayName, roles);
    }

    /** The current user's id, or {@code null} when there is no authenticated principal. */
    @Transactional
    public UUID currentUserIdOrNull() {
        try {
            return requireCurrentUser().getId();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    @Transactional(readOnly = true)
    public List<UserAccount> listTenantMembers() {
        return users.findByTenantIdOrderByDisplayNameAsc(TenantContext.require());
    }

    private UserAccount provision(String subject, String email, String displayName, List<String> roles) {
        try {
            UserAccount created = users.save(new UserAccount(subject, email, displayName, roles));
            log.info("Provisioned local user account for subject ending {}", tail(subject));
            return created;
        } catch (DataIntegrityViolationException ex) {
            // Two first requests arriving together: one insert wins, the other reads the winner.
            //
            // A re-read that still finds nothing means the row exists but is invisible, which
            // under row-level security means it belongs to another tenant. That is the same
            // condition the explicit check above catches when RLS is not in force.
            return users.findBySubject(subject)
                    .orElseThrow(() -> new TenantMismatchException(subject));
        }
    }

    private Optional<Jwt> currentJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.of(jwt);
    }

    /** Authorities carry the ROLE_ prefix Spring expects; the stored snapshot does not. */
    private List<String> currentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return List.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toList());
    }

    private String displayNameOf(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        String preferred = jwt.getClaimAsString("preferred_username");
        return preferred != null && !preferred.isBlank() ? preferred : jwt.getSubject();
    }

    /** Enough of the subject to correlate a log line, without writing the identifier itself. */
    private String tail(String subject) {
        return subject.length() <= 6 ? "******" : "…" + subject.substring(subject.length() - 6);
    }

    /** The token's tenant claim disagrees with the stored record for this identity. */
    public static class TenantMismatchException extends IllegalStateException {
        public TenantMismatchException(String subject) {
            super("Authenticated subject belongs to a different tenant than the request context");
        }
    }
}
