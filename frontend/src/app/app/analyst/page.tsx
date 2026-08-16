"use client";

import { useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  ApiError,
  analystApi,
  tixApi,
  type EvidencePack,
  type SearchResult,
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
} from "@/components/ui";
import { AskDip } from "@/components/AskDip";

/**
 * The Dival AI analyst, which is at present not an AI.
 *
 * <p>That is said on the screen rather than implied by omission, and it is the first line of the
 * pack itself. No language model is configured in this deployment; what is built is the thing a
 * model would have to rest on, because the hard part of an AI analyst was never the model.
 *
 * <p><strong>An analyst with a database connection is a disclosure hole.</strong> Every rule that
 * makes this exchange safe lives above SQL — a count of institutions and never their names, one
 * operator kept out of another's records, a contested record withheld the moment it is contested.
 * A model handed a connection has none of them and would answer "which companies does the other
 * operator report" correctly and catastrophically. So the pack is assembled only from services the
 * caller could have called themselves, and this screen shows nothing they could not have reached by
 * clicking two others.
 *
 * <p>Assembling a pack asks the exchange, so it is charged and audited exactly like an inquiry.
 * The button says so before it is pressed rather than the allowance quietly draining.
 */
export default function AnalystPage() {
  const messages = useMessages();
  const t = messages.analyst;

  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResult[] | null>(null);
  const [purpose, setPurpose] = useState("");
  const [pack, setPack] = useState<EvidencePack | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);

  const describe = (caught: unknown) =>
    caught instanceof ApiError
      ? `${caught.status} ${caught.code} — ${caught.message}`
      : String(caught);

  async function onSearch() {
    setFailure(null);
    try {
      setResults(await tixApi.search(query.trim()));
    } catch (caught) {
      setFailure(describe(caught));
    }
  }

  async function onAssemble(subjectId: string) {
    setBusy(true);
    setFailure(null);
    try {
      setPack(await analystApi.pack(subjectId, purpose.trim()));
    } catch (caught) {
      setFailure(describe(caught));
    } finally {
      setBusy(false);
    }
  }

  const ready = purpose.trim().length > 0 && !busy;

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />

      {/* Before anything else, and not in small print at the bottom. Somebody arriving from a
          menu entry with "AI" in it should learn what this is in the first thing they read. */}
      <p className="mb-5 rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
        {t.whatThisIs}
      </p>

      <AskDip />

      <Card title={t.findTitle} description={t.findNote}>
        <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
          <Field label={t.queryLabel} htmlFor="analyst-query">
            <input
              id="analyst-query"
              className={inputClass}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder={t.queryPlaceholder}
            />
          </Field>
          <Button type="button" onClick={() => void onSearch()}>
            {t.search}
          </Button>
        </div>

        <Field label={t.purposeLabel} htmlFor="analyst-purpose" hint={t.purposeHint}>
          <input
            id="analyst-purpose"
            className={inputClass}
            value={purpose}
            onChange={(event) => setPurpose(event.target.value)}
          />
        </Field>

        {results !== null && results.length === 0 && <EmptyState>{t.noResults}</EmptyState>}

        {results !== null && results.length > 0 && (
          <ul className="mt-3 flex flex-col gap-2">
            {results.map((result) => (
              <li
                key={result.subjectId}
                className="flex flex-wrap items-center gap-3 rounded border border-line px-3 py-2"
              >
                <span className="font-semibold text-navy">{result.name}</span>
                <Button
                  type="button"
                  onClick={() => void onAssemble(result.subjectId)}
                  disabled={!ready}
                >
                  {busy ? t.assembling : t.assemble}
                </Button>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {failure && (
        <ErrorNotice>
          {t.failed}
          <span className="mt-1 block font-mono text-xs">{failure}</span>
        </ErrorNotice>
      )}

      {pack && <Pack pack={pack} t={t} messages={messages} />}
    </div>
  );
}

function Pack({
  pack,
  t,
  messages,
}: {
  pack: EvidencePack;
  t: ReturnType<typeof useMessages>["analyst"];
  messages: ReturnType<typeof useMessages>;
}) {
  return (
    <>
      <Card
        title={interpolate(t.packTitle, t.packTitle, { name: pack.held.name })}
        description={interpolate(t.packNote, t.packNote, {
          version: pack.packVersion,
          purpose: pack.purpose,
        })}
      >
        <h3 className="mb-2 text-sm font-bold text-navy">{t.ownTitle}</h3>
        <p className="mb-3 text-xs text-muted">{t.ownNote}</p>
        <ul className="flex flex-col gap-2">
          {pack.held.records.map((record) => (
            <li key={record.recordId} className="rounded border border-line px-3 py-2 text-sm">
              <span className="font-semibold tabular-nums text-navy">
                {record.amount} {record.currency}
              </span>
              <span className="ml-2 text-muted">{record.defaultDate}</span>
              {/* Provenance, which is what makes this evidence rather than a summary. A figure
                  derived from a delivery and a figure somebody typed are different claims. */}
              <span className="ml-2 text-xs text-muted">
                {record.imported ? t.fromDelivery : t.declaredHere}
              </span>
            </li>
          ))}
        </ul>

        <h3 className="mt-5 mb-2 text-sm font-bold text-navy">{t.exchangeTitle}</h3>
        <div className="flex flex-wrap items-center gap-3 text-sm">
          <Pill tone={pack.exchange.outcome === "OUTSTANDING_DEBT" ? "serious" : "neutral"}>
            {messages.tix.outcomes[pack.exchange.outcome]}
          </Pill>
          <span className="text-muted">
            {interpolate(t.institutions, t.institutions, {
              count: String(pack.exchange.institutionCount),
            })}
          </span>
        </div>
      </Card>

      <Card title={t.absentTitle} description={t.absentNote}>
        <ul className="flex flex-col gap-2 text-sm">
          {pack.absent.map((code) => (
            <li key={code} className="rounded border border-line bg-soft px-3 py-2 text-muted">
              {t.absences[code]}
            </li>
          ))}
        </ul>
      </Card>
    </>
  );
}
