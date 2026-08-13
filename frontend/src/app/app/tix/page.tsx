"use client";

import { useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";
import {
  ApiError,
  tixApi,
  type IdentifierType,
  type InquiryOutcome,
  type InquiryResult,
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
import { RiskIndicatorPanel } from "@/components/RiskIndicatorPanel";

/**
 * Check a business before extending credit.
 *
 * <p>Business-shaped, which the first version was not: it led with a national ID, and the real
 * data is keyed on a business register number or an operator account reference. A credit officer
 * assessing a company has the RCCM in front of them, not the director's identity card.
 *
 * <p><strong>The panel under the verdict used to be a mock</strong> — 72 out of 100 over four
 * invented bars, inside a dashed amber border that said so. That was the honest thing to show
 * while there was no model. There is one now, so the sketch is gone and the figure below is
 * computed from the same records that produced the verdict above it.
 */

// ACCOUNT_REFERENCE last, and deliberately not first: an inquiry by your own account
// number resolves your own customer and then asks the exchange about them, which is
// useful — but it can never find a company you have not reported yourself.
const BUSINESS_IDENTIFIERS: IdentifierType[] =
  ["RCCM", "TAX_NUMBER", "NATIONAL_ID", "MSISDN", "ACCOUNT_REFERENCE"];

const OUTCOME_TONE: Record<InquiryOutcome, Tone> = {
  NO_MATCH: "neutral",
  CLEAR: "positive",
  OUTSTANDING_DEBT: "serious",
  REVIEW_REQUIRED: "review",
};

export default function CreditCheckPage() {
  const messages = useMessages();
  const t = messages.tix;

  const [identifierType, setIdentifierType] = useState<IdentifierType>("RCCM");
  const [identifier, setIdentifier] = useState("");
  const [legalName, setLegalName] = useState("");
  const [purpose, setPurpose] = useState("");

  const [result, setResult] = useState<InquiryResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setResult(null);
    try {
      setResult(
        await tixApi.inquire({
          // An empty list rather than an entry with an empty value. The server treats "no
          // identifier" and "an identifier that is blank" differently, and it should: the first
          // means resolve by name, the second is a caller sending nonsense.
          identifiers: identifier.trim()
            ? [{ type: identifierType, value: identifier.trim() }]
            : [],
          fullName: legalName.trim() || undefined,
          purpose: purpose.trim(),
        }),
      );
    } catch (caught) {
      // A 403 means the account lacks TIX_INQUIRER, which is a different problem from a bad
      // request and deserves its own sentence.
      setError(
        caught instanceof ApiError
          ? caught.status === 403
            ? t.forbidden
            : caught.message
          : t.networkError,
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-4xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />

      <Card title={t.checkTitle} description={t.checkDescription}>
        <form onSubmit={onSubmit} className="flex flex-col gap-5">
          <div className="grid gap-5 sm:grid-cols-2">
            <Field label={t.identifierType} htmlFor="identifierType" hint={t.identifierTypeHint}>
              <select
                id="identifierType"
                className={inputClass}
                value={identifierType}
                onChange={(e) => setIdentifierType(e.target.value as IdentifierType)}
              >
                {BUSINESS_IDENTIFIERS.map((type) => (
                  <option key={type} value={type}>
                    {t.identifierTypes[type]}
                  </option>
                ))}
              </select>
            </Field>

            <Field label={t.identifier} htmlFor="identifier">
              <input
                id="identifier"
                className={inputClass}
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
              />
            </Field>

            <Field label={t.legalName} htmlFor="legalName" hint={t.legalNameHint}>
              <input
                id="legalName"
                className={inputClass}
                value={legalName}
                onChange={(e) => setLegalName(e.target.value)}
              />
            </Field>

            <Field label={t.purpose} htmlFor="purpose" hint={t.purposeHint}>
              <input
                id="purpose"
                className={inputClass}
                value={purpose}
                onChange={(e) => setPurpose(e.target.value)}
                required
              />
            </Field>
          </div>

          {error && <ErrorNotice>{error}</ErrorNotice>}

          <div>
            {/* Either an identifier or a name of at least four characters. The server enforces
                the same rule; this only stops the request that would certainly be refused. */}
            <Button
              type="submit"
              disabled={
                submitting ||
                purpose.trim() === "" ||
                (identifier.trim() === "" && legalName.trim().length < 4)
              }
            >
              {submitting ? messages.common.loading : t.submit}
            </Button>
          </div>
        </form>
      </Card>

      {result && (
        <div className="mt-6 flex flex-col gap-6">
          <Card title={t.resultTitle}>
            <div className="flex flex-col gap-5">
              <div className="flex flex-wrap items-center gap-3">
                <Pill tone={OUTCOME_TONE[result.outcome]}>{t.outcomes[result.outcome]}</Pill>
                <span className="text-sm text-muted">{t.outcomeExplained[result.outcome]}</span>
              </div>

              <dl className="grid gap-4 sm:grid-cols-3">
                <div>
                  <dt className="text-xs text-muted">{t.reportedBy}</dt>
                  <dd className="mt-0.5 text-2xl font-bold tabular-nums text-navy">
                    {result.institutionCount}
                  </dd>
                  <dd className="text-xs text-muted">{t.reportedByNote}</dd>
                </div>
                <div>
                  <dt className="text-xs text-muted">{t.statusesHeld}</dt>
                  <dd className="mt-1 flex flex-wrap gap-1.5">
                    {result.statuses.length === 0 ? (
                      <span className="text-sm text-muted">—</span>
                    ) : (
                      result.statuses.map((status) => (
                        <Pill key={status}>{t.statuses[status]}</Pill>
                      ))
                    )}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs text-muted">{t.signals}</dt>
                  <dd className="mt-1 flex flex-wrap gap-1.5">
                    {result.fraudSignals.length === 0 ? (
                      <span className="text-sm text-muted">{t.noSignals}</span>
                    ) : (
                      result.fraudSignals.map((signal) => (
                        <Pill key={signal} tone="review">
                          {signal}
                        </Pill>
                      ))
                    )}
                  </dd>
                </div>
              </dl>

              <p className="rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
                {t.whatIsNotShown}
              </p>
            </div>
          </Card>

          {result.indicator ? (
            <RiskIndicatorPanel indicator={result.indicator} />
          ) : (
            // Withheld rather than zero. An indicator of nought over an unconfirmed match would
            // read as a clean company, which is the one thing the exchange did not say.
            <Card title={messages.risk.title}>
              <EmptyState>{messages.risk.noIndicator}</EmptyState>
            </Card>
          )}
        </div>
      )}
    </div>
  );
}
