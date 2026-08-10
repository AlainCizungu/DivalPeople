package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * The one number the exchange discloses, and everything it must not disclose alongside it.
 *
 * <p>An enquiring operator learns <em>how many</em> participants hold an obligation against this
 * subject and never which, never how much, never since when. That trade is the reason a competitor
 * would join a registry run by people it competes with, so the count has to be right and the
 * silence around it has to be complete.
 *
 * <p>It was neither. The screen showed the number of distinct <em>statuses</em> under a label
 * promising institutions, so two operators both reporting an outstanding debt displayed as one.
 * Wrong in the direction that understates risk, which is the worse direction for a credit
 * decision, and invisible because a set of one status and a set of one operator look identical.
 */
@RequiresDocker
class InstitutionCountTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private SubjectRightsService subjectRights;
    @Autowired
    private ExchangeService exchange;

    private UUID vodacom;
    private UUID orange;
    private UUID bank;
    private String rccm;

    @BeforeEach
    void setUp() {
        vodacom = tenants.save(new Tenant("Count A", "cnt-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        orange = tenants.save(new Tenant("Count B", "cnt-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        bank = tenants.save(new Tenant("Count Bank", "cnt-c-" + UUID.randomUUID(),
                Tenant.Edition.BANKING, "fr")).getId();
        rccm = "CD/KIN/RCCM/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("two operators reporting one company are counted as two, though their statuses are one")
    void twoOperatorsAreTwo() {
        declare(vodacom);
        declare(orange);

        InquiryResult result = ask();

        // The assertion the old code would have failed. Both records are OUTSTANDING, so the
        // status set has one element — and the screen was showing that number under a label
        // promising a count of institutions.
        assertThat(result.statuses()).hasSize(1);
        assertThat(result.institutionCount())
                .as("two institutions report this company, and the enquirer is entitled to know that")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("one operator reporting is one")
    void oneOperatorIsOne() {
        declare(vodacom);

        // The bank asking here reports nothing itself, so the one is Vodacom. The count is of
        // everyone holding a record, the enquirer included when it holds one — see the three-operator
        // case below, where the bank is one of the three.
        assertThat(ask().institutionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a suppressed record raises the count no more than it shows its status")
    void suppressedRecordsAreNotCounted() {
        declare(vodacom);
        declare(orange);

        // A dispute suppresses the record at every operator from the moment it is raised, before
        // anybody decides who is right. If the count kept including it, the enquirer would be told
        // that two institutions report a debt while being shown one status — and the number is the
        // half that carries the weight.
        TenantContext.runAs(orange, () -> subjectRights.raise(
                SubjectRequestType.DISPUTE, IdentifierType.RCCM, rccm,
                "This debt was settled in March.", null));

        InquiryResult afterDispute = ask();

        assertThat(afterDispute.institutionCount())
                .as("the disputed record stopped counting the day it was contested")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a third operator is a third institution, not a third record")
    void theCountIsOfOperatorsNotRecords() {
        declare(vodacom);
        declare(orange);
        declare(bank);

        assertThat(ask().institutionCount()).isEqualTo(3);
        assertThat(ask().statuses()).hasSize(1);
    }

    @Test
    @DisplayName("no match and review required disclose nothing, including the count")
    void nothingIsDisclosedWithoutAMatch() {
        // Zero rather than absent, and it says the same thing: an answer that carries no subject
        // must carry no count either, or the count becomes a way to probe for one.
        assertThat(InquiryResult.noMatch().institutionCount()).isZero();
        assertThat(InquiryResult.reviewRequired().institutionCount()).isZero();
    }

    @Test
    @DisplayName("the count is all that is disclosed: no operator is named anywhere in the answer")
    void theAnswerNamesNoOperator() {
        declare(vodacom);
        declare(orange);

        InquiryResult result = ask();

        // The whole trade in one assertion. The response is a record with four other components,
        // and none of them may carry a tenant. If a future field ever does, this fails.
        assertThat(result.toString())
                .as("an operator id in the response would identify a rival's customer relationship")
                .doesNotContain(vodacom.toString())
                .doesNotContain(orange.toString());
    }

    private void declare(UUID operator) {
        TenantContext.runAs(operator, () -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(IdentifierType.RCCM, rccm)),
                "Trans-Congo Négoce", Subject.SubjectType.BUSINESS, null, "CD",
                new BigDecimal("500.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(30), true), null));
    }

    /** Asked by the bank, which reports nothing itself unless a test says otherwise. */
    private InquiryResult ask() {
        return TenantContext.runAsResult(bank, () -> exchange.inquire(new InquiryRequest(
                List.of(new InquiryRequest.SubmittedIdentifier(IdentifierType.RCCM, rccm)),
                null, "Credit application, file 4471"), UUID.randomUUID()));
    }
}
