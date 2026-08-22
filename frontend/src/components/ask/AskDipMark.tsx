"use client";

import { useReducedMotion } from "@/components/visual/motion";

/**
 * The face of the assistant, and deliberately not a face.
 *
 * <p>A face was the obvious thing to draw here and it is the one thing this feature must not have.
 * Two eyes and a mouth make a persona, a persona has opinions, and the single claim this screen
 * rests on is that <strong>the model reads the question and the platform computes the answer</strong>
 * — every figure a sum over rows, nothing judged. A character that looks like it is thinking about
 * your customer undoes that in a way no caption can repair. It would also age badly: the friendly
 * robot is the first thing to look dated, and a national registry is meant to be boring for
 * decades.
 *
 * <p>So: an orb with a spark in it. Warm enough to read as an assistant, abstract enough to make no
 * promises. It breathes slowly while idle and quickens while an answer is being assembled, which is
 * the only thing it ever communicates — that something is happening, not that something is being
 * felt.
 *
 * <p>Motion respects {@code prefers-reduced-motion}: still at every size, with the busy state shown
 * by the ring instead. Somebody who asked their system for less movement should not get a pulsing
 * circle in the corner of every screen.
 */
export function AskDipMark({
  size = 32,
  busy = false,
}: {
  size?: number;
  busy?: boolean;
}) {
  const reduced = useReducedMotion();
  const animate = !reduced;

  return (
    <span
      className="relative inline-flex shrink-0 items-center justify-center"
      style={{ width: size, height: size }}
      aria-hidden="true"
    >
      {/* The halo. Present only while busy, so an idle assistant is a quiet one. */}
      {busy && (
        <span
          className="absolute inset-0 rounded-full"
          style={{
            background:
              "radial-gradient(circle, rgba(0,103,184,0.45) 0%, rgba(0,103,184,0) 70%)",
            ...(animate ? { animation: "dip-halo 1.6s ease-in-out infinite" } : {}),
          }}
        />
      )}

      <span
        className="relative flex h-full w-full items-center justify-center rounded-full"
        style={{
          background:
            "radial-gradient(circle at 32% 28%, #4aa3e8 0%, #0067b8 45%, #0b1f3a 100%)",
          boxShadow: "inset 0 0 6px rgba(255,255,255,0.35)",
          ...(animate
            ? {
                animation: busy
                  ? "dip-breathe 1.4s ease-in-out infinite"
                  : "dip-breathe 5s ease-in-out infinite",
              }
            : {}),
        }}
      >
        <svg
          viewBox="0 0 24 24"
          fill="white"
          style={{ width: size * 0.5, height: size * 0.5 }}
        >
          <path d="M12 3l1.7 5L18.7 9.7 13.7 11.4 12 16.4l-1.7-5L5.3 9.7 10.3 8z" />
          <path
            d="M18.2 14.6l.7 2 2 .7-2 .7-.7 2-.7-2-2-.7 2-.7z"
            opacity="0.75"
          />
        </svg>
      </span>

      <style>{`
        @keyframes dip-breathe {
          0%, 100% { transform: scale(1); }
          50% { transform: scale(1.06); }
        }
        @keyframes dip-halo {
          0%, 100% { transform: scale(1); opacity: 0.5; }
          50% { transform: scale(1.5); opacity: 0; }
        }
      `}</style>
    </span>
  );
}
