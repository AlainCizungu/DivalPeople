package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.PolicyRefusedException;
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
 * Somebody stops pursuing their case, which is not a decision about it.
 *
 * <p>WITHDRAWN existed as a status from the first migration and nothing ever set it. A person who
 * changed their mind left a case open on a queue with a statutory deadline it would go on to miss,
 * waiting for a ruling nobody had any basis to make.
 *
 * <p>The reason this needed care rather than a status update: raising a dispute suppresses the
 * records immediately, before anybody weighs it. Withdrawing without lifting that would turn a
 * protection into a hole — dispute a record that is entirely true, walk away, and it stays out of
 * the exchange permanently with no decision anybody can appeal. Every test below is really about
 * that.
 */
@RequiresDocker
class WithdrawalTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private SubjectRightsService rights;
    @Autowired
    private ExchangeService exchange;

    /**
     * The member of staff handling the case.
     *
     * <p>Not null, and the first version of this test learned why the hard way: it passed null and
     * the failure came from the setup, where close() refuses a decision that names nobody. The
     * withdrawal path now refuses one too, for the same reason and a sharper one — it is the only
     * way to close a case without ruling on it.
     */
    private static final UUID STAFF = UUID.randomUUID();

    private UUID operator;
    private UUID enquirer;
    private String rccm;

    @BeforeEach
    void setUp() {
        operator = tenants.save(new Tenant("Withdraw A", "wd-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        enquirer = tenants.save(new Tenant("Withdraw Bank", "wd-b-" + UUID.randomUUID(),
                Tenant.Edition.BANKING, "fr")).getId();
        rccm = "CD/KIN/RCCM/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("withdrawing puts back the records the dispute took out of the exchange")
    void withdrawingLiftsTheSuppression() {
        declare();
        UUID caseId = raiseDispute();

        // The state the hole would live in: suppressed, undecided, and nobody pursuing it.
        assertThat(ask().outcome()).isEqualTo(InquiryResult.Outcome.CLEAR);

        TenantContext.runAs(operator, () ->
                rights.withdraw(caseId, "Called on 14 May; settled directly with us.", STAFF));

        assertThat(ask().outcome())
                .as("a true record does not stay out of the exchange because somebody walked away")
                .isEqualTo(InquiryResult.Outcome.OUTSTANDING_DEBT);
    }

    @Test
    @DisplayName("the case is closed, and closed as withdrawn rather than as a finding")
    void theStatusSaysWhatHappened() {
        declare();
        UUID caseId = raiseDispute();

        SubjectRequest withdrawn = TenantContext.runAsResult(operator, () ->
                rights.withdraw(caseId, "Called on 14 May; settled directly with us.", STAFF));

        // Not UPHELD and not REFUSED. Recording an abandonment as a refusal would say the operator
        // considered the complaint and rejected it, which is a claim nobody made.
        assertThat(withdrawn.getStatus()).isEqualTo(SubjectRequestStatus.WITHDRAWN);
    }

    @Test
    @DisplayName("a withdrawal has to say how the person asked for it")
    void aWithdrawalNeedsANote() {
        declare();
        UUID caseId = raiseDispute();

        // This is the one way to close a case without deciding it, so an operator with a full
        // queue and a deadline has an obvious use for it. The note is what separates a withdrawal
        // from a case quietly dropped.
        assertThatThrownBy(() -> TenantContext.runAsResult(operator, () ->
                rights.withdraw(caseId, "   ", STAFF)))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("how the person told you");
    }

    @Test
    @DisplayName("a withdrawal has to name who recorded it")
    void aWithdrawalNeedsAnActor() {
        declare();
        UUID caseId = raiseDispute();

        assertThatThrownBy(() -> TenantContext.runAsResult(operator, () ->
                rights.withdraw(caseId, "Called and asked to drop it.", null)))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("who recorded it");
    }

    @Test
    @DisplayName("a decided case cannot be withdrawn afterwards")
    void aDecidedCaseStaysDecided() {
        declare();
        UUID caseId = raiseDispute();

        TenantContext.runAs(operator, () -> {
            rights.verifyIdentity(caseId, "National ID seen in person, photograph matches.", STAFF);
            rights.close(caseId, false, "The debt is owed and the invoices were produced.", STAFF);
        });

        // Otherwise withdrawal becomes a way to erase a finding somebody is accountable for.
        assertThatThrownBy(() -> TenantContext.runAsResult(operator, () ->
                rights.withdraw(caseId, "Called and asked to drop it.", STAFF)))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("decided");
    }

    private void declare() {
        TenantContext.runAs(operator, () -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(IdentifierType.RCCM, rccm)),
                // Suffixed because subjects are shared and these tests are not transactional:
                // an unsuffixed fixture name stays in the registry and can resolve somebody
                // else's inquiry test.
                "Trans-Congo Négoce " + rccm.substring(rccm.length() - 8),
                Subject.SubjectType.BUSINESS, null, "CD",
                new BigDecimal("500.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(30), true), null));
    }

    private UUID raiseDispute() {
        return TenantContext.runAsResult(operator, () -> rights.raise(
                SubjectRequestType.DISPUTE, IdentifierType.RCCM, rccm,
                "I paid this in March.", null).getId());
    }

    private InquiryResult ask() {
        return TenantContext.runAsResult(enquirer, () -> exchange.inquire(new InquiryRequest(
                List.of(new InquiryRequest.SubmittedIdentifier(IdentifierType.RCCM, rccm)),
                null, "Credit application, file 4471"), UUID.randomUUID()));
    }
}
