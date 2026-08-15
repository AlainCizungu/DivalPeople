package ai.dival.dip.modules.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The model, checked without a database.
 *
 * <p>No {@code @RequiresDocker}, no Spring context, no fixtures. The scorer takes primitives and
 * returns a record, so these run on a laptop with nothing installed and in CI in milliseconds —
 * and that is not a convenience, it is the point of having put the model in a module that depends
 * on nothing. A risk model whose tests need a running Postgres is a risk model nobody reviews.
 *
 * <p>Three things are being defended here. That the arithmetic is what the documentation says it
 * is. That the two refusals to assess survive — they are the easiest thing in the file to delete
 * by accident and the hardest to notice missing. And that the ceiling is reachable, because an
 * indicator described as being out of 100 whose parts sum to 95 has a top band nobody ever lands
 * in and a scale that quietly means something else.
 */
class RiskIndicatorTest {

    private final RiskIndicatorService model = new RiskIndicatorService();

    @Test
    @DisplayName("the assessed factors add up to exactly the 100 the indicator claims")
    void theScaleIsWhatItSaysItIs() {
        int declared = Arrays.stream(RiskFactorCode.values())
                .mapToInt(RiskFactorCode::maxPoints)
                .sum();

        assertThat(declared)
                .as("five assessed factors and two that are never assessed. The ceiling was 90 "
                        + "under DIP-RI-2, when exposure could not be weighed because the "
                        + "currency of the operator files was unknown; counsel confirmed USD in "
                        + "August 2026 and exposure came back at ten points, which is what puts "
                        + "the top of the scale back where the screen says it is")
                .isEqualTo(100);
    }

    @Test
    @DisplayName("and the ceiling is actually reachable by a real subject")
    void theWorstCaseScoresOneHundred() {
        // Declared maxima adding to 100 is not the same claim as some combination of inputs
        // producing 100. A factor whose branches all stop short of its own maximum would pass the
        // test above and cap the model below its stated top.
        RiskIndicator worst = model.assess(new RiskInputs(
                true, false, 3, 400, IdentityStrength.NAME_ONLY, 1, new BigDecimal("250000")));

        assertThat(worst.score()).isEqualTo(100);
        assertThat(worst.band()).isEqualTo(RiskBand.HIGH);
    }

    @Test
    @DisplayName("a subject who settled everything sits near the floor, not on it")
    void aSettledHistoryIsNotNothing() {
        RiskIndicator settled = model.assess(new RiskInputs(
                false, true, 0, -1, IdentityStrength.STRONG, 0, null));

        // Five rather than zero. They defaulted and then paid, which is information, and the
        // exchange only ever assesses subjects it holds a record against — so the comparison a
        // reader makes is against other matched companies, not against strangers.
        assertThat(settled.score()).isEqualTo(5);
        assertThat(settled.band()).isEqualTo(RiskBand.LOW);
    }

    @Test
    @DisplayName("the worked example from the design: two institutions, over a year, matched on an RCCM")
    void theCaseTheProductWasDesignedAround() {
        RiskIndicator indicator = model.assess(new RiskInputs(
                true, false, 2, 400, IdentityStrength.STRONG, 0, new BigDecimal("800")));

        // 25 unpaid + 30 over a year + 10 for the second institution + 2 for a small exposure.
        //
        // Sixty-seven, which is the figure the screen was designed around and which DIP-RI-2 could
        // not produce — it scored this subject 65, because exposure was unweighable. Pleasing, and
        // worth saying plainly that it is a coincidence rather than a calibration: nobody chose
        // the exposure weights to land here.
        assertThat(indicator.score()).isEqualTo(67);
        assertThat(indicator.band()).isEqualTo(RiskBand.ELEVATED);
        assertThat(indicator.principalDrivers())
                .as("the sentence the screen writes: driven by the age of the obligation and by "
                        + "more than one institution reporting it")
                .containsExactly(RiskFactorCode.DEBT_AGING, RiskFactorCode.PAYMENT_BEHAVIOUR);
    }

