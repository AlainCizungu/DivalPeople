"use client";

import Link from "next/link";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import { CheckList, Eyebrow } from "./primitives";

/** Hero with the executive-overview panel from the prototype. */
export function Hero() {
  const messages = useMessages();
  const { status } = useSession();
  const { hero, board, actions } = messages.landing;

  return (
    <section className="bg-[radial-gradient(circle_at_85%_20%,rgba(0,103,184,0.23),transparent_28%),linear-gradient(120deg,#eef6ff_0%,#fff_52%,#eefbf7_100%)]">
      <div className="mx-auto grid max-w-7xl items-center gap-14 px-6 py-20 lg:grid-cols-[1.05fr_0.95fr]">
        <div>
          <Eyebrow>{hero.eyebrow}</Eyebrow>
          <h1 className="mb-6 text-[clamp(2.75rem,6vw,4.5rem)] leading-[1.03] font-bold tracking-[-0.04em] text-navy">
            {hero.title}
          </h1>
          <p className="mb-7 text-xl text-[#374151]">{hero.body}</p>

          <div className="flex flex-wrap gap-3">
            <a
              href="#platform"
              className="rounded bg-blue px-5 py-3.5 text-sm font-bold text-white transition hover:bg-blue-dark"
            >
              {actions.seePlatform}
            </a>
            {status === "authenticated" ? (
              <Link
                href="/app"
                className="rounded border border-ink bg-white px-5 py-3.5 text-sm font-bold text-ink transition hover:bg-soft"
              >
                {actions.dashboard}
              </Link>
            ) : (
              <a
                href="#hr"
                className="rounded border border-ink bg-white px-5 py-3.5 text-sm font-bold text-ink transition hover:bg-soft"
              >
                {actions.exploreCapabilities}
              </a>
            )}
          </div>

          <CheckList items={hero.checks} className="mt-7" />
        </div>

        <div className="overflow-hidden rounded-2xl border border-[#e6edf5] bg-white shadow-xl">
          <div className="flex justify-between bg-navy px-5 py-4.5 text-white">
            <div>
              <strong>{board.title}</strong>
              <br />
              <small className="text-[#9ec5e8]">{board.subtitle}</small>
            </div>
            <div className="text-sm">{board.period}</div>
          </div>

          <div className="bg-[#f7f9fc] p-5">
            <div className="mb-3.5 grid grid-cols-3 gap-3">
              {[
                { label: board.totalEmployees, value: "2,486" },
                { label: board.payrollAccuracy, value: "99.4%" },
                { label: board.openAlerts, value: "18" },
              ].map((metric) => (
                <div
                  key={metric.label}
                  className="rounded-lg border border-[#e8edf3] bg-white p-4"
                >
                  <small className="text-[#64748b]">{metric.label}</small>
                  <strong className="mt-1.5 block text-2xl tabular-nums text-navy">
                    {metric.value}
                  </strong>
                </div>
              ))}
            </div>

            <div className="h-40 rounded-lg border border-[#e8edf3] bg-white p-4">
              <svg viewBox="0 0 500 120" className="h-full w-full" aria-hidden="true">
                <line x1="0" y1="100" x2="500" y2="100" stroke="#dbe3ec" />
                <line x1="0" y1="70" x2="500" y2="70" stroke="#eef2f6" />
                <line x1="0" y1="40" x2="500" y2="40" stroke="#eef2f6" />
                <polyline
                  fill="none"
                  stroke="#0067b8"
                  strokeWidth="4"
                  points="0,95 55,72 110,79 165,48 220,62 275,40 330,54 385,25 440,36 500,14"
                />
              </svg>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
