"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  recruitmentApi,
  type Application,
  type ApplicationStatus,
  type Requisition,
  type RequisitionStatus,
} from "@/api/client";

const REQUISITION_STYLES: Record<RequisitionStatus, string> = {
  DRAFT: "bg-soft text-muted",
  PENDING_APPROVAL: "bg-warning/20 text-ink",
  APPROVED: "bg-blue/10 text-blue",
  OPEN: "bg-green/10 text-green",
  ON_HOLD: "bg-warning/20 text-ink",
  FILLED: "bg-navy/10 text-navy",
  CANCELLED: "bg-soft text-muted",
};

const APPLICATION_STYLES: Record<ApplicationStatus, string> = {
  APPLIED: "bg-soft text-muted",
  SCREENING: "bg-blue/10 text-blue",
  INTERVIEWING: "bg-blue/10 text-blue",
  OFFER: "bg-warning/20 text-ink",
  HIRED: "bg-green/10 text-green",
  REJECTED: "bg-error/10 text-error",
  WITHDRAWN: "bg-soft text-muted",
};

export default function RecruitmentPage() {
  const messages = useMessages();
  const { status } = useSession();
  // The proxy attaches the token; the page only needs to know whether it may call yet.
  const ready = status === "authenticated";

  const [requisitions, setRequisitions] = useState<Requisition[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [applications, setApplications] = useState<Application[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!ready) return;
    try {
      const list = await recruitmentApi.listRequisitions();
      setRequisitions(list);
      setError(null);
      // Opening on the first role means the page is never a list of links to nothing.
      setSelected((current) => current ?? list[0]?.id ?? null);
    } catch {
      setError(messages.recruitment.loadFailed);
    }
  }, [ready, messages.recruitment.loadFailed]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!ready || !selected) return;
    let cancelled = false;
    setApplications(null);
    recruitmentApi
      .applications(selected)
      .then((rows) => {
        // The pipeline of a role the user has since clicked away from is not worth showing.
        if (!cancelled) setApplications(rows);
      })
      .catch(() => {
        if (!cancelled) setError(messages.recruitment.loadFailed);
      });
    return () => {
      cancelled = true;
    };
  }, [ready, selected, messages.recruitment.loadFailed]);

  const openCount = requisitions?.filter((role) => role.status === "OPEN").length ?? 0;

  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6 flex items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-navy">
            {messages.recruitment.title}
          </h1>
          <p className="mt-1 text-muted">{messages.recruitment.subtitle}</p>
        </div>
        {openCount > 0 && (
          <p className="shrink-0 text-sm text-muted">
            {openCount} {messages.recruitment.openRoles}
          </p>
        )}
      </header>

      {error && (
        <div role="alert" className="rounded-lg border border-error/40 bg-error/10 p-5">
          <p className="text-sm text-ink">{error}</p>
        </div>
      )}

      {!error && requisitions === null && <p className="text-muted">{messages.common.loading}</p>}

      {!error && requisitions?.length === 0 && (
        <div className="rounded-lg border border-line bg-white p-10 text-center">
          <p className="text-muted">{messages.recruitment.empty}</p>
        </div>
      )}

      {!error && requisitions && requisitions.length > 0 && (
        <div className="grid gap-6 lg:grid-cols-[20rem_1fr]">
          <nav aria-label={messages.recruitment.title} className="flex flex-col gap-2">
            {requisitions.map((role) => (
              <button
                key={role.id}
                type="button"
                onClick={() => setSelected(role.id)}
                aria-current={role.id === selected}
                className={`rounded-lg border p-4 text-left transition ${
                  role.id === selected
                    ? "border-blue bg-blue/5"
                    : "border-line bg-white hover:border-blue/40"
                }`}
              >
                <div className="flex items-start justify-between gap-2">
                  <span className="font-semibold text-navy">{role.title}</span>
                  <span
                    className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${
                      REQUISITION_STYLES[role.status]
                    }`}
                  >
                    {messages.recruitment.requisitionStatus[role.status]}
                  </span>
                </div>
                <p className="mt-1 font-mono text-xs text-muted">{role.requisitionNumber}</p>
                <p className="mt-2 text-sm text-muted">
                  {interpolate(
                    messages.recruitment.filled,
                    "{filled} / {headcount}",
                    {
                      filled: String(role.filledCount),
                      headcount: String(role.headcount),
                    },
                  )}
                </p>
                {role.orgUnitName && (
                  <p className="mt-1 text-sm text-muted">{role.orgUnitName}</p>
                )}
              </button>
            ))}
          </nav>

          <section className="overflow-x-auto rounded-lg border border-line bg-white">
            <h2 className="border-b border-line px-4 py-3 text-sm font-semibold text-navy">
              {messages.recruitment.pipeline}
            </h2>

            {applications === null && (
              <p className="p-6 text-muted">{messages.common.loading}</p>
            )}

            {applications?.length === 0 && (
              <p className="p-6 text-muted">{messages.recruitment.emptyPipeline}</p>
            )}

            {applications && applications.length > 0 && (
              <table className="w-full text-left text-sm">
                <thead className="border-b border-line bg-soft text-xs tracking-wide text-muted uppercase">
                  <tr>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.recruitment.candidate}
                    </th>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.recruitment.stage}
                    </th>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.recruitment.applied}
                    </th>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.recruitment.outcome}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {applications.map((application) => (
                    <tr key={application.id} className="border-b border-line last:border-0">
                      <td className="px-4 py-3 font-medium text-ink">
                        {application.candidateName}
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                            APPLICATION_STYLES[application.status]
                          }`}
                        >
                          {messages.recruitment.applicationStatus[application.status]}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-muted">{application.appliedOn}</td>
                      <td className="px-4 py-3 text-muted">
                        {application.outcomeReason ?? messages.recruitment.noReason}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
