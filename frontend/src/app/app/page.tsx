"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import { useSession } from "@/auth/SessionProvider";
import {
  executiveApi,
  overviewApi,
  tixApi,
  type ExecutiveBriefing,
  type Overview,
  type SearchResult,
} from "@/api/client";
import { Spotlight } from "@/components/dashboard/Spotlight";
import { HoverTile, Ring, Sparkline } from "@/components/visual/motion";
import { Button, Card, EmptyState, inputClass } from "@/components/ui";

/**
 * The platform's front door.
 *
 * <p>It used to fetch every debt record the operator had ever declared, send them all to the
 * browser and count them there. Its own javadoc said the fix was an endpoint that counts, and one
 * real import made that urgent: 3,699 records over the wire to render four numbers.
 *
 * <p><strong>Organised by what is waiting on somebody</strong>, not by what is impressive. The
 * spotlight is empty on a good day, and that is the point — a dashboard whose top can be quiet is
 * one people believe when it is not. Totals come second, because a total is a thing you look at
 * once a month and an overdue statutory deadline is a thing you look at today.
 *
 * <p><strong>Every figure links to the list it was counted from.</strong> That rule survived the
 * visual pass unchanged and constrains it: the ring, the bars and the tiles are all openable, and
 * nothing was added that draws a number this platform did not compute. A dashboard that is more
 * pleasant to look at and less checkable would be a worse dashboard.
 *
 * <p>The activity series comes from the executive briefing rather than a second endpoint built for
 * this page. It is the same thirteen months the executive screen draws, so two screens cannot
 * disagree about a month, and it is fetched separately so that a caller entitled to one and not
 * the other still gets a working page.
 */
