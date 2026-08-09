"use client";

import { useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import { ApiError, tixApi, type SearchResult } from "@/api/client";
import {
  Button,
  Card,
  EmptyState,
  ErrorNotice,
  PageHeader,
  Pill,
  inputClass,
} from "@/components/ui";

/**
 * Finding a business in your own book.
 *
 * <p><strong>Read the note this screen prints before extending it.</strong> A subject is shared
 * across operators — the same business is reported by several — so a search over subjects would
 * be a search over the national registry, and one participant could type a letter and list every
 * business its competitors had reported. The server scopes this to records the caller declared
 * and there is no parameter that could widen it.
 *
 * <p>That scoping is stated on the screen rather than left implicit, because the failure mode is
 * not technical. It is a credit officer searching a name, finding nothing, and concluding the
 * business is clear — when what they have learned is only that their own organisation has never
 * reported it. The exchange inquiry answers the other question, and the empty state says so.
 */
export default function SearchPage() {
  const messages = useMessages();
  const t = messages.search;

  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResult[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

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

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />

      <Card>
        <form onSubmit={onSubmit} className="flex flex-wrap items-center gap-3">
          <input
            aria-label={t.title}
            className={`${inputClass} min-w-0 flex-1`}
            placeholder={t.placeholder}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
          <Button type="submit" disabled={busy || query.trim().length < 2}>
            {busy ? messages.common.loading : t.action}
          </Button>
        </form>
        <p className="mt-4 rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
          {t.scopeNote}
        </p>
      </Card>

      {error && (
        <div className="mt-4">
          <ErrorNotice>{error}</ErrorNotice>
        </div>
      )}

      {results !== null && (
        <div className="mt-6">
          <Card title={t.resultsTitle}>
            {results.length === 0 ? (
              <>
                <EmptyState>{t.noResults}</EmptyState>
                {/* The most important sentence on the page. Nothing found here means your own
                    organisation has not reported them, and nothing more than that. */}
                <p className="mt-4 text-center text-sm text-muted">
                  {t.noResultsHint}{" "}
                  <Link href="/app/tix" className="font-semibold text-blue hover:underline">
                    {messages.nav.inquiries} →
                  </Link>
                </p>
              </>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full min-w-[36rem] text-left text-sm">
                  <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                    <tr>
                      <th scope="col" className="pb-3 font-semibold">{t.colName}</th>
                      <th scope="col" className="pb-3 text-right font-semibold">
                        {t.colRecords}
                      </th>
                      <th scope="col" className="pb-3 text-right font-semibold">
                        {t.colOutstanding}
                      </th>
                      <th scope="col" className="pb-3 font-semibold">{t.colOldest}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {results.map((result) => (
                      <tr
                        key={result.subjectId}
                        className="border-b border-line last:border-0 hover:bg-soft"
                      >
                        <th scope="row" className="py-3.5 font-semibold text-navy">
                          <Link
                            href={`/app/subjects/${result.subjectId}`}
                            className="hover:underline"
                          >
                            {result.name}
                          </Link>
                          <span className="ml-2 align-middle">
                            <Pill>{t.types[result.subjectType]}</Pill>
                          </span>
                        </th>
                        <td className="py-3.5 text-right tabular-nums text-ink">
                          {result.openCount}
                          <span className="ml-1 text-xs text-muted">/{result.recordCount}</span>
                        </td>
                        <td className="py-3.5 text-right">
                          {result.mixedCurrency ? (
                            <Pill tone="review">{t.mixedCurrency}</Pill>
                          ) : (
                            <span className="font-bold tabular-nums text-navy">
                              {result.outstanding} {result.currency}
                            </span>
                          )}
                        </td>
                        <td className="py-3.5 text-sm text-muted">
                          {result.oldestBand
                            ? messages.portfolio.bands[result.oldestBand]
                            : "—"}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        </div>
      )}
    </div>
  );
}
