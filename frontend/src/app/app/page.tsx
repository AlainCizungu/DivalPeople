"use client";

import { useMessages } from "@/i18n/LocaleProvider";

function MetricCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-line bg-white p-5">
      <p className="text-sm text-muted">{label}</p>
      <p className="mt-1 text-3xl font-bold tabular-nums text-navy">{value}</p>
    </div>
  );
}

/**
 * Exchange overview.
 *
 * <p>The figures are em dashes, not numbers, and that is the honest state: nothing behind this
 * screen is wired up yet. It previously showed "2,486 employees" and "99.4% payroll accuracy",
 * invented figures for a product this application no longer is — and invented figures inside the
 * authenticated application are worse than on a marketing page, because everything else a signed-in
 * user sees here is real.
 *
 * <p>Replace the placeholders when there is an endpoint to read, not before.
 */
export default function DashboardPage() {
  const messages = useMessages();

  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6">
        <h1 className="text-3xl font-bold tracking-tight text-navy">{messages.dashboard.title}</h1>
        <p className="mt-1 text-muted">{messages.dashboard.subtitle}</p>
      </header>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <MetricCard label={messages.dashboard.declaredRecords} value="—" />
        <MetricCard label={messages.dashboard.openRecords} value="—" />
        <MetricCard label={messages.dashboard.inquiries} value="—" />
      </div>

      <p className="mt-6 text-sm text-muted">{messages.dashboard.empty}</p>
    </div>
  );
}
