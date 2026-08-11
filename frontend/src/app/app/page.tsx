"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import { useSession } from "@/auth/SessionProvider";
import { overviewApi, type Overview } from "@/api/client";
import { Card, EmptyState, Metric, PageHeader } from "@/components/ui";

/**
 * The platform's front door.
 *
 * <p>It used to fetch every debt record the operator had ever declared, send them all to the
 * browser and count them there. Its own javadoc said the fix was an endpoint that counts, and one
 * real import made that urgent: 3,699 records over the wire to render four numbers.
 *
 * <p><strong>Organised by what is waiting on somebody</strong>, not by what is impressive. The
 * first card is empty on a good day, and that is the point — a dashboard whose top section can be
 * empty is one people believe when it is not. Totals come second, because a total is a thing you
 * look at once a month and an overdue statutory deadline is a thing you look at today.
 *
 * <p>Every figure links to the list it was counted from. A number nobody can open is a number
 * nobody can check.
 */
export default function DashboardPage() {
  const messages = useMessages();
  const t = messages.dashboard;
  const { profile } = useSession();

  const [overview, setOverview] = useState<Overview | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const loaded = await overviewApi.load();
        if (!cancelled) setOverview(loaded);
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const loading = overview === null && !failed;
  const value = (n: number | undefined) => (n === undefined ? "—" : String(n));

  const rights = overview?.rights ?? null;
  const register = overview?.register ?? null;
  const deliveries = overview?.deliveries ?? null;

  // Nothing to chase: no overdue case, nothing due this week, no delivery abandoned part-way.
  const waiting =
    (rights?.overdue ?? 0) +
    (rights?.dueSoon ?? 0) +
    (deliveries?.awaitingValidation ?? 0) +
    (deliveries?.awaitingPublication ?? 0);

  const roles = profile?.roles ?? [];
  const actions = [
    { href: "/app/tix", label: t.actionInquire, role: "TIX_INQUIRER" },
    { href: "/app/tix/declare", label: t.actionDeclare, role: "TIX_DECLARANT" },
    { href: "/app/imports", label: t.actionImports, role: "TIX_DECLARANT" },
    { href: "/app/tix/portfolio", label: t.actionPortfolio, role: "TIX_DECLARANT" },
    { href: "/app/subject-requests", label: t.actionCases, role: "TIX_DECLARANT" },
    { href: "/app/audit", label: t.actionAudit, role: "TENANT_ADMIN" },
  ].filter((action) => roles.includes(action.role));

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title={t.title}
        subtitle={t.subtitle.replace("{date}", overview?.asOf ?? "…")}
      />

      <Card title={t.waitingTitle} description={t.waitingDescription}>
        {loading ? (
          <EmptyState>{messages.common.loading}</EmptyState>
        ) : waiting === 0 ? (
          // Worth a sentence rather than four zeroes. Four zeroes read as a screen that has not
          // loaded; a sentence reads as an answer.
          <EmptyState>{t.nothingWaiting}</EmptyState>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {rights && (
              <>
                <LinkedMetric
                  href="/app/subject-requests"
                  label={t.overdue}
                  value={value(rights.overdue)}
                  note={t.overdueNote}
                  tone={rights.overdue > 0 ? "serious" : "plain"}
                />
                <LinkedMetric
                  href="/app/subject-requests"
                  label={t.dueSoon}
                  value={value(rights.dueSoon)}
                  note={t.dueSoonNote}
                  tone={rights.dueSoon > 0 ? "warning" : "plain"}
                />
              </>
            )}
            {deliveries && (
              <>
                <LinkedMetric
                  href="/app/imports"
                  label={t.awaitingValidation}
                  value={value(deliveries.awaitingValidation)}
                  note={t.awaitingValidationNote}
                  tone={deliveries.awaitingValidation > 0 ? "warning" : "plain"}
                />
                <LinkedMetric
                  href="/app/imports"
                  label={t.awaitingPublication}
                  value={value(deliveries.awaitingPublication)}
                  note={t.awaitingPublicationNote}
                  tone={deliveries.awaitingPublication > 0 ? "warning" : "plain"}
                />
              </>
            )}
          </div>
        )}
      </Card>

      <div className="mt-6">
        <Card title={t.registerTitle} description={t.registerDescription}>
          {loading ? (
            <EmptyState>{messages.common.loading}</EmptyState>
          ) : register === null ? (
            // Absent, not nought. "You have declared nothing" and "this is not yours to see" are
            // different statements, and showing the first when you mean the second is a false
            // reassurance.
            <EmptyState>{t.noRegister}</EmptyState>
          ) : (
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              <LinkedMetric
                href="/app/tix/records"
                label={t.declaredRecords}
                value={value(register.total)}
              />
              <LinkedMetric
                href="/app/tix/records"
                label={t.openRecords}
                value={value(register.outstanding)}
                note={t.openNote}
              />
              <LinkedMetric
                href="/app/tix/records"
                label={t.contestedRecords}
                value={value(register.contested)}
                note={t.contestedNote}
                tone={register.contested > 0 ? "warning" : "plain"}
              />
              <LinkedMetric
                href="/app/tix/records"
                label={t.settledRecords}
                value={value(register.settled)}
              />
              <LinkedMetric
                href="/app/tix/portfolio"
                label={t.expiringSoon}
                value={value(register.expiringSoon)}
                note={t.expiringNote}
              />
              <LinkedMetric
                href="/app/tix/portfolio"
                label={t.awaitingErasure}
                value={value(register.awaitingErasure)}
                note={t.awaitingErasureNote}
                tone={register.awaitingErasure > 0 ? "serious" : "plain"}
              />
            </div>
          )}
        </Card>
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        {deliveries && (
          <Card title={t.deliveriesTitle} description={t.deliveriesDescription}>
            <div className="grid gap-4 sm:grid-cols-3">
              <LinkedMetric
                href="/app/imports"
                label={t.awaitingValidation}
                value={value(deliveries.awaitingValidation)}
              />
              <LinkedMetric
                href="/app/imports"
                label={t.awaitingPublication}
                value={value(deliveries.awaitingPublication)}
              />
              <LinkedMetric
                href="/app/imports"
                label={t.published}
                value={value(deliveries.published)}
                note={t.publishedNote}
              />
            </div>
          </Card>
        )}

        <Card title={t.actionsTitle} description={t.actionsDescription}>
          <div className="flex flex-col gap-3">
            {actions.map((action) => (
              <Link
                key={action.href}
                href={action.href}
                className="rounded-lg border border-line px-4 py-3 text-sm font-bold text-navy transition hover:bg-soft"
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

/**
 * A figure and the list it was counted from.
 *
 * <p>Every number on this page is one of these. A dashboard figure that cannot be opened is a
 * figure nobody can check, and this platform's entire argument is that its numbers can be.
 */
function LinkedMetric({
  href,
  label,
  value,
  note,
  tone,
}: {
  href: string;
  label: string;
  value: string;
  note?: string;
  tone?: "plain" | "warning" | "serious";
}) {
  return (
    <Link href={href} className="block transition hover:-translate-y-0.5">
      <Metric label={label} value={value} note={note} tone={tone} />
    </Link>
  );
}
