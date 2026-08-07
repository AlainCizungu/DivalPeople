import { NextResponse, type NextRequest } from "next/server";
import { serverEnv } from "@/server/env";
import { exchangeCode } from "@/server/oidc";
import {
  PKCE_COOKIE,
  SESSION_COOKIE,
  decodeJwtPayload,
  newSessionId,
  profileFromClaims,
  sessionCookieOptions,
  writeSession,
} from "@/server/session";

export const dynamic = "force-dynamic";

/**
 * Completes a sign-in.
 *
 * <p>This is the only place an authorization code is ever seen, and it runs on the server. The
 * response that reaches the browser carries a session cookie and nothing else.
 */
export async function GET(request: NextRequest) {
  const code = request.nextUrl.searchParams.get("code");
  const state = request.nextUrl.searchParams.get("state");
  const error = request.nextUrl.searchParams.get("error");

  if (error) {
    // The provider's own message is not shown: it is attacker-influencable text on our origin.
    return failed(request, "provider");
  }
  if (!code || !state) {
    return failed(request, "incomplete");
  }

  const stored = request.cookies.get(PKCE_COOKIE)?.value;
  if (!stored) {
    // Usually a stale tab or a bookmarked callback. Sending them back to the start is kinder
    // than an error nobody can act on.
    return failed(request, "expired");
  }

  const { verifier, state: expectedState } = JSON.parse(stored) as {
    verifier: string;
    state: string;
  };

  const [returnedState, encodedReturnTo] = splitState(state);
  if (returnedState !== expectedState) {
    // The state check is what stops somebody else's authorization code being planted in this
    // browser's session.
    return failed(request, "state");
  }

  let tokens;
  try {
    tokens = await exchangeCode(code, verifier);
  } catch {
    return failed(request, "exchange");
  }

  const claims = decodeJwtPayload(tokens.id_token ?? tokens.access_token);
  const id = newSessionId();

  await writeSession(id, {
    accessToken: tokens.access_token,
    refreshToken: tokens.refresh_token,
    idToken: tokens.id_token,
    expiresAt: Date.now() + tokens.expires_in * 1000,
    profile: profileFromClaims(claims),
  });

  const returnTo = safePath(decodeReturnTo(encodedReturnTo));
  const response = NextResponse.redirect(new URL(returnTo, serverEnv.siteUrl));
  response.cookies.set(
    SESSION_COOKIE,
    id,
    sessionCookieOptions(serverEnv.sessionTtlSeconds),
  );
  response.cookies.delete(PKCE_COOKIE);
  return response;
}

/** A malformed escape sequence must not take the whole callback down with it. */
function decodeReturnTo(encoded: string | undefined): string {
  if (!encoded) {
    return "/app";
  }
  try {
    return decodeURIComponent(encoded);
  } catch {
    return "/app";
  }
}

function splitState(state: string): [string, string | undefined] {
  const separator = state.indexOf(":");
  return separator === -1
    ? [state, undefined]
    : [state.slice(0, separator), state.slice(separator + 1)];
}

/**
 * The only path a redirect may go to: one on this site.
 *
 * <p>Resolved through the URL parser rather than checked with string prefixes. The previous
 * version rejected "//host" and accepted "/\\host" — and the WHATWG parser treats a backslash as
 * a slash for http(s), so that resolves to an absolute origin somewhere else. A redirect off the
 * real domain immediately after a real sign-in is the highest-credibility phishing position
 * there is, and the guard was one character away from allowing it.
 *
 * <p>Anything that parses to a different origin, or does not parse at all, becomes "/app".
 */
function safePath(candidate: string | null | undefined): string {
  if (!candidate) {
    return "/app";
  }
  try {
    const site = new URL(serverEnv.siteUrl);
    const resolved = new URL(candidate, site);
    return resolved.origin === site.origin
      ? resolved.pathname + resolved.search + resolved.hash
      : "/app";
  } catch {
    return "/app";
  }
}

/** Back to the landing page with a reason code the UI can translate. */
function failed(request: NextRequest, reason: string) {
  const url = new URL("/", serverEnv.siteUrl);
  url.searchParams.set("authError", reason);
  const response = NextResponse.redirect(url);
  response.cookies.delete(PKCE_COOKIE);
  return response;
}
