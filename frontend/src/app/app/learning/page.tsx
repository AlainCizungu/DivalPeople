"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  employeesApi,
  learningApi,
  type ComplianceGap,
  type Course,
  type CourseEnrolment,
  type EmployeeSummary,
  type EnrolmentStatus,
} from "@/api/client";

const STATUS_STYLES: Record<EnrolmentStatus, string> = {
  ENROLLED: "bg-soft text-muted",
  IN_PROGRESS: "bg-blue/10 text-blue",
  COMPLETED: "bg-green/10 text-green",
  FAILED: "bg-error/10 text-error",
  WITHDRAWN: "bg-soft text-muted",
  EXPIRED: "bg-warning/20 text-ink",
};

export default function LearningPage() {
  const messages = useMessages();
  const { status } = useSession();
  const ready = status === "authenticated";

  const [courses, setCourses] = useState<Course[] | null>(null);
  const [gaps, setGaps] = useState<ComplianceGap[] | null>(null);
  const [people, setPeople] = useState<EmployeeSummary[] | null>(null);
  const [person, setPerson] = useState<string | null>(null);
  const [records, setRecords] = useState<CourseEnrolment[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!ready) return;
    try {
      const [catalogue, directory] = await Promise.all([
        learningApi.courses(),
        employeesApi.list(),
      ]);
      setCourses(catalogue);
      setPeople(directory);
      setError(null);
      setPerson((current) => current ?? directory[0]?.id ?? null);

      // Compliance is held to narrower roles than the catalogue, so a refusal here is expected
      // rather than a failure — the rest of the page still works without it.
      learningApi
        .compliance()
        .then(setGaps)
        .catch(() => setGaps([]));
    } catch {
      setError(messages.learningModule.loadFailed);
    }
  }, [ready, messages.learningModule.loadFailed]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!ready || !person) return;
    let cancelled = false;
    setRecords(null);
    learningApi
      .enrolments(person)
      .then((found) => {
        if (!cancelled) setRecords(found);
      })
      .catch(() => {
        if (!cancelled) setError(messages.learningModule.loadFailed);
      });
    return () => {
      cancelled = true;
    };
  }, [ready, person, messages.learningModule.loadFailed]);

  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6">
        <h1 className="text-3xl font-bold tracking-tight text-navy">
          {messages.learningModule.title}
        </h1>
        <p className="mt-1 text-muted">{messages.learningModule.subtitle}</p>
      </header>

      {error && (
        <div role="alert" className="rounded-lg border border-error/40 bg-error/10 p-5">
          <p className="text-sm text-ink">{error}</p>
        </div>
      )}

      {!error && courses === null && <p className="text-muted">{messages.common.loading}</p>}

      {!error && courses && (
        <div className="flex flex-col gap-6">
          {/* The compliance gap comes first. It is the question the module exists to answer,
              and burying it under a catalogue would be the wrong emphasis. */}
          {gaps && gaps.length > 0 && (
            <section className="rounded-lg border border-error/40 bg-error/5 p-5">
              <h2 className="mb-3 text-sm font-semibold text-error">
                {messages.learningModule.compliance}
              </h2>
              <div className="flex flex-col gap-3">
                {gaps.map((gap) => (
                  <div key={gap.courseId}>
                    <p className="font-medium text-ink">
                      {gap.courseTitle}
                      <span className="ml-2 text-sm text-error">
                        {interpolate(
                          messages.learningModule.missingCount,
                          "{count} not qualified",
                          { count: String(gap.missingCount) },
                        )}
                      </span>
                    </p>
                    <p className="mt-1 text-sm text-muted">
                      {gap.missing.map((who) => who.displayName).join(", ")}
                    </p>
                  </div>
                ))}
              </div>
            </section>
          )}

          {gaps && gaps.length === 0 && (
            <section className="rounded-lg border border-green/40 bg-green/5 p-4">
              <p className="text-sm text-green">{messages.learningModule.noGaps}</p>
            </section>
          )}

          <section>
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <h2 className="text-sm font-semibold text-navy">
                {messages.learningModule.records}
              </h2>
              {people?.map((candidate) => (
                <button
                  key={candidate.id}
                  type="button"
                  onClick={() => setPerson(candidate.id)}
                  aria-current={candidate.id === person}
                  className={`rounded border px-3 py-1 text-xs transition ${
                    candidate.id === person
                      ? "border-blue bg-blue/5 font-semibold text-navy"
                      : "border-line bg-white text-ink hover:border-blue/40"
                  }`}
                >
                  {candidate.displayName}
                </button>
              ))}
            </div>

            {records === null && <p className="text-muted">{messages.common.loading}</p>}
            {records?.length === 0 && (
              <div className="rounded-lg border border-line bg-white p-6 text-center">
                <p className="text-muted">{messages.learningModule.noRecords}</p>
              </div>
            )}

            {records && records.length > 0 && (
              <div className="overflow-x-auto rounded-lg border border-line bg-white">
                <table className="w-full text-left text-sm">
                  <thead className="border-b border-line bg-soft text-xs tracking-wide text-muted uppercase">
                    <tr>
                      <th scope="col" className="px-4 py-3 font-semibold">
                        {messages.learningModule.course}
                      </th>
                      <th scope="col" className="px-4 py-3 font-semibold">
                        {messages.learningModule.completed}
                      </th>
                      <th scope="col" className="px-4 py-3 font-semibold">
                        {messages.learningModule.expires}
                      </th>
                      <th scope="col" className="px-4 py-3 font-semibold">
                        {messages.learningModule.score}
                      </th>
                      <th scope="col" className="px-4 py-3 font-semibold">
                        {messages.learningModule.status}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {records.map((record) => (
                      <tr key={record.id} className="border-b border-line last:border-0">
                        <td className="px-4 py-3">
                          <span className="font-medium text-ink">{record.courseTitle}</span>
                          {record.mandatory && (
                            <span className="ml-2 rounded-full bg-navy/10 px-2 py-0.5 text-xs font-medium text-navy">
                              {messages.learningModule.mandatory}
                            </span>
                          )}
                          {record.notes && (
                            <p className="mt-1 text-xs text-muted">{record.notes}</p>
                          )}
                        </td>
                        <td className="px-4 py-3 text-muted">{record.completedOn ?? "—"}</td>
                        <td
                          className={`px-4 py-3 ${
                            record.status === "EXPIRED" ? "font-medium text-error" : "text-muted"
                          }`}
                        >
                          {record.expiresOn ?? messages.learningModule.never}
                        </td>
                        <td className="px-4 py-3 text-muted tabular-nums">
                          {record.score === null ? "—" : `${record.score}%`}
                        </td>
                        <td className="px-4 py-3">
                          <span
                            className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                              STATUS_STYLES[record.status]
                            }`}
                          >
                            {messages.learningModule.enrolmentStatus[record.status]}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="overflow-x-auto rounded-lg border border-line bg-white">
            <h2 className="border-b border-line px-4 py-3 text-sm font-semibold text-navy">
              {messages.learningModule.catalogue}
            </h2>

            {courses.length === 0 && (
              <p className="p-6 text-muted">{messages.learningModule.noCourses}</p>
            )}

            {courses.length > 0 && (
              <table className="w-full text-left text-sm">
                <tbody>
                  {courses.map((course) => (
                    <tr key={course.id} className="border-b border-line last:border-0">
                      <td className="px-4 py-3">
                        <span className="font-medium text-ink">{course.title}</span>
                        {course.mandatory && (
                          <span className="ml-2 rounded-full bg-navy/10 px-2 py-0.5 text-xs font-medium text-navy">
                            {messages.learningModule.mandatory}
                          </span>
                        )}
                        {course.description && (
                          <p className="mt-1 text-xs text-muted">{course.description}</p>
                        )}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted">
                        {messages.learningModule.deliveryMode[course.deliveryMode]}
                        {course.provider && ` · ${course.provider}`}
                      </td>
                      <td className="px-4 py-3 text-xs text-muted">
                        {course.validityMonths
                          ? `${messages.learningModule.validity} ${course.validityMonths} ${messages.learningModule.months}`
                          : messages.learningModule.never}
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
