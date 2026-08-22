package ai.dival.dip.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Says out loud that this instance carries invented data.
 *
 * <p>The demo profile is a deliberate exception to a rule the seeders state in their own javadoc:
 * that they never run in a deployed environment. An exception nobody can see from the outside is
 * how it stops being an exception — a year from now somebody inherits a running instance, finds
 * eight institutions and a few hundred obligations in it, and has no way to tell which of them
 * anybody actually reported.
 *
 * <p>So it is in the log, at WARN, on every start. Not INFO: this is not a fact about
 * configuration, it is a caveat about every figure the platform will show for as long as it runs.
 *
 * <p>Runs first, before anything is seeded, so the warning is above the seeding lines in the log
 * rather than buried under them.
 *
 * <p>It does not refuse to start, and that is a judgement rather than an oversight. The profile
 * has to be asked for explicitly, in an environment variable, on a machine somebody deliberately
 * provisioned; a guard that made it harder would mostly be a guard somebody works around at the
 * wrong moment. What it must not be is quiet.
 */
@Component
@Profile("demo")
@Order(0) // before LocalTenantSeeder at 10
public class DemoProfileBanner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoProfileBanner.class);

    @Override
    public void run(ApplicationArguments args) {
        log.warn("");
        log.warn("  ############################################################");
        log.warn("  #  DEMONSTRATION DATA IS ENABLED (spring profile 'demo')   #");
        log.warn("  ############################################################");
        log.warn("");
        log.warn("  The tenants, subjects and obligations in this database are invented.");
        log.warn("  Every institution name carries '(demo)'. Nothing here was reported by");
        log.warn("  anybody, and no figure on any screen is a fact about a real business.");
        log.warn("");
        log.warn("  No sign-in has been created. Accounts are made by hand in Keycloak, and");
        log.warn("  the realm fixture in infra/keycloak/ must never be imported here: its");
        log.warn("  passwords are published in the repository.");
        log.warn("");
        log.warn("  Remove 'demo' from SPRING_PROFILES_ACTIVE before this instance holds");
        log.warn("  anything real. Seeding is idempotent, so removing it leaves what was");
        log.warn("  already seeded in place — that has to be cleared deliberately.");
        log.warn("");
    }
}
