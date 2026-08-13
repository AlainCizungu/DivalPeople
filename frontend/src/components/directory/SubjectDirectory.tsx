"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import { ApiError, tixApi, type SearchResult, type SubjectType } from "@/api/client";
import { Card, EmptyState, ErrorNotice, PageHeader, Pill } from "@/components/ui";

/**
 * The operator's own book, listed rather than searched.
 *
 * <p>One component behind two menu entries, because Businesses and Individuals differ in a query
 * parameter and in what an empty list means. Two files would drift the first time somebody
 * improved one of them.
 *
 * <p>The screen answers the question the search box could not: <em>who is in here?</em> Until now
 * the only way to reach a subject was to guess enough of its name to clear the three-character
 * minimum, which works when you already know who you are looking for and not at all otherwise.
 *
 * <p>Name order rather than worst-first. A directory is for finding somebody; the risk-ordered
 * view of the same book is the exposure screen, and two screens ranking one book by different
 * rules would be two answers to one question.
 */
export function SubjectDirectory({
  type,
  title,
  subtitle,
  emptyHint,
}: {
  type: SubjectType;
  title: string;
  subtitle: string;
  emptyHint: string;
}) {
  const messages = useMessages();
  const t = messages.directory;

  const [subjects, setSubjects] = useState<SearchResult[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [forbidden, setForbidden] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const page = await tixApi.browse(type);
        if (cancelled) return;
        setSubjects(page.subjects);
        setTruncated(page.truncated);
      } catch (caught) {
        if (cancelled) return;
        // Refused and broken want different words. Lacking the declarant role is the design
        // working, and printing it as "could not load the list, 403" reads as a fault.
        if (caught instanceof ApiError && caught.status === 403) {
          setForbidden(true);
          setSubjects([]);
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
  }, [type]);

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader
        title={title}
        subtitle={
          subjects === null || forbidden
            ? subtitle
            : `${subtitle} — ${interpolate(t.count, t.count, {
                count: String(subjects.length),
              })}`
        }
      />

      <p className="mb-6 rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
        {t.ownBookNote}
      </p>

      {failure && (
        <ErrorNotice>
          {t.loadFailed}
          <span className="mt-1 block font-mono text-xs">{failure}</span>
        </ErrorNotice>
      )}
      {!failure && subjects === null && <EmptyState>{messages.common.loading}</EmptyState>}

      {forbidden && (
        <Card title={title}>
          <EmptyState>{t.forbidden}</EmptyState>
        </Card>
      )}

      {!forbidden && subjects !== null && subjects.length === 0 && (
        <Card title={t.empty} description={emptyHint}>
          <EmptyState>{emptyHint}</EmptyState>
        </Card>
      )}

      {!forbidden && subjects !== null && subjects.length > 0 && (
        <Card title={title}>
          <div className="flex flex-col divide-y divide-line">
            {subjects.map((subject) => (
              <Row key={subject.subjectId} subject={subject} t={t} />
            ))}
          </div>

          {truncated && (
            // Said rather than left to be noticed. A list that stops at a round number without
            // saying so reads as the whole book, and somebody will conclude they have seen it.
            <p className="mt-4 border-t border-line pt-3 text-xs text-muted">
              {interpolate(t.truncated, t.truncated, { count: String(subjects.length) })}
            </p>
          )}
        </Card>
      )}
    </div>
  );
}

function Row({
  subject,
  t,
}: {
  subject: SearchResult;
  t: ReturnType<typeof useMessages>["directory"];
}) {
  return (
    <Link
      href={`/app/subjects/${subject.subjectId}`}
      className="flex flex-wrap items-center gap-x-4 gap-y-1.5 py-3 transition hover:bg-soft"
    >
      <div className="min-w-0">
        <p className="font-semibold text-navy">{subject.name}</p>
        <p className="text-xs text-muted">
          {subject.recordCount} {t.records}
          {subject.openCount > 0 && ` · ${subject.openCount} ${t.open}`}
        </p>
      </div>

      <div className="ml-auto flex flex-wrap items-center gap-2">
        {subject.oldestBand && <Pill tone="review">{subject.oldestBand}</Pill>}
        {/* Null with mixedCurrency is a different statement from null without it: one says the
            records span several currencies so a single figure would be of nothing, the other
            says there is nothing outstanding. */}
        {subject.mixedCurrency ? (
          <span className="text-xs text-muted">{t.mixedCurrency}</span>
        ) : (
          subject.outstanding && (
            <span className="text-sm font-semibold tabular-nums text-navy">
              {subject.outstanding} {subject.currency}
            </span>
          )
        )}
      </div>
    </Link>
  );
}
