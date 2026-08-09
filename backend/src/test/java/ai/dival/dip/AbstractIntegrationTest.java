package ai.dival.dip;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
 *
 * <p>{@link TestRequestCounter} supplies an in-memory rate-limit counter so no test needs Redis.
 * The production counter stays unconditional: a limiter that quietly disappears when Redis is
 * absent is the same shape of defect as an endpoint that is reachable because nobody annotated it.
 *
 * <p><strong>These tests connect as the schema owner, which bypasses row-level security.</strong>
 * That is intentional: they exercise the application-level tenant scoping, and several of them
 * legitimately act as two tenants inside a single transaction, which a tenant-pinned connection
 * cannot do. The database policies are proven separately by {@code RowLevelSecurityTest}, which
 * connects as the unprivileged {@code dip_app} role the application really uses.
 *
 * <p><strong>Know what that costs.</strong> A whole class of defect is invisible from here: any
 * code whose correctness depends on which tenant a connection is bound to will pass as the owner
 * and fail in production. One did. Every method of {@code SubjectRightsService} was
 * {@code @Transactional}, so its per-tenant blocks kept the caller's connection and its binding,
 * and cross-operator suppression, erasure and disclosure would each have touched only the calling
 * operator's rows — with three hundred tests green.
 *
 * <p>A subclass can downgrade the application's own datasource to {@code dip_app} with
 * {@code @TestPropertySource}, leaving Flyway as the owner exactly as a deployment does.
 * {@code SubjectRightsUnderRlsTest} is the example. Anything that acts across tenants deserves one.
 */
@SpringBootTest
@Import(TestRequestCounter.class)
@ActiveProfiles("test")
@RequiresDocker
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // Suppliers, so nothing touches Docker until the context is genuinely being created.
        registry.add("spring.datasource.url", () -> PostgresTestContainer.INSTANCE.getJdbcUrl());
        registry.add("spring.datasource.username", () -> PostgresTestContainer.INSTANCE.getUsername());
        registry.add("spring.datasource.password", () -> PostgresTestContainer.INSTANCE.getPassword());

        // Migrations run as the owner, as they do in production.
        registry.add("spring.flyway.url", () -> PostgresTestContainer.INSTANCE.getJdbcUrl());
        registry.add("spring.flyway.user", () -> PostgresTestContainer.INSTANCE.getUsername());
        registry.add("spring.flyway.password", () -> PostgresTestContainer.INSTANCE.getPassword());
    }
}
