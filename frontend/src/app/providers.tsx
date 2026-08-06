"use client";

import { AuthProvider } from "react-oidc-context";
import { LocaleProvider } from "@/i18n/LocaleProvider";
import { oidcConfig } from "@/auth/config";

/**
 * Client-side provider stack applied to every route, public and private alike.
 *
 * <p>Auth context is available everywhere so the marketing header can show "Go to dashboard"
 * to someone already signed in, but it gates nothing on its own — {@code AuthGate} does that,
 * and only inside the product route group.
 */
export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <LocaleProvider>
      <AuthProvider {...oidcConfig}>{children}</AuthProvider>
    </LocaleProvider>
  );
}
