"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import {
  ApiError,
  watchlistApi,
  type Watch,
  type WatchlistGroup,
} from "@/api/client";
import {
  Button,
  Card,
  EmptyState,
  ErrorNotice,
  Pill,
  inputClass,
  type Tone,
} from "@/components/ui";
import { Band, CountUp } from "@/components/visual/motion";

/**
 * Companies this institution is asking the exchange about on a schedule.
 *
 * <p>A watch is not a new power, and the page says so before it says anything else. It is the
 * inquiry this account can already make, asked every night, charged against the same hourly
 * allowance and written to the same audit trail with the same stated purpose — and it reports
 * exactly what an inquiry reports: an outcome and how many institutions, never which.
 *
 * <p>The sweep button prints what it cost. Watching two hundred companies spends two hundred
 * inquiries, and a screen that hid that would be the reason somebody found their own inquiries
 * refused at nine in the morning.
 */
export default function WatchlistsPage() {
  const messages = useMessages();
  const t = messages.watchlist;

  const [watches, setWatches] = useState<Watch[] | null>(null);
  const [groups, setGroups] = useState<WatchlistGroup[] | null>(null);
  const [newName, setNewName] = useState("");
  const [newPurpose, setNewPurpose] = useState("");
  const [creating, setCreating] = useState(false);
  const [forbidden, setForbidden] = useState(false);
  const [failure, setFailure] = useState<string | null>(null);
  const [sweeping, setSweeping] = useState(false);
  const [outcome, setOutcome] = useState<string | null>(null);

  const describe = (caught: unknown) =>
    caught instanceof ApiError
      ? `${caught.status} ${caught.code} — ${caught.message}`
      : String(caught);

  const load = useCallback(async () => {
    try {
      // Both together. Rendering the groups before the watches arrive would draw empty sections
      // and then fill them, which reads as a list that lost its contents.
      const [listed, grouped] = await Promise.all([
        watchlistApi.list(),
        watchlistApi.groups(),
      ]);
      setWatches(listed);
      setGroups(grouped);
      setFailure(null);
    } catch (caught) {
      if (caught instanceof ApiError && caught.status === 403) {
        setForbidden(true);
        setWatches([]);
        return;
      }
      setFailure(describe(caught));
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function onSweep() {
    setSweeping(true);
    setOutcome(null);
    try {
      const swept = await watchlistApi.sweep();
      setOutcome(
        interpolate(t.sweepResult, t.sweepResult, {
          checked: String(swept.checked),
          watched: String(swept.watched),
          changed: String(swept.changed),
        }),
      );
      await load();
    } catch (caught) {
      setFailure(describe(caught));
    } finally {
      setSweeping(false);
    }
  }

  async function onCreate() {
    setCreating(true);
    try {
      await watchlistApi.createGroup(newName.trim(), newPurpose.trim());
      setNewName("");
      setNewPurpose("");
      await load();
    } catch (caught) {
      setFailure(describe(caught));
    } finally {
      setCreating(false);
    }
  }

  async function onFile(watchId: string, watchlistId: string | null) {
    try {
      await watchlistApi.file(watchId, watchlistId);
      await load();
    } catch (caught) {
      setFailure(describe(caught));
    }
  }

  async function onRemove(id: string) {
    try {
      await watchlistApi.unwatch(id);
      await load();
    } catch (caught) {
      setFailure(describe(caught));
    }
  }

  return (
    <div className="mx-auto max-w-5xl">
      <Band>
        <div className="px-6 py-8 md:px-10 md:py-9">
          <p className="mb-2 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
            {t.eyebrow}
          </p>
          <h1 className="mb-2 text-3xl font-bold tracking-tight md:text-4xl">{t.title}</h1>
          <p className="mb-6 max-w-2xl text-sm text-white/70">{t.subtitle}</p>

          {/* The counts, and then the button that spends them. Watching two hundred companies
              costs two hundred inquiries a night, and a screen that hid the arithmetic would be
              the reason somebody finds their own inquiries refused at nine in the morning. */}
          <div className="flex flex-wrap items-center gap-x-8 gap-y-4">
            <div>
              <p className="text-3xl font-bold">
                <CountUp value={watches?.length ?? 0} />
              </p>
              <p className="text-xs text-white/60">{t.watchedCount}</p>
            </div>
            <div>
              <p className="text-3xl font-bold">
                <CountUp value={(groups ?? []).filter((g) => g.id !== null).length} />
              </p>
              <p className="text-xs text-white/60">{t.listCount}</p>
            </div>
            <div className="ml-auto flex flex-wrap items-center gap-3">
              {outcome && <span className="text-sm text-white/70">{outcome}</span>}
              <button
                type="button"
                onClick={() => void onSweep()}
                disabled={sweeping}
                className="rounded-full bg-white px-5 py-2.5 text-sm font-bold text-navy transition hover:bg-white/90 disabled:opacity-60"
              >
                {sweeping ? t.sweeping : t.sweep}
              </button>
            </div>
          </div>
        </div>
      </Band>

      <div className="mt-4 grid gap-3 sm:grid-cols-2">
        <p className="rounded-lg border border-line bg-soft px-4 py-3 text-sm text-muted">
          {t.why}
        </p>
        <Link
          href="/app/monitoring"
          className="group rounded-lg border border-blue/30 bg-blue/5 px-4 py-3 text-sm text-muted transition hover:border-blue/60"
        >
          {t.whyNightly}
          <span className="mt-1 block font-semibold text-blue">
            {t.openMonitoring}
            <span aria-hidden="true" className="ml-1 inline-block transition group-hover:translate-x-0.5">
              →
            </span>
          </span>
        </Link>
      </div>

      {failure && (
        <ErrorNotice>
          {t.loadFailed}
          <span className="mt-1 block font-mono text-xs">{failure}</span>
        </ErrorNotice>
      )}
      {forbidden && (
        <Card title={t.title}>
          <EmptyState>{t.forbidden}</EmptyState>
        </Card>
      )}
      {!failure && !forbidden && watches === null && (
        <EmptyState>{messages.common.loading}</EmptyState>
      )}

      {!forbidden && watches !== null && watches.length === 0 && (
        <Card title={t.empty} description={t.emptyHint}>
          <EmptyState>{t.emptyHint}</EmptyState>
        </Card>
      )}

      {!forbidden && watches !== null && watches.length > 0 && (
        <>
          {/* Grouped, and the unfiled section is last and named rather than hidden. A watch nobody
              put in a list is still being monitored, and a screen that only showed groups would
              quietly stop showing it. */}
          <div className="mt-6 flex flex-col gap-5">
            {sections(watches, groups).map((section) => {
              const accent = section.id ? accentFor(section.id) : null;
              return (
                <section
                  key={section.id ?? "unfiled"}
                  className={`overflow-hidden rounded-lg border bg-white ${
                    section.id ? "border-line" : "border-dashed border-line"
                  }`}
                >
                  <header className="flex flex-wrap items-start gap-3 border-b border-line px-5 py-4">
                    {/* Identity, not severity. See accentFor — these four hues are deliberately
                        not the ones that mean anything elsewhere on this screen. */}
                    <span
                      aria-hidden="true"
                      className="mt-1 h-8 w-1.5 shrink-0 rounded-full"
                      style={{ background: accent ?? "var(--color-line)" }}
                    />
                    <div className="min-w-0">
                      <h2 className="font-bold text-navy">{section.name ?? t.unfiled}</h2>
                      <p className="mt-0.5 text-sm text-muted">
                        {section.purpose ?? t.unfiledHint}
                      </p>
                    </div>
                    <span className="ml-auto rounded-full bg-soft px-3 py-1 text-sm font-bold tabular-nums text-navy">
                      {section.watches.length}
                    </span>
                  </header>

                  {section.watches.length === 0 ? (
                    // A list somebody made and has not filled. Not an error, and not hidden — the
                    // alternative makes "create" look like it did nothing.
                    <div className="px-5 py-6">
                      <EmptyState>{t.groupEmpty}</EmptyState>
                    </div>
                  ) : (
                    <div className="flex flex-col divide-y divide-line">
                      {section.watches.map((watch) => (
                        <Row
                          key={watch.id}
                          watch={watch}
                          t={t}
                          groups={groups ?? []}
                          onRemove={onRemove}
                          onFile={onFile}
                        />
                      ))}
                    </div>
                  )}
                </section>
              );
            })}
          </div>
        </>
      )}

      {!forbidden && watches !== null && (
        <div className="mt-6">
          <Card title={t.newGroup} description={t.newGroupHint} footer={t.expiryNote}>
            <form
              className="flex flex-col gap-3 sm:flex-row"
              onSubmit={(event) => {
                event.preventDefault();
                void onCreate();
              }}
            >
              <input
                className={inputClass}
                value={newName}
                onChange={(event) => setNewName(event.target.value)}
                placeholder={t.newGroupName}
                aria-label={t.newGroupName}
              />
              <input
                className={inputClass}
                value={newPurpose}
                onChange={(event) => setNewPurpose(event.target.value)}
                placeholder={t.newGroupPurpose}
                aria-label={t.newGroupPurpose}
              />
              <Button
                type="submit"
                disabled={
                  creating || newName.trim() === "" || newPurpose.trim() === ""
                }
              >
                {creating ? messages.common.loading : t.create}
              </Button>
            </form>
          </Card>
        </div>
      )}
    </div>
  );
}

/**
 * Which section each watch belongs in.
 *
 * <p>Built from the groups the server returned rather than from the watches, so an empty group
 * still appears. A list somebody made and has not filled is not the same as a list that does not
 * exist, and hiding it would make "create" look like it did nothing.
 *
 * <p>Unfiled last, and only when there is something in it — a section that materialises the first
 * time somebody forgets to file a watch is stranger than one that is simply absent.
 */
/**
 * A stable colour for a group, used as identity and never as a verdict.
 *
 * <p><strong>Four hues, and none of them is green, amber or red.</strong> Everywhere else on this
 * screen colour means severity — the outcome pill, the indicator bar, the alert queue next door —
 * and a group card in green beside a company in trouble would read as a judgement about the
 * companies in it. These are brand hues that carry no rating.
 *
 * <p>Derived from the id so it survives a rename and a reload. A colour that shuffled on refresh
 * would be decoration pretending to be a label.
 */
function accentFor(id: string): string {
  const hues = [
    "var(--color-blue)",
    "var(--color-teal)",
    "var(--color-purple)",
    "var(--color-navy)",
  ];
  let sum = 0;
  for (let index = 0; index < id.length; index += 1) {
    sum = (sum + id.charCodeAt(index)) % 4096;
  }
  return hues[sum % hues.length] ?? hues[0]!;
}

function sections(watches: Watch[], groups: WatchlistGroup[] | null) {
  const named = (groups ?? []).filter((group) => group.id !== null);
  const built = named.map((group) => ({
    id: group.id,
    name: group.name,
    purpose: group.purpose,
    watches: watches.filter((watch) => watch.watchlistId === group.id),
  }));

  const unfiled = watches.filter((watch) => watch.watchlistId === null);
  if (unfiled.length > 0) {
    built.push({ id: null, name: null, purpose: null, watches: unfiled });
  }
  return built;
}

function Row({
  watch,
  t,
  groups,
  onRemove,
  onFile,
}: {
  watch: Watch;
  t: ReturnType<typeof useMessages>["watchlist"];
  groups: WatchlistGroup[];
  onRemove: (id: string) => Promise<void>;
  onFile: (watchId: string, watchlistId: string | null) => Promise<void>;
}) {
  // Read here rather than in a helper below. The first version called useMessages() from a plain
  // function, which is a hook outside a component: it compiles, and React refuses it at runtime.
  const outcomes = useMessages().tix.outcomes;

  const OUTCOME_TONE: Record<NonNullable<Watch["lastOutcome"]>, Tone> = {
    NO_MATCH: "neutral",
    CLEAR: "positive",
    OUTSTANDING_DEBT: "serious",
    REVIEW_REQUIRED: "review",
  };

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-3.5 transition hover:bg-soft/60">
      <div className="min-w-0">
        <Link
          href={`/app/subjects/${watch.subjectId}`}
          className="font-semibold text-navy hover:text-blue hover:underline"
        >
          {watch.name}
        </Link>
        <p className="text-xs text-muted">
          {t.purpose}: {watch.purpose}
        </p>
      </div>

      {/* The indicator as it stood at the last sweep, drawn. A bare number tells a reader nothing
          about where 61 sits on a scale they have not memorised. Null is left blank rather than
          drawn as an empty bar: the exchange withholds a score for any answer it is not confident
          about, and a bar at zero would read as a company with nothing against it. */}
      {watch.lastScore !== null && (
        <div className="w-28 shrink-0">
          <div className="flex items-baseline justify-between">
            <span className="text-xs text-muted">{t.indicator}</span>
            <span className="text-sm font-bold tabular-nums text-navy">{watch.lastScore}</span>
          </div>
          <div className="mt-1 h-1.5 w-full overflow-hidden rounded-full bg-line">
            <span
              className="block h-full rounded-full"
              style={{
                width: `${Math.min(100, Math.max(2, watch.lastScore))}%`,
                background:
                  watch.lastScore >= 70
                    ? "var(--color-error)"
                    : watch.lastScore >= 40
                      ? "var(--color-warning)"
                      : "var(--color-success)",
              }}
            />
          </div>
        </div>
      )}

      <div className="ml-auto flex flex-wrap items-center gap-2">
        {/* Filing moves a watch between lists. It never stops one — that is the remove button, and
            keeping them visibly different is the point of having both. */}
        <select
          className="rounded border border-line bg-white px-2 py-1 text-xs text-ink"
          value={watch.watchlistId ?? ""}
          aria-label={t.fileUnder}
          onChange={(event) => void onFile(watch.id, event.target.value || null)}
        >
          <option value="">{t.unfiled}</option>
          {groups
            .filter((group) => group.id !== null)
            .map((group) => (
              <option key={group.id} value={group.id ?? ""}>
                {group.name}
              </option>
            ))}
        </select>

        {/* Null is not "clear". It means nobody has asked yet, which is the state every watch is
            in on the day it is created. */}
        {watch.lastOutcome ? (
          <>
            <Pill tone={OUTCOME_TONE[watch.lastOutcome]}>
              {outcomes[watch.lastOutcome]}
            </Pill>
            {watch.lastInstitutions !== null && (
              <span className="flex items-center gap-1.5 text-xs text-muted">
                {/* Marks rather than a numeral, as on the inquiry result. Identical, unlabelled,
                    in no meaningful order: three of them say three institutions and refuse to say
                    which, which is exactly what the number does. */}
                <span aria-hidden="true" className="flex gap-0.5">
                  {Array.from({ length: Math.min(watch.lastInstitutions, 5) }, (_, index) => (
                    <span key={index} className="h-3 w-1.5 rounded-sm bg-blue" />
                  ))}
                </span>
                <span className="tabular-nums">
                  {watch.lastInstitutions} {t.institutions}
                </span>
              </span>
            )}
          </>
        ) : (
          <span className="text-xs text-muted">{t.neverChecked}</span>
        )}

        <span className="text-xs text-muted">
          {watch.lastCheckedAt && (
            <span className="block">
              {t.lastChecked}: {watch.lastCheckedAt.slice(0, 10)}
            </span>
          )}
          <span className="block">
            {t.expires}: {watch.expiresAt.slice(0, 10)}
          </span>
        </span>

        <button
          type="button"
          onClick={() => void onRemove(watch.id)}
          className="rounded border border-line px-3 py-1.5 text-xs font-semibold text-muted transition hover:bg-soft"
        >
          {t.remove}
        </button>
      </div>
    </div>
  );
}
