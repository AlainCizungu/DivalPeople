"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import {
  ApiError,
  tixApi,
  type DebtRecord,
  type DebtStatus,
  type SubjectType,
} from "@/api/client";
import { interpolate } from "@/i18n/interpolate";
import {
  Button,
  Card,
  EmptyState,
  ErrorNotice,
  Pill,
  type Tone,
} from "@/components/ui";
import { ListActions } from "@/components/ListActions";
import { Band, CountUp } from "@/components/visual/motion";

/** The edge on a row, matching the pill in it. */
const STATUS_EDGE: Record<DebtStatus, string> = {
  OUTSTANDING: "border-l-error",
  SETTLED: "border-l-success",
  DISPUTED: "border-l-warning",
  UNDER_INVESTIGATION: "border-l-warning",
  CLEARED: "border-l-line",
};

const STATUS_TONE: Record<DebtStatus, Tone> = {
  OUTSTANDING: "serious",
  SETTLED: "positive",
  DISPUTED: "review",
  UNDER_INVESTIGATION: "review",
  CLEARED: "neutral",
};

/** Days from today until a date, negative once it has passed. */
function daysUntil(iso: string): number {
  const then = new Date(`${iso}T00:00:00Z`).getTime();
  const now = new Date(new Date().toISOString().slice(0, 10) + "T00:00:00Z").getTime();
  return Math.round((then - now) / 86_400_000);
}

/** All, or one kind of subject. Not a route — see the note on the filter below. */
type TypeFilter = "ALL" | SubjectType;

/**
 * The records this operator has declared, businesses and individuals together.
 *
 * <p>Shows the retention date on every row, and counts down to it. That column is the one an
 * operator cannot get anywhere else: it is the difference between "we reported this" and "this is
 * still visible to other operators", and until this screen existed the answer lived only in a
 * database column nobody could see.
 *
 * <p><strong>The subject is named, and its kind is a filter rather than a screen.</strong> This
 * list used to show an amount, a service and a date against nothing at all — an operator could see
 * that it had reported 18,400 USD and not who owed it. Businesses and Individuals were two menu
 * entries over one component differing in a query parameter, which made "is this person in our
 * book?" and "is this company in our book?" two different places to look for one question.
 *
 * <p>Filtered in the browser, deliberately. The list is already fetched in full because the
 * retention countdown is computed per row, so a round trip per filter click would buy nothing and
 * cost a spinner. If this book grows past what one response should carry, the pagination and the
 * filter move to the server together — not the filter alone.
 */
