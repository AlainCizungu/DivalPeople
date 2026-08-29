"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { useSession } from "@/auth/SessionProvider";
import { notificationsApi } from "@/api/client";
import { useMessages } from "@/i18n/LocaleProvider";
import { BrandMark } from "./BrandMark";
import { LanguageSwitcher } from "./LanguageSwitcher";
import { AskDipLauncher } from "./AskDipLauncher";
import {
  activeHref,
  buildNavigation,
  TOP_BAR_ORDER,
  itemKey,
  keyOfFirst,
  type NavGroup,
} from "./navigation";

/**
 * Application shell: one bar across the top, and the page underneath it.
 *
 * <p><strong>There is no left menu.</strong> There was, for a while: a 256px column of seven
 * collapsible headings, remembered open state, badge sums carried up to a collapsed heading so
 * tidying could not hide a count. All of it careful, and all of it solving problems the column
 * created. The column itself was the cost — present on every screen of a product whose screens are
 * mostly tables and figures, and hidden entirely below tablet width, so a phone had no navigation
 * at all. That last part is the one that settles it: an elaborate answer for desktop and no answer
 * for a phone is worse than a plain answer for both.
 *
 * <p>Every area is now a dropdown in the bar, in one row that scrolls sideways when it has to.
 * Seven closed labels take less room than seven open headings and are reachable at every width.
 *
 * <p><strong>What the bar has to do that the menu did for free.</strong> A menu shows where you
 * are just by being open — a highlighted row inside a visible heading. A bar of closed dropdowns
 * says nothing until one is pointed at, so the group holding the current page is marked in the bar
 * and the current entry is marked inside its menu. Without both, every screen looks like every
 * other screen, which is how people get lost.
 *
 * <p><strong>Still the platform's shape rather than a list of screens.</strong> The catalogue in
 * navigation.ts is unchanged and is still the single place that says what DIP contains; the front
 * door's directory reads the same list. An item sits in the area it belongs to whether or not it
 * has been built, and an unbuilt one is drawn as unbuilt rather than pointed at a neighbour so it
 * can look finished. No entry ever navigates to a page that is not there.
 *
 * <p>The badge machinery went with the menu, and so did NavItem.badge. It was kept as a dormant
 * field on the argument that a menu able to tidy away an unread count without saying so is the
 * defect worth guarding against — true, and it guarded a menu that no longer exists. A field
 * nothing writes and nothing reads is not a safeguard, it is a thing the next person has to work
 * out the purpose of.
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

  const groups = buildNavigation(t, { isPlatformAdmin });

  /**
   * Every group, as a dropdown, in one order.
   *
   * <p>The left menu is gone. It was a 256px column holding seven collapsible headings, present on
   * every screen, and on anything narrower than a tablet it was not present at all — so the
   * product had one navigation for desktop and none for a phone. One bar that every width gets is
   * both simpler and the first time the narrow case has been answered.
   *
   * <p>A group with nothing in it for this caller is dropped rather than drawn empty: Network holds
   * only the platform administrator's screen, so for everybody else it would be a heading over
   * nothing.
   */
  const menus = TOP_BAR_ORDER.map((id) =>
    groups.find((group) => group.id === id),
  ).filter((group): group is NavGroup => group !== undefined && group.items.length > 0);

  const current = activeHref(
    pathname,
    groups.flatMap((group) => group.items.map((item) => item.href)),
  );

  // No two entries share a route — check_architecture.py fails the build if they do — so this
  // resolves a route to the single entry that owns it. Kept as first-match rather than assuming
  // uniqueness: the guard is the thing that holds the property, and a highlight that throws when
  // the guard is wrong would be a worse way to find out.
  const currentKey = keyOfFirst(groups, current);

  /**
   * Which heading the page you are on lives under, so the bar can mark it.
   *
   * <p>Found by the entry rather than by the route, which is what let it survive two groups
   * sharing one — and is still the right way round now that they cannot, because matching on an
   * href in two places means two places that have to agree.
   *
   * <p>The remembered open/closed state that used to live here went with the menu. A dropdown has
   * no state worth persisting: it is open while you are pointing at it and shut the rest of the
   * time, which is the whole reason it fits in a bar.
   */
  const currentGroup = groups.find((group) =>
    group.items.some((item) => itemKey(group, item) === currentKey),
  )?.id;

  return (
    <div className="flex min-h-screen flex-col">
      <header className="flex items-center gap-3 border-b border-line bg-white px-4 py-3 md:px-6">
        {/* Home, and the only way back to it now that the menu is gone.

            It points at /app rather than at the marketing site, which is where the menu's brand
            used to go. Inside the product the useful meaning of the logo is "take me to the
            overview"; a link out to the public page is a thing somebody clicks once by accident
            and then avoids. */}
        <Link
          href="/app"
          aria-label={messages.app.name}
          className="flex shrink-0 items-center gap-2"
        >
          <BrandMark size={24} />
          <span className="hidden font-bold text-navy sm:inline">
            {messages.app.name}
          </span>
        </Link>

        {/* Every area of the platform, at every width.

            Scrolls sideways rather than wrapping or hiding. Seven headings, some of them two words
            and longer again in French, will not fit a phone — and the two alternatives are worse
            than a scrollbar: wrapping makes the bar grow a second row and shove the page down,
            hiding is what the old menu did and it is why a phone had no navigation at all.

            min-w-0 is what makes the shrinking work. A flex child defaults to min-width:auto and
            refuses to become narrower than its content, so without it this pushes the profile
            menu off the right-hand edge instead of scrolling. */}
        <nav
          aria-label={t.topBarLandmark}
          className="-mx-1 flex min-w-0 flex-1 items-center gap-0.5 overflow-x-auto px-1 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden"
        >
          {menus.map((group) => (
            <TopBarMenu
              key={group.id}
              label={group.heading}
              // The area you are in, marked in the bar itself. With the menu gone this is the
              // only thing on screen that says where you are, and a product where every screen
              // looks equally like every other is a product people get lost in.
              current={group.id === currentGroup}
            >
              {(close) => (
                <>
                  {group.items.map((item) =>
                    item.href ? (
                      <MenuLink
                        key={itemKey(group, item)}
                        href={item.href}
                        onClick={close}
                        current={itemKey(group, item) === currentKey}
                      >
                        {item.label}
                      </MenuLink>
                    ) : null,
                  )}
                </>
              )}
            </TopBarMenu>
          ))}
        </nav>

        <div className="flex shrink-0 items-center gap-1">
            <LanguageSwitcher />

            <TopBarLink
              href="/app/notifications"
              label={messages.nav.notifications}
              badge={unreadCount}
            >
              <path d="M18 8a6 6 0 10-12 0c0 7-3 8-3 8h18s-3-1-3-8M13.7 21a2 2 0 01-3.4 0" />
            </TopBarLink>

            <TopBarMenu label={messages.common.help}>
              {(close) => (
                <MenuLink
                  // Was /app#everything, an anchor into the bottom of the overview. That section
                  // is now its own page: the overview answers "what needs me this morning" and a
                  // site map underneath it pushed the answer up and away.
                  href="/app/directory"
                  onClick={close}
                  current={pathname === "/app/directory"}
                >
                  {messages.common.exploreDip}
                </MenuLink>
              )}
            </TopBarMenu>

            <TopBarMenu label={messages.common.profile}>
              {() => (
                <>
                  <div className="border-b border-line px-4 py-3">
                    <p className="text-xs text-muted">{messages.common.signedInAs}</p>
                    <p className="truncate text-sm font-semibold text-navy">{displayName}</p>
                    {tenantId && (
                      <p className="mt-1 font-mono text-xs text-muted">{tenantId.slice(0, 8)}</p>
                    )}
                  </div>
                  <button
                    type="button"
                    onClick={() => void signOut()}
                    className="w-full px-4 py-2.5 text-left text-sm font-semibold text-ink transition hover:bg-soft hover:text-blue"
                  >
                    {messages.common.signOut}
                  </button>
                </>
              )}
          </TopBarMenu>
        </div>
      </header>

      {/* Full width now, where it used to start 256px in. That space was a menu on every screen of
          a product whose screens are mostly tables and figures, and the overview in particular was
          being read in a column narrower than it was designed for. */}
      <main className="flex-1 p-4 md:p-6">{children}</main>

      {/* Every screen, bottom right, closed until asked for. The questions this answers are the
          ones somebody thinks of while looking at something else. */}
      <AskDipLauncher />
    </div>
  );
}

