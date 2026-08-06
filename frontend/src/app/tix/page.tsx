"use client";

import { useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";

type Outcome = "NO_MATCH" | "CLEAR" | "OUTSTANDING_DEBT" | "REVIEW_REQUIRED";

const IDENTIFIER_TYPES = [
  "MSISDN",
  "NATIONAL_ID",
  "PASSPORT",
  "DRIVER_LICENSE",
  "VOTER_CARD",
  "RCCM",
  "TAX_NUMBER",
] as const;

const OUTCOME_STYLES: Record<Outcome, string> = {
  NO_MATCH: "border-line bg-soft text-ink",
  CLEAR: "border-green/30 bg-green/10 text-green",
  OUTSTANDING_DEBT: "border-error/30 bg-error/10 text-error",
  REVIEW_REQUIRED: "border-warning/40 bg-warning/10 text-ink",
};

/**
 * TIX verification screen.
 *
 * <p>Placeholder state only — this renders the workflow and the advisory framing. Wiring it to
 * POST /api/v1/tix/inquiries comes with authentication.
 */
export default function TixPage() {
  const messages = useMessages();
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  return (
    <div className="mx-auto max-w-3xl">
      <header className="mb-6">
        <h1 className="text-3xl font-bold tracking-tight text-navy">{messages.tix.title}</h1>
        <p className="mt-1 text-muted">{messages.tix.subtitle}</p>
      </header>

      <form
        className="space-y-4 rounded-lg border border-line bg-white p-6"
        onSubmit={(event) => {
          event.preventDefault();
          setOutcome("REVIEW_REQUIRED");
        }}
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label htmlFor="identifierType" className="mb-1 block text-sm font-semibold text-ink">
              {messages.tix.identifierType}
            </label>
            <select
              id="identifierType"
              className="w-full rounded border border-line px-3 py-2 text-sm"
              defaultValue="NATIONAL_ID"
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
              className="w-full rounded border border-line px-3 py-2 text-sm"
            />
          </div>
        </div>

        <div>
          <label htmlFor="fullName" className="mb-1 block text-sm font-semibold text-ink">
            {messages.tix.fullName}
          </label>
          <input id="fullName" className="w-full rounded border border-line px-3 py-2 text-sm" />
        </div>

        <div>
          <label htmlFor="purpose" className="mb-1 block text-sm font-semibold text-ink">
            {messages.tix.purpose} <span className="text-error">*</span>
          </label>
          <input
            id="purpose"
            required
            className="w-full rounded border border-line px-3 py-2 text-sm"
          />
          <p className="mt-1 text-xs text-muted">{messages.tix.advisoryNotice}</p>
        </div>

        <button
          type="submit"
          className="rounded bg-blue px-5 py-2.5 text-sm font-bold text-white transition hover:bg-blue-dark"
        >
          {messages.tix.submit}
        </button>
      </form>

      {outcome && (
        <div
          role="status"
          className={`mt-5 rounded-lg border p-5 ${OUTCOME_STYLES[outcome]}`}
        >
          <p className="font-bold">{messages.tix.outcome[outcome]}</p>
          <p className="mt-1 text-sm">{messages.tix.advisoryNotice}</p>
        </div>
      )}
    </div>
  );
}
