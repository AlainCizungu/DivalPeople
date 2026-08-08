"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import { tixApi, type DebtRecord } from "@/api/client";
import { Card, EmptyState, Metric, PageHeader, Pill } from "@/components/ui";

/**
 * Exchange overview.
 *
 * <p>Every figure here is counted from records this operator actually declared. It showed
 * "2,486 employees" and "99.4% payroll accuracy" until yesterday — invented numbers for a product
 * this no longer is — and then em dashes, which were honest and useless. These are honest and
 * useful, and the difference is only that they are derived from an endpoint rather than typed in.
 *
 * <p>Counted in the browser from the operator's own list rather than from a summary endpoint,
 * because no summary endpoint exists and inventing plausible aggregates server-side would be the
 * same mistake in a different file. When the list grows enough for that to matter, the fix is an
 * endpoint that counts — not a number nobody can trace.
 */
export default function DashboardPage() {
  const messages = useMessages();
  const t = messages.dashboard;

  const [records, setRecords] = useState<DebtRecord[] | null>(null);
  const [refused, setRefused] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const loaded = await tixApi.listDebtRecords();
        if (!cancelled) setRecords(loaded);
      } catch {
        // A user holding only the inquirer role is refused here, and that is correct rather than
        // broken: they have declared nothing, so there is nothing to count. Say so plainly
        // instead of showing them an error about a permission they were never meant to have.
        if (!cancelled) {
          setRecords([]);
          setRefused(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const loading = records === null;
  const total = records?.length ?? 0;
  const open = records?.filter((r) => r.status === "OUTSTANDING").length ?? 0;
  const settled = records?.filter((r) => r.status === "SETTLED").length ?? 0;

  const today = new Date().toISOString().slice(0, 10);
  const expiringSoon =
    records?.filter((r) => {
      const days = Math.round(
        (new Date(`${r.retentionUntil}T00:00:00Z`).getTime() -
          new Date(`${today}T00:00:00Z`).getTime()) /
          86_400_000,
      );
      return days >= 0 && days <= 90;
    }).length ?? 0;

  const value = (n: number) => (loading ? "—" : String(n));

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Metric label={t.declaredRecords} value={value(total)} />
        <Metric label={t.openRecords} value={value(open)} note={t.openNote} />
        <Metric label={t.settledRecords} value={value(settled)} />
        <Metric
          label={t.expiringSoon}
          value={value(expiringSoon)}
          note={t.expiringNote}
          tone={expiringSoon > 0 ? "warning" : "plain"}
        />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        <Card title={t.recentTitle} description={t.recentDescription}>
          {loading ? (
            <EmptyState>{messages.common.loading}</EmptyState>
          ) : total === 0 ? (
            <EmptyState>{refused ? t.noAccess : t.empty}</EmptyState>
          ) : (
            <ul className="flex flex-col divide-y divide-line">
              {[...records]
                .sort((a, b) => b.defaultDate.localeCompare(a.defaultDate))
                .slice(0, 5)
                .map((record) => (
                  <li key={record.id} className="flex items-center justify-between gap-4 py-3">
                    <span className="font-bold tabular-nums text-navy">
                      {record.amount} {record.currency}
                    </span>
                    <span className="flex items-center gap-3 text-sm text-muted">
                      {record.defaultDate}
                      <Pill tone={record.status === "OUTSTANDING" ? "serious" : "positive"}>
                        {messages.tix.statuses[record.status]}
                      </Pill>
                    </span>
                  </li>
                ))}
            </ul>
          )}
        </Card>

        <Card title={t.actionsTitle} description={t.actionsDescription}>
          <div className="flex flex-col gap-3">
            {[
              { href: "/app/tix/declare", label: t.actionDeclare },
              { href: "/app/tix", label: t.actionInquire },
              { href: "/app/tix/records", label: t.actionRecords },
            ].map((action) => (
              <Link
                key={action.href}
                href={action.href}
                className="rounded border border-line px-4 py-3 text-sm font-bold text-navy transition hover:bg-soft"
              >
                {action.label} →
              </Link>
            ))}
          </div>
        </Card>
      </div>
    </div>
  );
}
