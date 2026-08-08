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

const STATUS_TONE: Record<BatchStatus, Tone> = {
  RECEIVED: "neutral",
  VALIDATED: "review",
  PUBLISHED: "positive",
  REJECTED: "serious",
  REVERTED: "serious",
};

/**
 * Data imports.
 *
 * <p>Uploads the file itself rather than a parsed version of it, which is the whole reason this
 * screen is thin: the browser's job is to hand over bytes, and every decision about what those
 * bytes mean belongs on the server where the checksum is taken. A page that parsed the CSV and
 * posted JSON would make the stored rows and the checksummed file two unrelated claims.
 *
 * <p>CSV only for now. XLSX needs a parser and decisions about typed cells that should be made
 * while looking at a real export.
 */
export default function ImportsPage() {
  const messages = useMessages();
  const t = messages.imports;

  const [sources, setSources] = useState<IngestSource[] | null>(null);
  const [batches, setBatches] = useState<ImportBatch[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [sourceId, setSourceId] = useState("");
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
    if (!file || !sourceId) {
      return;
    }
    await run(async () => {
      await ingestApi.upload(sourceId, file);
      if (fileInput.current) {
        fileInput.current.value = "";
      }
    });
  }

  const hasSources = (sources?.length ?? 0) > 0;

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />

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

            <Field label={t.file} htmlFor="file" hint={t.fileHint}>
              <input
                id="file"
                ref={fileInput}
                type="file"
                accept=".csv,text/csv"
                className={inputClass}
                required
              />
            </Field>

            <Button type="submit" disabled={busy || !hasSources}>
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
                    <tr key={batch.id} className="border-b border-line last:border-0">
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
