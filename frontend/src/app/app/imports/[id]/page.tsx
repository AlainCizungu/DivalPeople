"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useMessages } from "@/i18n/LocaleProvider";
import {
  ApiError,
  ingestApi,
  tixApi,
  type BatchProfile,
  type BatchStatus,
  type DerivationReport,
  type IdentifierType,
  type ImportBatch,
  type RawRow,
  type SourceMappingView,
  type SubjectType,
} from "@/api/client";
import {
  Button,
  Card,
  EmptyState,
  ErrorNotice,
  Field,
  Metric,
  PageHeader,
  Pill,
  inputClass,
  type Tone,
} from "@/components/ui";

const STATUS_TONE: Record<BatchStatus, Tone> = {
  RECEIVED: "neutral",
  VALIDATED: "review",
  PUBLISHED: "positive",
  REJECTED: "serious",
  REVERTED: "serious",
};

/**
 * One delivery, and the rows exactly as stored.
 *
 * <p>The rows are rendered from the stored JSON rather than from anything re-parsed, so what an
 * operator sees here is literally the evidence the platform holds. The checksum is shown in full
 * and monospaced for the same reason: it is the operator's means of confirming that what we have
 * is the file they sent, and a truncated digest cannot be compared against anything.
 */
export default function BatchPage() {
  const messages = useMessages();
  const t = messages.imports;
  const params = useParams<{ id: string }>();
  const batchId = params.id;

  const [batch, setBatch] = useState<ImportBatch | null>(null);
  const [rows, setRows] = useState<RawRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [reason, setReason] = useState("");
  /**
   * Loaded on request rather than with the page.
   *
   * <p>Profiling reads every row of the batch, which for the real export is four thousand of them.
   * The rows table above needs fifty. Making the page wait for the larger of the two would slow
   * down the common case — an operator checking that a delivery arrived — for the sake of the
   * rarer one.
   */
  const [profile, setProfile] = useState<BatchProfile | null>(null);
  const [mapping, setMapping] = useState<SourceMappingView | null>(null);
  const [history, setHistory] = useState<SourceMappingView[]>([]);
  const [report, setReport] = useState<DerivationReport | null>(null);
  const [dunning, setDunning] = useState(false);

  // The mapping form. Seeded from the mapping in force so that redefining one is an edit of what
  // is there rather than a blank page — which is how somebody accidentally supersedes a correct
  // mapping with three empty columns.
  const [identifierColumn, setIdentifierColumn] = useState("");
  const [identifierType, setIdentifierType] = useState<IdentifierType>("RCCM");
  /**
   * Whether this delivery identifies anybody by number at all.
   *
   * <p>The Orange export does not: its first column is a row number and its second is the
   * customer name. Held as an explicit choice rather than inferred from an empty box, so the
   * operator is stating something about their file instead of leaving a field blank.
   */
  const [noIdentifier, setNoIdentifier] = useState(false);
  const [nameColumn, setNameColumn] = useState("");
  const [amountColumn, setAmountColumn] = useState("");
  const [currency, setCurrency] = useState("USD");
  const [serviceCategory, setServiceCategory] = useState("POSTPAID");
  const [subjectType, setSubjectType] = useState<SubjectType>("BUSINESS");

  const load = useCallback(async () => {
    try {
      const [batches, loadedRows] = await Promise.all([
        ingestApi.listBatches(),
        ingestApi.rows(batchId),
      ]);
      const found = batches.find((candidate) => candidate.id === batchId) ?? null;
      setBatch(found);
      setRows(loadedRows);

      if (found) {
        const [current, versions] = await Promise.all([
          ingestApi.currentMapping(found.sourceId),
          ingestApi.mappingHistory(found.sourceId),
        ]);
        setMapping(current);
        setHistory(versions);
        if (current) {
          setIdentifierColumn(current.identifierColumn ?? "");
          setIdentifierType(current.identifierType ?? "RCCM");
          setNoIdentifier(current.identifierColumn === null);
          setNameColumn(current.nameColumn);
          setAmountColumn(current.amountColumn);
          setCurrency(current.currency);
          setServiceCategory(current.serviceCategory);
          setSubjectType(current.subjectType);
        }
      }
    } catch (caught) {
      setRows([]);
      setError(caught instanceof ApiError ? caught.message : messages.common.unexpectedError);
    }
  }, [batchId, messages.common.unexpectedError]);

  useEffect(() => {
    void load();
  }, [load]);

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      await load();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : messages.common.unexpectedError);
    } finally {
      setBusy(false);
    }
  }

  // Columns come from the first row's key order, which the server preserved from the file. Union
  // of all keys would be wrong: a row with a different shape is refused at parse time, so any
  // disagreement here would be a bug worth seeing rather than papering over.
  const parsed = (rows ?? []).map((row) => JSON.parse(row.payload) as Record<string, string>);
  const columns = parsed.length > 0 ? Object.keys(parsed[0]!) : [];

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader
        title={batch?.filename ?? t.batch}
        subtitle={t.batchSubtitle}
        action={
          <Link href="/app/imports" className="text-sm font-bold text-blue hover:underline">
            ← {t.allBatches}
          </Link>
        }
      />

      {error && (
        <div className="mb-4">
          <ErrorNotice>{error}</ErrorNotice>
        </div>
      )}

      {batch && (
        <div className="mb-6">
          <Card>
            <dl className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
              <div>
                <dt className="text-xs text-muted">{t.status}</dt>
                <dd className="mt-1">
                  <Pill tone={STATUS_TONE[batch.status]}>{t.statuses[batch.status]}</Pill>
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted">{t.rows}</dt>
                <dd className="mt-1 font-bold tabular-nums text-navy">{batch.rowCount}</dd>
              </div>
              <div>
                <dt className="text-xs text-muted">{t.size}</dt>
                <dd className="mt-1 font-bold tabular-nums text-navy">{batch.byteSize}</dd>
              </div>
              <div>
                <dt className="text-xs text-muted">{t.asAt}</dt>
                <dd className="mt-1 font-bold tabular-nums text-navy">
                  {batch.reportedAsAt ?? "—"}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted">{t.received}</dt>
                <dd className="mt-1 tabular-nums text-ink">{batch.receivedAt.slice(0, 19)}</dd>
              </div>
            </dl>

            <div className="mt-5 border-t border-line pt-4">
              <p className="text-xs text-muted">{t.checksum}</p>
              <p className="mt-1 font-mono text-xs break-all text-ink">{batch.checksumSha256}</p>
              <p className="mt-1 text-xs text-muted">{t.checksumHint}</p>
            </div>

            <div className="mt-5 flex flex-wrap items-center gap-3 border-t border-line pt-4">
              {batch.status === "RECEIVED" && (
                <Button disabled={busy} onClick={() => run(() => ingestApi.validate(batch.id))}>
                  {t.validate}
                </Button>
              )}
              {batch.status === "VALIDATED" && (
                <Button disabled={busy} onClick={() => run(() => ingestApi.publish(batch.id))}>
                  {t.publish}
                </Button>
              )}

              {(batch.status === "RECEIVED" || batch.status === "VALIDATED") && (
                <>
                  <input
                    className={`${inputClass} max-w-xs`}
                    placeholder={t.reasonPlaceholder}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                  />
                  <Button
                    variant="secondary"
                    disabled={busy || reason.trim() === ""}
                    onClick={() => run(() => ingestApi.reject(batch.id, reason.trim()))}
                  >
                    {t.reject}
                  </Button>
                </>
              )}

              {batch.status === "PUBLISHED" && (
                <>
                  <input
                    className={`${inputClass} max-w-xs`}
                    placeholder={t.reasonPlaceholder}
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                  />
                  <Button
                    variant="secondary"
                    disabled={busy || reason.trim() === ""}
                    onClick={() => run(() => tixApi.revertImport(batch.id, reason.trim()))}
                  >
                    {t.revert}
                  </Button>
                </>
              )}
            </div>
          </Card>
        </div>
      )}

      <div className="mb-6">
        <Card title={t.profileTitle} description={t.profileDescription}>
          {profile === null ? (
            <Button
              disabled={busy}
              onClick={() => {
                setBusy(true);
                setError(null);
                void ingestApi
                  .profile(batchId)
                  .then(setProfile)
                  .catch((caught) =>
                    setError(
                      caught instanceof ApiError
                        ? caught.message
                        : messages.common.unexpectedError,
                    ),
                  )
                  .finally(() => setBusy(false));
              }}
            >
              {busy ? messages.common.loading : t.profileAction}
            </Button>
          ) : profile.columns.length === 0 ? (
            <EmptyState>{t.profileEmpty}</EmptyState>
          ) : (
            <>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[46rem] text-left text-sm">
                  <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                    <tr>
                      <th scope="col" className="pb-3 pr-4 font-semibold">{t.colColumn}</th>
                      <th scope="col" className="pb-3 pr-4 text-right font-semibold">
                        {t.colFilled}
                      </th>
                      <th scope="col" className="pb-3 pr-4 text-right font-semibold">
                        {t.colDistinct}
                      </th>
                      <th scope="col" className="pb-3 pr-4 text-right font-semibold">
                        {t.colTotal}
                      </th>
                      <th scope="col" className="pb-3 pr-4 font-semibold">{t.colRange}</th>
                      <th scope="col" className="pb-3 font-semibold">{t.colValues}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {profile.columns.map((column) => {
                      // Two shapes worth naming on sight. A column unique on every row is what an
                      // identifier looks like — it is how BPR_0 was found in the real export. A
                      // column with one distinct value carries no information at all.
                      const unique = column.filled > 0 && column.filled === column.distinct;
                      const constant = column.distinct === 1;
                      const sparse = profile.rows > 0 && column.filled / profile.rows < 0.05;
                      return (
                        <tr key={column.column} className="border-b border-line last:border-0">
                          <th scope="row" className="py-3 pr-4 font-semibold text-navy">
                            {column.column}
                            <span className="ml-2 inline-flex flex-wrap gap-1.5 align-middle">
                              {unique && <Pill tone="positive">{t.identifierLike}</Pill>}
                              {constant && <Pill>{t.constant}</Pill>}
                              {sparse && column.filled > 0 && (
                                <Pill tone="review">{t.mostlyEmpty}</Pill>
                              )}
                            </span>
                          </th>
                          <td className="py-3 pr-4 text-right tabular-nums text-ink">
                            {column.filled}
                            <span className="ml-1 text-xs text-muted">
                              /{profile.rows}
                            </span>
                          </td>
                          <td className="py-3 pr-4 text-right tabular-nums text-ink">
                            {column.distinct}
                          </td>
                          <td className="py-3 pr-4 text-right font-bold tabular-nums text-navy">
                            {column.total ?? <span className="font-normal text-muted">—</span>}
                          </td>
                          <td className="py-3 pr-4 text-xs text-muted tabular-nums">
                            {column.numeric
                              ? `${column.minimum} – ${column.maximum}`
                              : column.filled === 0
                                ? "—"
                                : t.textLength
                                    .replace("{shortest}", String(column.shortestLength))
                                    .replace("{longest}", String(column.longestLength))}
                          </td>
                          <td className="py-3">
                            <span className="flex flex-wrap gap-1.5">
                              {column.vocabulary.map((entry) => (
                                <Pill key={entry.value}>
                                  {entry.value} · {entry.count}
                                </Pill>
                              ))}
                            </span>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
              <p className="mt-5 border-t border-line pt-4 text-xs text-muted">
                {t.profileNote}
              </p>

              <div className="mt-6 border-t border-line pt-5">
                <h3 className="font-bold text-navy">{t.issuesTitle}</h3>
                <p className="mt-0.5 mb-4 text-sm text-muted">{t.issuesDescription}</p>

                {!profile.issues.emptyRows &&
                !profile.issues.duplicateRows &&
                !profile.issues.rowsMissingIdentifier ? (
                  <EmptyState>{t.issuesNone}</EmptyState>
                ) : (
                  <>
                    <div className="grid gap-4 sm:grid-cols-3">
                      <Metric
                        label={t.issuesEmptyRows}
                        value={String(profile.issues.emptyRows)}
                        tone={profile.issues.emptyRows > 0 ? "warning" : "plain"}
                      />
                      <Metric
                        label={t.issuesDuplicates}
                        value={String(profile.issues.duplicateRows)}
                        tone={profile.issues.duplicateRows > 0 ? "warning" : "plain"}
                      />
                      <Metric
                        label={t.issuesMissingIdentifier}
                        value={String(profile.issues.rowsMissingIdentifier)}
                        note={t.issuesMissingIdentifierNote}
                        tone={profile.issues.rowsMissingIdentifier > 0 ? "warning" : "plain"}
                      />
                    </div>

                    {profile.issues.keyColumns.length > 0 && (
                      <p className="mt-4 text-sm text-muted">
                        {t.issuesKeyColumns}:{" "}
                        {profile.issues.keyColumns.map((column) => (
                          <span key={column} className="mr-1.5 align-middle">
                            <Pill>{column}</Pill>
                          </span>
                        ))}
                      </p>
                    )}

                    <div className="mt-4 overflow-x-auto">
                      <table className="w-full min-w-[26rem] text-left text-sm">
                        <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                          <tr>
                            <th scope="col" className="pb-3 pr-4 font-semibold">{t.colRow}</th>
                            <th scope="col" className="pb-3 pr-4 font-semibold">{t.colIssue}</th>
                            <th scope="col" className="pb-3 font-semibold">{t.colColumn}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {profile.issues.findings.map((finding, index) => (
                            <tr
                              key={`${finding.rowNumber}-${finding.issue}-${index}`}
                              className="border-b border-line last:border-0"
                            >
                              <th scope="row" className="py-2.5 pr-4 tabular-nums text-navy">
                                {finding.rowNumber}
                              </th>
                              <td className="py-2.5 pr-4 text-ink">
                                {t.issueKinds[finding.issue]}
                                {/* Naming the row it duplicates is the whole value of the
                                    finding: "row 3 is a duplicate" sends somebody hunting. */}
                                {finding.detail && (
                                  <span className="ml-1 tabular-nums text-muted">
                                    {finding.detail}
                                  </span>
                                )}
                              </td>
                              <td className="py-2.5 text-muted">{finding.column ?? "—"}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>

                    {!profile.issues.complete && (
                      <p className="mt-3 text-xs text-muted">{t.issuesTruncated}</p>
                    )}
                  </>
                )}
              </div>
            </>
          )}
        </Card>
      </div>

      {/* Beside the profile, not on another screen. The evidence for the choice is the profile:
          which columns are unique, which are constant, which are entirely numeric. Asking
          somebody to pick the amount column on a page that does not show them is asking them to
          remember. */}
      <div className="mb-6">
        <Card title={t.mappingTitle} description={t.mappingDescription}>
          {mapping ? (
            <p className="mb-4 flex flex-wrap items-center gap-2 text-sm text-muted">
              <Pill tone="positive">{t.mappingCurrent}</Pill>
              <span>
                {t.mappingVersion} {mapping.versionNumber}
              </span>
              <span className="tabular-nums">{mapping.definedAt.slice(0, 10)}</span>
            </p>
          ) : (
            <div className="mb-4">
              <EmptyState>{t.mappingNone}</EmptyState>
            </div>
          )}

          {/* The profile's findings, restated as a hint. This is the whole reason the mapping
              lives on this page. */}
          {profile && (
            <div className="mb-5 flex flex-col gap-2 rounded border border-line bg-soft px-4 py-3 text-sm">
              <p className="text-muted">
                <span className="font-semibold text-ink">{t.uniqueColumns}:</span>{" "}
                {profile.columns
                  .filter((c) => c.filled > 0 && c.filled === c.distinct)
                  .map((c) => c.column)
                  .join(", ") || "—"}
              </p>
              <p className="text-muted">
                <span className="font-semibold text-ink">{t.numericColumns}:</span>{" "}
                {profile.columns
                  .filter((c) => c.numeric)
                  .map((c) => c.column)
                  .join(", ") || "—"}
              </p>
            </div>
          )}

          <form
            className="grid gap-4 sm:grid-cols-2"
            onSubmit={(event) => {
              event.preventDefault();
              if (!batch) return;
              void run(() =>
                ingestApi.defineMapping(batch.sourceId, {
                  // Both or neither. The server says the same thing and the database says it
                  // again; sending half of a pair from here would only turn a clear choice into
                  // a validation error.
                  identifierColumn: noIdentifier ? null : identifierColumn.trim(),
                  identifierType: noIdentifier ? null : identifierType,
                  nameColumn: nameColumn.trim(),
                  amountColumn: amountColumn.trim(),
                  currency: currency.trim(),
                  serviceCategory: serviceCategory.trim(),
                  subjectType,
                }),
              );
            }}
          >
            <label className="flex items-start gap-2 sm:col-span-2">
              <input
                type="checkbox"
                className="mt-1"
                checked={noIdentifier}
                onChange={(e) => setNoIdentifier(e.target.checked)}
              />
              <span>
                <span className="font-semibold text-ink">{t.mappingNoIdentifier}</span>
                {noIdentifier && (
                  <span className="mt-1 block text-sm text-muted">
                    {t.mappingNoIdentifierNote}
                  </span>
                )}
              </span>
            </label>

            {!noIdentifier && (
              <Field
                label={t.mappingIdentifier}
                htmlFor="identifierColumn"
                hint={t.mappingIdentifierHint}
              >
                <input
                  id="identifierColumn"
                  list="batch-columns"
                  className={inputClass}
                  value={identifierColumn}
                  onChange={(e) => setIdentifierColumn(e.target.value)}
                  required
                />
              </Field>
            )}

            {!noIdentifier && (
            <Field label={t.mappingIdentifierType} htmlFor="identifierType">
              <select
                id="identifierType"
                className={inputClass}
                value={identifierType}
                onChange={(e) => setIdentifierType(e.target.value as IdentifierType)}
              >
                {(Object.keys(messages.tix.identifierTypes) as IdentifierType[]).map((type) => (
                  <option key={type} value={type}>
                    {messages.tix.identifierTypes[type]}
                  </option>
                ))}
              </select>
              {identifierType === "ACCOUNT_REFERENCE" && (
                // Said here rather than in a document, because here is where the choice is made
                // and the consequence is not obvious from the label. Mapping a file this way is
                // the right answer when the export carries nothing else, and it does mean the
                // resulting records cannot be matched by any other operator.
                <p className="mt-2 text-sm text-muted">{messages.tix.accountReferenceNote}</p>
              )}
            </Field>
            )}

            <Field label={t.mappingName} htmlFor="nameColumn">
              <input
                id="nameColumn"
                list="batch-columns"
                className={inputClass}
                value={nameColumn}
                onChange={(e) => setNameColumn(e.target.value)}
                required
              />
            </Field>

            <Field label={t.mappingAmount} htmlFor="amountColumn" hint={t.mappingAmountHint}>
              <input
                id="amountColumn"
                list="batch-columns"
                className={inputClass}
                value={amountColumn}
                onChange={(e) => setAmountColumn(e.target.value)}
                required
              />
            </Field>

            <Field label={t.mappingCurrency} htmlFor="currency" hint={t.mappingCurrencyHint}>
              <input
                id="currency"
                className={inputClass}
                maxLength={3}
                value={currency}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())}
                required
              />
            </Field>

            <Field label={t.mappingService} htmlFor="serviceCategory">
              <input
                id="serviceCategory"
                className={inputClass}
                value={serviceCategory}
                onChange={(e) => setServiceCategory(e.target.value)}
                required
              />
            </Field>

            <Field label={t.mappingSubjectType} htmlFor="subjectType">
              <select
                id="subjectType"
                className={inputClass}
                value={subjectType}
                onChange={(e) => setSubjectType(e.target.value as SubjectType)}
              >
                <option value="BUSINESS">{messages.search.types.BUSINESS}</option>
                <option value="INDIVIDUAL">{messages.search.types.INDIVIDUAL}</option>
              </select>
            </Field>

            {/* The header of this delivery, offered as suggestions rather than as a closed list:
                a mapping may legitimately name a column a later file will have. */}
            <datalist id="batch-columns">
              {columns.map((column) => (
                <option key={column} value={column} />
              ))}
            </datalist>

            <div className="sm:col-span-2">
              <Button type="submit" disabled={busy}>
                {mapping ? t.mappingReplace : t.mappingSave}
              </Button>
              {mapping && <p className="mt-2 text-xs text-muted">{t.mappingReplaceNote}</p>}
            </div>
          </form>

          {history.length > 1 && (
            <div className="mt-5 border-t border-line pt-4">
              <p className="mb-2 text-xs text-muted">{t.mappingHistory}</p>
              <ul className="flex flex-col gap-1.5 text-xs text-muted">
                {history
                  .filter((version) => !version.current)
                  .map((version) => (
                    <li key={version.id} className="tabular-nums">
                      v{version.versionNumber} — {version.identifierColumn} /{" "}
                      {version.nameColumn} / {version.amountColumn} · {t.mappingSupersededOn}{" "}
                      {version.supersededAt?.slice(0, 10)}
                    </li>
                  ))}
              </ul>
            </div>
          )}
        </Card>
      </div>

      {batch?.status === "PUBLISHED" && mapping && (
        <div className="mb-6">
          <Card title={t.deriveTitle} description={t.deriveDescription}>
            {report === null ? (
              <div className="flex flex-col gap-4">
                <label className="flex items-start gap-3 text-sm text-ink">
                  <input
                    type="checkbox"
                    className="mt-1"
                    checked={dunning}
                    onChange={(e) => setDunning(e.target.checked)}
                  />
                  <span>
                    {t.deriveDunning}
                    <span className="mt-0.5 block text-xs text-muted">{t.deriveDunningHint}</span>
                  </span>
                </label>
                <div>
                  <Button
                    disabled={busy || !dunning || batch.reportedAsAt === null}
                    onClick={() => {
                      setBusy(true);
                      setError(null);
                      void tixApi
                        .deriveImport(batch.id, dunning)
                        .then(setReport)
                        .catch((caught) =>
                          setError(
                            caught instanceof ApiError
                              ? caught.message
                              : messages.common.unexpectedError,
                          ),
                        )
                        .finally(() => setBusy(false));
                    }}
                  >
                    {busy ? messages.common.loading : t.deriveAction}
                  </Button>
                </div>
              </div>
            ) : (
              <>
                <div className="grid gap-4 sm:grid-cols-2">
                  <Metric label={t.deriveCreated} value={String(report.created)} />
                  <Metric
                    label={t.deriveRefused}
                    value={String(report.refused)}
                    tone={report.refused > 0 ? "warning" : "plain"}
                  />
                </div>

                <p className="mt-4 text-sm text-muted">
                  {t.deriveTook}{" "}
                  <span className="tabular-nums text-ink">
                    {report.elapsedMs < 1000
                      ? `${report.elapsedMs} ms`
                      : `${(report.elapsedMs / 1000).toFixed(1)} s`}
                  </span>{" "}
                  · {t.deriveAsAt} <span className="tabular-nums text-ink">{report.asAt}</span> ·{" "}
                  {t.deriveMappingVersion}{" "}
                  <span className="tabular-nums text-ink">{report.mappingVersion}</span>
                </p>

                {report.refusals.length > 0 && (
                  <div className="mt-5 border-t border-line pt-4">
                    <p className="mb-3 font-bold text-navy">{t.deriveRefusalsTitle}</p>
                    <div className="overflow-x-auto">
                      <table className="w-full min-w-[26rem] text-left text-sm">
                        <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                          <tr>
                            <th scope="col" className="pb-3 pr-4 font-semibold">{t.colRow}</th>
                            <th scope="col" className="pb-3 font-semibold">{t.colReason}</th>
                          </tr>
                        </thead>
                        <tbody>
                          {report.refusals.map((refusal) => (
                            <tr
                              key={refusal.rowNumber}
                              className="border-b border-line last:border-0"
                            >
                              <th scope="row" className="py-2.5 pr-4 tabular-nums text-navy">
                                {refusal.rowNumber}
                              </th>
                              <td className="py-2.5 text-ink">{refusal.reason}</td>
                            </tr>
                          ))}
                        </tbody>
                      </table>
                    </div>
                    {!report.complete && (
                      <p className="mt-3 text-xs text-muted">{t.deriveTruncated}</p>
                    )}
                  </div>
                )}
              </>
            )}
          </Card>
        </div>
      )}

      <Card title={t.rowsTitle} description={t.rowsDescription}>
        {rows === null ? (
          <EmptyState>{messages.common.loading}</EmptyState>
        ) : parsed.length === 0 ? (
          <EmptyState>{t.noRows}</EmptyState>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                <tr>
                  <th scope="col" className="pb-3 pr-4 font-semibold">#</th>
                  {columns.map((column) => (
                    <th key={column} scope="col" className="pb-3 pr-4 font-semibold">
                      {column}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {parsed.map((row, index) => (
                  <tr key={rows[index]!.id} className="border-b border-line last:border-0">
                    <th scope="row" className="py-2.5 pr-4 tabular-nums text-muted">
                      {rows[index]!.rowNumber}
                    </th>
                    {columns.map((column) => (
                      <td key={column} className="py-2.5 pr-4 text-ink">
                        {/* Empty cells render as a dash so an empty string and a missing column
                            are visibly different — they mean different things downstream. */}
                        {row[column] === "" ? (
                          <span className="text-muted">—</span>
                        ) : (
                          row[column]
                        )}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
