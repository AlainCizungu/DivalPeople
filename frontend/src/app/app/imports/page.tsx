"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import {
  ApiError,
  ingestApi,
  type BatchStatus,
  type ImportBatch,
  type IngestSource,
} from "@/api/client";
import {
  Button,
  Card,
  EmptyState,
  ErrorNotice,
  Field,
  PageHeader,
  Pill,
  inputClass,
  type Tone,
} from "@/components/ui";
import { Band, CountUp } from "@/components/visual/motion";

/**
 * How each state is painted.
 *
 * <p><strong>REVERTED is not REJECTED, and it stopped being red.</strong> Rejected means the
 * platform refused a delivery; reverted means somebody deliberately took one back, which is the
 * feature working and a decision being exercised. Painting both in the colour of failure told an
 * operator that its own correct action was an error, and made a screen full of reverts look like a
 * screen full of problems.
 */
const STATUS_TONE: Record<BatchStatus, Tone> = {
  RECEIVED: "neutral",
  VALIDATED: "review",
  PUBLISHED: "positive",
  REJECTED: "serious",
  REVERTED: "neutral",
};

/** The edge on a row, matching the pill beside it. */
const STATUS_EDGE: Record<BatchStatus, string> = {
  RECEIVED: "border-l-line",
  VALIDATED: "border-l-warning",
  PUBLISHED: "border-l-success",
  REJECTED: "border-l-error",
  REVERTED: "border-l-muted",
};

/**
 * Data imports.
 *
 * <p>Uploads the file itself rather than a parsed version of it, which is the whole reason this
 * screen is thin: the browser's job is to hand over bytes, and every decision about what those
 * bytes mean belongs on the server where the checksum is taken. A page that parsed the CSV and
 * posted JSON would make the stored rows and the checksummed file two unrelated claims.
 *
 * <p>CSV and XLSX. The format is decided from the bytes on the server, not from the extension
 * here, so `accept` below is a convenience in the file picker and never a guarantee — an operator
 * who renames an export has not changed what is inside it.
 */
