import "server-only";

/**
 * Server-side configuration.
 *
 * <p>Nothing here is prefixed {@code NEXT_PUBLIC_}, so none of it reaches the browser bundle.
 * That is the point: the client secret and the Redis address are exactly the values that used to
 * have nowhere safe to live.
 *
 * <p>The development fallbacks below <strong>do not apply in production</strong>. A deployment
 * that forgets a variable fails at start-up with the name of the variable, rather than starting
 * happily against localhost and a public client. Convenient defaults are the reason so many
 * deployed systems authenticate against nothing: they work, so nobody looks.
 */
// `next build` runs with NODE_ENV=production and loads every route module to collect its
// metadata, so without this guard the checks below would fire during the image build — on a CI
// runner that has no production secrets and no business holding any. The checks belong to
// start-up, not to compilation.
const isBuild = process.env.NEXT_PHASE === "phase-production-build";

const isProduction = process.env.NODE_ENV === "production" && !isBuild;

function required(name: string, developmentFallback?: string): string {
  const value =
    process.env[name] ?? (isProduction ? undefined : developmentFallback);
  if (!value) {
    throw new Error(
      isProduction
        ? `Missing required environment variable in production: ${name}`
        : `Missing required environment variable: ${name}`,
    );
  }
  return value;
}

export const serverEnv = {
  oidc: {
    issuer: required("OIDC_ISSUER", "http://localhost:8081/realms/dip"),
    clientId: required("OIDC_CLIENT_ID", "dip-local"),
    /**
     * Empty in development against a public Keycloak client. In production the client must be
     * confidential, which the check below enforces rather than merely documents.
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

  isProduction,
};

/**
 * Refuses to start on a configuration that is merely inherited from development.
 *
 * <p>Every one of these has been a real incident somewhere: a staging box pointed at a local
 * issuer, a public OAuth client left public, a cookie marked Secure travelling over plain HTTP.
 * They are cheap to check and impossible to notice, which is the combination that makes a check
 * worth writing.
 */
function verifyProductionConfiguration(): void {
  if (!isProduction) {
    return;
  }

  const faults: string[] = [];

  if (!serverEnv.oidc.clientSecret) {
    faults.push(
      "OIDC_CLIENT_SECRET is empty. A public OAuth client in production means anybody who can " +
        "reach the token endpoint can impersonate this application.",
    );
  }

  for (const [name, value] of [
    ["OIDC_ISSUER", serverEnv.oidc.issuer],
    ["SITE_URL", serverEnv.siteUrl],
  ] as const) {
    if (!value.startsWith("https://")) {
      faults.push(
        `${name} must be https in production; got ${new URL(value).protocol}//…`,
      );
    }
    if (/^https?:\/\/(localhost|127\.0\.0\.1|\[::1\])/i.test(value)) {
      faults.push(`${name} still points at localhost.`);
    }
  }

  // API_BASE_URL is server-to-server and legitimately private — an internal address or a
  // container name is correct there, so it is checked for being unset rather than for scheme.
  if (/^https?:\/\/(localhost|127\.0\.0\.1)/i.test(serverEnv.apiBaseUrl)) {
    faults.push(
      "API_BASE_URL points at localhost, which in a container means this process itself.",
    );
  }

  if (
    !Number.isFinite(serverEnv.sessionTtlSeconds) ||
    serverEnv.sessionTtlSeconds <= 0
  ) {
    faults.push("SESSION_TTL_SECONDS must be a positive number of seconds.");
  }

  if (faults.length > 0) {
    throw new Error(
      "Refusing to start with a development configuration:\n  - " +
        faults.join("\n  - "),
    );
  }
}

verifyProductionConfiguration();

export const AUTH_PATHS = {
  login: "/api/auth/login",
  callback: "/api/auth/callback",
  logout: "/api/auth/logout",
  session: "/api/auth/session",
} as const;

export function callbackUrl(): string {
  return new URL(AUTH_PATHS.callback, serverEnv.siteUrl).toString();
}
