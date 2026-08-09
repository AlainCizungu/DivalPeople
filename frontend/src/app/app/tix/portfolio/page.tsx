"use client";

import { useEffect, useState } from "react";
import { useLocale } from "@/i18n/LocaleProvider";
import { ApiError, tixApi, type Portfolio } from "@/api/client";
import { Card, EmptyState, ErrorNotice, Metric, PageHeader, Pill } from "@/components/ui";

/**
 * Chart geometry, in the SVG's own coordinate space.
 *
 * <p>The viewBox scales to whatever width the card has, so these are proportions rather than
 * pixels. Nine bands is fixed by AgingBand, and the width is derived from it rather than typed —
 * adding a band should widen the chart, not overflow it.
 */
const BAR_WIDTH = 40;
const BAR_GAP = 12;
const BANDS_WIDTH = 9 * (BAR_WIDTH + BAR_GAP);
const CHART_TOP = 18;
const PLOT_HEIGHT = 120;
const CHART_HEIGHT = CHART_TOP + PLOT_HEIGHT;

/**
 * What this operator is owed, aged.
 *
 * <p>Every figure comes from `GET /tix/portfolio`, which counts only the calling operator's own
 * records. Nothing is computed in this file — deliberately. Aging is date arithmetic over money
 * and belongs somewhere a test can run against it, and a browser doing its own totals is how a
 * screen and an export end up disagreeing.
 *
 * <p><strong>Amounts are formatted, never added.</strong> The API sends decimal strings; turning
 * them into JavaScript numbers to sum them would undo the reason the backend uses BigDecimal.
 * `Number()` appears once below, for grouping digits, and its result is never stored or compared.
 *
 * <p>There is no invented figure anywhere on this page and no illustrative panel. The credit-check
 * screen has one because a bank has to be shown what a score will look like before it exists; an
 * operator's own book is not a thing to sketch.
 */
