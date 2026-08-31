package ai.dival.dip.modules.users;

import ai.dival.dip.common.security.Roles;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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
 * <p>Failures are wrapped rather than propagated raw, because {@code RestClientException.getMessage()}
 * embeds the request and the request carries the bearer token. Keycloak's own {@code errorMessage}
 * field is kept — that is the server's answer rather than a copy of what was sent, and dropping it
 * meant a 400 arrived with no reason at all.
 */
@Component
public class IdentityAdminClient {

    private final IdentityAdminProperties properties;
    private final RestClient http;
    private final SecureRandom random = new SecureRandom();

    public IdentityAdminClient(IdentityAdminProperties properties) {
        this.properties = properties;

        // The JDK client rather than SimpleClientHttpRequestFactory, which is built on
        // HttpURLConnection. Keycloak removes role mappings with a DELETE that carries a body, and
        // HttpURLConnection's handling of that is unreliable — it is the one request shape in this
        // class that a conventional client gets wrong, and it sits in the middle of creating an
        // account, where a failure leaves half a user behind.
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.timeoutMs()))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
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

    /**
     * Makes the account's DIP roles exactly this set, and leaves everything else alone.
     *
     * <p><strong>Only roles DIP declares are touched.</strong> The first version read every realm
     * role the account held and deleted the lot before adding the wanted ones, which on a
     * newly-created account means deleting {@code default-roles-dip} — Keycloak's own composite,
     * carrying {@code offline_access}, {@code uma_authorization} and the account-console mappings
     * that every user in the realm is given. Stripping it is not DIP's business: those roles say
     * what somebody may do with their own Keycloak account, not what they may do in this product,
     * and an institution's administrator changing a colleague's job title should not silently
     * revoke their ability to manage their own credentials.
     *
     * <p>So the set difference is computed against {@link Roles#all()} and nothing outside it is
     * removed or added. It also makes the operation idempotent in the useful direction: a role
     * already held is not removed and re-added, which was two calls and a window in which the
     * account had neither.
     */
    public void setRoles(String token, UUID userId, Set<String> roles) {
        String base = "/admin/realms/" + properties.realm() + "/users/" + userId
                + "/role-mappings/realm";

        List<String> declared = Roles.all();

        List<Map<String, Object>> ours = getList(token, base).stream()
                .filter(held -> declared.contains(String.valueOf(held.get("name"))))
                .toList();
        Set<String> held = ours.stream()
                .map(role -> String.valueOf(role.get("name")))
                .collect(Collectors.toSet());

        List<Map<String, Object>> remove = ours.stream()
                .filter(role -> !roles.contains(String.valueOf(role.get("name"))))
                .toList();
        if (!remove.isEmpty()) {
            delete(token, base, remove);
        }

        Set<String> wanted = roles.stream().filter(role -> !held.contains(role))
                .collect(Collectors.toSet());
        if (wanted.isEmpty()) {
            return;
        }

        // The roles this particular account could be given, asked user-by-user rather than of the
        // realm. See availableTo for why that distinction is the whole fix.
        Map<String, Map<String, Object>> available = availableTo(token, base);

        List<Map<String, Object>> add = new ArrayList<>();
        for (String role : wanted) {
            Map<String, Object> representation = available.get(role);
            if (representation == null) {
                throw new IdentityAdminException(
                        "The identity provider will not assign " + role + " to this account. The "
                                + "realm and this application disagree about what roles exist, or "
                                + "the service account may not hand this one out.");
            }
            add.add(Map.of("id", representation.get("id"), "name", role));
        }
        post(token, base, add);
    }

    /**
     * Every realm role this account could be given, by name.
     *
     * <p><strong>This is the 403.</strong> Assigning a role needs its id, not its name, and the
     * obvious way to turn one into the other is {@code GET /admin/realms/{realm}/roles/{name}} —
     * which is a read of the realm, and needs {@code view-realm}. The service account has
     * {@code manage-users} and {@code view-users} and nothing else, on my own insistence, so every
     * invitation created the account, failed here, and rolled back.
     *
     * <p>{@code role-mappings/realm/available} answers the same question scoped to one user, which
     * is a user read and covered by {@code view-users}. Same ids, same names, no wider privilege.
     *
     * <p>The lesson is not that the narrow grant was wrong. It is that "grant the least" has to be
     * paired with exercising every path against it, because the failure surfaces as one call in
     * the middle of a sequence rather than at start-up, and the account it half-creates outlives
     * the error.
     */
    private Map<String, Map<String, Object>> availableTo(String token, String base) {
        return getList(token, base + "/available").stream()
                .filter(role -> role.get("name") != null && role.get("id") != null)
                .collect(Collectors.toMap(
                        role -> String.valueOf(role.get("name")),
                        role -> role,
                        (first, second) -> first));
    }

    /**
     * Removes an account outright.
     *
     * <p>The one place DIP deletes rather than disables, and only for an account it created
     * moments earlier that could not be finished. Half a user — created, with no roles and no
     * password — is worse than none: it holds the address, so the administrator who retries is
     * told it is already in use, and nothing on any screen explains why.
     */
    public void deleteUser(String token, UUID userId) {
        delete(token, "/admin/realms/" + properties.realm() + "/users/" + userId, null);
    }

    /** Whether this deployment sends invitations rather than showing a password. */
    public boolean invitesByEmail() {
        return properties.invitesByEmail();
    }

    /**
     * Emails a link that lets somebody set their own password, and verifies the address on the way.
     *
     * <p>Nothing about the credential passes through DIP, an administrator, or a chat window. The
     * account is created with no password at all, so until the person follows the link there is
     * nothing to steal and no way in.
     *
     * <p>Both actions matter and for different reasons. {@code UPDATE_PASSWORD} is the point.
     * {@code VERIFY_EMAIL} is what makes a mistyped address fail loudly: without it, an invitation
     * to {@code alian@} instead of {@code alain@} creates a working account belonging to whoever
     * happens to own the typo, and nothing anywhere says so.
     *
     * <p>The link expires. A long lifespan feels kinder and is the wrong instinct here — this sets
     * the password on an account that can read other institutions' credit records, so a link
     * sitting in an inbox is a credential sitting in an inbox. Re-sending is two clicks.
     *
     * @throws IdentityAdminException when the realm has no mail server, or the send fails
     */
    public void sendInvitation(String token, UUID userId) {
        String path = "/admin/realms/" + properties.realm() + "/users/" + userId
                + "/execute-actions-email"
                + "?client_id=" + encode(properties.inviteClientId())
                + "&redirect_uri=" + encode(properties.inviteRedirectUri())
                + "&lifespan=" + properties.inviteLifespanSeconds();

        put(token, path, List.of("UPDATE_PASSWORD", "VERIFY_EMAIL"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Sets a temporary password. The account must change it at first sign-in. */
    public void setTemporaryPassword(String token, UUID userId, String password) {
        put(token, "/admin/realms/" + properties.realm() + "/users/" + userId + "/reset-password",
                Map.of("type", "password", "value", password, "temporary", true));
    }

    /**
     * Records which institution an existing account belongs to.
     *
     * <p>Used once, when somebody joins by proving an address. Every request that account ever
     * makes afterwards is trusted on this attribute, because it is what the {@code tenant_id} claim
     * in the access token is built from — so this single write is the moment a stranger becomes a
     * member of an institution.
     *
     * <p><strong>Reads the account first and writes the attributes back merged.</strong> Keycloak's
     * user update replaces the whole attribute map, so sending only {@code tenant_id} would delete
     * every other attribute the account has. There are none today. There will be, and the day
     * somebody adds one this would quietly remove it for exactly the accounts that joined by
     * themselves — a bug that appears months later and only for some users, which is the worst kind
     * to be handed.
     */
    public void assignTenant(String token, UUID userId, UUID tenantId) {
        Map<String, Object> user =
                get(token, "/admin/realms/" + properties.realm() + "/users/" + userId);

        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        if (user.get("attributes") instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> attributes.put(String.valueOf(key), value));
        }
        attributes.put("tenant_id", List.of(tenantId.toString()));

        put(token, "/admin/realms/" + properties.realm() + "/users/" + userId,
                Map.of("attributes", attributes));
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

    /**
     * A DELETE, with a body when Keycloak needs one.
     *
     * <p>Removing role mappings is a DELETE that carries the roles to remove, which is unusual
     * enough that not every HTTP client will send it. A null body means a plain DELETE, for
     * removing a resource by its path.
     */
    private void delete(String token, String path, Object body) {
        try {
            RestClient.RequestBodySpec request = http.method(HttpMethod.DELETE)
                    .uri(properties.baseUrl() + path)
                    .header("Authorization", "Bearer " + token);

            if (body == null) {
                request.retrieve().toBodilessEntity();
            } else {
                request.contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
            }
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
        if (!(failed instanceof HttpStatusCodeException coded)) {
            return new IdentityAdminException("The identity provider could not be reached.", 0);
        }

        int status = coded.getStatusCode().value();
        String reason = errorMessageIn(coded.getResponseBodyAsString());

        return new IdentityAdminException(
                reason == null
                        ? "The identity provider refused the change with status " + status + "."
                        : "The identity provider refused the change with status " + status
                                + ": " + reason,
                status);
    }

    /**
     * Keycloak's own sentence, and nothing else from the response.
     *
     * <p>The first version discarded the body entirely, on the reasoning that a Keycloak error can
     * carry the client secret. That is true of {@code RestClientException.getMessage()}, which
     * embeds the request, and of the exception as a cause — and it is not true of the response
     * body, which is Keycloak's answer rather than a copy of what was sent.
     *
     * <p>The caution cost more than it saved. A 400 arrived with no reason attached and the only
     * way to learn why was to reproduce the call by hand with curl, which is a round trip and a
     * paragraph of shell for something the server had already said.
     *
     * <p>Only the {@code errorMessage} field, capped. Keycloak puts a human sentence there — "User
     * email missing", "Invalid redirect uri" — and taking that one field rather than the whole body
     * means a future error shape cannot smuggle anything else into an API response or a log line.
     */
    private static String errorMessageIn(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        int start = body.indexOf("\"errorMessage\"");
        if (start < 0) {
            return null;
        }
        int open = body.indexOf('"', body.indexOf(':', start) + 1);
        int close = open < 0 ? -1 : body.indexOf('"', open + 1);
        if (open < 0 || close < 0) {
            return null;
        }
        String message = body.substring(open + 1, close);
        return message.length() > 200 ? message.substring(0, 200) : message;
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
