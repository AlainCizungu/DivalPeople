import { NextResponse, type NextRequest } from "next/server";
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

  const response = NextResponse.redirect(await authorizationUrl(pkce, returnTo));
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
function safeReturnTo(candidate: string | null): string {
  if (!candidate || !candidate.startsWith("/") || candidate.startsWith("//")) {
    return "/app";
  }
  return candidate;
}
