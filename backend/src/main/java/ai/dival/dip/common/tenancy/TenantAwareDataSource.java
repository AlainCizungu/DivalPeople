package ai.dival.dip.common.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Binds the current tenant to every connection handed out, so PostgreSQL row-level security
 * can enforce isolation independently of application code.
 *
 * <p>The setting is applied at checkout rather than per statement, which means a transaction is
 * pinned to the tenant that was current when it began. That matches how the application works —
 * one request, one tenant — and it is the reason a transaction cannot quietly change tenant
 * halfway through.
 *
 * <p>When no tenant is bound the setting is written as empty, which makes every policy
 * comparison NULL and therefore hides every tenant-owned row. Unauthenticated paths fail closed.
 */
public class TenantAwareDataSource extends DelegatingDataSource {

    /**
     * {@code false} for the local flag argument: the setting must persist for the connection,
     * not just the current transaction, because it is applied before any transaction starts.
     */
    private static final String APPLY_TENANT = "SELECT set_config('app.tenant_id', ?, false)";

    public TenantAwareDataSource(DataSource delegate) {
        super(delegate);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return bindTenant(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return bindTenant(super.getConnection(username, password));
    }

    private Connection bindTenant(Connection connection) throws SQLException {
        String tenantId = TenantContext.find().map(UUID::toString).orElse("");
        try (PreparedStatement statement = connection.prepareStatement(APPLY_TENANT)) {
            statement.setString(1, tenantId);
            statement.execute();
        } catch (SQLException ex) {
            // Never hand back a connection whose tenant binding is unknown.
            connection.close();
            throw ex;
        }
        return connection;
    }
}
