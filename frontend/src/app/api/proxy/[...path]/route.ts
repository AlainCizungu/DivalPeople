import { NextResponse, type NextRequest } from "next/server";
import { serverEnv } from "@/server/env";
import { refresh } from "@/server/oidc";
import {
  SESSION_COOKIE,
  destroySession,
  readSessionById,
  sessionCookieOptions,
  writeSession,
  type Session,
} from "@/server/session";

export const dynamic = "force-dynamic";

/** Refresh this far before expiry, so a request never races the clock. */
const REFRESH_MARGIN_MS = 30_000;

/**
 * Every API call the browser makes goes through here.
 *
 * <p>The session cookie comes in; an {@code Authorization} header goes out. Nothing in between
 * is visible to the page, which is the entire purpose — see ADR 0003.
 */
async function handle(request: NextRequest, path: string[]) {
  const forbidden = refuseCrossOrigin(request);
  if (forbidden) {
    return forbidden;
  }

  const sessionId = request.cookies.get(SESSION_COOKIE)?.value;
  if (!sessionId) {
    return NextResponse.json(
      { code: "UNAUTHENTICATED", message: "No session" },
      { status: 401 },
    );
  }

  let session = await readSessionById(sessionId);
  if (!session) {
    // The cookie outlived the session. Clearing it stops the browser retrying forever.
    const response = NextResponse.json(
      { code: "SESSION_EXPIRED", message: "Session expired" },
      { status: 401 },
    );
    response.cookies.set(SESSION_COOKIE, "", sessionCookieOptions(0));
    return response;
  }

  const renewed = await ensureFresh(sessionId, session);
  if (!renewed) {
    await destroySession(sessionId);
    const response = NextResponse.json(
      { code: "SESSION_EXPIRED", message: "Session expired" },
      { status: 401 },
    );
    response.cookies.set(SESSION_COOKIE, "", sessionCookieOptions(0));
    return response;
  }
  session = renewed;

  const target = new URL(
    `/api/v1/${path.join("/")}${request.nextUrl.search}`,
    serverEnv.apiBaseUrl,
  );

  const body =
    request.method === "GET" || request.method === "HEAD"
      ? undefined
      : await request.arrayBuffer();

  const upstream = await fetch(target, {
    method: request.method,
    headers: {
      // Only what the API needs. The cookie is deliberately not forwarded: the API authenticates
      // on the bearer token and has no business seeing this application's session credential.
      Authorization: `Bearer ${session.accessToken}`,
      "Content-Type": request.headers.get("content-type") ?? "application/json",
      "Accept-Language": request.headers.get("accept-language") ?? "en",
    },
    body,
    cache: "no-store",
  });

  const payload = await upstream.arrayBuffer();
  return new NextResponse(payload, {
    status: upstream.status,
    headers: {
      "Content-Type": upstream.headers.get("content-type") ?? "application/json",
      // Nothing from this proxy is cacheable: it is all per-session data.
      "Cache-Control": "no-store",
    },
  });
}

/**
 * Renews the access token when it is close to expiring.
 *
 * <p>Returns null when the session can no longer be renewed, which is the signal to sign the
 * user out rather than to keep retrying with a dead token.
 */
async function ensureFresh(id: string, session: Session): Promise<Session | null> {
  if (session.expiresAt - REFRESH_MARGIN_MS > Date.now()) {
    return session;
  }
  if (!session.refreshToken) {
    return null;
  }

  try {
    const tokens = await refresh(session.refreshToken);
    const updated: Session = {
      ...session,
      accessToken: tokens.access_token,
      // Keycloak rotates refresh tokens by default; keeping the old one would fail next time.
      refreshToken: tokens.refresh_token ?? session.refreshToken,
      idToken: tokens.id_token ?? session.idToken,
      expiresAt: Date.now() + tokens.expires_in * 1000,
    };
    await writeSession(id, updated);
    return updated;
  } catch {
    // An expired or revoked refresh token is a normal end of session, not an error worth
    // surfacing with a stack trace.
    return null;
  }
}

/**
 * Refuses requests that did not come from our own page.
 *
 * <p>A cookie-borne credential is attached by the browser automatically, which is the one thing a
 * bearer header never did. {@code SameSite=Lax} already blocks the cross-site state-changing
 * cases; this is the second lock, checked explicitly, because the first one is a browser default
 * we do not control.
 */
function refuseCrossOrigin(request: NextRequest): NextResponse | null {
  if (request.method === "GET" || request.method === "HEAD") {
    return null;
  }
  const origin = request.headers.get("origin");
  if (!origin) {
    // Same-origin fetches from a page always send Origin for state-changing methods. Its
    // absence means something other than our application is calling.
    return NextResponse.json(
      { code: "FORBIDDEN_ORIGIN", message: "Missing origin" },
      { status: 403 },
    );
  }
  if (new URL(origin).origin !== new URL(serverEnv.siteUrl).origin) {
    return NextResponse.json(
      { code: "FORBIDDEN_ORIGIN", message: "Cross-origin request refused" },
      { status: 403 },
    );
  }
  return null;
}

type Context = { params: Promise<{ path: string[] }> };

export async function GET(request: NextRequest, context: Context) {
  return handle(request, (await context.params).path);
}

export async function POST(request: NextRequest, context: Context) {
  return handle(request, (await context.params).path);
}

export async function PUT(request: NextRequest, context: Context) {
  return handle(request, (await context.params).path);
}

export async function PATCH(request: NextRequest, context: Context) {
  return handle(request, (await context.params).path);
}

export async function DELETE(request: NextRequest, context: Context) {
  return handle(request, (await context.params).path);
}
