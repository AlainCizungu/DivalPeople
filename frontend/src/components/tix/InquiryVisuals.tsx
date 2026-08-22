"use client";

import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import type { InquiryOutcome } from "@/api/client";

/**
 * The two pictures an inquiry result is worth drawing.
 *
 * <p>The verdict, in the colour of what it says, and the institution count as marks rather than a
 * numeral. Everything else on that screen was already honest and needed no help.
 */

/**
 * How each outcome is painted.
 *
 * <p>Four outcomes, four colours, and the two that are not green or red matter most. NO_MATCH is
 * grey because nothing was found and grey is what "nothing" looks like — painting it green would
 * turn "we have never heard of this company" into "this company is clear", which is the single
 * most expensive misreading available on this screen. REVIEW_REQUIRED is amber for the same
 * reason in the other direction: the exchange is not confident, and a confident colour would say
 * it was.
 */
const VERDICT: Record<
  InquiryOutcome,
  { band: string; ink: string; edge: string; glyph: string }
> = {
  CLEAR: {
    band: "linear-gradient(120deg,#0f5132 0%,#107c10 100%)",
    ink: "text-white",
    edge: "border-transparent",
    glyph: "✓",
  },
  OUTSTANDING_DEBT: {
    band: "linear-gradient(120deg,#7a1020 0%,#c50f1f 100%)",
    ink: "text-white",
    edge: "border-transparent",
    glyph: "!",
  },
  REVIEW_REQUIRED: {
    band: "linear-gradient(120deg,#7c4a03 0%,#d18700 100%)",
    ink: "text-white",
    edge: "border-transparent",
    glyph: "?",
  },
  NO_MATCH: {
    band: "linear-gradient(120deg,#3f4a57 0%,#5f6368 100%)",
    ink: "text-white",
    edge: "border-transparent",
    glyph: "–",
  },
};

/** The answer, at the size of the decision it is used for. */
export function VerdictBanner({ outcome }: { outcome: InquiryOutcome }) {
  const t = useMessages().tix;
  const look = VERDICT[outcome];

  return (
    <div
      className={`rounded-2xl border p-6 md:p-7 ${look.ink} ${look.edge}`}
      style={{ background: look.band }}
      role="status"
    >
      <div className="flex items-start gap-4">
        <span
          aria-hidden="true"
          className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-white/15 text-2xl font-bold"
        >
          {look.glyph}
        </span>
        <div>
          <p className="text-2xl font-bold md:text-3xl">{t.outcomes[outcome]}</p>
          <p className="mt-1.5 max-w-2xl text-sm text-white/80">
            {t.outcomeExplained[outcome]}
          </p>
        </div>
      </div>
    </div>
  );
}

/**
 * How many institutions report this subject, drawn as marks.
 *
 * <p>The one number the exchange discloses, and the picture makes the shape of the disclosure
 * visible: identical marks, no labels, no order that means anything. Three marks say three
 * institutions and refuse to say which, which is exactly what the number does — the drawing simply
 * cannot be misread as a list the way a table with three blank rows would be.
 *
 * <p>Capped, with the remainder as a numeral. Twenty marks would be a bar chart of one bar, and
 * counting them would take longer than reading the figure.
 */
export function InstitutionPips({ count }: { count: number }) {
  const t = useMessages().tix;
  const shown = Math.min(count, 8);
  const rest = count - shown;

  return (
    <div>
      <p className="text-xs text-muted">{t.reportedBy}</p>
      <div className="mt-2 flex items-center gap-1.5">
        {count === 0 ? (
          <span className="text-2xl font-bold tabular-nums text-muted">0</span>
        ) : (
          <>
            {Array.from({ length: shown }, (_, index) => (
              <span
                key={index}
                aria-hidden="true"
                className="h-6 w-6 rounded-md bg-blue"
                style={{ opacity: 1 - index * 0.06 }}
              />
            ))}
            {rest > 0 && (
              <span className="ml-1 text-lg font-bold tabular-nums text-navy">+{rest}</span>
            )}
            <span className="ml-2 text-2xl font-bold tabular-nums text-navy">{count}</span>
          </>
        )}
      </div>
      <p className="mt-1.5 text-xs text-muted">{t.reportedByNote}</p>
      {count > 0 && (
        <p className="mt-1 text-xs text-muted/80">
          {interpolate(t.pipsNote, t.pipsNote, { count: String(count) })}
        </p>
      )}
    </div>
  );
}
