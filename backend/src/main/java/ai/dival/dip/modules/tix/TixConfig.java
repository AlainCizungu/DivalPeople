package ai.dival.dip.modules.tix;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Module configuration.
 *
 * <p>Registered in the module rather than in a shared configuration class, for the same reason
 * {@code FileConfig} is: a module owns its settings, and putting them in {@code common} would make
 * shared code depend on a module — the direction {@code scripts/check_architecture.py} forbids.
 */
@Configuration
@EnableConfigurationProperties({TixProperties.class, DisclosureProperties.class})
public class TixConfig {
}
