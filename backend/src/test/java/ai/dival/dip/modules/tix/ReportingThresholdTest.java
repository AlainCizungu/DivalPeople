package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.common.error.PolicyRefusedException;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The boundary, exhaustively, without a database.
 *
 * <p>This is the control that decides who is in a national bad-payer registry and who is simply
 * somebody who owes a small amount. An off-by-one is invisible in production — nobody notices the
 * hundred people who should not have been listed — so it is worth being tedious about here.
 */
class ReportingThresholdTest {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ReportingThreshold threshold =
            new ReportingThreshold(new TixProperties(Map.of("USD", HUNDRED)));

    @Test
    @DisplayName("comfortably above the floor is declarable")
    void aboveTheFloorPasses() {
        assertThatCode(() -> threshold.requireDeclarable(new BigDecimal("150.00"), "USD"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("exactly the threshold is declarable — the TDR says at or above")
    void exactlyTheFloorPasses() {
        assertThatCode(() -> threshold.requireDeclarable(HUNDRED, "USD"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the same amount written with more decimal places is still declarable")
    void scaleDoesNotChangeTheAnswer() {
        // BigDecimal.equals compares scale as well as value, so "100.00" is not equal to "100".
        // An operator whose system sends two decimal places must not be refused for formatting.
        assertThatCode(() -> threshold.requireDeclarable(new BigDecimal("100.00"), "USD"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("one cent below the floor is refused")
    void justBelowTheFloorIsRefused() {
        assertThatThrownBy(() -> threshold.requireDeclarable(new BigDecimal("99.99"), "USD"))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("100");
    }

    @Test
    @DisplayName("a currency with no configured floor is refused, not defaulted")
    void unconfiguredCurrencyIsRefused() {
        // The dangerous alternative is treating an unknown currency as "no floor" and letting
        // everything through, which would put somebody in the registry over 500 CDF — about a
        // fifth of a dollar — while every USD record obeyed a hundred-dollar rule.
        assertThatThrownBy(() -> threshold.requireDeclarable(new BigDecimal("5000000"), "CDF"))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("CDF")
                .hasMessageContaining("USD");
    }

    @Test
    @DisplayName("currency codes are matched without regard to case or padding")
    void currencyMatchingIsForgiving() {
        assertThatCode(() -> threshold.requireDeclarable(new BigDecimal("150"), " usd "))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a deployment that configured nothing declares nothing")
    void noConfigurationMeansNoDeclarations() {
        ReportingThreshold empty = new ReportingThreshold(new TixProperties(Map.of()));

        // Failing closed. An empty threshold map most likely means the configuration did not load,
        // and the safe reading of "I do not know the floor" is not "then there isn't one".
        assertThatThrownBy(() -> empty.requireDeclarable(new BigDecimal("1000000"), "USD"))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("none");
    }

    @Test
    @DisplayName("a null currency is refused rather than throwing a null pointer")
    void nullCurrencyIsRefusedCleanly() {
        assertThatThrownBy(() -> threshold.requireDeclarable(HUNDRED, null))
                .isInstanceOf(PolicyRefusedException.class);
    }
}
