package ai.dival.dip.dev;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * What the demo profile is allowed to put on a public host.
 *
 * <p>The rule this defends is one sentence: <strong>seeding data is not seeding logins, and
 * exchange data is not personnel data.</strong> Three seeders answer to {@code demo} — the
 * tenants and the two that fill the exchange — and the other ten seed salaries, national
 * identifiers, leave balances and sign-ins wired to fixture Keycloak accounts. None of that
 * belongs on an instance reachable from the internet, and none of it is what anybody came to see.
 *
 * <p><strong>Why a test rather than a comment.</strong> Adding {@code "demo"} to an annotation is
 * a two-word edit that looks like configuration and is a disclosure decision. It will be made one
 * afternoon by somebody who wants the org chart to be non-empty in a demonstration, and there is
 * no other place in this codebase where that edit would be noticed. Here it fails the build and
 * has to be argued for.
 *
 * <p>Scanned rather than listed, so a <em>new</em> seeder created with the demo profile is caught
 * too. A hardcoded list would only defend the classes that existed when it was written, which is
 * the wrong half of the problem.
 *
 * <p>Needs no database and no Docker. It is a question about annotations.
 */
class DemoProfileScopeTest {

    private static final String PACKAGE = "ai.dival.dip.dev";

    /**
     * Everything the demo profile may switch on.
     *
     * <p>The banner is here because it is also {@code @Profile("demo")} and is the one component
     * that must be, which makes it part of the expected set rather than an exception to it.
     */
    private static final Set<String> ALLOWED = Set.of(
            "DemoProfileBanner",
            "LocalTenantSeeder",
            "LocalTixSeeder",
            "LocalTixPortfolioSeeder");

    @Test
    @DisplayName("only the exchange seeders run under the demo profile")
    void demoProfileSeedsDataAndNeverIdentities() {
        assertThat(componentsAnswering("demo"))
                .as("""
                    A component in %s answers to the 'demo' profile that was not agreed to.

                    That profile runs on a host reachable from the internet. Adding a seeder to it \
                    is a decision about what invented salaries, national identifiers and sign-ins \
                    are published, not a configuration change. If this is deliberate, say so here \
                    and in application-demo.yml.""".formatted(PACKAGE))
                .containsExactlyInAnyOrderElementsOf(ALLOWED);
    }

    @Test
    @DisplayName("every seeder is still restricted to a profile")
    void noSeederRunsUnconditionally() {
        // The other half of the same rule. A seeder that lost its @Profile entirely would pass
        // the test above — it answers to no named profile — and would then run in every
        // environment including production, which is worse than the failure that test describes.
        assertThat(scan())
                .allSatisfy(type -> assertThat(type.getAnnotation(Profile.class))
                        .as("%s has no @Profile and would run everywhere, including prod",
                                type.getSimpleName())
                        .isNotNull());
    }

    private static Set<String> componentsAnswering(String profile) {
        Set<String> answering = new TreeSet<>();
        for (Class<?> type : scan()) {
            Profile annotation = type.getAnnotation(Profile.class);
            if (annotation != null && Arrays.asList(annotation.value()).contains(profile)) {
                answering.add(type.getSimpleName());
            }
        }
        return answering;
    }

    /** Every {@link org.springframework.boot.ApplicationRunner} in the dev package. */
    private static List<Class<?>> scan() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(
                org.springframework.boot.ApplicationRunner.class));

        List<Class<?>> found = scanner.findCandidateComponents(PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(DemoProfileScopeTest::load)
                .toList();

        // A scan that silently found nothing would make both tests pass by vacuity, which is the
        // classic way a guard like this stops guarding without anybody noticing.
        assertThat(found)
                .as("found no ApplicationRunner in %s — the scan is broken, not the code", PACKAGE)
                .isNotEmpty();
        return found;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException impossible) {
            throw new IllegalStateException("scanner found a class it cannot load: " + name,
                    impossible);
        }
    }
}
