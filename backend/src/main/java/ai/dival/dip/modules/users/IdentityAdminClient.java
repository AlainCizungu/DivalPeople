package ai.dival.dip.modules.users;

import java.net.URI;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The part that talks to Keycloak, and nothing else.
 *
 * <p>Deliberately mechanical. Every decision this could get wrong — whose tenant, which roles, what
 * an address has to look like — is made in {@link MembershipRules} before anything reaches here,
 * and every check on whether the caller may touch a particular account is made in
 * {@link MembershipService}. This class knows how to speak the admin API and holds no policy at
 * all, which is what makes it acceptable that it cannot be integration-tested without standing up
 * an identity provider.
 *
 * <p><strong>The token is fetched per operation and never cached.</strong> An invitation happens a
 * handful of times a week; a cache would be state to invalidate, a lifetime to get wrong, and a
 * credential held in memory for longer, in exchange for a saving nobody would measure.
 *
 * <p>Failures are wrapped rather than propagated raw. A {@code RestClientException} carrying a
 * Keycloak error body can contain the client secret in a header dump, and it would land in the API
 * response and the log.
 */
@Component
public class IdentityAdminClient {

    private final IdentityAdminProperties properties;
    private final RestClient http;
    private final SecureRandom random = new SecureRandom();

    public IdentityAdminClient(IdentityAdminProperties properties) {
        this.properties = properties;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(properties.timeoutMs()));
        factory.setReadTimeout(Duration.ofMillis(properties.timeoutMs()));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    public boolean configured() {
        return properties.configured();
    }

    /**
     * A password nobody chose and nobody keeps.
     *
     * <p>Set as temporary, so Keycloak forces a change at first sign-in and whatever the
     * administrator wrote down stops working the moment it is used. There is no mail server on
     * this deployment, so the invitation travels by whatever channel the administrator already
     * uses with their colleague; when SMTP exists, Keycloak's own update-password email is the
     * better path and this can go.
     *
     * <p>{@code SecureRandom} and URL-safe base64: 24 bytes, no ambiguity about which characters
     * survive being pasted into a chat window.
     */
    public String freshPassword() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * Creates an account and returns its Keycloak id.
     *
     * <p>The tenant is written as an attribute here because it is what every later request will be
     * trusted on: {@code TenantResolutionFilter} reads {@code tenant_id} from the access token, and
     * the token carries whatever this attribute says. The caller has already decided the value; it
     * is never taken from a request body.
     *
     * @throws IdentityAdminException when the address is already in use, or Keycloak refuses
     */
    public UUID createUser(String token, String email, String displayName, UUID tenantId) {
        String[] name = splitName(displayName);

        URI created = post(token, "/admin/realms/" + properties.realm() + "/users", Map.of(
                "username", email,
                "email", email,
                "firstName", name[0],
                "lastName", name[1],
                "enabled", true,
                "emailVerified", false,
                "attributes", Map.of("tenant_id", List.of(tenantId.toString()))));

        // Keycloak returns the new id only in the Location header. Parsing it beats searching for
        // the username afterwards, which would race with anybody else creating the same address.
        String path = created == null ? "" : created.getPath();
        int slash = path.lastIndexOf('/');
        if (slash < 0 || slash == path.length() - 1) {
            throw new IdentityAdminException(
                    "The account was created but the identity provider did not say where. It "
                            + "exists and has no roles; find it by address and finish by hand.");
        }
        return UUID.fromString(path.substring(slash + 1));
    }

    /** Replaces the account's realm roles with exactly this set. */
    public void setRoles(String token, UUID userId, Set<String> roles) {
        String base = "/admin/realms/" + properties.realm() + "/users/" + userId
                + "/role-mappings/realm";

        List<Map<String, Object>> held = getList(token, base);
        if (!held.isEmpty()) {
            delete(token, base, held);
        }
        if (roles.isEmpty()) {
            return;
        }

        List<Map<String, Object>> wanted = roles.stream().map(role -> readRole(token, role)).toList();
        post(token, base, wanted);
    }

    /** Sets a temporary password. The account must change it at first sign-in. */
    public void setTemporaryPassword(String token, UUID userId, String password) {
        put(token, "/admin/realms/" + properties.realm() + "/users/" + userId + "/reset-password",
                Map.of("type", "password", "value", password, "temporary", true));
    }

    /** Enables or disables an account. Disabling is how somebody leaves; nothing is deleted. */
    public void setEnabled(String token, UUID userId, boolean enabled) {
        put(token, "/admin/realms/" + properties.realm() + "/users/" + userId,
                Map.of("enabled", enabled));
    }

