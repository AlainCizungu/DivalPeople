"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useMessages } from "@/i18n/LocaleProvider";
import { ApiError, tixApi, type SubjectProfile } from "@/api/client";
import {
  Card,
  EmptyState,
  ErrorNotice,
  Metric,
  PageHeader,
  Pill,
  type Tone,
} from "@/components/ui";

/**
 * One subject, as far as this operator is concerned.
 *
 * <p>Everything here is the caller's own data, shown in full — withholding an operator's own
 * figures from it protects nobody. What is deliberately absent is any indication of whether
 * another operator holds anything: that question has an answer, and it is reached through an
 * inquiry, with a stated purpose, a rate limit and an audit row. A profile page that quietly
 * included it would be the exchange boundary leaking through a convenience.
 *
 * <p>A subject the operator holds nothing against returns "not found" rather than "not yours".
 * The two must be indistinguishable, or the URL becomes a way to test whether a business is in
 * the national registry.
 */
const STATUS_TONE: Record<string, Tone> = {
  OUTSTANDING: "serious",
  SETTLED: "positive",
  CLEARED: "positive",
  DISPUTED: "review",
  UNDER_INVESTIGATION: "review",
};

export default function SubjectProfilePage() {
  const messages = useMessages();
  const t = messages.search;
  const params = useParams<{ id: string }>();
  const subjectId = params.id;

  const [profile, setProfile] = useState<SubjectProfile | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notHeld, setNotHeld] = useState(false);

  const load = useCallback(async () => {
    try {
      setProfile(await tixApi.subject(subjectId));
    } catch (caught) {
      setProfile(null);
      // 404 and 403 are the same sentence on purpose: "you hold nothing about this subject".
      // Distinguishing them here would undo the server's refusal to distinguish them.
      const absent =
        caught instanceof ApiError && (caught.status === 404 || caught.status === 403);
      setNotHeld(absent);
      setError(
        absent
          ? null
          : caught instanceof ApiError
            ? caught.message
            : messages.common.unexpectedError,
      );
    }
  }, [subjectId, messages.common.unexpectedError]);

  useEffect(() => {
    void load();
  }, [load]);

  const back = (
    <Link href="/app/search" className="text-sm font-semibold text-blue hover:underline">
      ← {t.backToSearch}
    </Link>
  );

  if (notHeld) {
    return (
      <div className="mx-auto max-w-5xl">
        <PageHeader title={t.profileTitle} subtitle={t.profileSubtitle} action={back} />
        <EmptyState>{t.notHeld}</EmptyState>
      </div>
    );
  }

  if (error) {
    return (
      <div className="mx-auto max-w-5xl">
        <PageHeader title={t.profileTitle} subtitle={t.profileSubtitle} action={back} />
        <ErrorNotice>{error}</ErrorNotice>
      </div>
    );
  }

  if (profile === null) {
    return (
      <div className="mx-auto max-w-5xl">
        <PageHeader title={t.profileTitle} subtitle={t.profileSubtitle} action={back} />
        <EmptyState>{messages.common.loading}</EmptyState>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader title={profile.name} subtitle={t.profileSubtitle} action={back} />

      {/* The way on to the exchange, and it is a link rather than a section of this page. Asking
          costs an inquiry against the hourly allowance and needs a stated purpose; folding it in
          here would spend both because somebody opened their own file. */}
      <Link
        href={`/app/subjects/${subjectId}/profile`}
        className="mb-6 flex items-center justify-between gap-4 rounded-lg border border-blue/30 bg-blue/5 px-5 py-4 transition hover:border-blue/60"
      >
        <span>
          <strong className="block text-sm font-bold text-navy">{t.open360}</strong>
          <span className="text-sm text-muted">{t.open360Note}</span>
        </span>
        <span aria-hidden="true" className="text-blue">
          →
        </span>
      </Link>

      <div className="grid gap-4 sm:grid-cols-3">
        <Metric label={t.colRecords} value={String(profile.summary.recordCount)} />
        <Metric
          label={t.colOutstanding}
          value={
            profile.summary.mixedCurrency
              ? t.mixedCurrency
              : `${profile.summary.outstanding} ${profile.summary.currency}`
          }
        />
        <Metric
          label={t.colOldest}
          value={
            profile.summary.oldestBand
              ? messages.portfolio.bands[profile.summary.oldestBand]
              : "—"
          }
        />
      </div>

      <div className="mt-6 grid gap-6 lg:grid-cols-2">
        <Card title={t.identifiersTitle} description={t.identifiersDescription}>
          <ul className="flex flex-col divide-y divide-line">
            {profile.identifiers.map((identifier) => (
              <li
                key={`${identifier.type}-${identifier.value}`}
                className="flex items-center justify-between gap-4 py-3"
              >
                <span className="text-sm text-muted">
                  {messages.tix.identifierTypes[identifier.type]}
                </span>
                <span className="font-mono text-sm font-semibold text-navy">
                  {identifier.value}
                </span>
              </li>
            ))}
          </ul>
        </Card>

        <Card title={t.detailsTitle}>
          <dl className="flex flex-col divide-y divide-line">
            <div className="flex items-center justify-between gap-4 py-3">
              <dt className="text-sm text-muted">{t.colName}</dt>
              <dd className="text-sm font-semibold text-navy">
                {t.types[profile.subjectType]}
              </dd>
            </div>
            {profile.dateOfBirth && (
              <div className="flex items-center justify-between gap-4 py-3">
                <dt className="text-sm text-muted">{t.dateOfBirth}</dt>
                <dd className="text-sm tabular-nums text-navy">{profile.dateOfBirth}</dd>
              </div>
            )}
            {profile.nationality && (
              <div className="flex items-center justify-between gap-4 py-3">
                <dt className="text-sm text-muted">{t.nationality}</dt>
                <dd className="text-sm text-navy">{profile.nationality}</dd>
              </div>
            )}
          </dl>
          {/* The other half of the picture is behind a purpose and a rate limit, and it stays
              there. A link, not an inlined answer. */}
          <p className="mt-4 border-t border-line pt-4">
            <Link href="/app/tix" className="text-sm font-semibold text-blue hover:underline">
              {t.inquire} →
            </Link>
          </p>
        </Card>
      </div>

      <div className="mt-6">
        <Card title={t.recordsTitle} description={t.recordsDescription}>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[42rem] text-left text-sm">
              <thead className="border-b border-line text-xs tracking-wide text-muted uppercase">
                <tr>
                  <th scope="col" className="pb-3 pr-4 font-semibold">{t.colStatus}</th>
                  <th scope="col" className="pb-3 pr-4 text-right font-semibold">
                    {t.colAmount}
                  </th>
                  <th scope="col" className="pb-3 pr-4 font-semibold">{t.colService}</th>
                  <th scope="col" className="pb-3 pr-4 font-semibold">{t.colDefaultDate}</th>
                  <th scope="col" className="pb-3 pr-4 font-semibold">{t.colAge}</th>
                  <th scope="col" className="pb-3 pr-4 font-semibold">{t.colRetainedUntil}</th>
                  <th scope="col" className="pb-3 font-semibold">{t.colOrigin}</th>
                </tr>
              </thead>
              <tbody>
                {profile.records.map((record) => (
                  <tr key={record.recordId} className="border-b border-line last:border-0">
                    <td className="py-3 pr-4">
                      <Pill tone={STATUS_TONE[record.status] ?? "neutral"}>
                        {messages.tix.statuses[record.status]}
                      </Pill>
                    </td>
                    <td className="py-3 pr-4 text-right font-bold tabular-nums text-navy">
                      {record.amount} {record.currency}
                    </td>
                    <td className="py-3 pr-4 text-ink">{record.serviceCategory}</td>
                    <td className="py-3 pr-4 tabular-nums text-muted">{record.defaultDate}</td>
                    <td className="py-3 pr-4 text-muted">
                      {messages.portfolio.bands[record.band]}
                    </td>
                    <td className="py-3 pr-4 tabular-nums text-muted">{record.retainedUntil}</td>
                    <td className="py-3">
                      <Pill>{record.imported ? t.originImport : t.originDeclared}</Pill>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      </div>
    </div>
  );
}
