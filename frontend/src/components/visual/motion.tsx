"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";
import Image from "next/image";

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
  size = "normal",
  action,
}: {
  href: string;
  label: string;
  value: number | null;
  reveal: string;
  tone?: "plain" | "warning" | "serious";
  /**
   * `lead` is for a figure somebody is meant to act on this morning.
   *
   * <p>It is not merely bigger. It states its meaning permanently instead of on hover, and it
   * carries a named action. The distinction is the hierarchy: a tile that hides what it means
   * behind a pointer is asking to be scanned past, which is correct for a secondary count and
   * wrong for a missed statutory deadline.
   */
  size?: "normal" | "lead";
  /** "Open the cases", "Review deliveries". Always visible when given, and only on `lead` tiles. */
  action?: string;
}) {
  const lead = size === "lead";

  // Border and figure are coloured together or not at all. A red number in a grey box reads as a
  // typo; a red box around a navy number reads as decoration.
  const edge =
    tone === "serious"
      ? "border-error/50 hover:border-error"
      : tone === "warning"
        ? "border-warning/60 hover:border-warning"
        : "border-line hover:border-blue";

  const ink =
    tone === "serious" ? "text-[#b91c1c]" : tone === "warning" ? "text-[#b45309]" : "text-navy";

  return (
    <a
      href={href}
      className={`group flex flex-col rounded-lg border bg-white transition hover:-translate-y-0.5 hover:shadow-md ${edge} ${
        lead ? "p-6 shadow-sm" : "p-4"
      }`}
    >
      <p className={`text-muted ${lead ? "text-sm font-semibold" : "text-xs"}`}>{label}</p>
      <p className={`mt-1 font-bold ${ink} ${lead ? "text-5xl" : "text-3xl"}`}>
        {value === null ? <span className="text-muted">—</span> : <CountUp value={value} />}
      </p>

      {lead ? (
        <>
          {/* Stated, not revealed. See the note on `size`. */}
          <p className="mt-2 text-xs leading-relaxed text-muted">{reveal}</p>
          {action && (
            <span className="mt-4 inline-flex items-center gap-1.5 text-sm font-bold text-blue">
              {action}
              <span aria-hidden="true" className="transition group-hover:translate-x-0.5">
                →
              </span>
            </span>
          )}
        </>
      ) : (
        // Reserved height rather than appearing from nothing: a tile that grows on hover pushes
        // the row below it and the whole grid twitches as the pointer crosses it.
        <p className="mt-1 h-4 text-xs text-muted opacity-0 transition group-hover:opacity-100">
          {reveal}
        </p>
      )}
    </a>
  );
}

/**
 * Which way a series moved, between two months named out loud.
 *
 * <p><strong>Neutral by default, and that is the whole design.</strong> An arrow is the fastest
 * way to say "more" and the fastest way to imply "better", and on this platform they are not the
 * same thing. More declarations is more work done, or a worse quarter for the operator's
 * customers, depending on who is reading. More inquiries is a busier credit desk, or somebody
 * mining the exchange. So the arrow points and the colour says nothing, unless a caller who knows
 * the direction's meaning passes {@code meaning} — which today nothing on the overview does.
 *
 * <p>{@code from} and {@code to} are labels rather than an implied "vs last month", because the
 * caller is not comparing the last two months. The last month in this platform's activity series
 * is the current one and is therefore partial; comparing it to a complete month invents a fall
 * that is really just the calendar. The caption names the two months so the reader can see what
 * was compared instead of trusting that it was the right pair.
 *
 * <p>A rise from nothing has no percentage — a denominator of zero is an infinity, not a number —
 * so it shows the absolute change instead.
 */
