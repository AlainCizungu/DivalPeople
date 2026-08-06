"use client";

import { Fragment, useCallback, useEffect, useState } from "react";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  payrollApi,
  type ComponentType,
  type PayComponent,
  type Payslip,
  type PayrollPeriod,
  type PeriodStatus,
} from "@/api/client";

const STATUS_STYLES: Record<PeriodStatus, string> = {
  DRAFT: "bg-soft text-muted",
  CALCULATED: "bg-blue/10 text-blue",
  APPROVED: "bg-green/10 text-green",
  PAID: "bg-navy/10 text-navy",
};

const LINE_STYLES: Record<ComponentType, string> = {
  EARNING: "text-ink",
  DEDUCTION: "text-error",
  EMPLOYER_CONTRIBUTION: "text-muted",
};

/**
 * Amounts arrive as decimal strings, not numbers, and are formatted rather than parsed into a
 * float. A payslip figure must be the one the server calculated to the cent, and binary floating
 * point is the standard way that stops being true.
 */
function money(amount: string, currency: string): string {
  return `${currency} ${amount}`;
}

export default function PayrollPage() {
  const messages = useMessages();
  const { status } = useSession();
  const ready = status === "authenticated";

  const [periods, setPeriods] = useState<PayrollPeriod[] | null>(null);
  const [selected, setSelected] = useState<string | null>(null);
  const [payslips, setPayslips] = useState<Payslip[] | null>(null);
  const [components, setComponents] = useState<PayComponent[] | null>(null);
  const [expanded, setExpanded] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!ready) return;
    try {
      const runs = await payrollApi.periods();
      setPeriods(runs);
      setError(null);
      setSelected((current) => current ?? runs[0]?.id ?? null);

      // The component catalogue is held to the same roles as the runs, but a refusal here
      // should not blank the page that answers what people were paid.
      payrollApi
        .components()
        .then(setComponents)
        .catch(() => setComponents([]));
    } catch {
      setError(messages.payrollModule.loadFailed);
    }
  }, [ready, messages.payrollModule.loadFailed]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!ready || !selected) return;
    let cancelled = false;
    setPayslips(null);
    setExpanded(null);
    payrollApi
      .payslipsIn(selected)
      .then((found) => {
        if (!cancelled) setPayslips(found);
      })
      .catch(() => {
        if (!cancelled) setError(messages.payrollModule.loadFailed);
      });
    return () => {
      cancelled = true;
    };
  }, [ready, selected, messages.payrollModule.loadFailed]);

  const run = periods?.find((period) => period.id === selected) ?? null;

  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6">
        <h1 className="text-3xl font-bold tracking-tight text-navy">
          {messages.payrollModule.title}
        </h1>
        <p className="mt-1 text-muted">{messages.payrollModule.subtitle}</p>
      </header>

      {/* Stated on the screen, not only in the documentation. Somebody approving a run should
          not have to read a repository to learn that no statutory rate is applied here. */}
      <p className="mb-6 rounded-lg border border-warning/40 bg-warning/10 p-4 text-sm text-ink">
        {messages.payrollModule.scopeNotice}
      </p>

      {error && (
        <div
          role="alert"
          className="rounded-lg border border-error/40 bg-error/10 p-5"
        >
          <p className="text-sm text-ink">{error}</p>
        </div>
      )}

      {!error && periods === null && (
        <p className="text-muted">{messages.common.loading}</p>
      )}

      {!error && periods?.length === 0 && (
        <div className="rounded-lg border border-line bg-white p-6 text-center">
          <p className="text-muted">{messages.payrollModule.noPeriods}</p>
        </div>
      )}

      {!error && periods && periods.length > 0 && (
        <div className="flex flex-col gap-6">
          <div className="flex flex-wrap items-center gap-2">
            {periods.map((period) => (
              <button
                key={period.id}
                type="button"
                onClick={() => setSelected(period.id)}
                aria-current={period.id === selected}
                className={`rounded border px-3 py-1 text-xs transition ${
                  period.id === selected
                    ? "border-blue bg-blue/5 font-semibold text-navy"
                    : "border-line bg-white text-ink hover:border-blue/40"
                }`}
              >
                {period.name}
              </button>
            ))}
          </div>

          {run && (
            <section className="rounded-lg border border-line bg-white p-5">
              <div className="flex flex-wrap items-center gap-3">
                <h2 className="text-lg font-semibold text-navy">{run.name}</h2>
                <span
                  className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                    STATUS_STYLES[run.status]
                  }`}
                >
                  {messages.payrollModule.status[run.status]}
                </span>
              </div>
              <p className="mt-1 text-sm text-muted">
                {interpolate(
                  messages.payrollModule.periodDates,
                  "{start} to {end}",
                  {
                    start: run.periodStart,
                    end: run.periodEnd,
                  },
                )}
                {run.paymentDate &&
                  ` · ${messages.payrollModule.paymentDate}: ${run.paymentDate}`}
              </p>
              {run.approverName && (
                <p className="mt-1 text-sm text-green">
                  {interpolate(
                    messages.payrollModule.approvedBy,
                    "Approved by {name}",
                    {
                      name: run.approverName,
                    },
                  )}
                </p>
              )}
            </section>
          )}

          {payslips === null && (
            <p className="text-muted">{messages.common.loading}</p>
          )}

          {payslips?.length === 0 && (
            <div className="rounded-lg border border-line bg-white p-6 text-center">
              <p className="text-muted">{messages.payrollModule.noPayslips}</p>
            </div>
          )}

          {payslips && payslips.length > 0 && (
            <section className="overflow-x-auto rounded-lg border border-line bg-white">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-line bg-soft text-xs tracking-wide text-muted uppercase">
                  <tr>
                    <th scope="col" className="px-4 py-3 font-semibold">
                      {messages.payrollModule.employee}
                    </th>
                    <th
                      scope="col"
                      className="px-4 py-3 text-right font-semibold"
                    >
                      {messages.payrollModule.basic}
                    </th>
                    <th
                      scope="col"
                      className="px-4 py-3 text-right font-semibold"
                    >
                      {messages.payrollModule.gross}
                    </th>
                    <th
                      scope="col"
                      className="px-4 py-3 text-right font-semibold"
                    >
                      {messages.payrollModule.deductions}
                    </th>
                    <th
                      scope="col"
                      className="px-4 py-3 text-right font-semibold"
                    >
                      {messages.payrollModule.net}
                    </th>
                    <th scope="col" className="px-4 py-3" />
                  </tr>
                </thead>
                <tbody>
                  {payslips.map((slip) => (
                    <Fragment key={slip.id}>
                      <tr className="border-b border-line last:border-0">
                        <td className="px-4 py-3">
                          <span className="font-medium text-ink">
                            {slip.employeeName}
                          </span>
                          <span className="ml-2 text-xs text-muted">
                            {slip.employeeNumber}
                          </span>
                          {Number(slip.unpaidLeaveDays) > 0 && (
                            <p className="mt-1 text-xs text-warning">
                              {interpolate(
                                messages.payrollModule.unpaidLeave,
                                "{days} days unpaid leave",
                                { days: slip.unpaidLeaveDays },
                              )}
                            </p>
                          )}
                          {slip.overtimeMinutes > 0 && (
                            <p className="mt-1 text-xs text-muted">
                              {interpolate(
                                messages.payrollModule.overtime,
                                "{hours} h overtime",
                                {
                                  hours: (slip.overtimeMinutes / 60).toFixed(1),
                                },
                              )}
                            </p>
                          )}
                        </td>
                        <td className="px-4 py-3 text-right text-muted tabular-nums">
                          {money(slip.baseAmount, slip.currency)}
                        </td>
                        <td className="px-4 py-3 text-right text-ink tabular-nums">
                          {money(slip.grossEarnings, slip.currency)}
                        </td>
                        <td className="px-4 py-3 text-right text-error tabular-nums">
                          {money(slip.totalDeductions, slip.currency)}
                        </td>
                        <td className="px-4 py-3 text-right font-semibold text-navy tabular-nums">
                          {money(slip.netPay, slip.currency)}
                        </td>
                        <td className="px-4 py-3 text-right">
                          <button
                            type="button"
                            onClick={() =>
                              setExpanded((current) =>
                                current === slip.id ? null : slip.id,
                              )
                            }
                            aria-expanded={expanded === slip.id}
                            className="text-xs font-medium text-blue hover:underline"
                          >
                            {expanded === slip.id
                              ? messages.payrollModule.hideBreakdown
                              : messages.payrollModule.breakdown}
                          </button>
                        </td>
                      </tr>

                      {/* Every line carries the server's own account of how it reached its
                          figure. A payslip nobody can check is a payslip nobody should trust. */}
                      {expanded === slip.id && (
                        <tr className="border-b border-line last:border-0">
                          <td colSpan={6} className="bg-soft/50 px-4 py-3">
                            <table className="w-full text-left text-xs">
                              <thead className="text-muted">
                                <tr>
                                  <th
                                    scope="col"
                                    className="py-1 font-semibold"
                                  >
                                    {messages.payrollModule.components}
                                  </th>
                                  <th
                                    scope="col"
                                    className="py-1 font-semibold"
                                  >
                                    {messages.payrollModule.basis}
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
                                {slip.lines.map((line) => (
                                  <tr key={line.id}>
                                    <td className="py-1">
                                      <span
                                        className={
                                          LINE_STYLES[line.componentType]
                                        }
                                      >
                                        {line.componentName}
                                      </span>
                                      <span className="ml-2 text-muted">
                                        {
                                          messages.payrollModule.componentType[
                                            line.componentType
                                          ]
                                        }
                                      </span>
                                    </td>
                                    <td className="py-1 text-muted">
                                      {line.basis ?? "—"}
                                    </td>
                                    <td
                                      className={`py-1 text-right tabular-nums ${
                                        LINE_STYLES[line.componentType]
                                      }`}
                                    >
                                      {money(line.amount, slip.currency)}
                                    </td>
                                  </tr>
                                ))}
                                <tr className="border-t border-line font-semibold">
                                  <td className="py-1 text-navy" colSpan={2}>
                                    {messages.payrollModule.net}
                                  </td>
                                  <td className="py-1 text-right text-navy tabular-nums">
                                    {money(slip.netPay, slip.currency)}
                                  </td>
                                </tr>
                                <tr>
                                  <td className="py-1 text-muted" colSpan={2}>
                                    {messages.payrollModule.employerCost}
                                  </td>
                                  <td className="py-1 text-right text-muted tabular-nums">
                                    {money(slip.employerCost, slip.currency)}
                                  </td>
                                </tr>
                              </tbody>
                            </table>
                          </td>
                        </tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            </section>
          )}

          <section className="overflow-x-auto rounded-lg border border-line bg-white">
            <h2 className="border-b border-line px-4 py-3 text-sm font-semibold text-navy">
              {messages.payrollModule.components}
            </h2>

            {components?.length === 0 && (
              <p className="p-6 text-muted">
                {messages.payrollModule.noComponents}
              </p>
            )}

            {components && components.length > 0 && (
              <table className="w-full text-left text-sm">
                <tbody>
                  {components.map((component) => (
                    <tr
                      key={component.id}
                      className="border-b border-line last:border-0"
                    >
                      <td className="px-4 py-3">
                        <span className="font-medium text-ink">
                          {component.name}
                        </span>
                        <span className="ml-2 text-xs text-muted">
                          {component.code}
                        </span>
                        {!component.active && (
                          <span className="ml-2 rounded-full bg-soft px-2 py-0.5 text-xs text-muted">
                            {messages.payrollModule.retired}
                          </span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-xs">
                        <span className={LINE_STYLES[component.componentType]}>
                          {
                            messages.payrollModule.componentType[
                              component.componentType
                            ]
                          }
                        </span>
                      </td>
                      <td className="px-4 py-3 text-xs text-muted">
                        {
                          messages.payrollModule.calculation[
                            component.calculation
                          ]
                        }
                        {component.percentage && ` · ${component.percentage}%`}
                        {component.defaultAmount &&
                          ` · ${component.defaultAmount}`}
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
