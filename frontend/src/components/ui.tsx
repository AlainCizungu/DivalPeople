"use client";

/**
 * Shared pieces for the authenticated application.
 *
 * <p>Follows `docs/UI_DESIGN_SYSTEM.md`: white surfaces, navy for trust, blue for primary
 * actions, teal/green for verified, amber for review, red only for something serious. The system
 * explicitly warns off gradients and decorative dashboards, so there are none here — the cards
 * are flat, bordered and quiet, and the only colour carries meaning.
 */

import type { ReactNode } from "react";

export function PageHeader({
  title,
  subtitle,
  action,
}: {
  title: string;
  subtitle?: string;
  action?: ReactNode;
}) {
  return (
    <header className="mb-6 flex flex-wrap items-start justify-between gap-4">
      <div>
        <h1 className="text-3xl font-bold tracking-tight text-navy">{title}</h1>
        {subtitle && <p className="mt-1 max-w-2xl text-muted">{subtitle}</p>}
      </div>
      {action}
    </header>
  );
}

export function Card({
  title,
  description,
  children,
  footer,
  accent,
  emphasis = false,
  action,
}: {
  title?: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
  /**
   * One colour for this card, drawn as a rule along the top.
   *
   * <p>The same device the directory uses for its seven areas, and the same restraint applies:
   * this is identity, not severity. Passing a red here would say "this card is the red one" on a
   * platform where red means a deadline has been missed, so callers pass blues, teals and navy
   * and let the figures inside spend the severity palette.
   */
  accent?: string;
  /**
   * Whether this card outranks the ones beside it.
   *
   * <p>A shadow and a slightly darker edge, and nothing else — the difference has to survive
   * being seen out of the corner of an eye, and has to not look like a second kind of card.
   */
  emphasis?: boolean;
  /** A link in the header, opposite the title. "Open the register", "Investigate". */
  action?: ReactNode;
}) {
  return (
    <section
      className={`overflow-hidden rounded-lg border bg-white ${
        emphasis ? "border-line/80 shadow-sm" : "border-line"
      }`}
      style={accent ? { borderTop: `3px solid ${accent}` } : undefined}
    >
      {(title || description || action) && (
        <div className="flex items-start justify-between gap-4 border-b border-line px-5 py-4">
          <div>
            {title && <h2 className="text-lg font-bold text-navy">{title}</h2>}
            {description && <p className="mt-0.5 text-[0.9375rem] text-muted">{description}</p>}
          </div>
          {action && <div className="shrink-0 pt-0.5">{action}</div>}
        </div>
      )}
      <div className="p-5">{children}</div>
      {footer && <div className="border-t border-line bg-soft px-5 py-3">{footer}</div>}
    </section>
  );
}

/**
 * The line that starts a tier of the page.
 *
 * <p><strong>On the sizes here and in Card.</strong> Both were a step smaller until somebody said
 * the overview read small, and they were: 12px uppercase named a whole tier of a page while the
 * figures under it were 48px, and a supporting note sat at the same 14px as a table cell. The
 * scale moved one step — nothing doubled, nothing became a heading that was not one — and it moved
 * in the shared primitive rather than on the overview alone. A second type scale that applies to
 * one screen is how a product ends up with two, and the screens either side of the front door are
 * built from these same two components.
 *
 * <p>The overview had four sections and one of them had a heading. Everything else began by simply
 * being the next thing, which is why the page reads as one long list of equally important boxes —
 * whitespace alone cannot say "this group is subordinate to that one", it can only say "these are
 * apart".
 *
 * <p>The accent rule is the second half of it. One colour per section, carried nowhere else in
 * that section, so a reader who has scrolled knows which tier they are in without re-reading the
 * heading. Chosen from blue through navy for the same reason the directory's are: severity owns
 * green, amber and red on every other screen here, and a heading is never severe.
 */
export function SectionHeading({
  title,
  note,
  accent,
  action,
}: {
  title: string;
  note?: string;
  accent: string;
  action?: ReactNode;
}) {
  return (
    <div className="mb-4 flex flex-wrap items-end justify-between gap-3">
      <div>
        <div className="flex items-center gap-2.5">
          <span
            aria-hidden="true"
            className="h-3.5 w-1 rounded-full"
            style={{ background: accent }}
          />
          <h2 className="text-[0.8125rem] font-semibold tracking-[0.14em] text-muted uppercase">{title}</h2>
        </div>
        {note && <p className="mt-1.5 max-w-2xl text-[0.9375rem] leading-relaxed text-muted">{note}</p>}
      </div>
      {action}
    </div>
  );
}

