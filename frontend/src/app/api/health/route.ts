import { NextResponse } from "next/server";

/**
 * Liveness only. Deliberately says nothing else.
 *
 * <p>It answers one question: is this process able to serve a request. It does not check Redis or
 * the API, and that is a decision rather than an omission — a health check that fails when a
 * dependency is down causes an orchestrator to restart a process that was working perfectly well,
 * turning one outage into two. Dependency health belongs on a status page, not on the probe that
 * decides whether to kill the container.
 *
 * <p>No version, no build id, no configuration. An unauthenticated endpoint is reachable by
 * anyone who can reach the port, and telling them precisely what is running is free
 * reconnaissance.
 */
export const dynamic = "force-dynamic";

export function GET() {
  return NextResponse.json({ status: "UP" }, { status: 200 });
}
