"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import { ApiError, tixApi, type SearchResult, type SubjectType } from "@/api/client";
import { AgedStrip, Band, CountUp } from "@/components/visual/motion";
import { Button, Card, EmptyState, ErrorNotice, Pill, inputClass } from "@/components/ui";

/**
 * Finding a business in your own book.
 *
 * <p><strong>Read the note this screen prints before extending it.</strong> A subject is shared
 * across operators — the same business is reported by several — so a search over subjects would be
 * a search over the national registry, and one participant could type a letter and list every
 * business its competitors had reported. The server scopes this to records the caller declared and
 * there is no parameter that could widen it.
 *
 * <p>That scoping is stated on the screen rather than left implicit, because the failure mode is
 * not technical. It is a credit officer searching a name, finding nothing, and concluding the
 * business is clear — when what they have learned is only that their own organisation has never
 * reported it. The exchange inquiry answers the other question, and the empty state says so.
 *
 * <p><strong>The visual pass changed how results are read, not what they are.</strong> The table
 * became tiles because a row of numbers makes a reader compare columns, and what somebody
 * searching actually does is pick one company. Every figure on a tile is the same figure the table
 * printed, from the same response. The sort and the type filter run in the browser over results
 * already fetched — there is no second call, and no parameter that could widen the scope.
 */

/** Ordered oldest-last, matching the server's own band order. Never sort this in a component. */
const BANDS = [
  "NOT_DUE",
  "DAYS_30",
  "DAYS_60",
  "DAYS_90",
  "DAYS_120",
  "DAYS_150",
  "DAYS_180",
  "DAYS_270",
  "OVER_270",
];

type Sort = "EXPOSURE" | "OLDEST" | "NAME";
type Filter = "ALL" | SubjectType;

