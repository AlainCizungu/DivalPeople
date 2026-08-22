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
import {
  activeHref,
  buildNavigation,
  GROUP_IDS,
  itemKey,
  keyOfFirst,
  type GroupId,
} from "./navigation";

/**
 * Where the open headings are remembered.
 *
 * <p>Per browser, not per account, and that is the right scope: it is a preference about a menu,
 * not a fact about a person. Nothing in it is worth a round trip, and nothing in it is worth
 * anything to anybody who reads it.
 */
const OPEN_GROUPS_KEY = "dip.nav.open";

const KNOWN_GROUPS: readonly GroupId[] = GROUP_IDS;

/**
 * Application shell: left navigation, top utility bar, main content area.
 *
 * <p>The navigation used to be a flat list of the eleven screens that happened to exist, in the
 * order they were built. It read as a changelog. This is the platform's actual shape — eight
 * areas of work, and an item sits in the area it belongs to whether or not it has been built.
 *
 * <p><strong>The headings collapse.</strong> Twenty-one entries under seven headings is a menu
 * that scrolls on a laptop, and a menu that scrolls is one where the thing you want is reliably
 * below the fold. Closed, it is seven rows. The group you are in opens on every navigation — you
 * can shut it again, and it will reopen the next time you move, because hiding the highlight that
 * says where you are defeats the point of the menu.
 *
 * <p>What was open is remembered per browser, and a collapsed heading carries the sum of its
 * items' badges so that tidying the menu cannot hide the unread count that is most of the reason
 * anybody opens System.
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

  const groups = buildNavigation(t, { isPlatformAdmin, unreadCount });

  const current = activeHref(
    pathname,
    groups.flatMap((group) => group.items.map((item) => item.href)),
  );

  // Two entries can share a route, so the highlight goes to the first of them and no other.
  // Lighting both would read as a rendering fault rather than as the deliberate duplication it
  // is, and the reader has no way to tell those apart from the outside.
  const currentKey = keyOfFirst(groups, current);

  /**
   * Which heading the page you are on lives under.
   *
   * <p>Found by the entry, not by the route, because two groups can share a route: Inquiries and
   * Risk intelligence are both {@code /app/tix}. The highlight already resolves that to one
   * entry, and the open group has to be the same one or the menu would open a heading whose
   * contents are not lit.
   */
  const currentGroup = groups.find((group) =>
    group.items.some((item) => itemKey(group, item) === currentKey),
  )?.id;

  const [open, setOpen] = useState<GroupId[]>(currentGroup ? [currentGroup] : []);
  const [restored, setRestored] = useState(false);

  /**
   * What was open last time.
   *
   * <p>Read after mount rather than during the first render. {@code localStorage} does not exist
   * on the server, and a component whose first client render disagrees with the markup it was
   * given is a hydration error — so the server and the first client paint both show exactly one
   * group open, the one you are in, and the remembered set arrives a frame later.
   *
   * <p>Stored ids are filtered against the groups that exist. A heading removed in a later
   * version leaves its id behind in every browser that ever saw it, and nothing else here would
   * notice.
   */
  useEffect(() => {
    let stored: GroupId[] = [];
    try {
      const raw = window.localStorage.getItem(OPEN_GROUPS_KEY);
      if (raw) {
        const parsed: unknown = JSON.parse(raw);
        if (Array.isArray(parsed)) {
          stored = parsed.filter((value): value is GroupId =>
            KNOWN_GROUPS.includes(value as GroupId),
          );
        }
      }
    } catch {
      // A browser with storage disabled, or a key somebody hand-edited. Neither is a reason to
      // fail to draw the navigation.
    }
    setOpen((previous) => Array.from(new Set([...stored, ...previous])));
    setRestored(true);
    // Once, on mount. Re-reading storage on every navigation would undo a collapse the moment
    // somebody clicked anything.
  }, []);

  /**
   * The group you are in opens, every time the route changes.
   *
   * <p>You may collapse it again afterwards and that sticks until you navigate. The alternative —
   * refusing to reopen it — hides the highlight that tells you where you are, which is the one
   * thing the menu is for.
   */
  useEffect(() => {
    if (!currentGroup) return;
    setOpen((previous) =>
      previous.includes(currentGroup) ? previous : [...previous, currentGroup],
    );
  }, [currentGroup]);

  useEffect(() => {
    // Guarded on restored, so the single-group set that exists for one frame before the read
    // completes is never the thing that gets written back over it.
    if (!restored) return;
    try {
      window.localStorage.setItem(OPEN_GROUPS_KEY, JSON.stringify(open));
    } catch {
      // Nothing is lost that was not already only a convenience.
    }
  }, [open, restored]);

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

        {/* Still scrolls, because every heading can be opened at once and somebody will. The
            brand and the footer stay put. */}
        <div className="flex-1 overflow-y-auto p-3">
          {groups.map((group) => {
            // A group can empty out entirely — Network does, for anybody who is not a platform
            // administrator, once its unbuilt items are gone. Printing the heading over nothing
            // would be worse than printing neither.
            if (group.items.length === 0) return null;
            const expanded = open.includes(group.id);
            // Carried up to the heading so a collapsed group cannot swallow the number. The
            // notification badge is most of the reason anybody opens System, and a menu that
            // hides it while claiming to be tidier has lost the argument for collapsing at all.
            const hiddenBadges = expanded
              ? 0
              : group.items.reduce((sum, item) => sum + (item.badge ?? 0), 0);
            return (
              <div key={group.id} className="mb-2 last:mb-0">
                <button
                  type="button"
                  onClick={() =>
                    setOpen((previous) =>
                      previous.includes(group.id)
                        ? previous.filter((id) => id !== group.id)
                        : [...previous, group.id],
                    )
                  }
                  aria-expanded={expanded}
                  aria-controls={`nav-${group.id}`}
                  className="flex w-full items-center gap-1.5 rounded px-3 py-1.5 text-left text-[11px] font-bold tracking-wider text-muted uppercase transition hover:bg-soft hover:text-ink"
                >
                  <svg
                    aria-hidden="true"
                    width="10"
                    height="10"
                    viewBox="0 0 10 10"
                    className={`shrink-0 transition-transform motion-reduce:transition-none ${
                      expanded ? "rotate-90" : ""
                    }`}
                  >
                    <path
                      d="M3 1.5L7 5l-4 3.5"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="1.6"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                  <span>{group.heading}</span>
                  {hiddenBadges > 0 && (
                    <span className="ml-auto rounded-full bg-blue px-2 py-0.5 text-[10px] font-bold text-white tabular-nums">
                      {hiddenBadges}
                    </span>
                  )}
                </button>
                <ul id={`nav-${group.id}`} hidden={!expanded} className="space-y-0.5 pt-0.5">
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
                            // Dormant, not broken. No navigation item is href-less today — the
                            // last one, the TIX chip, was removed — so this branch renders
                            // nothing until somebody adds a designed-but-unbuilt entry. Kept
                            // because the affordance is generic; it promises nothing on its own.
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
