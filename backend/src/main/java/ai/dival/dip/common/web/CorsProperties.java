package ai.dival.dip.common.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Origins permitted to call the API from a browser.
 *
 * <p>Empty by default, so a deployment that forgets to configure this refuses cross-origin
 * requests rather than accepting all of them. Wildcards are not supported deliberately: the API
 * responds to credentialed requests, and {@code Access-Control-Allow-Origin: *} cannot be
 * combined with credentials.
 *
 * @param allowedOrigins exact origins, e.g. {@code https://app.dival.ai}
 */
@ConfigurationProperties(prefix = "dip.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