export default function SearchPage() {
  const messages = useMessages();
  const t = messages.search;

  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResult[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [sort, setSort] = useState<Sort>("EXPOSURE");
  const [filter, setFilter] = useState<Filter>("ALL");

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      setResults(await tixApi.search(query.trim()));
    } catch (caught) {
      setResults(null);
      setError(
        caught instanceof ApiError
          ? caught.status === 403
            ? t.noAccess
            : caught.message
          : messages.common.unexpectedError,
      );
    } finally {
      setBusy(false);
    }
  }

  const shown = useMemo(() => {
    if (!results) return [];
    const kept =
      filter === "ALL"
        ? results
        : results.filter((result) => result.subjectType === filter);
    const ordered = [...kept];
    ordered.sort((left, right) => {
      if (sort === "NAME") return left.name.localeCompare(right.name);
      if (sort === "OLDEST") {
        // Unknown bands sort last rather than first. A company whose age we cannot state is not
        // the most urgent thing on the screen, and putting it at the top would say it was.
        const l = left.oldestBand ? BANDS.indexOf(left.oldestBand) : -1;
        const r = right.oldestBand ? BANDS.indexOf(right.oldestBand) : -1;
        return r - l;
      }
      // Mixed currencies cannot be ranked against a single-currency figure, so they sort last
      // rather than being read as zero.
      const l = left.mixedCurrency ? -1 : Number(left.outstanding ?? 0);
      const r = right.mixedCurrency ? -1 : Number(right.outstanding ?? 0);
      return r - l;
    });
    return ordered;
  }, [results, sort, filter]);

  /**
   * What the result set adds up to.
   *
   * Totalled over one currency only, and the count of what was left out is shown beside it. A
   * total that silently blended dollars and francs would be a made-up number in the most
   * authoritative position on the screen.
   */
  const summary = useMemo(() => {
    let owed = 0;
    let currency: string | null = null;
    let excluded = 0;
    let oldest = -1;
    for (const result of shown) {
      if (result.oldestBand) oldest = Math.max(oldest, BANDS.indexOf(result.oldestBand));
      if (result.mixedCurrency || result.outstanding === null) {
        excluded++;
        continue;
      }
      if (currency === null) currency = result.currency;
      if (currency !== result.currency) {
        excluded++;
        continue;
      }
      owed += Number(result.outstanding);
    }
    return { owed, currency, excluded, oldest, count: shown.length };
  }, [shown]);

  return (
    <div className="mx-auto max-w-5xl">
      {/* The search itself, given the weight it earns. This is the one thing people open this
          screen to do, and it used to be a field inside a card below a page heading. */}
      <Band>
        <div className="px-6 py-8 md:px-10 md:py-10">
          <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
            {t.eyebrow}
          </p>
          <h1 className="mb-2 text-3xl font-bold tracking-tight md:text-4xl">{t.title}</h1>
          <p className="mb-6 max-w-2xl text-sm text-white/70">{t.subtitle}</p>

          <form onSubmit={onSubmit} className="flex flex-wrap items-center gap-3">
            <input
              aria-label={t.title}
              className={`${inputClass} min-w-0 flex-1 border-transparent bg-white`}
              placeholder={t.placeholder}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
            <Button type="submit" disabled={busy || query.trim().length < 2}>
              {busy ? messages.common.loading : t.action}
            </Button>
          </form>

          <p className="mt-4 max-w-3xl text-xs text-white/60">{t.scopeNote}</p>
        </div>
      </Band>

      {error && (
        <div className="mt-6">
          <ErrorNotice>{error}</ErrorNotice>
        </div>
      )}

      {results !== null && results.length === 0 && (
        <div className="mt-6">
          <Card title={t.resultsTitle}>
            <EmptyState>{t.noResults}</EmptyState>
            {/* The most important sentence on the page. Nothing found here means your own
                organisation has not reported them, and nothing more than that. */}
            <p className="mt-4 text-center text-sm text-muted">
              {t.noResultsHint}{" "}
              <Link href="/app/tix" className="font-semibold text-blue hover:underline">
                {messages.nav.inquiries} →
              </Link>
            </p>
          </Card>
        </div>
      )}

      {results !== null && results.length > 0 && (
        <>
          <div className="mt-6 grid gap-4 sm:grid-cols-3">
            <SummaryFigure label={t.found} value={summary.count} />
            <div className="rounded-lg border border-line bg-white p-4">
              <p className="text-xs text-muted">{t.owedToYou}</p>
              <p className="mt-1 text-3xl font-bold text-navy">
                <CountUp value={summary.owed} />
                {summary.currency && (
                  <span className="ml-1 text-lg font-semibold text-muted">
                    {summary.currency}
                  </span>
                )}
              </p>
              <p className="mt-1 h-4 text-xs text-muted">
                {summary.excluded > 0
                  ? interpolate(t.excludedFromTotal, t.excludedFromTotal, {
                      count: String(summary.excluded),
                    })
                  : ""}
              </p>
            </div>
            <div className="rounded-lg border border-line bg-white p-4">
              <p className="text-xs text-muted">{t.oldestInResults}</p>
              <p className="mt-1 text-xl font-bold text-navy">
                {summary.oldest < 0
                  ? "—"
                  : messages.portfolio.bands[
                      BANDS[summary.oldest] as keyof typeof messages.portfolio.bands
                    ]}
              </p>
              <div className="mt-2">
                <AgedStrip
                  band={summary.oldest < 0 ? null : (BANDS[summary.oldest] ?? null)}
                  bands={BANDS}
                  label={t.oldestInResults}
                />
              </div>
            </div>
          </div>

          <div className="mt-5 flex flex-wrap items-center gap-2">
            {(["ALL", "BUSINESS", "INDIVIDUAL"] as const).map((option) => (
              <Chip
                key={option}
                active={filter === option}
                onClick={() => setFilter(option)}
                label={t.filters[option]}
                count={
                  option === "ALL"
                    ? results.length
                    : results.filter((result) => result.subjectType === option).length
                }
              />
            ))}
            <span className="ml-auto flex items-center gap-2">
              <span className="text-xs text-muted">{t.sortBy}</span>
              {(["EXPOSURE", "OLDEST", "NAME"] as const).map((option) => (
                <Chip
                  key={option}
                  active={sort === option}
                  onClick={() => setSort(option)}
                  label={t.sorts[option]}
                />
              ))}
            </span>
          </div>

          {shown.length === 0 ? (
            <div className="mt-4">
              <Card>
                <EmptyState>{t.noneOfThatKind}</EmptyState>
              </Card>
            </div>
          ) : (
            <div className="mt-4 grid gap-4 md:grid-cols-2">
              {shown.map((result) => (
                <ResultTile key={result.subjectId} result={result} />
              ))}
            </div>
          )}
        </>
      )}
    </div>
  );
}

