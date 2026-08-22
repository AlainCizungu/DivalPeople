"use client";

import { useRef, type ReactNode } from "react";
import { useMessages } from "@/i18n/LocaleProvider";

/**
 * The question box, with the parts DIP will pick out marked as you type.
 *
 * <p>An amount gets a blue wash, a period gets a teal one. Before pressing ask, somebody can see
 * that "20,000" and "this week" were noticed — which is the difference between a box that feels
 * like a search field and one that feels like it is listening.
 *
 * <p><strong>Two colours and no more, and that is a correctness decision rather than restraint.</strong>
 * The server reads a question one of two ways: by rule, or — where a model is configured — by asking
 * the model to classify it. This highlighter can only mirror the rules. Amounts and periods are
 * marked because the rule reader definitely reads them and a model asked to classify would agree;
 * anything subtler, a company name or an intent, could be highlighted here and read differently
 * there, and a screen that showed a confident wrong reading before the answer arrived would be
 * worse than a plain box.
 *
 * <p>The authority stays where it was: the interpretation pill above the answer says what the
 * question was <em>actually</em> taken to mean, after the fact and from the server. This is a hint,
 * and the legend beneath calls it one.
 *
 * <p>Drawn as a layer behind the input rather than by colouring the input's own text. Transparent
 * text with a coloured overlay is the usual trick and it breaks in the places nobody tests —
 * selection, the caret, and every browser that renders them slightly differently. Here the real
 * text sits on top and unstyled; only the washes are underneath, so the worst a mismatch can do is
 * put a highlight half a pixel out.
 */

/** Numbers, with the separators a Congolese user might type. Mirrors the server's AMOUNT reader. */
const AMOUNT = /\b\d[\d\s.,]*\s*(?:k|m|000)?\b/gi;

/** Periods the rule reader turns into a window. Both languages, because the box takes both. */
const PERIOD =
  /\b(this week|this month|this quarter|recently|last \d+ days?|cette semaine|ce mois|ce trimestre|récemment|recemment|derniers? \d+ jours?)\b/gi;

type Mark = { start: number; end: number; kind: "amount" | "period" };

/**
 * Every match from both readers, non-overlapping.
 *
 * <p>Periods win where they collide. "last 30 days" contains a number, and washing the 30 as an
 * amount inside a phrase that is plainly a period would say the opposite of what the reader does
 * with it.
 */
export function marksIn(text: string): Mark[] {
  const found: Mark[] = [];
  for (const match of text.matchAll(PERIOD)) {
    if (match.index === undefined) continue;
    found.push({ start: match.index, end: match.index + match[0].length, kind: "period" });
  }
  for (const match of text.matchAll(AMOUNT)) {
    if (match.index === undefined) continue;
    const start = match.index;
    const end = start + match[0].length;
    const overlaps = found.some((mark) => start < mark.end && end > mark.start);
    if (!overlaps) found.push({ start, end, kind: "amount" });
  }
  return found.sort((left, right) => left.start - right.start);
}

function paint(text: string): ReactNode[] {
  const marks = marksIn(text);
  const out: ReactNode[] = [];
  let cursor = 0;
  marks.forEach((mark, index) => {
    if (mark.start > cursor) out.push(text.slice(cursor, mark.start));
    out.push(
      <span
        key={`${mark.start}-${index}`}
        className={`rounded ${
          mark.kind === "amount" ? "bg-blue/20" : "bg-teal/20"
        }`}
      >
        {text.slice(mark.start, mark.end)}
      </span>,
    );
    cursor = mark.end;
  });
  if (cursor < text.length) out.push(text.slice(cursor));
  return out;
}

export function QuestionField({
  value,
  onChange,
  placeholder,
  label,
  disabled = false,
}: {
  value: string;
  onChange: (next: string) => void;
  placeholder: string;
  label: string;
  disabled?: boolean;
}) {
  const t = useMessages().ask;
  const mirror = useRef<HTMLDivElement>(null);
  const marks = marksIn(value);

  return (
    <div className="flex-1">
      <div className="relative">
        {/* Behind, and identical in every property that affects where a glyph lands: font, size,
            tracking, padding, border width. A mirror that is off by one of them puts every wash a
            little further wrong along the line. */}
        <div
          ref={mirror}
          aria-hidden="true"
          className="pointer-events-none absolute inset-0 overflow-hidden rounded-lg border border-transparent px-3.5 py-2.5 text-sm leading-6 whitespace-pre text-transparent"
        >
          {paint(value)}
        </div>

        <input
          className="relative w-full rounded-lg border border-line bg-transparent px-3.5 py-2.5 text-sm leading-6 text-ink outline-none transition focus:border-blue focus:ring-2 focus:ring-blue/20"
          value={value}
          disabled={disabled}
          onChange={(event) => onChange(event.target.value)}
          // The mirror does not scroll on its own, so a question longer than the box would leave
          // its washes behind while the text moved.
          onScroll={(event) => {
            if (mirror.current) {
              mirror.current.scrollLeft = event.currentTarget.scrollLeft;
            }
          }}
          placeholder={placeholder}
          aria-label={label}
        />
      </div>

      {/* Only once something is actually marked. A legend explaining colours that are not on the
          screen is noise on every question that does not have them. */}
      {marks.length > 0 && (
        <p className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-muted">
          {marks.some((mark) => mark.kind === "amount") && (
            <span className="flex items-center gap-1.5">
              <span aria-hidden="true" className="h-2.5 w-4 rounded bg-blue/20" />
              {t.legendAmount}
            </span>
          )}
          {marks.some((mark) => mark.kind === "period") && (
            <span className="flex items-center gap-1.5">
              <span aria-hidden="true" className="h-2.5 w-4 rounded bg-teal/20" />
              {t.legendPeriod}
            </span>
          )}
          <span className="text-muted/70">{t.legendHint}</span>
        </p>
      )}
    </div>
  );
}
