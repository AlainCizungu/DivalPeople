"use client";

import { useCallback, useEffect, useState } from "react";
import { useMessages } from "@/i18n/LocaleProvider";
import { useSession } from "@/auth/SessionProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  ApiError,
  resolutionApi,
  type MatchCase,
  type MatchSignal,
  type MatchStatus,
  type RegistrySubject,
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

/**
 * The Identity Resolution Center.
 *
 * <p>The DRC has no single identifier covering everybody, so two institutions reporting one person
 * create two records and nothing in either file says they are one. Making the resolution of that a
 * feature rather than an apology is the point of this screen.
 *
 * <p><strong>It belongs to the registry and to nobody else.</strong> Each case shows one operator's
 * record beside another's with both names visible; a participant with this queue would be reading a
 * competitor's customer file one review at a time, which is the disclosure the whole exchange is
 * built to prevent. Hence PLATFORM_ADMIN on every endpoint behind it.
 *
 * <p>The menu entry, however, is shown to everybody, which reverses how this shipped. Hiding it
 * meant a built feature read as unbuilt — a "Soon" chip for something that exists — and that is a
 * worse thing to tell somebody than a refusal. So the page opens for anybody and says whose work
 * this is, and the API's 403 is rendered as the explanation above rather than as a fault.
 *
 * <p>Nothing here decides on its own at any confidence. The number decides whether somebody is
 * asked to look.
 */
