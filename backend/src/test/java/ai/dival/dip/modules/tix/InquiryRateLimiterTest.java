package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.common.error.RateLimitExceededException;
import ai.dival.dip.common.ratelimit.RequestCounter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The window arithmetic, without Redis.
 *
 * <p>The counter is an interface precisely so this can exist: the part that goes subtly wrong is
 * the arithmetic — an off-by-one at the limit, a window that never turns over, a TTL applied on
 * every request so the counter becomes a permanent ban. None of that needs a container to find,
 * and a test that needs one is a test that gets skipped.
 */
class InquiryRateLimiterTest {

    private final UUID operator = UUID.randomUUID();
    private final UUID otherOperator = UUID.randomUUID();

    /** Counts in a map and honours the fixed-window key, which is all the real one guarantees. */
    private static final class InMemoryCounter implements RequestCounter {
        private final Map<String, Long> counts = new HashMap<>();
        private final Map<String, Duration> ttls = new HashMap<>();

        @Override
        public long increment(String key, Duration window) {
            long next = counts.merge(key, 1L, Long::sum);
            // Mirrors the Redis implementation: the TTL is set once, on first increment.
            if (next == 1L) {
                ttls.put(key, window);
            }
            return next;
        }
    }

    private final InMemoryCounter counter = new InMemoryCounter();

    private InquiryRateLimiter limiterAt(Instant now, int limit) {
        return new InquiryRateLimiter(counter, Clock.fixed(now, ZoneOffset.UTC), limit);
    }

    @Test
    @DisplayName("inquiries up to the limit are allowed")
    void allowsUpToTheLimit() {
        InquiryRateLimiter limiter = limiterAt(Instant.parse("2026-08-07T10:15:00Z"), 3);

        assertThatCode(() -> {
            limiter.charge(operator);
            limiter.charge(operator);
            limiter.charge(operator);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the one after the limit is refused")
    void refusesTheOneAfter() {
        InquiryRateLimiter limiter = limiterAt(Instant.parse("2026-08-07T10:15:00Z"), 3);
        limiter.charge(operator);
        limiter.charge(operator);
        limiter.charge(operator);

        assertThatThrownBy(() -> limiter.charge(operator))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("3 an hour");
    }

    @Test
    @DisplayName("hammering after a refusal stays refused rather than alternating")
    void continuedAttemptsStayRefused() {
        // The counter increments before the check for exactly this reason. Checking first and
        // incrementing only on success would let a caller at the limit alternate between refused
        // and allowed indefinitely, which is a limit in name only.
        InquiryRateLimiter limiter = limiterAt(Instant.parse("2026-08-07T10:15:00Z"), 1);
        limiter.charge(operator);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> limiter.charge(operator))
                    .isInstanceOf(RateLimitExceededException.class);
        }
    }

    @Test
    @DisplayName("one operator's usage does not spend another's allowance")
    void allowancesAreSeparatePerOperator() {
        InquiryRateLimiter limiter = limiterAt(Instant.parse("2026-08-07T10:15:00Z"), 1);
        limiter.charge(operator);

        // Competitors share the exchange. One of them exhausting its quota must not take the
        // others down with it.
        assertThatCode(() -> limiter.charge(otherOperator)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the allowance returns in the next hour")
    void windowTurnsOver() {
        limiterAt(Instant.parse("2026-08-07T10:59:59Z"), 1).charge(operator);

        assertThatCode(() -> limiterAt(Instant.parse("2026-08-07T11:00:00Z"), 1).charge(operator))
                .as("a limit that never resets is a ban")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the refusal says how long to wait, and never says zero")
    void refusalCarriesAUsableRetryAfter() {
        InquiryRateLimiter atFiveTwelve = limiterAt(Instant.parse("2026-08-07T10:05:12Z"), 1);
        atFiveTwelve.charge(operator);

        assertThatThrownBy(() -> atFiveTwelve.charge(operator))
                .isInstanceOfSatisfying(RateLimitExceededException.class, ex ->
                        assertThat(ex.getRetryAfterSeconds()).isEqualTo(3600 - (5 * 60 + 12)));

        // A retry-after of zero invites an immediate retry, which is the one thing a client
        // being throttled must not do.
        InquiryRateLimiter atTheBoundary = limiterAt(Instant.parse("2026-08-07T11:59:59.999Z"), 1);
        atTheBoundary.charge(otherOperator);
        assertThatThrownBy(() -> atTheBoundary.charge(otherOperator))
                .isInstanceOfSatisfying(RateLimitExceededException.class, ex ->
                        assertThat(ex.getRetryAfterSeconds()).isPositive());
    }
}
