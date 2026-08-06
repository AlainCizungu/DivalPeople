"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import {
  employeesApi,
  leaveApi,
  type EmployeeSummary,
  type LeaveBalance,
  type LeaveRequest,
  type LeaveRequestStatus,
} from "@/api/client";

const STATUS_STYLES: Record<LeaveRequestStatus, string> = {
  SUBMITTED: "bg-warning/20 text-ink",
  APPROVED: "bg-green/10 text-green",
  REJECTED: "bg-error/10 text-error",
  CANCELLED: "bg-soft text-muted",
};

export default function LeavePage() {
  const messages = useMessages();
  const { status } = useSession();
  // The proxy attaches the token; the page only needs to know whether it may call yet.
  const ready = status === "authenticated";

  const [people, setPeople] = useState<EmployeeSummary[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [balances, setBalances] = useState<LeaveBalance[] | null>(null);
  const [requests, setRequests] = useState<LeaveRequest[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!ready) return;
    try {
      const directory = await employeesApi.list();
      setPeople(directory);
      setError(null);
      setSelected((current) => current ?? directory[0]?.id ?? null);
    } catch {
      setError(messages.leave.loadFailed);
    }
  }, [ready, messages.leave.loadFailed]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!ready || !selected) return;
    let cancelled = false;
    setBalances(null);
    setRequests(null);

    Promise.all([leaveApi.balances(selected), leaveApi.requests(selected)])
      .then(([found, history]) => {
        // Results for somebody the user has since clicked away from are not worth showing.
        if (cancelled) return;
        setBalances(found);
        setRequests(history);
      })
      .catch(() => {
        if (!cancelled) setError(messages.leave.loadFailed);
      });

    return () => {
      cancelled = true;
    };
  }, [ready, selected, messages.leave.loadFailed]);

  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6">
        <h1 className="text-3xl font-bold tracking-tight text-navy">{messages.leave.title}</h1>
        <p className="mt-1 text-muted">{messages.leave.subtitle}</p>
      </header>

      {error && (
        <div role="alert" className="rounded-lg border border-error/40 bg-error/10 p-5">
          <p className="text-sm text-ink">{error}</p>
        </div>
      )}

      {!error && people === null && <p className="text-muted">{messages.common.loading}</p>}

      {!error && people && people.length > 0 && (
        <div className="grid gap-6 lg:grid-cols-[16rem_1fr]">
          <nav aria-label={messages.employees.title} className="flex flex-col gap-1">
            {people.map((person) => (
              <button
                key={person.id}
                type="button"
                onClick={() => setSelected(person.id)}
                aria-current={person.id === selected}
                className={`rounded-lg border px-4 py-3 text-left text-sm transition ${
                  person.id === selected
                    ? "border-blue bg-blue/5 font-semibold text-navy"
                    : "border-line bg-white text-ink hover:border-blue/40"
                }`}
              >
                {person.displayName}
              </button>
            ))}
          </nav>

          <div className="flex flex-col gap-6">
            <section>
              <h2 className="mb-3 text-sm font-semibold text-navy">{messages.leave.balances}</h2>

              {balances === null && <p className="text-muted">{messages.common.loading}</p>}

              {balances?.length === 0 && (
                <div className="rounded-lg border border-line bg-white p-6 text-center">
                  <p className="text-muted">{messages.leave.noBalances}</p>
                </div>
              )}

              {balances && balances.length > 0 && (
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {balances.map((balance) => (
                    <article
                      key={balance.id}
                      className="rounded-lg border border-line bg-white p-4"
                    >
                      <p className="font-semibold text-navy">{balance.leaveTypeName}</p>
                      <p className="mt-2 text-3xl font-bold tabular-nums text-blue">
                        {balance.availableDays}
                        <span className="ml-1 text-sm font-normal text-muted">
                          {messages.leave.days}
                        </span>
                      </p>

                      {/* The figures behind the headline number, so it never has to be
                          taken on trust. */}
                      <dl className="mt-3 space-y-1 text-xs text-muted">
                        <Figure label={messages.leave.opening} value={balance.openingDays} />
                        <Figure label={messages.leave.accrued} value={balance.accruedDays} />
                        <Figure label={messages.leave.taken} value={balance.takenDays} />
                        <Figure label={messages.leave.pending} value={balance.pendingDays} />
                        <Figure
                          label={messages.leave.adjustment}
                          value={balance.adjustmentDays}
                        />
                      </dl>
                    </article>
                  ))}
                </div>
              )}
            </section>

            <section>
              <h2 className="mb-3 text-sm font-semibold text-navy">{messages.leave.requests}</h2>

              {requests === null && <p className="text-muted">{messages.common.loading}</p>}

              {requests?.length === 0 && (
                <div className="rounded-lg border border-line bg-white p-6 text-center">
                  <p className="text-muted">{messages.leave.noRequests}</p>
                </div>
              )}

              {requests && requests.length > 0 && (
                <div className="overflow-x-auto rounded-lg border border-line bg-white">
                  <table className="w-full text-left text-sm">
                    <thead className="border-b border-line bg-soft text-xs tracking-wide text-muted uppercase">
                      <tr>
                        <th scope="col" className="px-4 py-3 font-semibold">
                          {messages.leave.dates}
                        </th>
                        <th scope="col" className="px-4 py-3 font-semibold">
                          {messages.leave.type}
                        </th>
                        <th scope="col" className="px-4 py-3 font-semibold">
                          {messages.leave.days}
                        </th>
                        <th scope="col" className="px-4 py-3 font-semibold">
                          {messages.leave.status}
                        </th>
                        <th scope="col" className="px-4 py-3 font-semibold">
                          {messages.leave.approver}
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {requests.map((leaveRequest) => (
                        <tr key={leaveRequest.id} className="border-b border-line last:border-0">
                          <td className="px-4 py-3 text-ink">
                            {leaveRequest.startDate} {messages.leave.to} {leaveRequest.endDate}
                            {(leaveRequest.halfDayStart || leaveRequest.halfDayEnd) && (
                              <span className="ml-2 text-xs text-muted">
                                ({messages.leave.halfDay})
                              </span>
                            )}
                            {leaveRequest.reason && (
                              <p className="mt-1 text-xs text-muted">{leaveRequest.reason}</p>
                            )}
                          </td>
                          <td className="px-4 py-3 text-muted">{leaveRequest.leaveTypeName}</td>
                          <td className="px-4 py-3 font-medium tabular-nums text-ink">
                            {leaveRequest.days}
                          </td>
                          <td className="px-4 py-3">
                            <span
                              className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                                STATUS_STYLES[leaveRequest.status]
                              }`}
                            >
                              {messages.leave.requestStatus[leaveRequest.status]}
                            </span>
                          </td>
                          <td className="px-4 py-3 text-muted">
                            {leaveRequest.approverName ?? "—"}
                            {leaveRequest.decisionNotes && (
                              <p className="mt-1 text-xs">{leaveRequest.decisionNotes}</p>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          </div>
        </div>
      )}
    </div>
  );
}

function Figure({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-4">
      <dt>{label}</dt>
      <dd className="tabular-nums">{value}</dd>
    </div>
  );
}
