"use client";

import { useLocale } from "@/i18n/LocaleProvider";

/**
 * Switches between the two launch languages.
 *
 * <p>Labelled rather than icon-only, and the current language is announced via aria-label so a
 * screen-reader user knows what the button will do.
 */
export function LanguageSwitcher() {
  const { locale, setLocale, messages } = useLocale();
  const next = locale === "en" ? "fr" : "en";

  return (
    <button
      type="button"
      onClick={() => setLocale(next)}
      aria-label={`${messages.common.language}: ${next.toUpperCase()}`}
      className="rounded border border-line bg-white px-3 py-1.5 text-sm font-semibold text-ink transition hover:border-blue hover:text-blue"
    >
      {next.toUpperCase()}
    </button>
  );
}