export default function RecordsPage() {
  const messages = useMessages();
  const t = messages.tix.records;

  const [records, setRecords] = useState<DebtRecord[] | null>(null);
  const [filter, setFilter] = useState<TypeFilter>("ALL");
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const load = useCallback(async () => {
    setError(null);
    try {
      setRecords(await tixApi.listDebtRecords());
    } catch (caught) {
      setRecords([]);
      setError(caught instanceof ApiError ? caught.message : messages.common.unexpectedError);
    }
  }, [messages.common.unexpectedError]);

  useEffect(() => {
    void load();
  }, [load]);

  async function act(id: string, action: "settle" | "dispute") {
    setBusyId(id);
    setError(null);
    try {
      const updated = action === "settle" ? await tixApi.settle(id) : await tixApi.dispute(id);
      // Replace in place rather than refetching: the row the user is looking at is the one that
      // changed, and a full reload would scroll them away from it.
      setRecords((current) =>
        (current ?? []).map((record) => (record.id === updated.id ? updated : record)),
      );
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : messages.common.unexpectedError);
    } finally {
      setBusyId(null);
    }
  }

  const shown =
    records === null
      ? []
      : filter === "ALL"
        ? records
        : records.filter((record) => record.subjectType === filter);

  return (
    <div className="mx-auto max-w-6xl">
      <Band>
        <div className="px-6 py-8 md:px-10 md:py-9">
          <div className="mb-6 flex flex-wrap items-start justify-between gap-4">
            <div>
              <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
                {t.eyebrow}
              </p>
              <h1 className="mb-2 text-3xl font-bold tracking-tight md:text-4xl">{t.title}</h1>
              <p className="max-w-2xl text-sm text-white/70">{t.subtitle}</p>
            </div>
            <Link
              href="/app/tix/declare"
              className="shrink-0 rounded bg-white px-4 py-2.5 text-sm font-bold text-navy transition hover:bg-white/85"
            >
              {t.declare}
            </Link>
          </div>

          {/* Counts, and no total owed. This book can hold more than one currency and the screen
              below shows each record's own — adding them here would be a number that is not of
              anything, which the portfolio screen already refuses to print. */}
          {records !== null && (
            <div className="flex flex-wrap items-end gap-x-10 gap-y-4">
              <div>
                <p className="text-4xl font-bold">
                  <CountUp value={records.length} />
                </p>
                <p className="text-xs text-white/60">{t.filters.ALL}</p>
              </div>
              <div>
                <p className="text-4xl font-bold">
                  <CountUp
                    value={records.filter((r) => r.status === "OUTSTANDING").length}
                  />
                </p>
                <p className="text-xs text-white/60">{messages.tix.statuses.OUTSTANDING}</p>
              </div>
              <div>
                <p className="text-4xl font-bold">
                  <CountUp
                    value={
                      records.filter(
                        (r) =>
                          r.status === "DISPUTED" || r.status === "UNDER_INVESTIGATION",
                      ).length
                    }
                  />
                </p>
                <p className="text-xs text-white/60">{t.contested}</p>
              </div>
              {/* Only when it is not zero. A permanent "0 past retention" trains somebody to stop
                  reading the number, and this is the one on the screen that means the platform is
                  holding something it should not. */}
              {records.some((r) => daysUntil(r.retentionUntil) < 0) && (
                <div>
                  <p className="text-4xl font-bold text-[#ffb0b0]">
                    <CountUp
                      value={records.filter((r) => daysUntil(r.retentionUntil) < 0).length}
                    />
                  </p>
                  <p className="text-xs text-white/60">{t.awaitingErasure}</p>
                </div>
              )}
            </div>
          )}
        </div>
      </Band>

      <div className="mt-6" />

      {error && (
        <div className="mb-4">
          <ErrorNotice>{error}</ErrorNotice>
        </div>
      )}

      {/* Counts on the tabs, so an empty Individuals list is visibly empty rather than looking
          like a filter that failed.

          The two actions sit on the same line, at the far end. They act on what the filter has
          left on screen — narrow the list to individuals and the download holds individuals —
          which is why they belong beside the filter rather than in the page header above it. */}
      <div className="mb-4 flex flex-wrap items-center gap-2">
        {(["ALL", "BUSINESS", "INDIVIDUAL"] as const).map((option) => (
          <button
            key={option}
            type="button"
            onClick={() => setFilter(option)}
            aria-pressed={filter === option}
            className={`rounded-full border px-4 py-1.5 text-sm font-semibold transition ${
              filter === option
                ? "border-blue bg-blue text-white"
                : "border-line bg-white text-ink hover:bg-soft"
            }`}
          >
            {t.filters[option]}
            <span className={`ml-2 tabular-nums ${filter === option ? "text-white/70" : "text-muted"}`}>
              {records === null
                ? "—"
                : option === "ALL"
                  ? records.length
                  : records.filter((record) => record.subjectType === option).length}
            </span>
          </button>
        ))}

        <div className="ml-auto">
          <ListActions
            rows={shown}
            filename="dip-records"
            columns={[
              { heading: t.subject, value: (r) => r.subjectName },
              { heading: t.amount, value: (r) => `${r.amount} ${r.currency}` },
              { heading: t.service, value: (r) => r.serviceCategory },
              { heading: t.defaultDate, value: (r) => r.defaultDate },
              { heading: t.status, value: (r) => messages.tix.statuses[r.status] },
              { heading: t.retention, value: (r) => r.retentionUntil },
            ]}
          />
        </div>
      </div>

      <Card>
        {records === null ? (
          <EmptyState>{messages.common.loading}</EmptyState>
        ) : shown.length === 0 ? (
          <EmptyState>{records.length === 0 ? t.empty : t.emptyForFilter}</EmptyState>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[60rem] text-left text-sm">
              <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                <tr>
                  <th scope="col" className="pb-3 font-semibold">{t.subject}</th>
                  <th scope="col" className="pb-3 font-semibold">{t.amount}</th>
                  <th scope="col" className="pb-3 font-semibold">{t.service}</th>
                  <th scope="col" className="pb-3 font-semibold">{t.defaultDate}</th>
                  <th scope="col" className="pb-3 font-semibold">{t.status}</th>
                  <th scope="col" className="pb-3 font-semibold">{t.retention}</th>
                  <th scope="col" className="pb-3 font-semibold">
                    <span className="sr-only">{t.actions}</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {shown.map((record) => {
                  const remaining = daysUntil(record.retentionUntil);
                  const expired = remaining < 0;
                  return (
                    <tr
                      key={record.id}
                      className={`border-b border-l-4 border-line last:border-b-0 transition hover:bg-soft/60 ${
                        expired ? "border-l-error bg-error/5" : STATUS_EDGE[record.status]
                      }`}
                    >
                      <th scope="row" className="py-3.5 pr-4 font-bold text-navy">
                        <Link
                          href={`/app/subjects/${record.subjectId}`}
                          className="hover:text-blue hover:underline"
                        >
                          {record.subjectName}
                        </Link>
                        <span className="mt-0.5 block text-xs font-normal text-muted">
                          {messages.tix.subjectTypes[record.subjectType]}
                        </span>
                      </th>
                      <td className="py-3.5 font-bold tabular-nums text-navy">
                        {record.amount} {record.currency}
                      </td>
                      <td className="py-3.5 text-muted">{record.serviceCategory}</td>
                      <td className="py-3.5 tabular-nums text-ink">{record.defaultDate}</td>
                      <td className="py-3.5">
                        <Pill tone={STATUS_TONE[record.status]}>
                          {messages.tix.statuses[record.status]}
                        </Pill>
                      </td>
                      <td className="py-3.5">
                        <span className="tabular-nums text-ink">{record.retentionUntil}</span>
                        <span
                          className={`ml-2 text-xs ${
                            expired
                              ? "font-bold text-error"
                              : remaining <= 30
                                ? "font-semibold text-[#7c4a03]"
                                : "text-muted"
                          }`}
                        >
                          {expired
                            ? t.awaitingErasure
                            : interpolate(t.daysRemaining, t.daysRemaining, {
                                days: String(remaining),
                              })}
                        </span>
                      </td>
                      <td className="py-3.5 text-right">
                        {record.status === "OUTSTANDING" && (
                          <span className="flex justify-end gap-2">
                            <Button
                              variant="secondary"
                              disabled={busyId === record.id}
                              onClick={() => act(record.id, "settle")}
                            >
                              {t.settle}
                            </Button>
                            <Button
                              variant="quiet"
                              disabled={busyId === record.id}
                              onClick={() => act(record.id, "dispute")}
                            >
                              {t.dispute}
                            </Button>
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      <p className="mt-4 text-sm text-muted">{t.settleNote}</p>
    </div>
  );
}
