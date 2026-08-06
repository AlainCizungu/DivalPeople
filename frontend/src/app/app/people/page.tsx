"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useAuth } from "react-oidc-context";
import { useMessages } from "@/i18n/LocaleProvider";
import { employeesApi, type EmployeeStatus, type EmployeeSummary } from "@/api/client";

const STATUS_STYLES: Record<EmployeeStatus, string> = {
  ACTIVE: "bg-green/10 text-green",
  ON_LEAVE: "bg-blue/10 text-blue",
  SUSPENDED: "bg-warning/20 text-ink",
  TERMINATED: "bg-soft text-muted",
};

export default function PeoplePage() {
  const messages = useMessages();
  const auth = useAuth();
  const token = auth.user?.access_token;

  const [people, setPeople] = useState<EmployeeSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!token) return;
    try {
      setPeople(await employeesApi.list(token));
      setError(null);
    } catch {
      setError(messages.employees.loadFailed);
    }
  }, [token, messages.employees.loadFailed]);

  useEffect(() => {
    void load();
  }, [load]);

  // Managers are returned as ids; resolving names client-side avoids an endpoint that would
  // hand out the whole directory again per row.
  const namesById = useMemo(() => {
    const map = new Map<string, string>();
    people?.forEach((person) => map.set(person.id, person.displayName));
    return map;
  }, [people]);

  return (
    <div className="mx-auto max-w-5xl">
      <header className="mb-6 flex items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-navy">
            {messages.employees.title}
          </h1>
          <p className="mt-1 text-muted">{messages.employees.subtitle}</p>
        </div>
        {people && people.length > 0 && (
          <p className="shrink-0 text-sm text-muted">
            {people.length} {messages.employees.count}
          </p>
        )}
      </header>

      {error && (
        <div role="alert" className="rounded-lg border border-error/40 bg-error/10 p-5">
          <p className="text-sm text-ink">{error}</p>
        </div>
      )}

      {!error && people === null && <p className="text-muted">{messages.common.loading}</p>}

      {!error && people?.length === 0 && (
        <div className="rounded-lg border border-line bg-white p-10 text-center">
          <p className="text-muted">{messages.employees.empty}</p>
        </div>
      )}

      {!error && people && people.length > 0 && (
        <div className="overflow-x-auto rounded-lg border border-line bg-white">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-line bg-soft text-xs tracking-wide text-muted uppercase">
              <tr>
                <th scope="col" className="px-4 py-3 font-semibold">
                  {messages.employees.number}
                </th>
                <th scope="col" className="px-4 py-3 font-semibold">
                  {messages.employees.title}
                </th>
                <th scope="col" className="px-4 py-3 font-semibold">
                  {messages.employees.unit}
                </th>
                <th scope="col" className="px-4 py-3 font-semibold">
                  {messages.employees.manager}
                </th>
                <th scope="col" className="px-4 py-3 font-semibold">
                  {messages.common.tenant}
                </th>
              </tr>
            </thead>
            <tbody>
              {people.map((person) => (
                <tr key={person.id} className="border-b border-line last:border-b-0">
                  <td className="px-4 py-3 font-mono text-xs text-muted">
                    {person.employeeNumber}
                  </td>
                  <td className="px-4 py-3 font-semibold text-ink">{person.displayName}</td>
                  <td className="px-4 py-3 text-muted">
                    {person.orgUnitName ?? messages.employees.noUnit}
                  </td>
                  <td className="px-4 py-3 text-muted">
                    {person.managerId
                      ? (namesById.get(person.managerId) ?? messages.employees.noManager)
                      : messages.employees.noManager}
                  </td>
                  <td className="px-4 py-3">
                    <span
                      className={`rounded px-2 py-0.5 text-xs font-semibold ${STATUS_STYLES[person.status]}`}
                    >
                      {messages.employees.status[person.status]}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
