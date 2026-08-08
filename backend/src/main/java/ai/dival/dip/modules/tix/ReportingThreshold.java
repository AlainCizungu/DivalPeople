package ai.dival.dip.modules.tix;

import ai.dival.dip.common.error.PolicyRefusedException;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The floor below which a person does not go into the registry at all.
 *
 * <p>From the March 2026 terms of reference: the platform centralises unpaid obligations "at or
 * above a certain threshold, e.g. 100 USD". It reads like a detail and it is closer to the moral
 * centre of the scheme. Every other control here limits what happens to people already in the
 * registry; this is the only one that keeps people out. A shared national bad-payer list that
 * accepts a two-dollar balance is not a credit-risk instrument — it is a way to make somebody
 * unbankable over a rounding error, and it is the version that gets the platform closed.
 *
 * <p>Its own class rather than three lines inside the service, so it can be tested exhaustively
 * without a database or a Spring context. The boundary condition is the whole behaviour: the TDR
 * says <em>at or above</em>, so a debt of exactly the threshold is declarable and one cent below
 * is not. An off-by-one here is invisible in production and decides whether people are in a
 * registry.
 */
@Component
public class ReportingThreshold {

    private final TixProperties properties;

    public ReportingThreshold(TixProperties properties) {
        this.properties = properties;
    }

    /**
     * @throws PolicyRefusedException when the amount is below the floor, or when the currency has
     *         no floor configured — never a silent pass, and never a converted guess
     */
    public void requireDeclarable(BigDecimal amount, String currency) {
        BigDecimal floor = properties.floorFor(currency).orElseThrow(() -> {
            Set<String> known = properties.configuredCurrencies();
            return new PolicyRefusedException(
                    "No reporting threshold is configured for " + currency
                            + ", so nothing may be declared in it. Configured: "
                            + (known.isEmpty() ? "none" : String.join(", ", known))
                            + ". Converting between currencies is a decision for the operators, "
                            + "not for this application.");
        });

        // compareTo, not equals: BigDecimal.equals("100").equals("100.00") is false because it
        // compares scale as well as value, and an operator sending 100.00 must not be refused for
        // sending two decimal places.
        if (amount.compareTo(floor) < 0) {
            throw new PolicyRefusedException(
                    "Below the reporting threshold: " + floor.toPlainString() + " " + currency
                            + " is the minimum that may be declared to the exchange.");
        }
    }
}
