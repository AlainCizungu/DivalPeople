package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * An operator's own book, and the two ways a portfolio view lies.
 *
 * <p>The first is showing somebody else's money. The second is adding currencies together, which
 * produces a number that is wrong and looks completely normal — there is no formatting, no
 * exception and no visible symptom, just a total that is not of anything.
 *
 * <p>Not {@code @Transactional}: declaration and settlement commit, and several tests act as two
 * operators in turn. Isolation comes from fresh tenants and randomised documents per test.
 *
 * <p>A CDF floor is configured here and deliberately not in {@code application.yml} — production
 * refuses a currency whose reporting threshold nobody has decided, and that stays true. The figure
 * below is a test fixture, not a policy.
 */
@RequiresDocker
@TestPropertySource(properties = {
        // Bracket notation: it is the form Spring Boot documents for map keys and the one that
        // does not go through relaxed-binding case handling.
        "dip.tix.minimum-declarable[USD]=100",
        "dip.tix.minimum-declarable[CDF]=250000",
})
class PortfolioTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecordService;
    @Autowired
    private PortfolioService portfolio;

    private UUID operatorA;
    private UUID operatorB;

    @BeforeEach
    void setUp() {
        operatorA = tenants.save(new Tenant("Portfolio A", "pf-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        operatorB = tenants.save(new Tenant("Portfolio B", "pf-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // --- fixtures -----------------------------------------------------------

    private UUID declare(UUID operator, String amount, String currency, long daysOverdue) {
        return TenantContext.runAsResult(operator, () -> debtRecordService.declare(
                new DeclarationRequest(
                        List.of(new DeclarationRequest.SubmittedIdentifier(
                                IdentifierType.NATIONAL_ID, "CD-" + UUID.randomUUID())),
                        "Société Kabila SARL", Subject.SubjectType.BUSINESS, null, "CD",
                        new BigDecimal(amount), currency, "POSTPAID",
                        LocalDate.now().minusDays(daysOverdue), true),
                null).record().getId());
    }

    private PortfolioService.Summary summaryFor(UUID operator, LocalDate asOf) {
        return TenantContext.runAsResult(operator, () -> portfolio.summarise(asOf));
    }

    private PortfolioService.Summary summaryFor(UUID operator) {
        return summaryFor(operator, LocalDate.now());
    }

    private static PortfolioService.CurrencyExposure exposure(PortfolioService.Summary summary,
                                                              String currency) {
        return summary.exposure().stream()
                .filter(entry -> entry.currency().equals(currency))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no exposure in " + currency));
    }

    private static PortfolioService.Band band(PortfolioService.Summary summary, AgingBand which) {
        return summary.aging().stream()
                .filter(entry -> entry.band() == which)
                .findFirst()
                .orElseThrow(() -> new AssertionError("band missing: " + which));
    }

    // --- the boundary -------------------------------------------------------

    @Test
    @DisplayName("a portfolio contains only the calling operator's records")
    void neverAnotherOperatorsBook() {
        declare(operatorA, "500.00", "USD", 10);
        declare(operatorB, "900.00", "USD", 10);

        // The whole value of the exchange is that operators share statuses and not balances. A
        // portfolio view is the most natural place for that to leak, because summing is exactly
        // what it does.
        assertThat(summaryFor(operatorA).recordCount()).isEqualTo(1);
        assertThat(exposure(summaryFor(operatorA), "USD").outstanding()).isEqualTo("500.00");
        assertThat(exposure(summaryFor(operatorB), "USD").outstanding()).isEqualTo("900.00");
    }

    // --- money --------------------------------------------------------------

    @Test
    @DisplayName("two currencies stay two totals and are never added together")
    void currenciesAreNotAdded() {
        declare(operatorA, "500.00", "USD", 10);
        declare(operatorA, "600000.00", "CDF", 10);

        PortfolioService.Summary summary = summaryFor(operatorA);

        assertThat(summary.exposure()).hasSize(2);
        assertThat(exposure(summary, "USD").outstanding()).isEqualTo("500.00");
        assertThat(exposure(summary, "CDF").outstanding()).isEqualTo("600000.00");
        // 600500 would be the number an unguarded sum produces, and it would sit on a dashboard
        // looking entirely plausible.
        assertThat(summary.exposure()).extracting(PortfolioService.CurrencyExposure::outstanding)
                .doesNotContain("600500.00");
    }

    @Test
    @DisplayName("amounts add without losing centimes")
    void amountsAreExact() {
        declare(operatorA, "100.01", "USD", 10);
        declare(operatorA, "200.02", "USD", 10);

        // BigDecimal throughout. The same addition in doubles gives 300.02999999999997.
        assertThat(exposure(summaryFor(operatorA), "USD").outstanding()).isEqualTo("300.03");
    }

    @Test
    @DisplayName("contested money is shown apart from money nobody is arguing about")
    void disputedIsNotOutstanding() {
        UUID contested = declare(operatorA, "500.00", "USD", 10);
        declare(operatorA, "300.00", "USD", 10);
        TenantContext.runAs(operatorA, () -> debtRecordService.dispute(contested, null));

        PortfolioService.CurrencyExposure usd = exposure(summaryFor(operatorA), "USD");

        // Still the operator's money, and still owed as far as it is concerned — but somebody is
        // contesting it, and a total that folded the two together would hide that.
        assertThat(usd.outstanding()).isEqualTo("300.00");
        assertThat(usd.outstandingCount()).isEqualTo(1);
        assertThat(usd.contested()).isEqualTo("500.00");
        assertThat(usd.contestedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("settling moves money to recovered and takes it out of the aging")
    void settledDebtHasNoAge() {
        UUID recordId = declare(operatorA, "500.00", "USD", 200);
        TenantContext.runAs(operatorA, () -> debtRecordService.settle(recordId, null));

        PortfolioService.Summary summary = summaryFor(operatorA);

        assertThat(exposure(summary, "USD").settled()).isEqualTo("500.00");
        assertThat(exposure(summary, "USD").outstanding()).isEqualTo("0");
        // Otherwise a portfolio would look older the more of it had been recovered.
        assertThat(summary.aging()).allMatch(entry -> entry.count() == 0);
    }

    // --- aging --------------------------------------------------------------

    @Test
    @DisplayName("aging runs from the default date, not the date it was reported")
    void ageComesFromTheDefaultDate() {
        declare(operatorA, "500.00", "USD", 200);

        PortfolioService.Summary summary = summaryFor(operatorA);

        // Declared seconds ago, 200 days overdue. An operator that reports late does not thereby
        // make the debt younger — the same rule the retention clock follows.
        assertThat(band(summary, AgingBand.DAYS_270).count()).isEqualTo(1);
        assertThat(band(summary, AgingBand.DAYS_30).count()).isZero();
    }

    @Test
    @DisplayName("every band is returned, including the empty ones")
    void emptyBandsAreStillReported() {
        declare(operatorA, "500.00", "USD", 10);

        PortfolioService.Summary summary = summaryFor(operatorA);

        // The shape of an aging profile is most of its meaning, and a gap in the middle is
        // information. A chart drawn from a list with the zeroes dropped invents its own axis.
        assertThat(summary.aging()).hasSize(AgingBand.values().length);
        assertThat(summary.aging()).extracting(PortfolioService.Band::band)
                .containsExactly(AgingBand.values());
    }

    @Test
    @DisplayName("a band carries its money per currency, not as one figure")
    void bandsAreAlsoPerCurrency() {
        declare(operatorA, "500.00", "USD", 200);
        declare(operatorA, "600000.00", "CDF", 200);

        PortfolioService.Band oldest = band(summaryFor(operatorA), AgingBand.DAYS_270);

        assertThat(oldest.count()).isEqualTo(2);
        assertThat(oldest.amounts())
                .extracting(PortfolioService.BandAmount::currency,
                        PortfolioService.BandAmount::amount)
                .containsExactlyInAnyOrder(
                        tuple("USD", "500.00"),
                        tuple("CDF", "600000.00"));
    }

    // --- retention ----------------------------------------------------------

    @Test
    @DisplayName("a record past its retention date leaves the book and is counted for erasure")
    void expiredRecordsAreNotExposure() {
        declare(operatorA, "500.00", "USD", 30);

        // Six years on: past the five-year period for a first default, and the nightly purge has
        // not yet run. Four years used to be enough and stopped being so when the period moved.
        PortfolioService.Summary later = summaryFor(operatorA, LocalDate.now().plusYears(6));

        assertThat(later.recordCount()).isZero();
        assertThat(later.exposure()).isEmpty();
        // Shown rather than silently dropped: a figure that does not return to zero every morning
        // means the purge has stopped running, and nothing else in the product would say so.
        assertThat(later.awaitingErasure()).isEqualTo(1);
    }

    // --- provenance ---------------------------------------------------------

    @Test
    @DisplayName("records declared through the API do not count as imported")
    void declaredRecordsAreNotImports() {
        declare(operatorA, "500.00", "USD", 10);

        PortfolioService.Summary summary = summaryFor(operatorA);

        // The honest measure of how much of the ingest pipeline reaches the exchange. It is zero
        // today because publishing a batch still derives nothing, and the screen says so out
        // loud instead of leaving a reader to assume the imports are in there somewhere.
        assertThat(summary.importedRecords()).isZero();
        assertThat(summary.recordCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("statuses and service categories are counted for the operator's own records only")
    void breakdownsAreScopedToo() {
        declare(operatorA, "500.00", "USD", 10);
        declare(operatorB, "900.00", "USD", 10);

        PortfolioService.Summary summary = summaryFor(operatorA);

        assertThat(summary.byStatus()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.status()).isEqualTo(DebtStatus.OUTSTANDING);
                    assertThat(entry.count()).isEqualTo(1);
                });
        assertThat(summary.byService()).singleElement()
                .satisfies(entry -> {
                    assertThat(entry.label()).isEqualTo("POSTPAID");
                    assertThat(entry.count()).isEqualTo(1);
                });
    }
}
