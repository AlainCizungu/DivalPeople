"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import { organizationApi, type OrgUnit, type OrgUnitType } from "@/api/client";

const TYPE_STYLES: Record<OrgUnitType, string> = {
  LEGAL_ENTITY: "bg-navy text-white",
  BRANCH: "bg-blue text-white",
  DEPARTMENT: "bg-teal text-white",
  COST_CENTER: "bg-purple text-white",
  LOCATION: "bg-orange text-white",
};

/**
 * Read-only view of the tenant's organisation tree.
 *
 * <p>The API returns a flat list ordered by depth, which is enough to render the hierarchy by
 * indenting on `depth`. Editing is a separate screen; showing the structure is what unblocks the
 * rest of the product.
 */
export default function OrganizationPage() {
  const messages = useMessages();
  const { status } = useSession();

  const [units, setUnits] = useState<OrgUnit[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  const ready = status === "authenticated";

  const load = useCallback(async () => {
    if (!ready) return;
    try {
      setUnits(await organizationApi.listUnits());
      setError(null);
    } catch {
      setError(messages.org.loadFailed);
    }
  }, [ready, messages.org.loadFailed]);

  useEffect(() => {
    void load();
  }, [load]);

  return (
    <div className="mx-auto max-w-4xl">
      <header className="mb-6 flex items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-navy">{messages.org.title}</h1>
          <p className="mt-1 text-muted">{messages.org.subtitle}</p>
        </div>
        {units && units.length > 0 && (
          <p className="shrink-0 text-sm text-muted">
            {units.length} {messages.org.unitCount}
          </p>
        )}
      </header>

      {error && (
        <div role="alert" className="rounded-lg border border-error/40 bg-error/10 p-5">
          <p className="text-sm text-ink">{error}</p>
        </div>
      )}

      {!error && units === null && <p className="text-muted">{messages.common.loading}</p>}

      {!error && units?.length === 0 && (
        <div className="rounded-lg border border-line bg-white p-10 text-center">
          <p className="font-semibold text-navy">{messages.org.empty}</p>
          <p className="mt-1 text-sm text-muted">{messages.org.emptyHint}</p>
        </div>
      )}

      {!error && units && units.length > 0 && (
        <ul className="overflow-hidden rounded-lg border border-line bg-white">
          {units.map((unit) => (
            <li
              key={unit.id}
              className="flex items-center gap-3 border-b border-line px-4 py-3 last:border-b-0"
              // Indent by depth: the flat list is already ordered so parents precede children.
              style={{ paddingLeft: `${unit.depth * 1.75 + 1}rem` }}
            >
              <span
                className={`shrink-0 rounded px-2 py-0.5 text-[11px] font-bold ${TYPE_STYLES[unit.unitType]}`}
              >
                {messages.org.type[unit.unitType]}
              </span>

              <span className={unit.active ? "font-semibold text-ink" : "text-muted line-through"}>
                {unit.name}
              </span>

              <span className="font-mono text-xs text-muted">{unit.code}</span>

              {!unit.active && (
                <span className="ml-auto shrink-0 rounded bg-soft px-2 py-0.5 text-xs text-muted">
                  {messages.org.inactive}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
