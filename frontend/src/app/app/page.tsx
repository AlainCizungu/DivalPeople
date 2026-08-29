"use client";

import { useEffect, useMemo, useState } from "react";
import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useLocale, useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import { monthLabel } from "@/i18n/month";
import type { Messages } from "@/i18n/messages";
import { useSession } from "@/auth/SessionProvider";
import {
  executiveApi,
  overviewApi,
  tixApi,
  type ExecutiveBriefing,
  type ExecutiveMonth,
  type Overview,
  type SearchResult,
} from "@/api/client";
import { NetworkStrip } from "@/components/dashboard/NetworkStrip";
import { Spotlight } from "@/components/dashboard/Spotlight";
import {
  CountUp,
  HoverTile,
  MiniSpark,
  Ring,
  Sparkline,
  Trend,
} from "@/components/visual/motion";
import {
  Button,
  Card,
  EmptyState,
  SectionHeading,
} from "@/components/ui";

/**
 * One colour per tier, and none of them from the severity palette.
 *
 * <p>Taken from the directory's seven, so a reader who learns that teal is the register down here
 * meets the same teal in the directory tile that opens it. The alternative — picking four fresh
 * colours for this page — would give DIP two colour systems that agree about nothing.
 *
 * <p>Green, amber and red are absent on purpose and the omission is the point of the whole
 * scheme: those three say something is wrong, and a section heading never is.
 */
const TIER = {
  ask: "#1f6feb",
  waiting: "#0b1f3a",
  book: "#0a7f8c",
  activity: "#5b4bd6",
} as const;

/**
 * Morning, afternoon or evening, by the reader's own clock.
 *
 * <p>The browser's clock and not the server's, deliberately. The server runs in UTC and DIP's
 * users are in Kinshasa and Lubumbashi; a greeting computed there would say good evening to
 * somebody having lunch. This is the one figure on the page that should not come from the
 * backend, because it is about the reader rather than about the data.
 *
 * <p>Boundaries at 12 and 18. There is no correct answer and any choice is wrong for somebody, so
 * this is the conventional one rather than a considered one.
 */
function greetingFor(now: Date, t: Messages["dashboard"]["greeting"]): string {
  const hour = now.getHours();
  if (hour < 12) return t.morning;
  if (hour < 18) return t.afternoon;
  return t.evening;
}

/**
 * The name somebody would answer to.
 *
 * <p>"Alain CIZUNGU" greeted in full reads like a letter from a bank. Taking the first
 * whitespace-separated token is right for the names this platform actually holds and wrong for
 * some it may meet — a two-part given name, a name written family-first. It degrades to the whole
 * string when there is nothing to split, which is the safe direction: greeting somebody by their
 * full name is stiff, and greeting them by the wrong half is worse.
 */
function firstNameOf(displayName: string): string {
  return displayName.trim().split(/\s+/)[0] ?? displayName;
}

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
 * <p><strong>It ends with what exists, not with what is happening.</strong> Everything above the
 * directory is a count, and a count is only legible to somebody who already knows the platform.
 * The seven areas of work were previously discoverable only by reading the left menu, where the
 * grouping is grey capitals and "Data import lives under Data management" is expressed as four
 * pixels of indentation. The directory is drawn from the same list the menu is drawn from, so the
 * two cannot come to disagree about what DIP contains.
 *
 * <p>It replaced a row of six identical grey buttons headed "Quick actions", which named screens
 * and said nothing about them, showed only what the signed-in roles permitted, and stopped at six.
 *
 * <p>The activity series comes from the executive briefing rather than a second endpoint built for
 * this page. It is the same thirteen months the executive screen draws, so two screens cannot
 * disagree about a month, and it is fetched separately so that a caller entitled to one and not
 * the other still gets a working page.
 *
 * <p><strong>Three tiers, and the gaps between them are wider than the gaps inside them.</strong>
 * The page was previously a column of boxes at one weight — a missed statutory deadline was drawn
 * the same size as a delivery somebody had not got round to — so it had to be read in full to be
 * read at all. Now: ask a question, then what is waiting, then the book, then the directory, each
 * under a heading with one accent colour and separated by more air than anything within it.
 *
 * <p><strong>Colour is spent only where something is wrong.</strong> Amber means a statutory
 * deadline falls this week; red means one has already passed, or records are sitting past their
 * retention date. Nothing else on this page is ever coloured — deliveries in particular used to
 * render amber merely for existing, which is how a palette stops meaning anything.
 */