export default function DashboardPage() {
  const messages = useMessages();
  const t = messages.dashboard;
  const { profile } = useSession();
  const router = useRouter();

  const [overview, setOverview] = useState<Overview | null>(null);
  const [briefing, setBriefing] = useState<ExecutiveBriefing | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const loaded = await overviewApi.load();
        if (!cancelled) setOverview(loaded);
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();
    // Separate, and its failure is silent. The briefing is the chart; the counts are the page. A
    // caller who cannot read one must still get the other rather than an error screen.
    void (async () => {
      try {
        const loaded = await executiveApi.load();
        if (!cancelled) setBriefing(loaded);
      } catch {
        /* The chart simply does not appear. */
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const loading = overview === null && !failed;
  const rights = overview?.rights ?? null;
  const register = overview?.register ?? null;
  const deliveries = overview?.deliveries ?? null;

  const roles = profile?.roles ?? [];
  const actions = [
    { href: "/app/tix", label: t.actionInquire, role: "TIX_INQUIRER" },
    { href: "/app/tix/declare", label: t.actionDeclare, role: "TIX_DECLARANT" },
    { href: "/app/imports", label: t.actionImports, role: "TIX_DECLARANT" },
    { href: "/app/tix/portfolio", label: t.actionPortfolio, role: "TIX_DECLARANT" },
    { href: "/app/subject-requests", label: t.actionCases, role: "TIX_DECLARANT" },
    { href: "/app/audit", label: t.actionAudit, role: "TENANT_ADMIN" },
  ].filter((action) => roles.includes(action.role));

  /**
   * What the band rotates through, in the order somebody should deal with it.
   *
   * Built from the counts rather than from a list of features, so a slide exists only while the
   * thing it describes is true. When none of them are, the band carries the quiet-day slide and
   * stops moving.
   */
  const slides = useMemo(() => {
    if (!overview) return [];
    const built: {
      key: string;
      eyebrow: string;
      headline: string;
      action: string;
      href: string;
    }[] = [];

    if (rights && rights.overdue > 0) {
      built.push({
        key: "overdue",
        eyebrow: t.spotlight.statutory,
        headline: interpolate(t.spotlight.overdue, t.spotlight.overdue, {
          count: String(rights.overdue),
        }),
        action: t.spotlight.openCases,
        href: "/app/subject-requests",
      });
    }
    if (rights && rights.dueSoon > 0) {
      built.push({
        key: "dueSoon",
        eyebrow: t.spotlight.statutory,
        headline: interpolate(t.spotlight.dueSoon, t.spotlight.dueSoon, {
          count: String(rights.dueSoon),
        }),
        action: t.spotlight.openCases,
        href: "/app/subject-requests",
      });
    }
    if (register && register.awaitingErasure > 0) {
      built.push({
        key: "erasure",
        eyebrow: t.spotlight.retention,
        headline: interpolate(t.spotlight.awaitingErasure, t.spotlight.awaitingErasure, {
          count: String(register.awaitingErasure),
        }),
        action: t.spotlight.openPortfolio,
        href: "/app/tix/portfolio",
      });
    }
    if (deliveries && deliveries.awaitingPublication + deliveries.awaitingValidation > 0) {
      built.push({
        key: "deliveries",
        eyebrow: t.spotlight.deliveries,
        headline: interpolate(t.spotlight.awaitingDelivery, t.spotlight.awaitingDelivery, {
          count: String(deliveries.awaitingPublication + deliveries.awaitingValidation),
        }),
        action: t.spotlight.openImports,
        href: "/app/imports",
      });
    }
    if (register && register.contested > 0) {
      built.push({
        key: "contested",
        eyebrow: t.spotlight.contested,
        headline: interpolate(t.spotlight.contestedRecords, t.spotlight.contestedRecords, {
          count: String(register.contested),
        }),
        action: t.spotlight.openRecords,
        href: "/app/tix/records",
      });
    }

    if (built.length === 0) {
      built.push({
        key: "quiet",
        eyebrow: t.spotlight.quietEyebrow,
        headline: t.spotlight.quiet,
        action: t.spotlight.openRecords,
        href: "/app/tix/records",
      });
    }
    return built;
  }, [overview, rights, register, deliveries, t]);

  const activity = briefing?.activity ?? null;

  return (
    <div className="mx-auto max-w-6xl">
      <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-navy">{t.title}</h1>
          <p className="mt-1 text-sm text-muted">
            {t.subtitle.replace("{date}", overview?.asOf ?? "…")}
          </p>
        </div>
      </div>

      {loading ? (
        <Card>
          <EmptyState>{messages.common.loading}</EmptyState>
        </Card>
      ) : (
        <Spotlight slides={slides} />
      )}

      <div className="mt-6">
        <LookupBar />
      </div>

      {!loading && (
        <>
          <h2 className="mt-8 mb-3 text-xs font-semibold tracking-[0.16em] text-muted uppercase">
            {t.waitingTitle}
          </h2>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <HoverTile
              href="/app/subject-requests"
              label={t.overdue}
              value={rights?.overdue ?? null}
              reveal={t.overdueNote}
              tone={(rights?.overdue ?? 0) > 0 ? "serious" : "plain"}
            />
            <HoverTile
              href="/app/subject-requests"
              label={t.dueSoon}
              value={rights?.dueSoon ?? null}
              reveal={t.dueSoonNote}
              tone={(rights?.dueSoon ?? 0) > 0 ? "warning" : "plain"}
            />
            <HoverTile
              href="/app/imports"
              label={t.awaitingValidation}
              value={deliveries?.awaitingValidation ?? null}
              reveal={t.awaitingValidationNote}
              tone={(deliveries?.awaitingValidation ?? 0) > 0 ? "warning" : "plain"}
            />
            <HoverTile
              href="/app/imports"
              label={t.awaitingPublication}
              value={deliveries?.awaitingPublication ?? null}
              reveal={t.awaitingPublicationNote}
              tone={(deliveries?.awaitingPublication ?? 0) > 0 ? "warning" : "plain"}
            />
          </div>

          <div className="mt-6 grid gap-6 lg:grid-cols-2">
            <Card title={t.registerTitle} description={t.registerDescription}>
              {register === null ? (
                // Absent, not nought. "You have declared nothing" and "this is not yours to see"
                // are different statements, and showing the first when you mean the second is a
                // false reassurance.
                <EmptyState>{t.noRegister}</EmptyState>
              ) : (
                <>
                  <Ring
                    total={register.total}
                    caption={t.declaredRecords}
                    segments={[
                      {
                        label: t.openRecords,
                        value: register.outstanding,
                        colour: "var(--color-error)",
                      },
                      {
                        label: t.contestedRecords,
                        value: register.contested,
                        colour: "var(--color-warning)",
                      },
                      {
                        label: t.settledRecords,
                        value: register.settled,
                        colour: "var(--color-success)",
                      },
                    ]}
                  />
                  <div className="mt-5 grid gap-3 sm:grid-cols-2">
                    <HoverTile
                      href="/app/tix/portfolio"
                      label={t.expiringSoon}
                      value={register.expiringSoon}
                      reveal={t.expiringNote}
                    />
                    <HoverTile
                      href="/app/tix/portfolio"
                      label={t.awaitingErasure}
                      value={register.awaitingErasure}
                      reveal={t.awaitingErasureNote}
                      tone={register.awaitingErasure > 0 ? "serious" : "plain"}
                    />
                  </div>
                </>
              )}
            </Card>

            <Card title={t.activityTitle} description={t.activityDescription}>
              {activity === null || activity.length === 0 ? (
                <EmptyState>{t.noActivity}</EmptyState>
              ) : (
                <>
                  <Sparkline
                    label={t.activityTitle}
                    months={activity.map((month) => ({
                      month: month.month,
                      value: month.declared,
                    }))}
                  />
                  <Link
                    href="/app/executive"
                    className="mt-4 inline-block text-sm font-semibold text-blue hover:underline"
                  >
                    {t.openExecutive} →
                  </Link>
                </>
              )}
            </Card>
          </div>

          <Card title={t.actionsTitle} description={t.actionsDescription}>
            <div className="mt-0 flex flex-wrap gap-3">
              {actions.map((action) => (
                <Link
                  key={action.href}
                  href={action.href}
                  className="rounded-lg border border-line px-4 py-3 text-sm font-bold text-navy transition hover:-translate-y-0.5 hover:border-blue hover:shadow-sm"
                >
                  {action.label} →
                </Link>
              ))}
            </div>
          </Card>
        </>
      )}
    </div>
  );
}

/**
 * Look a company up without leaving the dashboard.
 *
 * <p>The one thing somebody opens this platform to do, put where they land. It searches the
 * operator's own book — the same call the search screen makes, and not the registry, because a
 * lookup starting from subjects would let any participant enumerate what its competitors report.
 *
 * <p>Picking a result goes to the company's own file rather than to the 360° profile. The profile
 * asks the exchange, which charges an inquiry and needs a stated purpose, and a box on a dashboard
 * is exactly where somebody would spend one by accident.
 */
function LookupBar() {
  const messages = useMessages();
  const t = messages.dashboard.lookup;
  const router = useRouter();

  const [query, setQuery] = useState("");
  const [results, setResults] = useState<SearchResult[] | null>(null);
  const [busy, setBusy] = useState(false);

  async function run(text: string) {
    setBusy(true);
    try {
      setResults(await tixApi.search(text));
    } catch {
      setResults([]);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title={t.title} description={t.note}>
      <form
        className="flex flex-col gap-3 sm:flex-row"
        onSubmit={(event) => {
          event.preventDefault();
          void run(query.trim());
        }}
      >
        <input
          className={inputClass}
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder={t.placeholder}
          aria-label={t.title}
        />
        <Button type="submit" disabled={busy || query.trim().length < 3}>
          {busy ? messages.common.loading : t.action}
        </Button>
      </form>

      {results !== null && results.length === 0 && (
        <div className="mt-4">
          <EmptyState>{t.noResults}</EmptyState>
        </div>
      )}

      {results !== null && results.length > 0 && (
        <ul className="mt-4 flex flex-col gap-2">
          {results.slice(0, 5).map((result) => (
            <li key={result.subjectId}>
              <button
                type="button"
                onClick={() => router.push(`/app/subjects/${result.subjectId}`)}
                className="w-full rounded-lg border border-line px-4 py-3 text-left transition hover:-translate-y-0.5 hover:border-blue hover:shadow-sm"
              >
                <span className="block font-semibold text-navy">{result.name}</span>
                <span className="text-sm text-muted">
                  {interpolate(t.records, t.records, { count: String(result.recordCount) })}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}
