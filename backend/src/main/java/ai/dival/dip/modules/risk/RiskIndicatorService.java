package ai.dival.dip.modules.risk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * The rules that turn what the exchange knows into the DIP Risk Indicator.
 *
 * <p>A sum of five stated weights. There is no model fitted to anything, no coefficient anybody
 * would struggle to defend, and that is the honest description of what this platform can support
 * today: it has facts about who is owed money and no outcome data at all. A logistic regression
 * trained on nothing would be the same arithmetic wearing a lab coat.
 *
 * <p><strong>Pure.</strong> No clock, no repository, no tenant context, nothing injected. It is a
 * Spring bean only so callers can have it handed to them; every method would behave identically
 * called from a {@code main}. That is what lets the tests run without a database, and it is what
 * lets a bank's model reviewer be given one file and told this is all of it.
 *
 * <p><strong>Every factor appears in every assessment.</strong> Including the ones worth nothing,
 * and including the two that are never assessed. A table whose length varies with how bad the
 * news is cannot be read: five rows one day and seven the next tells the reader nothing about
 * whether the model changed or the company did.
 */
@Service
public class RiskIndicatorService {

    /**
     * Which set of rules produced an assessment.
     *
     * <p>Stamped on every result and not for tidiness. Somebody declined for credit in 2026 may
     * ask why in 2029, by which time the weights below will have moved; without a version the
     * only available answer is what the model says now, which is not what happened. The version
     * changes whenever any weight or threshold in this file changes — that is the rule, and it is
     * the whole reason the constant is here rather than a literal at the bottom of the method.
     */
    public static final String MODEL_VERSION = "DIP-RI-2";

    /**
     * How many factors the narrative names.
     *
     * <p>Two. One reads as a single cause where there is rarely one; four is a list nobody
     * finishes. The screen says "primarily driven by X and Y", and primarily is doing real work —
     * these are the largest contributors, not all of them.
     */
    private static final int NARRATED_DRIVERS = 2;

    /** A factor has to be worth this much before it is worth naming as a driver. */
    private static final int DRIVER_THRESHOLD = 5;

    public RiskIndicator assess(RiskInputs inputs) {
        List<RiskFactor> factors = List.of(
                paymentBehaviour(inputs),
                debtAging(inputs),
                reportingInstitutions(inputs),
                identityConfidence(inputs),
                // Three permanently zero now, all listed, and each says which kind of silence it
                // is. Fraud joined the other two when it turned out the signal behind it could
                // never fire — see NO_FRAUD_SIGNAL_IS_COMPUTABLE. The ceiling is therefore 90
                // rather than 100, which is why this is DIP-RI-2 and not a tweak to DIP-RI-1.
                RiskFactor.notAssessed(RiskFactorCode.FRAUD_INDICATORS,
                        NotAssessedReason.NO_FRAUD_SIGNAL_IS_COMPUTABLE),
                RiskFactor.notAssessed(RiskFactorCode.OUTSTANDING_EXPOSURE,
                        NotAssessedReason.CURRENCY_UNCONFIRMED),
                RiskFactor.notAssessed(RiskFactorCode.DISPUTE_HISTORY,
                        NotAssessedReason.DISPUTES_ARE_NOT_DISCLOSED));

        int score = factors.stream().mapToInt(RiskFactor::points).sum();
        return new RiskIndicator(score, RiskBand.of(score), factors, driversOf(factors),
                MODEL_VERSION);
    }

    /**
     * Is anything unpaid.
     *
     * <p>The crude question, weighted heaviest of the simple ones, and it stays crude on purpose.
     * Everything below refines it.
     *
     * <p>A subject whose every recorded obligation was settled scores a little rather than
     * nothing. They defaulted and then paid, which is information, and it is better information
     * than the platform holds about somebody it has never heard of — but the exchange never
     * assesses somebody it has never heard of, so the comparison the reader will make is against
     * other matched subjects, where a clean settlement history genuinely is close to the floor.
     */
    private RiskFactor paymentBehaviour(RiskInputs inputs) {
        if (inputs.anyOutstanding()) {
            return RiskFactor.assessed(RiskFactorCode.PAYMENT_BEHAVIOUR, RiskRating.HIGH, 25);
        }
        if (inputs.anySettled()) {
            return RiskFactor.assessed(RiskFactorCode.PAYMENT_BEHAVIOUR, RiskRating.LOW, 5);
        }
        return RiskFactor.assessed(RiskFactorCode.PAYMENT_BEHAVIOUR, RiskRating.LOW, 0);
    }

