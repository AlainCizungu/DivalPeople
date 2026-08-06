import "server-only";

/**
 * Server-side configuration.
 *
 * <p>Nothing here is prefixed {@code NEXT_PUBLIC_}, so none of it reaches the browser bundle.
 * That is the point: the client secret and the Redis address are exactly the values that used to
 * have nowhere safe to live.
 */
function required(name: string, fallback?: string): string {
  const value = process.env[name] ?? fallback;
  if (!value) {
    // Failing at startup with the name of the missing variable beats failing at the first login
    // with a stack trace from a token exchange.
    throw new Error(`Missing required environment variable: ${name}`);
  }
  return value;
}

export const serverEnv = {
  oidc: {
    issuer: required("OIDC_ISSUER", "http://localhost:8081/realms/dip"),
    clientId: required("OIDC_CLIENT_ID", "dip-local"),
    /**
     * Empty in development against a public Keycloak client. In any deployed environment the
     * client is confidential and this must be set; see ADR 0003.
     */
    clientSecret: process.env.OIDC_CLIENT_SECRET ?? "",
    scope: process.env.OIDC_SCOPE ?? "openid profile email",
  },

  /** Where the browser reaches this application. Used to build redirect and origin checks. */
  siteUrl: required("SITE_URL", "http://localhost:3000"),

  /** Server-to-server, so this is not the address the browser would use in every deployment. */
  apiBaseUrl: required("API_BASE_URL", "http://localhost:8080"),

  redisUrl: required("REDIS_URL", "redis://127.0.0.1:56379"),

  /** How long a session survives without use. Refresh happens underneath it, invisibly. */
  sessionTtlSeconds: Number(process.env.SESSION_TTL_SECONDS ?? 60 * 60 * 12),

  isProduction: process.env.NODE_ENV === "production",
};

export const AUTH_PATHS = {
  login: "/api/auth/login",
  callback: "/api/auth/callback",
  logout: "/api/auth/logout",
  session: "/api/auth/session",
} as const;

export function callbackUrl(): string {
  return new URL(AUTH_PATHS.callback, serverEnv.siteUrl).toString();
}
