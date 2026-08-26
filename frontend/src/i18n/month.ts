/**
 * An ISO month, in the reader's language.
 *
 * <p>The server sends `2026-08` and no words, deliberately: a month name is language, and a
 * backend that formatted it would be picking one. So the formatting happens here, where the
 * locale is known.
 *
 * <p>Extracted because there were about to be two of these. The executive screen has drawn its
 * axis this way for a while and the overview now needs the same thing beside a trend arrow — and
 * two copies would drift the first time one of them wanted a longer form, leaving two screens
 * naming the same month differently.
 *
 * <p>`new Date(year, month - 1, 1)` and not `new Date(iso)`: the second parses `2026-08` as UTC
 * midnight, which in any negative offset is the 31st of July, and the chart would name the wrong
 * month for half the planet.
 */
export function monthLabel(iso: string, locale: string, form: "short" | "long" = "short"): string {
  const [year, month] = iso.split("-");
  const y = Number(year);
  const m = Number(month);
  // A malformed month is shown as it arrived rather than as "Invalid Date" or as January.
  if (!Number.isFinite(y) || !Number.isFinite(m) || m < 1 || m > 12) {
    return iso;
  }
  return new Intl.DateTimeFormat(locale, { month: form }).format(new Date(y, m - 1, 1));
}
