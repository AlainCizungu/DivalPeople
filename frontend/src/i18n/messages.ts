import en from "../../messages/en.json";
import fr from "../../messages/fr.json";

export const locales = ["en", "fr"] as const;
export type Locale = (typeof locales)[number];

export const defaultLocale: Locale = "en";

/**
 * The English catalogue is the source of truth for the message shape. Typing `fr` against it
 * means a key added to one language and forgotten in the other is a compile error, which is how
 * the bilingual rule in AGENTS.md gets enforced rather than merely stated.
 */
export type Messages = typeof en;

const catalogues: Record<Locale, Messages> = {
  en,
  fr: fr as Messages,
};

export function getMessages(locale: Locale): Messages {
  return catalogues[locale];
}

export function isLocale(value: string): value is Locale {
  return (locales as readonly string[]).includes(value);
}
