"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  employeesApi,
  performanceApi,
  type EmployeeSummary,
  type Goal,
  type GoalStatus,
  type PerformanceReview,
  type ReviewCycle,
  type ReviewStatus,
} from "@/api/client";

const GOAL_STYLES: Record<GoalStatus, string> = {
  DRAFT: "bg-soft text-muted",
  ACTIVE: "bg-blue/10 text-blue",
  ACHIEVED: "bg-green/10 text-green",
  PARTIALLY_MET: "bg-warning/20 text-ink",
  MISSED: "bg-error/10 text-error",
  CANCELLED: "bg-soft text-muted",
};

const REVIEW_STYLES: Record<ReviewStatus, string> = {
  PENDING: "bg-soft text-muted",
  IN_PROGRESS: "bg-blue/10 text-blue",
  BOTH_SUBMITTED: "bg-blue/10 text-blue",
  CALIBRATED: "bg-warning/20 text-ink",
  SHARED: "bg-green/10 text-green",
  ACKNOWLEDGED: "bg-green/10 text-green",
};

export default function PerformancePage() {
  const messages = useMessages();
  const { status } = useSession();
  const ready = status === "authenticated";

  const [cycles, setCycles] = useState<ReviewCycle[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [reviews, setReviews] = useState<PerformanceReview[] | null>(null);
  const [people, setPeople] = useState<EmployeeSummary[] | null>(null);
  const [person, setPerson] = useState<string | null>(null);
  const [goals, setGoals] = useState<Goal[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!ready) return;
    try {
      const [foundCycles, directory] = await Promise.all([
        performanceApi.cycles(),
        employeesApi.list(),
      ]);
      setCycles(foundCycles);
      setPeople(directory);
      setError(null);
      setSelected((current) => current ?? foundCycles[0]?.id ?? null);
      setPerson((current) => current ?? directory[0]?.id ?? null);
    } catch {
      setError(messages.performance.loadFailed);
    }
  }, [ready, messages.performance.loadFailed]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!ready || !selected) return;
    let cancelled = false;
    setReviews(null);
    performanceApi
      .reviewsInCycle(selected)
      .then((found) => {
        if (!cancelled) setReviews(found);
      })
      .catch(() => {
        if (!cancelled) setError(messages.performance.loadFailed);
      });
    return () => {
      cancelled = true;
    };
  }, [ready, selected, messages.performance.loadFailed]);

  useEffect(() => {
    if (!ready || !person) return;
    let cancelled = false;
    setGoals(null);
    performanceApi
      .goals(person)
      .then((found) => {
        if (!cancelled) setGoals(found);
      })
      .catch(() => {
        if (!cancelled) setError(messages.performance.loadFailed);
      });
    return () => {
      cancelled = true;
    };
  }, [ready, person, messages.performance.loadFailed]);

  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6">
        <h1 className="text-3xl font-bold tracking-tight text-navy">
          {messages.performance.title}
        </h1>
        <p className="mt-1 text-muted">{messages.performance.subtitle}</p>
      </header>

      {error && (
        <div role="alert" className="rounded-lg border border-error/40 bg-error/10 p-5">
          <p className="text-sm text-ink">{error}</p>
        </div>
      )}

      {!error && cycles === null && <p className="text-muted">{messages.common.loading}</p>}

      {!error && cycles?.length === 0 && (
        <div className="rounded-lg border border-line bg-white p-10 text-center">
          <p className="text-muted">{messages.performance.noCycles}</p>
        </div>
      )}

      {!error && cycles && cycles.length > 0 && (
        <div className="flex flex-col gap-6">
          <nav aria-label={messages.performance.cycle} className="flex flex-wrap gap-2">
            {cycles.map((cycle) => (
              <button
                key={cycle.id}
                type="button"
                onClick={() => setSelected(cycle.id)}
                aria-current={cycle.id === selected}
                className={`rounded-lg border px-4 py-2 text-sm transition ${
                  cycle.id === selected
                    ? "border-blue bg-blue/5 font-semibold text-navy"
                    : "border-line bg-white text-ink hover:border-blue/40"
                }`}
              >
                {cycle.name}
                <span className="ml-2 text-xs text-muted">
                  {messages.performance.cycleStatus[cycle.status]}
                </span>
              </button>
            ))}
          </nav>

          <section className="overflow-x-auto rounded-lg border border-line bg-white">
            <h2 className="border-b border-line px-4 py-3 text-sm font-semibold text-navy">
              {messages.performance.reviews}
            </h2>

            {reviews === null && <p className="p-6 text-muted">{messages.common.loading}</p>}
            {reviews?.length === 0 && (
              <p className="p-6 text-muted">{messages.performance.noReviews}</p>
            )}

            {reviews && reviews.length > 0 && (
              <table className="w-full text-left text-sm">
                <thead className="border-b border-line bg-soft text-xs tracking-wide text-muted uppercase">
                  <tr>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.performance.employee}
                    </th>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.performance.reviewer}
                    </th>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.performance.rating}
                    </th>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.performance.status}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {reviews.map((review) => (
                    <tr key={review.id} className="border-b border-line last:border-0">
                      <td className="px-4 py-3 font-medium text-ink">{review.employeeName}</td>
                      <td className="px-4 py-3 text-muted">{review.reviewerName}</td>
                      <td className="px-4 py-3">
                        {review.effectiveRating ? (
                          <>
                            <span className="font-medium text-ink">
                              {messages.performance.rating_[review.effectiveRating]}
                            </span>
                            {/* Calibration is shown, not hidden: an adjustment nobody can see
                                is the thing that makes moderation untrustworthy. */}
                            {review.calibratedRating &&
                              review.proposedRating &&
                              review.calibratedRating !== review.proposedRating && (
                                <p className="mt-1 text-xs text-muted">
                                  {interpolate(
                                    messages.performance.moderated,
                                    "from {from}",
                                    {
                                      from: messages.performance.rating_[review.proposedRating],
                                    },
                                  )}
                                </p>
                              )}
                          </>
                        ) : (
                          <span className="text-muted">{messages.performance.notYet}</span>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                            REVIEW_STYLES[review.status]
                          }`}
                        >
                          {messages.performance.reviewStatus[review.status]}
                        </span>
                        {review.employeeDisagrees && (
                          <span className="ml-2 rounded-full bg-error/10 px-2 py-0.5 text-xs font-medium text-error">
                            {messages.performance.disagreed}
                          </span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </section>

          <section>
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <h2 className="text-sm font-semibold text-navy">{messages.performance.goals}</h2>
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

            {goals === null && <p className="text-muted">{messages.common.loading}</p>}
            {goals?.length === 0 && (
              <div className="rounded-lg border border-line bg-white p-6 text-center">
                <p className="text-muted">{messages.performance.noGoals}</p>
              </div>
            )}

            {goals && goals.length > 0 && (
              <div className="grid gap-3 md:grid-cols-2">
                {goals.map((goal) => (
                  <article key={goal.id} className="rounded-lg border border-line bg-white p-4">
                    <div className="flex items-start justify-between gap-2">
                      <p className="font-semibold text-navy">{goal.title}</p>
                      <span
                        className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-medium ${
                          GOAL_STYLES[goal.status]
                        }`}
                      >
                        {messages.performance.goalStatus[goal.status]}
                      </span>
                    </div>

                    {goal.measure && <p className="mt-1 text-sm text-muted">{goal.measure}</p>}

                    <div className="mt-3">
                      <div className="h-2 w-full rounded-full bg-soft">
                        <div
                          className="h-2 rounded-full bg-blue"
                          style={{ width: `${goal.progressPercent}%` }}
                        />
                      </div>
                      <p className="mt-1 text-xs text-muted tabular-nums">
                        {goal.progressPercent}% · {messages.performance.weight} {goal.weight}
                        {goal.targetDate && ` · ${messages.performance.target} ${goal.targetDate}`}
                      </p>
                    </div>

                    {goal.outcomeNotes && (
                      <p className="mt-2 text-xs text-muted">{goal.outcomeNotes}</p>
                    )}
                  </article>
                ))}
              </div>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
