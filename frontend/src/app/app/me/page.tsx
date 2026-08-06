"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  meApi,
  type BookLeaveRequest,
  type LeaveTypeOption,
  type Me,
  type MyGoal,
  type MyLeaveBalance,
  type MyLeaveRequest,
  type MyPayslip,
  type MyReview,
  type MyTraining,
  type TeamMember,
} from "@/api/client";

const STATUS_STYLES: Record<string, string> = {
  SUBMITTED: "bg-warning/20 text-ink",
  APPROVED: "bg-green/10 text-green",
  REJECTED: "bg-error/10 text-error",
  CANCELLED: "bg-soft text-muted",
};

/** Amounts are decimal strings from the server and are never parsed into a float. */
function money(amount: string, currency: string) {
  return `${currency} ${amount}`;
}

export default function MyPage() {
  const messages = useMessages();
  const { status } = useSession();
  const ready = status === "authenticated";

  const [me, setMe] = useState<Me | null>(null);
  const [team, setTeam] = useState<TeamMember[]>([]);
  const [payslips, setPayslips] = useState<MyPayslip[]>([]);
  const [balances, setBalances] = useState<MyLeaveBalance[]>([]);
  const [types, setTypes] = useState<LeaveTypeOption[]>([]);
  const [requests, setRequests] = useState<MyLeaveRequest[]>([]);
  const [goals, setGoals] = useState<MyGoal[]>([]);
  const [reviews, setReviews] = useState<MyReview[]>([]);
  const [training, setTraining] = useState<MyTraining[]>([]);

  const [expanded, setExpanded] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notLinked, setNotLinked] = useState(false);
  const [booking, setBooking] = useState(false);
  const [bookingError, setBookingError] = useState<string | null>(null);

  const [form, setForm] = useState({
    leaveTypeId: "",
    startDate: "",
    endDate: "",
    halfDayStart: false,
    halfDayEnd: false,
    reason: "",
  });

  const load = useCallback(async () => {
    if (!ready) return;
    try {
      const profile = await meApi.profile();
      setMe(profile);
      setNotLinked(false);
      setError(null);

      // Each section fails on its own. A person whose sign-in cannot read one of these should
      // still get the rest of their own information rather than a blank page.
      const settle = <T,>(p: Promise<T[]>, set: (v: T[]) => void) =>
        p.then(set).catch(() => set([]));

      await Promise.all([
        settle(meApi.team(), setTeam),
        settle(meApi.payslips(), setPayslips),
        settle(meApi.leaveBalances(), setBalances),
        settle(meApi.leaveTypes(), setTypes),
        settle(meApi.leaveRequests(), setRequests),
        settle(meApi.goals(), setGoals),
        settle(meApi.reviews(), setReviews),
        settle(meApi.training(), setTraining),
      ]);
    } catch (caught) {
      // A sign-in with no employee record is an ordinary situation, not a fault, and saying so
      // is more use than "could not load".
      const message = caught instanceof Error ? caught.message : "";
      if (
        message.includes("403") ||
        message.toLowerCase().includes("forbidden")
      ) {
        setNotLinked(true);
      } else {
        setError(messages.meModule.loadFailed);
      }
    }
  }, [ready, messages.meModule.loadFailed]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    const first = types[0];
    if (!form.leaveTypeId && first) {
      setForm((current) => ({ ...current, leaveTypeId: first.id }));
    }
  }, [types, form.leaveTypeId]);

  async function book(event: React.FormEvent) {
    event.preventDefault();
    setBooking(true);
    setBookingError(null);
    try {
      const body: BookLeaveRequest = {
        leaveTypeId: form.leaveTypeId,
        startDate: form.startDate,
        endDate: form.endDate,
        halfDayStart: form.halfDayStart,
        halfDayEnd: form.halfDayEnd,
        reason: form.reason || null,
      };
      await meApi.bookLeave(body);
      setForm((current) => ({
        ...current,
        startDate: "",
        endDate: "",
        reason: "",
      }));
      const [freshRequests, freshBalances] = await Promise.all([
        meApi.leaveRequests(),
        meApi.leaveBalances(),
      ]);
      setRequests(freshRequests);
      setBalances(freshBalances);
    } catch {
      setBookingError(messages.meModule.bookingFailed);
    } finally {
      setBooking(false);
    }
  }

  async function cancel(id: string) {
    try {
      await meApi.cancelLeave(id);
      const [freshRequests, freshBalances] = await Promise.all([
        meApi.leaveRequests(),
        meApi.leaveBalances(),
      ]);
      setRequests(freshRequests);
      setBalances(freshBalances);
    } catch {
      setBookingError(messages.meModule.bookingFailed);
    }
  }

  if (notLinked) {
    return (
      <div className="mx-auto max-w-3xl">
        <div className="rounded-lg border border-line bg-white p-6">
          <p className="text-ink">{messages.meModule.notLinked}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6">
        <h1 className="text-3xl font-bold tracking-tight text-navy">
          {messages.meModule.title}
        </h1>
        <p className="mt-1 text-muted">{messages.meModule.subtitle}</p>
      </header>

      {error && (
        <div
          role="alert"
          className="rounded-lg border border-error/40 bg-error/10 p-5"
        >
          <p className="text-sm text-ink">{error}</p>
        </div>
      )}

      {!error && me === null && (
        <p className="text-muted">{messages.common.loading}</p>
      )}

      {!error && me && (
        <div className="flex flex-col gap-6">
          <section className="rounded-lg border border-line bg-white p-5">
            <h2 className="text-lg font-semibold text-navy">
              {me.displayName}
            </h2>
            <dl className="mt-3 grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
              <div>
                <dt className="text-xs text-muted">
                  {messages.meModule.employeeNumber}
                </dt>
                <dd className="text-ink">{me.employeeNumber}</dd>
              </div>
              <div>
                <dt className="text-xs text-muted">
                  {messages.meModule.hireDate}
                </dt>
                <dd className="text-ink">{me.hireDate}</dd>
              </div>
              <div>
                <dt className="text-xs text-muted">
                  {messages.meModule.manager}
                </dt>
                <dd className="text-ink">{me.managerName ?? "—"}</dd>
              </div>
              <div>
                <dt className="text-xs text-muted">
                  {messages.meModule.orgUnit}
                </dt>
                <dd className="text-ink">{me.orgUnitName ?? "—"}</dd>
              </div>
            </dl>
          </section>

          {/* Pay -------------------------------------------------------- */}
          <section>
            <h2 className="mb-3 text-sm font-semibold text-navy">
              {messages.meModule.payslips}
            </h2>

            {payslips.length === 0 && (
              <div className="rounded-lg border border-line bg-white p-6">
                <p className="text-muted">{messages.meModule.noPayslips}</p>
              </div>
            )}

            <div className="flex flex-col gap-3">
              {payslips.map((slip) => (
                <div
                  key={slip.id}
                  className="rounded-lg border border-line bg-white p-5"
                >
                  <div className="flex flex-wrap items-baseline justify-between gap-3">
                    <div>
                      <p className="font-medium text-ink">{slip.periodName}</p>
                      {slip.paymentDate && (
                        <p className="text-xs text-muted">
                          {interpolate(
                            messages.meModule.paidOn,
                            "Paid {date}",
                            {
                              date: slip.paymentDate,
                            },
                          )}
                        </p>
                      )}
                    </div>
                    <p className="text-2xl font-bold text-navy tabular-nums">
                      {money(slip.netPay, slip.currency)}
                    </p>
                  </div>

                  <div className="mt-2 flex flex-wrap gap-4 text-xs text-muted">
                    <span>
                      {messages.meModule.gross}:{" "}
                      {money(slip.grossEarnings, slip.currency)}
                    </span>
                    <span>
                      {messages.meModule.deductions}:{" "}
                      {money(slip.totalDeductions, slip.currency)}
                    </span>
                    <button
                      type="button"
                      onClick={() =>
                        setExpanded((current) =>
                          current === slip.id ? null : slip.id,
                        )
                      }
                      aria-expanded={expanded === slip.id}
                      className="font-medium text-blue hover:underline"
                    >
                      {expanded === slip.id
                        ? messages.meModule.hideBreakdown
                        : messages.meModule.breakdown}
                    </button>
                  </div>

                  {expanded === slip.id && (
                    <table className="mt-3 w-full text-left text-xs">
                      <thead className="text-muted">
                        <tr>
                          <th scope="col" className="py-1 font-semibold">
                            {messages.payrollModule.components}
                          </th>
                          <th scope="col" className="py-1 font-semibold">
                            {messages.meModule.basis}
                          </th>
                          <th
                            scope="col"
                            className="py-1 text-right font-semibold"
                          >
                            {messages.payrollModule.amount}
                          </th>
                        </tr>
                      </thead>
                      <tbody>
                        {slip.lines.map((line, index) => (
                          <tr key={`${slip.id}-${index}`}>
                            <td
                              className={`py-1 ${
                                line.componentType === "DEDUCTION"
                                  ? "text-error"
                                  : "text-ink"
                              }`}
                            >
                              {line.componentName}
                            </td>
                            <td className="py-1 text-muted">
                              {line.basis ?? "—"}
                            </td>
                            <td
                              className={`py-1 text-right tabular-nums ${
                                line.componentType === "DEDUCTION"
                                  ? "text-error"
                                  : "text-ink"
                              }`}
                            >
                              {money(line.amount, slip.currency)}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}
                </div>
              ))}
            </div>
          </section>

          {/* Leave ------------------------------------------------------ */}
          <section>
            <h2 className="mb-3 text-sm font-semibold text-navy">
              {messages.meModule.leave}
            </h2>

            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {balances.map((balance) => (
                <div
                  key={balance.leaveTypeId}
                  className="rounded-lg border border-line bg-white p-4"
                >
                  <p className="text-sm font-medium text-ink">
                    {balance.leaveTypeName}
                  </p>
                  <p className="mt-1 text-2xl font-bold text-navy tabular-nums">
                    {balance.availableDays}
                    <span className="ml-1 text-sm font-normal text-muted">
                      {messages.meModule.days}
                    </span>
                  </p>
                  <p className="mt-1 text-xs text-muted">
                    {messages.meModule.taken}: {balance.takenDays} ·{" "}
                    {messages.meModule.pending}: {balance.pendingDays}
                  </p>
                </div>
              ))}
            </div>

            {types.length > 0 && (
              <form
                onSubmit={book}
                className="mt-4 rounded-lg border border-line bg-white p-5"
              >
                <h3 className="mb-3 text-sm font-semibold text-navy">
                  {messages.meModule.bookLeave}
                </h3>

                <div className="grid gap-3 sm:grid-cols-3">
                  <label className="text-xs text-muted">
                    {messages.meModule.leaveType}
                    <select
                      value={form.leaveTypeId}
                      onChange={(e) =>
                        setForm({ ...form, leaveTypeId: e.target.value })
                      }
                      className="mt-1 w-full rounded border border-line px-2 py-1.5 text-sm text-ink"
                    >
                      {types.map((type) => (
                        <option key={type.id} value={type.id}>
                          {type.name}
                        </option>
                      ))}
                    </select>
                  </label>

                  <label className="text-xs text-muted">
                    {messages.meModule.from}
                    <input
                      type="date"
                      required
                      value={form.startDate}
                      onChange={(e) =>
                        setForm({ ...form, startDate: e.target.value })
                      }
                      className="mt-1 w-full rounded border border-line px-2 py-1.5 text-sm text-ink"
                    />
                  </label>

                  <label className="text-xs text-muted">
                    {messages.meModule.to}
                    <input
                      type="date"
                      required
                      value={form.endDate}
                      onChange={(e) =>
                        setForm({ ...form, endDate: e.target.value })
                      }
                      className="mt-1 w-full rounded border border-line px-2 py-1.5 text-sm text-ink"
                    />
                  </label>
                </div>

                <div className="mt-3 flex flex-wrap items-center gap-4 text-xs text-ink">
                  <label className="flex items-center gap-2">
                    <input
                      type="checkbox"
                      checked={form.halfDayStart}
                      onChange={(e) =>
                        setForm({ ...form, halfDayStart: e.target.checked })
                      }
                    />
                    {messages.meModule.halfDayStart}
                  </label>
                  <label className="flex items-center gap-2">
                    <input
                      type="checkbox"
                      checked={form.halfDayEnd}
                      onChange={(e) =>
                        setForm({ ...form, halfDayEnd: e.target.checked })
                      }
                    />
                    {messages.meModule.halfDayEnd}
                  </label>
                </div>

                <label className="mt-3 block text-xs text-muted">
                  {messages.meModule.reason}
                  <input
                    type="text"
                    value={form.reason}
                    onChange={(e) =>
                      setForm({ ...form, reason: e.target.value })
                    }
                    className="mt-1 w-full rounded border border-line px-2 py-1.5 text-sm text-ink"
                  />
                </label>

                {bookingError && (
                  <p role="alert" className="mt-3 text-sm text-error">
                    {bookingError}
                  </p>
                )}

                <button
                  type="submit"
                  disabled={booking}
                  className="mt-4 rounded bg-navy px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
                >
                  {booking
                    ? messages.meModule.submitting
                    : messages.meModule.submit}
                </button>
              </form>
            )}

            <h3 className="mt-5 mb-2 text-sm font-semibold text-navy">
              {messages.meModule.myRequests}
            </h3>

            {requests.length === 0 ? (
              <div className="rounded-lg border border-line bg-white p-6">
                <p className="text-muted">{messages.meModule.noRequests}</p>
              </div>
            ) : (
              <div className="overflow-x-auto rounded-lg border border-line bg-white">
                <table className="w-full text-left text-sm">
                  <tbody>
                    {requests.map((request) => (
                      <tr
                        key={request.id}
                        className="border-b border-line last:border-0"
                      >
                        <td className="px-4 py-3">
                          <span className="font-medium text-ink">
                            {request.leaveTypeName}
                          </span>
                          <p className="text-xs text-muted">
                            {request.startDate} → {request.endDate} ·{" "}
                            {request.days} {messages.meModule.days}
                          </p>
                        </td>
                        <td className="px-4 py-3">
                          <span
                            className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                              STATUS_STYLES[request.status] ??
                              "bg-soft text-muted"
                            }`}
                          >
                            {request.status}
                          </span>
                          {request.decisionNotes && (
                            <p className="mt-1 text-xs text-muted">
                              {request.decisionNotes}
                            </p>
                          )}
                        </td>
                        <td className="px-4 py-3 text-right">
                          {request.status === "SUBMITTED" && (
                            <button
                              type="button"
                              onClick={() => cancel(request.id)}
                              className="text-xs font-medium text-blue hover:underline"
                            >
                              {messages.meModule.cancel}
                            </button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          {/* Performance and training ----------------------------------- */}
          <div className="grid gap-6 lg:grid-cols-2">
            <section>
              <h2 className="mb-3 text-sm font-semibold text-navy">
                {messages.meModule.performance}
              </h2>

              <div className="rounded-lg border border-line bg-white p-5">
                {reviews.length === 0 && (
                  <p className="text-muted">{messages.meModule.noReviews}</p>
                )}
                {reviews.map((review) => (
                  <div
                    key={review.id}
                    className="border-b border-line pb-3 last:border-0"
                  >
                    <p className="font-medium text-ink">{review.cycleName}</p>
                    <p className="text-xs text-muted">
                      {messages.meModule.reviewer}: {review.reviewerName ?? "—"}
                    </p>
                    {review.reviewerAssessment ? (
                      <>
                        <p className="mt-2 text-sm text-ink">
                          {review.reviewerAssessment}
                        </p>
                        {review.effectiveRating && (
                          <p className="mt-1 text-xs text-navy">
                            {messages.meModule.rating}: {review.effectiveRating}
                          </p>
                        )}
                      </>
                    ) : (
                      <p className="mt-2 text-xs text-muted">
                        {messages.meModule.notSharedYet}
                      </p>
                    )}
                  </div>
                ))}

                <h3 className="mt-4 mb-2 text-xs font-semibold text-navy">
                  {messages.meModule.goals}
                </h3>
                {goals.length === 0 && (
                  <p className="text-xs text-muted">
                    {messages.meModule.noGoals}
                  </p>
                )}
                {goals.map((goal) => (
                  <div key={goal.id} className="mt-2">
                    <p className="text-sm text-ink">{goal.title}</p>
                    <div className="mt-1 h-1.5 w-full rounded bg-soft">
                      <div
                        className="h-1.5 rounded bg-blue"
                        style={{ width: `${goal.progressPercent}%` }}
                      />
                    </div>
                  </div>
                ))}
              </div>
            </section>

            <section>
              <h2 className="mb-3 text-sm font-semibold text-navy">
                {messages.meModule.training}
              </h2>

              <div className="rounded-lg border border-line bg-white p-5">
                {training.length === 0 && (
                  <p className="text-muted">{messages.meModule.noTraining}</p>
                )}
                {training.map((course) => (
                  <div
                    key={course.id}
                    className="border-b border-line py-2 last:border-0"
                  >
                    <p className="text-sm text-ink">
                      {course.courseTitle}
                      {course.mandatory && (
                        <span className="ml-2 rounded-full bg-navy/10 px-2 py-0.5 text-xs text-navy">
                          {messages.meModule.mandatory}
                        </span>
                      )}
                    </p>
                    <p
                      className={`text-xs ${
                        course.status === "EXPIRED"
                          ? "text-error"
                          : "text-muted"
                      }`}
                    >
                      {course.status} ·{" "}
                      {course.expiresOn
                        ? `${messages.meModule.expires} ${course.expiresOn}`
                        : messages.meModule.never}
                    </p>
                  </div>
                ))}
              </div>
            </section>
          </div>

          {/* Team ------------------------------------------------------- */}
          {team.length > 0 && (
            <section>
              <h2 className="mb-3 text-sm font-semibold text-navy">
                {messages.meModule.myTeam}
              </h2>
              <div className="overflow-x-auto rounded-lg border border-line bg-white">
                <table className="w-full text-left text-sm">
                  <tbody>
                    {team.map((member) => (
                      <tr
                        key={member.employeeId}
                        className="border-b border-line last:border-0"
                      >
                        <td className="px-4 py-3 font-medium text-ink">
                          {member.displayName}
                        </td>
                        <td className="px-4 py-3 text-xs text-muted">
                          {member.employeeNumber}
                        </td>
                        <td className="px-4 py-3 text-xs text-muted">
                          {member.status}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </div>
      )}
    </div>
  );
}
