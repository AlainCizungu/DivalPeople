package ai.dival.dip.modules.tix;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Exchange settings that a deployment must be able to change without a rebuild.
 *
 * <p>{@code minimumDeclarable} is the reporting threshold from the March 2026 terms of reference:
 * only obligations at or above a floor may enter the registry. It matters more than it looks. The
 * floor is the whole proportionality argument for the scheme — a national bad-payer registry that
 * accepts a two-dollar dispute is not a credit-risk instrument, it is a punishment, and it is the
 * version a regulator closes. The TDR suggests 100 USD.
 *
 * <p>It is a map per currency, and a currency with no configured floor is <strong>refused</strong>
 * rather than defaulted. That is deliberate, and it is the only honest option available here.
 * Records carry their own currency, the TDR states the threshold in USD alone, and converting CDF
 * to USD requires a rate — which moves, which somebody must own, and which this application has no
 * business inventing. A hardcoded CDF figure would be a made-up exchange rate wearing the costume
 * of a constant, and it would silently drift out of date while continuing to look authoritative.
 * Refusing forces the decision into the open, where it belongs: someone accountable sets the CDF
 * floor and revisits it.
 *
 * @param minimumDeclarable currency code to smallest amount that may be declared, inclusive
 * @param retention        how long a record survives before it is erased
 */
@ConfigurationProperties(prefix = "dip.tix")
public record TixProperties(Map<String, BigDecimal> minimumDeclarable, Retention retention) {

    /**
     * Retention periods, from the terms of reference.
     *
     * <p>Configurable because these are legal periods, not engineering constants: they are the
     * output of the feasibility study's analysis of the Code du numérique, and they will change
     * when a regulator says so. A number compiled into a class is a number nobody can correct
     * without a release, which is how systems end up holding data past the period they are
     * allowed to.
     *
     * <p><strong>Five years, on counsel's advice of August 2026.</strong> The defaults were the
     * TDR's illustrative three and five and had never been checked against the Code; asked
     * directly, counsel answered five. So both periods are five, which means the distinction
     * between a first default and a repeat one currently changes nothing — the fields remain
     * because a deployment may yet be told to differentiate, and collapsing them into one setting
     * would make reinstating the difference a schema change rather than a configuration one.
     *
     * <p>{@code settledDays} was not asked about and is still an unchecked placeholder. It is
     * reported as one on the settings screen, beside two figures that are no longer placeholders,
     * which is the only reason the distinction is worth carrying.
     *
     * @param simpleYears  a first, unsettled default. Five, per counsel
     * @param repeatYears  a default by somebody the exchange has seen default before. Also five,
     *                     so this presently decides nothing
     * @param settledDays  how long a regularised debt survives after settlement. The TDR asks for
     *                     erasure on regularisation; a short window rather than zero exists so the
     *                     settling operator can reconcile, and it can only ever shorten a
     *                     record's life, never extend it. Boxed deliberately — zero is a
     *                     meaningful setting here (erase at the next sweep), so it must be
     *                     distinguishable from "nobody configured this", which a primitive int
     *                     would silently conflate.
     */
    public record Retention(int simpleYears, int repeatYears, Integer settledDays) {

        public Retention {
            simpleYears = simpleYears > 0 ? simpleYears : 5;
            repeatYears = repeatYears > 0 ? repeatYears : 5;
            settledDays = settledDays == null || settledDays < 0 ? 30 : settledDays;
        }

        static Retention defaults() {
            return new Retention(5, 5, 30);
        }
    }

    public TixProperties {
        retention = retention == null ? Retention.defaults() : retention;
        Map<String, BigDecimal> normalized = new LinkedHashMap<>();
        if (minimumDeclarable != null) {
            minimumDeclarable.forEach((currency, floor) -> {
                if (currency != null && floor != null) {
                    normalized.put(currency.trim().toUpperCase(Locale.ROOT), floor);
                }
            });
        }
        minimumDeclarable = Map.copyOf(normalized);
    }

    /** The floor for a currency, or empty when none is configured — never a guess. */
    public java.util.Optional<BigDecimal> floorFor(String currency) {
        if (currency == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(
                minimumDeclarable.get(currency.trim().toUpperCase(Locale.ROOT)));
    }

    /** Currencies this deployment will accept, for an error message that can be acted on. */
    public java.util.Set<String> configuredCurrencies() {
        return minimumDeclarable.keySet();
    }
}
