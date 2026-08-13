package ai.dival.dip.modules.risk;

/**
 * One line of the table under the figure.
 *
 * <p>Every factor the model knows about appears in every assessment, including the ones that
 * contributed nothing. A factor shown only when it fires produces a table whose length varies
 * with how bad the news is, and a reader who sees five rows one day and seven the next cannot
 * tell whether the model changed or the company did.
 *
 * @param code    which factor, as a code the screen turns into words in either language
 * @param rating  how it reads
 * @param points  what it added to the score. Zero for an unassessed factor, and zero for an
 *                assessed one that found nothing — the rating is what tells those apart
 * @param reason  why it was not assessed, and null when it was
 */
public record RiskFactor(RiskFactorCode code, RiskRating rating, int points,
                         NotAssessedReason reason) {

    public RiskFactor {
        if ((rating == RiskRating.NOT_ASSESSED) != (reason != null)) {
            // The pairing is the point. An unassessed factor with no reason is an omission
            // wearing an explanation's clothes, and a reason attached to an assessed factor
            // would say the model both used and excluded the same thing.
            throw new IllegalArgumentException(
                    "An unassessed factor must say why, and an assessed one must not pretend to");
        }
        if (points < 0 || points > code.maxPoints()) {
            throw new IllegalArgumentException(
                    code + " may contribute at most " + code.maxPoints() + ", not " + points);
        }
    }

    static RiskFactor assessed(RiskFactorCode code, RiskRating rating, int points) {
        return new RiskFactor(code, rating, points, null);
    }

    static RiskFactor notAssessed(RiskFactorCode code, NotAssessedReason reason) {
        return new RiskFactor(code, RiskRating.NOT_ASSESSED, 0, reason);
    }
}
