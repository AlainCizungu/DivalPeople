"use client";

import { useEffect, useState } from "react";
import { useMessages, useLocale } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  ApiError,
  executiveApi,
  type ExecutiveBriefing,
  type ExecutiveMonth,
} from "@/api/client";
import { Card, EmptyState, ErrorNotice, Metric, PageHeader } from "@/components/ui";

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
      <PageHeader
        title={t.title}
        subtitle={
          briefing
            ? interpolate(t.subtitle, t.subtitle, { date: briefing.asOf })
            : t.subtitleLoading
        }
      />

      {failure && (
        <ErrorNotice>
          {t.loadFailed}
          <span className="mt-1 block font-mono text-xs">{failure}</span>
        </ErrorNotice>
      )}
      {!failure && !briefing && <EmptyState>{messages.common.loading}</EmptyState>}

      {briefing?.book && (
        <Card title={t.bookTitle} description={t.bookNote}>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <Metric label={t.bookTotal} value={String(briefing.book.total)} />
            <Metric label={t.bookOutstanding} value={String(briefing.book.outstanding)} />
            <Metric label={t.bookSettled} value={String(briefing.book.settled)} />
            <Metric
              label={t.bookContested}
              value={String(briefing.book.contested)}
              note={t.bookContestedNote}
              tone={briefing.book.contested > 0 ? "warning" : "plain"}
            />
          </div>
        </Card>
      )}

      {briefing?.activity && (
        <Card title={t.activityTitle} description={t.activityNote}>
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
          {answered === 0 && (
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

  const monthLabel = (iso: string) => {
    const [year, month] = iso.split("-");
    return new Intl.DateTimeFormat(locale, { month: "short" }).format(
      new Date(Number(year), Number(month) - 1, 1),
    );
  };

  if (months.every((m) => m.declared === 0 && m.inquiries === 0 && m.refused === 0)) {
    return <EmptyState>{t.activityEmpty}</EmptyState>;
  }

  return (
    <div>
      <p className="mb-3 text-xs text-muted">
        {interpolate(t.activityScale, t.activityScale, { peak: String(peak) })}
      </p>
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
                  {monthLabel(m.month)}{" "}
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
