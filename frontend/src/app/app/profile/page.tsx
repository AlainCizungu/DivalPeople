"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { tixApi, type SearchResult } from "@/api/client";
import { Button, Card, EmptyState } from "@/components/ui";
import { Band } from "@/components/visual/motion";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";

/**
 * Choose a company, then open its 360° profile.
 *
 * <p>The profile itself lives at {@code /app/subjects/[id]/profile} and is a view of one company,
 * not a place — so there was nowhere for a menu entry to point. Reaching it meant finding the
 * company, opening its file, and clicking through, which is three steps for an intent that is one
 * thought: "give me the full picture on this company".
 *
 * <p>This screen is that thought. It searches the operator's own book, the same call the search
 * screen makes, and picking a result goes straight to the profile.
 *
 * <p><strong>Only companies you already hold a record against.</strong> Not a limitation of this
 * screen — it is the exchange's rule. A search that could find any subject in the registry would
 * let a participant enumerate its competitors' customers, which is the one thing the whole design
 * refuses. To ask about a company you have never dealt with, the inquiry form takes an identifier
 * and answers without ever showing you a list.
 *
 * <p><strong>It says what it costs before the click, not after.</strong> The profile asks the
 * exchange, charges an inquiry against the hourly allowance and writes an audit row with a stated
 * purpose. A screen that reached it in one step without saying so would be a screen that spends
 * somebody's allowance for them.
 */
export default function ChooseSubjectPage() {
  const messages = useMessages();
  const t = messages.profile360;
  const lookup = messages.dashboard.lookup;
  const router = useRouter();

  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResult[] | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(text: string) {
    setBusy(true);
    try {
      setResults(await tixApi.search(text));
    } catch {
      setResults([]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mx-auto max-w-4xl">
      <Band>
        <div className="px-6 py-8 md:px-10 md:py-9">
          <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
            {t.chooseEyebrow}
          </p>
          <h1 className="mb-2 text-3xl font-bold tracking-tight md:text-4xl">
            {t.chooseTitle}
          </h1>
          <p className="max-w-2xl text-sm text-white/70">{t.chooseSubtitle}</p>
        </div>
      </Band>

      <div className="mt-6">
        <Card>
          <form
            className="flex flex-col gap-3 sm:flex-row"
            onSubmit={(event) => {
              event.preventDefault();
              void run(query.trim());
            }}
          >
            <div className="relative flex-1">
              <svg
                aria-hidden="true"
                viewBox="0 0 24 24"
                className="pointer-events-none absolute top-1/2 left-4 h-5 w-5 -translate-y-1/2 text-muted"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
              >
                <circle cx="11" cy="11" r="7" />
                <path d="M20 20l-3.5-3.5" />
              </svg>
              <input
                className="w-full rounded-lg border border-line bg-white py-4 pr-4 pl-12 text-base text-ink transition focus:border-blue focus:ring-2 focus:ring-blue/30 focus:outline-none"
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder={lookup.placeholder}
                aria-label={t.chooseTitle}
              />
            </div>
            <Button
              type="submit"
              size="lead"
              disabled={busy || query.trim().length < 3}
            >
              {busy ? messages.common.loading : lookup.action}
            </Button>
          </form>

          {results !== null && results.length === 0 && (
            <div className="mt-5">
              <EmptyState>{lookup.noResults}</EmptyState>
            </div>
          )}

          {results !== null && results.length > 0 && (
            <ul className="mt-5 flex flex-col gap-2">
              {results.slice(0, 8).map((result) => (
                <li key={result.subjectId}>
                  <button
                    type="button"
                    onClick={() =>
                      router.push(`/app/subjects/${result.subjectId}/profile`)
                    }
                    className="w-full rounded-lg border border-line px-4 py-3.5 text-left transition hover:-translate-y-0.5 hover:border-blue hover:shadow-sm"
                  >
                    <span className="block font-semibold text-navy">
                      {result.name}
                    </span>
                    <span className="text-sm text-muted">
                      {interpolate(lookup.records, lookup.records, {
                        count: String(result.recordCount),
                      })}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}

          {/* Under the field, before anybody has typed. An inquiry is spent by opening a profile,
              and the moment to say so is while somebody is deciding which company to open. */}
          <p className="mt-5 text-sm leading-relaxed text-muted">
            {messages.search.open360Note}
          </p>
        </Card>
      </div>
    </div>
  );
}
