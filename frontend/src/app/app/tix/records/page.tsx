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
  PageHeader,
  Pill,
  type Tone,
} from "@/components/ui";

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
      <PageHeader
        title={t.title}
        subtitle={t.subtitle}
        action={
          <Link
            href="/app/tix/declare"
            className="rounded bg-blue px-4 py-2.5 text-sm font-bold text-white transition hover:bg-blue-dark"
          >
            {t.declare}
          </Link>
        }
      />

      {error && (
        <div className="mb-4">
          <ErrorNotice>{error}</ErrorNotice>
        </div>
      )}

      {/* Counts on the tabs, so an empty Individuals list is visibly empty rather than looking
          like a filter that failed. */}
      <div className="mb-4 flex flex-wrap gap-2">
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
                    <tr key={record.id} className="border-b border-line last:border-0">
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
                          className={`ml-2 text-xs ${expired ? "text-[#b45309]" : "text-muted"}`}
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
