package ai.dival.dip;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Lazily initialised singleton PostgreSQL container, shared by every integration test in the JVM.
 *
 * <p>The container lives here rather than on {@code AbstractIntegrationTest} for a specific
 * reason: a static field on the base class is initialised as soon as a subclass is initialised,
 * which happens before JUnit evaluates skip conditions. Docker would then be contacted — and
 * throw — even for a test that was about to be skipped. Holding it in its own class defers
 * startup until a property supplier actually reads it, which only happens when a Spring context
 * is really being built.
 *
 * <p>One container for the whole run, not one per class: startup dominates the runtime of these
 * tests. It is never stopped explicitly; Ryuk reaps it when the JVM exits.
 */
public final class PostgresTestContainer {

    public static final PostgreSQLContainer<?> INSTANCE = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("dip")
            .withUsername("dip")
            .withPassword("dip");

    static {
        INSTANCE.start();
    }

    private PostgresTestContainer() {
    }
}
