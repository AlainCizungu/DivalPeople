package ai.dival.dip.modules.analyst;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the model's configuration, the same way the exchange binds its own.
 *
 * <p>A separate class rather than an annotation on the application, so that turning the analyst's
 * model on or off is a change in this module and visible to anybody reading it. No key lives here
 * or anywhere else this repository tracks: both switches read the environment and both are inert
 * without it.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AnalystConfig {
}
