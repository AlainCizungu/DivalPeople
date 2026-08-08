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
 * The rights of the people in the registry.
 *
 * <p>Not {@code @Transactional}: the service binds each tenant in turn and commits per operator,
 * because a person's file spans operators and the row-level security policy allows an operator to
 * write only its own rows. A rolled-back test would observe none of it.
 */
@RequiresDocker
class SubjectRightsTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private DebtRecordRepository records;
    @Autowired
    private SubjectRightsService rights;

    private UUID operatorA;
    private UUID operatorB;
    private String document;

    @BeforeEach
    void setUp() {
        operatorA = tenants.save(new Tenant("Rights A", "rights-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        operatorB = tenants.save(new Tenant("Rights B", "rights-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        document = "CD-" + UUID.randomUUID();
        TenantContext.set(operatorA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void declareAs(UUID operator, String amount) {
        TenantContext.runAs(operator, () -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, document)),
                "Jean Kabila", Subject.SubjectType.INDIVIDUAL, LocalDate.of(1990, 5, 12), "CD",
                new BigDecimal(amount), "USD", "POSTPAID",
                LocalDate.now().minusDays(30), true), null));
    }

    private SubjectRequest verified(SubjectRequestType type) {
        SubjectRequest request = rights.raise(type, IdentifierType.NATIONAL_ID, document,
                "Raised at the counter", null);
        return rights.verifyIdentity(request.getId(), "National ID seen in person", null);
    }

    // --- the case itself ----------------------------------------------------

    @Test
    @DisplayName("asking about yourself does not put you in the registry")
    void lookingSomebodyUpNeverCreatesThem() {
        // The single most important property of this whole feature. If raising a case created the
        // subject, then a person walking in to ask "am I listed?" would be listed by asking.
        assertThatThrownBy(() -> rights.raise(SubjectRequestType.ACCESS,
                IdentifierType.NATIONAL_ID, "CD-" + UUID.randomUUID(), "Am I listed?", null))
                .isInstanceOf(SubjectRightsService.SubjectNotInRegistryException.class);
    }

    @Test
    @DisplayName("nothing can be decided before identity is verified")
    void decisionsRequireVerifiedIdentity() {
        declareAs(operatorA, "500.00");
        SubjectRequest request = rights.raise(SubjectRequestType.ERASURE,
                IdentifierType.NATIONAL_ID, document, "Remove my data", null);

        // Otherwise "I am that person" is enough to erase somebody else's debts.
        assertThatThrownBy(() -> rights.decideErasure(request.getId(), null))
                .isInstanceOf(PolicyRefusedException.class);
    }

    @Test
    @DisplayName("verification must say how the person was checked")
    void verificationNeedsEvidence() {
        declareAs(operatorA, "500.00");
        SubjectRequest request = rights.raise(SubjectRequestType.ACCESS,
                IdentifierType.NATIONAL_ID, document, "What do you hold?", null);

        assertThatThrownBy(() -> rights.verifyIdentity(request.getId(), "  ", null))
                .isInstanceOf(PolicyRefusedException.class);
    }

    // --- access -------------------------------------------------------------

    @Test
    @DisplayName("an access request returns the whole file, across every operator")
    void accessSpansOperators() {
        declareAs(operatorA, "500.00");
        declareAs(operatorB, "700.00");

        List<SubjectRightsService.Disclosure> file =
                rights.disclose(verified(SubjectRequestType.ACCESS).getId(), null);

        // A person's right of access is to everything held about them, not to whichever operator's
        // office they happened to walk into. This is the one call that crosses the boundary on the
        // subject's behalf, and it names the operators — which is exactly what an *enquiring*
        // operator is never told.
        assertThat(file).hasSize(2);
        assertThat(file).extracting(SubjectRightsService.Disclosure::operator)
                .containsExactlyInAnyOrder("Rights A", "Rights B");
        assertThat(file).extracting(SubjectRightsService.Disclosure::amount)
                .containsExactlyInAnyOrder("500.00 USD", "700.00 USD");
    }

    @Test
    @DisplayName("a file is not disclosed before identity is verified")
    void disclosureRequiresVerification() {
        declareAs(operatorA, "500.00");
        SubjectRequest request = rights.raise(SubjectRequestType.ACCESS,
                IdentifierType.NATIONAL_ID, document, "What do you hold?", null);

        assertThatThrownBy(() -> rights.disclose(request.getId(), null))
                .isInstanceOf(PolicyRefusedException.class);
    }

    // --- dispute ------------------------------------------------------------

    @Test
    @DisplayName("raising a dispute suppresses the records immediately, at every operator")
    void disputeSuppressesBeforeItIsDecided() {
        declareAs(operatorA, "500.00");
        declareAs(operatorB, "700.00");

        rights.raise(SubjectRequestType.DISPUTE, IdentifierType.NATIONAL_ID, document,
                "That is not my debt", null);

        // Before the decision, not after. The harm of being wrongly listed accrues every day the
        // listing stands; somebody should not lose a contract during the weeks their case is read.
        assertThat(recordsOf(operatorA)).allMatch(r -> r.getStatus() == DebtStatus.DISPUTED);
        assertThat(recordsOf(operatorB)).allMatch(r -> r.getStatus() == DebtStatus.DISPUTED);
    }

    @Test
    @DisplayName("a suppressed record disappears from inquiries")
    void suppressedRecordsAreNotReported() {
        declareAs(operatorB, "700.00");
        rights.raise(SubjectRequestType.DISPUTE, IdentifierType.NATIONAL_ID, document,
                "Not mine", null);

        // DISPUTED was already excluded from exchange results. What did not exist until now was
        // any way for the person concerned to cause it — the dispute endpoint required
        // TIX_DECLARANT, so the only party who could raise one was the operator making the claim.
        assertThat(recordsOf(operatorB))
                .allMatch(record -> !record.isVisibleToOtherOperators());
    }

    @Test
    @DisplayName("refusing a dispute puts the records back")
    void refusedDisputeLiftsSuppression() {
        declareAs(operatorA, "500.00");
        SubjectRequest request = verified(SubjectRequestType.DISPUTE);

        rights.close(request.getId(), false, "Signed contract and dunning correspondence produced",
                null);

        assertThat(recordsOf(operatorA)).allMatch(r -> r.getStatus() == DebtStatus.OUTSTANDING);
        assertThat(recordsOf(operatorA)).allMatch(r -> r.getSuppressedByRequestId() == null);
    }

    @Test
    @DisplayName("upholding a dispute leaves the records suppressed")
    void upheldDisputeKeepsRecordsSuppressed() {
        declareAs(operatorA, "500.00");
        SubjectRequest request = verified(SubjectRequestType.DISPUTE);

        rights.close(request.getId(), true, "Operator could produce no contract", null);

        // A claim found to be wrong should not quietly return to the exchange because the case
        // closed. Correcting it is the operator's move, through settlement or a new declaration.
        assertThat(recordsOf(operatorA)).allMatch(r -> r.getStatus() == DebtStatus.DISPUTED);
    }

    @Test
    @DisplayName("a decision must carry grounds")
    void decisionsNeedReasons() {
        declareAs(operatorA, "500.00");
        SubjectRequest request = verified(SubjectRequestType.DISPUTE);

        assertThatThrownBy(() -> rights.close(request.getId(), false, "   ", null))
                .as("a refusal nobody can appeal is not a decision")
                .isInstanceOf(PolicyRefusedException.class);
    }

    // --- erasure ------------------------------------------------------------

    @Test
    @DisplayName("an outstanding debt is not erased on request, and the refusal says why")
    void outstandingDebtsSurviveAnErasureRequest() {
        declareAs(operatorA, "500.00");

        SubjectRequest decided = rights.decideErasure(verified(SubjectRequestType.ERASURE).getId(), null);

        // An unconditional erasure right would let anybody delete their own debts, and no operator
        // would contribute to an exchange that worked that way — the right would defeat the thing
        // it is attached to.
        assertThat(decided.getStatus()).isEqualTo(SubjectRequestStatus.REFUSED);
        assertThat(decided.getDecisionReason()).contains("still outstanding");
        assertThat(recordsOf(operatorA)).hasSize(1);
    }

    @Test
    @DisplayName("a settled debt is erased on request")
    void settledDebtsAreErased() {
        declareAs(operatorA, "500.00");
        UUID recordId = recordsOf(operatorA).get(0).getId();
        TenantContext.runAs(operatorA, () -> debtRecords.settle(recordId, null));

        SubjectRequest decided = rights.decideErasure(verified(SubjectRequestType.ERASURE).getId(), null);

        // Once regularised the operator has no remaining interest in reporting it, and waiting out
        // the retention window serves nobody.
        assertThat(decided.getStatus()).isEqualTo(SubjectRequestStatus.UPHELD);
        assertThat(records.findById(recordId)).isEmpty();
    }

    @Test
    @DisplayName("a partly granted erasure says exactly what was and was not removed")
    void partialErasureIsStatedAsSuch() {
        declareAs(operatorA, "500.00");
        declareAs(operatorB, "700.00");
        UUID settledOne = recordsOf(operatorA).get(0).getId();
        TenantContext.runAs(operatorA, () -> debtRecords.settle(settledOne, null));

        SubjectRequest decided = rights.decideErasure(verified(SubjectRequestType.ERASURE).getId(), null);

        assertThat(decided.getStatus()).isEqualTo(SubjectRequestStatus.UPHELD);
        assertThat(decided.getDecisionReason())
                .contains("1 settled record(s) erased")
                .contains("1 kept");
        assertThat(recordsOf(operatorA)).isEmpty();
        assertThat(recordsOf(operatorB)).hasSize(1);
    }

    @Test
    @DisplayName("one operator cannot progress another operator's case")
    void casesDoNotCrossOperators() {
        declareAs(operatorA, "500.00");
        SubjectRequest ofA = rights.raise(SubjectRequestType.ACCESS,
                IdentifierType.NATIONAL_ID, document, "Mine", null);

        TenantContext.runAs(operatorB, () ->
                assertThatThrownBy(() -> rights.verifyIdentity(ofA.getId(), "seen", null))
                        .isInstanceOf(SubjectRightsService.RequestNotFoundException.class));
    }

    private List<DebtRecord> recordsOf(UUID operator) {
        return TenantContext.runAsResult(operator, () -> records.findByTenantId(operator));
    }
}
