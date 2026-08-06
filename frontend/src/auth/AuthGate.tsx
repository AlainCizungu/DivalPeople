"use client";

import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import { BrandMark } from "@/components/BrandMark";

/**
 * Renders children only for an authenticated user; otherwise shows a sign-in prompt.
 *
 * <p>This is a usability boundary, not a security one. Authorization is enforced server-side —
 * hiding UI proves nothing, per docs/SECURITY_MODEL.md.
 */
export function AuthGate({ children }: { children: React.ReactNode }) {
  const { status, signIn } = useSession();
  const messages = useMessages();

  if (status === "loading") {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <p className="text-muted">{messages.common.loading}</p>
      </div>
    );
  }

  if (status !== "authenticated") {
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
            onClick={() => signIn()}
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