export function Trend({
  previous,
  current,
  caption,
  meaning = "neutral",
}: {
  previous: number;
  current: number;
  caption: string;
  /** `up-is-good` and `up-is-bad` colour the arrow. `neutral` leaves it navy. */
  meaning?: "neutral" | "up-is-good" | "up-is-bad";
}) {
  const delta = current - previous;
  const direction = delta === 0 ? "flat" : delta > 0 ? "up" : "down";

  const colour =
    meaning === "neutral" || direction === "flat"
      ? "text-navy"
      : (direction === "up") === (meaning === "up-is-good")
        ? "text-[#14532d]"
        : "text-[#b91c1c]";

  const magnitude =
    previous === 0
      ? `${delta > 0 ? "+" : ""}${delta}`
      : `${delta > 0 ? "+" : delta < 0 ? "−" : ""}${Math.abs(Math.round((delta / previous) * 100))}%`;

  return (
    <p className={`flex items-baseline gap-1.5 text-sm font-bold ${colour}`}>
      <span aria-hidden="true">
        {direction === "up" ? "▲" : direction === "down" ? "▼" : "▬"}
      </span>
      <span className="tabular-nums">{direction === "flat" ? "0%" : magnitude}</span>
      <span className="text-xs font-normal text-muted">{caption}</span>
    </p>
  );
}

/**
 * A dozen bars, an inch wide, beside a figure.
 *
 * <p>Not a small {@link Sparkline}: that one is a chart with an axis and a hover value, and this
 * one is punctuation. It says "and here is the shape of it" in the space after a number, and it
 * carries no labels at all — the card it sits in already links to the screen where the same series
 * is drawn properly with its months on it.
 *
 * <p>{@code aria-hidden}, deliberately. The figure beside it and the trend beneath it are both
 * text, so a reader who cannot see this loses nothing; announcing twelve unlabelled magnitudes
 * would be noise standing in for information.
 */
export function MiniSpark({ values, colour = "var(--color-blue)" }: {
  values: number[];
  colour?: string;
}) {
  const peak = Math.max(1, ...values);
  return (
    <span aria-hidden="true" className="flex h-7 items-end gap-[2px]">
      {values.map((value, index) => (
        <span
          key={index}
          className="w-1 rounded-sm"
          style={{
            height: `${Math.max(2, Math.round((value / peak) * 28))}px`,
            background: colour,
            // The recent months read stronger than the old ones, so the eye starts at the end,
            // which is the only part anybody is actually asking about.
            opacity: 0.35 + 0.65 * (index / Math.max(1, values.length - 1)),
          }}
        />
      ))}
    </span>
  );
}

/**
 * A band that fills the width and holds a screen's heading and its headline figures.
 *
 * <p>Optionally with a photograph beside it. That lives here rather than being pasted into each
 * screen because it was about to be pasted into four, and four copies of a grid drift: one gets a
 * different breakpoint, another forgets to hide the image on a phone, and the app quietly stops
 * looking like one product.
 *
 * @param image a path under {@code public/}, or nothing. Decorative by definition — the alt text
 *              is deliberately empty, because everything the picture conveys is already in the
 *              heading beside it, and a screen reader announcing "man at a laptop" before the
 *              figures somebody came for is noise standing in front of content.
 */
export function Band({ children, image }: { children: ReactNode; image?: string }) {
  const shell =
    "overflow-hidden rounded-2xl bg-[linear-gradient(120deg,#0b1f3a_0%,#123a63_55%,#0a4f5c_100%)] text-white";

  if (!image) {
    return <div className={shell}>{children}</div>;
  }

  return (
    <div className={shell}>
      {/* Hidden below the medium breakpoint, and that is not a detail. These bands carry the
          counts a screen exists to show; on a phone a photograph above them would push every
          figure off the first screenful, which is the opposite of what the band is for. */}
      <div className="grid md:grid-cols-[1.35fr_1fr]">
        <div>{children}</div>
        <div className="hidden md:block">
          <Image
            src={image}
            alt=""
            width={1536}
            height={1024}
            className="h-full w-full object-cover"
            sizes="480px"
          />
        </div>
      </div>
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
