package ai.dival.dip.modules.tix;

import ai.dival.dip.common.error.RateLimitExceededException;
import ai.dival.dip.common.ratelimit.RequestCounter;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Caps how many inquiries one operator may make in an hour.
 *
 * <p>Without this the exchange can be swept in bulk through the intended API. An operator with a
 * valid {@code TIX_INQUIRER} role could walk an identifier format space, learn which values are
 * registered, and read a competing operator's debt statuses at HTTP speed — every request
 * individually legitimate, the aggregate a bulk export of somebody else's book.
 *
 * <p>This does not make that impossible; it makes it slow enough to notice. The audit trail is
 * what makes it visible, and the two are only worth anything together: a throttle without a trail
 * slows down something nobody can see afterwards, and a trail without a throttle records a sweep
 * that already finished.
 *
 * <p>A fixed window rather than a sliding one, deliberately. A caller can burst twice the limit
 * across a window boundary, which is a real and accepted weakness — the alternative costs a
 * sorted set per tenant and more moving parts, and the purpose here is to turn a weekend into a
 * decade rather than to be exact.
 */
@Component
public class InquiryRateLimiter {

    private static final Duration WINDOW = Duration.ofHours(1);

    private final RequestCounter counter;
    private final Clock clock;
    private final int limit;

    public InquiryRateLimiter(RequestCounter counter, Clock clock,
                              @Value("${dip.tix.inquiries-per-hour:120}") int limit) {
        this.counter = counter;
        this.clock = clock;
        this.limit = limit;
    }

    /**
     * Charges one inquiry against the tenant's allowance.
     *
     * @throws RateLimitExceededException when the allowance is spent, carrying the seconds until
     *         the window turns over so a well-behaved client can wait rather than guess
     */
    public void charge(UUID tenantId) {
        long window = clock.millis() / WINDOW.toMillis();
        String key = "tix:inquiry:" + tenantId + ":" + window;

        // Counted before the check, so a caller who keeps hammering after being refused keeps
        // incrementing and stays refused. Checking first and incrementing only on success would
        // let a caller at the limit alternate between refused and allowed forever.
        long used = counter.increment(key, WINDOW);

        if (used > limit) {
            long secondsIntoWindow = (clock.millis() % WINDOW.toMillis()) / 1000;
            throw new RateLimitExceededException(
                    "Inquiry limit of " + limit + " an hour reached for this operator",
                    Math.max(1, WINDOW.toSeconds() - secondsIntoWindow));
        }
    }
}
