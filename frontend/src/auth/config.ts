import type { AuthProviderProps } from "react-oidc-context";
import { WebStorageStateStore } from "oidc-client-ts";

/**
 * OIDC configuration for the browser client.
 *
 * <p>Authorization code flow with PKCE against a public client — there is no client secret,
 * because a secret shipped to a browser is not a secret.
 *
 * <p>Tokens are held in session storage rather than local storage so they do not outlive the
 * browser session. For production this should move behind a backend-for-frontend that keeps
 * tokens server-side; see docs/SECURITY_MODEL.md.
 */
export const oidcConfig: AuthProviderProps = {
  authority:
    process.env.NEXT_PUBLIC_OIDC_AUTHORITY ?? "http://localhost:8081/realms/dip",
  client_id: process.env.NEXT_PUBLIC_OIDC_CLIENT_ID ?? "dip-local",
  redirect_uri:
    process.env.NEXT_PUBLIC_OIDC_REDIRECT_URI ?? "http://localhost:3000/",
  post_logout_redirect_uri:
    process.env.NEXT_PUBLIC_OIDC_REDIRECT_URI ?? "http://localhost:3000/",
  response_type: "code",
  scope: "openid profile email",
  automaticSilentRenew: true,
  userStore:
    typeof window === "undefined"
      ? undefined
      : new WebStorageStateStore({ store: window.sessionStorage }),

  // Strip the ?code=&state= query string after a successful sign-in so a refresh does not
  // attempt to redeem an authorization code a second time.
  onSigninCallback: () => {
    window.history.replaceState({}, document.title, window.location.pathname);
  },
};

export const apiBaseUrl =
  process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
