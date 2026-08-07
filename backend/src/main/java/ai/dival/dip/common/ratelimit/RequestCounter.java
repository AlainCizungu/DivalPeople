package ai.dival.dip.common.ratelimit;

import java.time.Duration;

/**
 * Counts requests in a window, shared across every instance of the application.
 *
 * <p>An interface rather than a direct Redis call so the window arithmetic can be tested without
 * a Redis container. The arithmetic is the part that gets subtly wrong — an off-by-one at the
 * limit, or a TTL that never gets set and turns the counter into a permanent ban.
 */
public interface RequestCounter {

    /**
     * Increments the counter for {@code key} and returns the new value.
     *
     * <p>The TTL is applied on first increment and not extended afterwards, which is what makes
     * this a fixed window rather than a counter that never resets under sustained load.
     */
    long increment(String key, Duration window);
}
