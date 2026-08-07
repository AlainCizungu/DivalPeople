"use client";

import { useEffect, useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";

/**
 * Shows why a sign-in did not finish.
 *
 * <p>The callback has always redirected a failure to {@code /?authError=<reason>} — and until this
 * component existed, nothing anywhere read that parameter. Every way a sign-in could fail
 * therefore produced the same observable behaviour as a dead button: three redirects in a few
 * milliseconds and the landing page again, unchanged. The first real failure cost an afternoon
 * of looking at the button.
 *
 * <p>A failure path that writes a reason nobody reads is worse than one that writes nothing,
 * because it looks handled.
 *
 * <p>The parameter is read from {@code window.location} in an effect rather than through
 * {@code useSearchParams}, which would drag a Suspense boundary onto a purely decorative banner.
 * Only the reasons below are rendered, by lookup — the value arrives from a redirect the identity
 * provider influences, and reflecting it into the page would be a small XSS waiting for a
 * careless {@code dangerouslySetInnerHTML} later.
 */
const REASONS = ["provider", "incomplete", "expired", "state", "exchange"] as const;

type Reason = (typeof REASONS)[number];

function isReason(value: string | null): value is Reason {
  return value !== null && (REASONS as readonly string[]).includes(value);
}

export function AuthErrorNotice() {
  const messages = useMessages();
  const [reason, setReason] = useState<Reason | null>(null);

  useEffect(() => {
    const found = new URLSearchParams(window.location.search).get("authError");
    if (!isReason(found)) {
      return;
    }
    setReason(found);
    // Taken out of the address bar so a refresh, or a link shared from it, does not resurrect a
    // failure that has since been fixed. replaceState rather than a router push: this is not a
    // navigation and it should not add a history entry to go "back" to.
    const url = new URL(window.location.href);
    url.searchParams.delete("authError");
    window.history.replaceState({}, "", url.toString());
  }, []);

  if (!reason) {
    return null;
  }

  const copy = messages.landing.authError;

  return (
    <div
      role="alert"
      className="border-b border-[#f3c9c9] bg-[#fdf2f2] px-6 py-3.5 text-sm text-[#8a1c1c]"
    >
      <div className="mx-auto flex max-w-7xl items-start justify-between gap-4">
        <p>
          <strong className="font-bold">{copy.title}</strong>{" "}
          <span>{copy.reasons[reason]}</span>
        </p>
        <button
          type="button"
          onClick={() => setReason(null)}
          className="shrink-0 font-bold underline"
        >
          {copy.dismiss}
        </button>
      </div>
    </div>
  );
}
