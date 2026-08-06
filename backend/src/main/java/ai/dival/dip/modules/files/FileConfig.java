package ai.dival.dip.modules.files;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Module configuration.
 *
 * <p>Registered here rather than in a shared configuration class: a module owns its settings, and
 * putting them in {@code common} would make shared code depend on a module — the exact direction
 * {@code scripts/check_architecture.py} forbids.
 */
@Configuration
@EnableConfigurationProperties(FileProperties.class)
public class FileConfig {
}
