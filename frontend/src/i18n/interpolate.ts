/**
 * Substitutes `{name}` placeholders in a translated string.
 *
 * <p>Notifications arrive as a message key plus parameters so they render in whatever language
 * the reader has chosen, rather than the one whoever triggered them happened to be using.
 *
 * <p>An unknown key falls back to the key itself. A notification that reads oddly is a bug worth
 * seeing; one that renders as an empty row is a bug that hides.
 */
export function interpolate(
  template: string | undefined,
  fallback: string,
  params: Record<string, string>,
): string {
  const source = template ?? fallback;
  return source.replace(/\{(\w+)\}/g, (match, name: string) =>
    Object.prototype.hasOwnProperty.call(params, name) ? params[name]! : match,
  );
}