export default function PortfolioPage() {
  const { locale, messages } = useLocale();
  const t = messages.portfolio;

  const [portfolio, setPortfolio] = useState<Portfolio | null>(null);
  const [refused, setRefused] = useState(false);
  /**
   * Anything that is not a refusal.
   *
   * <p>Tracked separately, because without it a failed request left `portfolio` null and the page
   * rendered its loading state permanently — a spinner that means "broken" is worse than an error,
   * since nobody knows when to stop waiting. Found by walking the demo path rather than by a test,
   * which is the honest note to leave here.
   */
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const loaded = await tixApi.portfolio();
        if (!cancelled) setPortfolio(loaded);
      } catch (caught) {
        if (cancelled) return;
        // An inquirer-only account is refused, and that is correct rather than broken: it has
        // declared nothing, so it has no book. Say so instead of showing it an error about a
        // permission it was never meant to hold.
        const forbidden = caught instanceof ApiError && caught.status === 403;
        setRefused(forbidden);
        setError(
          forbidden
            ? null
            : caught instanceof ApiError
              ? caught.message
              : messages.common.unexpectedError,
        );
        setPortfolio(null);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [messages.common.unexpectedError]);

  /**
   * Groups digits for reading. Display only — the string is the value.
   *
   * <p>Falls back to the raw string if the amount does not parse, rather than rendering NaN.
   */
  const amount = (value: string, currency: string) => {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) return `${value} ${currency}`;
    return `${parsed.toLocaleString(locale, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    })} ${currency}`;
  };

  if (refused) {
    return (
      <div className="mx-auto max-w-6xl">
        <PageHeader title={t.title} subtitle={t.subtitle} />
        <EmptyState>{t.noAccess}</EmptyState>
      </div>
    );
  }

  if (portfolio === null) {
    return (
      <div className="mx-auto max-w-6xl">
        <PageHeader title={t.title} subtitle={t.subtitle} />
        {error ? <ErrorNotice>{error}</ErrorNotice> : <EmptyState>{messages.common.loading}</EmptyState>}
      </div>
    );
  }

  const declared = portfolio.recordCount - portfolio.importedRecords;
  // The bars are scaled to the fullest band, not to the total. An aging profile is read by its
  // shape, and scaling to the total flattens every band into an indistinguishable sliver.
  const busiest = Math.max(1, ...portfolio.aging.map((band) => band.count));

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title={t.title}
        subtitle={t.subtitle}
        action={
          <p className="text-sm text-muted">
            {t.asOf} <span className="font-semibold text-ink">{portfolio.asOf}</span>
          </p>
        }
      />

      <div className="grid gap-4 sm:grid-cols-2">
        <Metric
          label={t.records}
          value={String(portfolio.recordCount)}
          note={t.recordsNote}
        />
        <Metric
          label={t.awaitingErasure}
          value={String(portfolio.awaitingErasure)}
          note={t.awaitingErasureNote}
          tone={portfolio.awaitingErasure > 0 ? "warning" : "plain"}
        />
      </div>

      {portfolio.recordCount === 0 ? (
        <div className="mt-6">
          <Card title={t.exposureTitle}>
            <EmptyState>{t.empty}</EmptyState>
          </Card>
        </div>
      ) : (
        <>
          <div className="mt-6">
            <Card title={t.exposureTitle} description={t.exposureDescription}>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[34rem] text-left text-sm">
                  <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                    <tr>
                      <th scope="col" className="pb-3 font-semibold">{t.colCurrency}</th>
                      <th scope="col" className="pb-3 text-right font-semibold">
                        {t.colOutstanding}
                      </th>
                      <th scope="col" className="pb-3 text-right font-semibold">
                        {t.colContested}
                      </th>
                      <th scope="col" className="pb-3 text-right font-semibold">
                        {t.colSettled}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {portfolio.exposure.map((entry) => (
                      <tr key={entry.currency} className="border-b border-line last:border-0">
                        <th scope="row" className="py-3.5 font-semibold text-navy">
                          {entry.currency}
                        </th>
                        <td className="py-3.5 text-right">
                          <span className="font-bold tabular-nums text-navy">
                            {amount(entry.outstanding, entry.currency)}
                          </span>
                          <span className="ml-2 text-xs text-muted tabular-nums">
                            ({entry.outstandingCount})
                          </span>
                        </td>
                        <td className="py-3.5 text-right">
                          <span
                            className={`tabular-nums ${
                              entry.contestedCount > 0 ? "font-bold text-[#b45309]" : "text-muted"
                            }`}
                          >
                            {amount(entry.contested, entry.currency)}
                          </span>
                          <span className="ml-2 text-xs text-muted tabular-nums">
                            ({entry.contestedCount})
                          </span>
                        </td>
                        <td className="py-3.5 text-right">
                          <span className="tabular-nums text-muted">
                            {amount(entry.settled, entry.currency)}
                          </span>
                          <span className="ml-2 text-xs text-muted tabular-nums">
                            ({entry.settledCount})
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Card>
          </div>

          <div className="mt-6">
            <Card title={t.agingTitle} description={t.agingDescription}>
              {/*
                Hand-drawn SVG rather than a charting library. One chart does not justify a
                dependency, and this one has to survive being printed and pasted into a board
                paper — an inline SVG does both, and a canvas-based library does neither.

                Bars are scaled to the fullest band, not to the total. An aging profile is read by
                its shape; scaling to the total flattens every band into an indistinguishable
                sliver as soon as one of them dominates, which in a real book it always does.
              */}
              <svg
                viewBox={`0 0 ${BANDS_WIDTH} ${CHART_HEIGHT}`}
                className="w-full"
                role="img"
                aria-label={t.agingTitle}
                preserveAspectRatio="none"
              >
                {portfolio.aging.map((band, index) => {
                  const height =
                    band.count === 0 ? 0 : Math.max(2, (band.count / busiest) * PLOT_HEIGHT);
                  const x = index * (BAR_WIDTH + BAR_GAP) + BAR_GAP / 2;
                  return (
                    <g key={band.band}>
                      {/* The empty bands are drawn as a faint track, so a gap in the middle of
                          the profile reads as a gap rather than as missing rendering. */}
                      <rect
                        x={x}
                        y={CHART_TOP}
                        width={BAR_WIDTH}
                        height={PLOT_HEIGHT}
                        rx="2"
                        className="fill-soft"
                      />
                      <rect
                        x={x}
                        y={CHART_TOP + PLOT_HEIGHT - height}
                        width={BAR_WIDTH}
                        height={height}
                        rx="2"
                        className={band.band === "OVER_270" ? "fill-error" : "fill-blue"}
                      />
                      {band.count > 0 && (
                        <text
                          x={x + BAR_WIDTH / 2}
                          y={CHART_TOP + PLOT_HEIGHT - height - 6}
                          textAnchor="middle"
                          className="fill-navy text-[11px] font-bold"
                        >
                          {band.count}
                        </text>
                      )}
                    </g>
                  );
                })}
              </svg>

              <ol className="mt-3 grid grid-cols-3 gap-x-4 gap-y-2 sm:grid-cols-5 lg:grid-cols-9">
                {portfolio.aging.map((band) => (
                  <li key={band.band} className="text-center">
                    <p
                      className={`text-[11px] leading-tight ${
                        band.count > 0 ? "text-ink" : "text-muted"
                      }`}
                    >
                      {t.bands[band.band]}
                    </p>
                    {band.amounts.map((money) => (
                      <p
                        key={money.currency}
                        className="text-[11px] font-bold tabular-nums text-navy"
                      >
                        {amount(money.amount, money.currency)}
                      </p>
                    ))}
                  </li>
                ))}
              </ol>

              {/* The same numbers as a table, for anybody reading with a screen reader. A chart
                  that is only a picture is a chart half the audience cannot read. */}
              <table className="sr-only">
                <caption>{t.agingTitle}</caption>
                <thead>
                  <tr>
                    <th scope="col">{t.agingTitle}</th>
                    <th scope="col">{t.records}</th>
                  </tr>
                </thead>
                <tbody>
                  {portfolio.aging.map((band) => (
                    <tr key={band.band}>
                      <th scope="row">{t.bands[band.band]}</th>
                      <td>{band.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <p className="mt-5 border-t border-line pt-4 text-xs text-muted">
                {t.agingFootnote}
              </p>
            </Card>
          </div>

          <div className="mt-6 grid gap-6 lg:grid-cols-2">
            <Card title={t.statusTitle} description={t.statusDescription}>
              <ul className="flex flex-col divide-y divide-line">
                {portfolio.byStatus.map((entry) => (
                  <li
                    key={entry.status}
                    className="flex items-center justify-between gap-4 py-3"
                  >
                    <Pill
                      tone={
                        entry.status === "OUTSTANDING"
                          ? "serious"
                          : entry.status === "SETTLED" || entry.status === "CLEARED"
                            ? "positive"
                            : "review"
                      }
                    >
                      {messages.tix.statuses[entry.status]}
                    </Pill>
                    <span className="font-bold tabular-nums text-navy">{entry.count}</span>
                  </li>
                ))}
              </ul>
            </Card>

            <Card title={t.serviceTitle} description={t.serviceDescription}>
              <ul className="flex flex-col divide-y divide-line">
                {portfolio.byService.map((entry) => (
                  <li
                    key={entry.label}
                    className="flex items-center justify-between gap-4 py-3"
                  >
                    {/* Free text submitted by the declaring operator, so it is rendered as
                        given rather than translated — a label invented here would hide that
                        two operators are spelling the same service differently. */}
                    <span className="text-sm text-ink">{entry.label}</span>
                    <span className="font-bold tabular-nums text-navy">{entry.count}</span>
                  </li>
                ))}
              </ul>
            </Card>
          </div>
        </>
      )}

      <div className="mt-6">
        <Card title={t.provenanceTitle}>
          <dl className="grid gap-4 sm:grid-cols-2">
            <div className="rounded-lg border border-line p-4">
              <dt className="text-sm text-muted">{t.provenanceDeclared}</dt>
              <dd className="mt-1 text-2xl font-bold tabular-nums text-navy">{declared}</dd>
            </div>
            <div className="rounded-lg border border-line p-4">
              <dt className="text-sm text-muted">{t.provenanceImported}</dt>
              <dd className="mt-1 text-2xl font-bold tabular-nums text-navy">
                {portfolio.importedRecords}
              </dd>
            </div>
          </dl>
          {/* The gap, stated on the screen rather than in a document nobody opens. Zero imported
              records is not a bug in the uploader; it is that publishing a batch derives nothing
              yet, and a reader is entitled to know before assuming their files are in the totals. */}
          <p className="mt-4 rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
            {t.provenanceNote}
          </p>
        </Card>
      </div>
    </div>
  );
}
