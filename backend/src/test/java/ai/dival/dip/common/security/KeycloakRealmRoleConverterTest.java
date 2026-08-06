package ai.dival.dip.common.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    @Test
    @DisplayName("realm roles become ROLE_-prefixed authorities")
    void mapsRealmRoles() {
        Jwt jwt = jwtWithClaim("realm_access", Map.of("roles", List.of("TIX_INQUIRER", "TIX_DECLARANT")));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_TIX_INQUIRER", "ROLE_TIX_DECLARANT");
    }

    @Test
    @DisplayName("Keycloak's built-in roles are discarded")
    void ignoresBuiltInRoles() {
        Jwt jwt = jwtWithClaim("realm_access",
                Map.of("roles", List.of("offline_access", "uma_authorization", "default-roles-dip", "EMPLOYEE")));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_EMPLOYEE");
    }

    @Test
    @DisplayName("a token with no realm_access claim yields no authorities rather than failing")
    void toleratesMissingClaim() {
        assertThat(converter.convert(jwtWithClaim("scope", "openid"))).isEmpty();
    }

    @Test
    @DisplayName("a malformed realm_access claim yields no authorities")
    void toleratesMalformedClaim() {
        assertThat(converter.convert(jwtWithClaim("realm_access", "not-an-object"))).isEmpty();
        assertThat(converter.convert(jwtWithClaim("realm_access", Map.of("roles", "not-a-list")))).isEmpty();
    }

    private Jwt jwtWithClaim(String name, Object value) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "00000000-0000-0000-0000-000000000001")
                .claim(name, value)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
    }
}
