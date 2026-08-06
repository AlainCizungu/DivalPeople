"use client";

import { useAuth } from "react-oidc-context";
import { useMessages } from "@/i18n/LocaleProvider";
import { BrandMark } from "@/components/BrandMark";

/**
 * Renders children only for an authenticated user; otherwise shows a sign-in prompt.
 *
 * <p>This is a usability boundary, not a security one. Authorization is enforced server-side —
 * hiding UI proves nothing, per docs/SECURITY_MODEL.md.
 */
export function AuthGate({ children }: { children: React.ReactNode }) {
  const auth = useAuth();
  const messages = useMessages();

  if (auth.isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <p className="text-muted">{messages.common.loading}</p>
      </div>
    );
  }

  if (auth.error) {
    return (
      <div className="flex min-h-screen items-center justify-center p-6">
        <div className="max-w-md rounded-lg border border-line bg-white p-8 text-center">
          <h1 className="mb-2 text-xl font-bold text-navy">{messages.auth.errorTitle}</h1>
          <p className="mb-5 text-sm text-muted">{auth.error.message}</p>
          <button
            type="button"
            onClick={() => void auth.signinRedirect()}
            className="rounded bg-blue px-5 py-2.5 text-sm font-bold text-white transition hover:bg-blue-dark"
          >
            {messages.auth.signIn}
          </button>
        </div>
      </div>
    );
  }

  if (!auth.isAuthenticated) {
    return (
      <div className="flex min-h-screen items-center justify-center p-6">
        <div className="w-full max-w-md rounded-lg border border-line bg-white p-10 text-center">
          <div className="mb-5 flex items-center justify-center gap-2">
            <BrandMark size={30} />
            <span className="text-xl font-bold text-navy">{messages.app.name}</span>
          </div>
          <p className="mb-7 text-muted">{messages.auth.signInPrompt}</p>
          <button
            type="button"
            onClick={() => void auth.signinRedirect()}
            className="w-full rounded bg-blue px-5 py-3 text-sm font-bold text-white transition hover:bg-blue-dark"
          >
            {messages.auth.signIn}
          </button>
          <p className="mt-6 text-xs text-muted">{messages.app.platform}</p>
        </div>
      </div>
    );
  }

  return <>{children}</>;
}
