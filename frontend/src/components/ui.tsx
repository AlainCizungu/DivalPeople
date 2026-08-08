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
}: {
  title?: string;
  description?: string;
  children: ReactNode;
  footer?: ReactNode;
}) {
  return (
    <section className="rounded-lg border border-line bg-white">
      {(title || description) && (
        <div className="border-b border-line px-5 py-4">
          {title && <h2 className="font-bold text-navy">{title}</h2>}
          {description && <p className="mt-0.5 text-sm text-muted">{description}</p>}
        </div>
      )}
      <div className="p-5">{children}</div>
      {footer && <div className="border-t border-line bg-soft px-5 py-3">{footer}</div>}
    </section>
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
  tone?: "plain" | "warning";
}) {
  return (
    <div className="rounded-lg border border-line bg-white p-5">
      <p className="text-sm text-muted">{label}</p>
      <p
        className={`mt-1 text-3xl font-bold tabular-nums ${
          tone === "warning" ? "text-[#b45309]" : "text-navy"
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
  ...props
}: React.ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "quiet";
}) {
  const base =
    "rounded px-4 py-2.5 text-sm font-bold transition disabled:cursor-not-allowed disabled:opacity-50";
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