/**
 * An icon in the top bar that goes somewhere, with a count on it when there is one.
 *
 * <p>The only place notifications appear. They were briefly in two — here and in the left menu —
 * and the menu is gone, which settles an argument rather than winning it: the catalogue key still
 * exists and this renders it, so there is one entry for one screen again.
 *
 * <p>The count is in the accessible name, not only in a coloured circle. "Notifications" and
 * "Notifications, 3 unread" are different facts and a screen reader is entitled to the second.
 */
function TopBarLink({
  href,
  label,
  badge,
  children,
}: {
  href: string;
  label: string;
  badge: number;
  children: React.ReactNode;
}) {
  return (
    <Link
      href={href}
      aria-label={badge > 0 ? `${label} (${badge})` : label}
      title={label}
      className="relative rounded p-2 text-ink transition hover:bg-soft hover:text-blue"
    >
      <svg
        aria-hidden="true"
        width="18"
        height="18"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {children}
      </svg>
      {badge > 0 && (
        <span
          aria-hidden="true"
          className="absolute top-0.5 right-0.5 min-w-[16px] rounded-full bg-blue px-1 text-center text-[10px] leading-4 font-bold text-white tabular-nums"
        >
          {badge > 9 ? "9+" : badge}
        </span>
      )}
    </Link>
  );
}

