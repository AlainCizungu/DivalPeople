"use client";

import { useEffect, useState } from "react";
import { useMessages, useLocale } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import { monthLabel } from "@/i18n/month";
import {
  ApiError,
  executiveApi,
  type ExecutiveBriefing,
  type ExecutiveMonth,
} from "@/api/client";
import Link from "next/link";
import { Card, EmptyState, ErrorNotice, Metric } from "@/components/ui";
import { ListActions } from "@/components/ListActions";
import { Band, CountUp, HoverTile, Ring } from "@/components/visual/motion";

/**
 * Executive intelligence.
 *
 * <p>The front door answers "what needs a person today". This answers the two questions asked one
 * floor up and one quarter apart: is this working, and are we meeting the obligation we took on.
 *
 * <p><strong>Nothing here is modelled, projected or benchmarked</strong>, and that constraint is
 * the design rather than a limitation being apologised for. An executive screen is where invented
 * metrics live, because the audience is furthest from the data and least placed to challenge a
 * figure — and a platform arguing that its numbers can be checked cannot afford one page where
 * they cannot. Every figure is a count of rows somebody could go and look at.
 *
 * <p>Two absences are printed rather than left as gaps. The platform snapshots nothing, so there
 * is no honest chart of exposure over time; and the audit trail records that an inquiry happened
 * and what it was for, never what it returned, so the obvious executive figure — how many of our
 * inquiries found a debt — cannot be produced. Both are said in words at the bottom.
 */
