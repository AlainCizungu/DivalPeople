package ai.dival.dip.modules.files;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upload limits.
 *
 * <p>An allowlist rather than a blocklist, per {@code docs/SECURITY_MODEL.md}: a blocklist is a
 * guess about every dangerous type anyone will ever invent, and it is always out of date.
 *
 * @param maxSizeBytes      largest accepted upload
 * @param allowedContentTypes exact MIME types accepted; nothing else is stored
 */
@ConfigurationProperties(prefix = "dip.files")
public record FileProperties(long maxSizeBytes, List<String> allowedContentTypes) {

    private static final long DEFAULT_MAX_SIZE = 20L * 1024 * 1024;

    private static final List<String> DEFAULT_TYPES = List.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/webp",
            "text/csv",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    public FileProperties {
        maxSizeBytes = maxSizeBytes > 0 ? maxSizeBytes : DEFAULT_MAX_SIZE;
        allowedContentTypes = allowedContentTypes == null || allowedContentTypes.isEmpty()
                ? DEFAULT_TYPES
                : List.copyOf(allowedContentTypes);
    }
}
