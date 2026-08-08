"use client";

import { useEffect, useState } from "react";
import { useLocale } from "@/i18n/LocaleProvider";
import { ApiError, tixApi, type Portfolio } from "@/api/client";
import { Card, EmptyState, ErrorNotice, Metric, PageHeader, Pill } from "@/components/ui";

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
              <ol className="flex flex-col gap-3">
                {portfolio.aging.map((band) => (
                  <li key={band.band}>
                    <div className="mb-1.5 flex flex-wrap items-baseline justify-between gap-2 text-sm">
                      <span className={band.count > 0 ? "text-ink" : "text-muted"}>
                        {t.bands[band.band]}
                      </span>
                      <span className="flex flex-wrap items-baseline gap-3">
                        {band.amounts.map((money) => (
                          <span
                            key={money.currency}
                            className="font-bold tabular-nums text-navy"
                          >
                            {amount(money.amount, money.currency)}
                          </span>
                        ))}
                        <span className="text-xs text-muted tabular-nums">({band.count})</span>
                      </span>
                    </div>
                    <div className="h-2 overflow-hidden rounded-full bg-soft">
                      <div
                        className="h-full rounded-full bg-blue"
                        style={{ width: `${(band.count / busiest) * 100}%` }}
                      />
                    </div>
                  </li>
                ))}
              </ol>
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