export default function ResolutionPage() {
  const messages = useMessages();
  const t = messages.resolution;
  const { profile } = useSession();

  const [cases, setCases] = useState<MatchCase[] | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  /**
   * Refused rather than broken.
   *
   * <p>Kept apart from {@code failure} because the two want different words. A 403 here is the
   * design working — the registry resolves and a participant does not — and printing it as
   * "could not load the review queue, 403" would read as a fault in a screen that is behaving
   * exactly as intended.
   */
  const [forbidden, setForbidden] = useState(false);
  const [scanning, setScanning] = useState(false);
  const [outcomeMessage, setOutcomeMessage] = useState<string | null>(null);

  const describe = (caught: unknown) =>
    caught instanceof ApiError
      ? `${caught.status} ${caught.code} — ${caught.message}`
      : String(caught);

  const load = useCallback(async () => {
    try {
      setCases(await resolutionApi.open());
      setFailure(null);
      setForbidden(false);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 403) {
        setForbidden(true);
        setCases([]);
        return;
      }
      setFailure(describe(caught));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function onScan() {
    setScanning(true);
    setOutcomeMessage(null);
    try {
      const scan = await resolutionApi.scan();
      setOutcomeMessage(
        interpolate(t.scanResult, t.scanResult, {
          compared: String(scan.compared),
          subjects: String(scan.subjects),
          opened: String(scan.opened),
        }),
      );
      await load();
    } catch (caught) {
      setFailure(describe(caught));
    } finally {
      setScanning(false);
    }
  }

  async function onDecide(caseId: string, outcome: MatchStatus, note: string) {
    try {
      const decision = await resolutionApi.decide(caseId, outcome, note);
      setOutcomeMessage(
        decision.moved > 0
          ? interpolate(t.decided, t.decided, { moved: String(decision.moved) })
          : t.decidedNoMove,
      );
      await load();
    } catch (caught) {
      setFailure(describe(caught));
    }
  }

  return (
    <div className="mx-auto max-w-5xl">
      <PageHeader title={t.title} subtitle={t.subtitle} />

      <p className="mb-5 rounded border border-line bg-soft px-4 py-3 text-sm text-muted">
        {t.why}
      </p>

      {forbidden && (
        <Card title={t.title}>
          <EmptyState>{t.forbidden}</EmptyState>
          {/* The refusal explains the policy; this says which account is being refused.
              Without it the page is a wall — correct, and no help at all to somebody trying to
              work out whether they signed in as the wrong user or whether the role never
              arrived. Two different problems, one screen, and the screen is the only witness. */}
          <p className="mt-3 border-t border-line pt-3 text-xs text-muted">
            {interpolate(t.whoYouAre, t.whoYouAre, {
              name: profile?.preferredUsername ?? profile?.email ?? "—",
              roles: (profile?.roles ?? []).join(", ") || t.noRoles,
            })}
          </p>
        </Card>
      )}

      <div className={forbidden ? "hidden" : "mb-6 flex flex-wrap items-center gap-3"}>
        <Button type="button" onClick={() => void onScan()} disabled={scanning}>
          {scanning ? t.scanning : t.scan}
        </Button>
        {outcomeMessage && <span className="text-sm text-muted">{outcomeMessage}</span>}
      </div>

      {failure && (
        <ErrorNotice>
          {t.loadFailed}
          <span className="mt-1 block font-mono text-xs">{failure}</span>
        </ErrorNotice>
      )}
      {!failure && cases === null && <EmptyState>{messages.common.loading}</EmptyState>}

      {!forbidden && cases !== null && cases.length === 0 && (
        <Card title={t.empty} description={t.emptyHint}>
          <EmptyState>{t.empty}</EmptyState>
        </Card>
      )}

      <div className="flex flex-col gap-6">
        {(cases ?? []).map((pending) => (
          <CaseCard key={pending.id} pending={pending} t={t} onDecide={onDecide} />
        ))}
      </div>
    </div>
  );
}

function CaseCard({
  pending,
  t,
  onDecide,
}: {
  pending: MatchCase;
  t: ReturnType<typeof useMessages>["resolution"];
  onDecide: (caseId: string, outcome: MatchStatus, note: string) => Promise<void>;
}) {
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);

  // Required on all three outcomes. The merge can be undone; the reason somebody believed two
  // records were one person cannot be recovered from anywhere else.
  const ready = note.trim().length > 0 && !busy;

  async function decide(outcome: MatchStatus) {
    setBusy(true);
    try {
      await onDecide(pending.id, outcome, note.trim());
    } finally {
      setBusy(false);
    }
  }

  const percent = Math.round(pending.confidence * 100);

  return (
    <div className="rounded-lg border border-line bg-white p-5">
      <div className="mb-5 flex flex-wrap items-center gap-3">
        <Pill tone="review">{t.possibleMatch}</Pill>
        <span className="ml-auto text-right">
          <span className="block text-xs text-muted">{t.confidence}</span>
          <span className="text-3xl font-bold tabular-nums text-navy">{percent}%</span>
        </span>
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        {/* Left is the older record, which a confirmation keeps. Said on the card rather than
            left to be discovered, because a reviewer choosing between two names is entitled to
            know the registry has already chosen which one survives. */}
        <RecordCard subject={pending.left} label={t.recordA} kept t={t} />
        <RecordCard subject={pending.right} label={t.recordB} t={t} />
      </div>

      <div className="mt-5">
        <h3 className="mb-2 text-sm font-bold text-navy">{t.signalsTitle}</h3>
        <ul className="flex flex-col gap-1">
          {pending.signals.map((signal) => (
            <SignalRow key={signal.code} signal={signal} t={t} />
          ))}
        </ul>
        <p className="mt-3 text-xs text-muted">{t.unavailableNote}</p>
        <p className="mt-1.5 text-xs text-muted">{t.neutralNote}</p>
        {/* Shown only where it applies. A register number that differs is the one signal on this
            card whose weight a reviewer would otherwise read as a bug — it looks far too light
            beside the others until somebody explains that an RCCM gets reissued. */}
        {pending.signals.some(
          (signal) =>
            signal.code === "SHARED_REGISTER_NUMBER" && signal.verdict === "CONFLICTS",
        ) && <p className="mt-1.5 text-xs text-muted">{t.registerNumberNote}</p>}
      </div>

      <div className="mt-5">
        <Field label={t.noteLabel} htmlFor={`note-${pending.id}`} hint={t.noteHint}>
          <input
            id={`note-${pending.id}`}
            className={inputClass}
            value={note}
            placeholder={t.notePlaceholder}
            onChange={(event) => setNote(event.target.value)}
          />
        </Field>
      </div>

      <p className="mt-3 rounded border border-line bg-soft px-3 py-2.5 text-xs text-muted">
        {t.confirmWarning}
      </p>

      <div className="mt-4 flex flex-wrap gap-3">
        <Button type="button" disabled={!ready} onClick={() => void decide("CONFIRMED")}>
          {t.confirm}
        </Button>
        <button
          type="button"
          disabled={!ready}
          onClick={() => void decide("REJECTED")}
          className="rounded border border-ink px-4 py-3 text-sm font-bold text-ink transition hover:bg-soft disabled:opacity-40"
        >
          {t.reject}
        </button>
        {/* The third button, and the one a queue is worse without: a reviewer who cannot tell has
            an honest answer available. Offering only the first two pushes that into "not same
            subject", because rejecting feels safer, and the pair leaves looking decided. */}
        <button
          type="button"
          disabled={!ready}
          onClick={() => void decide("INVESTIGATING")}
          className="rounded border border-line px-4 py-3 text-sm font-bold text-muted transition hover:bg-soft disabled:opacity-40"
        >
          {t.investigate}
        </button>
      </div>

      <p className="mt-4 text-xs text-muted">
        {interpolate(t.modelNote, t.modelNote, { version: pending.modelVersion })}
      </p>
    </div>
  );
}

function RecordCard({
  subject,
  label,
  kept = false,
  t,
}: {
  subject: RegistrySubject;
  label: string;
  kept?: boolean;
  t: ReturnType<typeof useMessages>["resolution"];
}) {
  const identifiers = Object.entries(subject.nationalIdentifiers);

  return (
    <div className="rounded-lg border border-line bg-soft p-4">
      <div className="mb-1.5 flex items-center gap-2">
        <span className="text-xs font-bold uppercase tracking-wide text-muted">{label}</span>
        {kept && <Pill tone="positive">{t.keptOnConfirm}</Pill>}
      </div>

      <p className="text-base font-bold text-navy">{subject.fullName}</p>
      <p className="text-xs text-muted">{subject.business ? t.business : t.individual}</p>

      <dl className="mt-3 flex flex-col gap-1 text-sm">
        {subject.dateOfBirth && (
          <Line label={t.bornOn} value={subject.dateOfBirth} />
        )}
        {subject.nationality && (
          <Line label={t.nationality} value={subject.nationality} />
        )}
        {identifiers.length === 0 ? (
          // The ordinary case rather than the exception, and the reason this whole screen exists.
          <span className="text-xs text-muted">{t.noIdentifiers}</span>
        ) : (
          identifiers.map(([type, value]) => (
            <Line key={type} label={type} value={value} mono />
          ))
        )}
        {subject.hasAccountReference && (
          <span className="text-xs text-muted">{t.accountReference}</span>
        )}
      </dl>
    </div>
  );
}

function Line({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="flex gap-2">
      <dt className="shrink-0 text-xs text-muted">{label}</dt>
      <dd className={mono ? "font-mono text-xs text-ink" : "text-xs text-ink"}>{value}</dd>
    </div>
  );
}

function SignalRow({
  signal,
  t,
}: {
  signal: MatchSignal;
  t: ReturnType<typeof useMessages>["resolution"];
}) {
  // Four states, four marks. UNAVAILABLE deliberately does not get a cross: a reviewer who sees
  // one concludes the records disagreed about a city, when in fact no delivery carries a city.
  const MARK: Record<MatchSignal["verdict"], string> = {
    AGREES: "✓",
    CONFLICTS: "✕",
    NEUTRAL: "⚠",
    UNAVAILABLE: "—",
  };
  const TONE: Record<MatchSignal["verdict"], Tone> = {
    AGREES: "positive",
    CONFLICTS: "serious",
    NEUTRAL: "review",
    UNAVAILABLE: "neutral",
  };

  const muted = signal.verdict === "UNAVAILABLE";

  return (
    <li className="flex items-center gap-2.5 text-sm">
      <span aria-hidden className="w-4 text-center font-bold text-muted">
        {MARK[signal.verdict]}
      </span>
      <span className={muted ? "text-muted" : "text-ink"}>{t.signals[signal.code]}</span>
      <span className="ml-auto">
        <Pill tone={TONE[signal.verdict]}>{t.verdicts[signal.verdict]}</Pill>
      </span>
    </li>
  );
}
