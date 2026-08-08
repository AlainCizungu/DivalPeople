package ai.dival.dip.common.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.PostgresTestContainer;
import ai.dival.dip.RequiresDocker;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Proves the row-level security policies actually bind.
 *
 * <p>Deliberately bypasses Spring and JPA and talks to PostgreSQL directly as the unprivileged
 * {@code dip_app} role — the role the application really uses. The point is to show that even
 * hand-written SQL, with no application-level tenant predicate anywhere, cannot see or write
 * another tenant's rows. A test that went through the repositories would prove only that the
 * repositories are careful, which is what the other tests already cover.
 */
@RequiresDocker
class RowLevelSecurityTest extends AbstractIntegrationTest {

    private static final String APP_USER = "dip_app";
    private static final String APP_PASSWORD = "dip_app";

    private UUID tenantA;
    private UUID tenantB;
    private UUID subjectId;

    @BeforeEach
    void seedAsOwner() throws SQLException {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        subjectId = UUID.randomUUID();

        try (Connection owner = ownerConnection()) {
            insertTenant(owner, tenantA, "rls-a");
            insertTenant(owner, tenantB, "rls-b");

            try (PreparedStatement statement = owner.prepareStatement("""
                    INSERT INTO tix_subject (id, subject_type, full_name, normalized_name)
                    VALUES (?, 'INDIVIDUAL', 'RLS Subject', 'rls subject')
                    """)) {
                statement.setObject(1, subjectId);
                statement.executeUpdate();
            }

            insertDebtRecord(owner, tenantA);
            insertDebtRecord(owner, tenantB);
        }
    }

    @Test
    @DisplayName("raw SQL as the application role sees only the bound tenant's rows")
    void readsAreConfinedToTheBoundTenant() throws SQLException {
        try (Connection connection = appConnection(tenantA)) {
            assertThat(countDebtRecords(connection)).isEqualTo(1);
            assertThat(tenantOfSingleVisibleRecord(connection)).isEqualTo(tenantA);
        }

        try (Connection connection = appConnection(tenantB)) {
            assertThat(tenantOfSingleVisibleRecord(connection)).isEqualTo(tenantB);
        }
    }

    @Test
    @DisplayName("with no tenant bound, nothing is visible at all")
    void unboundTenantSeesNothing() throws SQLException {
        try (Connection connection = appConnection(null)) {
            assertThat(countDebtRecords(connection)).isZero();
        }
    }

    @Test
    @DisplayName("a write naming another tenant is rejected by the policy")
    void writesCannotEscapeTheBoundTenant() throws SQLException {
        try (Connection connection = appConnection(tenantA)) {
            // SETTLED, not OUTSTANDING: an OUTSTANDING row for this tenant and subject already
            // exists, and the partial unique index would fire first, proving the wrong thing.
            assertThatThrownBy(() -> insertDebtRecord(connection, tenantB, "SETTLED"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    @DisplayName("exchange mode reads across operators")
    void exchangeModeReadsAcrossOperators() throws SQLException {
        try (Connection connection = appConnection(tenantA)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL app.exchange = 'on'");
            }

            assertThat(countDebtRecords(connection)).isEqualTo(2);
            connection.rollback();
        }
    }

    @Test
    @DisplayName("exchange mode relaxes reads but still cannot write outside the tenant")
    void exchangeModeCannotWriteAcrossOperators() throws SQLException {
        try (Connection connection = appConnection(tenantA)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL app.exchange = 'on'");
            }

            assertThatThrownBy(() -> insertDebtRecord(connection, tenantB, "SETTLED"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");

            connection.rollback();
        }
    }

    @Test
    @DisplayName("the exchange flag does not survive its transaction")
    void exchangeModeIsTransactionScoped() throws SQLException {
        try (Connection connection = appConnection(tenantA)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL app.exchange = 'on'");
            }
            assertThat(countDebtRecords(connection)).isEqualTo(2);
            connection.commit();

            // Same physical connection, new transaction: the relaxation is gone.
            assertThat(countDebtRecords(connection)).isEqualTo(1);
            connection.rollback();
        }
    }

    // --- helpers -----------------------------------------------------------

    private Connection ownerConnection() throws SQLException {
        return DriverManager.getConnection(
                PostgresTestContainer.INSTANCE.getJdbcUrl(),
                PostgresTestContainer.INSTANCE.getUsername(),
                PostgresTestContainer.INSTANCE.getPassword());
    }

    /** A connection as the application role, with {@code app.tenant_id} bound as production does. */
    private Connection appConnection(UUID tenantId) throws SQLException {
        Connection connection = DriverManager.getConnection(
                PostgresTestContainer.INSTANCE.getJdbcUrl(), APP_USER, APP_PASSWORD);
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT set_config('app.tenant_id', ?, false)")) {
            statement.setString(1, tenantId == null ? "" : tenantId.toString());
            statement.execute();
        }
        return connection;
    }

    private void insertTenant(Connection connection, UUID id, String slugPrefix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tenant (id, name, slug, edition, default_locale)
                VALUES (?, ?, ?, 'TELECOM', 'fr')
                """)) {
            statement.setObject(1, id);
            statement.setString(2, slugPrefix);
            statement.setString(3, slugPrefix + "-" + UUID.randomUUID());
            statement.executeUpdate();
        }
    }

    private void insertDebtRecord(Connection connection, UUID tenantId) throws SQLException {
        insertDebtRecord(connection, tenantId, "OUTSTANDING");
    }

    private void insertDebtRecord(Connection connection, UUID tenantId, String status)
            throws SQLException {
        // retention_until is NOT NULL as of V19. This test writes raw SQL on purpose — it is
        // checking the row-level security policy itself, not the application's behaviour, so it
        // deliberately bypasses every Java guard and therefore has to satisfy the schema by hand.
        // Far in the future, so a purge running concurrently could never erase the rows this test
        // is asserting about.
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO tix_debt_record
                    (tenant_id, subject_id, status, amount, currency, service_category,
                     default_date, dunning_evidence, retention_until)
                VALUES (?, ?, ?, 100.00, 'USD', 'POSTPAID', CURRENT_DATE, TRUE,
                        CURRENT_DATE + INTERVAL '10 years')
                """)) {
            statement.setObject(1, tenantId);
            statement.setObject(2, subjectId);
            statement.setString(3, status);
            statement.executeUpdate();
        }
    }

    private int countDebtRecords(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT COUNT(*) FROM tix_debt_record WHERE subject_id = '" + subjectId + "'")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private UUID tenantOfSingleVisibleRecord(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT tenant_id FROM tix_debt_record WHERE subject_id = '" + subjectId + "'")) {
            rs.next();
            return (UUID) rs.getObject(1);
        }
    }
}
