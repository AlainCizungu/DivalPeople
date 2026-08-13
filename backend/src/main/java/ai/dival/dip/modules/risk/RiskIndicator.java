package ai.dival.dip.modules.risk;

import java.util.List;

/**
 * The DIP Risk Indicator.
 *
 * <p><strong>Not a credit score, and the name is load-bearing.</strong> A credit score is a
 * statistical claim: this proportion of borrowers who looked like this failed to repay. Making
 * that claim needs outcome data, a validation sample, and a regulatory position on all three, and
 * this platform has none of them yet. What it has is a set of facts about who is owed what, and
 * an explicit rule for reading them. Calling that a credit score would be borrowing an authority
 * nobody has granted, and the first competent question from a bank's model risk team would find
 * it out.
 *
 * <p>So: an indicator, out of 100, with every factor that produced it listed beside it, including
 * the two the model refuses to weigh. A reader who disagrees with the weighting can see the
 * weighting. That is the only sense in which this is explainable, and it is a stronger sense than
 * most scores manage.
 *
 * @param score            0 to 100, where 0 is no adverse information and 100 is the most this
 *                         platform can observe. The risk way up, not the credit way up
 * @param band             the sentence beside the number, because 67 and 64 are not different
 *                         findings
 * @param factors          every factor, always, in a fixed order
 * @param principalDrivers the one or two factors that produced most of the score, so the screen
 *                         can say what drove it without recomputing anything. Empty when nothing
 *                         drove it, which is what a score of zero means
 * @param modelVersion     which set of rules produced this. A lending decision made in 2026 has
 *                         to be explainable in 2029, and by then the rules will have moved
 */
public record RiskIndicator(
        int score,
        RiskBand band,
        List<RiskFactor> factors,
        List<RiskFactorCode> principalDrivers,
        String modelVersion) {

    public RiskIndicator {
        factors = List.copyOf(factors);
        principalDrivers = List.copyOf(principalDrivers);
    }
}
