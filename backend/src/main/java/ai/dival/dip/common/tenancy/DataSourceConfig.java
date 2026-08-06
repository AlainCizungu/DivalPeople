package ai.dival.dip.common.tenancy;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The application's datasource, wrapped so every connection carries its tenant.
 *
 * <p>This connects as the unprivileged {@code dip_app} role. Flyway is configured separately
 * with owner credentials, because migrations must create and alter objects that the application
 * role is deliberately not allowed to touch — and because an owner bypasses row-level security,
 * which is exactly what the application must not do.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        return new TenantAwareDataSource(properties.initializeDataSourceBuilder().build());
    }
}
