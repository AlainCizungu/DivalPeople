"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import {
  ApiError,
  subjectRightsApi,
  type Disclosure,
  type IdentifierType,
  type SubjectRequest,
  type SubjectRequestStatus,
  type SubjectRequestType,
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

/**
 * The queue for the people in the registry.
 *
 * <p>The rights behind this were built in V21 and had no screen, which meant the most
 * differentiated thing in the product could only be exercised with curl. Worse, since V23 every
 * case carries a statutory deadline — sixty days for access, thirty for the rest — and a deadline
 * nobody can see is a liability rather than a control.
 *
 * <p><strong>Two roles, on purpose.</strong> Opening a case needs the declarant role and deciding
 * one needs the compliance officer role, because whoever takes the request at the counter should
 * not also rule on it. The actions below are hidden when the account cannot perform them, which is
 * a courtesy: the server refuses regardless, and hiding a button has never stopped anybody posting
 * to the endpoint.
 *
 * <p>No identifier and no name appear in the queue, because the server does not send them. Whoever
 * is progressing a case already knows who walked in; a queue that echoed identity documents back
 * would be a second copy of the registry with weaker controls around it.
 */

const REQUEST_TYPES: SubjectRequestType[] = [
  "ACCESS",
  "DISPUTE",
  "RECTIFICATION",
  "ERASURE",
];

const IDENTIFIER_TYPES: IdentifierType[] = [
  "NATIONAL_ID",
  "PASSPORT",
  "MSISDN",
  "RCCM",
  "TAX_NUMBER",
  "ACCOUNT_REFERENCE",
  "VOTER_CARD",
  "DRIVER_LICENSE",
];

const STATUS_TONE: Record<SubjectRequestStatus, Tone> = {
  RECEIVED: "review",
  IDENTITY_VERIFIED: "neutral",
  UPHELD: "positive",
  REFUSED: "serious",
  WITHDRAWN: "neutral",
};