export default function ExecutivePage() {
  const messages = useMessages();
  // useLocale returns the whole context; the chart only needs the tag for Intl.
  const { locale } = useLocale();
  const t = messages.executive;

  const [briefing, setBriefing] = useState<ExecutiveBriefing | null>(null);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    executiveApi
      .load()
      .then((loaded) => {
        if (!cancelled) {
          setBriefing(loaded);
          setFailure(null);
        }
      })
      .catch((caught: unknown) => {
        if (!cancelled) {
          setFailure(
            caught instanceof ApiError
              ? `${caught.status} ${caught.code} — ${caught.message}`
              : String(caught),
          );
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const answered = briefing?.rights ? briefing.rights.inTime + briefing.rights.late : 0;

  return (
    <div className="mx-auto max-w-5xl">
      <Band image="/review-together.webp">
        <div className="px-6 py-8 md:px-10 md:py-9">
          <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
            {t.eyebrow}
          </p>
          <h1 className="mb-2 text-3xl font-bold tracking-tight md:text-4xl">{t.title}</h1>
          <p className="mb-6 max-w-2xl text-sm text-white/70">
            {briefing
              ? interpolate(t.subtitle, t.subtitle, { date: briefing.asOf })
              : t.subtitleLoading}
          </p>

          {/* Three counts and no money. This briefing is read by somebody who does not work the
              queues, and the figure they would most like — what the market is owed — is the one
              the exchange refuses. Counts are what can honestly be totalled. */}
          {briefing && (
            <div className="flex flex-wrap items-end gap-x-10 gap-y-4">
              <div>
                <p className="text-4xl font-bold">
                  <CountUp value={briefing.book?.total ?? 0} />
                </p>
                <p className="text-xs text-white/60">{t.bookTotal}</p>
              </div>
              <div>
                <p className="text-4xl font-bold">
                  <CountUp value={briefing.book?.outstanding ?? 0} />
                </p>
                <p className="text-xs text-white/60">{t.bookOutstanding}</p>
              </div>
              <div>
                <p className="text-4xl font-bold">
                  <CountUp value={briefing.rights?.raised ?? 0} />
                </p>
                <p className="text-xs text-white/60">{t.rightsRaised}</p>
              </div>
            </div>
          )}
        </div>
      </Band>

      <div className="mt-6" />

      {failure && (
        <ErrorNotice>
          {t.loadFailed}
          <span className="mt-1 block font-mono text-xs">{failure}</span>
        </ErrorNotice>
      )}
      {!failure && !briefing && <EmptyState>{messages.common.loading}</EmptyState>}

      {briefing?.book && (
        <Card title={t.bookTitle} description={t.bookNote}>
          {/* The same ring as the dashboard and the portfolio, over the same statuses in the same
              colours. Three screens describing one book in three visual languages is how a reader
              concludes they are looking at three books. */}
          <Ring
            total={briefing.book.total}
            caption={t.bookTotal}
            segments={[
              {
                label: t.bookOutstanding,
                value: briefing.book.outstanding,
                colour: "var(--color-error)",
              },
              {
                label: t.bookContested,
                value: briefing.book.contested,
                colour: "var(--color-warning)",
              },
              {
                label: t.bookSettled,
                value: briefing.book.settled,
                colour: "var(--color-success)",
              },
            ]}
          />
          <div className="mt-5 grid gap-3 sm:grid-cols-2">
            <HoverTile
              href="/app/tix/records"
              label={t.bookContested}
              value={briefing.book.contested}
              reveal={t.bookContestedNote}
              tone={briefing.book.contested > 0 ? "warning" : "plain"}
            />
            <HoverTile
              href="/app/tix/portfolio"
              label={t.bookAwaitingErasure}
              value={briefing.book.awaitingErasure}
              reveal={t.bookAwaitingErasureNote}
              tone={briefing.book.awaitingErasure > 0 ? "serious" : "plain"}
            />
          </div>
        </Card>
      )}

      {briefing?.activity && (
        <Card
          title={t.activityTitle}
          description={t.activityNote}
          // Thirteen months of counts is the one thing on this page somebody carries into a
          // meeting. It was readable on screen and nowhere else.
          action={
            <ListActions
              rows={briefing.activity}
              filename="dip-activity"
              columns={[
                { heading: t.activityMonth, value: (m) => m.month },
                { heading: t.activityDeclared, value: (m) => String(m.declared) },
                { heading: t.activityInquiries, value: (m) => String(m.inquiries) },
                { heading: t.activityRefused, value: (m) => String(m.refused) },
              ]}
            />
          }
        >
          <ActivityChart months={briefing.activity} locale={locale} t={t} />
        </Card>
      )}

      {briefing?.rights && (
        <Card title={t.rightsTitle} description={t.rightsNote}>
          <div className="grid gap-4 sm:grid-cols-3">
            <Metric label={t.rightsRaised} value={String(briefing.rights.raised)} />
            <Metric label={t.rightsInTime} value={String(briefing.rights.inTime)} />
            {/* Serious rather than amber, and not a service level. Article 214 makes a missed
                deadline grounds in itself for a complaint, so this counts occasions on which
                somebody could have complained and been right. */}
            <Metric
              label={t.rightsLate}
              value={String(briefing.rights.late)}
              note={t.rightsLateNote}
              tone={briefing.rights.late > 0 ? "serious" : "plain"}
            />
          </div>
          {/* The one ratio on this screen, and it is drawn only when there is something to
              divide. Article 214 makes a missed deadline grounds for a complaint in itself, so
              this is the figure a board asks about — and a "100%" printed over nothing answered
              would be the most flattering lie the platform could tell. */}
          {answered > 0 ? (
            <div className="mt-5">
              <div className="mb-1.5 flex items-baseline justify-between">
                <span className="text-sm text-muted">{t.rightsOnTime}</span>
                <span className="text-2xl font-bold tabular-nums text-navy">
                  {Math.round((briefing.rights.inTime / answered) * 100)}%
                </span>
              </div>
              <div className="flex h-2.5 w-full overflow-hidden rounded-full bg-line">
                <span
                  className="block h-full bg-success"
                  style={{ width: `${(briefing.rights.inTime / answered) * 100}%` }}
                />
                <span
                  className="block h-full bg-error"
                  style={{ width: `${(briefing.rights.late / answered) * 100}%` }}
                />
              </div>
              <p className="mt-1.5 text-xs text-muted">
                {interpolate(t.rightsOnTimeNote, t.rightsOnTimeNote, {
                  answered: String(answered),
                })}
              </p>
            </div>
          ) : (
            <p className="mt-3 text-xs text-muted">{t.rightsNothingAnswered}</p>
          )}
        </Card>
      )}

      {briefing && !briefing.book && !briefing.rights && (
        <Card title={t.title}>
          <EmptyState>{t.nothingVisible}</EmptyState>
        </Card>
      )}

      {briefing && (
        <p className="mt-5 rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
          {t.absences}
        </p>
      )}
    </div>
  );
}

/**
 * Thirteen months, drawn as bars against the largest month rather than against a round number.
 *
 * <p>No chart library. Three series of at most thirteen integers is a table with widths on it, and
 * a dependency here would have to be justified to the same reviewer who is asked to accept the
 * risk model — which is a poor use of the only credibility this screen has.
 *
 * <p>The scale is shared across all three series and stated in the header. Scaling each row to its
 * own maximum would make one refused inquiry as tall as four hundred declarations, which is the
 * single most common way a bar chart lies.
 */
function ActivityChart({
  months,
  locale,
  t,
}: {
  months: ExecutiveMonth[];
  locale: string;
  t: ReturnType<typeof useMessages>["executive"];
}) {
  const peak = Math.max(
    1,
    ...months.map((m) => Math.max(m.declared, m.inquiries, m.refused)),
  );

  // Shared with the overview's trend caption, so the two screens cannot name a month differently.
  const label = (iso: string) => monthLabel(iso, locale);

  if (months.every((m) => m.declared === 0 && m.inquiries === 0 && m.refused === 0)) {
    return <EmptyState>{t.activityEmpty}</EmptyState>;
  }

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-center gap-x-5 gap-y-2 text-xs text-muted">
        <span>{interpolate(t.activityScale, t.activityScale, { peak: String(peak) })}</span>
        {/* A legend, because three bars in three colours with the colours explained only by the
            column headers means reading the header row again on every line. */}
        <span className="ml-auto flex flex-wrap items-center gap-4">
          <span className="flex items-center gap-1.5">
            <span aria-hidden="true" className="h-2 w-4 rounded-full bg-navy" />
            {t.activityDeclared}
          </span>
          <span className="flex items-center gap-1.5">
            <span aria-hidden="true" className="h-2 w-4 rounded-full bg-green" />
            {t.activityInquiries}
          </span>
          <span className="flex items-center gap-1.5">
            <span aria-hidden="true" className="h-2 w-4 rounded-full bg-error" />
            {t.activityRefused}
          </span>
        </span>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full min-w-[36rem] text-sm">
          <thead>
            <tr className="border-b border-line text-left text-xs text-muted">
              <th className="pb-1.5 font-semibold">{t.activityMonth}</th>
              <th className="pb-1.5 font-semibold">{t.activityDeclared}</th>
              <th className="pb-1.5 font-semibold">{t.activityInquiries}</th>
              <th className="pb-1.5 font-semibold">{t.activityRefused}</th>
            </tr>
          </thead>
          <tbody>
            {months.map((m) => (
              <tr key={m.month} className="border-b border-line/60 last:border-0">
                <td className="py-2 pr-3 whitespace-nowrap text-muted">
                  {label(m.month)}{" "}
                  <span className="text-xs">{m.month.slice(0, 4)}</span>
                </td>
                <Bar value={m.declared} peak={peak} className="bg-navy" />
                <Bar value={m.inquiries} peak={peak} className="bg-green" />
                {/* Refusals share the scale so they read as small, which is what they usually
                    are. A month where they do not is the month worth asking about. */}
                <Bar value={m.refused} peak={peak} className="bg-error" />
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Bar({
  value,
  peak,
  className,
}: {
  value: number;
  peak: number;
  className: string;
}) {
  return (
    <td className="py-2 pr-3">
      <span className="flex items-center gap-2">
        <span className="h-2 w-24 rounded-full bg-soft">
          <span
            className={`block h-2 rounded-full ${className}`}
            // A zero draws nothing rather than a sliver: a hairline where there is no data reads
            // as a small amount of data.
            style={{ width: value === 0 ? 0 : `${Math.max(4, (value / peak) * 100)}%` }}
          />
        </span>
        <span className="tabular-nums">{value}</span>
      </span>
    </td>
  );
}
