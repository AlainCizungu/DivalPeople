"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  lifecycleApi,
  type ChecklistDetail,
  type ChecklistSummary,
  type ChecklistType,
  type ItemStatus,
} from "@/api/client";

const TYPE_STYLES: Record<ChecklistType, string> = {
  ONBOARDING: "bg-green/10 text-green",
  OFFBOARDING: "bg-warning/20 text-ink",
};

const ITEM_STYLES: Record<ItemStatus, string> = {
  PENDING: "bg-soft text-muted",
  DONE: "bg-green/10 text-green",
  BLOCKED: "bg-error/10 text-error",
  NOT_APPLICABLE: "bg-soft text-muted",
};

export default function LifecyclePage() {
  const messages = useMessages();
  const { status } = useSession();
  // The proxy attaches the token; the page only needs to know whether it may call yet.
  const ready = status === "authenticated";

  const [checklists, setChecklists] = useState<ChecklistSummary[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<ChecklistDetail | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!ready) return;
    try {
      const list = await lifecycleApi.open();
      setChecklists(list);
      setError(null);
      setSelected((current) => current ?? list[0]?.id ?? null);
    } catch {
      setError(messages.lifecycle.loadFailed);
    }
  }, [ready, messages.lifecycle.loadFailed]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!ready || !selected) return;
    let cancelled = false;
    setDetail(null);
    lifecycleApi
      .checklist(selected)
      .then((found) => {
        // The steps of a list the user has since clicked away from are not worth showing.
        if (!cancelled) setDetail(found);
      })
      .catch(() => {
        if (!cancelled) setError(messages.lifecycle.loadFailed);
      });
    return () => {
      cancelled = true;
    };
  }, [ready, selected, messages.lifecycle.loadFailed]);

  const today = new Date().toISOString().slice(0, 10);

  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6 flex items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-navy">
            {messages.lifecycle.title}
          </h1>
          <p className="mt-1 text-muted">{messages.lifecycle.subtitle}</p>
        </div>
        {checklists && checklists.length > 0 && (
          <p className="shrink-0 text-sm text-muted">
            {checklists.length} {messages.lifecycle.inProgress}
          </p>
        )}
      </header>

      {error && (
        <div role="alert" className="rounded-lg border border-error/40 bg-error/10 p-5">
          <p className="text-sm text-ink">{error}</p>
        </div>
      )}

      {!error && checklists === null && <p className="text-muted">{messages.common.loading}</p>}

      {!error && checklists?.length === 0 && (
        <div className="rounded-lg border border-line bg-white p-10 text-center">
          <p className="text-muted">{messages.lifecycle.empty}</p>
        </div>
      )}

      {!error && checklists && checklists.length > 0 && (
        <div className="grid gap-6 lg:grid-cols-[20rem_1fr]">
          <nav aria-label={messages.lifecycle.title} className="flex flex-col gap-2">
            {checklists.map((checklist) => (
              <button
                key={checklist.id}
                type="button"
                onClick={() => setSelected(checklist.id)}
                aria-current={checklist.id === selected}
                className={`rounded-lg border p-4 text-left transition ${
                  checklist.id === selected
                    ? "border-blue bg-blue/5"
                    : "border-line bg-white hover:border-blue/40"
                }`}
              >
                <div className="flex items-start justify-between gap-2">
                  <span className="font-semibold text-navy">{checklist.employeeName}</span>
                  <span
                    className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${
                      TYPE_STYLES[checklist.checklistType]
                    }`}
                  >
                    {messages.lifecycle.checklistType[checklist.checklistType]}
                  </span>
                </div>
                <p className="mt-1 text-sm text-muted">{checklist.templateName}</p>
                <p className="mt-2 text-sm text-muted">
                  {interpolate(messages.lifecycle.progress, "{settled} / {total}", {
                    settled: String(checklist.settledCount),
                    total: String(checklist.itemCount),
                  })}
                </p>
                {checklist.outstandingMandatory > 0 && (
                  <p className="mt-1 text-sm text-error">
                    {interpolate(messages.lifecycle.outstanding, "{count}", {
                      count: String(checklist.outstandingMandatory),
                    })}
                  </p>
                )}
              </button>
            ))}
          </nav>

          <section className="overflow-x-auto rounded-lg border border-line bg-white">
            <h2 className="border-b border-line px-4 py-3 text-sm font-semibold text-navy">
              {messages.lifecycle.steps}
            </h2>

            {detail === null && <p className="p-6 text-muted">{messages.common.loading}</p>}

            {detail && (
              <table className="w-full text-left text-sm">
                <thead className="border-b border-line bg-soft text-xs tracking-wide text-muted uppercase">
                  <tr>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.lifecycle.step}
                    </th>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.lifecycle.owner}
                    </th>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.lifecycle.due}
                    </th>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.lifecycle.status}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {detail.items.map((item) => {
                    // Overdue is worth seeing at a glance; it is the whole reason for due dates.
                    const overdue =
                      item.status === "PENDING" && item.dueOn !== null && item.dueOn < today;
                    return (
                      <tr key={item.id} className="border-b border-line last:border-0">
                        <td className="px-4 py-3">
                          <span className="font-medium text-ink">{item.title}</span>
                          <span className="ml-2 text-xs text-muted">
                            {messages.lifecycle.category[item.category]}
                          </span>
                          {item.mandatory && (
                            <span className="ml-2 rounded-full bg-navy/10 px-2 py-0.5 text-xs font-medium text-navy">
                              {messages.lifecycle.required}
                            </span>
                          )}
                          {item.notes && (
                            <p className="mt-1 text-xs text-muted">{item.notes}</p>
                          )}
                        </td>
                        <td className="px-4 py-3 text-muted">
                          {item.assigneeName ?? messages.lifecycle.unassigned}
                        </td>
                        <td className={`px-4 py-3 ${overdue ? "font-medium text-error" : "text-muted"}`}>
                          {item.dueOn ?? messages.lifecycle.noDue}
                        </td>
                        <td className="px-4 py-3">
                          <span
                            className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                              ITEM_STYLES[item.status]
                            }`}
                          >
                            {messages.lifecycle.itemStatus[item.status]}
                          </span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
