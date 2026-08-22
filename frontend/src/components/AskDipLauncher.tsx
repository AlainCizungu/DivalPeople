"use client";

import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";
import { AskDip } from "@/components/AskDip";
import { AskDipMark } from "@/components/ask/AskDipMark";

/** The dedicated page. A floating shortcut to the screen you are on is clutter, not help. */
const ANALYST_PAGE = "/app/analyst";

/**
 * The analyst, reachable from every screen without leaving the one you are on.
 *
 * <p>Bottom right, closed by default. The questions it answers are the ones somebody thinks of
 * <em>while</em> looking at something else — reading a portfolio and wondering what changed this
 * week, working a queue and wondering who to chase. Making them navigate away to ask is how a
 * feature ends up used once during the demo.
 *
 * <p>Closed by default and never auto-opening, which is the difference between an assistant and an
 * interruption. It also covers part of the screen while open, so it opens because somebody asked
 * for it and shuts on Escape.
 *
 * <p>The navigation no longer carries an entry for it. A menu item and a permanent button in the
 * corner are two doors to one room, and the menu item was the one that made people leave the screen
 * they were asking about.
 *
 * <p>The page at {@code /app/analyst} still exists and still renders the same component, unlinked —
 * a long answer deserves the width, and a bookmark should not break. When somebody is on it, this
 * launcher gets out of the way rather than floating a shortcut to the screen they are looking at.
 */
export function AskDipLauncher() {
  const t = useMessages().ask;
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const panel = useRef<HTMLDivElement>(null);

  // Escape closes it. A panel that covers the corner of the screen and can only be dismissed by
  // finding a small button is a panel people leave open and then resent.
  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [open]);

  // Focus the question box on opening, so the keyboard lands where the user is already looking.
  useEffect(() => {
    if (open) panel.current?.querySelector("input")?.focus();
  }, [open]);

  // Hooks first, then the decision. Returning before useEffect would change the hook order
  // between renders the moment somebody navigates onto the analyst page, which React forbids.
  if (pathname === ANALYST_PAGE) {
    return null;
  }

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-expanded={false}
        className="fixed bottom-6 right-6 z-40 flex items-center gap-2.5 rounded-full bg-[linear-gradient(120deg,#0b1f3a_0%,#123a63_55%,#0a4f5c_100%)] py-2.5 pl-2.5 pr-5 text-sm font-semibold text-white shadow-lg transition hover:-translate-y-0.5 hover:shadow-xl"
      >
        <AskDipMark size={28} />
        {t.title}
      </button>
    );
  }

  return (
    <div
      ref={panel}
      role="dialog"
      aria-label={t.title}
      /* Tall answers scroll inside the panel rather than pushing it off the screen, and the width
         is capped so it never becomes a second page. */
      className="fixed bottom-6 right-6 z-40 flex max-h-[calc(100vh-3rem)] w-[min(28rem,calc(100vw-3rem))] flex-col overflow-y-auto rounded-lg border border-line bg-white shadow-xl"
    >
      {/* The panel's own header, in the assistant's colours rather than the page's. It is the one
          surface on screen that is not part of whatever you were reading, and looking different is
          how it says so. */}
      <div className="sticky top-0 z-10 flex items-center justify-between gap-3 bg-[linear-gradient(120deg,#0b1f3a_0%,#123a63_55%,#0a4f5c_100%)] px-4 py-3 text-white">
        <span className="flex items-center gap-2.5 text-sm font-bold">
          <AskDipMark size={26} />
          {t.title}
        </span>
        <button
          type="button"
          onClick={() => setOpen(false)}
          aria-label={t.close}
          className="rounded px-2 py-1 text-lg leading-none text-white/70 transition hover:bg-white/10 hover:text-white"
        >
          ×
        </button>
      </div>

      <div className="p-1">
        <AskDip bare />
      </div>
    </div>
  );
}
