"use client";

import { useMessages } from "@/i18n/LocaleProvider";

function MetricCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-line bg-white p-5">
      <p className="text-sm text-muted">{label}</p>
      <p className="mt-1 text-3xl font-bold text-navy tabular-nums">{value}</p>
    </div>
  );
}

export default function DashboardPage() {
  const messages = useMessages();

  return (
    <div className="mx-auto max-w-6xl">
      <header className="mb-6">
        <h1 className="text-3xl font-bold tracking-tight text-navy">{messages.dashboard.title}</h1>
        <p className="mt-1 text-muted">{messages.dashboard.subtitle}</p>
      </header>

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        <MetricCard label={messages.dashboard.totalEmployees} value="2,486" />
        <MetricCard label={messages.dashboard.payrollAccuracy} value="99.4%" />
        <MetricCard label={messages.dashboard.openAlerts} value="18" />
      </div>
    </div>
  );
}
