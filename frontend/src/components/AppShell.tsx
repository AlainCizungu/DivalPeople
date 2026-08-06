"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { useAuth } from "react-oidc-context";
import { notificationsApi } from "@/api/client";
import { useMessages } from "@/i18n/LocaleProvider";
import { BrandMark } from "./BrandMark";
import { LanguageSwitcher } from "./LanguageSwitcher";

/**
 * Application shell: left navigation, top utility bar, main content area.
 * Structure follows docs/UI_DESIGN_SYSTEM.md.
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const messages = useMessages();
  const pathname = usePathname();
  const auth = useAuth();

  const [unreadCount, setUnreadCount] = useState(0);
  const token = auth.user?.access_token;

  // Polled rather than pushed. A websocket for a number that changes a few times a day is a
  // connection to keep alive, reconnect and authorise for very little gain.
  useEffect(() => {
    if (!token) return;

    let cancelled = false;
    const refresh = async () => {
      try {
        const { unread } = await notificationsApi.unreadCount(token);
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
  }, [token, pathname]);

  const profile = auth.user?.profile;
  const displayName =
    profile?.name ?? profile?.preferred_username ?? profile?.email ?? "";
  // Claim carried by the access token and enforced server-side; shown so it is obvious which
  // operator the current session is acting as.
  const tenantId =
    typeof profile?.tenant_id === "string" ? profile.tenant_id : undefined;

  // Only routes that exist. A nav full of links to nothing makes the product feel broken and
  // makes it impossible to tell a missing feature from a bug; entries are added as they ship.
  const navigation = [
    { href: "/app", label: messages.nav.home, badge: 0 },
    { href: "/app/people", label: messages.nav.people, badge: 0 },
    { href: "/app/recruitment", label: messages.nav.recruitment, badge: 0 },
    { href: "/app/lifecycle", label: messages.nav.onboarding, badge: 0 },
    { href: "/app/leave", label: messages.nav.timeAndLeave, badge: 0 },
    { href: "/app/organization", label: messages.nav.organization, badge: 0 },
    { href: "/app/notifications", label: messages.nav.notifications, badge: unreadCount },
    { href: "/app/tix", label: messages.nav.tix, badge: 0 },
  ];

  return (
    <div className="flex min-h-screen">
      <nav
        aria-label={messages.app.name}
        className="hidden w-64 shrink-0 flex-col border-r border-line bg-white md:flex"
      >
        <Link href="/" className="flex items-center gap-2 border-b border-line px-5 py-4">
          <BrandMark size={26} />
          <span className="text-lg font-bold text-navy">{messages.app.name}</span>
        </Link>

        <ul className="flex-1 space-y-0.5 p-3">
          {navigation.map((item) => {
            const active = pathname === item.href;
            return (
              <li key={item.href}>
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
                  {item.badge > 0 && (
                    <span className="rounded-full bg-blue px-2 py-0.5 text-xs font-bold text-white tabular-nums">
                      {item.badge}
                    </span>
                  )}
                </Link>
              </li>
            );
          })}
        </ul>

        <p className="border-t border-line px-5 py-3 text-xs text-muted">{messages.app.platform}</p>
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
              onClick={() => void auth.signoutRedirect()}
              className="rounded px-3 py-1.5 text-sm font-semibold text-ink transition hover:text-blue"
            >
              {messages.common.signOut}
            </button>
          </div>
        </header>

        <main className="flex-1 p-6">{children}</main>
      </div>
    </div>
  );
}
