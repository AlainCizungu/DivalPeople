package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.risk.RiskBand;
import ai.dival.dip.modules.risk.RiskFactor;
import ai.dival.dip.modules.risk.RiskFactorCode;
import ai.dival.dip.modules.risk.RiskRating;
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

/**
 * The indicator where it meets real records.
 *
 * <p>The model's own arithmetic is checked next door without a database, which is where arithmetic
 * belongs. What is checked here is the half the model cannot see: that the facts handed to it are
 * the facts the exchange was willing to disclose, and no others.
 *
 * <p>That is the whole risk in attaching a score to this response. A number is a fine-grained
 * function of its inputs, and a caller who can watch it move over time can difference it back into
 * whatever moved. So a record suppressed by a dispute must not shift the indicator any more than
 * it may show its status — otherwise the dispute the exchange hides is legible in the score, and
 * the suppression is decorative.
 */
@RequiresDocker
class RiskIndicatorOnInquiryTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private SubjectRightsService rights;
    @Autowired
    private ExchangeService exchange;

    private UUID vodacom;
    private UUID orange;
    private UUID bank;
    private String rccm;

    @BeforeEach
    void setUp() {
        vodacom = tenants.save(new Tenant("Risk A", "risk-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        orange = tenants.save(new Tenant("Risk B", "risk-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        bank = tenants.save(new Tenant("Risk Bank", "risk-c-" + UUID.randomUUID(),
                Tenant.Edition.BANKING, "fr")).getId();
        rccm = "CD/KIN/RCCM/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a confirmed match carries an indicator, its factors and the model that made it")
    void aMatchIsAssessed() {
        declare(vodacom, 400);

        InquiryResult result = ask();

        assertThat(result.indicator()).isNotNull();
        assertThat(result.indicator().modelVersion()).isEqualTo("DIP-RI-1");
        assertThat(result.indicator().factors())
                .as("every factor, every time, so two assessments can be compared")
                .hasSize(RiskFactorCode.values().length);
    }

    @Test
    @DisplayName("nothing matched, nothing assessed")
    void noMatchCarriesNoIndicator() {
        // An indicator on a no-match would be a number computed from an empty set, and a caller
        // could tell a matched-but-clean subject from an unknown one by whether one arrived.
        InquiryResult result = TenantContext.runAsResult(bank, () -> exchange.inquire(
                new InquiryRequest(
                        List.of(new InquiryRequest.SubmittedIdentifier(
                                IdentifierType.RCCM, "CD/KIN/RCCM/" + UUID.randomUUID())),
                        null, "Credit application, file 4471"), UUID.randomUUID()));

        assertThat(result.outcome()).isEqualTo(InquiryResult.Outcome.NO_MATCH);
        assertThat(result.indicator()).isNull();
    }

    @Test
    @DisplayName("a debt over a year old at two institutions reads as elevated")
    void theCaseTheProductExistsFor() {
        declare(vodacom, 400);
        declare(orange, 40);

        InquiryResult result = ask();

        // 25 unpaid + 30 for the older of the two + 10 for the second institution. The bank
        // learns that two participants are owed and that something is over a year old, and does
        // not learn which two or how much — the same trade the institution count makes.
        assertThat(result.indicator().score()).isEqualTo(65);
        assertThat(result.indicator().band()).isEqualTo(RiskBand.ELEVATED);
        assertThat(result.indicator().principalDrivers())
                .containsExactly(RiskFactorCode.DEBT_AGING, RiskFactorCode.PAYMENT_BEHAVIOUR);
    }

    @Test
    @DisplayName("the age weighed is the oldest unpaid one, not the oldest one")
    void settledRecordsDoNotAge() {
        UUID old = declare(vodacom, 900);
        TenantContext.runAs(vodacom, () -> debtRecords.settle(old, null));
        declare(orange, 10);

        InquiryResult result = ask();

        // A company that fell behind two years ago and paid, and fell behind again last week, is
        // a company that is ten days late. Weighing the settled record's date would report it as
        // two years late for the rest of the retention period.
        RiskFactor aging = factor(result, RiskFactorCode.DEBT_AGING);
        assertThat(aging.rating()).isEqualTo(RiskRating.LOW);
        assertThat(aging.points()).isEqualTo(5);
    }

    @Test
    @DisplayName("a suppressed record moves the indicator no more than it shows its status")
    void aDisputeIsInvisibleToTheIndicator() {
        UUID settled = declare(vodacom, 100);
        TenantContext.runAs(vodacom, () -> debtRecords.settle(settled, null));
        declare(orange, 900);

        InquiryResult before = ask();

        TenantContext.runAs(orange, () -> rights.raise(
                SubjectRequestType.DISPUTE, IdentifierType.RCCM, rccm,
                "This debt was settled in March.", null));

        InquiryResult after = ask();

        // The defect this test exists to prevent. Orange's record is taken out of the answer the
        // moment it is contested; if it went on feeding the score, a bank watching the number
        // would see it fall on exactly the day a dispute was raised, and the suppression that
        // protects the company would be the thing that announces it.
        assertThat(before.indicator().score()).isGreaterThan(after.indicator().score());
        assertThat(after.indicator().score())
                .as("what is left is one settled record and nothing else")
                .isEqualTo(5);
        assertThat(factor(after, RiskFactorCode.DISPUTE_HISTORY).rating())
                .as("and the table still refuses to assess disputes at all")
                .isEqualTo(RiskRating.NOT_ASSESSED);
    }

    @Test
    @DisplayName("exposure goes unassessed however large the amounts are")
    void amountsAreNeverWeighed() {
        declare(vodacom, 400);

        // Both real operator exports carry an amount column with no stated currency. Until
        // somebody confirms it, weighting it would make every assessment potentially wrong by a
        // factor of about 2,800 while looking entirely reasonable.
        assertThat(factor(ask(), RiskFactorCode.OUTSTANDING_EXPOSURE).rating())
                .isEqualTo(RiskRating.NOT_ASSESSED);
    }

    private static RiskFactor factor(InquiryResult result, RiskFactorCode code) {
        return result.indicator().factors().stream()
                .filter(f -> f.code() == code)
                .findFirst()
                .orElseThrow(() -> new AssertionError(code + " is missing from the assessment"));
    }

    private UUID declare(UUID operator, int daysOverdue) {
        return TenantContext.runAsResult(operator, () -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(IdentifierType.RCCM, rccm)),
                // Suffixed, because subjects are shared across the exchange and this class is not
                // transactional: an unsuffixed fixture name outlives it in the registry.
                "Kivu Logistique " + rccm.substring(rccm.length() - 8),
                Subject.SubjectType.BUSINESS, null, "CD",
                new BigDecimal("500.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(daysOverdue), true), null).record().getId());
    }

    /** Asked by the bank, which holds no record of its own. */
    private InquiryResult ask() {
        return TenantContext.runAsResult(bank, () -> exchange.inquire(new InquiryRequest(
                List.of(new InquiryRequest.SubmittedIdentifier(IdentifierType.RCCM, rccm)),
                null, "Credit application, file 4471"), UUID.randomUUID()));
    }
}