/**
 * A metric.
 *
 * <p>`value` is a string so that "—" is as legitimate as "12". A dashboard that cannot say "I do
 * not know this yet" ends up saying zero instead, and zero is a claim.
 */
export function Metric({
  label,
  value,
  note,
  tone = "plain",
}: {
  label: string;
  value: string;
  note?: string;
  /**
   * Three levels, because two were not enough to be honest with.
   *
   * `warning` is "worth a look". `serious` is for the two figures that mean something is wrong
   * rather than merely notable: a rights case past a statutory deadline, and records still here
   * after their retention period ended. Pill has carried the same word for a while; the metric
   * simply had no way to say it, so those numbers were rendering as amber alongside a delivery
   * somebody had not got round to.
   */
  tone?: "plain" | "warning" | "serious";
}) {
  return (
    <div className="rounded-lg border border-line bg-white p-5">
      <p className="text-[0.9375rem] text-muted">{label}</p>
      <p
        className={`mt-1 text-3xl font-bold tabular-nums ${
          tone === "serious"
            ? "text-[#b91c1c]"
            : tone === "warning"
              ? "text-[#b45309]"
              : "text-navy"
        }`}
      >
        {value}
      </p>
      {note && <p className="mt-1 text-xs text-muted">{note}</p>}
    </div>
  );
}

export type Tone = "neutral" | "positive" | "review" | "serious";

const TONE_CLASSES: Record<Tone, string> = {
  neutral: "border-line bg-soft text-ink",
  positive: "border-green/40 bg-green/10 text-[#14532d]",
  review: "border-warning/50 bg-warning/10 text-[#7c4a03]",
  serious: "border-error/40 bg-error/10 text-[#7f1d1d]",
};

export function Pill({ tone = "neutral", children }: { tone?: Tone; children: ReactNode }) {
  return (
    <span
      className={`inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-semibold ${TONE_CLASSES[tone]}`}
    >
      {children}
    </span>
  );
}

export function Field({
  label,
  hint,
  htmlFor,
  children,
}: {
  label: string;
  hint?: string;
  htmlFor?: string;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={htmlFor} className="text-sm font-semibold text-ink">
        {label}
      </label>
      {children}
      {hint && <p className="text-xs text-muted">{hint}</p>}
    </div>
  );
}

export const inputClass =
  "w-full rounded border border-line bg-white px-3 py-2.5 text-sm text-ink " +
  "focus:border-blue focus:ring-1 focus:ring-blue focus:outline-none";

export function Button({
  children,
  variant = "primary",
  size = "normal",
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "quiet";
  /**
   * "lead" for the one control a page exists for.
   *
   * <p>Added rather than letting the overview hand-roll a bigger button beside a search field it
   * had also hand-rolled. Two button styles in a product is how a product ends up with five, and
   * the second one always starts as a reasonable exception on one screen.
   *
   * <p>Deliberately one extra size and not a scale. There is the ordinary button and there is the
   * one at the front door; a set of five would be a set somebody has to choose from.
   */
  size?: "normal" | "lead";
}) {
  const base =
    size === "lead"
      ? "rounded-lg px-7 py-4 text-base font-bold transition disabled:cursor-not-allowed disabled:opacity-40"
      : "rounded px-4 py-2.5 text-sm font-bold transition disabled:cursor-not-allowed disabled:opacity-50";
  const variants = {
    primary: "bg-blue text-white hover:bg-blue-dark",
    secondary: "border border-ink text-ink hover:bg-soft",
    quiet: "text-blue hover:underline",
  };
  return (
    <button {...props} className={`${base} ${variants[variant]}`}>
      {children}
    </button>
  );
}

/**
 * An error the user can act on.
 *
 * <p>The API's message is shown rather than replaced with something generic. A declaration
 * refused for being below the reporting threshold, or for colliding with an open record, is only
 * useful if the operator can read which rule refused it — that is why the backend puts an
 * actionable sentence in `PolicyRefusedException` and `ConflictException` in the first place.
 */
export function ErrorNotice({ children }: { children: ReactNode }) {
  return (
    <p
      role="alert"
      className="rounded border border-error/40 bg-error/10 px-4 py-3 text-sm text-[#7f1d1d]"
    >
      {children}
    </p>
  );
}

export function EmptyState({ children }: { children: ReactNode }) {
  return (
    <p className="rounded border border-dashed border-line bg-soft px-4 py-8 text-center text-sm text-muted">
      {children}
    </p>
  );
}
