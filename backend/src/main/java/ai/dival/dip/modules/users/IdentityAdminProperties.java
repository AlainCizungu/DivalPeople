package ai.dival.dip.modules.users;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials that can create accounts, and the reasons to be uncomfortable about them.
 *
 * <p>Everywhere else DIP talks to Keycloak it only <em>verifies</em>: it takes a token somebody
 * else issued and checks the signature. This is the first place the application can <em>change</em>
 * who exists. That is a real widening of what a compromised backend could do, and it is the price
 * of not being a helpdesk for every institution's staff turnover.
 *
 * <p><strong>Ships off.</strong> With no client id or secret configured the feature is inert and
 * the endpoints refuse, in the same way the disclosure switches do. A deployment turns this on
 * deliberately, and until it does, accounts are still made by hand.
 *
 * <p><strong>Give the service account {@code manage-users} and {@code view-users}, and nothing
 * else.</strong> Not {@code realm-admin}. The difference is whether a stolen secret can create a
 * user inside a tenant — bad, and bounded by what {@link MembershipRules} allowed the caller to ask
 * for — or reconfigure the realm, mint clients, and rewrite the mappers that carry {@code
 * tenant_id} in the first place. The second is unrecoverable and nothing in DIP needs it.
 *
 * <p>The secret belongs in the environment, never in a file this repository tracks, and it is a
 * different secret from the web client's. Reusing {@code dip-web}'s would mean one leak costs both
 * sign-in and user management.
 *
 * @param baseUrl      where Keycloak is reachable from the backend, which on the deployed stack is
 *                     the container name rather than the public hostname. Admin traffic has no
 *                     business leaving the private network to come back in.
 * @param realm        the realm holding the accounts, normally {@code dip}
 * @param clientId     a confidential client with a service account, separate from the web client
 * @param clientSecret from the environment
 * @param timeoutMs    how long to wait before giving up. An identity provider that is slow must
 *                     fail an invitation, not hold a request open until the browser abandons it
 */
@ConfigurationProperties(prefix = "dip.identity-admin")
public record IdentityAdminProperties(String baseUrl, String realm, String clientId,
                                      String clientSecret, int timeoutMs) {

    public IdentityAdminProperties {
        realm = blankToNull(realm) == null ? "dip" : realm.trim();
        baseUrl = trimTrailingSlash(blankToNull(baseUrl));
        clientId = blankToNull(clientId);
        clientSecret = blankToNull(clientSecret);
        timeoutMs = timeoutMs > 0 ? timeoutMs : 5_000;
    }

    /**
     * Whether an institution may manage its own accounts on this deployment.
     *
     * <p>All four or none. A half-configured client would fail at the moment somebody invites a
     * colleague, which is the worst time to discover a deployment was incomplete — so the screen
     * asks this first and says the feature is unavailable rather than offering a form that throws.
     */
    public boolean configured() {
        return baseUrl != null && clientId != null && clientSecret != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Trailing slashes removed once, here.
     *
     * <p>Keycloak's admin paths are built by concatenation, and a base URL ending in a slash
     * produces {@code //admin/realms/...}, which some proxies pass through, some normalise, and
     * some refuse. Fixing it at the edge means no call site has to think about it.
     */
    private static String trimTrailingSlash(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.isEmpty() ? null : trimmed;
    }
}