    /**
     * How old the oldest unpaid obligation is.
     *
     * <p>The heaviest factor in the model, because age is what separates a missed invoice from a
     * write-off, and because it is the fact this platform holds most reliably — a default date is
     * either reported by the operator or derived from a stated as-at date, and a record says
     * which.
     *
     * <p><strong>These thresholds are not the reporting bands.</strong> {@code AgingBand} exists
     * over in the telecom module and mirrors the columns of a real operator export, because a
     * declared record and an imported one have to land in one vocabulary. Those edges are an
     * accident of somebody's spreadsheet. Reusing them here would mean that the day an operator
     * changes its export format, every company in the country gets reassessed — so the risk scale
     * is its own, coarser, and answerable on its own terms.
     */
    private RiskFactor debtAging(RiskInputs inputs) {
        long days = inputs.longestOverdueDays();
        if (!inputs.anyOutstanding() || days < 0) {
            // Nothing unpaid has no age. Rated rather than omitted, so the row still appears.
            return RiskFactor.assessed(RiskFactorCode.DEBT_AGING, RiskRating.LOW, 0);
        }
        if (days <= 30) {
            return RiskFactor.assessed(RiskFactorCode.DEBT_AGING, RiskRating.LOW, 5);
        }
        if (days <= 90) {
            return RiskFactor.assessed(RiskFactorCode.DEBT_AGING, RiskRating.MODERATE, 12);
        }
        if (days <= 180) {
            return RiskFactor.assessed(RiskFactorCode.DEBT_AGING, RiskRating.MODERATE, 18);
        }
        if (days <= 365) {
            return RiskFactor.assessed(RiskFactorCode.DEBT_AGING, RiskRating.HIGH, 25);
        }
        return RiskFactor.assessed(RiskFactorCode.DEBT_AGING, RiskRating.HIGH, 30);
    }

    /**
     * How many participants report something unpaid.
     *
     * <p>The factor that cannot exist outside an exchange, and the argument for joining one. A
     * single unpaid invoice is a commercial disagreement and is often exactly that; the same
     * company unpaid at three institutions is a pattern, and no one of those three could have
     * seen it alone.
     *
     * <p>Counted in three steps and no further. The difference between three institutions and
     * seven is real but the model cannot say it is worth more than the ceiling, and a scale that
     * kept climbing would let somebody read the exact number back out of the score.
     */
    private RiskFactor reportingInstitutions(RiskInputs inputs) {
        int count = inputs.institutionsWithOutstanding();
        if (count <= 1) {
            return RiskFactor.assessed(
                    RiskFactorCode.REPORTING_INSTITUTIONS, RiskRating.LOW, 0);
        }
        if (count == 2) {
            return RiskFactor.assessed(
                    RiskFactorCode.REPORTING_INSTITUTIONS, RiskRating.MODERATE, 10);
        }
        return RiskFactor.assessed(RiskFactorCode.REPORTING_INSTITUTIONS, RiskRating.HIGH, 20);
    }

    /**
     * How firmly this is even the right company.
     *
     * <p>Not a property of the subject — a property of the answer, and the only factor here that
     * describes the platform's own uncertainty rather than somebody's conduct. It raises the
     * score, which is worth being explicit about: a weakly matched subject reads as riskier, not
     * as cleaner. The alternative would let a bank take a name match on a common trading name as
     * confidently as an RCCM match, and the direction of that error is the dangerous one.
     */
    private RiskFactor identityConfidence(RiskInputs inputs) {
        return switch (inputs.identity()) {
            case STRONG -> RiskFactor.assessed(
                    RiskFactorCode.IDENTITY_CONFIDENCE, RiskRating.LOW, 0);
            case PARTIAL -> RiskFactor.assessed(
                    RiskFactorCode.IDENTITY_CONFIDENCE, RiskRating.MODERATE, 8);
            case NAME_ONLY -> RiskFactor.assessed(
                    RiskFactorCode.IDENTITY_CONFIDENCE, RiskRating.HIGH, 15);
        };
    }

    /**
     * The one or two factors that produced most of the score.
     *
     * <p>Computed here rather than on the screen, so that the sentence a bank reads and the
     * arithmetic that produced the number can never come from two different places. A narrative
     * assembled in the browser is a narrative that drifts.
     *
     * <p>Ties break on the model's own order, which is the order the factors are listed in. That
     * is arbitrary and it is stable, which is the property that matters: the same inputs must
     * produce the same sentence every time, or somebody comparing two printouts of one assessment
     * finds a difference the model never made.
     */
    private List<RiskFactorCode> driversOf(List<RiskFactor> factors) {
        List<RiskFactor> ranked = new ArrayList<>(factors);
        ranked.sort(Comparator.comparingInt(RiskFactor::points).reversed());

        List<RiskFactorCode> drivers = new ArrayList<>();
        for (RiskFactor factor : ranked) {
            if (factor.points() < DRIVER_THRESHOLD || drivers.size() == NARRATED_DRIVERS) {
                break;
            }
            drivers.add(factor.code());
        }
        return List.copyOf(drivers);
    }
}
