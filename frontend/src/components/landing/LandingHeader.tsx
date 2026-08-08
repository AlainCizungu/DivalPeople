"use client";

import Link from "next/link";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import { LanguageSwitcher } from "@/components/LanguageSwitcher";

/**
 * Marketing header.
 *
 * <p>Shows "Go to dashboard" to a visitor who already has a session, and "Sign in" otherwise —
 * the same button in the same place either way, so somebody returning does not have to work out
 * whether they are already signed in.
 */
export function LandingHeader() {
  const messages = useMessages();
  const { status, signIn } = useSession();

  const links = [
    { href: "#platform", label: messages.landing.nav.platform },
    { href: "#search", label: messages.landing.nav.search },
    { href: "#risk", label: messages.landing.nav.risk },
    { href: "#industries", label: messages.landing.nav.industries },
    { href: "#exchange", label: messages.landing.nav.exchange },
  ];

  return (
    <header className="sticky top-0 z-50 border-b border-line bg-white/95 backdrop-blur">
      <nav className="mx-auto flex max-w-7xl items-center justify-between gap-6 px-6 py-3.5">
        <Link href="/" className="text-lg font-bold tracking-tight text-navy">
          Dival <span className="text-blue">Intelligence</span>
        </Link>

        <div className="hidden gap-6 text-sm md:flex">
          {links.map((link) => (
            <a key={link.href} href={link.href} className="text-ink transition hover:text-blue">
              {link.label}
            </a>
          ))}
        </div>

        <div className="flex items-center gap-2.5">
          <LanguageSwitcher />
          {status === "authenticated" ? (
            <Link
              href="/app"
              className="rounded bg-blue px-4 py-3 text-sm font-bold text-white transition hover:bg-blue-dark"
            >
              {messages.landing.actions.dashboard}
            </Link>
          ) : (
            <>
              <button
                type="button"
                onClick={() => signIn("/app")}
                className="hidden rounded border border-ink px-4 py-3 text-sm font-bold text-ink transition hover:bg-soft sm:inline-flex"
              >
                {messages.landing.actions.signIn}
              </button>
              <a
                href="#demo"
                className="rounded bg-blue px-4 py-3 text-sm font-bold text-white transition hover:bg-blue-dark"
              >
                {messages.landing.actions.requestDemo}
              </a>
            </>
          )}
        </div>
      </nav>
    </header>
  );
}
