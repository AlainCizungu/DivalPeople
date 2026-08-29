"use client";

import { useCallback, useEffect, useState } from "react";
import { ApiError, usersApi, type Standing } from "@/api/client";
import { useSession } from "@/auth/SessionProvider";
import { BrandMark } from "@/components/BrandMark";
import { useMessages } from "@/i18n/LocaleProvider";

/**
 * What somebody sees between signing in and being allowed to do anything.
 *
 * <p>There are two states here that the product previously had no answer for, and both of them
 * arrived as the same thing: every screen empty, with "Authentication is required". That message is
 * true and useless. It describes a filter refusing a request; it says nothing about what happened
 * or what the person should do, and there is no path from it to a working account.
 *
 * <ul>
 *   <li><strong>Signed in and in no institution.</strong> Somebody who registered themselves. If
 *       their verified address belongs to a mapped domain they can join with one button. If it
 *       does not, they are told so plainly, because the honest answer is that their organisation is
 *       not on DIP or their address is a personal one.
 *   <li><strong>In an institution with no roles.</strong> Everybody, immediately after joining.
 *       Their administrator has to decide what they may do, and until then this page is the whole
 *       product — so it says who to ask rather than implying something is broken.
 * </ul>
 *
 * <p><strong>This is a usability boundary and not a security one.</strong> Nothing here decides
 * anything: the same account is refused by the server whether or not this component exists, and a
 * person who edits it out of their own browser gains exactly nothing. It exists so that the refusal
 * is legible.
 *
 * <p>It renders instead of the shell rather than inside it. A navigation menu whose every link
 * leads to a refusal is worse than no menu — it invites somebody to try each one and conclude the
 * product is broken.
 */
export function StandingGate({ children }: { children: React.ReactNode }) {
  const messages = useMessages();
  const t = messages.joining;
  const { signOut } = useSession();

  const [standing, setStanding] = useState<Standing | null>(null);
  const [failed, setFailed] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [joined, setJoined] = useState(false);

  const load = useCallback(async () => {
    try {
      setStanding(await usersApi.standing());
    } catch {
      // An older backend has no such endpoint, and a person who cannot ask this question should
      // not be blocked by it. Falling through to the application is right: if they have no access
      // the server still refuses them, exactly as it did before this component existed.
      setStanding({
        member: true,
        verified: true,
        hasEmail: true,
        joinable: false,
        hasAccess: true,
      });
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function join() {
    setBusy(true);
    setFailed(null);
    try {
      await usersApi.join();
      setJoined(true);
    } catch (caught) {
      setFailed(caught instanceof ApiError ? caught.message : String(caught));
    } finally {
      setBusy(false);
    }
  }

  // Nothing is drawn until the answer is known. Showing the application and then replacing it a
  // moment later would be a flash of somebody else's product.
  if (standing === null) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <p className="text-muted">{messages.common.loading}</p>
      </div>
    );
  }

  if (standing.member && standing.hasAccess) {
    return <>{children}</>;
  }

  // Joining changes an attribute at the identity provider, and the tenant travels in the access
  // token — which was minted before that. Nothing in the application will work until the token is
  // replaced, so the only honest next step is to sign in again, and saying anything else would
  // send somebody into a loop of empty screens.
  if (joined) {
    return (
      <Notice title={t.joinedTitle} body={t.joinedBody}>
        <Action onClick={() => void signOut()}>{t.signInAgain}</Action>
      </Notice>
    );
  }

  if (standing.member) {
    return <Notice title={t.pendingTitle} body={t.pendingBody} />;
  }

  if (!standing.hasEmail) {
    return <Notice title={t.noAddressTitle} body={t.noAddressBody} />;
  }

  if (!standing.verified) {
    return <Notice title={t.unverifiedTitle} body={t.unverifiedBody} />;
  }

  if (!standing.joinable) {
    return <Notice title={t.noOrganisationTitle} body={t.noOrganisationBody} />;
  }

  return (
    <Notice title={t.joinTitle} body={t.joinBody} failure={failed}>
      <Action onClick={() => void join()} disabled={busy}>
        {busy ? messages.common.loading : t.joinAction}
      </Action>
    </Notice>
  );
}

/** One card, centred, in the same clothes as the sign-in prompt this sits directly behind. */
function Notice({
  title,
  body,
  failure,
  children,
}: {
  title: string;
  body: string;
  failure?: string | null;
  children?: React.ReactNode;
}) {
  const messages = useMessages();

  return (
    <div className="flex min-h-screen items-center justify-center p-6">
      <div className="w-full max-w-md rounded-lg border border-line bg-white p-10 text-center">
        <div className="mb-5 flex items-center justify-center gap-2">
          <BrandMark size={30} />
          <span className="text-xl font-bold text-navy">{messages.app.name}</span>
        </div>
        <h1 className="mb-3 text-lg font-bold text-ink">{title}</h1>
        <p className="mb-7 text-sm leading-relaxed text-muted">{body}</p>
        {/* The same clothes as ErrorNotice, written out rather than imported: this file renders
            outside the shell and outside the design system's Card, and pulling in one component
            from it would drag the rest of that context with it. */}
        {failure && (
          <p
            role="alert"
            className="mb-5 rounded border border-error/40 bg-error/10 px-4 py-3 text-left text-sm text-[#7f1d1d]"
          >
            {failure}
          </p>
        )}
        {children}
        <p className="mt-6 text-xs text-muted">{messages.app.platform}</p>
      </div>
    </div>
  );
}

function Action({
  onClick,
  disabled,
  children,
}: {
  onClick: () => void;
  disabled?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="w-full rounded bg-blue px-5 py-3 text-sm font-bold text-white transition hover:bg-blue-dark disabled:opacity-50"
    >
      {children}
    </button>
  );
}
