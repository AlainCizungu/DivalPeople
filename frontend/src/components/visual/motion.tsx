"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";

/**
 * Movement, and the one rule all of it obeys.
 *
 * <p>Lives outside {@code components/dashboard} because it stopped being the dashboard's: the
 * search screen draws the same counters and the same aged strips, and two copies of an animation
 * drift until one of them forgets to check the motion preference.
 *
 * <p><strong>Every animation here checks {@code prefers-reduced-motion} and renders its final
 * state instantly when it is set.</strong> That is an accessibility setting people turn on because
 * motion makes them ill — vestibular disorders, migraine, motion sensitivity — and a counter that
 * spins or a band that slides is exactly what it is asking us not to do. It is not a preference to
 * honour when convenient: a dashboard somebody has to look at every morning is the worst place to
 * ignore it.
 *
 * <p>The second rule is that nothing here invents a number. A count-up animates toward a figure the
 * server computed; a ring is drawn from the same counts printed beside it. Motion is how the figure
 * arrives, never what the figure is.
 */

/** True when the person has asked their system for less movement. */
export function useReducedMotion(): boolean {
  // Starts true, which is the safe default: the first paint on a machine that wants no motion must
  // not be the one frame of movement we then stop. Corrected on mount, before anything animates.
  const [reduced, setReduced] = useState(true);

  useEffect(() => {
    const query = window.matchMedia("(prefers-reduced-motion: reduce)");
    setReduced(query.matches);
    const onChange = (event: MediaQueryListEvent) => setReduced(event.matches);
    query.addEventListener("change", onChange);
    return () => query.removeEventListener("change", onChange);
  }, []);

  return reduced;
}

/**
 * A number that counts up to its value.
 *
 * <p>Eased rather than linear, because a linear count reads like a loading bar and an eased one
 * reads like an arrival. Short — under a second — since the figure is the point and the animation
 * is not.
 *
 * <p>`tabular-nums` is not optional here. Without it the digits change width as they climb and the
 * whole row jitters, which turns a nice effect into the thing somebody files a bug about.
 */
export function CountUp({ value, className = "" }: { value: number; className?: string }) {
  const reduced = useReducedMotion();
  const [shown, setShown] = useState(value);
  const frame = useRef<number | null>(null);

  useEffect(() => {
    if (reduced) {
      setShown(value);
      return;
    }
    const from = 0;
    const started = performance.now();
    const duration = 700;

    const step = (now: number) => {
      const progress = Math.min(1, (now - started) / duration);
      // easeOutCubic: fast to begin with, settling rather than stopping.
      const eased = 1 - Math.pow(1 - progress, 3);
      setShown(Math.round(from + (value - from) * eased));
      if (progress < 1) {
        frame.current = requestAnimationFrame(step);
      }
    };

    frame.current = requestAnimationFrame(step);
    return () => {
      if (frame.current !== null) cancelAnimationFrame(frame.current);
    };
  }, [value, reduced]);

  return <span className={`tabular-nums ${className}`}>{shown}</span>;
}

/**
 * The register as a ring: outstanding, contested, settled.
 *
 * <p>A ring rather than a pie because the middle is worth more than the extra wedge — the total
 * goes there, so the reader gets the composition and the size in one glance instead of doing
 * arithmetic across a legend.
 *
 * <p>Drawn with stroke offsets on one circle rather than as arc paths. Arc maths gets the sweep
 * flag wrong at exactly one boundary — a segment at or past half the circle — and the bug is
 * invisible until a real book happens to be shaped that way.
 *
 * <p>Segments that round to nothing are dropped rather than drawn as a hairline, and the caption
 * beside the ring still counts them. A one-pixel wedge is a decoration that looks like data.
 */
