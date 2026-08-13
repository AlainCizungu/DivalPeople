"use client";

import { useMessages } from "@/i18n/LocaleProvider";
import { interpolate } from "@/i18n/interpolate";
import type { RiskFactor, RiskIndicator } from "@/api/client";
import { Card, Pill, type Tone } from "@/components/ui";

/**
 * The DIP Risk Indicator, and everything that produced it.
 *
 * <p>This replaces an amber dashed panel that showed 72/100 over four invented bars, marked as a
 * sketch. That was the honest thing to do while there was no model; now there is one, and the
 * figure below is computed by {@code RiskIndicatorService} from records this operator was
 * entitled to see.
 *
 * <p><strong>The number is never shown alone.</strong> Every factor is listed beneath it,
 * including the two the model refuses to weigh and the reason for each refusal, and a sentence
 * says what drove the result. That is the whole product argument in one screen: a bank can
 * disagree with the weighting because it can see the weighting.
 *
 * <p>The words come from the message catalogue and the codes from the API, so an explanation can
 * be reworded in either language without touching a model somebody lent money on the strength of.
 */
export function RiskIndicatorPanel({ indicator }: { indicator: RiskIndicator }) {
  const messages = useMessages();
  const t = messages.risk;

  // The band the score falls in, not the score itself, decides the colour. Two subjects the model
  // cannot tell apart must not be coloured differently.
  const BAND_TONE: Record<RiskIndicator["band"], Tone> = {
    LOW: "positive",
    MODERATE: "neutral",
    ELEVATED: "review",
    HIGH: "serious",
  };

  const RATING_TONE: Record<RiskFactor["rating"], Tone> = {
    NOT_ASSESSED: "neutral",
    LOW: "positive",
    MODERATE: "review",
    HIGH: "serious",
  };

  const unassessed = indicator.factors.filter((factor) => factor.reason !== null);

  return (
    <Card title={t.title} description={t.scaleNote}>
      <div className="flex flex-col gap-6">
        <div className="flex flex-wrap items-baseline gap-3">
          <span className="text-5xl font-bold tabular-nums text-navy">{indicator.score}</span>
          <span className="text-sm text-muted">{t.outOf}</span>
          <span className="ml-auto">
            <Pill tone={BAND_TONE[indicator.band]}>{t.bands[indicator.band]}</Pill>
          </span>
        </div>

        <div>
          <h3 className="mb-2 text-sm font-bold text-navy">{t.factorsTitle}</h3>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-line text-left text-xs text-muted">
                <th className="pb-1.5 font-semibold">{t.factorHeader}</th>
                <th className="pb-1.5 text-right font-semibold">{t.assessmentHeader}</th>
              </tr>
            </thead>
            <tbody>
              {/* Every factor, in the model's own order, whether or not it contributed. A table
                  that grew with the bad news would be unreadable across two assessments. */}
              {indicator.factors.map((factor) => (
                <tr key={factor.code} className="border-b border-line/60 last:border-0">
                  <td className="py-2 text-ink">{t.factors[factor.code]}</td>
                  <td className="py-2 text-right">
                    <Pill tone={RATING_TONE[factor.rating]}>
                      {/* Identity confidence is the one factor a reader thinks of as a strength
                          rather than a risk, so "low" is rendered "strong". The model holds one
                          scale; the screen speaks the reader's language. */}
                      {factor.code === "IDENTITY_CONFIDENCE"
                        ? t.identityRatings[factor.rating]
                        : t.ratings[factor.rating]}
                    </Pill>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div>
          <h3 className="mb-1.5 text-sm font-bold text-navy">{t.whyTitle}</h3>
          <p className="text-sm text-ink">{narrative(t, indicator)}</p>
        </div>

        {unassessed.length > 0 && (
          <div>
            <h3 className="mb-1.5 text-sm font-bold text-navy">{t.notAssessedTitle}</h3>
            <ul className="flex flex-col gap-2">
              {unassessed.map((factor) => (
                <li key={factor.code} className="text-sm text-muted">
                  <span className="font-semibold text-ink">{t.factors[factor.code]}</span>
                  {" — "}
                  {t.notAssessedReasons[factor.reason!]}
                </li>
              ))}
            </ul>
          </div>
        )}

        <p className="rounded border border-line bg-soft px-4 py-3 text-xs text-muted">
          {interpolate(t.modelNote, t.modelNote, { version: indicator.modelVersion })}
        </p>
      </div>
    </Card>
  );
}

/**
 * The sentence under "Why this assessment?".
 *
 * <p>Composed from codes the server ranked, never from the score. If this function decided for
 * itself which factors mattered, the sentence a bank reads and the arithmetic that produced the
 * number would come from two places and would eventually disagree.
 */
function narrative(
  t: ReturnType<typeof useMessages>["risk"],
  indicator: RiskIndicator,
): string {
  const [first, second] = indicator.principalDrivers;
  if (!first) {
    // No driver is not the same as a driver worth nothing. Writing "driven primarily by identity
    // confidence" over a subject against whom nothing was found reads as an accusation.
    return t.whyNothing;
  }
  const band = t.bands[indicator.band];
  return second
    ? interpolate(t.whyTwo, t.whyTwo, {
        band,
        first: t.drivers[first],
        second: t.drivers[second],
      })
    : interpolate(t.whyOne, t.whyOne, { band, first: t.drivers[first] });
}
