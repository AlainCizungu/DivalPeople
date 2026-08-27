"use client";

import { useEffect, useState } from "react";
import Image from "next/image";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  ApiError,
  anomaliesApi,
  type BehaviourReport,
  type InquiryBehaviour,
} from "@/api/client";
import { Card, EmptyState, ErrorNotice, Pill, type Tone } from "@/components/ui";
import { Band, CountUp } from "@/components/visual/motion";

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

  const people = report?.people ?? [];
  const flagged = people.filter((person) => person.flags.length > 0);
  const totalInquiries = people.reduce((running, person) => running + person.inquiries, 0);

  /**
   * Flagged first, then busiest.
   *
   * <p>Order only. Everybody is still listed, which is the rule this screen was built on: a page
   * that showed exceptions alone would be empty on almost every visit, and an empty screen cannot
   * be told from a broken one. But putting the flags in whatever order the server returned meant
   * the one row worth reading could sit twentieth, and a supervisory screen nobody scrolls is a
   * supervisory screen nobody uses.
   */
  const ordered = [...people].sort((left, right) => {
    const byFlag = right.flags.length - left.flags.length;
    return byFlag !== 0 ? byFlag : right.inquiries - left.inquiries;
  });

  return (
    <div className="mx-auto max-w-5xl">
      {/* The band the rest of the platform's working screens carry, and this one did not: it had
          a plain heading and two grey slabs of explanation stacked above the report, so the first
          thing a compliance officer met was three paragraphs and no figures.

          The four counts are the report's own — people, inquiries, flags, and the median the
          comparison is made against. That last one is the most important number on the screen and
          was previously buried in a sentence inside a card description: "unusual" here means
          unusual *for this institution*, and a reader cannot judge a flag without seeing what it
          was judged against. */}
      <Band>
        <div className="grid gap-8 px-6 py-8 md:grid-cols-[1.3fr_1fr] md:px-10 md:py-9">
          <div>
            <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
              {t.eyebrow}
            </p>
            <h1 className="mb-2 text-3xl font-bold tracking-tight md:text-4xl">{t.title}</h1>
            <p className="mb-6 max-w-xl text-sm text-white/70">{t.subtitle}</p>

            {report !== null && (
              <div className="flex flex-wrap gap-x-8 gap-y-4">
                {[
                  [people.length, t.statPeople],
                  [totalInquiries, t.statInquiries],
                  [flagged.length, t.statFlagged],
                  [report.medianInquiries, t.statMedian],
                ].map(([value, label]) => (
                  <div key={label as string}>
                    <p className="text-3xl font-bold">
                      <CountUp value={value as number} />
                    </p>
                    <p className="text-xs text-white/60">{label as string}</p>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* Beside the words, not above them. This screen has no form to push down, but the
              figures are what somebody came for and they stay in the first column. */}
          <div className="hidden overflow-hidden rounded-xl md:block">
            <Image
              src="/anomaly-watch.webp"
              alt=""
              width={1536}
              height={1024}
              className="h-full w-full object-cover"
              sizes="400px"
            />
          </div>
        </div>
      </Band>

      {/* One note, not two slabs. The second — what this cannot detect — keeps a heavier left
          edge because it is the more surprising claim and the one a fraud team will want to
          argue with: three of the alerts a screen like this is expected to carry cannot fire
          here, and an alert that can never fire is a permanently green light. */}
      <p className="mt-6 text-sm leading-relaxed text-muted">{t.why}</p>
      <p className="mt-4 border-l-2 border-line pl-4 text-sm leading-relaxed text-muted">
        {t.cannotDetect}
      </p>
      <div className="mb-6" />

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

          <p className="mb-3 text-xs text-muted">{t.flaggedFirst}</p>

          <div className="flex flex-col divide-y divide-line">
            {ordered.map((person, index) => (
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

  const flagged = person.flags.length > 0;
  const noMatchShare = person.inquiries === 0 ? 0 : person.noMatch / person.inquiries;

  return (
    <div className={`py-3.5 ${flagged ? "-mx-3 rounded-lg bg-soft px-3" : ""}`}>
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

      {/* What share of somebody's inquiries found nobody, drawn rather than left as two figures
          to divide in your head. It is the signal that matters most on this screen: a person
          working from real customer files mostly finds people, and a person walking identifiers
          mostly does not.

          Coloured only when the row is already flagged. An unflagged person with a high share is
          a small sample or a bad week, and painting it amber would spend on ordinary work the
          one colour this screen has to say "look at this". */}
      {person.inquiries > 0 && (
        <div
          className="mt-2 h-1.5 overflow-hidden rounded-full bg-line"
          role="img"
          aria-label={interpolate(t.shareLabel, t.shareLabel, {
            noMatch: String(person.noMatch),
            total: String(person.inquiries),
          })}
        >
          <div
            className={`h-full rounded-full ${flagged ? "bg-warning" : "bg-blue/40"}`}
            style={{ width: `${Math.round(noMatchShare * 100)}%` }}
          />
        </div>
      )}

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
