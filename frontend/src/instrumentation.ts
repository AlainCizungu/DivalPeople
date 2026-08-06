/**
 * Runs once, when the server starts.
 *
 * <p>This exists to make a claim in {@code server/env.ts} actually true. Those checks run at
 * module load, and Next.js loads a route module the first time something asks for that route —
 * so without this the configuration would be validated on the first request rather than at
 * start-up.
 *
 * <p>The difference matters. A container that exits immediately is caught by whoever deployed it,
 * within seconds, while they are still watching. A container that starts healthy and fails on the
 * first real request is caught by a user, and looks like an outage rather than a typo.
 */
export async function register() {
  // Node only. The edge runtime has no place holding a client secret, and importing
  // `server-only` there would fail anyway.
  if (process.env.NEXT_RUNTIME !== "nodejs") {
    return;
  }

  // The import itself is the check: server/env.ts validates at module load and throws.
  await import("./server/env");
}
