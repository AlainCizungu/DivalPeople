"use client";

import { AuthProvider } from "react-oidc-context";
import { LocaleProvider } from "@/i18n/LocaleProvider";
import { AuthGate } from "@/auth/AuthGate";
import { AppShell } from "@/components/AppShell";
import { oidcConfig } from "@/auth/config";

/**
 * Client-side provider stack.
 *
 * <p>Locale sits outside auth so the sign-in screen is translated too — a user who cannot read
 * the login page has no way to reach the language switcher.
 */
export function Providers({ children }: { children: React.ReactNode }) {
  return (
    <LocaleProvider>
      <AuthProvider {...oidcConfig}>
        <AuthGate>
          <AppShell>{children}</AppShell>
        </AuthGate>
      </AuthProvider>
    </LocaleProvider>
  );
}
