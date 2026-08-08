"use client";

import { useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";
import { CheckList, Eyebrow } from "./primitives";

/**
 * Hero, with the universal-search panel from the prototype.
 *
 * <p>The panel is a <strong>mock</strong>: the tabs move, nothing is queried, and no request
 * leaves the page. It is labelled as illustrative in both languages rather than only in the
 * design, because a screenshot of a convincing risk score against a named company is exactly the
 * kind of image that ends up in a deck as though it were real output. Grand Horizon SARL does not
 * exist.
 */
export function Hero() {
  const messages = useMessages();
  const { hero, board, actions } = messages.landing;
  const [tab, setTab] = useState<"business" | "individual">("business");

  return (
    <section
      id="search"
      className="bg-[radial-gradient(circle_at_85%_20%,rgba(0,103,184,0.23),transparent_28%),linear-gradient(120deg,#eef6ff_0%,#fff_52%,#eefbf7_100%)]"
    >
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
              {actions.explorePlatform}
            </a>
            <a
              href="#risk"
              className="rounded border border-ink bg-white px-5 py-3.5 text-sm font-bold text-ink transition hover:bg-soft"
            >
              {actions.viewRiskReport}
            </a>
          </div>

          <CheckList items={hero.checks} className="mt-7" />
        </div>

        <div className="overflow-hidden rounded-2xl border border-[#e6edf5] bg-white shadow-xl">
          <div className="flex items-start justify-between gap-4 bg-navy px-5 py-4.5 text-white">
            <div>
              <strong>{board.title}</strong>
              <br />
              <small className="text-[#9ec5e8]">{board.illustrative}</small>
            </div>
            <div className="flex items-center gap-1.5 text-sm whitespace-nowrap text-[#9ec5e8]">
              <span aria-hidden="true" className="text-green">
                ●
              </span>
              {board.secure}
            </div>
          </div>

          <div className="bg-[#f7f9fc] p-5">
            <div className="mb-3 flex gap-2">
              {(
                [
                  ["business", board.tabBusiness],
                  ["individual", board.tabIndividual],
                ] as const
              ).map(([key, label]) => (
                <button
                  key={key}
                  type="button"
                  onClick={() => setTab(key)}
                  aria-pressed={tab === key}
                  className={`rounded px-3.5 py-2 text-sm font-bold transition ${
                    tab === key
                      ? "bg-blue text-white"
                      : "border border-[#e8edf3] bg-white text-ink hover:bg-soft"
                  }`}
                >
                  {label}
                </button>
              ))}
            </div>

            {/* Not a form. There is nothing behind it, and a submit that silently did nothing
                would be worse than an input that plainly cannot be typed into. */}
            <div className="mb-3.5 flex gap-2">
              <div className="flex-1 rounded-lg border border-[#e8edf3] bg-white px-3.5 py-3 text-sm text-[#94a3b8]">
                {board.searchPlaceholder}
              </div>
              <span className="rounded-lg bg-blue px-4 py-3 text-sm font-bold text-white">
                {board.searchAction}
              </span>
            </div>

            <div className="rounded-lg border border-[#e8edf3] bg-white p-4">
              <h3 className="text-lg font-bold text-navy">{board.resultName}</h3>
              <p className="mb-3.5 text-sm text-[#64748b]">{board.resultMeta}</p>

              <div className="mb-3.5 grid grid-cols-3 gap-3">
                {[
                  { label: board.scoreLabel, value: board.score, note: board.scoreValue },
                  { label: board.exposureLabel, value: board.exposureValue },
                  { label: board.sourcesLabel, value: board.sourcesValue },
                ].map((metric) => (
                  <div
                    key={metric.label}
                    className="rounded-lg border border-[#e8edf3] bg-[#f7f9fc] p-3"
                  >
                    <small className="text-[#64748b]">{metric.label}</small>
                    <strong className="mt-1 block text-2xl tabular-nums text-navy">
                      {metric.value}
                    </strong>
                    {metric.note && (
                      <small className="text-[#64748b]">{metric.note}</small>
                    )}
                  </div>
                ))}
              </div>

              <p className="rounded-lg border-l-4 border-[#ff8c00] bg-[#fff8ef] p-3 text-sm text-[#7c4a03]">
                {board.signal}
              </p>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
