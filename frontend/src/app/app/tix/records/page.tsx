"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import { ApiError, tixApi, type DebtRecord, type DebtStatus } from "@/api/client";
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

/**
 * The records this operator has declared.
 *
 * <p>Shows the retention date on every row, and counts down to it. That column is the one an
 * operator cannot get anywhere else: it is the difference between "we reported this" and "this is
 * still visible to other operators", and until this screen existed the answer lived only in a
 * database column nobody could see.
 */
export default function RecordsPage() {
  const messages = useMessages();
  const t = messages.tix.records;

  const [records, setRecords] = useState<DebtRecord[] | null>(null);
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

      <Card>
        {records === null ? (
          <EmptyState>{messages.common.loading}</EmptyState>
        ) : records.length === 0 ? (
          <EmptyState>{t.empty}</EmptyState>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[52rem] text-left text-sm">
              <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                <tr>
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
                {records.map((record) => {
                  const remaining = daysUntil(record.retentionUntil);
                  const expired = remaining < 0;
                  return (
                    <tr key={record.id} className="border-b border-line last:border-0">
                      <th scope="row" className="py-3.5 font-bold tabular-nums text-navy">
                        {record.amount} {record.currency}
                      </th>
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
