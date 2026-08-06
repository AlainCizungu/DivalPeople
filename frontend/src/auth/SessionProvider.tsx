"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";

/**
 * Who the browser thinks it is.
 *
 * <p>Deliberately token-free. There is no access token in this object, no method that returns
 * one, and no endpoint that would hand one over — see ADR 0003. Everything the page needs to
 * call the API goes through {@code /api/proxy}, which attaches the token server-side.
 */
export type SessionProfile = {
  sub: string;
  name?: string;
  email?: string;
  preferredUsername?: string;
  tenantId?: string;
  roles: string[];
};

export type SessionState = {
  status: "loading" | "authenticated" | "anonymous";
  profile: SessionProfile | null;
  signIn: (returnTo?: string) => void;
  signOut: () => Promise<void>;
  /** Re-reads the session, for the rare case where something changed underneath the page. */
  refresh: () => Promise<void>;
};

const SessionContext = createContext<SessionState | undefined>(undefined);

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const [status, setStatus] = useState<SessionState["status"]>("loading");
  const [profile, setProfile] = useState<SessionProfile | null>(null);

  const load = useCallback(async () => {
    try {
      const response = await fetch("/api/auth/session", { credentials: "include" });
      const body = (await response.json()) as {
        authenticated: boolean;
        profile?: SessionProfile;
      };
      setProfile(body.profile ?? null);
      setStatus(body.authenticated ? "authenticated" : "anonymous");
    } catch {
      // A failed session probe means anonymous, not broken. The gate will offer a sign-in.
      setProfile(null);
      setStatus("anonymous");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const signIn = useCallback((returnTo?: string) => {
    const target = returnTo ?? window.location.pathname;
    // A full navigation rather than a fetch: the provider needs to be able to show its own
    // login page, and that cannot happen inside an XHR.
    window.location.href = `/api/auth/login?returnTo=${encodeURIComponent(target)}`;
  }, []);

  const signOut = useCallback(async () => {
    const response = await fetch("/api/auth/logout", {
      method: "POST",
      credentials: "include",
    });
    const body = (await response.json().catch(() => ({ to: "/" }))) as { to?: string };
    window.location.href = body.to ?? "/";
  }, []);

  return (
    <SessionContext.Provider
      value={{ status, profile, signIn, signOut, refresh: load }}
    >
      {children}
    </SessionContext.Provider>
  );
}

export function useSession(): SessionState {
  const context = useContext(SessionContext);
  if (!context) {
    throw new Error("useSession must be used inside SessionProvider");
  }
  return context;
}