export default function DashboardPage() {
  const messages = useMessages();
  const t = messages.dashboard;
  const { locale } = useLocale();
  const { profile } = useSession();
  const router = useRouter();

  const [overview, setOverview] = useState<Overview | null>(null);
  const [briefing, setBriefing] = useState<ExecutiveBriefing | null>(null);
  const [failed, setFailed] = useState(false);
  /**
   * When the figures on screen arrived.
   *
   * <p>Every count in this response is computed at request time, so the moment the fetch returned
   * is the moment the figures were true — which makes "last refreshed" an honest label rather
   * than an approximation. It is deliberately not {@code asOf}, which is a date and answers a
   * different question: which day the retention and deadline windows were measured against.
   *
   * <p>Its real job is a tab left open since yesterday. Counts do not visibly age, and a screen
   * that cannot say when it last spoke to the server is one somebody acts on hours late.
   */
  const [loadedAt, setLoadedAt] = useState<Date | null>(null);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const loaded = await overviewApi.load();
        if (!cancelled) {
          setOverview(loaded);
          setLoadedAt(new Date());
        }
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

  // Recomputed on every render, which is what makes the greeting correct for somebody who leaves
  // the tab open across noon. It costs a Date and nothing else.
  const now = new Date();
  const firstName = firstNameOf(
    profile?.name ?? profile?.preferredUsername ?? profile?.email ?? "",
  );

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
        headline: interpolate(
          t.spotlight.awaitingErasure,
          t.spotlight.awaitingErasure,
          {
            count: String(register.awaitingErasure),
          },
        ),
        action: t.spotlight.openPortfolio,
        href: "/app/tix/portfolio",
      });
    }
    if (
      deliveries &&
      deliveries.awaitingPublication + deliveries.awaitingValidation > 0
    ) {
      built.push({
        key: "deliveries",
        eyebrow: t.spotlight.deliveries,
        headline: interpolate(
          t.spotlight.awaitingDelivery,
          t.spotlight.awaitingDelivery,
          {
            count: String(
              deliveries.awaitingPublication + deliveries.awaitingValidation,
            ),
          },
        ),
        action: t.spotlight.openImports,
        href: "/app/imports",
      });
    }
    if (register && register.contested > 0) {
      built.push({
        key: "contested",
        eyebrow: t.spotlight.contested,
        headline: interpolate(
          t.spotlight.contestedRecords,
          t.spotlight.contestedRecords,
          {
            count: String(register.contested),
          },
        ),
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
      {/* The greeting lives here rather than in the top bar, and the reason is a cost rather
          than a preference: the organisation's name comes from the overview response, and the
          top bar is on every screen. Fetching it app-wide would make every page pay for the
          network aggregation this endpoint now does, to draw a line that only makes sense on the
          page somebody lands on after signing in. */}
      {/* Beside the greeting, not above it. Everything below this block is work waiting on
          somebody, and the one rule the images in this product follow is that none of them push a
          figure or a form further down the page. On a phone the photograph is not rendered at
          all — the greeting is three short lines there and a picture beside it would become a
          picture above it. */}
      <div className="mb-6 grid items-center gap-6 md:grid-cols-[1.6fr_1fr]">
        <div>
          <h1 className="text-3xl font-bold tracking-tight text-navy">
            {interpolate(
              greetingFor(now, t.greeting),
              greetingFor(now, t.greeting),
              {
                name: firstName,
              },
            )}
          </h1>
          <p className="mt-1 text-sm font-semibold text-muted">
            {messages.app.platform}
          </p>
          <p className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 text-sm text-muted">
            <span>
              {overview?.organisation
                ? interpolate(
                    t.greeting.organisation,
                    t.greeting.organisation,
                    {
                      name: overview.organisation,
                    },
                  )
                : loading
                  ? t.greeting.refreshing
                  : t.greeting.organisationUnknown}
            </span>
            {loadedAt && (
              <>
                <span aria-hidden="true">·</span>
                <span>
                  {interpolate(t.greeting.refreshed, t.greeting.refreshed, {
                    time: loadedAt.toLocaleTimeString(locale, {
                      hour: "2-digit",
                      minute: "2-digit",
                    }),
                  })}
                </span>
              </>
            )}
          </p>
        </div>

        <div className="hidden overflow-hidden rounded-xl md:block">
          <Image
            src="/overview-welcome.webp"
            alt=""
            width={1536}
            height={1024}
            className="h-auto w-full"
            sizes="380px"
            priority
          />
        </div>
      </div>

      {loading ? (
        <Card>
          <EmptyState>{messages.common.loading}</EmptyState>
        </Card>
      ) : (
        <Spotlight slides={slides} />
      )}

      {/* TIER ONE — the thing somebody opens this platform to do, and now the face of the page.

          ONE PANEL, TWO HALVES, rather than two cards beside each other. The pairing is the
          teaching: "is this company in OUR book?" is free and instant, "does anybody else report
          them?" reaches the network, costs an inquiry and is recorded. People conflate those two
          constantly. Drawing them as one object split down the middle says they are two answers to
          one question far better than a paragraph or a gap does, and the filled half says which of
          them costs something without anybody reading a word. */}
      <section className="mt-10">
        <div
          className="grid overflow-hidden rounded-2xl border border-line bg-white shadow-sm lg:grid-cols-[1.55fr_1fr]"
          style={{ borderTop: `3px solid ${TIER.ask}` }}
        >
          <LookupPanel />
          {roles.includes("TIX_INQUIRER") && <InquiryPanel />}
        </div>
      </section>

      {!loading && (
        <>
          {/* TIER TWO — what is waiting, and the only place on the page that spends colour. */}
          <section className="mt-16">
            <SectionHeading
              title={t.waitingTitle}
              note={t.sections.waitingNote}
              accent={TIER.waiting}
              action={
                <Link
                  href="/app/subject-requests"
                  className="text-sm font-bold text-blue hover:underline"
                >
                  {t.actions.openCases} →
                </Link>
              }
            />

            {/* The two statutory figures, at lead size. They are the only counts here that carry
                a legal consequence for being ignored: Article 214 makes a missed deadline grounds
                in itself for a complaint. Everything else in this section is work. */}
            <div className="grid gap-5 lg:grid-cols-2">
              <HoverTile
                size="lead"
                href="/app/subject-requests"
                label={t.overdue}
                value={rights?.overdue ?? null}
                reveal={t.overdueNote}
                action={t.actions.investigate}
                tone={(rights?.overdue ?? 0) > 0 ? "serious" : "plain"}
              />
              <HoverTile
                size="lead"
                href="/app/subject-requests"
                label={t.dueSoon}
                value={rights?.dueSoon ?? null}
                reveal={t.dueSoonNote}
                action={t.actions.openCases}
                tone={(rights?.dueSoon ?? 0) > 0 ? "warning" : "plain"}
              />
            </div>

            {/* Deliveries, deliberately quieter and deliberately never coloured. A file somebody
                uploaded and has not validated yet is the ordinary state of a working queue; it
                was rendering amber merely for being non-zero, which taught people that amber on
                this page means nothing in particular and cost the two figures above their
                loudness. */}
            <div className="mt-5 grid gap-4 sm:grid-cols-2">
              <HoverTile
                href="/app/imports"
                label={t.awaitingValidation}
                value={deliveries?.awaitingValidation ?? null}
                reveal={t.awaitingValidationNote}
              />
              <HoverTile
                href="/app/imports"
                label={t.awaitingPublication}
                value={deliveries?.awaitingPublication ?? null}
                reveal={t.awaitingPublicationNote}
              />
            </div>
          </section>

          {/* TIER THREE — the book. Counts rather than obligations: nothing here is money. */}
          <section className="mt-16">
            <SectionHeading
              title={t.sections.bookTitle}
              note={t.sections.bookNote}
              accent={TIER.book}
            />

            <div className="grid gap-6 lg:grid-cols-2">
              <Card
                title={t.registerTitle}
                description={t.registerDescription}
                accent={TIER.book}
                emphasis
                action={
                  <Link
                    href="/app/tix/records"
                    className="text-sm font-bold whitespace-nowrap text-blue hover:underline"
                  >
                    {t.actions.openRecords} →
                  </Link>
                }
              >
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
                        tone={
                          register.awaitingErasure > 0 ? "serious" : "plain"
                        }
                      />
                    </div>
                  </>
                )}
              </Card>

              <Card
                title={t.activityTitle}
                description={t.activityDescription}
                accent={TIER.activity}
                action={
                  <Link
                    href="/app/executive"
                    className="text-sm font-bold whitespace-nowrap text-blue hover:underline"
                  >
                    {t.openExecutive} →
                  </Link>
                }
              >
                <Activity months={activity} locale={locale} t={t} />
              </Card>
            </div>
          </section>
        </>
      )}

      {/* The exchange itself, after the caller's own book and before the directory. Deliberately
          not at the top: what is waiting on somebody this morning outranks how large the network
          is, however good the second one looks in a demonstration. */}
      {overview && <NetworkStrip network={overview.network} />}

      {/* The directory used to be tier five, here, and it has moved to /app/directory behind
          Help → Explore DIP.

          It was the wrong thing at the bottom of this page. This page answers "what needs me this
          morning"; a site map answers "what is in this product", which somebody asks twice in
          their first week and rarely again — and it sat below every figure, so the front door
          always ended in a list of links rather than in the work. */}
    </div>
  );
}

