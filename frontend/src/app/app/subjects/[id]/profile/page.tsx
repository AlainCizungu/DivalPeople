"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import { ApiError, tixApi, type Subject360 } from "@/api/client";
import { RiskIndicatorPanel } from "@/components/RiskIndicatorPanel";
import {
  Button,
  Card,
  EmptyState,
  ErrorNotice,
  Metric,
  PageHeader,
  Pill,
  inputClass,
  type Tone,
} from "@/components/ui";

/**
 * The 360° profile: one company, everything the platform can say about it.
 *
 * <p>The screen a search result should end at. It is deliberately a *different page* from
 * `/app/subjects/[id]`, which shows the operator's own file and asks the exchange nothing. This one
 * asks, so it costs an inquiry, needs a stated purpose, and is guarded on the inquirer role rather
 * than the declarant one. Two pages rather than a flag on one, because "this click charges your
 * hourly allowance and appears in an audit trail" is not a detail to bury in a toggle.
 *
 * <p><strong>Every figure in the overview is yours except the institution count.</strong> The
 * headings say so in words rather than relying on the reader to remember. "Total known exposure"
 * would be read as the market's, and this platform cannot total what other institutions are owed.
 *
 * <p>The contributors panel says which of two things an empty list means. A deployment that
 * withholds names and a company nobody else reports produce the same empty array, and rendering
 * nothing for both would let a reader conclude the more comforting one.
 */
const SIGNAL_TONE: Record<string, Tone> = {
  MULTIPLE_OUTSTANDING_OBLIGATIONS: "serious",
  OBLIGATION_OLDER_THAN_A_YEAR: "serious",
  REPORTED_BY_SEVERAL_INSTITUTIONS: "review",
  AN_IDENTIFIER_IS_REUSED: "review",
  SOME_RECORDS_ARE_CONTESTED: "review",
  NO_NATIONAL_DOCUMENT_ON_FILE: "review",
  NO_IDENTIFIER_CONFLICT: "positive",
  NOTHING_OUTSTANDING_IN_YOUR_BOOK: "positive",
  // Neither good news nor bad. A green tick here would assert a check that is never performed.
  FRAUD_NOT_ASSESSED: "neutral",
};

