"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useMessages } from "@/i18n/LocaleProvider";
import {
  ApiError,
  ingestApi,
  type BatchStatus,
  type ImportBatch,
  type RawRow,
} from "@/api/client";
import {
  Button,
  Card,
  EmptyState,
  ErrorNotice,
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

  const load = useCallback(async () => {
    try {
      const [batches, loadedRows] = await Promise.all([
        ingestApi.listBatches(),
        ingestApi.rows(batchId),
      ]);
      setBatch(batches.find((candidate) => candidate.id === batchId) ?? null);
      setRows(loadedRows);
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
                    onClick={() => run(() => ingestApi.revert(batch.id, reason.trim()))}
                  >
                    {t.revert}
                  </Button>
                </>
              )}
            </div>
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
