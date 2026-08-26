"use client";

import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import { useSession } from "@/auth/SessionProvider";
import { interpolate } from "@/i18n/interpolate";
import { CountUp } from "@/components/visual/motion";
import type { Network } from "@/api/client";

/**
 * The exchange, on the one screen that was only ever about the caller.
 *
 * <p>Every other figure on this page counts the operator's own book. DIP's argument is that one
 * institution cannot see what several can — and the platform's front door had no way to say so,
 * which meant the differentiator was legible only to somebody who already understood the product.
 *
 * <p><strong>Nothing here is illustrative.</strong> On a new deployment this strip reads two
 * institutions and a handful of subjects, and that is the correct behaviour rather than a thing
 * to be papered over: the figures are counted from the same rows an inquiry would reach, so they
 * grow exactly as the network does. A strip that opened at an impressive number would be the one
 * element on a platform arguing its figures can be checked that could not be.
 *
 * <p><strong>Counts, never names.</strong> The response has nowhere to put an operator's identity
 * — {@code Network} is six numbers — and the riskiest of them, subjects owing two or more
 * operators, is aggregated inside Postgres so the subject-to-operator pairing never leaves the
 * database. The line under the strip says this out loud, because an operator reading "21,000
 * shared subjects" is entitled to wonder what else the platform is willing to publish about it.
 *
 * <p>Navy on white rather than a dark panel. The spotlight above is already the heavy element and
 * two of them would compete; the strip earns its weight from figure size and one accent.
 */

const ACCENT = "#0b1f3a";

export function NetworkStrip({ network }: { network: Network }) {
  const t = useMessages().dashboard.network;
  const { profile } = useSession();
  const isPlatformAdmin = profile?.roles.includes("PLATFORM_ADMIN") ?? false;

  /**
   * Five figures, in the order somebody meets the idea.
   *
   * <p>Who is in it, what it covers, how big it is, what it found, what it did today. The fourth
   * is the one worth pausing on and is placed where the eye lands after the two it needs for
   * context.
   */
  const figures: {
    key: string;
    label: string;
    value: number;
    note: string;
    /** Rendered instead of the number when the platform is not recording the input yet. */
    absent?: boolean;
  }[] = [
    {
      key: "institutions",
      label: t.institutions,
      value: network.institutions,
      note: interpolate(t.contributingNote, t.contributingNote, {
        count: String(network.contributing),
      }),
    },
    {
      key: "sectors",
      label: t.sectors,
      value: network.sectors,
      // Zero here means "no operator has mapped a sector column", not "the network covers no
      // industries". A bare 0 says the second, which is false and unflattering in the same
      // breath, so the tile shows a dash and explains itself.
      absent: network.sectors === 0,
      note: network.sectors === 0 ? t.sectorsAbsent : t.sectorsNote,
    },
    {
      key: "subjects",
      label: t.subjects,
      value: network.subjects,
      note: t.subjectsNote,
    },
    {
      key: "shared",
      label: t.shared,
      value: network.sharedSubjects,
      note: t.sharedNote,
    },
    {
      key: "today",
      label: t.today,
      value: network.declaredToday,
      note: t.todayNote,
    },
  ];

  return (
    <section className="mt-16">
      <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
        <div>
          <div className="flex items-center gap-2.5">
            <span
              aria-hidden="true"
              className="h-3.5 w-1 rounded-full"
              style={{ background: ACCENT }}
            />
            <h2 className="text-xs font-semibold tracking-[0.16em] text-muted uppercase">
              {t.title}
            </h2>
          </div>
          <p className="mt-1.5 max-w-2xl text-sm text-muted">{t.subtitle}</p>
        </div>
        {/* Only for whoever runs the network.
            This link shipped ungated and sent every operator to a screen that refuses them: the
            participants list is PLATFORM_ADMIN, and both the menu and the directory already knew
            that — they are built from buildNavigation, which drops the entry. This was the one
            link on the platform written by hand, and it went straight past the rule the shared
            list exists to enforce.

            No substitute link when the caller is not an administrator. The obvious candidates —
            the inquiry, the exchange — are already on this page above, and an action invented to
            fill the space would be worse than a heading without one. */}
        {isPlatformAdmin && (
          <Link href="/app/participants" className="text-sm font-bold text-blue hover:underline">
            {t.action} →
          </Link>
        )}
      </div>

      <div
        className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-line bg-line md:grid-cols-3 xl:grid-cols-5"
        style={{ borderTop: `3px solid ${ACCENT}` }}
      >
        {figures.map((figure) => (
          <div key={figure.key} className="bg-white p-5">
            <p className="text-xs font-semibold tracking-wide text-muted uppercase">
              {figure.label}
            </p>
            <p className="mt-1.5 text-4xl font-bold text-navy">
              {figure.absent ? (
                <span className="text-muted">—</span>
              ) : (
                <CountUp value={figure.value} />
              )}
            </p>
            <p className="mt-1.5 text-xs leading-relaxed text-muted">{figure.note}</p>
          </div>
        ))}
      </div>

      {/* The caveat belongs with the figures rather than in a help page. Somebody reading a count
          of shared subjects is being told something about the other participants, and is owed the
          limit of that in the same glance. */}
      <p className="mt-3 text-xs text-muted">{t.boundary}</p>
    </section>
  );
}
