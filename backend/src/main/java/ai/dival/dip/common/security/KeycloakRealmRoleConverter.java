package ai.dival.dip.common.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns Keycloak realm roles into Spring Security authorities.
 *
 * <p>Keycloak puts roles in a nested {@code realm_access.roles} claim, while Spring's default
 * converter only reads {@code scope}/{@code scp}. Without this, every {@code @PreAuthorize} check
 * denies even for a correctly authenticated user — and it fails closed, so it looks like a
 * permissions bug rather than a wiring bug.
 *
 * <p>{@code hasRole('X')} tests for the authority {@code ROLE_X}, hence the prefix.
 */
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    /** Keycloak's built-in roles carry no meaning for this application. */
    private static final Set<String> IGNORED = Set.of(
            "offline_access", "uma_authorization", "default-roles-dip");

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (!(realmAccess instanceof Map<?, ?> claim)) {
            return List.of();
        }

        Object roles = claim.get(ROLES);
        if (!(roles instanceof Collection<?> roleList)) {
            return List.of();
        }

        return roleList.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> !IGNORED.contains(role))
                .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                .collect(Collectors.toUnmodifiableSet());
    }
}
