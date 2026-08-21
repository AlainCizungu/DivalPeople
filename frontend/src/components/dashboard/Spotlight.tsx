"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import { Band, useReducedMotion } from "@/components/visual/motion";

/**
 * The rotating band at the top of the dashboard.
 *
 * <p>Modelled on the carousel a marketing homepage opens with, and pointed at something else
 * entirely. A carousel on a working screen that advertised features would be an advertisement
 * shown to somebody who already bought the product. <strong>Every slide here is a thing that is
 * actually true of this operator's book right now</strong>, built from the same counts as the tiles
 * below it, and each one goes to the list it came from.
 *
 * <p><strong>A quiet day does not rotate.</strong> When nothing is waiting there is one slide, it
 * says so, and it sits still. That matters more than it sounds: a band that keeps moving whatever
 * the state is trains people to ignore it, and then it fails on the morning it has something to
 * say.
 *
 * <p>Rotation stops on hover and on keyboard focus, and never starts at all under
 * {@code prefers-reduced-motion} — the dots still work, so nothing is unreachable, it simply waits
 * to be asked.
 */
export function Spotlight({
  slides,
}: {
  slides: { key: string; eyebrow: string; headline: string; action: string; href: string }[];
}) {
  const messages = useMessages();
  const t = messages.dashboard.spotlight;
  const reduced = useReducedMotion();
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);

  // Six seconds. Long enough to read two lines without hurrying, short enough that a reader who
  // looked away does not think it has stopped.
  useEffect(() => {
    if (reduced || paused || slides.length < 2) return;
    const timer = setInterval(() => {
      setIndex((current) => (current + 1) % slides.length);
    }, 6000);
    return () => clearInterval(timer);
  }, [reduced, paused, slides.length]);

  // A slide list that shrinks — the last overdue case gets closed while somebody is looking at the
  // screen — must not leave the index pointing past the end.
  useEffect(() => {
    setIndex((current) => (current >= slides.length ? 0 : current));
  }, [slides.length]);

  if (slides.length === 0) {
    return null;
  }

  // Clamped and then checked. noUncheckedIndexedAccess is right to insist: the clamp is correct
  // today only because of the early return above it, and a guard that depends on a line five
  // statements away is a guard somebody deletes.
  const slide = slides[Math.min(index, slides.length - 1)];
  if (!slide) {
    return null;
  }

  return (
    <Band>
      <div
        className="relative px-6 py-8 md:px-10 md:py-10"
        onMouseEnter={() => setPaused(true)}
        onMouseLeave={() => setPaused(false)}
        onFocus={() => setPaused(true)}
        onBlur={() => setPaused(false)}
        // Announced as a region that changes, and politely: a dashboard band that interrupted a
        // screen reader every six seconds would be unusable.
        aria-live="polite"
        aria-roledescription={t.roleDescription}
      >
        <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
          {slide.eyebrow}
        </p>
        <h2 className="mb-5 max-w-3xl text-2xl leading-tight font-bold md:text-3xl">
          {slide.headline}
        </h2>
        <Link
          href={slide.href}
          className="inline-flex items-center gap-2 rounded-full bg-white px-5 py-2.5 text-sm font-bold text-navy transition hover:bg-white/90"
        >
          {slide.action}
          <span aria-hidden="true">→</span>
        </Link>

        {slides.length > 1 && (
          <div className="mt-6 flex items-center gap-2">
            {slides.map((option, position) => (
              <button
                key={option.key}
                type="button"
                onClick={() => setIndex(position)}
                aria-current={position === index}
                aria-label={interpolate(t.goTo, t.goTo, {
                  position: String(position + 1),
                  total: String(slides.length),
                })}
                className={`h-1.5 rounded-full transition-all ${
                  position === index ? "w-8 bg-white" : "w-3 bg-white/40 hover:bg-white/70"
                }`}
              />
            ))}
            {/* Said out loud rather than left to be inferred from a band that stopped moving. */}
            {paused && !reduced && (
              <span className="ml-2 text-xs text-white/50">{t.paused}</span>
            )}
          </div>
        )}
      </div>
    </Band>
  );
}
