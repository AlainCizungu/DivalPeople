package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.audit.AuditEventRepository;
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
 * The 360° profile <strong>with full disclosure switched on</strong>.
 *
 * <p>This is not the shipped configuration. {@code Subject360Test} is, and it asserts the opposite
 * of everything below. Both classes exist because a switch is only worth having if both of its
 * positions are proven: an off setting nobody tested is a setting that silently stops working, and
 * an on setting nobody tested is a feature that was never built.
 *
 * <p>Turning it on here is a property override on a test class. Turning it on in a deployment
 * requires an answer from counsel and the agreement of the participants whose books become visible
 * — see {@link DisclosureProperties}. Nothing in this file is an argument that it should be.
 *
 * <p><strong>The audit assertion is the one that matters most.</strong> A disclosure that leaves no
 * trace is the failure this module is built to prevent, and the inquiry's own audit row cannot
 * serve as the trace: it is written identically whether or not names went out with the answer.
 */
@RequiresDocker
@TestPropertySource(properties = {
        "dip.disclosure.name-institutions=true",
        "dip.disclosure.disclose-amounts=true"})
class NamedContributorsTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private Subject360Service profiles;
    @Autowired
    private AuditEventRepository auditEvents;

    private UUID vodacom;
    private UUID orange;
    private String rccm;
    private UUID subject;

    @BeforeEach
    void setUp() {
        vodacom = tenants.save(new Tenant("Named Vodacom", "nmd-v-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        orange = tenants.save(new Tenant("Named Orange", "nmd-o-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        rccm = "CD/KIN/RCCM/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("with the switch on, the operators are named and their positions are priced")
    void namedAndPriced() {
        declare(vodacom, "500.00");
        declare(orange, "1200.00");

        Subject360Service.Subject360 view = openAs(vodacom);

        assertThat(view.contributors()).hasSize(2);
        assertThat(view.contributors()).extracting(InquiryResult.Contributor::institution)
                .containsExactlyInAnyOrder("Named Vodacom", "Named Orange");
        assertThat(view.contributors()).extracting(InquiryResult.Contributor::owed)
                .containsExactlyInAnyOrder("500.00", "1200.00");
        assertThat(view.contributorsWithheld())
                .as("nothing was withheld, so the screen must not say anything was")
                .isFalse();
        assertThat(view.overview().marketExposure())
                .as("summed from the rows beneath it, so it cannot disagree with them")
                .isEqualTo("1700.00");
    }

    @Test
    @DisplayName("naming a competitor writes its own audit row, separate from the inquiry's")
    void theDisclosureIsAudited() {
        declare(vodacom, "500.00");
        declare(orange, "1200.00");

        long before = disclosureRows();
        openAs(vodacom);

        assertThat(disclosureRows())
                .as("an operator that found out this switch had been on would want to count what "
                        + "left, and the inquiry row cannot answer that — it is written the same "
                        + "way whether names went out or not")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("the count and the named list agree, because they come from one filtered pass")
    void theListAgreesWithTheCount() {
        declare(vodacom, "500.00");
        declare(orange, "1200.00");

        Subject360Service.Subject360 view = openAs(vodacom);

        // The failure worth preventing: a list assembled in a second pass would have to reproduce
        // the dispute filter, the expiry filter and the status filter, and a contributor list that
        // disagreed with the number printed beside it is worse than no list at all.
        assertThat(view.contributors()).hasSize(view.overview().institutionCount());
    }

    @Test
    @DisplayName("a settled position is named without an amount, not named with a zero")
    void settledPositionsCarryNoFigure() {
        declare(vodacom, "500.00");
        UUID orangeRecord = declareReturning(orange, "1200.00");
        TenantContext.runAs(orange, () -> debtRecords.settle(orangeRecord, null));

        Subject360Service.Subject360 view = openAs(vodacom);

        InquiryResult.Contributor settled = view.contributors().stream()
                .filter(contributor -> "Named Orange".equals(contributor.institution()))
                .findFirst()
                .orElseThrow();

        assertThat(settled.owed())
                .as("owed nothing and amount withheld must not be told apart by reading a 0")
                .isNull();
        assertThat(settled.records())
                .as("the record still counts toward the shape of the relationship")
                .isEqualTo(1);
        assertThat(view.overview().marketExposure())
                .as("a partial total presented as a total is invisible on screen and wrong")
                .isNull();
    }

    private long disclosureRows() {
        return auditEvents.findAll().stream()
                .filter(event -> ExchangeService.CONTRIBUTORS_DISCLOSED.equals(event.getAction()))
                .count();
    }

    private Subject360Service.Subject360 openAs(UUID operator) {
        return TenantContext.runAsResult(operator,
                () -> profiles.assemble(subject, "Credit application, file 4471", null));
    }

    private void declare(UUID operator, String amount) {
        declareReturning(operator, amount);
    }

    private UUID declareReturning(UUID operator, String amount) {
        DebtRecord record = TenantContext.runAsResult(operator, () -> debtRecords.declare(
                new DeclarationRequest(
                        List.of(new DeclarationRequest.SubmittedIdentifier(IdentifierType.RCCM,
                                rccm)),
                        "Trans-Congo Named " + rccm.substring(rccm.length() - 8),
                        Subject.SubjectType.BUSINESS, null, "CD",
                        new BigDecimal(amount), "USD", "POSTPAID",
                        LocalDate.now().minusDays(30), true), null).record());
        subject = record.getSubject().getId();
        return record.getId();
    }
}
