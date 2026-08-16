"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { useSession } from "@/auth/SessionProvider";
import { notificationsApi } from "@/api/client";
import { useMessages } from "@/i18n/LocaleProvider";
import { BrandMark } from "./BrandMark";
import { LanguageSwitcher } from "./LanguageSwitcher";
import { AskDipLauncher } from "./AskDipLauncher";

/**
 * Application shell: left navigation, top utility bar, main content area.
 *
 * <p>The navigation used to be a flat list of the eleven screens that happened to exist, in the
 * order they were built. It read as a changelog. This is the platform's actual shape — eight
 * areas of work, and an item sits in the area it belongs to whether or not it has been built.
 *
 * <p><strong>The unbuilt items are shown, and shown as unbuilt.</strong> That is a deliberate
 * reversal of the note that used to stand here, which said a nav full of links to nothing makes
 * it impossible to tell a missing feature from a bug. It does — if they look like links. Marked
 * plainly and not clickable, they do the opposite: somebody looking for fraud monitoring finds
 * out in one second that it is coming rather than hunting three menus for it, and nobody can
 * mistake it for something broken. The rule the old note was really protecting is intact: no
 * entry here ever navigates to a page that is not there.
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const messages = useMessages();
  const pathname = usePathname();
  const { profile, signOut } = useSession();

  const [unreadCount, setUnreadCount] = useState(0);
  /**
   * Only sessions that belong to an operator have notifications to count.
   *
   * <p>A platform administrator runs the network and has no tenant of its own, so the
   * tenant-scoped endpoint refuses it — correctly. The badge swallowed the refusal and the user
   * saw nothing, but it asked again every sixty seconds and filled the server log with denials
   * that looked like an authorisation problem and were a client asking a question that does not
   * apply to it. Not asking is the fix; suppressing the log line would have been the bug.
   */
  const hasNotifications = profile?.tenantId != null;

  // Polled rather than pushed. A websocket for a number that changes a few times a day is a
  // connection to keep alive, reconnect and authorise for very little gain.
  useEffect(() => {
    if (!hasNotifications) return;

    let cancelled = false;
    const refresh = async () => {
      try {
        const { unread } = await notificationsApi.unreadCount();
        if (!cancelled) setUnreadCount(unread);
      } catch {
        // A failed badge refresh must never interrupt whatever the user is doing.
      }
    };

    void refresh();
    const timer = setInterval(() => void refresh(), 60_000);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [hasNotifications, pathname]);

  const displayName =
    profile?.name ?? profile?.preferredUsername ?? profile?.email ?? "";
  // Read from the session on the server and enforced there too; shown so it is obvious which
  // operator the current session is acting as.
  const tenantId = profile?.tenantId;
  const t = messages.nav;

  const isPlatformAdmin = profile?.roles.includes("PLATFORM_ADMIN") ?? false;

  /**
   * The platform's shape.
   *
   * <p>An item with no href is designed and not built. Two items may share an href — Exposure
   * and Portfolio intelligence are one screen today, as are Subject requests and Disputes &
   * corrections — and that is recorded here rather than hidden, because the day either one grows
   * its own screen this is the list that has to change.
   */
  const groups: NavGroup[] = [
    {
      heading: t.groupIntelligence,
      items: [
        { href: "/app", label: t.home },
        { href: "/app/search", label: t.search },
        { href: "/app/executive", label: t.executive },
        { href: "/app/tix/portfolio", label: t.portfolioIntelligence },
      ],
    },
    {
      heading: t.groupSubjects,
      items: [
        // Two entries, one screen behind them, differing in a query parameter and in what an
        // empty list means. Individuals is empty on the data that exists and says why — an empty
        // list that names what would fill it makes the ask concrete in a way a roadmap does not.
        { href: "/app/businesses", label: t.businesses },
        { href: "/app/individuals", label: t.individuals },
        { href: "/app/tix/records", label: t.records },
        { href: "/app/tix", label: t.inquiries },
        { href: "/app/tix/declare", label: t.declare },
        { href: "/app/subject-requests", label: t.subjectRequests },
      ],
    },
    {
      heading: t.groupRisk,
      items: [
        // Where risk is actually assessed today: submit an identifier and the DIP Risk Indicator
        // comes back with the verdict. It will grow its own screen — a ranked view of an
        // operator's own book — and until it does, pointing this at the assessment that exists
        // is truer than marking it unbuilt.
        { href: "/app/tix", label: t.riskIntelligence },
        { href: "/app/tix/portfolio", label: t.portfolio },
        // Compliance officer or tenant administrator only, and shown to everybody like entity
        // resolution: the page explains that reading colleagues' behaviour is a supervisory
        // function rather than leaving a built screen looking unbuilt.
        { href: "/app/anomalies", label: t.fraud },
        // A watch is an inquiry asked on a schedule, so the entry sits beside the risk screens
        // rather than under Subjects: what it produces is an answer about exposure, not a record.
        { href: "/app/watchlists", label: t.watchlists },
      ],
    },
    {
      heading: t.groupNetwork,
      items: [
        { label: t.tix },
        // Platform administration. Hidden rather than shown-and-refused, because a menu item
        // that always 403s teaches people to ignore refusals.
        ...(isPlatformAdmin
          ? [{ href: "/app/participants", label: t.participants }]
          : []),
      ],
    },
    {
      heading: t.groupData,
      items: [
        { href: "/app/imports", label: t.imports },
        // Data sources and Data quality used to sit here and next door. Neither was a screen:
        // sources are a card on this one, and quality is the column profile inside a delivery,
        // which cannot be reached without a batch to ask about. Two menu entries for one screen
        // and one for a thing that is not a screen at all — both gone rather than promised.
        //
        // Entity resolution is shown to everybody, unlike Participants, and the two are treated
        // differently on purpose. Participants is administration: an operator has no reason to
        // want it and hiding it costs them nothing. Entity resolution is the product — a
        // participant asking where it went is asking a fair question, and answering with a "Soon"
        // chip tells them a built feature does not exist yet. That is a worse lie than a refusal.
        //
        // So the page opens for anybody and says whose work this is: the registry resolves,
        // because a case puts one operator's record beside another's with both names visible.
        // The API still refuses everybody else, and the screen shows that refusal rather than an
        // empty queue.
        { href: "/app/resolution", label: t.entityResolution },
      ],
    },
    {
      heading: t.groupAi,
      items: [{ href: "/app/analyst", label: t.aiAnalyst }],
    },
    {
      heading: t.groupGovernance,
      items: [
        { href: "/app/audit", label: t.audit },
        // Reachable by anybody signed in, not only an administrator. Its more useful half is
        // the answer to "why can I not open that screen", and refusing that question to the
        // people who have it would be the wrong way round.
        { href: "/app/access", label: t.access },
        { href: "/app/subject-requests", label: t.disputes },
      ],
    },
    {
      heading: t.groupSystem,
      items: [
        { href: "/app/notifications", label: t.notifications, badge: unreadCount },
        { href: "/app/organization", label: t.organization },
        // The last System entry to get a screen, and the one with the most already behind it:
        // every value it shows existed in a yaml file that only a deployer could read.
        { href: "/app/settings", label: t.settings },
      ],
    },
  ];

  const current = activeHref(
    pathname,
    groups.flatMap((group) => group.items.map((item) => item.href)),
  );

  // Two entries can share a route, so the highlight goes to the first of them and no other.
  // Lighting both would read as a rendering fault rather than as the deliberate duplication it
  // is, and the reader has no way to tell those apart from the outside.
  const currentKey = keyOfFirst(groups, current);

  return (
    <div className="flex min-h-screen">
      <nav
        aria-label={messages.app.name}
        className="hidden w-64 shrink-0 flex-col border-r border-line bg-white md:flex"
      >
        <Link
          href="/"
          className="flex items-center gap-2 border-b border-line px-5 py-4"
        >
          <BrandMark size={26} />
          <span className="text-lg font-bold text-navy">
            {messages.app.name}
          </span>
        </Link>

        {/* Twenty-five entries do not fit on a laptop, so the list scrolls and the brand and
            the footer stay put. */}
        <div className="flex-1 overflow-y-auto p-3">
          {groups.map((group) => {
            // A group can empty out entirely — Network does, for anybody who is not a platform
            // administrator, once its unbuilt items are gone. Printing the heading over nothing
            // would be worse than printing neither.
            if (group.items.length === 0) return null;
            return (
              <div key={group.heading} className="mb-4 last:mb-0">
                <p className="px-3 pb-1 text-[11px] font-bold uppercase tracking-wider text-muted">
                  {group.heading}
                </p>
                <ul className="space-y-0.5">
                  {group.items.map((item) => {
                    const key = itemKey(group, item);
                    const active = key === currentKey;
                    return (
                      <li key={key}>
                        {item.href ? (
                          <Link
                            href={item.href}
                            aria-current={active ? "page" : undefined}
                            className={
                              active
                                ? "flex items-center justify-between gap-2 rounded border-l-2 border-blue bg-soft px-3 py-2 text-sm font-semibold text-blue"
                                : "flex items-center justify-between gap-2 rounded px-3 py-2 text-sm text-ink transition hover:bg-soft"
                            }
                          >
                            <span>{item.label}</span>
                            {(item.badge ?? 0) > 0 && (
                              <span className="rounded-full bg-blue px-2 py-0.5 text-xs font-bold text-white tabular-nums">
                                {item.badge}
                              </span>
                            )}
                          </Link>
                        ) : (
                          <span
                            title={t.soonTitle}
                            className="flex cursor-default items-center justify-between gap-2 rounded px-3 py-2 text-sm text-muted"
                          >
                            <span>{item.label}</span>
                            <span className="rounded border border-line px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide text-muted">
                              {t.soon}
                            </span>
                          </span>
                        )}
                      </li>
                    );
                  })}
                </ul>
              </div>
            );
          })}
        </div>

        <p className="border-t border-line px-5 py-3 text-xs text-muted">
          {messages.app.platform}
        </p>
      </nav>

      <div className="flex min-w-0 flex-1 flex-col">
        <header className="flex items-center justify-between gap-4 border-b border-line bg-white px-6 py-3">
          <div className="flex items-center gap-2 md:hidden">
            <BrandMark size={22} />
            <span className="font-bold text-navy">{messages.app.name}</span>
          </div>

          <p className="hidden text-sm text-muted md:block">
            {messages.common.tenant}:{" "}
            <span className="font-semibold text-ink">{displayName}</span>
            {tenantId && (
              <span className="ml-2 rounded bg-soft px-2 py-0.5 font-mono text-xs">
                {tenantId.slice(0, 8)}
              </span>
            )}
          </p>

          <div className="flex items-center gap-3">
            <LanguageSwitcher />
            <button
              type="button"
              onClick={() => void signOut()}
              className="rounded px-3 py-1.5 text-sm font-semibold text-ink transition hover:text-blue"
            >
              {messages.common.signOut}
            </button>
          </div>
        </header>

        <main className="flex-1 p-6">{children}</main>

        {/* Every screen, bottom right, closed until asked for. The questions this answers are the
            ones somebody thinks of while looking at something else. */}
        <AskDipLauncher />
      </div>
    </div>
  );
}

