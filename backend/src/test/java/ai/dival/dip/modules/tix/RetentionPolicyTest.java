package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retention arithmetic, on a fixed clock.
 *
 * <p>None of this needs a database, and a test that needs one is a test that gets skipped. What it
 * does need is a clock it controls: the interesting assertions are about what a record looks like
 * three and five years out, and about the single day at the boundary.
 */
class RetentionPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);

    private final RetentionPolicy policy = policyWith(3, 5, 30);

    private static RetentionPolicy policyWith(int simple, int repeat, int settledDays) {
        return new RetentionPolicy(
                new TixProperties(Map.of("USD", new BigDecimal("100")),
                        new TixProperties.Retention(simple, repeat, settledDays)),
                Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a first default is kept three years from the day it fell due")
    void simpleDefaultKeepsThreeYears() {
        assertThat(policy.expiryFor(LocalDate.of(2026, 3, 1), false))
                .isEqualTo(LocalDate.of(2029, 3, 1));
    }

    @Test
    @DisplayName("a repeat default is kept five years")
    void repeatDefaultKeepsFiveYears() {
        assertThat(policy.expiryFor(LocalDate.of(2026, 3, 1), true))
                .isEqualTo(LocalDate.of(2031, 3, 1));
    }

    @Test
    @DisplayName("the clock runs from the default date, not from when it was declared")
    void theClockRunsFromTheDefault() {
        // An operator declaring a four-year-old debt today does not get four fresh years. If the
        // period ran from declaration, a record could be kept alive indefinitely by settling and
        // re-declaring, and retention would measure how slow the operator was rather than how old
        // the default is.
        LocalDate old = TODAY.minusYears(4);
        assertThat(policy.expiryFor(old, false))
                .as("already past its period on the day it was declared")
                .isBefore(TODAY);
    }

    @Test
    @DisplayName("a record is still live on its final day")
    void expiryIsExclusiveOfTheLastDay() {
        // Off by one here shortens every retention period in the system by a day, which is
        // invisible forever.
        assertThat(policy.hasExpired(TODAY)).as("today is still within the period").isFalse();
        assertThat(policy.hasExpired(TODAY.minusDays(1))).isTrue();
        assertThat(policy.hasExpired(TODAY.plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("settling brings erasure forward")
    void settlementShortensRetention() {
        LocalDate farAway = LocalDate.of(2031, 1, 1);
        assertThat(policy.expiryOnSettlement(farAway)).isEqualTo(TODAY.plusDays(30));
    }

    @Test
    @DisplayName("settling never pushes erasure further away")
    void settlementCannotExtendRetention() {
        // A record already due to expire next week must not be handed a fresh thirty days by the
        // act of paying it off. Paying a debt can only ever help the person who paid it.
        LocalDate soon = TODAY.plusDays(3);
        assertThat(policy.expiryOnSettlement(soon)).isEqualTo(soon);
    }

    @Test
    @DisplayName("a deployment may configure erasure on the next sweep after settlement")
    void zeroSettlementDaysIsAMeaningfulSetting() {
        // Zero has to be expressible and distinguishable from "unset", which is why the property
        // is a boxed Integer. With a primitive int, an absent setting and a deliberate "erase
        // immediately" are the same value, and one of them silently becomes the other.
        assertThat(policyWith(3, 5, 0).expiryOnSettlement(LocalDate.of(2031, 1, 1)))
                .isEqualTo(TODAY);
    }

    @Test
    @DisplayName("absent configuration falls back to the advised period, not to zero")
    void unconfiguredRetentionUsesTheDocumentedDefaults() {
        TixProperties bare = new TixProperties(Map.of("USD", new BigDecimal("100")), null);
        RetentionPolicy fallback = new RetentionPolicy(bare,
                Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC));

        // Failing to zero would erase the entire registry on the first nightly sweep.
        //
        // Both are five now, so a repeat default falls on the same day as a first one. That the
        // two lines below are identical is the finding, not a copy-paste: counsel answered "five
        // years" without distinguishing them, and until somebody asks whether he meant to, the
        // repeat period earns its existence only as a place to put a different answer.
        assertThat(fallback.expiryFor(LocalDate.of(2026, 1, 1), false))
                .isEqualTo(LocalDate.of(2031, 1, 1));
        assertThat(fallback.expiryFor(LocalDate.of(2026, 1, 1), true))
                .isEqualTo(LocalDate.of(2031, 1, 1));
    }
}