export default function SubjectRequestsPage() {
  const messages = useMessages();
  const t = messages.subjectRights;
  const { profile } = useSession();

  const canDecide = profile?.roles.includes("COMPLIANCE_OFFICER") ?? false;
  // Recording a withdrawal is not deciding a case, so it belongs to whoever the person spoke to
  // rather than to the officer who would rule on it.
  const canWithdraw = profile?.roles.includes("TIX_DECLARANT") ?? false;

  const [cases, setCases] = useState<SubjectRequest[] | null>(null);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [file, setFile] = useState<Disclosure[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [refused, setRefused] = useState(false);
  const [busy, setBusy] = useState(false);

  const [requestType, setRequestType] = useState<SubjectRequestType>("DISPUTE");
  const [identifierType, setIdentifierType] = useState<IdentifierType>("NATIONAL_ID");
  const [identifier, setIdentifier] = useState("");
  const [detail, setDetail] = useState("");
  const [evidence, setEvidence] = useState("");
  const [reason, setReason] = useState("");
  const [withdrawNote, setWithdrawNote] = useState("");

  const load = useCallback(async () => {
    try {
      setCases(await subjectRightsApi.list());
      setRefused(false);
    } catch (caught) {
      setCases([]);
      setRefused(caught instanceof ApiError && caught.status === 403);
      if (!(caught instanceof ApiError && caught.status === 403)) {
        setError(caught instanceof ApiError ? caught.message : messages.common.unexpectedError);
      }
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
      // A 404 from raising means nobody holds that identifier, which is the answer the person
      // came for rather than a failure. Saying "not found" to a member of staff who just told
      // somebody they might be listed is worse than saying nothing.
      setError(
        caught instanceof ApiError
          ? caught.status === 404
            ? t.notInRegistry
            : caught.message
          : messages.common.unexpectedError,
      );
    } finally {
      setBusy(false);
    }
  }

  const selected = cases?.find((entry) => entry.id === selectedId) ?? null;
  const openCases = cases?.filter((entry) => !entry.decidedAt).length ?? 0;
  const overdue = cases?.filter((entry) => entry.overdue).length ?? 0;
  const decided = cases?.filter((entry) => entry.decidedAt).length ?? 0;
  const value = (n: number) => (cases === null ? "—" : String(n));

  const day = (iso: string | null) => (iso ? iso.slice(0, 10) : "—");

  if (refused) {
    return (
      <div className="mx-auto max-w-6xl">
        <PageHeader title={t.title} subtitle={t.subtitle} />
        <EmptyState>{t.noAccess}</EmptyState>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-6xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />

      {error && (
        <div className="mb-4">
          <ErrorNotice>{error}</ErrorNotice>
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-3">
        <Metric label={t.open} value={value(openCases)} />
        <Metric
          label={t.overdue}
          value={value(overdue)}
          note={t.overdueNote}
          tone={overdue > 0 ? "warning" : "plain"}
        />
        <Metric label={t.decided} value={value(decided)} />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-[1.5fr_1fr]">
        <Card title={t.queueTitle} description={t.queueDescription}>
          {cases === null ? (
            <EmptyState>{messages.common.loading}</EmptyState>
          ) : cases.length === 0 ? (
            <EmptyState>{t.empty}</EmptyState>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[36rem] text-left text-sm">
                <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                  <tr>
                    <th scope="col" className="pb-3 font-semibold">{t.colType}</th>
                    <th scope="col" className="pb-3 font-semibold">{t.colStatus}</th>
                    <th scope="col" className="pb-3 font-semibold">{t.colRaised}</th>
                    <th scope="col" className="pb-3 font-semibold">{t.colDue}</th>
                  </tr>
                </thead>
                <tbody>
                  {[...cases]
                    .sort((a, b) => a.dueAt.localeCompare(b.dueAt))
                    .map((entry) => (
                      <tr
                        key={entry.id}
                        onClick={() => {
                          setSelectedId(entry.id);
                          setFile(null);
                          setEvidence("");
                          setReason("");
                        }}
                        className={`cursor-pointer border-b border-line last:border-0 ${
                          entry.id === selectedId ? "bg-soft" : "hover:bg-soft"
                        }`}
                      >
                        <th scope="row" className="py-3.5 font-semibold text-navy">
                          {t.types[entry.requestType]}
                        </th>
                        <td className="py-3.5">
                          <Pill tone={STATUS_TONE[entry.status]}>
                            {t.statuses[entry.status]}
                          </Pill>
                        </td>
                        <td className="py-3.5 text-muted tabular-nums">{day(entry.raisedAt)}</td>
                        <td className="py-3.5 tabular-nums">
                          <span className={entry.overdue ? "font-bold text-[#b45309]" : "text-muted"}>
                            {day(entry.dueAt)}
                          </span>
                          {entry.overdue && (
                            <span className="ml-2">
                              <Pill tone="review">{t.overdue}</Pill>
                            </span>
                          )}
                        </td>
                      </tr>
                    ))}
                </tbody>
              </table>
            </div>
          )}
        </Card>

        <Card title={t.raiseTitle} description={t.raiseDescription}>
          <form
            className="flex flex-col gap-4"
            onSubmit={(event) => {
              event.preventDefault();
              void run(async () => {
                await subjectRightsApi.raise({
                  requestType,
                  identifierType,
                  identifier: identifier.trim(),
                  detail: detail.trim() || undefined,
                });
                setIdentifier("");
                setDetail("");
              });
            }}
          >
            <Field
              label={t.raiseType}
              htmlFor="requestType"
              hint={t.typeHints[requestType]}
            >
              <select
                id="requestType"
                className={inputClass}
                value={requestType}
                onChange={(e) => setRequestType(e.target.value as SubjectRequestType)}
              >
                {REQUEST_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {t.types[type]}
                  </option>
                ))}
              </select>
            </Field>

            <Field label={t.raiseIdentifierType} htmlFor="identifierType">
              <select
                id="identifierType"
                className={inputClass}
                value={identifierType}
                onChange={(e) => setIdentifierType(e.target.value as IdentifierType)}
              >
                {IDENTIFIER_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {messages.tix.identifierTypes[type]}
                  </option>
                ))}
              </select>
            </Field>

            <Field label={t.raiseIdentifier} htmlFor="identifier">
              <input
                id="identifier"
                className={inputClass}
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
                required
              />
            </Field>

            <Field label={t.raiseDetail} htmlFor="detail" hint={t.raiseDetailHint}>
              <textarea
                id="detail"
                rows={3}
                className={inputClass}
                value={detail}
                onChange={(e) => setDetail(e.target.value)}
              />
            </Field>

            <Button type="submit" disabled={busy || identifier.trim() === ""}>
              {t.raiseAction}
            </Button>
          </form>
        </Card>
      </div>

      {/* The queue stays on screen while a case is open, so there is no "back" — but a row of
          clickable rows with nothing below them reads as broken rather than as waiting. */}
      {!selected && cases !== null && cases.length > 0 && (
        <p className="mt-6 rounded border border-dashed border-line bg-soft px-4 py-6 text-center text-sm text-muted">
          {t.select}
        </p>
      )}

      {selected && (
        <div className="mt-6 flex flex-col gap-6">
          <Card title={t.actionsTitle}>
            <dl className="grid gap-4 sm:grid-cols-3">
              <div>
                <dt className="text-xs text-muted">{t.colType}</dt>
                <dd className="mt-1 font-bold text-navy">{t.types[selected.requestType]}</dd>
              </div>
              <div>
                <dt className="text-xs text-muted">{t.colStatus}</dt>
                <dd className="mt-1">
                  <Pill tone={STATUS_TONE[selected.status]}>
                    {t.statuses[selected.status]}
                  </Pill>
                </dd>
              </div>
              <div>
                <dt className="text-xs text-muted">{t.colDue}</dt>
                <dd
                  className={`mt-1 font-bold tabular-nums ${
                    selected.overdue ? "text-[#b45309]" : "text-navy"
                  }`}
                >
                  {day(selected.dueAt)}
                </dd>
              </div>
            </dl>
            {selected.detail && (
              <div className="mt-5 border-t border-line pt-4">
                <p className="text-xs text-muted">{t.colDetail}</p>
                <p className="mt-1 text-sm text-ink italic">“{selected.detail}”</p>
              </div>
            )}
          </Card>

          {/* Nothing can be decided or disclosed before this. Without it, "I am that person" is
              enough to erase somebody else's debts. */}
          {selected.status === "RECEIVED" && canDecide && (
            <Card title={t.verifyTitle} description={t.verifyDescription}>
              <form
                className="flex flex-col gap-4"
                onSubmit={(event) => {
                  event.preventDefault();
                  void run(() =>
                    subjectRightsApi.verifyIdentity(selected.id, evidence.trim()),
                  );
                }}
              >
                <Field
                  label={t.verifyEvidence}
                  htmlFor="evidence"
                  hint={t.verifyEvidenceHint}
                >
                  <textarea
                    id="evidence"
                    rows={3}
                    className={inputClass}
                    value={evidence}
                    onChange={(e) => setEvidence(e.target.value)}
                    required
                  />
                </Field>
                <div>
                  <Button type="submit" disabled={busy || evidence.trim() === ""}>
                    {t.verifyAction}
                  </Button>
                </div>
              </form>
            </Card>
          )}

          {selected.status === "IDENTITY_VERIFIED" &&
            selected.requestType === "ACCESS" &&
            canDecide && (
              <Card title={t.discloseTitle} description={t.discloseDescription}>
                {file === null ? (
                  <Button
                    disabled={busy}
                    onClick={() =>
                      void run(async () => {
                        setFile(await subjectRightsApi.disclose(selected.id));
                      })
                    }
                  >
                    {t.discloseAction}
                  </Button>
                ) : file.length === 0 ? (
                  <EmptyState>{t.discloseEmpty}</EmptyState>
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full min-w-[34rem] text-left text-sm">
                      <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                        <tr>
                          <th scope="col" className="pb-3 font-semibold">{t.colOperator}</th>
                          <th scope="col" className="pb-3 font-semibold">{t.colStatus}</th>
                          <th scope="col" className="pb-3 text-right font-semibold">
                            {t.colAmount}
                          </th>
                          <th scope="col" className="pb-3 font-semibold">{t.colDefaultDate}</th>
                          <th scope="col" className="pb-3 font-semibold">{t.colRetainedUntil}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {file.map((entry, index) => (
                          <tr
                            key={`${entry.operator}-${index}`}
                            className="border-b border-line last:border-0"
                          >
                            {/* Named, unlike anywhere else. The subject is entitled to know who
                                is reporting them; an enquiring operator never is. */}
                            <th scope="row" className="py-3.5 font-semibold text-navy">
                              {entry.operator}
                            </th>
                            <td className="py-3.5">
                              <Pill>{messages.tix.statuses[entry.status]}</Pill>
                            </td>
                            <td className="py-3.5 text-right font-bold tabular-nums text-navy">
                              {entry.amount}
                            </td>
                            <td className="py-3.5 text-muted tabular-nums">{entry.defaultDate}</td>
                            <td className="py-3.5 text-muted tabular-nums">
                              {entry.retainedUntil}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </Card>
            )}

          {selected.status === "IDENTITY_VERIFIED" &&
            selected.requestType === "ERASURE" &&
            canDecide && (
              <Card title={t.erasureTitle} description={t.erasureDescription}>
                <Button
                  disabled={busy}
                  onClick={() => void run(() => subjectRightsApi.decideErasure(selected.id))}
                >
                  {t.erasureAction}
                </Button>
              </Card>
            )}

          {selected.status === "IDENTITY_VERIFIED" &&
            (selected.requestType === "DISPUTE" ||
              selected.requestType === "RECTIFICATION") &&
            canDecide && (
              <Card title={t.decideTitle} description={t.decideDescription}>
                <div className="flex flex-col gap-4">
                  <Field label={t.decideReason} htmlFor="reason" hint={t.decideReasonHint}>
                    <textarea
                      id="reason"
                      rows={3}
                      className={inputClass}
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                      required
                    />
                  </Field>
                  <div className="flex flex-wrap gap-3">
                    <Button
                      disabled={busy || reason.trim() === ""}
                      onClick={() =>
                        void run(() =>
                          subjectRightsApi.close(selected.id, true, reason.trim()),
                        )
                      }
                    >
                      {t.uphold}
                    </Button>
                    <Button
                      variant="secondary"
                      disabled={busy || reason.trim() === ""}
                      onClick={() =>
                        void run(() =>
                          subjectRightsApi.close(selected.id, false, reason.trim()),
                        )
                      }
                    >
                      {t.refuse}
                    </Button>
                  </div>
                </div>
              </Card>
            )}

          {canWithdraw &&
            selected.status !== "UPHELD" &&
            selected.status !== "REFUSED" &&
            selected.status !== "WITHDRAWN" && (
              <Card title={t.withdrawTitle} description={t.withdrawDescription}>
                <div className="flex flex-col gap-4">
                  <Field
                    label={t.withdrawNote}
                    htmlFor="withdrawNote"
                    hint={t.withdrawNoteHint}
                  >
                    <textarea
                      id="withdrawNote"
                      rows={2}
                      className={inputClass}
                      value={withdrawNote}
                      onChange={(e) => setWithdrawNote(e.target.value)}
                      required
                    />
                  </Field>
                  <div>
                    <Button
                      variant="secondary"
                      disabled={busy || withdrawNote.trim() === ""}
                      onClick={() =>
                        void run(() =>
                          subjectRightsApi.withdraw(selected.id, withdrawNote.trim()),
                        )
                      }
                    >
                      {t.withdraw}
                    </Button>
                  </div>
                </div>
              </Card>
            )}

          {!canDecide && selected.status !== "UPHELD" && selected.status !== "REFUSED" && (
            <Card title={t.actionsTitle}>
              <EmptyState>{t.cannotDecide}</EmptyState>
            </Card>
          )}

          {selected.decisionReason && (
            <Card title={t.decisionTitle}>
              <p className="text-sm text-ink">{selected.decisionReason}</p>
              <p className="mt-2 text-xs text-muted tabular-nums">{day(selected.decidedAt)}</p>
            </Card>
          )}
        </div>
      )}
    </div>
  );
}
