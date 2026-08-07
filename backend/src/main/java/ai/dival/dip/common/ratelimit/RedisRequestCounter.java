package ai.dival.dip.common.ratelimit;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * The shared counter, in Redis.
 *
 * <p>Redis rather than an in-process map because a limit that resets when a container restarts,
 * or that a second instance does not know about, is not a limit. Redis is already load-bearing —
 * sessions live there — so this adds no new dependency.
 *
 * <p><strong>Fails closed.</strong> If Redis cannot be reached, the request is refused rather
 * than allowed. That is the unusual choice and it is deliberate: failing open would mean anyone
 * who can disturb Redis gets an unmetered window against a credit-bureau exchange. It costs
 * almost nothing in practice, because without Redis nobody can hold a session either, so the
 * application is already unusable.
 */
@Component
public class RedisRequestCounter implements RequestCounter {

    private final StringRedisTemplate redis;

    public RedisRequestCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public long increment(String key, Duration window) {
        Long count = redis.opsForValue().increment(key);
        if (count == null) {
            throw new IllegalStateException("Redis returned no count for " + key);
        }
        // Set the expiry only on the first increment. Calling expire() every time would slide the
        // window forward on every request, so a caller making one request per second would keep
        // the counter alive for ever and be banned permanently after the first burst.
        if (count == 1L) {
            redis.expire(key, window);
        }
        return count;
    }
}
