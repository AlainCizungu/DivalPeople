package ai.dival.dip.modules.tenants;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * What decides whose credit records a stranger can eventually read.
 *
 * <p>Plain JUnit, no Spring, no database, no identity provider — which is the point of having made
 * these rules static. This class is where the awkward addresses live, and every one of them was
 * cheaper to find here than in a browser against a deployed realm.
 */
class JoiningRulesTest {

    @Nested
    @DisplayName("the domain of an address")
    class DomainOf {

        @Test
        @DisplayName("is the part after the at-sign, lower-cased")
        void takesTheDomain() {
            assertThat(JoiningRules.domainOf("Alice.Mbala@Vodacom.CD"))
                    .contains("vodacom.cd");
        }

        @Test
        @DisplayName("survives the whitespace a paste brings with it")
        void trims() {
            assertThat(JoiningRules.domainOf("  alice@vodacom.cd \n"))
                    .contains("vodacom.cd");
        }

        /**
         * The one that matters.
         *
         * <p>With two at-signs, {@code indexOf} and {@code lastIndexOf} disagree, and code that
         * uses the first would read {@code a@vodacom.cd@attacker.example} as belonging to Vodacom.
         * Refusing anything with more than one at-sign is what makes that impossible rather than
         * merely unlikely.
         */
        @Test
        @DisplayName("refuses an address with two at-signs rather than picking one")
        void refusesTwoAtSigns() {
            assertThat(JoiningRules.domainOf("alice@vodacom.cd@attacker.example")).isEmpty();
            assertThat(JoiningRules.domainOf("alice@attacker.example@vodacom.cd")).isEmpty();
        }

        @ParameterizedTest
        @DisplayName("refuses what cannot be an address")
        @ValueSource(strings = {
            "",
            "   ",
            "alice",
            "@vodacom.cd",
            "alice@",
            "alice@cd",
            "alice@.cd",
            "alice@vodacom.",
            "alice@voda..cd",
            "alice@voda com.cd",
        })
        void refusesRubbish(String candidate) {
            assertThat(JoiningRules.domainOf(candidate)).isEmpty();
        }

        @Test
        @DisplayName("refuses null without throwing, because a token may simply have no email")
        void toleratesNull() {
            assertThat(JoiningRules.domainOf(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("a domain an operator types")
    class NormaliseDomain {

        @ParameterizedTest
        @DisplayName("accepts what a person will actually paste")
        @ValueSource(strings = {
            "vodacom.cd",
            "VODACOM.CD",
            "  vodacom.cd  ",
            "@vodacom.cd",
            "vodacom.cd.",
            "@VODACOM.CD.",
        })
        void normalises(String typed) {
            assertThat(JoiningRules.normaliseDomain(typed)).contains("vodacom.cd");
        }

        @ParameterizedTest
        @DisplayName("refuses what is not a domain")
        @ValueSource(strings = {"", "   ", "vodacom", "alice@vodacom.cd", "voda com.cd", ".cd"})
        void refuses(String typed) {
            assertThat(JoiningRules.normaliseDomain(typed)).isEmpty();
        }
    }

    @Nested
    @DisplayName("whether a domain can identify an institution at all")
    class FreeMail {

        @ParameterizedTest
        @DisplayName("refuses free mail providers, because they identify nobody")
        @ValueSource(strings = {"gmail.com", "yahoo.fr", "outlook.com", "icloud.com", "proton.me"})
        void refusesFreeMail(String domain) {
            assertThat(JoiningRules.canIdentifyAnInstitution(domain)).isFalse();
        }

        @Test
        @DisplayName("accepts a company domain")
        void acceptsCompany() {
            assertThat(JoiningRules.canIdentifyAnInstitution("vodacom.cd")).isTrue();
            assertThat(JoiningRules.canIdentifyAnInstitution("rawbank.cd")).isTrue();
        }

        /**
         * Mapping gmail.com would not be a slightly wrong configuration. It would put every Gmail
         * user on earth inside one institution's book, and it would look like the product working
         * — which is why this is refused rather than warned about.
         */
        @Test
        @DisplayName("the refusal list is checked after normalising, not before")
        void refusesFreeMailWhateverTheCase() {
            String normalised = JoiningRules.normaliseDomain("  @GMail.COM ").orElseThrow();
            assertThat(JoiningRules.canIdentifyAnInstitution(normalised)).isFalse();
        }
    }

    @Nested
    @DisplayName("matching an address against a mapped domain")
    class Matching {

        @Test
        @DisplayName("matches its own domain")
        void matches() {
            assertThat(JoiningRules.matches("vodacom.cd", "vodacom.cd")).isTrue();
        }

        /**
         * The attack a suffix match would allow, written out.
         *
         * <p>{@code payroll@vodacom.cd.attacker.example} ends with a string that ends with
         * {@code vodacom.cd}. Anybody who can register a domain can register that one, and a
         * suffix match would hand them an account inside Vodacom's records. Exact matching makes
         * the whole class of attack impossible rather than requiring the anchoring to be right.
         */
        @Test
        @DisplayName("refuses a domain that merely ends with the mapped one")
        void refusesSuffix() {
            String hostile = JoiningRules.domainOf("payroll@vodacom.cd.attacker.example")
                    .orElseThrow();
            assertThat(JoiningRules.matches("vodacom.cd", hostile)).isFalse();
        }

        /**
         * A subdomain is a different domain. Some of them were delegated to a contractor years ago
         * and nobody remembers; if an institution needs one, that is a second row somebody adds on
         * purpose.
         */
        @Test
        @DisplayName("refuses a subdomain of the mapped domain")
        void refusesSubdomain() {
            assertThat(JoiningRules.matches("vodacom.cd", "mail.vodacom.cd")).isFalse();
        }

        @Test
        @DisplayName("refuses the mapped domain as a subdomain of the address")
        void refusesTheOtherDirection() {
            assertThat(JoiningRules.matches("mail.vodacom.cd", "vodacom.cd")).isFalse();
        }

        @Test
        @DisplayName("refuses a lookalike")
        void refusesLookalike() {
            assertThat(JoiningRules.matches("vodacom.cd", "vodacom.com")).isFalse();
            assertThat(JoiningRules.matches("vodacom.cd", "v0dacom.cd")).isFalse();
        }

        @Test
        @DisplayName("never matches nothing")
        void refusesNulls() {
            assertThat(JoiningRules.matches(null, "vodacom.cd")).isFalse();
            assertThat(JoiningRules.matches("vodacom.cd", null)).isFalse();
        }

        /**
         * The end-to-end shape of the decision, in one test: an address arrives, its domain is
         * taken, and it is compared to what an operator typed. Both sides are normalised, so a
         * mapping entered as "@Vodacom.CD " matches an address written "Alice@VODACOM.cd".
         */
        @Test
        @DisplayName("an address and a mapping meet after both are normalised")
        void endToEnd() {
            Optional<String> mapped = JoiningRules.normaliseDomain("@Vodacom.CD ");
            Optional<String> address = JoiningRules.domainOf("Alice@VODACOM.cd");

            assertThat(mapped).isPresent();
            assertThat(address).isPresent();
            assertThat(JoiningRules.matches(mapped.orElseThrow(), address.orElseThrow())).isTrue();
        }
    }
}
