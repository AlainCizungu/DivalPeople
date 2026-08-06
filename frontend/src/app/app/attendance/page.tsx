"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import {
  attendanceApi,
  employeesApi,
  type EmployeeSummary,
  type TimeEntry,
  type TimesheetTotals,
} from "@/api/client";

/** Monday of the week containing a date. Weeks run Monday to Sunday, as the backend does. */
function mondayOf(date: Date): Date {
  const monday = new Date(date);
  const offset = (monday.getDay() + 6) % 7;
  monday.setDate(monday.getDate() - offset);
  monday.setHours(0, 0, 0, 0);
  return monday;
}

function isoDate(date: Date): string {
  return date.toISOString().slice(0, 10);
}

/** Minutes as "7h 30", because nobody reads a payslip in minutes. */
function hours(minutes: number): string {
  const sign = minutes < 0 ? "-" : "";
  const absolute = Math.abs(minutes);
  const h = Math.floor(absolute / 60);
  const m = absolute % 60;
  return m === 0 ? `${sign}${h}h` : `${sign}${h}h ${String(m).padStart(2, "0")}`;
}

function clockTime(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

export default function AttendancePage() {
  const messages = useMessages();
  const { status } = useSession();
  // The proxy attaches the token; the page only needs to know whether it may call yet.
  const ready = status === "authenticated";

  const [people, setPeople] = useState<EmployeeSummary[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [weekStart, setWeekStart] = useState<Date>(() => mondayOf(new Date()));
  const [entries, setEntries] = useState<TimeEntry[] | null>(null);
  const [totals, setTotals] = useState<TimesheetTotals | null>(null);
  const [error, setError] = useState<string | null>(null);

  const weekEnd = new Date(weekStart);
  weekEnd.setDate(weekEnd.getDate() + 6);

  const load = useCallback(async () => {
    if (!ready) return;
    try {
      const directory = await employeesApi.list();
      setPeople(directory);
      setError(null);
      setSelected((current) => current ?? directory[0]?.id ?? null);
    } catch {
      setError(messages.attendance.loadFailed);
    }
  }, [ready, messages.attendance.loadFailed]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!ready || !selected) return;
    let cancelled = false;
    setEntries(null);
    setTotals(null);

    const from = isoDate(weekStart);
    const to = isoDate(weekEnd);

    Promise.all([
      attendanceApi.entries(selected, from, to),
      attendanceApi.preview(selected, from, to),
    ])
      .then(([found, summary]) => {
        // Results for a week or a person the user has since moved away from are stale.
        if (cancelled) return;
        setEntries(found);
        setTotals(summary);
      })
      .catch(() => {
        if (!cancelled) setError(messages.attendance.loadFailed);
      });

    return () => {
      cancelled = true;
    };
    // weekStart is a Date; comparing by value avoids refetching on every render.
  }, [ready, selected, weekStart.getTime(), messages.attendance.loadFailed]);

  const shiftWeek = (weeks: number) => {
    const next = new Date(weekStart);
    next.setDate(next.getDate() + weeks * 7);
    setWeekStart(next);
  };

  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6">
        <h1 className="text-3xl font-bold tracking-tight text-navy">
          {messages.attendance.title}
        </h1>
        <p className="mt-1 text-muted">{messages.attendance.subtitle}</p>
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
            <div className="flex items-center justify-between gap-4">
              <button
                type="button"
                onClick={() => shiftWeek(-1)}
                className="rounded border border-line bg-white px-3 py-1.5 text-sm font-medium text-ink transition hover:border-blue/40"
              >
                ← {messages.attendance.previousWeek}
              </button>
              <p className="text-sm font-semibold text-navy">
                {messages.attendance.week} {isoDate(weekStart)} – {isoDate(weekEnd)}
              </p>
              <button
                type="button"
                onClick={() => shiftWeek(1)}
                className="rounded border border-line bg-white px-3 py-1.5 text-sm font-medium text-ink transition hover:border-blue/40"
              >
                {messages.attendance.nextWeek} →
              </button>
            </div>

            {totals && (
              <section
                aria-label={messages.attendance.summary}
                className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6"
              >
                <Figure label={messages.attendance.worked} value={hours(totals.worked)} strong />
                <Figure label={messages.attendance.expected} value={hours(totals.expected)} />
                <Figure label={messages.attendance.onLeave} value={hours(totals.leave)} />
                <Figure label={messages.attendance.holiday} value={hours(totals.holiday)} />
                <Figure
                  label={messages.attendance.overtime}
                  value={hours(totals.overtime)}
                  tone={totals.overtime > 0 ? "good" : undefined}
                />
                <Figure
                  label={messages.attendance.absent}
                  value={hours(totals.absent)}
                  tone={totals.absent > 0 ? "bad" : undefined}
                />
              </section>
            )}

            <section className="overflow-x-auto rounded-lg border border-line bg-white">
              <h2 className="border-b border-line px-4 py-3 text-sm font-semibold text-navy">
                {messages.attendance.entries}
              </h2>

              {entries === null && <p className="p-6 text-muted">{messages.common.loading}</p>}

              {entries?.length === 0 && (
                <p className="p-6 text-muted">{messages.attendance.noEntries}</p>
              )}

              {entries && entries.length > 0 && (
                <table className="w-full text-left text-sm">
                  <thead className="border-b border-line bg-soft text-xs tracking-wide text-muted uppercase">
                    <tr>
                      <th scope="col" className="px-4 py-3 font-semibold">
                        {messages.attendance.day}
                      </th>
                      <th scope="col" className="px-4 py-3 font-semibold">
                        {messages.attendance.from}
                      </th>
                      <th scope="col" className="px-4 py-3 font-semibold">
                        {messages.attendance.until}
                      </th>
                      <th scope="col" className="px-4 py-3 font-semibold">
                        {messages.attendance.break}
                      </th>
                      <th scope="col" className="px-4 py-3 font-semibold">
                        {messages.attendance.worked}
                      </th>
                      <th scope="col" className="px-4 py-3 font-semibold">
                        {messages.attendance.source}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {entries.map((entry) => (
                      <tr key={entry.id} className="border-b border-line last:border-0">
                        <td className="px-4 py-3 text-ink">{entry.workDate}</td>
                        <td className="px-4 py-3 text-muted tabular-nums">
                          {clockTime(entry.startedAt)}
                        </td>
                        <td className="px-4 py-3 text-muted tabular-nums">
                          {entry.endedAt ? (
                            clockTime(entry.endedAt)
                          ) : (
                            <span className="text-blue">
                              {messages.attendance.stillClockedIn}
                            </span>
                          )}
                        </td>
                        <td className="px-4 py-3 text-muted tabular-nums">
                          {entry.breakMinutes > 0 ? hours(entry.breakMinutes) : "—"}
                        </td>
                        <td className="px-4 py-3 font-medium text-ink tabular-nums">
                          {entry.endedAt ? hours(entry.workedMinutes) : "—"}
                        </td>
                        <td className="px-4 py-3 text-xs text-muted">
                          {messages.attendance.entrySource[entry.source]}
                          {entry.amendReason && (
                            <p className="mt-1">
                              <span className="rounded-full bg-warning/20 px-2 py-0.5 font-medium text-ink">
                                {messages.attendance.corrected}
                              </span>{" "}
                              {entry.amendReason}
                            </p>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </section>
          </div>
        </div>
      )}
    </div>
  );
}

function Figure({
  label,
  value,
  strong,
  tone,
}: {
  label: string;
  value: string;
  strong?: boolean;
  tone?: "good" | "bad";
}) {
  const colour =
    tone === "bad" ? "text-error" : tone === "good" ? "text-green" : "text-navy";
  return (
    <div className="rounded-lg border border-line bg-white p-3">
      <dt className="text-xs text-muted">{label}</dt>
      <dd
        className={`mt-1 tabular-nums ${strong ? "text-2xl font-bold" : "text-lg font-semibold"} ${colour}`}
      >
        {value}
      </dd>
    </div>
  );
}