/**
 * Thirteen months, and what the last complete one did.
 *
 * <p><strong>The comparison skips the current month, and that is the whole reason this is a
 * component rather than three lines inline.</strong> The series ends on the month we are standing
 * in, which is partial by definition — on the 3rd it holds three days of work. Comparing it to a
 * full month would draw a collapse every month and a recovery every month, and the arrow would be
 * measuring the calendar rather than the operator. So the trend compares the last two *complete*
 * months and the caption names them both, which means a reader can see which pair was used instead
 * of assuming it was the sensible one.
 *
 * <p>The partial month is still in the chart. Removing it would hide work that has actually
 * happened; it is only excluded from the arithmetic, and the line beneath says so.
 *
 * <p>Inquiries are shown beside declarations because the overview never showed them and they are
 * the other half of what an operator does here — one is what you tell the exchange, the other is
 * what you ask it. Neither arrow is coloured: see {@link Trend}.
 */
function Activity({
  months,
  locale,
  t,
}: {
  months: ExecutiveMonth[] | null;
  locale: string;
  t: ReturnType<typeof useMessages>["dashboard"];
}) {
  if (months === null || months.length === 0) {
    return <EmptyState>{t.noActivity}</EmptyState>;
  }

  const current = months[months.length - 1];
  const complete = months.slice(0, -1);
  const latest = complete[complete.length - 1];
  const prior = complete[complete.length - 2];

  // Two complete months are needed before anything can be compared to anything. A new operator in
  // its first weeks gets the chart and no arrows, rather than an arrow measured against a month
  // that does not exist.
  const comparable = latest !== undefined && prior !== undefined;
  const caption = comparable
    ? interpolate(t.trend.caption, t.trend.caption, {
        to: monthLabel(latest.month, locale, "long"),
        from: monthLabel(prior.month, locale, "long"),
      })
    : "";

  return (
    <>
      {comparable && (
        <div className="mb-5 grid gap-4 sm:grid-cols-2">
          <Figure
            label={t.activityDeclared}
            value={latest.declared}
            previous={prior.declared}
            caption={caption}
            series={complete.map((month) => month.declared)}
            colour="var(--color-blue)"
          />
          <Figure
            label={t.activityInquiries}
            value={latest.inquiries}
            previous={prior.inquiries}
            caption={caption}
            series={complete.map((month) => month.inquiries)}
            colour="#5b4bd6"
          />
        </div>
      )}

      <Sparkline
        label={t.activityTitle}
        months={months.map((month) => ({
          month: month.month,
          value: month.declared,
        }))}
      />

      {current && (
        <p className="mt-3 text-xs text-muted">
          {interpolate(t.trend.partial, t.trend.partial, {
            current: monthLabel(current.month, locale, "long"),
          })}
        </p>
      )}
    </>
  );
}

