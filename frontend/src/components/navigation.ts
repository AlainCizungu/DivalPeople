import type { Messages } from "@/i18n/messages";

/**
 * The platform's shape, in one place.
 *
 * <p>This list used to live inside {@link AppShell}, which was fine while the menu was the only
 * thing that needed it. It is not: the overview page now draws the same seven groups as a
 * directory, and a second hand-written copy of "what DIP contains" would be wrong within a month —
 * the first screen added to Risk would appear in the menu, not on the front door, and nobody would
 * notice until somebody went looking for it.
 *
 * <p><strong>Every item carries a stable id.</strong> The headings and labels are translated, so
 * they cannot be matched on: a colour keyed off "Data management" is a colour that disappears in
 * French. The id is the same string as the catalogue key it renders, which means adding an entry
 * here and forgetting its label is a compile error rather than a blank tile.
 *
 * <p><strong>Two items may share an href.</strong> Exposure and Portfolio intelligence are one
 * screen today, as are Subject requests and Disputes & corrections. That is recorded rather than
 * hidden, because the day either grows its own screen this is the list that has to change.
 *
 * <p>An item with no href is designed and not built. None are today — the last one, the TIX chip,
 * was removed — but the shape is kept because the rule it enforces is the important part: no entry
 * here ever navigates to a page that is not there.
 */

/** An item with no href is designed and not built, and the menu says so rather than linking. */
export type NavItem = {
  /** Stable across languages. Same string as the {@code nav} catalogue key it renders. */
  id: ItemId;
  href?: string;
  label: string;
  badge?: number;
};

export type NavGroup = {
  /** Stable across languages; what colours and icons are keyed off. */
  id: GroupId;
  heading: string;
  items: NavItem[];
};

/**
 * Every screen, named once.
 *
 * <p>Spelled out rather than derived from {@code keyof Messages["nav"]}, which would also admit
 * the group headings and the "Soon" chip. Anything consuming this list — the menu, the front
 * door's directory — can then be made to fail compilation when an entry is added and its side of
 * the story is not, which is the only reliable way two renderings of one list stay in step.
 */
export type ItemId =
  | "home"
  | "search"
  | "executive"
  | "portfolioIntelligence"
  | "records"
  | "inquiries"
  | "declare"
  | "subjectRequests"
  | "riskIntelligence"
  | "portfolio"
  | "fraud"
  | "watchlists"
  | "monitoring"
  | "participants"
  | "imports"
  | "entityResolution"
  | "audit"
  | "access"
  | "disputes"
  | "notifications"
  | "organization"
  | "settings";

export type GroupId =
  | "intelligence"
  | "subjects"
  | "risk"
  | "network"
  | "data"
  | "governance"
  | "system";

export type NavAudience = {
  isPlatformAdmin: boolean;
  /** Shown on the Notifications entry. Zero renders nothing. */
  unreadCount: number;
};

