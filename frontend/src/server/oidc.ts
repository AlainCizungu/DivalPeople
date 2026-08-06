import "server-only";

import { createHash, randomBytes } from "node:crypto";
import { callbackUrl, serverEnv } from "./env";

/**
 * The OIDC conversation, conducted entirely from the server.
 *
 * <p>PKCE is kept even though the client is confidential. It costs nothing and it closes
 * authorization-code interception independently of the secret, which means one mistake in
 * configuration does not become two failures at once.
 */

type Discovery = {
  authorization_endpoint: string;
  token_endpoint: string;
  end_session_endpoint?: string;
};

let cached: Discovery | undefined;

/**
 * Discovery, fetched once per process.
 *
 * <p>Cached rather than re-fetched because these endpoints change when a realm is reconfigured,
 * which is a restart-shaped event, not a per-request one.
 */
export async function discover(): Promise<Discovery> {
  if (cached) {
    return cached;
  }
  const response = await fetch(
    `${serverEnv.oidc.issuer}/.well-known/openid-configuration`,
    { cache: "no-store" },
  );
  if (!response.ok) {
    throw new Error(`OIDC discovery failed: ${response.status}`);
  }
  cached = (await response.json()) as Discovery;
  return cached;
}

export type Pkce = { verifier: string; challenge: string; state: string };

export function createPkce(): Pkce {
  const verifier = randomBytes(32).toString("base64url");
  const challenge = createHash("sha256").update(verifier).digest("base64url");
  return { verifier, challenge, state: randomBytes(16).toString("base64url") };
}

export async function authorizationUrl(pkce: Pkce, returnTo: string): Promise<string> {
  const { authorization_endpoint } = await discover();
  const url = new URL(authorization_endpoint);
  url.searchParams.set("client_id", serverEnv.oidc.clientId);
  url.searchParams.set("redirect_uri", callbackUrl());
  url.searchParams.set("response_type", "code");
  url.searchParams.set("scope", serverEnv.oidc.scope);
  url.searchParams.set("code_challenge", pkce.challenge);
  url.searchParams.set("code_challenge_method", "S256");
  // State carries where to land afterwards, so a deep link survives the round trip. It is
  // compared on return, so it doubles as the CSRF check the spec asks for.
  url.searchParams.set("state", `${pkce.state}:${encodeURIComponent(returnTo)}`);
  return url.toString();
}

export type TokenSet = {
  access_token: string;
  refresh_token?: string;
  id_token?: string;
  expires_in: number;
};

export async function exchangeCode(code: string, verifier: string): Promise<TokenSet> {
  return tokenRequest({
    grant_type: "authorization_code",
    code,
    redirect_uri: callbackUrl(),
    code_verifier: verifier,
  });
}

export async function refresh(refreshToken: string): Promise<TokenSet> {
  return tokenRequest({ grant_type: "refresh_token", refresh_token: refreshToken });
}

async function tokenRequest(params: Record<string, string>): Promise<TokenSet> {
  const { token_endpoint } = await discover();
  const body = new URLSearchParams({ ...params, client_id: serverEnv.oidc.clientId });
  if (serverEnv.oidc.clientSecret) {
    body.set("client_secret", serverEnv.oidc.clientSecret);
  }

  const response = await fetch(token_endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
    cache: "no-store",
  });

  if (!response.ok) {
    // The provider's error body can contain the code or the refresh token. It is logged as a
    // status only, deliberately.
    throw new Error(`Token request failed: ${response.status}`);
  }
  return (await response.json()) as TokenSet;
}

export async function endSessionUrl(idToken: string | undefined): Promise<string> {
  const { end_session_endpoint } = await discover();
  if (!end_session_endpoint) {
    return serverEnv.siteUrl;
  }
  const url = new URL(end_session_endpoint);
  url.searchParams.set("post_logout_redirect_uri", serverEnv.siteUrl);
  if (idToken) {
    url.searchParams.set("id_token_hint", idToken);
  }
  return url.toString();
}
