import { NextResponse } from "next/server";
import { readSession } from "@/server/session";

export const dynamic = "force-dynamic";

/**
 * Who the browser is.
 *
 * <p>Returns the display profile and nothing else. There is deliberately no endpoint that hands
 * back a token: the whole point of ADR 0003 is that no such endpoint exists.
 */
export async function GET() {
  const session = await readSession();
  if (!session) {
    return NextResponse.json({ authenticated: false }, { status: 200 });
  }
  return NextResponse.json({ authenticated: true, profile: session.profile });
}
