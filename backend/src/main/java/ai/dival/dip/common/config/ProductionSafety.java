package ai.dival.dip.common.config;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Refuses to start on a configuration inherited from development.
 *
 * <p>A missing variable already stops the application: {@code application-prod.yml} declares its
 * placeholders without defaults, and Spring will not resolve them. This covers the harder case —
 * a variable that is present and wrong. Those do not announce themselves, because everything
 * works.
 *
 * <p>The checks are chosen for that property: each one is something a deployment can get wrong
 * silently, where the consequence is severe and the symptom is nothing at all.
 *
 * <ul>
 *   <li>The application connecting as the schema owner. Row-level security is the boundary
 *       between two telecoms' debt records, and the owner <em>bypasses</em> it. Every isolation
 *       test would still pass; every tenant would see every other.
 *   <li>A development password surviving into a deployment. They are in this repository.
 *   <li>An issuer over plain HTTP, or pointing at localhost, which means tokens are either
 *       interceptable or being validated against a server that is not the identity provider.
 * </ul>
 *
 * <p>Failing at start-up is the point. An application that refuses to boot gets attention within
 * minutes; one that boots wrong can run for months.
 */
@Component
@Profile("prod")
public class ProductionSafety {

    private static final Logger log = LoggerFactory.getLogger(ProductionSafety.class);

    /** Passwords that appear in this repository, and therefore in anybody's clone of it. */
    private static final List<String> DEVELOPMENT_SECRETS = List.of(
            "dip", "dip_app", "postgres", "password", "changeme", "change-me",
            "change-me-locally", "admin", "secret", "dip-local-development-secret");

    private final String appUser;
    private final String appPassword;
    private final String ownerUser;
    private final String ownerPassword;
    private final String issuerUri;
    private final String datasourceUrl;

    public ProductionSafety(
            @Value("${spring.datasource.username}") String appUser,
            @Value("${spring.datasource.password}") String appPassword,
            @Value("${spring.flyway.user}") String ownerUser,
            @Value("${spring.flyway.password}") String ownerPassword,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
            @Value("${spring.datasource.url}") String datasourceUrl) {
        this.appUser = appUser;
        this.appPassword = appPassword;
        this.ownerUser = ownerUser;
        this.ownerPassword = ownerPassword;
        this.issuerUri = issuerUri;
        this.datasourceUrl = datasourceUrl;
    }

    @PostConstruct
    void verify() {
        List<String> faults = new ArrayList<>();

        // The most dangerous single misconfiguration this system has. The owner is not subject to
        // row-level security, so running as it turns every tenant boundary into a comment.
        if (appUser != null && appUser.equals(ownerUser)) {
            faults.add("The application and migration database users are the same account ("
                    + appUser + "). The migration user owns the schema and bypasses row-level "
                    + "security, so tenant isolation would not hold. They must be separate roles.");
        }

        checkSecret(faults, "DIP_APP_DB_PASSWORD", appPassword);
        checkSecret(faults, "DIP_DB_PASSWORD", ownerPassword);

        if (isBlank(issuerUri)) {
            faults.add("DIP_OIDC_ISSUER_URI is empty.");
        } else {
            String issuer = issuerUri.toLowerCase(Locale.ROOT);
            if (!issuer.startsWith("https://")) {
                faults.add("DIP_OIDC_ISSUER_URI must be https. Token signing keys fetched over "
                        + "plain HTTP can be replaced in transit, which makes every token "
                        + "forgeable.");
            }
            if (issuer.contains("localhost") || issuer.contains("127.0.0.1")) {
                faults.add("DIP_OIDC_ISSUER_URI still points at localhost.");
            }
        }

        if (datasourceUrl != null
                && (datasourceUrl.contains("localhost") || datasourceUrl.contains("127.0.0.1"))) {
            faults.add("DIP_DB_URL points at localhost, which inside a container is the "
                    + "container itself.");
        }

        if (!faults.isEmpty()) {
            // The message names variables, never values. A start-up log is not a place to print
            // a password, least of all one being reported as wrong.
            String report = String.join("\n  - ", faults);
            log.error("Refusing to start with an unsafe production configuration:\n  - {}",
                    report);
            throw new IllegalStateException(
                    "Refusing to start with an unsafe production configuration:\n  - " + report);
        }

        log.info("Production configuration checks passed");
    }

    private void checkSecret(List<String> faults, String name, String value) {
        if (isBlank(value)) {
            faults.add(name + " is empty.");
            return;
        }
        if (DEVELOPMENT_SECRETS.contains(value.toLowerCase(Locale.ROOT))) {
            faults.add(name + " is set to a value that appears in this repository. Anybody with "
                    + "a clone of it has this credential.");
        }
        if (value.length() < 16) {
            faults.add(name + " is shorter than 16 characters.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
