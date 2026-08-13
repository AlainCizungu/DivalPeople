"use client";

import { useEffect, useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  ApiError,
  anomaliesApi,
  type BehaviourReport,
  type InquiryBehaviour,
} from "@/api/client";
import { Card, EmptyState, ErrorNotice, PageHeader, Pill, type Tone } from "@/components/ui";

/**
 * The platform watching how it is used.
 *
 * <p>Everything else in DIP checks whether a record is right. This checks whether the asking is
 * right, which is the one question an institution cannot answer for itself, and the reason every
 * inquiry has been recorded with its actor, its outcome and a stated purpose since the rate
 * limiter went in. Nothing had ever read those rows back.
 *
 * <p><strong>Everybody's figures are shown, not only the flagged ones.</strong> A screen that
 * listed exceptions would be empty on almost every visit, and an empty screen cannot be told from
 * a broken one. Showing the whole table means the absence of a flag says somebody looked.
 *
 * <p>It also says plainly what it cannot detect. Three of the alerts a fraud screen is expected to
 * carry — one identifier under two customers, one phone on two accounts, one document under two
 * names — are impossible here, because the registry's uniqueness rules forbid them. That is the
 * same property that makes a business register number resolve to one company. An alert that can
 * never fire is a permanently green light.
 */
export default function AnomaliesPage() {
  const messages = useMessages();
  const t = messages.anomalies;

  const [report, setReport] = useState<BehaviourReport | null>(null);
  const [forbidden, setForbidden] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const loaded = await anomaliesApi.behaviour();
        if (!cancelled) setReport(loaded);
      } catch (caught) {
        if (cancelled) return;
        if (caught instanceof ApiError && caught.status === 403) {
          setForbidden(true);
          return;
        }
        setFailure(
          caught instanceof ApiError
            ? `${caught.status} ${caught.code} — ${caught.message}`
            : String(caught),
        );
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const flagged = (report?.people ?? []).filter((person) => person.flags.length > 0);

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />

      <p className="mb-3 rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
        {t.why}
      </p>
      <p className="mb-6 rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
        {t.cannotDetect}
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
      {!failure && !forbidden && report === null && (
        <EmptyState>{messages.common.loading}</EmptyState>
      )}

      {report !== null && report.people.length === 0 && (
        <Card title={t.title}>
          <EmptyState>
            {interpolate(t.empty, t.empty, { days: String(report.windowDays) })}
          </EmptyState>
        </Card>
      )}

      {report !== null && report.people.length > 0 && (
        <Card
          title={t.title}
          description={interpolate(t.windowNote, t.windowNote, {
            days: String(report.windowDays),
            median: String(report.medianInquiries),
          })}
        >
          {flagged.length === 0 && (
            // Said rather than left as an absence. Nothing unusual and nothing checked look
            // identical on a screen that only lists exceptions.
            <p className="mb-4 rounded border border-line bg-soft px-3 py-2.5 text-sm text-muted">
              {t.nothingUnusual}
            </p>
          )}

          <div className="flex flex-col divide-y divide-line">
            {report.people.map((person, index) => (
              <PersonRow key={person.actorId ?? `unknown-${index}`} person={person} t={t} />
            ))}
          </div>

          <p className="mt-4 border-t border-line pt-3 text-xs text-muted">{t.notAccusation}</p>
        </Card>
      )}
    </div>
  );
}

function PersonRow({
  person,
  t,
}: {
  person: InquiryBehaviour;
  t: ReturnType<typeof useMessages>["anomalies"];
}) {
  const TONE: Record<InquiryBehaviour["flags"][number], Tone> = {
    HIGH_VOLUME: "review",
    MOSTLY_NO_MATCH: "serious",
    HIT_THE_RATE_LIMIT: "serious",
  };

  return (
    <div className="py-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <span className="font-mono text-xs text-ink">
          {/* The user id rather than a name. The list of people at an institution lives on the
              access screen, which is where somebody with the right role can put a name to this. */}
          {person.actorId ? person.actorId.slice(0, 8) : t.unknownActor}
        </span>
        <span className="text-sm text-muted tabular-nums">
          {person.inquiries} {t.inquiries} · {person.noMatch} {t.noMatch}
          {person.refused > 0 && ` · ${person.refused} ${t.refused}`}
        </span>
        <span className="ml-auto text-xs text-muted">
          {t.lastAsked}: {person.lastAsked.slice(0, 10)}
        </span>
      </div>

      {person.flags.length > 0 && (
        <div className="mt-2 flex flex-col gap-1.5">
          {person.flags.map((flag) => (
            <div key={flag} className="flex flex-wrap items-baseline gap-2">
              <Pill tone={TONE[flag]}>{t.flags[flag]}</Pill>
              <span className="text-xs text-muted">{t.flagNotes[flag]}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
