package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two positions of the disclosure switch, and the combination that must not exist.
 *
 * <p>No database and no Docker. Pure logic, so it is tested as pure logic — the rule this codebase
 * keeps rather than reaching for a mock.
 */
class DisclosurePropertiesTest {

    @Test
    @DisplayName("the shape a deployment that configured nothing gets discloses nothing")
    void nothingConfiguredDisclosesNothing() {
        DisclosureProperties shipped = DisclosureProperties.countOnly();

        assertThat(shipped.canName()).isFalse();
        assertThat(shipped.canPrice()).isFalse();
    }

    @Test
    @DisplayName("amounts alone do nothing: pricing without naming is not a safer disclosure")
    void amountsWithoutNamesAreRefused() {
        // The combination somebody reaches for when they want the numbers without the
        // awkwardness. It is the same disclosure with the labels one screen away — a reader with
        // two inquiries and a subtraction recovers them — so the switch simply does not offer it.
        DisclosureProperties pricedButAnonymous = new DisclosureProperties(false, true);

        assertThat(pricedButAnonymous.canName()).isFalse();
        assertThat(pricedButAnonymous.canPrice())
                .as("an itemised list of anonymous amounts is not an anonymous list")
                .isFalse();
    }

    @Test
    @DisplayName("naming without pricing is the coherent middle setting, and is offered")
    void namesWithoutAmountsIsCoherent() {
        // A lender learns who else is in the room without learning the size of anybody's
        // position. Unlike the combination above, this one is a real answer to a real question.
        DisclosureProperties named = new DisclosureProperties(true, false);

        assertThat(named.canName()).isTrue();
        assertThat(named.canPrice()).isFalse();
    }

    @Test
    @DisplayName("both on is both on")
    void bothOn() {
        DisclosureProperties full = new DisclosureProperties(true, true);

        assertThat(full.canName()).isTrue();
        assertThat(full.canPrice()).isTrue();
    }
}