    /**
     * The tenant an account belongs to, according to the identity provider.
     *
     * <p>Read before touching an account that already exists. The local record would be quicker
     * and is not authoritative: it is written on first sign-in, so somebody invited and not yet
     * arrived has none, and this is exactly the window in which a cross-tenant edit would go
     * unnoticed.
     */
    public Optional<UUID> tenantOf(String token, UUID userId) {
        Map<String, Object> user =
                get(token, "/admin/realms/" + properties.realm() + "/users/" + userId);
        Object attributes = user.get("attributes");
        if (!(attributes instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Object values = map.get("tenant_id");
        if (!(values instanceof List<?> list) || list.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(String.valueOf(list.get(0))));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }

    /** A service-account token, good for one operation. */
    public String token() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());

        try {
            Map<?, ?> body = http.post()
                    .uri(properties.baseUrl() + "/realms/" + properties.realm()
                            + "/protocol/openid-connect/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);

            Object token = body == null ? null : body.get("access_token");
            if (token == null) {
                throw new IdentityAdminException(
                        "The identity provider accepted the credentials and returned no token.");
            }
            return String.valueOf(token);
        } catch (RestClientException refused) {
            // Deliberately not chained. The cause can carry the request, and the request carries
            // the client secret.
            throw new IdentityAdminException(
                    "Could not authenticate to the identity provider. Check the service account "
                            + "credentials for this deployment.");
        }
    }

    // --- transport ----------------------------------------------------------

    private URI post(String token, String path, Object body) {
        try {
            return http.post()
                    .uri(properties.baseUrl() + path)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();
        } catch (RestClientException failed) {
            throw refusal(failed);
        }
    }

    private void put(String token, String path, Object body) {
        try {
            http.put()
                    .uri(properties.baseUrl() + path)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failed) {
            throw refusal(failed);
        }
    }

    private void delete(String token, String path, Object body) {
        try {
            http.method(org.springframework.http.HttpMethod.DELETE)
                    .uri(properties.baseUrl() + path)
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException failed) {
            throw refusal(failed);
        }
    }

    private Map<String, Object> get(String token, String path) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = http.get()
                    .uri(properties.baseUrl() + path)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(Map.class);
            return body == null ? Map.of() : body;
        } catch (RestClientException failed) {
            throw refusal(failed);
        }
    }

    private List<Map<String, Object>> getList(String token, String path) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> body = http.get()
                    .uri(properties.baseUrl() + path)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(List.class);
            return body == null ? List.of() : body;
        } catch (RestClientException failed) {
            throw refusal(failed);
        }
    }

    /** A role as Keycloak represents it: the mapping endpoint wants the id, not the name. */
    private Map<String, Object> readRole(String token, String role) {
        Map<String, Object> found =
                get(token, "/admin/realms/" + properties.realm() + "/roles/" + role);
        if (found.get("id") == null) {
            throw new IdentityAdminException(
                    "The identity provider has no role called " + role + ". The realm and this "
                            + "application disagree about what roles exist.");
        }
        return Map.of("id", found.get("id"), "name", role);
    }

    /**
     * A first and last name from whatever was typed.
     *
     * <p>Keycloak wants two fields and people have one name, three names, or a name written
     * family-first. Splitting on the last space is wrong for some of them and harmless: these
     * fields are shown, never matched on, and the person can correct them.
     */
    private static String[] splitName(String displayName) {
        String cleaned = displayName == null ? "" : displayName.trim();
        int space = cleaned.lastIndexOf(' ');
        return space <= 0
                ? new String[] {cleaned, ""}
                : new String[] {cleaned.substring(0, space), cleaned.substring(space + 1)};
    }

    /**
     * A refusal, carrying its status and not its body.
     *
     * <p>The status is kept because it is the only part a caller can act on: a 409 means the
     * address is taken and the person should try another, a 401 means this deployment's service
     * account is wrong and the person can do nothing about it. Without it every failure arrived as
     * the same opaque runtime exception and left the web layer no choice but a 500.
     *
     * <p>The body is dropped. It used to be interpolated into the message, which put Keycloak's
     * own JSON — {@code {"errorMessage":"User exists with same email"}} — into an API response and
     * a log line, and it is Keycloak's wording about Keycloak's data model, not DIP's about the
     * caller's problem. Callers that want to say something useful match on the status and write
     * their own sentence.
     */
    private static IdentityAdminException refusal(RestClientException failed) {
        int status = failed instanceof HttpStatusCodeException coded
                ? coded.getStatusCode().value()
                : 0;
        // The status only. Not failed.getMessage(), which embeds the response body, and not the
        // exception as a cause, because the request it carries carries the bearer token.
        return new IdentityAdminException(status == 0
                ? "The identity provider could not be reached."
                : "The identity provider refused the change with status " + status + ".", status);
    }

    /**
     * Something went wrong at the identity provider, said without quoting a credential.
     *
     * @param status the HTTP status the provider answered with, or 0 if it never answered
     */
    public static class IdentityAdminException extends RuntimeException {

        private final int status;

        public IdentityAdminException(String message) {
            this(message, 0);
        }

        public IdentityAdminException(String message, int status) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }

        /** True when the provider said the account already exists. */
        public boolean isConflict() {
            return status == 409;
        }
    }
}
