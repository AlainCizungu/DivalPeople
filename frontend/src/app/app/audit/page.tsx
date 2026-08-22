"use client";

import { useCallback, useEffect, useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";
import {
  ApiError,
  auditApi,
  type AuditActionCount,
  type AuditEntry,
} from "@/api/client";
import {
  Card,
  EmptyState,
  ErrorNotice,
  Metric,
  PageHeader,
  Pill,
  type Tone,
} from "@/components/ui";
import { Band, CountUp } from "@/components/visual/motion";

/**
 * The trail, finally readable.
 *
 * <p>The platform has written audit rows since its first migration and never shown one to anybody.
 * The landing page tells institutions that every inquiry is recorded with its stated purpose; this
 * is the screen that makes that checkable, and a claim about accountability nobody can inspect is
 * a claim about nothing.
 *
 * <p>Two things are shown that a prettier version would drop. **Refused attempts** are counted and
 * listed alongside successes, because a rate-limited sweep that left no trace would simply be a
 * slower invisible sweep — the denials are the interesting rows. And the **stated purpose** gets a
 * column of its own rather than a tooltip: it is what turns "somebody looked this person up" into
 * something answerable.
 *
 * <p>Actions are shown as the raw constants the server writes. Translating them would mean a
 * catalogue entry per action, silently falling back to nothing the first time a module adds one —
 * and a trail with blank rows is worse than an untranslated one.
 */

const OUTCOME_TONE: Record<string, Tone> = {
  SUCCESS: "positive",
  DENIED: "serious",
  FAILURE: "review",
};

export default function AuditPage() {
  const messages = useMessages();
  const t = messages.audit;

  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [summary, setSummary] = useState<AuditActionCount[]>([]);
  const [action, setAction] = useState<string>("");
  const [error, setError] = useState<string | null>(null);
  const [refused, setRefused] = useState(false);

  const load = useCallback(async () => {
    try {
      const [events, counts] = await Promise.all([
        auditApi.events(action || null),
        auditApi.summary(),
      ]);
      setEntries(events);
      setSummary(counts);
      setRefused(false);
      setError(null);
    } catch (caught) {
      setEntries([]);
      const forbidden = caught instanceof ApiError && caught.status === 403;
      setRefused(forbidden);
      setError(forbidden ? null : t.loadFailed);
    }
  }, [action, t.loadFailed]);

  useEffect(() => {
    void load();
  }, [load]);

  if (refused) {
    return (
      <div className="mx-auto max-w-6xl">
        <PageHeader title={t.title} subtitle={t.subtitle} />
        <EmptyState>{t.noAccess}</EmptyState>
      </div>
    );
  }

  const total = summary.reduce((sum, entry) => sum + entry.count, 0);
  const inquiries = summary.find((entry) => entry.action === "TIX_INQUIRY")?.count ?? 0;
  // Counted from the page rather than the summary: the summary groups by action, not by outcome.
  // Said plainly on the card rather than presented as a total, because it is not one.
  const refusals = (entries ?? []).filter((entry) => entry.outcome !== "SUCCESS").length;

  const when = (iso: string) => iso.slice(0, 19).replace("T", " ");
  const short = (value: string | null) => (value ? value.slice(0, 8) : "—");

  return (
    <div className="mx-auto max-w-6xl">
      <Band>
        <div className="px-6 py-8 md:px-10 md:py-9">
          <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
            {t.eyebrow}
          </p>
          <h1 className="mb-2 text-3xl font-bold tracking-tight md:text-4xl">{t.title}</h1>
          <p className="mb-6 max-w-2xl text-sm text-white/70">{t.subtitle}</p>

          {/* Only figures counted over the WHOLE trail. Refusals are counted from the page below
              and stay down there with the sentence that says so — a refusal count in a band reads
              as a total, and this one is not one. */}
          <div className="flex flex-wrap items-end gap-x-10 gap-y-4">
            <div>
              <p className="text-4xl font-bold">
                <CountUp value={total} />
              </p>
              <p className="text-xs text-white/60">{t.totalEvents}</p>
            </div>
            <div>
              <p className="text-4xl font-bold">
                <CountUp value={inquiries} />
              </p>
              <p className="text-xs text-white/60">{t.inquiries}</p>
            </div>
          </div>
        </div>
      </Band>

      <div className="mt-6" />

      {error && (
        <div className="mb-4">
          <ErrorNotice>{error}</ErrorNotice>
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2">
        <Metric
          label={t.inquiries}
          value={entries === null ? "—" : String(inquiries)}
          note={t.inquiriesNote}
        />
        <Metric
          label={t.refusals}
          value={entries === null ? "—" : String(refusals)}
          note={t.refusalsNote}
          tone={refusals > 0 ? "warning" : "plain"}
        />
      </div>

      <div className="mt-6">
        <Card title={t.filterTitle} description={t.shapeNote}>
          {/* The distribution, drawn and clickable. A select hides the shape of an operator's
              activity behind a click, and the shape is the thing an auditor reads first: a month
              that is nine-tenths inquiries looks different from one that is nine-tenths imports,
              and neither is visible in a dropdown. */}
          <ul className="flex flex-col gap-2">
            <li>
              <button
                type="button"
                onClick={() => setAction("")}
                aria-pressed={action === ""}
                className={`w-full rounded-lg border px-3 py-2 text-left text-sm transition ${
                  action === ""
                    ? "border-blue bg-blue/5 font-semibold text-navy"
                    : "border-line hover:bg-soft"
                }`}
              >
                {t.filterAll}
                <span className="ml-2 tabular-nums text-muted">{total}</span>
              </button>
            </li>
            {summary.map((entry) => {
              const busiest = Math.max(1, ...summary.map((one) => one.count));
              return (
                <li key={entry.action}>
                  <button
                    type="button"
                    onClick={() => setAction(entry.action)}
                    aria-pressed={action === entry.action}
                    className={`w-full rounded-lg border px-3 py-2 text-left transition ${
                      action === entry.action
                        ? "border-blue bg-blue/5"
                        : "border-line hover:bg-soft"
                    }`}
                  >
                    <span className="flex items-baseline justify-between gap-4">
                      <span className="font-mono text-xs font-semibold text-navy">
                        {entry.action}
                      </span>
                      <span className="text-sm font-bold tabular-nums text-navy">
                        {entry.count}
                      </span>
                    </span>
                    <span className="mt-1.5 block h-1.5 w-full overflow-hidden rounded-full bg-line">
                      <span
                        className="block h-full rounded-full bg-blue"
                        style={{ width: `${Math.max(2, (entry.count / busiest) * 100)}%` }}
                      />
                    </span>
                  </button>
                </li>
              );
            })}
          </ul>
        </Card>
      </div>

      <div className="mt-6">
        <Card title={t.eventsTitle} description={t.eventsDescription}>
          {entries === null ? (
            <EmptyState>{messages.common.loading}</EmptyState>
          ) : entries.length === 0 ? (
            <EmptyState>{t.empty}</EmptyState>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[52rem] text-left text-sm">
                <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                  <tr>
                    <th scope="col" className="pb-3 pr-4 font-semibold">{t.colWhen}</th>
                    <th scope="col" className="pb-3 pr-4 font-semibold">{t.colAction}</th>
                    <th scope="col" className="pb-3 pr-4 font-semibold">{t.colOutcome}</th>
                    <th scope="col" className="pb-3 pr-4 font-semibold">{t.colSubject}</th>
                    <th scope="col" className="pb-3 pr-4 font-semibold">{t.colActor}</th>
                    <th scope="col" className="pb-3 pr-4 font-semibold">{t.colDetail}</th>
                    <th scope="col" className="pb-3 font-semibold">{t.colOrigin}</th>
                  </tr>
                </thead>
                <tbody>
                  {entries.map((entry) => (
                    // A refusal gets a red edge and a tinted row. An audit trail is scanned, not
                    // read, and the rows worth stopping on are the ones where the platform said
                    // no — the whole reason the trail records denials as well as successes.
                    <tr
                      key={entry.id}
                      className={`border-b border-line last:border-0 ${
                        entry.outcome === "DENIED"
                          ? "border-l-4 border-l-error bg-error/5"
                          : entry.outcome !== "SUCCESS"
                            ? "border-l-4 border-l-warning bg-warning/5"
                            : ""
                      }`}
                    >
                      <td className="py-3 pr-4 font-mono text-xs whitespace-nowrap text-muted">
                        {when(entry.occurredAt)}
                      </td>
                      <td className="py-3 pr-4 font-mono text-xs font-semibold text-navy">
                        {entry.action}
                      </td>
                      <td className="py-3 pr-4">
                        <Pill tone={OUTCOME_TONE[entry.outcome] ?? "neutral"}>
                          {t.outcomes[entry.outcome]}
                        </Pill>
                      </td>
                      <td className="py-3 pr-4 font-mono text-xs text-muted">
                        {entry.resourceType}
                        {entry.resourceId && (
                          <span className="ml-1 text-ink">{short(entry.resourceId)}</span>
                        )}
                      </td>
                      <td className="py-3 pr-4 font-mono text-xs text-muted">
                        {short(entry.actorId)}
                      </td>
                      {/* The column the rest of the row exists to give context to. */}
                      <td className="py-3 pr-4 text-ink">
                        {entry.detail ?? <span className="text-muted">—</span>}
                      </td>
                      <td className="py-3 font-mono text-xs text-muted">
                        {entry.ipAddress ?? "—"}
                        {entry.requestId && (
                          <span className="ml-1 opacity-60">{short(entry.requestId)}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="mt-5 flex flex-col gap-2 border-t border-line pt-4 text-xs text-muted">
            <p>{t.immutableNote}</p>
            <p>{t.actorNote}</p>
          </div>
        </Card>
      </div>
    </div>
  );
}