/**
 * A labelled button that opens a small panel beneath it.
 *
 * <p>Closes on Escape, on a click anywhere outside, and on any navigation from inside — the
 * children are given the close function so a link can dismiss the panel it lives in rather than
 * leaving it hanging open over the new page.
 *
 * <p>Escape and outside-click are not decoration. A menu that can only be closed by clicking its
 * own button is a keyboard trap for somebody who opened it by accident, and this one sits in the
 * bar of every screen.
 */
function TopBarMenu({
  label,
  current,
  children,
}: {
  label: string;
  /**
   * Whether the page open right now lives inside this menu.
   *
   * <p>Not decoration. With the left menu gone this is the only thing on screen that says where
   * you are — the sidebar used to do it with a highlighted row inside a heading you could see, and
   * a bar of closed dropdowns says nothing at all until one is opened.
   *
   * <p>Drawn as weight and an underline rather than a fill, so it reads as "you are here" and not
   * as "this one is open".
   */
  current?: boolean;
  children: (close: () => void) => React.ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const holder = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;

    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") setOpen(false);
    };
    const onClick = (event: MouseEvent) => {
      if (holder.current && !holder.current.contains(event.target as Node)) {
        setOpen(false);
      }
    };

    document.addEventListener("keydown", onKey);
    document.addEventListener("mousedown", onClick);
    return () => {
      document.removeEventListener("keydown", onKey);
      document.removeEventListener("mousedown", onClick);
    };
  }, [open]);

  return (
    <div ref={holder} className="relative">
      <button
        type="button"
        onClick={() => setOpen((isOpen) => !isOpen)}
        aria-expanded={open}
        aria-haspopup="menu"
        className={`shrink-0 rounded px-2.5 py-1.5 text-sm font-semibold whitespace-nowrap transition ${
          current
            ? "text-blue underline decoration-2 underline-offset-8 hover:bg-soft"
            : "text-ink hover:bg-soft hover:text-blue"
        }`}
      >
        {label}
      </button>
      {open && (
        <div
          role="menu"
          className="absolute right-0 z-20 mt-1 w-60 overflow-hidden rounded-lg border border-line bg-white shadow-lg"
        >
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  );
}

function MenuLink({
  href,
  onClick,
  current,
  children,
}: {
  href: string;
  onClick: () => void;
  /**
   * Whether this is the page already open.
   *
   * <p>Marked with aria-current as well as colour. The left menu says where you are with a border
   * and a weight; a dropdown that closes the moment you look away has to say it too, or the two
   * renderings of one catalogue disagree about something the user can see.
   */
  current?: boolean;
  children: React.ReactNode;
}) {
  return (
    <Link
      href={href}
      role="menuitem"
      onClick={onClick}
      aria-current={current ? "page" : undefined}
      className={
        current
          ? "block bg-soft px-4 py-2.5 text-sm font-semibold text-blue"
          : "block px-4 py-2.5 text-sm font-semibold text-ink transition hover:bg-soft hover:text-blue"
      }
    >
      {children}
    </Link>
  );
}