    @Test
    @DisplayName("a subject identified only by name reads as riskier, not as cleaner")
    void aWeakMatchRaisesTheAssessment() {
        RiskInputs onFacts = new RiskInputs(true, false, 1, 200, IdentityStrength.STRONG, 0, new BigDecimal("5000"));
        RiskInputs sameFactsWeaklyMatched =
                new RiskInputs(true, false, 1, 200, IdentityStrength.NAME_ONLY, 0, new BigDecimal("5000"));

        // The whole of the Orange delivery is matched this way: 342 customers, no identifier of
        // any kind. If uncertainty about *who this is* lowered the score, every one of them would
        // look better than a company we are sure about, and the direction of that error is the
        // one that loses a bank money.
        assertThat(model.assess(sameFactsWeaklyMatched).score())
                .isGreaterThan(model.assess(onFacts).score());
    }

    @Test
    @DisplayName("fraud is never assessed, because the signal behind it cannot fire")
    void fraudIsRefusedBecauseNothingCanTriggerIt() {
        // A fraud signal count is still accepted as an input and still ignored. Both unique
        // indexes on tix_subject_identifier make one identifier under two subjects impossible —
        // which is not a defect, it is how an RCCM resolves to one company — so the factor spent
        // its whole life reporting LOW. A permanently reassuring number resting on a check that
        // never runs is worse than no number at all.
        RiskIndicator withSignals = model.assess(new RiskInputs(
                true, false, 2, 400, IdentityStrength.STRONG, 3, new BigDecimal("5000")));

        RiskFactor fraud = factor(withSignals, RiskFactorCode.FRAUD_INDICATORS);
        assertThat(fraud.rating()).isEqualTo(RiskRating.NOT_ASSESSED);
        assertThat(fraud.points()).isZero();
        assertThat(fraud.reason())
                .isEqualTo(NotAssessedReason.NO_FRAUD_SIGNAL_IS_COMPUTABLE);
    }

    @Test
    @DisplayName("exposure is weighed now the currency is known, in four steps and never as a figure")
    void exposureIsBandedRatherThanReported() {
        assertThat(exposureOf("100")).isEqualTo(2);
        assertThat(exposureOf("1000")).isEqualTo(2);
        assertThat(exposureOf("1000.01")).isEqualTo(5);
        assertThat(exposureOf("10000")).isEqualTo(5);
        assertThat(exposureOf("10000.01")).isEqualTo(8);
        assertThat(exposureOf("100000")).isEqualTo(8);
        assertThat(exposureOf("100000.01")).isEqualTo(10);
    }

    @Test
    @DisplayName("two very different debts inside one band score identically, which is the disclosure rule")
    void theBandIsWideEnoughToHideTheFigure() {
        // The property that makes weighing an amount safe at all. The exchange has never reported
        // a figure, because a figure tells a competitor the size of a rival's relationship — and
        // a score that moved with the amount would leak it by arithmetic instead. An enquirer who
        // reads the same subject weekly and subtracts every other factor learns which of four
        // brackets applies and nothing nearer.
        assertThat(exposureOf("11000")).isEqualTo(exposureOf("99999"));
        assertThat(exposureOf("100001")).isEqualTo(exposureOf("40000000"));
    }

    @Test
    @DisplayName("a file in two currencies is refused rather than part-added")
    void mixedCurrenciesAreNotSummed() {
        // Null is how ExchangeService says "some of this is not in dollars". Adding the dollars
        // and dropping the rest would report a smaller exposure than the subject has, which is
        // the direction of error that costs a lender money — and converting would mean inventing
        // an exchange rate, which moves and which nobody here owns.
        RiskIndicator indicator = model.assess(new RiskInputs(
                true, false, 2, 400, IdentityStrength.STRONG, 0, null));

        RiskFactor exposure = factor(indicator, RiskFactorCode.OUTSTANDING_EXPOSURE);
        assertThat(exposure.rating()).isEqualTo(RiskRating.NOT_ASSESSED);
        assertThat(exposure.points()).isZero();
        assertThat(exposure.reason()).isEqualTo(NotAssessedReason.MIXED_CURRENCY);
    }

