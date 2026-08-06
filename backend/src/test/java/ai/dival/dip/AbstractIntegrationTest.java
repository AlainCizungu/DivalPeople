package ai.dival.dip;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for tests that need a real database.
 *
 * <p>A real PostgreSQL container rather than an in-memory substitute, because the things most
 * worth testing here — row-level security, partial unique indexes, check constraints — do not
 * exist in H2.
 *
 * <p>This class deliberately holds no static container field. The container lives in
 * {@link PostgresTestContainer} so that it starts only when a property is actually resolved,
 * which is to say only when a test that was not skipped is really building its context.
 */
@SpringBootTest
@ActiveProfiles("test")
@RequiresDocker
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Suppliers, so nothing touches Docker until the context is genuinely being created.
        registry.add("spring.datasource.url", () -> PostgresTestContainer.INSTANCE.getJdbcUrl());
        registry.add("spring.datasource.username", () -> PostgresTestContainer.INSTANCE.getUsername());
        registry.add("spring.datasource.password", () -> PostgresTestContainer.INSTANCE.getPassword());
    }
}
