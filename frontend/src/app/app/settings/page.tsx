"use client";

import { useEffect, useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";
import { settingsApi, type Setting, type Settings } from "@/api/client";
import { Card, EmptyState, ErrorNotice, PageHeader, Pill, type Tone } from "@/components/ui";

/**
 * The rules this deployment runs by.
 *
 * <p>Everything here already existed and none of it was visible. It lives in a yaml file read by
 * whoever deploys the application and by nobody else, so an operator could not find out how long
 * their records are kept and a compliance officer could not check a retention period against the
 * law without asking an engineer.
 *
 * <p><strong>Provenance is a column, not a footnote.</strong> Three of these numbers are the terms
 * of reference's illustrative figures and have never been checked against the Code du numérique. A
 * page that printed "3 years" beside "5 years" with no qualification would read as a decision
 * somebody took, and would go on reading that way in a screenshot in somebody's board pack.
 *
 * <p>Read-only, which is the design rather than a first step postponed. Shortening a retention
 * period puts records past due the moment it is saved.
 */
export default function SettingsPage() {
  const messages = useMessages();
  const t = messages.settings;

  const [settings, setSettings] = useState<Settings | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const loaded = await settingsApi.load();
        if (!cancelled) setSettings(loaded);
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const sections: { id: keyof Settings; rows: Setting[] }[] = settings
    ? [
        { id: "retention", rows: settings.retention },
        { id: "reporting", rows: settings.reporting },
        { id: "exchange", rows: settings.exchange },
        { id: "models", rows: settings.models },
      ]
    : [];

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />

      <p className="mb-6 rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
        {t.readOnly}
      </p>

      {failed && <ErrorNotice>{t.loadFailed}</ErrorNotice>}
      {!failed && settings === null && <EmptyState>{messages.common.loading}</EmptyState>}

      <div className="flex flex-col gap-6">
        {sections.map((section) => (
          <Card
            key={section.id}
            title={t.groups[section.id]}
            description={t.groupNotes[section.id]}
          >
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-line text-left text-xs text-muted">
                  <th className="pb-1.5 font-semibold">{t.valueHeader}</th>
                  <th className="pb-1.5 text-right font-semibold">{t.sourceHeader}</th>
                </tr>
              </thead>
              <tbody>
                {section.rows.map((row) => (
                  <SettingRow
                    // The reporting floor repeats its key once per currency, so the unit is part
                    // of what makes a row unique.
                    key={`${row.key}-${row.unit ?? ""}`}
                    row={row}
                    t={t}
                  />
                ))}
              </tbody>
            </table>
          </Card>
        ))}
      </div>
    </div>
  );
}

function SettingRow({
  row,
  t,
}: {
  row: Setting;
  t: ReturnType<typeof useMessages>["settings"];
}) {
  // Only the two states worth interrupting somebody over. An operational default and a value set
  // by release are ordinary; a legal period nobody has verified, and a setting whose absence
  // causes a refusal, are not.
  const PROVENANCE_TONE: Record<Setting["provenance"], Tone> = {
    TERMS_OF_REFERENCE: "neutral",
    UNVERIFIED_PLACEHOLDER: "review",
    OPERATIONAL_DEFAULT: "neutral",
    COMPILED: "neutral",
    NOT_SET: "serious",
  };

  const note =
    row.provenance === "UNVERIFIED_PLACEHOLDER" || row.provenance === "NOT_SET"
      ? t.provenanceNote[row.provenance]
      : null;
  const unit = row.unit ? lookup(t.units, row.unit, row.unit) : "";

  return (
    <tr className="border-b border-line/60 last:border-0 align-top">
      <td className="py-2.5">
        <span className="font-semibold text-navy tabular-nums">
          {row.value ?? t.notSet}
          {row.value && unit ? ` ${unit}` : ""}
        </span>
        <span className="block text-xs text-muted">{lookup(t.keys, row.key, row.key)}</span>
        {note && <span className="mt-1 block text-xs text-muted">{note}</span>}
      </td>
      <td className="py-2.5 text-right">
        <Pill tone={PROVENANCE_TONE[row.provenance]}>{t.provenance[row.provenance]}</Pill>
      </td>
    </tr>
  );
}

/**
 * A label from the catalogue, falling back to the code the server sent.
 *
 * <p>The keys are server-side codes, so the catalogue cannot be typed against them without
 * duplicating the backend's enums in TypeScript and having the two drift. The fallback is the
 * point rather than a concession: a setting added to the API before the translations catch up
 * renders as its raw code, which is ugly and visible. Rendering it as an empty cell would hide a
 * missing translation, and hiding it is how a legally significant number ends up displayed with
 * no label at all.
 */
function lookup(table: Record<string, string>, key: string, fallback: string): string {
  return table[key] ?? fallback;
}