export function buildNavigation(
  t: Messages["nav"],
  { isPlatformAdmin, unreadCount }: NavAudience,
): NavGroup[] {
  return [
    {
      id: "intelligence",
      heading: t.groupIntelligence,
      items: [
        { id: "home", href: "/app", label: t.home },
        { id: "search", href: "/app/search", label: t.search },
        { id: "executive", href: "/app/executive", label: t.executive },
        {
          id: "portfolioIntelligence",
          href: "/app/tix/portfolio",
          label: t.portfolioIntelligence,
        },
      ],
    },
    {
      id: "subjects",
      heading: t.groupSubjects,
      items: [
        // Businesses and Individuals used to be two entries here, over one component that
        // differed in a query parameter. That made "is this company in our book?" and "is this
        // person in our book?" two places to look for one question, and neither of them named
        // the amount owed. Records answers both, with the kind as a filter.
        { id: "records", href: "/app/tix/records", label: t.records },
        { id: "inquiries", href: "/app/tix", label: t.inquiries },
        { id: "declare", href: "/app/tix/declare", label: t.declare },
        { id: "subjectRequests", href: "/app/subject-requests", label: t.subjectRequests },
      ],
    },
    {
      id: "risk",
      heading: t.groupRisk,
      items: [
        // Where risk is actually assessed today: submit an identifier and the DIP Risk Indicator
        // comes back with the verdict. It will grow its own screen — a ranked view of an
        // operator's own book — and until it does, pointing this at the assessment that exists
        // is truer than marking it unbuilt.
        { id: "riskIntelligence", href: "/app/tix", label: t.riskIntelligence },
        { id: "portfolio", href: "/app/tix/portfolio", label: t.portfolio },
        // Compliance officer or tenant administrator only, and shown to everybody like entity
        // resolution: the page explains that reading colleagues' behaviour is a supervisory
        // function rather than leaving a built screen looking unbuilt.
        { id: "fraud", href: "/app/anomalies", label: t.fraud },
        // A watch is an inquiry asked on a schedule, so the entry sits beside the risk screens
        // rather than under Subjects: what it produces is an answer about exposure, not a record.
        { id: "watchlists", href: "/app/watchlists", label: t.watchlists },
        // Beside the watchlist and not inside it. One screen answers "who do I care about" and
        // the other "what happened to them", and they are worked by different people on
        // different days.
        { id: "monitoring", href: "/app/monitoring", label: t.monitoring },
      ],
    },
    {
      id: "network",
      heading: t.groupNetwork,
      items: [
        // "TIX — Telecom intelligence" was here as a Soon chip, and it is gone. Everything the
        // exchange does today already happens under Intelligence, Subjects and Risk: an operator
        // declares, enquires, watches and is monitored, and all of it is the network working. A
        // separate entry promising telecom intelligence described a thing that is not a separate
        // thing, and a chip that never becomes a screen is a promise the menu keeps making.
        //
        // The group now holds only the platform administrator's view and disappears for
        // everybody else, which both renderers already handle. Hidden rather than
        // shown-and-refused, because a menu item that always 403s teaches people to ignore
        // refusals.
        ...(isPlatformAdmin
          ? [{ id: "participants" as const, href: "/app/participants", label: t.participants }]
          : []),
      ],
    },
    {
      id: "data",
      heading: t.groupData,
      items: [
        { id: "imports", href: "/app/imports", label: t.imports },
        // Data sources and Data quality used to sit here and next door. Neither was a screen:
        // sources are a card on this one, and quality is the column profile inside a delivery,
        // which cannot be reached without a batch to ask about. Two menu entries for one screen
        // and one for a thing that is not a screen at all — both gone rather than promised.
        //
        // Entity resolution is shown to everybody, unlike Participants, and the two are treated
        // differently on purpose. Participants is administration: an operator has no reason to
        // want it and hiding it costs them nothing. Entity resolution is the product — a
        // participant asking where it went is asking a fair question, and answering with a
        // "Soon" chip tells them a built feature does not exist yet. That is a worse lie than a
        // refusal. So the page opens for anybody and says whose work this is; the API still
        // refuses everybody else, and the screen shows that refusal rather than an empty queue.
        { id: "entityResolution", href: "/app/resolution", label: t.entityResolution },
      ],
    },
    {
      id: "governance",
      heading: t.groupGovernance,
      items: [
        { id: "audit", href: "/app/audit", label: t.audit },
        // Reachable by anybody signed in, not only an administrator. Its more useful half is
        // the answer to "why can I not open that screen", and refusing that question to the
        // people who have it would be the wrong way round.
        { id: "access", href: "/app/access", label: t.access },
        { id: "disputes", href: "/app/subject-requests", label: t.disputes },
      ],
    },
    {
      id: "system",
      heading: t.groupSystem,
      items: [
        {
          id: "notifications",
          href: "/app/notifications",
          label: t.notifications,
          badge: unreadCount,
        },
        { id: "organization", href: "/app/organization", label: t.organization },
        // The last System entry to get a screen, and the one with the most already behind it:
        // every value it shows existed in a yaml file that only a deployer could read.
        { id: "settings", href: "/app/settings", label: t.settings },
      ],
    },
  ];
}

/** Unique per entry rather than per route, because a route can appear twice. */
export function itemKey(group: NavGroup, item: NavItem): string {
  return `${group.id}/${item.id}`;
}

/** The first entry pointing at this route, reading down the menu as somebody reads it. */
export function keyOfFirst(
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
export function activeHref(
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
