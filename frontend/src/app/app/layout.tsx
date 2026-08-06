import { AuthGate } from "@/auth/AuthGate";
import { AppShell } from "@/components/AppShell";

/**
 * Layout for the authenticated product. Everything under /app requires a session and renders
 * inside the application shell; the marketing pages outside this segment do neither.
 */
export default function ProductLayout({ children }: { children: React.ReactNode }) {
  return (
    <AuthGate>
      <AppShell>{children}</AppShell>
    </AuthGate>
  );
}