/** One series: its last complete month, which way it moved, and its shape. */
function Figure({
  label,
  value,
  previous,
  caption,
  series,
  colour,
}: {
  label: string;
  value: number;
  previous: number;
  caption: string;
  series: number[];
  colour: string;
}) {
  return (
    <div className="rounded-lg border border-line bg-soft/60 p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <p className="text-xs text-muted">{label}</p>
          <p className="mt-0.5 text-4xl font-bold text-navy">
            <CountUp value={value} />
          </p>
        </div>
        <MiniSpark values={series} colour={colour} />
      </div>
      <div className="mt-2">
        <Trend previous={previous} current={value} caption={caption} />
      </div>
    </div>
  );
}

/**
 * The way through to the exchange.
 *
 * <p>A card rather than another line in the quick-actions list, because the inquiry is the thing
 * this platform exists to make possible and it was reachable only from a row of identical grey
 * buttons at the bottom of the page.
 *
 * <p>It says what it costs. An inquiry charges the hourly allowance and writes an audit row with
 * the purpose typed against it, and somebody arriving from a dashboard tile should know that
 * before they click rather than when the form asks them why.
 *
 * <p><strong>Solid navy, and it is now the only dark thing on the page.</strong> It was a gradient
 * once, competing with a second dark panel at the top, and neither read as the loudest thing while
 * the figures between them — the page's actual content — sat in the trough. That panel is gone, so
 * this one can be filled without competing with anything: it is the single dark shape on a white
 * page, which is as loud as an element gets without shouting.
 *
 * <p>It is half of an object rather than a card beside one. No border and no gap between it and
 * the lookup, because they are two answers to the same question and a gap would make them a list
 * of two unrelated things.
 */
