"use client";

import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import { useSession } from "@/auth/SessionProvider";
import {
  buildNavigation,
  type GroupId,
  type ItemId,
  type NavGroup,
  type NavItem,
} from "@/components/navigation";

/**
 * Everything DIP contains, arranged the way it is arranged.
 *
 * <p>The front door showed what was waiting and what had been declared, which is what somebody
 * who already knows this platform wants. Somebody who does not — and on the morning of a
 * demonstration that is everybody — could see no shape at all: seven areas of work were legible
 * only by reading the left menu top to bottom, where the grouping is a heading in small grey
 * capitals and the relationship between "Data management" and "Data import" is expressed as four
 * pixels of indentation.
 *
 * <p><strong>Built from the same list the menu is built from.</strong> Not a second copy. A
 * hand-written directory would be wrong the first time a screen was added, and wrong in the worst
 * way — silently, on the page whose whole job is to say what exists. {@link buildNavigation} is
 * the single source, and because every entry carries an id, the blurb map below fails to compile
 * when an entry is added without one.
 *
 * <p><strong>No green, amber or red.</strong> Colour means severity on every other screen here —
 * an overdue deadline, a material change, an obligation past its retention date. Seven groups
 * needing seven distinguishable accents is not a reason to spend the vocabulary that carries
 * urgency, so the palette is blue through navy and nothing in it means anything is wrong.
 *
 * <p>Overview is dropped. It is this page, and a directory whose first tile is the tile you are
 * standing on teaches nothing.
 */

/** Blue through navy, and deliberately no part of the severity palette. */
const ACCENT: Record<GroupId, { line: string; tint: string; ink: string }> = {
  intelligence: { line: "#1f6feb", tint: "#eaf2ff", ink: "#0d47a1" },
  subjects: { line: "#0a7f8c", tint: "#e6f6f8", ink: "#075f69" },
  risk: { line: "#5b4bd6", tint: "#eeecfd", ink: "#3f34a0" },
  network: { line: "#7a3fa8", tint: "#f5ecfb", ink: "#5b2f7e" },
  data: { line: "#0b6b8f", tint: "#e7f3f9", ink: "#08506c" },
  governance: { line: "#0b1f3a", tint: "#e9edf3", ink: "#0b1f3a" },
  system: { line: "#4a5568", tint: "#eef0f3", ink: "#374151" },
};

/** One stroke glyph per area. Decorative — every tile is also labelled in words. */
const GLYPH: Record<GroupId, string> = {
  intelligence: "M3 17l5-6 4 4 6-8M3 21h18",
  subjects: "M8 11a3.5 3.5 0 100-7 3.5 3.5 0 000 7zM2 20a6 6 0 0112 0M16 20a5 5 0 016-4.8",
  risk: "M12 3l8 3.5v5c0 4.5-3.2 8.4-8 9.5-4.8-1.1-8-5-8-9.5v-5L12 3zM12 8.5v4M12 16h.01",
  network: "M12 3v5m0 8v5M4.5 7.5l3.5 2m8 5l3.5 2m0-9l-3.5 2m-8 5l-3.5 2M12 14a2 2 0 100-4 2 2 0 000 4z",
  data: "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3",
  governance: "M12 3l8 4v2H4V7l8-4zM6 11v6M12 11v6M18 11v6M3 20h18",
  system: "M12 15a3 3 0 100-6 3 3 0 000 6zM19.4 15a1.7 1.7 0 00.3 1.9l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-2.9 1.2V21a2 2 0 11-4 0v-.1A1.7 1.7 0 004.6 19l-.1.1a2 2 0 11-2.8-2.8l.1-.1A1.7 1.7 0 003 13.4H3a2 2 0 110-4h.1A1.7 1.7 0 004.9 6.6l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.9.3H10a2 2 0 114 0v.1a1.7 1.7 0 002.9 1.2l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.9v.1a2 2 0 110 4h-.1z",
};

