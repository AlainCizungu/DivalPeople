"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  ApiError,
  watchlistApi,
  type AlertSeverity,
  type MonitoringAlert,
} from "@/api/client";
import { Band } from "@/components/visual/motion";
import { Button, Card, EmptyState, ErrorNotice, inputClass } from "@/components/ui";

/**
 * What changed about the companies this operator watches.
 *
 * <p>A search tells an institution what DIP knows right now. This screen is the other half: what
 * changed afterwards, found without anybody searching again. It is a different job from managing
 * the watchlist — one is "who do I care about", this is "what happened to them" — and the two are
 * different screens because they are worked by different people on different days.
 *
 * <p><strong>Every alert carries what the figures were before.</strong> That is the whole content
 * of a change; "the indicator is 61" is a fact anybody could have looked up, and "it was 42 in
 * September" is the reason somebody is reading this.
 *
 * <p><strong>What no alert says: which institution, and how much.</strong> Those are the exchange's
 * standing refusals, and neither becomes disclosable because it arrived as a change rather than as
 * an answer. A row can say a second institution began reporting; it cannot say who, and this screen
 * says so rather than leaving the absence to be noticed.
 *
 * <p>Acknowledging requires a note. An alert closed with no reason records only that somebody made
 * the queue shorter, which is not the outcome anybody wanted from monitoring.
 */
const SEVERITY: Record<AlertSeverity, { band: string; dot: string }> = {
  MATERIAL: { band: "border-error/50 bg-error/5", dot: "bg-error" },
  NOTABLE: { band: "border-warning/60 bg-warning/5", dot: "bg-warning" },
  INFORMATIONAL: { band: "border-line bg-white", dot: "bg-muted" },
};

export default function MonitoringPage() {
  const messages = useMessages();
  const t = messages.monitoring;

  const [alerts, setAlerts] = useState<MonitoringAlert[] | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      setAlerts(await watchlistApi.alerts());
      setFailure(null);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 403) {
        setForbidden(true);
        setAlerts([]);
        return;
      }
      setFailure(
        caught instanceof ApiError
          ? `${caught.status} ${caught.code} — ${caught.message}`
          : String(caught),
      );
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function onAcknowledge(id: string, note: string) {
    await watchlistApi.acknowledge(id, note);
    // Removed from the list rather than refetched. The row the user just closed is the one that
    // changed, and reloading would scroll them away from where they were working.
    setAlerts((current) => (current ?? []).filter((alert) => alert.id !== id));
  }

  const material = (alerts ?? []).filter((alert) => alert.severity === "MATERIAL").length;

  return (
    <div className="mx-auto max-w-5xl">
      <Band>
        <div className="px-6 py-8 md:px-10 md:py-9">
          <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
            {t.eyebrow}
          </p>
          <h1 className="mb-2 text-3xl font-bold tracking-tight md:text-4xl">{t.title}</h1>
          <p className="max-w-2xl text-sm text-white/70">{t.subtitle}</p>

          {alerts !== null && alerts.length > 0 && (
            <p className="mt-5 inline-flex items-center gap-2 rounded-full bg-white/10 px-4 py-2 text-sm">
              <span className="h-2 w-2 rounded-full bg-error" aria-hidden="true" />
              {interpolate(t.openCount, t.openCount, {
                open: String(alerts.length),
                material: String(material),
              })}
            </p>
          )}
        </div>
      </Band>

      {failure && (
        <div className="mt-6">
          <ErrorNotice>{failure}</ErrorNotice>
        </div>
      )}

      {forbidden && (
        <div className="mt-6">
          <Card>
            <EmptyState>{t.forbidden}</EmptyState>
          </Card>
        </div>
      )}

      {alerts !== null && !forbidden && alerts.length === 0 && (
        <div className="mt-6">
          <Card>
            {/* Empty is the normal state and reads as one. A monitoring queue that looks broken
                when nothing is wrong is a queue people stop opening. */}
            <EmptyState>{t.empty}</EmptyState>
            <p className="mt-3 text-center text-sm text-muted">
              {t.emptyHint}{" "}
              <Link href="/app/watchlists" className="font-semibold text-blue hover:underline">
                {t.openWatchlists} →
              </Link>
            </p>
          </Card>
        </div>
      )}

      {alerts !== null && alerts.length > 0 && (
        <div className="mt-6 flex flex-col gap-4">
          {alerts.map((alert) => (
            <AlertCard key={alert.id} alert={alert} onAcknowledge={onAcknowledge} />
          ))}
        </div>
      )}

      <p className="mt-6 text-sm text-muted">{t.boundary}</p>
    </div>
  );
}

