import "server-only";

import { randomBytes } from "node:crypto";
import { cookies } from "next/headers";
import { createClient, type RedisClientType } from "redis";
import { serverEnv } from "./env";

/**
 * Server-held sessions.
 *
 * <p>The browser gets one thing: an opaque identifier in an {@code httpOnly} cookie. Every token
 * stays here. See ADR 0003 for why, and for what this does and does not protect against.
 */

export const SESSION_COOKIE = "dip_session";
export const PKCE_COOKIE = "dip_pkce";

export type Session = {
  accessToken: string;
  refreshToken?: string;
  /** Kept only to hint the provider's end-session endpoint at logout. Never sent to the API. */
  idToken?: string;
  /** Epoch milliseconds. Computed at exchange time from {@code expires_in}. */
  expiresAt: number;
  profile: SessionProfile;
};

/** What the browser is allowed to know about who it is. Never a token. */
export type SessionProfile = {
  sub: string;
  name?: string;
  email?: string;
  preferredUsername?: string;
  tenantId?: string;
  roles: string[];
};

let client: RedisClientType | undefined;

async function redis(): Promise<RedisClientType> {
  if (!client) {
    client = createClient({ url: serverEnv.redisUrl });
    // A dropped connection must not take the process down; the next call reconnects.
    client.on("error", (error: unknown) => console.error("[session] redis error", error));
  }
  if (!client.isOpen) {
    await client.connect();
  }
  return client;
}

function key(id: string): string {
  return `dip:session:${id}`;
}

/** 256 bits from the system CSPRNG. This value is the whole credential, so it is not a counter. */
export function newSessionId(): string {
  return randomBytes(32).toString("base64url");
}

export async function readSession(): Promise<Session | null> {
  const id = (await cookies()).get(SESSION_COOKIE)?.value;
  if (!id) {
    return null;
  }
  return readSessionById(id);
}

export async function readSessionById(id: string): Promise<Session | null> {
  const stored = await (await redis()).get(key(id));
  if (!stored) {
    return null;
  }
  // Touching on read gives an idle timeout rather than an absolute one: somebody working all day
  // is not signed out mid-sentence, and somebody who walked away is.
  await (await redis()).expire(key(id), serverEnv.sessionTtlSeconds);
  return JSON.parse(stored) as Session;
}

export async function writeSession(id: string, session: Session): Promise<void> {
  await (await redis()).set(key(id), JSON.stringify(session), {
    EX: serverEnv.sessionTtlSeconds,
  });
}

export async function destroySession(id: string): Promise<void> {
  await (await redis()).del(key(id));
}

/**
 * Cookie flags, in one place so they cannot drift between the routes that set them.
 *
 * <p>{@code sameSite: "lax"} rather than {@code strict} because the OIDC callback is a
 * cross-site top-level navigation back from Keycloak, and {@code strict} would drop the cookie
 * on precisely that request. Lax still refuses to send it on cross-site POSTs, which is the case
 * that matters.
 */
export function sessionCookieOptions(maxAgeSeconds: number) {
  return {
    httpOnly: true,
    secure: serverEnv.isProduction,
    sameSite: "lax" as const,
    path: "/",
    maxAge: maxAgeSeconds,
  };
}

/** Claims we are willing to hand to the browser, pulled from the id token. */
export function profileFromClaims(claims: Record<string, unknown>): SessionProfile {
  const realmAccess = claims.realm_access as { roles?: string[] } | undefined;
  return {
    sub: String(claims.sub ?? ""),
    name: typeof claims.name === "string" ? claims.name : undefined,
    email: typeof claims.email === "string" ? claims.email : undefined,
    preferredUsername:
      typeof claims.preferred_username === "string" ? claims.preferred_username : undefined,
    tenantId: typeof claims.tenant_id === "string" ? claims.tenant_id : undefined,
    roles: Array.isArray(realmAccess?.roles) ? realmAccess.roles : [],
  };
}

/**
 * Reads the payload of a JWT without verifying it.
 *
 * <p>Safe here and nowhere else: this token came back from the token endpoint over a
 * server-to-server TLS connection we opened, so the channel establishes provenance. It is used
 * only to populate a display profile. Nothing is authorized on the strength of it — the API
 * verifies the access token properly, every request.
 */
export function decodeJwtPayload(token: string): Record<string, unknown> {
  const payload = token.split(".")[1];
  if (!payload) {
    return {};
  }
  try {
    return JSON.parse(Buffer.from(payload, "base64url").toString("utf8")) as Record<
      string,
      unknown
    >;
  } catch {
    return {};
  }
}