export function Directory() {
  const messages = useMessages();
  const t = messages.dashboard.directory;
  const { profile } = useSession();

  /**
   * What each screen answers, in one line.
   *
   * <p>Typed so that every id but Overview must appear. Each screen already carries a subtitle,
   * and reusing those was the first attempt: it guarantees the two can never disagree. It was
   * wrong. A page subtitle is written for somebody who has arrived and needs the caveats — "never
   * the amount, the service, or your name" — and a directory needs the sentence that gets them to
   * arrive. They are different sentences with the same duty of accuracy, and nothing here claims
   * anything the screen behind it does not do.
   */
  const blurb: Record<Exclude<ItemId, "home">, string> = {
    search: t.search,
    executive: t.executive,
    records: t.records,
    profile360: t.profile360,
    inquiries: t.inquiries,
    declare: t.declare,
    subjectRequests: t.subjectRequests,
    portfolio: t.portfolio,
    fraud: t.fraud,
    watchlists: t.watchlists,
    monitoring: t.monitoring,
    participants: t.participants,
    imports: t.imports,
    entityResolution: t.entityResolution,
    audit: t.audit,
    access: t.access,
    organization: t.organization,
    settings: t.settings,
  };

  const groups = buildNavigation(messages.nav, {
    isPlatformAdmin: profile?.roles.includes("PLATFORM_ADMIN") ?? false,
  })
    .map((group) => ({ ...group, items: group.items.filter((item) => item.id !== "home") }))
    .filter((group) => group.items.length > 0);

  return (
    // Furthest from the top and separated by the widest gap on the page. This is a reference, not
    // a queue: nothing here is waiting on anybody, and it should be the last thing the eye reaches.
    <section className="mt-16 border-t border-line pt-10">
      <h2 className="text-xs font-semibold tracking-[0.16em] text-muted uppercase">
        {t.title}
      </h2>
      <p className="mt-1 mb-4 text-sm text-muted">{t.subtitle}</p>

      <div className="grid gap-5 md:grid-cols-2 xl:grid-cols-3">
        {groups.map((group) => (
          <GroupCard key={group.id} group={group} blurb={blurb} />
        ))}
      </div>
    </section>
  );
}

function GroupCard({
  group,
  blurb,
}: {
  group: NavGroup;
  blurb: Record<Exclude<ItemId, "home">, string>;
}) {
  const accent = ACCENT[group.id];

  return (
    <section
      className="flex flex-col overflow-hidden rounded-lg border border-line bg-white transition hover:shadow-md"
      style={{ borderTop: `3px solid ${accent.line}` }}
    >
      <header className="flex items-center gap-3 px-5 pt-4 pb-3">
        <span
          aria-hidden="true"
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg"
          style={{ background: accent.tint }}
        >
          <svg
            width="18"
            height="18"
            viewBox="0 0 24 24"
            fill="none"
            stroke={accent.line}
            strokeWidth="1.7"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d={GLYPH[group.id]} />
          </svg>
        </span>
        <h3 className="text-sm font-bold tracking-wide" style={{ color: accent.ink }}>
          {group.heading}
        </h3>
        <span className="ml-auto text-xs text-muted tabular-nums">{group.items.length}</span>
      </header>

      <ul className="flex-1 border-t border-line">
        {group.items.map((item) => (
          <Entry key={item.id} item={item} accent={accent.line} blurb={blurb} />
        ))}
      </ul>
    </section>
  );
}

/**
 * One screen, under the area it belongs to.
 *
 * <p>The left edge is what carries the hierarchy: an entry is visibly inside its group, and the
 * accent only appears under the pointer, so a card at rest reads as a list rather than as seven
 * competing stripes.
 */
function Entry({
  item,
  accent,
  blurb,
}: {
  item: NavItem;
  accent: string;
  blurb: Record<Exclude<ItemId, "home">, string>;
}) {
  // Overview is filtered out before this renders, which is what makes the narrowing safe.
  const text = item.id === "home" ? null : blurb[item.id];

  if (!item.href) {
    // Designed, not built. No entry is today; kept because the shape must not quietly start
    // linking somewhere the day one is added.
    return (
      <li className="border-b border-line px-5 py-3 last:border-b-0">
        <p className="text-sm font-semibold text-muted">{item.label}</p>
        {text && <p className="mt-0.5 text-xs text-muted">{text}</p>}
      </li>
    );
  }

  return (
    <li className="border-b border-line last:border-b-0">
      <Link
        href={item.href}
        // The accent is a custom property so the hover and focus states can be plain CSS. Doing
        // it with mouse handlers worked and left the keyboard out, which is the usual cost of
        // reaching for JavaScript to colour something.
        className="group block border-l-[3px] border-l-transparent px-5 py-3 transition hover:border-l-[var(--accent)] hover:bg-soft focus-visible:border-l-[var(--accent)] focus-visible:bg-soft"
        style={{ "--accent": accent } as React.CSSProperties}
      >
        <span className="flex items-center justify-between gap-2">
          <span className="text-sm font-semibold text-navy transition group-hover:text-blue">
            {item.label}
          </span>
          <span
            aria-hidden="true"
            className="text-sm text-muted opacity-0 transition group-hover:translate-x-0.5 group-hover:opacity-100"
          >
            →
          </span>
        </span>
        {text && <span className="mt-0.5 block text-xs leading-relaxed text-muted">{text}</span>}
      </Link>
    </li>
  );
}