function AlertCard({
  alert,
  onAcknowledge,
}: {
  alert: MonitoringAlert;
  onAcknowledge: (id: string, note: string) => Promise<void>;
}) {
  const messages = useMessages();
  const t = messages.monitoring;

  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const look = SEVERITY[alert.severity];

  const scoreMove =
    alert.previousScore !== null && alert.currentScore !== null
      ? alert.currentScore - alert.previousScore
      : null;

  return (
    <div className={`rounded-lg border p-5 ${look.band}`}>
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div className="flex items-start gap-3">
          <span
            aria-hidden="true"
            className={`mt-1.5 h-2.5 w-2.5 shrink-0 rounded-full ${look.dot}`}
          />
          <div>
            <p className="text-xs font-semibold tracking-[0.14em] text-muted uppercase">
              {t.severities[alert.severity]}
            </p>
            <Link
              href={`/app/subjects/${alert.subjectId}`}
              className="text-lg font-bold text-navy hover:text-blue hover:underline"
            >
              {alert.name}
            </Link>
          </div>
        </div>
        <span className="text-xs text-muted">{alert.raisedAt.slice(0, 10)}</span>
      </div>

      <dl className="mb-4 grid gap-3 sm:grid-cols-3">
        <Movement
          label={t.indicator}
          before={alert.previousScore === null ? null : String(alert.previousScore)}
          after={alert.currentScore === null ? t.withheld : String(alert.currentScore)}
          delta={scoreMove}
        />
        <Movement
          label={t.institutions}
          before={
            alert.previousInstitutions === null ? null : String(alert.previousInstitutions)
          }
          after={String(alert.currentInstitutions)}
          delta={
            alert.previousInstitutions === null
              ? null
              : alert.currentInstitutions - alert.previousInstitutions
          }
        />
        <Movement
          label={t.outcome}
          before={
            alert.previousOutcome === null
              ? null
              : messages.tix.outcomes[alert.previousOutcome]
          }
          after={messages.tix.outcomes[alert.currentOutcome]}
          delta={null}
        />
      </dl>

      <form
        className="flex flex-col gap-3 sm:flex-row"
        onSubmit={(event) => {
          event.preventDefault();
          setBusy(true);
          void onAcknowledge(alert.id, note.trim()).finally(() => setBusy(false));
        }}
      >
        <input
          className={inputClass}
          value={note}
          onChange={(event) => setNote(event.target.value)}
          placeholder={t.notePlaceholder}
          aria-label={t.noteLabel}
        />
        <Button type="submit" disabled={busy || note.trim().length === 0}>
          {busy ? messages.common.loading : t.acknowledge}
        </Button>
      </form>
    </div>
  );
}

/**
 * One figure, before and after.
 *
 * <p>A null "before" is the subject's first observed state, not a zero. It renders as a dash and
 * carries no arrow, because there is no movement to describe — the alternative would show a company
 * climbing from nothing on the night DIP first looked at it.
 */
function Movement({
  label,
  before,
  after,
  delta,
}: {
  label: string;
  before: string | null;
  after: string;
  delta: number | null;
}) {
  return (
    <div className="rounded border border-line bg-white px-3 py-2">
      <dt className="text-xs text-muted">{label}</dt>
      <dd className="mt-0.5 flex items-baseline gap-2">
        <span className="text-sm text-muted tabular-nums">{before ?? "—"}</span>
        <span aria-hidden="true" className="text-muted">
          →
        </span>
        <span className="font-bold tabular-nums text-navy">{after}</span>
        {delta !== null && delta !== 0 && (
          <span
            className={`text-xs font-semibold tabular-nums ${
              delta > 0 ? "text-error" : "text-success"
            }`}
          >
            {delta > 0 ? "▲" : "▼"}
            {Math.abs(delta)}
          </span>
        )}
      </dd>
    </div>
  );
}
