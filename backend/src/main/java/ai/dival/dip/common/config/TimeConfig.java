package ai.dival.dip.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The clock, as a bean.
 *
 * <p>So that anything depending on the current time can be handed a different one in a test.
 * {@code Instant.now()} scattered through the code is untestable by construction: a window that
 * turns over on the hour cannot be exercised without either waiting or pretending.
 *
 * <p>UTC, not the system zone. A rate-limit window or an audit timestamp that shifts twice a year
 * is a bug waiting for a specific Sunday.
 */
@Configuration
public class TimeConfig {

    @Bean
    @Primary
    public Clock clock() {
        return Clock.systemUTC();
    }
}