    @Test
    @DisplayName("nothing unpaid is an assessed zero, not a refusal to look")
    void anEmptyExposureIsAFindingRatherThanASilence() {
        // Two different silences, and the screen prints them differently. A subject who owes
        // nothing has been assessed and found clean; a subject whose file mixes currencies has
        // not been assessed at all, and a reader who cannot tell those apart cannot tell whether
        // waiting for better data would change the answer.
        RiskFactor exposure = factor(model.assess(new RiskInputs(
                        false, true, 0, -1, IdentityStrength.STRONG, 0, null)),
                RiskFactorCode.OUTSTANDING_EXPOSURE);

        assertThat(exposure.rating()).isEqualTo(RiskRating.LOW);
        assertThat(exposure.reason()).isNull();
    }

    @Test
    @DisplayName("dispute history is never assessed, and for a different kind of reason")
    void disputesAreRefusedForADifferentReason() {
        RiskIndicator indicator = model.assess(new RiskInputs(
                true, false, 2, 400, IdentityStrength.STRONG, 0, new BigDecimal("5000")));

        RiskFactor disputes = factor(indicator, RiskFactorCode.DISPUTE_HISTORY);
        assertThat(disputes.points()).isZero();
        // This one does not close. Exposure's silence did close — a question was answered and the
        // factor came back — which is exactly the difference the two reasons exist to carry. A
        // dispute already removes a record from every answer the exchange gives, and reporting the
        // dispute would put it back by another route, making the exercise of a statutory right a
        // thing that costs you.
        assertThat(disputes.reason())
                .isEqualTo(NotAssessedReason.DISPUTES_ARE_NOT_DISCLOSED)
                .isNotEqualTo(NotAssessedReason.MIXED_CURRENCY);
    }

    @Test
    @DisplayName("every factor appears in every assessment, including the ones worth nothing")
    void theTableIsAlwaysTheSameLength() {
        RiskIndicator clean = model.assess(new RiskInputs(
                false, true, 0, -1, IdentityStrength.STRONG, 0, null));
        RiskIndicator bad = model.assess(new RiskInputs(
                true, false, 3, 400, IdentityStrength.NAME_ONLY, 2, new BigDecimal("5000")));

        // A table that grows with the bad news cannot be read. Somebody comparing two assessments
        // has no way to tell a factor that found nothing from a factor the model stopped having.
        assertThat(clean.factors()).hasSameSizeAs(bad.factors());
        assertThat(clean.factors().stream().map(RiskFactor::code))
                .containsExactlyElementsOf(EnumSet.allOf(RiskFactorCode.class));
    }

    @Test
    @DisplayName("nothing adverse names no drivers, rather than naming the least clean thing")
    void aQuietAssessmentHasNothingToExplain() {
        RiskIndicator clean = model.assess(new RiskInputs(
                false, false, 0, -1, IdentityStrength.STRONG, 0, null));

        assertThat(clean.score()).isZero();
        // Otherwise the screen writes "primarily driven by identity confidence" over a subject
        // against whom nothing at all was found, which reads as an accusation.
        assertThat(clean.principalDrivers()).isEmpty();
    }

    @Test
    @DisplayName("at most two drivers are named, however many factors fired")
    void theNarrativeStaysASentence() {
        RiskIndicator indicator = model.assess(new RiskInputs(
                true, false, 3, 400, IdentityStrength.NAME_ONLY, 1, new BigDecimal("250000")));

        assertThat(indicator.principalDrivers()).hasSize(2);
        assertThat(indicator.principalDrivers())
                .as("the two largest contributions, in descending order")
                .containsExactly(RiskFactorCode.DEBT_AGING, RiskFactorCode.PAYMENT_BEHAVIOUR);
    }

