"use client";

import { useState } from "react";
import { useAuth } from "react-oidc-context";
import { useMessages } from "@/i18n/LocaleProvider";
import {
  ApiError,
  tixApi,
  type IdentifierType,
  type InquiryOutcome,
  type InquiryResult,
} from "@/api/client";

const IDENTIFIER_TYPES: IdentifierType[] = [
  "NATIONAL_ID",
  "MSISDN",
  "PASSPORT",
  "DRIVER_LICENSE",
  "VOTER_CARD",
  "RCCM",
  "TAX_NUMBER",
];

const OUTCOME_STYLES: Record<InquiryOutcome, string> = {
  NO_MATCH: "border-line bg-soft",
  CLEAR: "border-green/40 bg-green/10",
  OUTSTANDING_DEBT: "border-error/40 bg-error/10",
  REVIEW_REQUIRED: "border-warning/50 bg-warning/10",
};

export default function TixPage() {
  const messages = useMessages();
  const auth = useAuth();

  const [identifierType, setIdentifierType] = useState<IdentifierType>("NATIONAL_ID");
  const [identifier, setIdentifier] = useState("");
  const [fullName, setFullName] = useState("");
  const [purpose, setPurpose] = useState("");

  const [result, setResult] = useState<InquiryResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setResult(null);

    const token = auth.user?.access_token;
    if (!token) {
      setError(messages.auth.sessionExpired);
      setSubmitting(false);
      return;
    }

    try {
      const response = await tixApi.inquire(
        {
          identifiers: [{ type: identifierType, value: identifier }],
          fullName: fullName || undefined,
          purpose,
        },
        token,
      );
      setResult(response);
    } catch (caught) {
      if (caught instanceof ApiError) {
        // A 403 here means the signed-in user lacks TIX_INQUIRER, which is a different
        // problem from a malformed request and deserves a different message.
        setError(
          caught.status === 403 ? messages.tix.forbidden : caught.message,
        );
      } else {
        setError(messages.tix.networkError);
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-3xl">
      <header className="mb-6">
        <h1 className="text-3xl font-bold tracking-tight text-navy">{messages.tix.title}</h1>
        <p className="mt-1 text-muted">{messages.tix.subtitle}</p>
      </header>

      <form onSubmit={onSubmit} className="space-y-4 rounded-lg border border-line bg-white p-6">
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor="identifierType" className="mb-1 block text-sm font-semibold text-ink">
              {messages.tix.identifierType}
            </label>
            <select
              id="identifierType"
              value={identifierType}
              onChange={(event) => setIdentifierType(event.target.value as IdentifierType)}
              className="w-full rounded border border-line px-3 py-2 text-sm"
            >
              {IDENTIFIER_TYPES.map((type) => (
                <option key={type} value={type}>
                  {type}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label htmlFor="identifier" className="mb-1 block text-sm font-semibold text-ink">
              {messages.tix.identifier} <span className="text-error">*</span>
            </label>
            <input
              id="identifier"
              required
              value={identifier}
              onChange={(event) => setIdentifier(event.target.value)}
              className="w-full rounded border border-line px-3 py-2 text-sm"
            />
          </div>
        </div>

        <div>
          <label htmlFor="fullName" className="mb-1 block text-sm font-semibold text-ink">
            {messages.tix.fullName}
          </label>
          <input
            id="fullName"
            value={fullName}
            onChange={(event) => setFullName(event.target.value)}
            className="w-full rounded border border-line px-3 py-2 text-sm"
          />
        </div>

        <div>
          <label htmlFor="purpose" className="mb-1 block text-sm font-semibold text-ink">
            {messages.tix.purpose} <span className="text-error">*</span>
          </label>
          <input
            id="purpose"
            required
            value={purpose}
            onChange={(event) => setPurpose(event.target.value)}
            placeholder="ONBOARDING_CHECK"
            className="w-full rounded border border-line px-3 py-2 text-sm"
          />
          <p className="mt-1 text-xs text-muted">{messages.tix.purposeHint}</p>
        </div>

        <button
          type="submit"
          disabled={submitting}
          className="rounded bg-blue px-5 py-2.5 text-sm font-bold text-white transition hover:bg-blue-dark disabled:opacity-50"
        >
          {submitting ? messages.common.loading : messages.tix.submit}
        </button>
      </form>

      {error && (
        <div role="alert" className="mt-5 rounded-lg border border-error/40 bg-error/10 p-5">
          <p className="font-bold text-error">{messages.tix.inquiryFailed}</p>
          <p className="mt-1 text-sm text-ink">{error}</p>
        </div>
      )}

      {result && (
        <div role="status" className={`mt-5 rounded-lg border p-5 ${OUTCOME_STYLES[result.outcome]}`}>
          <div className="flex items-baseline justify-between gap-4">
            <p className="text-lg font-bold text-navy">{messages.tix.outcome[result.outcome]}</p>
            <p className="text-sm text-muted">
              {messages.tix.confidence}:{" "}
              <span className="font-semibold tabular-nums text-ink">
                {Math.round(result.confidence * 100)}%
              </span>
            </p>
          </div>

          {result.statuses.length > 0 && (
            <ul className="mt-3 flex flex-wrap gap-2">
              {result.statuses.map((status) => (
                <li
                  key={status}
                  className="rounded border border-line bg-white px-2 py-1 text-xs font-semibold text-ink"
                >
                  {status}
                </li>
              ))}
            </ul>
          )}

          {result.fraudSignals.length > 0 && (
            <div className="mt-3">
              <p className="text-sm font-semibold text-ink">{messages.tix.fraudSignals}</p>
              <ul className="mt-1 flex flex-wrap gap-2">
                {result.fraudSignals.map((signal) => (
                  <li
                    key={signal}
                    className="rounded border border-orange/40 bg-white px-2 py-1 font-mono text-xs text-orange"
                  >
                    {signal}
                  </li>
                ))}
              </ul>
            </div>
          )}

          <p className="mt-4 border-t border-line pt-3 text-xs text-muted">
            {messages.tix.advisoryNotice}
          </p>
        </div>
      )}
    </div>
  );
}