export function Ring({
  segments,
  total,
  caption,
}: {
  segments: { label: string; value: number; colour: string }[];
  total: number;
  caption: string;
}) {
  const reduced = useReducedMotion();
  const radius = 52;
  const circumference = 2 * Math.PI * radius;
  const sum = segments.reduce((running, segment) => running + segment.value, 0);

  let consumed = 0;
  const drawn = segments
    .filter((segment) => segment.value > 0)
    .map((segment) => {
      const share = sum === 0 ? 0 : segment.value / sum;
      const length = share * circumference;
      const offset = consumed;
      consumed += length;
      return { ...segment, length, offset };
    });

  return (
    <div className="flex items-center gap-6">
      <svg width="128" height="128" viewBox="0 0 128 128" role="img" aria-label={caption}>
        <circle
          cx="64"
          cy="64"
          r={radius}
          fill="none"
          stroke="var(--color-line)"
          strokeWidth="14"
        />
        {drawn.map((segment) => (
          <circle
            key={segment.label}
            cx="64"
            cy="64"
            r={radius}
            fill="none"
            stroke={segment.colour}
            strokeWidth="14"
            strokeLinecap="butt"
            strokeDasharray={`${segment.length} ${circumference - segment.length}`}
            strokeDashoffset={-segment.offset}
            // Rotated so the ring starts at twelve o'clock rather than at three.
            transform="rotate(-90 64 64)"
            style={reduced ? undefined : { transition: "stroke-dasharray 600ms ease-out" }}
          />
        ))}
        <text
          x="64"
          y="60"
          textAnchor="middle"
          className="fill-navy text-2xl font-bold"
          style={{ fontSize: "22px" }}
        >
          {total}
        </text>
        <text
          x="64"
          y="78"
          textAnchor="middle"
          className="fill-muted"
          style={{ fontSize: "10px" }}
        >
          {caption}
        </text>
      </svg>

      <ul className="flex flex-col gap-2 text-sm">
        {segments.map((segment) => (
          <li key={segment.label} className="flex items-center gap-2">
            <span
              aria-hidden="true"
              className="h-2.5 w-2.5 shrink-0 rounded-full"
              style={{ background: segment.colour }}
            />
            <span className="text-muted">{segment.label}</span>
            <span className="ml-auto pl-4 font-bold tabular-nums text-navy">{segment.value}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Thirteen months of activity, as bars.
 *
 * <p>Bars rather than a line. The series counts events in a month — declarations, inquiries — and a
 * line drawn through counts implies a value between them, which for "how many things happened in
 * August" does not exist.
 *
 * <p>A month with nothing in it is drawn as a visible floor rather than as nothing, so a quiet
 * month is distinguishable from a month the chart forgot.
 */
export function Sparkline({
  months,
  label,
}: {
  months: { month: string; value: number }[];
  label: string;
}) {
  const reduced = useReducedMotion();
  const peak = Math.max(1, ...months.map((month) => month.value));

  return (
    <div>
      <div className="flex h-24 items-end gap-1" role="img" aria-label={label}>
        {months.map((month, index) => {
          const height = Math.max(2, Math.round((month.value / peak) * 96));
          return (
            <div
              key={month.month}
              className="group relative flex-1 rounded-t bg-blue/70 transition hover:bg-blue"
              style={{
                height: `${height}px`,
                ...(reduced
                  ? {}
                  : {
                      animation: `dip-rise 500ms ease-out ${index * 25}ms both`,
                    }),
              }}
            >
              {/* The value on hover rather than under every bar. Thirteen labels across a
                  dashboard card is a wall of digits nobody reads. */}
              <span className="pointer-events-none absolute -top-6 left-1/2 hidden -translate-x-1/2 rounded bg-navy px-1.5 py-0.5 text-xs whitespace-nowrap text-white group-hover:block">
                {month.month} · {month.value}
              </span>
            </div>
          );
        })}
      </div>
      <div className="mt-1.5 flex justify-between text-xs text-muted">
        <span>{months[0]?.month}</span>
        <span>{months[months.length - 1]?.month}</span>
      </div>
      <style>{`@keyframes dip-rise { from { transform: scaleY(0); transform-origin: bottom; } to { transform: scaleY(1); transform-origin: bottom; } }`}</style>
    </div>
  );
}

/**
 * A figure that lifts on hover and reveals a second line.
 *
 * <p>The reveal carries what the number means rather than a second number. A tile that hides a
 * figure until you hover has made the figure harder to read, which is the opposite of the job.
 *
 * <p>Always a link. Every count on this dashboard opens the list it was counted from — a number
 * nobody can check is a number nobody should trust — so the hover state is a promise that clicking
 * goes somewhere, and there is no version of this tile that does not.
 */
export function HoverTile({
  href,
  label,
  value,
  reveal,
  tone = "plain",
}: {
  href: string;
  label: string;
  value: number | null;
  reveal: string;
  tone?: "plain" | "warning" | "serious";
}) {
  const accent =
    tone === "serious"
      ? "border-error/40 hover:border-error"
      : tone === "warning"
        ? "border-warning/50 hover:border-warning"
        : "border-line hover:border-blue";

  return (
    <a
      href={href}
      className={`group block rounded-lg border bg-white p-4 transition hover:-translate-y-0.5 hover:shadow-md ${accent}`}
    >
      <p className="text-xs text-muted">{label}</p>
      <p className="mt-1 text-3xl font-bold text-navy">
        {value === null ? <span className="text-muted">—</span> : <CountUp value={value} />}
      </p>
      {/* Reserved height rather than appearing from nothing: a tile that grows on hover pushes
          the row below it and the whole grid twitches as the pointer crosses it. */}
      <p className="mt-1 h-4 text-xs text-muted opacity-0 transition group-hover:opacity-100">
        {reveal}
      </p>
    </a>
  );
}

/** A band that fills the width and holds the spotlight. Kept here so the page file stays readable. */
export function Band({ children }: { children: ReactNode }) {
  return (
    <div className="overflow-hidden rounded-2xl bg-[linear-gradient(120deg,#0b1f3a_0%,#123a63_55%,#0a4f5c_100%)] text-white">
      {children}
    </div>
  );
}

/**
 * How old the oldest unpaid obligation is, as a strip of segments.
 *
 * <p>The aging bands the exchange already uses, drawn rather than named. A band code — "OVER_360" —
 * is precise and takes a second to place; a strip with three of five lit says how far along the
 * scale a company is before the reader has finished the label beside it.
 *
 * <p>Filled up to and including the band, not only at it. A company in the oldest band is also past
 * every band before it, and one lit segment floating at the end reads as "only this one".
 *
 * @param band the band code, or null when nothing is unpaid
 */
export function AgedStrip({
  band,
  bands,
  label,
}: {
  band: string | null;
  bands: string[];
  label: string;
}) {
  const reached = band === null ? -1 : bands.indexOf(band);

  return (
    <div className="flex items-center gap-1" role="img" aria-label={label}>
      {bands.map((option, index) => {
        const lit = index <= reached;
        // Later bands are worse, so the strip warms as it fills rather than staying one colour.
        const colour =
          index >= bands.length - 1
            ? "var(--color-error)"
            : index >= bands.length - 2
              ? "var(--color-orange)"
              : index >= 1
                ? "var(--color-warning)"
                : "var(--color-success)";
        return (
          <span
            key={option}
            aria-hidden="true"
            className="h-1.5 w-5 rounded-full transition-colors"
            style={{ background: lit ? colour : "var(--color-line)" }}
          />
        );
      })}
    </div>
  );
}
