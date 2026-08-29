import { AuthGate } from "@/auth/AuthGate";
import { StandingGate } from "@/auth/StandingGate";
import { AppShell } from "@/components/AppShell";

/**
 * Layout for the authenticated product. Everything under /app requires a session and renders
 * inside the application shell; the marketing pages outside this segment do neither.
 *
 * <p>Three gates, narrowing. AuthGate asks whether there is a session at all. StandingGate asks
 * whether this account belongs to an institution and has been granted anything — because a
 * signed-in account with neither used to reach the full application and find every screen refusing
 * it, which reads as a broken product rather than as an account waiting for approval. Only then is
 * the shell drawn, so a navigation menu never offers links that all lead to a refusal.
 */
export default function ProductLayout({ children }: { children: React.ReactNode }) {
  return (
    <AuthGate>
      <StandingGate>
        <AppShell>{children}</AppShell>
      </StandingGate>
    </AuthGate>
  );
}
