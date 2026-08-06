package ai.dival.dip.common.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A guard nobody tests is a guard nobody can rely on.
 *
 * <p>These run without Docker and without a Spring context on purpose. The check has to work on
 * the worst day somebody will ever have with this system, and a test that needs infrastructure to
 * prove it is a test that gets skipped.
 */
class ProductionSafetyTest {

    private static final String GOOD_SECRET = "0dP4x9Qm2Lr7Wz1TfKb6";
    private static final String OTHER_SECRET = "8Hn3Vt5Yc0Rj2Md9Pw4S";
    private static final String GOOD_ISSUER = "https://id.dival.ai/realms/dip";
    private static final String GOOD_DB = "jdbc:postgresql://db.internal:5432/dip";

    private ProductionSafety safety(String appUser, String appPassword, String ownerUser,
                                    String ownerPassword, String issuer, String url) {
        return new ProductionSafety(appUser, appPassword, ownerUser, ownerPassword, issuer, url);
    }

    private ProductionSafety sound() {
        return safety("dip_app", GOOD_SECRET, "dip_owner", OTHER_SECRET, GOOD_ISSUER, GOOD_DB);
    }

    @Test
    @DisplayName("a sound configuration starts")
    void soundConfigurationStarts() {
        assertThatCode(() -> sound().verify()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the application must not run as the account that owns the schema")
    void refusesSharedDatabaseAccount() {
        // The one that matters most. The owner bypasses row-level security, so every isolation
        // test in this project would still pass while every tenant saw every other tenant.
        assertThatThrownBy(() -> safety("dip", GOOD_SECRET, "dip", OTHER_SECRET,
                GOOD_ISSUER, GOOD_DB).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("row-level security");
    }

    @Test
    @DisplayName("a password that is in this repository is refused")
    void refusesDevelopmentPassword() {
        assertThatThrownBy(() -> safety("dip_app", "dip_app", "dip_owner", OTHER_SECRET,
                GOOD_ISSUER, GOOD_DB).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DIP_APP_DB_PASSWORD");
    }

    @Test
    @DisplayName("the check is case-insensitive, because Dip_App is the same credential")
    void developmentPasswordCheckIgnoresCase() {
        assertThatThrownBy(() -> safety("dip_app", "Change-Me-Locally", "dip_owner", OTHER_SECRET,
                GOOD_ISSUER, GOOD_DB).verify())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a short password is refused")
    void refusesShortPassword() {
        assertThatThrownBy(() -> safety("dip_app", "s3cret!", "dip_owner", OTHER_SECRET,
                GOOD_ISSUER, GOOD_DB).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("16 characters");
    }

    @Test
    @DisplayName("an issuer over plain HTTP is refused")
    void refusesPlainHttpIssuer() {
        assertThatThrownBy(() -> safety("dip_app", GOOD_SECRET, "dip_owner", OTHER_SECRET,
                "http://id.dival.ai/realms/dip", GOOD_DB).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forgeable");
    }

    @Test
    @DisplayName("an issuer still pointing at localhost is refused")
    void refusesLocalhostIssuer() {
        assertThatThrownBy(() -> safety("dip_app", GOOD_SECRET, "dip_owner", OTHER_SECRET,
                "https://localhost:8081/realms/dip", GOOD_DB).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("localhost");
    }

    @Test
    @DisplayName("a database still pointing at localhost is refused")
    void refusesLocalhostDatabase() {
        assertThatThrownBy(() -> safety("dip_app", GOOD_SECRET, "dip_owner", OTHER_SECRET,
                GOOD_ISSUER, "jdbc:postgresql://127.0.0.1:5432/dip").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DIP_DB_URL");
    }

    @Test
    @DisplayName("every fault is reported at once, not one per restart")
    void reportsEveryFaultTogether() {
        // Fixing configuration one failed boot at a time is how a deploy window disappears.
        assertThatThrownBy(() -> safety("dip", "dip", "dip", "dip",
                "http://localhost:8081/realms/dip", "jdbc:postgresql://localhost:5432/dip")
                .verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("row-level security")
                .hasMessageContaining("DIP_APP_DB_PASSWORD")
                .hasMessageContaining("DIP_DB_PASSWORD")
                .hasMessageContaining("https")
                .hasMessageContaining("DIP_DB_URL");
    }

    @Test
    @DisplayName("the failure names variables and never prints their values")
    void neverPrintsASecret() {
        String password = "SuperSecretProductionValue123";
        assertThatThrownBy(() -> safety("dip_app", password, "dip_app", password,
                GOOD_ISSUER, GOOD_DB).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(password);
    }

    @Test
    @DisplayName("an empty password is refused rather than treated as absent")
    void refusesEmptyPassword() {
        assertThatThrownBy(() -> safety("dip_app", "  ", "dip_owner", OTHER_SECRET,
                GOOD_ISSUER, GOOD_DB).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is empty");
    }
}
