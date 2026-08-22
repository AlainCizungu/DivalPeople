"use client";

import { useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import {
  ApiError,
  tixApi,
  type DeclarationResult,
  type IdentifierType,
  type SubjectType,
} from "@/api/client";
import {
  Button,
  Card,
  ErrorNotice,
  Field,
  PageHeader,
  Pill,
  inputClass,
} from "@/components/ui";

const IDENTIFIER_TYPES: IdentifierType[] = [
  "NATIONAL_ID",
  "MSISDN",
  "PASSPORT",
  "DRIVER_LICENSE",
  "VOTER_CARD",
  "RCCM",
  "TAX_NUMBER",
  "ACCOUNT_REFERENCE",
];

/**
 * Declare a default.
 *
 * <p>The screen for the endpoint the module shipped without. Two things it does that a plain form
 * would not:
 *
 * <p>It requires the dunning box to be ticked before the button enables, rather than letting the
 * server refuse afterwards. The API check stays and is the real guarantee — this is only so the
 * operator meets the rule while they are still looking at the form.
 *
 * <p>And it reports {@code subjectWasCreated} prominently on success. Adding a record to somebody
 * already in the registry and putting somebody into a national registry for the first time are
 * different acts, and the person doing it should be able to tell which one just happened.
 */
export default function DeclarePage() {
  const messages = useMessages();
  const t = messages.tix.declare;

  const [identifierType, setIdentifierType] = useState<IdentifierType>("RCCM");
  const [identifier, setIdentifier] = useState("");
  const [subjectType, setSubjectType] = useState<SubjectType>("BUSINESS");
  const [fullName, setFullName] = useState("");
  const [amount, setAmount] = useState("");
  const [currency, setCurrency] = useState("USD");
  const [serviceCategory, setServiceCategory] = useState("POSTPAID");
  const [defaultDate, setDefaultDate] = useState("");
  const [dunning, setDunning] = useState(false);

  const [result, setResult] = useState<DeclarationResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const complete =
    identifier.trim() !== "" &&
    fullName.trim() !== "" &&
    amount.trim() !== "" &&
    defaultDate !== "" &&
    dunning;

  async function onSubmit(event: React.FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(null);
    setResult(null);

    try {
      setResult(
        await tixApi.declare({
          identifiers: [{ type: identifierType, value: identifier.trim() }],
          fullName: fullName.trim(),
          subjectType,
          dateOfBirth: null,
          nationality: "CD",
          amount: amount.trim(),
          currency: currency.trim().toUpperCase(),
          serviceCategory: serviceCategory.trim(),
          defaultDate,
          dunningEvidence: dunning,
        }),
      );
      setIdentifier("");
      setFullName("");
      setAmount("");
      setDefaultDate("");
      setDunning(false);
    } catch (caught) {
      // The API's own sentence, not a generic one. A refusal is only useful if the operator can
      // tell whether it was the threshold, an unconfigured currency, or an open record already
      // held against this subject — and the backend writes exactly that.
      setError(
        caught instanceof ApiError ? caught.message : messages.common.unexpectedError,
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mx-auto max-w-3xl">
      {/* Deliberately sober, and the only band in the application that is not the house gradient.
          Every other screen reads something; this one writes — it puts a company on a national
          register, where every participating institution can see that somebody is owed money. A
          cheerful header on the one screen whose action harms a third party would be the wrong
          instinct dressed as polish. */}
      <div className="overflow-hidden rounded-2xl border-l-4 border-l-warning bg-navy text-white">
        <div className="px-6 py-8 md:px-10 md:py-9">
          <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-warning uppercase">
            {t.eyebrow}
          </p>
          <h1 className="mb-2 text-3xl font-bold tracking-tight md:text-4xl">{t.title}</h1>
          <p className="mb-5 max-w-2xl text-sm text-white/70">{t.subtitle}</p>

          {/* What happens after the button, before the button. Three consequences, stated where
              somebody reads them rather than in a note underneath the form they have already
              filled in. */}
          <ul className="flex flex-col gap-2 text-sm text-white/80">
            {[t.consequenceExchange, t.consequenceRetention, t.consequenceDispute].map(
              (line) => (
                <li key={line} className="flex gap-2.5">
                  <span aria-hidden="true" className="text-warning">
                    •
                  </span>
                  {line}
                </li>
              ),
            )}
          </ul>

          <Link
            href="/app/tix/records"
            className="mt-5 inline-block text-sm font-bold text-blue hover:underline"
          >
            {t.viewRecords} →
          </Link>
        </div>
      </div>

      <div className="mt-6" />

      <Card>
        <form onSubmit={onSubmit} className="flex flex-col gap-5">
          <div className="grid gap-5 sm:grid-cols-2">
            <Field label={t.subjectType} htmlFor="subjectType">
              <select
                id="subjectType"
                className={inputClass}
                value={subjectType}
                onChange={(e) => setSubjectType(e.target.value as SubjectType)}
              >
                <option value="BUSINESS">{t.business}</option>
                <option value="INDIVIDUAL">{t.individual}</option>
              </select>
            </Field>

            <Field label={t.fullName} htmlFor="fullName" hint={t.fullNameHint}>
              <input
                id="fullName"
                className={inputClass}
                value={fullName}
                onChange={(e) => setFullName(e.target.value)}
                required
              />
            </Field>

            <Field label={t.identifierType} htmlFor="identifierType">
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

            <Field label={t.identifier} htmlFor="identifier" hint={t.identifierHint}>
              <input
                id="identifier"
                className={inputClass}
                value={identifier}
                onChange={(e) => setIdentifier(e.target.value)}
                required
              />
            </Field>

            <Field label={t.amount} htmlFor="amount" hint={t.amountHint}>
              <input
                id="amount"
                type="number"
                min="0"
                step="0.01"
                className={inputClass}
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                required
              />
            </Field>

            <Field label={t.currency} htmlFor="currency" hint={t.currencyHint}>
              <input
                id="currency"
                maxLength={3}
                className={inputClass}
                value={currency}
                onChange={(e) => setCurrency(e.target.value)}
                required
              />
            </Field>

            <Field label={t.serviceCategory} htmlFor="serviceCategory">
              <input
                id="serviceCategory"
                className={inputClass}
                value={serviceCategory}
                onChange={(e) => setServiceCategory(e.target.value)}
                required
              />
            </Field>

            <Field label={t.defaultDate} htmlFor="defaultDate" hint={t.defaultDateHint}>
              <input
                id="defaultDate"
                type="date"
                max={new Date().toISOString().slice(0, 10)}
                className={inputClass}
                value={defaultDate}
                onChange={(e) => setDefaultDate(e.target.value)}
                required
              />
            </Field>
          </div>

          <label className="flex items-start gap-2.5 rounded border border-warning/50 bg-warning/10 p-4 text-sm text-[#7c4a03]">
            <input
              type="checkbox"
              className="mt-0.5"
              checked={dunning}
              onChange={(e) => setDunning(e.target.checked)}
            />
            <span>{t.dunning}</span>
          </label>

          {error && <ErrorNotice>{error}</ErrorNotice>}

          <div className="flex items-center gap-3">
            <Button type="submit" disabled={submitting || !complete}>
              {submitting ? messages.common.loading : t.submit}
            </Button>
            {!dunning && <span className="text-sm text-muted">{t.dunningRequired}</span>}
          </div>
        </form>
      </Card>

      {result && (
        <div className="mt-6">
          {/* Confirmation, not congratulation. No green anywhere: a declaration that succeeded is
              not good news, it is a record created about a company that will be visible to every
              participant until it expires. Green here would be the platform cheering an act it
              exists to take seriously. */}
          <Card title={t.declared}>
            <div className="flex flex-col gap-4">
              {result.subjectWasCreated ? (
                <p className="rounded border border-warning/50 bg-warning/10 px-4 py-3 text-sm text-[#7c4a03]">
                  <strong>{t.subjectCreatedTitle}</strong> {t.subjectCreatedBody}
                </p>
              ) : (
                <p className="rounded border border-line bg-soft px-4 py-3 text-sm text-ink">
                  {t.subjectExisted}
                </p>
              )}

              <dl className="grid gap-4 sm:grid-cols-3">
                <div>
                  <dt className="text-xs text-muted">{t.amount}</dt>
                  <dd className="mt-0.5 font-bold tabular-nums text-navy">
                    {result.record.amount} {result.record.currency}
                  </dd>
                </div>
                <div>
                  <dt className="text-xs text-muted">{t.status}</dt>
                  <dd className="mt-0.5">
                    <Pill tone="serious">{messages.tix.statuses[result.record.status]}</Pill>
                  </dd>
                </div>
                <div>
                  <dt className="text-xs text-muted">{t.retainedUntil}</dt>
                  {/* Given the weight of the figure it is. This date is how long a company carries
                      the consequence of one afternoon's data entry, and it was set in the same
                      size as the service category. */}
                  <dd className="mt-0.5 text-lg font-bold tabular-nums text-navy">
                    {result.record.retentionUntil}
                  </dd>
                </div>
              </dl>

              <p className="text-sm text-muted">{t.retentionNote}</p>
            </div>
          </Card>
        </div>
      )}
    </div>
  );
}
