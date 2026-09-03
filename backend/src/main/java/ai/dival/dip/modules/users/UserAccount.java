package ai.dival.dip.modules.users;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * The platform's local record of a person.
 *
 * <p>Identity itself belongs to the OIDC provider. This exists so domain rows can reference an
 * actor, membership can be listed, and audit entries resolve to someone rather than to an opaque
 * token subject.
 *
 * <p>Nothing here grants access. Authorization is decided from the access token on every request.
 */
@Entity
@Table(name = "user_account")
public class UserAccount extends TenantOwnedEntity {

    /** Roles Keycloak issues to everyone, which say nothing about this application. */
    private static final Set<String> UNINTERESTING_ROLES =
            Set.of("offline_access", "uma_authorization", "default-roles-dip");

    @Column(name = "subject", nullable = false, updatable = false, length = 255)
    private String subject;

    @Column(name = "email", length = 320)
    private String email;

    @Column(name = "display_name", length = 300)
    private String displayName;

    /**
     * Comma-separated snapshot of realm roles at last sign-in, for display only.
     *
     * <p>Deliberately not authoritative. A role revoked at the provider takes effect on the next
     * request regardless of this value, because permission checks read the token.
     */
    @Column(name = "roles", nullable = false, length = 1000)
    private String roles = "";

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    protected UserAccount() {
        // for JPA
    }

    public UserAccount(String subject, String email, String displayName, Collection<String> roles) {
        this.subject = subject;
        this.email = email;
        this.displayName = displayName;
        this.roles = normalizeRoles(roles);
        this.active = true;
        this.lastSeenAt = Instant.now();
    }

    /**
     * Refreshes the mutable profile fields from the current token.
     *
     * @return {@code true} when something actually changed, so the caller can avoid a pointless
     *         write on every single request
     */
    public boolean refreshFrom(String email, String displayName, Collection<String> roles) {
        String incomingRoles = normalizeRoles(roles);
        boolean changed = !java.util.Objects.equals(this.email, email)
                || !java.util.Objects.equals(this.displayName, displayName)
                || !this.roles.equals(incomingRoles);

        this.email = email;
        this.displayName = displayName;
        this.roles = incomingRoles;
        this.lastSeenAt = Instant.now();
        return changed;
    }

    /** Sorted, de-duplicated, and stripped of the provider's built-in roles. */
    private static String normalizeRoles(Collection<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        Set<String> cleaned = new TreeSet<>();
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String trimmed = role.trim().toUpperCase(Locale.ROOT);
            if (!UNINTERESTING_ROLES.contains(role) && !UNINTERESTING_ROLES.contains(role.trim())) {
                cleaned.add(trimmed);
            }
        }
        return String.join(",", cleaned);
    }

    public void deactivate() {
        this.active = false;
    }

    public void reactivate() {
        this.active = true;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getRoles() {
        return roles;
    }

    public List<String> getRoleList() {
        return roles.isEmpty() ? List.of() : List.of(roles.split(","));
    }

    public boolean isActive() {
        return active;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }
}
