import { NextResponse, type NextRequest } from "next/server";
import { serverEnv } from "@/server/env";
import { authorizationUrl, createPkce } from "@/server/oidc";
import { PKCE_COOKIE, sessionCookieOptions } from "@/server/session";

export const dynamic = "force-dynamic";

/**
 * Starts a sign-in.
 *
 * <p>The code verifier and the state go into a short-lived cookie rather than a server store:
 * they are worthless to an attacker without also controlling the redirect, and keeping them in
 * the cookie means an abandoned login leaves nothing behind to expire.
 */
export async function GET(request: NextRequest) {
  const returnTo = safeReturnTo(request.nextUrl.searchParams.get("returnTo"));
  const pkce = createPkce();

  const response = NextResponse.redirect(
    await authorizationUrl(pkce, returnTo),
  );
  response.cookies.set(
    PKCE_COOKIE,
    JSON.stringify({ verifier: pkce.verifier, state: pkce.state }),
    // Ten minutes is longer than any real sign-in and shorter than any useful replay window.
    sessionCookieOptions(600),
  );
  return response;
}

/**
 * Only same-site paths are honoured.
 *
 * <p>Reflecting an arbitrary {@code returnTo} into a redirect is how a login page becomes an open
 * redirect, and an open redirect on the login page is how phishing gets a legitimate domain in
 * front of the victim.
 */
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
function safeReturnTo(candidate: string | null | undefined): string {
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
