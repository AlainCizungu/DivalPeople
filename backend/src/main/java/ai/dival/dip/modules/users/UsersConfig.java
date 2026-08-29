package ai.dival.dip.modules.users;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the identity-provider credentials, and nothing else.
 *
 * <p>Its own class rather than a line on a shared configuration, so that the one place in DIP
 * holding credentials which can create accounts is findable by looking for it.
 */
@Configuration
@EnableConfigurationProperties(IdentityAdminProperties.class)
public class UsersConfig {
}