function InquiryPanel() {
  const t = useMessages().dashboard.inquiry;
  return (
    <Link
      href="/app/tix"
      // The filled half. Not a card beside another card — the right-hand side of one object, with
      // no border between them and no gap, so the two read as a pair rather than as a list of two.
      //
      // Navy rather than anything from the severity palette. Nothing here is wrong; it is the
      // thing that costs an inquiry, and on this platform amber and red mean a deadline has
      // passed.
      className="group flex flex-col justify-between bg-navy p-7 transition hover:bg-navy/95 md:p-9"
    >
      <div>
        <p className="mb-3 text-xs font-semibold tracking-[0.18em] text-blue uppercase">
          {t.eyebrow}
        </p>
        <h2 className="mb-3 text-2xl font-bold tracking-tight text-white">
          {t.title}
        </h2>
        <p className="text-base leading-relaxed text-white/70">{t.note}</p>
      </div>
      <span className="mt-8 inline-flex items-center gap-2.5 self-start rounded-lg bg-white px-8 py-4 text-lg font-bold text-navy shadow-sm transition group-hover:bg-white/90">
        {t.action}
        <span aria-hidden="true" className="transition group-hover:translate-x-1">
          →
        </span>
      </span>
    </Link>
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
function LookupPanel() {
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
    <div className="p-7 md:p-9">
      <p className="mb-3 text-xs font-semibold tracking-[0.18em] uppercase" style={{ color: TIER.ask }}>
        {t.eyebrow}
      </p>
      <h2 className="mb-2 text-2xl font-bold tracking-tight text-navy">
        {t.title}
      </h2>
      <p className="mb-6 max-w-lg text-base leading-relaxed text-muted">
        {t.note}
      </p>

      {/* One field, and it is the largest control on the page.
          It was a 14px input in a card header, the same size as a filter on a table three screens
          away — for the thing somebody signs in to do. Size here is not decoration: it is the
          page saying what it is for. */}
      <form
        className="flex flex-col gap-3 sm:flex-row"
        onSubmit={(event) => {
          event.preventDefault();
          void run(query.trim());
        }}
      >
        <div className="relative flex-1">
          <svg
            aria-hidden="true"
            viewBox="0 0 24 24"
            className="pointer-events-none absolute top-1/2 left-4 h-5 w-5 -translate-y-1/2 text-muted"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
          >
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20l-3.5-3.5" />
          </svg>
          <input
            className="w-full rounded-lg border border-line bg-white py-4 pr-4 pl-12 text-base text-ink transition focus:border-blue focus:ring-2 focus:ring-blue/30 focus:outline-none"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder={t.placeholder}
            aria-label={t.title}
          />
        </div>
        <Button
          type="submit"
          size="lead"
          disabled={busy || query.trim().length < 3}
        >
          {busy ? messages.common.loading : t.action}
        </Button>
      </form>

      {results !== null && results.length === 0 && (
        <div className="mt-5">
          <EmptyState>{t.noResults}</EmptyState>
        </div>
      )}

      {results !== null && results.length > 0 && (
        <ul className="mt-5 flex flex-col gap-2">
          {results.slice(0, 5).map((result) => (
            <li key={result.subjectId}>
              <button
                type="button"
                onClick={() => router.push(`/app/subjects/${result.subjectId}`)}
                className="w-full rounded-lg border border-line px-4 py-3.5 text-left transition hover:-translate-y-0.5 hover:border-blue hover:shadow-sm"
              >
                <span className="block font-semibold text-navy">
                  {result.name}
                </span>
                <span className="text-sm text-muted">
                  {interpolate(t.records, t.records, {
                    count: String(result.recordCount),
                  })}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
