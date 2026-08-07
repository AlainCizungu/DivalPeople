package ai.dival.dip.common.config;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Runs {@link ProductionSafety} before anything can connect to anything.
 *
 * <p>This exists because the first version was an ordinary bean and a real container proved it
 * useless in the case that matters. Flyway's initializer opened its connection during context
 * refresh, before the guard's bean was created, so a deployment with a wrong database address got
 * a {@code ConnectException} stack trace and heard nothing about the other four faults. Fix the
 * address, restart, meet the next one. Reporting every fault at once — which the guard does, and
 * which there is a test for — is worth nothing if something else fails first.
 *
 * <p>An {@code EnvironmentPostProcessor} runs before the application context is created at all,
 * so nothing has opened a socket yet.
 *
 * <p>{@link Ordered#LOWEST_PRECEDENCE} deliberately: this has to run <em>after</em>
 * {@code ConfigDataEnvironmentPostProcessor}, or {@code application-prod.yml} would not be loaded
 * and there would be no properties to check.
 */
public class ProductionSafetyEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    private static final String PRODUCTION_PROFILE = "prod";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment,
                                       SpringApplication application) {
        if (!isProduction(environment)) {
            return;
        }

        // Read through the Environment so placeholders resolve exactly as they will for the
        // beans. A variable that is missing entirely throws here, which is the correct outcome
        // and is already how application-prod.yml is written.
        new ProductionSafety(
                environment.getProperty("spring.datasource.username"),
                environment.getProperty("spring.datasource.password"),
                environment.getProperty("spring.flyway.user"),
                environment.getProperty("spring.flyway.password"),
                environment.getProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri"),
                environment.getProperty("spring.datasource.url"))
                .verify();
    }

    /**
     * Whether the production profile is active.
     *
     * <p>Checks the resolved active profiles rather than the raw property, so
     * {@code SPRING_PROFILES_ACTIVE}, a command-line argument and a profile set in a
     * configuration file are all treated the same. Getting this wrong in the permissive direction
     * would silently disable every check.
     */
    private boolean isProduction(ConfigurableEnvironment environment) {
        return Arrays.asList(environment.getActiveProfiles()).contains(PRODUCTION_PROFILE);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
