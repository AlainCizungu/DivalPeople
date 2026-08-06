package ai.dival.dip;

import org.testcontainers.DockerClientFactory;

/**
 * Condition used by {@link AbstractIntegrationTest}.
 *
 * <p>Tests that need a database should be skipped when Docker is unavailable, not fail. A
 * developer without Docker running still gets useful signal from the unit tests, and CI — where
 * Docker is always present — runs everything.
 */
public final class DockerAvailable {

    private DockerAvailable() {
    }

    public static boolean isAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ex) {
            // Any failure probing the daemon means "not available" for our purposes.
            return false;
        }
    }
}
