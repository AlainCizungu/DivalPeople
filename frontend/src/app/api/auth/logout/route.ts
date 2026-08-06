import { NextResponse, type NextRequest } from "next/server";
import { serverEnv } from "@/server/env";
import { endSessionUrl } from "@/server/oidc";
import {
  SESSION_COOKIE,
  destroySession,
  readSessionById,
  sessionCookieOptions,
} from "@/server/session";

export const dynamic = "force-dynamic";

/**
 * Ends a session here and at the provider.
 *
 * <p>POST rather than GET: a sign-out reachable by navigation can be triggered from an image tag
 * on somebody else's page, which is a nuisance attack but a real one. The cookie is cleared
 * before the redirect, so a provider that is slow or down still leaves this side signed out.
 */
export async function POST(request: NextRequest) {
  const id = request.cookies.get(SESSION_COOKIE)?.value;
  let idToken: string | undefined;

  if (id) {
    const session = await readSessionById(id);
    idToken = session?.idToken;
    await destroySession(id);
  }

  const response = NextResponse.json({ to: await endSessionUrl(idToken) });
  response.cookies.set(SESSION_COOKIE, "", sessionCookieOptions(0));
  return response;
}

/** A stale tab landing here from a link is sent home rather than shown an error. */
export async function GET() {
  return NextResponse.redirect(serverEnv.siteUrl);
}
