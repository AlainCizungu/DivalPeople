"use client";

import Link from "next/link";
import { useSession } from "@/auth/SessionProvider";
import { useMessages } from "@/i18n/LocaleProvider";
import { BrandMark } from "@/components/BrandMark";
import { LanguageSwitcher } from "@/components/LanguageSwitcher";

/**
 * Public site header.
 *
 * <p>Shows "Go to dashboard" to a visitor who already has a session, and "Sign in" otherwise —
 * the auth context is available on public routes precisely so this can adapt.
 */
export function LandingHeader() {
  const messages = useMessages();
  const { status, signIn } = useSession();

  const links = [
    { href: "#platform", label: messages.landing.nav.platform },
    { href: "#hr", label: messages.landing.nav.hr },
    { href: "#ai", label: messages.landing.nav.ai },
    { href: "#financial", label: messages.landing.nav.financial },
    { href: "#industries", label: messages.landing.nav.industries },
    { href: "#security", label: messages.landing.nav.security },
  ];

  return (
    <header className="sticky top-0 z-20 border-b border-line bg-white/95 backdrop-blur">
      <nav className="mx-auto flex min-h-16 max-w-7xl items-center justify-between gap-6 px-6">
        <Link href="/" className="flex items-center gap-2.5 text-[22px] font-bold text-navy">
          <BrandMark size={32} />
          {messages.app.name}
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