    @Test
    @DisplayName("the band edges are where the documentation says they are")
    void theBandEdges() {
        assertThat(RiskBand.of(0)).isEqualTo(RiskBand.LOW);
        assertThat(RiskBand.of(19)).isEqualTo(RiskBand.LOW);
        assertThat(RiskBand.of(20)).isEqualTo(RiskBand.MODERATE);
        assertThat(RiskBand.of(39)).isEqualTo(RiskBand.MODERATE);
        assertThat(RiskBand.of(40)).isEqualTo(RiskBand.ELEVATED);
        // 67 is the figure the design was written around, and it has to read as elevated.
        assertThat(RiskBand.of(67)).isEqualTo(RiskBand.ELEVATED);
        assertThat(RiskBand.of(69)).isEqualTo(RiskBand.ELEVATED);
        assertThat(RiskBand.of(70)).isEqualTo(RiskBand.HIGH);
        assertThat(RiskBand.of(100)).isEqualTo(RiskBand.HIGH);
    }

    @Test
    @DisplayName("an age is only weighed when something is actually unpaid")
    void anAgeWithoutADebtIsNotAnAge() {
        // A settled record still has a default date, and it can be years old. Weighing that would
        // report a company that fell behind in 2023 and paid in 2023 as though it were still
        // behind.
        RiskIndicator indicator = model.assess(new RiskInputs(
                false, true, 0, 900, IdentityStrength.STRONG, 0, null));

        assertThat(factor(indicator, RiskFactorCode.DEBT_AGING).points()).isZero();
        assertThat(indicator.score()).isEqualTo(5);
    }

    @Test
    @DisplayName("the model stamps its version on every assessment")
    void everyAssessmentSaysWhichRulesMadeIt() {
        // A decision made this year has to be explainable in three, by which time these weights
        // will have moved. Without the version the only answer available is what the model says
        // now, which is not what happened.
        assertThat(model.assess(new RiskInputs(
                false, false, 0, -1, IdentityStrength.STRONG, 0, null)).modelVersion())
                .isEqualTo("DIP-RI-3");
    }

    @Test
    @DisplayName("a factor cannot be left out without saying why, or explained without being left out")
    void theUnassessedPairingIsEnforced() {
        assertThatThrownBy(() -> new RiskFactor(
                RiskFactorCode.OUTSTANDING_EXPOSURE, RiskRating.NOT_ASSESSED, 0, null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new RiskFactor(RiskFactorCode.DEBT_AGING, RiskRating.HIGH, 30,
                NotAssessedReason.MIXED_CURRENCY))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a factor cannot contribute more than it declares")
    void aFactorCannotExceedItsOwnCeiling() {
        // The guard that keeps the sum-to-100 test meaningful. Without it a weight typed as 35
        // instead of 25 produces scores above the stated maximum and the scale silently changes.
        assertThatThrownBy(() -> RiskFactor.assessed(
                RiskFactorCode.FRAUD_INDICATORS, RiskRating.HIGH, 11))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an assessment has to say how the subject was matched")
    void identityIsNotOptional() {
        assertThatThrownBy(() -> new RiskInputs(true, false, 1, 100, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Points the exposure factor contributes for a total, with everything else held still. */
    private int exposureOf(String usd) {
        return factor(model.assess(new RiskInputs(true, false, 1, 100, IdentityStrength.STRONG, 0,
                new BigDecimal(usd))), RiskFactorCode.OUTSTANDING_EXPOSURE).points();
    }

    private static RiskFactor factor(RiskIndicator indicator, RiskFactorCode code) {
        Optional<RiskFactor> found = indicator.factors().stream()
                .filter(f -> f.code() == code)
                .findFirst();
        assertThat(found).as("%s must appear in every assessment", code).isPresent();
        return found.orElseThrow();
    }
}
