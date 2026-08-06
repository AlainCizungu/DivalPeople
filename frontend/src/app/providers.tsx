"use client";

import { LocaleProvider } from "@/i18n/LocaleProvider";
import { SessionProvider } from "@/auth/SessionProvider";

/**
 * Client-side provider stack applied to every route, public and private alike.
 *
 * <p>Session context is available everywhere so the marketing header can show "Go to dashboard"
 * to somebody already signed in, but it gates nothing on its own — {@code AuthGate} does that,
 * and only inside the product route group.
 *
 * <p>There is no OIDC library here any more. The whole flow runs on the server; see ADR 0003.
 */
export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <LocaleProvider>
      <SessionProvider>{children}</SessionProvider>
    </LocaleProvider>
  );
}