/** An item with no href is designed and not built, and the nav says so rather than linking. */
type NavItem = { href?: string; label: string; badge?: number };

type NavGroup = { heading: string; items: NavItem[] };

/** Unique per entry rather than per route, because a route can appear twice. */
function itemKey(group: NavGroup, item: NavItem): string {
  return `${group.heading}/${item.label}`;
}

/** The first entry pointing at this route, reading down the menu as somebody reads it. */
function keyOfFirst(
  groups: NavGroup[],
  href: string | undefined,
): string | undefined {
  if (!href) return undefined;
  for (const group of groups) {
    for (const item of group.items) {
      if (item.href === href) return itemKey(group, item);
    }
  }
  return undefined;
}

/**
 * Which entry the current URL belongs to.
 *
 * <p>Exact matching left every nested page with nothing selected: open a delivery and the whole
 * menu went dark, so the one screen where somebody is deepest in a task was the one screen that
 * stopped telling them where they were.
 *
 * <p>Longest prefix rather than first match, and the difference is the whole function. Both
 * {@code /app/tix} and {@code /app/tix/declare} are prefixes of the declaration page, and only
 * the longer one is the right answer. {@code /app} is a prefix of everything, which is why the
 * boundary check matters: {@code /app/imports} must not be treated as living under it by
 * accident of string length alone.
 */
function activeHref(
  pathname: string,
  hrefs: (string | undefined)[],
): string | undefined {
  let best: string | undefined;
  for (const href of hrefs) {
    if (!href) continue;
    const matches =
      pathname === href ||
      // The slash is what stops /app/tix matching a future /app/tixture.
      pathname.startsWith(href.endsWith("/") ? href : `${href}/`);
    if (matches && (best === undefined || href.length > best.length)) {
      best = href;
    }
  }
  return best;
}