export default function Subject360Page() {
  const messages = useMessages();
  const t = messages.profile360;
  const params = useParams<{ id: string }>();
  const subjectId = params.id;

  const [purpose, setPurpose] = useState("");
  const [view, setView] = useState<Subject360 | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notHeld, setNotHeld] = useState(false);

  const open = useCallback(
    async (reason: string) => {
      setBusy(true);
      setError(null);
      try {
        setView(await tixApi.profile360(subjectId, reason));
      } catch (caught) {
        setView(null);
        // 404 and 403 read as one sentence, as they do on the server. Distinguishing them here
        // would undo the refusal to distinguish them there, and turn the URL into a way to test
        // whether a business is in the national registry.
        const absent =
          caught instanceof ApiError && (caught.status === 404 || caught.status === 403);
        setNotHeld(absent);
        setError(
          absent
            ? t.notHeld
            : caught instanceof ApiError
              ? `${caught.status} ${caught.code} — ${caught.message}`
              : String(caught),
        );
      } finally {
        setBusy(false);
      }
    },
    [subjectId, t.notHeld],
  );

  // Nothing loads on arrival, and that is the design. Opening this page asks the exchange, and a
  // fetch on mount would spend an inquiry because somebody followed a link — with a purpose
  // nobody typed, which is the one field the audit trail exists to carry.
  useEffect(() => {
    setView(null);
  }, [subjectId]);

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader title={view ? view.name : t.title} subtitle={t.subtitle} />

      {!view && (
        <Card title={t.purposeTitle} description={t.purposeNote}>
          <form
            className="flex flex-col gap-3 sm:flex-row"
            onSubmit={(event) => {
              event.preventDefault();
              void open(purpose.trim());
            }}
          >
            <input
              className={inputClass}
              value={purpose}
              onChange={(event) => setPurpose(event.target.value)}
              placeholder={t.purposePlaceholder}
              aria-label={t.purposeTitle}
            />
            <Button type="submit" disabled={busy || purpose.trim().length === 0}>
              {busy ? t.opening : t.openAction}
            </Button>
          </form>
          {error && (
            <div className="mt-4">
              {notHeld ? <EmptyState>{error}</EmptyState> : <ErrorNotice>{error}</ErrorNotice>}
            </div>
          )}
        </Card>
      )}

      {view && (
        <div className="flex flex-col gap-5">
          {view.indicator ? (
            <RiskIndicatorPanel indicator={view.indicator} />
          ) : (
            <Card title={t.noIndicatorTitle}>
              <EmptyState>{t.noIndicator}</EmptyState>
            </Card>
          )}

          <Card title={t.overviewTitle} description={t.overviewNote}>
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <Metric
                label={t.yourExposure}
                value={
                  view.overview.yourExposure
                    ? `${view.overview.yourExposure} ${view.overview.currency ?? ""}`.trim()
                    : t.mixedCurrency
                }
              />
              <Metric
                label={t.institutions}
                value={String(view.overview.institutionCount)}
                note={t.institutionsNote}
              />
              <Metric label={t.openAccounts} value={String(view.overview.openAccounts)} />
              <Metric
                label={t.pastDue}
                value={String(view.overview.pastDueAccounts)}
                tone={view.overview.pastDueAccounts > 0 ? "warning" : "plain"}
              />
              <Metric
                label={t.oldestUnpaid}
                value={
                  view.overview.oldestUnpaidDays < 0
                    ? t.none
                    : interpolate(t.days, t.days, {
                        days: String(view.overview.oldestUnpaidDays),
                      })
                }
                tone={view.overview.oldestUnpaidDays > 365 ? "serious" : "plain"}
              />
              <Metric
                label={t.lastUpdate}
                value={
                  view.overview.daysSinceUpdate < 0
                    ? t.none
                    : interpolate(t.daysAgo, t.daysAgo, {
                        days: String(view.overview.daysSinceUpdate),
                      })
                }
                note={t.lastUpdateNote}
              />
            </div>
            {view.overview.marketExposure && (
              <p className="mt-4 rounded border border-warning/50 bg-warning/10 px-4 py-3 text-sm text-[#7c4a03]">
                {interpolate(t.marketExposure, t.marketExposure, {
                  amount: view.overview.marketExposure,
                  currency: view.overview.currency ?? "",
                })}
              </p>
            )}
          </Card>

          <Card title={t.signalsTitle} description={t.signalsNote}>
            <ul className="flex flex-col gap-2">
              {view.signals.map((signal) => (
                <li key={signal} className="flex items-start gap-3 text-sm">
                  <Pill tone={SIGNAL_TONE[signal] ?? "neutral"}>{t.signalShort[signal] ?? signal}</Pill>
                  <span className="text-muted">{t.signals[signal] ?? signal}</span>
                </li>
              ))}
            </ul>
          </Card>

          <Card title={t.contributorsTitle} description={t.contributorsNote}>
            {view.contributorsWithheld ? (
              // The whole disclosure boundary, said out loud on the screen it applies to.
              <EmptyState>{t.contributorsWithheld}</EmptyState>
            ) : (
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-line text-left text-xs text-muted">
                    <th className="pb-1.5 font-semibold">{t.colInstitution}</th>
                    <th className="pb-1.5 font-semibold">{t.colOwed}</th>
                    <th className="pb-1.5 font-semibold">{t.colRecords}</th>
                  </tr>
                </thead>
                <tbody>
                  {view.contributors.map((contributor) => (
                    <tr
                      key={contributor.institution}
                      className="border-b border-line/60 last:border-0"
                    >
                      <td className="py-2 pr-3 font-medium">{contributor.institution}</td>
                      <td className="py-2 pr-3 tabular-nums">
                        {contributor.owed
                          ? `${contributor.owed} ${contributor.currency ?? ""}`.trim()
                          : t.amountWithheld}
                      </td>
                      <td className="py-2 pr-3 tabular-nums text-muted">{contributor.records}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </Card>

          <Card title={t.timelineTitle} description={t.timelineNote}>
            {view.timeline.length === 0 ? (
              <EmptyState>{t.timelineEmpty}</EmptyState>
            ) : (
              <ol className="flex flex-col gap-3">
                {view.timeline.map((event, index) => (
                  <li key={`${event.on}-${event.code}-${index}`} className="flex gap-4 text-sm">
                    <span className="w-24 shrink-0 tabular-nums text-muted">{event.on}</span>
                    <span className="flex-1">
                      {t.events[event.code] ?? event.code}
                      {event.detail && <span className="ml-2 text-muted">{event.detail}</span>}
                    </span>
                  </li>
                ))}
              </ol>
            )}
          </Card>

          <p className="text-xs text-muted">
            {interpolate(t.stamp, t.stamp, { version: view.viewVersion })}{" "}
            <Link href={`/app/subjects/${subjectId}`} className="underline hover:text-blue">
              {t.backToOwnFile}
            </Link>
          </p>
        </div>
      )}
    </div>
  );
}
