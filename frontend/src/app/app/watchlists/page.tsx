"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import { ApiError, watchlistApi, type Watch } from "@/api/client";
import { Button, Card, EmptyState, ErrorNotice, PageHeader, Pill, type Tone } from "@/components/ui";

/**
 * Companies this institution is asking the exchange about on a schedule.
 *
 * <p>A watch is not a new power, and the page says so before it says anything else. It is the
 * inquiry this account can already make, asked every night, charged against the same hourly
 * allowance and written to the same audit trail with the same stated purpose — and it reports
 * exactly what an inquiry reports: an outcome and how many institutions, never which.
 *
 * <p>The sweep button prints what it cost. Watching two hundred companies spends two hundred
 * inquiries, and a screen that hid that would be the reason somebody found their own inquiries
 * refused at nine in the morning.
 */
export default function WatchlistsPage() {
  const messages = useMessages();
  const t = messages.watchlist;

  const [watches, setWatches] = useState<Watch[] | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [sweeping, setSweeping] = useState(false);
  const [outcome, setOutcome] = useState<string | null>(null);

  const describe = (caught: unknown) =>
    caught instanceof ApiError
      ? `${caught.status} ${caught.code} — ${caught.message}`
      : String(caught);

  const load = useCallback(async () => {
    try {
      setWatches(await watchlistApi.list());
      setFailure(null);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 403) {
        setForbidden(true);
        setWatches([]);
        return;
      }
      setFailure(describe(caught));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function onSweep() {
    setSweeping(true);
    setOutcome(null);
    try {
      const swept = await watchlistApi.sweep();
      setOutcome(
        interpolate(t.sweepResult, t.sweepResult, {
          watched: String(swept.watched),
          changed: String(swept.changed),
        }),
      );
      await load();
    } catch (caught) {
      setFailure(describe(caught));
    } finally {
      setSweeping(false);
    }
  }

  async function onRemove(id: string) {
    try {
      await watchlistApi.unwatch(id);
      await load();
    } catch (caught) {
      setFailure(describe(caught));
    }
  }

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />

      <p className="mb-3 rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
        {t.why}
      </p>
      <p className="mb-6 rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
        {t.whyNightly}
      </p>

      {failure && (
        <ErrorNotice>
          {t.loadFailed}
          <span className="mt-1 block font-mono text-xs">{failure}</span>
        </ErrorNotice>
      )}
      {forbidden && (
        <Card title={t.title}>
          <EmptyState>{t.forbidden}</EmptyState>
        </Card>
      )}
      {!failure && !forbidden && watches === null && (
        <EmptyState>{messages.common.loading}</EmptyState>
      )}

      {!forbidden && watches !== null && watches.length === 0 && (
        <Card title={t.empty} description={t.emptyHint}>
          <EmptyState>{t.emptyHint}</EmptyState>
        </Card>
      )}

      {!forbidden && watches !== null && watches.length > 0 && (
        <Card title={t.title} description={t.expiryNote}>
          <div className="mb-4 flex flex-wrap items-center gap-3">
            <Button type="button" onClick={() => void onSweep()} disabled={sweeping}>
              {sweeping ? t.sweeping : t.sweep}
            </Button>
            {outcome && <span className="text-sm text-muted">{outcome}</span>}
          </div>

          <div className="flex flex-col divide-y divide-line">
            {watches.map((watch) => (
              <Row key={watch.id} watch={watch} t={t} onRemove={onRemove} />
            ))}
          </div>
        </Card>
      )}
    </div>
  );
}

function Row({
  watch,
  t,
  onRemove,
}: {
  watch: Watch;
  t: ReturnType<typeof useMessages>["watchlist"];
  onRemove: (id: string) => Promise<void>;
}) {
  // Read here rather than in a helper below. The first version called useMessages() from a plain
  // function, which is a hook outside a component: it compiles, and React refuses it at runtime.
  const outcomes = useMessages().tix.outcomes;

  const OUTCOME_TONE: Record<NonNullable<Watch["lastOutcome"]>, Tone> = {
    NO_MATCH: "neutral",
    CLEAR: "positive",
    OUTSTANDING_DEBT: "serious",
    REVIEW_REQUIRED: "review",
  };

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 py-3">
      <div className="min-w-0">
        <Link
          href={`/app/subjects/${watch.subjectId}`}
          className="font-semibold text-navy hover:text-blue"
        >
          {watch.name}
        </Link>
        <p className="text-xs text-muted">
          {t.purpose}: {watch.purpose}
        </p>
      </div>

      <div className="ml-auto flex flex-wrap items-center gap-2">
        {/* Null is not "clear". It means nobody has asked yet, which is the state every watch is
            in on the day it is created. */}
        {watch.lastOutcome ? (
          <>
            <Pill tone={OUTCOME_TONE[watch.lastOutcome]}>
              {outcomes[watch.lastOutcome]}
            </Pill>
            {watch.lastInstitutions !== null && (
              <span className="text-xs text-muted tabular-nums">
                {watch.lastInstitutions} {t.institutions}
              </span>
            )}
          </>
        ) : (
          <span className="text-xs text-muted">{t.neverChecked}</span>
        )}

        <span className="text-xs text-muted">
          {t.expires}: {watch.expiresAt.slice(0, 10)}
        </span>

        <button
          type="button"
          onClick={() => void onRemove(watch.id)}
          className="rounded border border-line px-3 py-1.5 text-xs font-semibold text-muted transition hover:bg-soft"
        >
          {t.remove}
        </button>
      </div>
    </div>
  );
}
