package ai.dival.dip;

import ai.dival.dip.common.ratelimit.RequestCounter;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * An in-memory request counter, so tests never reach Redis.
 *
 * <p>Imported explicitly by {@link AbstractIntegrationTest} rather than component-scanned, because
 * a test bean that is picked up by accident is a test bean that disappears by accident.
 *
 * <p>It mirrors the Redis implementation's one subtlety — the window is honoured through the key,
 * and nothing extends it — but it is not the thing under test. {@code InquiryRateLimiterTest}
 * exercises the arithmetic directly with its own counter; this exists only so that building a
 * Spring context does not require a Redis container.
 */
@TestConfiguration
public class TestRequestCounter {

    @Bean
    @Primary
    public RequestCounter inMemoryRequestCounter() {
        Map<String, Long> counts = new ConcurrentHashMap<>();
        return (key, window) -> counts.merge(key, 1L, Long::sum);
    }
}