export default function ImportsPage() {
  const messages = useMessages();
  const t = messages.imports;

  const [sources, setSources] = useState<IngestSource[] | null>(null);
  const [batches, setBatches] = useState<ImportBatch[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [sourceId, setSourceId] = useState("");
  /**
   * What the delivery reflects, from the operator rather than from the file.
   *
   * <p>The profiled export contains no dates. Deriving one from the aging buckets would give
   * 4,262 of its 4,290 rows the same date and a retention expiry clustered on a single day — a
   * guessed date is a guessed retention period. One input box moves the assumption to the only
   * party who can answer it.
   */
  const [reportedAsAt, setReportedAsAt] = useState("");
  const [newCode, setNewCode] = useState("");
  const [newName, setNewName] = useState("");
  const fileInput = useRef<HTMLInputElement>(null);

  const load = useCallback(async () => {
    try {
      const [loadedSources, loadedBatches] = await Promise.all([
        ingestApi.listSources(),
        ingestApi.listBatches(),
      ]);
      setSources(loadedSources);
      setBatches(loadedBatches);
      setSourceId((current) => current || (loadedSources[0]?.id ?? ""));
    } catch (caught) {
      setSources([]);
      setBatches([]);
      setError(caught instanceof ApiError ? caught.message : messages.common.unexpectedError);
    }
  }, [messages.common.unexpectedError]);

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
      // The API's sentence, not a generic one. "Two columns share a name", "Row 14 has 5 cells
      // but the header has 4", "this exact file is already published" — each names something the
      // operator can go and fix in their spreadsheet.
      setError(caught instanceof ApiError ? caught.message : messages.common.unexpectedError);
    } finally {
      setBusy(false);
    }
  }

  async function onUpload(event: React.FormEvent) {
    event.preventDefault();
    const file = fileInput.current?.files?.[0];
    if (!file || !sourceId || !reportedAsAt) {
      return;
    }
    await run(async () => {
      await ingestApi.upload(sourceId, file, reportedAsAt);
      if (fileInput.current) {
        fileInput.current.value = "";
      }
    });
  }

  const hasSources = (sources?.length ?? 0) > 0;

  return (
    <div className="mx-auto max-w-6xl">
      <Band>
        <div className="px-6 py-8 md:px-10 md:py-9">
          <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
            {t.eyebrow}
          </p>
          <h1 className="mb-2 text-3xl font-bold tracking-tight md:text-4xl">{t.title}</h1>
          <p className="mb-6 max-w-2xl text-sm text-white/70">{t.subtitle}</p>

          {/* The lifecycle, drawn, with the count at each stage. A delivery moves received →
              validated → published, and the stage it is stuck at is the whole question an operator
              opens this screen with. Counted over every batch, not a page: listBatches returns the
              lot. */}
          {batches !== null && (
            <div className="flex flex-wrap items-center gap-3">
              {(["RECEIVED", "VALIDATED", "PUBLISHED"] as const).map((stage, index) => (
                <span key={stage} className="flex items-center gap-3">
                  {index > 0 && (
                    <span aria-hidden="true" className="text-white/30">
                      →
                    </span>
                  )}
                  <span className="rounded-lg bg-white/10 px-4 py-2.5">
                    <span className="block text-2xl font-bold">
                      <CountUp
                        value={batches.filter((batch) => batch.status === stage).length}
                      />
                    </span>
                    <span className="block text-xs text-white/60">{t.statuses[stage]}</span>
                  </span>
                </span>
              ))}

              {/* Off to the side, because they are not stages of the same journey. A rejection is
                  the platform refusing; a revert is somebody deciding. Neither is on the way to
                  being published. */}
              {(["REJECTED", "REVERTED"] as const)
                .filter((stage) => batches.some((batch) => batch.status === stage))
                .map((stage) => (
                  <span
                    key={stage}
                    className="ml-2 rounded-lg border border-white/20 px-4 py-2.5"
                  >
                    <span className="block text-2xl font-bold">
                      <CountUp
                        value={batches.filter((batch) => batch.status === stage).length}
                      />
                    </span>
                    <span className="block text-xs text-white/60">{t.statuses[stage]}</span>
                  </span>
                ))}
            </div>
          )}
        </div>
      </Band>

      <div className="mt-6" />

      {error && (
        <div className="mb-4">
          <ErrorNotice>{error}</ErrorNotice>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title={t.sourcesTitle} description={t.sourcesDescription}>
          {sources === null ? (
            <EmptyState>{messages.common.loading}</EmptyState>
          ) : (
            <div className="flex flex-col gap-4">
              {hasSources ? (
                <ul className="flex flex-col divide-y divide-line">
                  {sources.map((source) => (
                    <li key={source.id} className="flex items-center justify-between py-2.5">
                      <span className="font-semibold text-navy">{source.code}</span>
                      <span className="text-sm text-muted">{source.name}</span>
                    </li>
                  ))}
                </ul>
              ) : (
                <EmptyState>{t.noSources}</EmptyState>
              )}

              <form
                className="flex flex-col gap-3 border-t border-line pt-4"
                onSubmit={(event) => {
                  event.preventDefault();
                  void run(async () => {
                    await ingestApi.registerSource({
                      code: newCode.trim(),
                      name: newName.trim(),
                      kind: "SPREADSHEET",
                    });
                    setNewCode("");
                    setNewName("");
                  });
                }}
              >
                <Field label={t.newSourceCode} htmlFor="code" hint={t.newSourceCodeHint}>
                  <input
                    id="code"
                    className={inputClass}
                    value={newCode}
                    onChange={(e) => setNewCode(e.target.value)}
                    required
                  />
                </Field>
                <Field label={t.newSourceName} htmlFor="name">
                  <input
                    id="name"
                    className={inputClass}
                    value={newName}
                    onChange={(e) => setNewName(e.target.value)}
                    required
                  />
                </Field>
                <Button type="submit" variant="secondary" disabled={busy}>
                  {t.addSource}
                </Button>
              </form>
            </div>
          )}
        </Card>

        <Card title={t.uploadTitle} description={t.uploadDescription}>
          <form onSubmit={onUpload} className="flex flex-col gap-4">
            <Field label={t.source} htmlFor="sourceId">
              <select
                id="sourceId"
                className={inputClass}
                value={sourceId}
                onChange={(e) => setSourceId(e.target.value)}
                disabled={!hasSources}
              >
                {sources?.map((source) => (
                  <option key={source.id} value={source.id}>
                    {source.code} — {source.name}
                  </option>
                ))}
              </select>
            </Field>

            <Field label={t.asAt} htmlFor="reportedAsAt" hint={t.asAtHint}>
              <input
                id="reportedAsAt"
                type="date"
                className={inputClass}
                value={reportedAsAt}
                max={new Date().toISOString().slice(0, 10)}
                onChange={(e) => setReportedAsAt(e.target.value)}
                required
              />
            </Field>

            <Field label={t.file} htmlFor="file" hint={t.fileHint}>
              <input
                id="file"
                ref={fileInput}
                type="file"
                accept=".csv,text/csv,.xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                className={inputClass}
                required
              />
            </Field>

            <Button type="submit" disabled={busy || !hasSources || reportedAsAt === ""}>
              {busy ? messages.common.loading : t.upload}
            </Button>
            {!hasSources && <p className="text-sm text-muted">{t.needSourceFirst}</p>}
          </form>
        </Card>
      </div>

      <div className="mt-6">
        <Card title={t.batchesTitle} description={t.batchesDescription}>
          {batches === null ? (
            <EmptyState>{messages.common.loading}</EmptyState>
          ) : batches.length === 0 ? (
            <EmptyState>{t.noBatches}</EmptyState>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[48rem] text-left text-sm">
                <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                  <tr>
                    <th scope="col" className="pb-3 font-semibold">{t.filename}</th>
                    <th scope="col" className="pb-3 font-semibold">{t.source}</th>
                    <th scope="col" className="pb-3 font-semibold">{t.rows}</th>
                    <th scope="col" className="pb-3 font-semibold">{t.status}</th>
                    <th scope="col" className="pb-3 font-semibold">{t.received}</th>
                    <th scope="col" className="pb-3" />
                  </tr>
                </thead>
                <tbody>
                  {batches.map((batch) => (
                    <tr
                      key={batch.id}
                      className={`border-b border-l-4 border-line last:border-b-0 transition hover:bg-soft/60 ${
                        STATUS_EDGE[batch.status]
                      }`}
                    >
                      <th scope="row" className="py-3.5 font-semibold text-navy">
                        {batch.filename}
                      </th>
                      <td className="py-3.5 text-muted">{batch.sourceCode}</td>
                      <td className="py-3.5 tabular-nums text-ink">{batch.rowCount}</td>
                      <td className="py-3.5">
                        <Pill tone={STATUS_TONE[batch.status]}>
                          {t.statuses[batch.status]}
                        </Pill>
                      </td>
                      <td className="py-3.5 tabular-nums text-muted">
                        {batch.receivedAt.slice(0, 10)}
                      </td>
                      <td className="py-3.5 text-right">
                        <Link
                          href={`/app/imports/${batch.id}`}
                          className="text-sm font-bold text-blue hover:underline"
                        >
                          {t.inspect} →
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>
      </div>
    </div>
  );
}