function SummaryFigure({ label, value }: { label: string; value: number }) {
  return (
    <div className="rounded-lg border border-line bg-white p-4">
      <p className="text-xs text-muted">{label}</p>
      <p className="mt-1 text-3xl font-bold text-navy">
        <CountUp value={value} />
      </p>
      <p className="mt-1 h-4" />
    </div>
  );
}

function Chip({
  active,
  onClick,
  label,
  count,
}: {
  active: boolean;
  onClick: () => void;
  label: string;
  count?: number;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`rounded-full border px-3.5 py-1.5 text-sm font-semibold transition ${
        active
          ? "border-blue bg-blue text-white"
          : "border-line bg-white text-ink hover:bg-soft"
      }`}
    >
      {label}
      {count !== undefined && (
        <span className={`ml-2 tabular-nums ${active ? "text-white/70" : "text-muted"}`}>
          {count}
        </span>
      )}
    </button>
  );
}

/**
 * One company, as a card you pick rather than a row you compare.
 *
 * <p>The whole tile is the link. A card with a link inside it makes people aim at the name, and the
 * name is the smallest target on it.
 *
 * <p>Goes to the company's own file, not the 360° profile. The profile asks the exchange, which
 * charges an inquiry and needs a stated purpose, and a grid of tiles is exactly where somebody
 * would spend one by clicking around.
 */
function ResultTile({ result }: { result: SearchResult }) {
  const messages = useMessages();
  const t = messages.search;

  return (
    <Link
      href={`/app/subjects/${result.subjectId}`}
      className="group block rounded-lg border border-line bg-white p-5 transition hover:-translate-y-0.5 hover:border-blue hover:shadow-md"
    >
      <div className="mb-3 flex items-start justify-between gap-3">
        <h3 className="font-bold text-navy group-hover:text-blue">{result.name}</h3>
        <Pill>{t.types[result.subjectType]}</Pill>
      </div>

      <div className="flex items-end justify-between gap-4">
        <div>
          <p className="text-xs text-muted">{t.colOutstanding}</p>
          {result.mixedCurrency ? (
            <Pill tone="review">{t.mixedCurrency}</Pill>
          ) : (
            <p className="text-2xl font-bold tabular-nums text-navy">
              {result.outstanding}
              <span className="ml-1 text-sm font-semibold text-muted">{result.currency}</span>
            </p>
          )}
        </div>
        <div className="text-right">
          <p className="text-xs text-muted">{t.colRecords}</p>
          <p className="text-lg font-bold tabular-nums text-ink">
            {result.openCount}
            <span className="text-sm text-muted">/{result.recordCount}</span>
          </p>
        </div>
      </div>

      <div className="mt-4 flex items-center justify-between gap-3">
        <AgedStrip
          band={result.oldestBand}
          bands={BANDS}
          label={
            result.oldestBand
              ? messages.portfolio.bands[result.oldestBand]
              : t.nothingUnpaid
          }
        />
        <span className="text-xs text-muted">
          {result.oldestBand ? messages.portfolio.bands[result.oldestBand] : t.nothingUnpaid}
        </span>
      </div>

      <p className="mt-3 h-4 text-xs font-semibold text-blue opacity-0 transition group-hover:opacity-100">
        {t.openFile} →
      </p>
    </Link>
  );
}
