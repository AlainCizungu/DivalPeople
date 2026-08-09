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
import org.springframework.test.context.TestPropertySource;

/**
 * The subject-rights path, asked from the side that actually matters.
 *
 * <p><strong>This is the only test in the suite that runs the application as {@code dip_app}.</strong>
 * Every other integration test connects as the schema owner, which bypasses row-level security
 * entirely — deliberately, because several of them legitimately act as two tenants inside one
 * transaction and a tenant-pinned connection cannot. The cost of that decision is that a whole
 * class of defect is invisible, and in August 2026 one was living in it: every method of
 * {@link SubjectRightsService} was {@code @Transactional}, so the per-tenant blocks joined the
 * caller's transaction and kept the caller's connection — and therefore the caller's tenant
 * binding. Under {@code dip_app} a dispute raised at one operator would have suppressed nothing at
 * any other, and a person's "whole file" would have contained a single operator.
 *
 * <p>Three hundred green tests said otherwise. This one would not have.
 *
 * <p>Flyway still runs as the owner, as it does in production — only the application's own
 * datasource is downgraded, which is exactly the split a deployment has.
 */
@RequiresDocker
@TestPropertySource(properties = {
        "spring.datasource.username=dip_app",
        "spring.datasource.password=dip_app",
})
class SubjectRightsUnderRlsTest extends AbstractIntegrationTest {

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

    private static final UUID STAFF = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // The tenant table has no row-level security policy of its own — it is the thing the
        // policies key on — so these inserts are legitimate as dip_app.
        operatorA = tenants.save(new Tenant("RLS Rights A", "rls-r-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        operatorB = tenants.save(new Tenant("RLS Rights B", "rls-r-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        document = "CD-" + UUID.randomUUID();
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

    private List<DebtRecord> recordsOf(UUID operator) {
        return TenantContext.runAsResult(operator, () -> records.findByTenantId(operator));
    }

    @Test
    @DisplayName("a dispute raised at one operator really does suppress the other's record")
    void suppressionCrossesTheBoundaryUnderRls() {
        declareAs(operatorA, "500.00");
        declareAs(operatorB, "700.00");

        TenantContext.runAs(operatorA, () -> rights.raise(SubjectRequestType.DISPUTE,
                IdentifierType.NATIONAL_ID, document, "That is not my debt", STAFF));

        // The one that was always going to pass.
        assertThat(recordsOf(operatorA)).allMatch(r -> r.getStatus() == DebtStatus.DISPUTED);
        // The one that mattered. Under the old shape this stayed OUTSTANDING, and the person went
        // on being reported by operator B throughout the case they had already raised.
        assertThat(recordsOf(operatorB)).allMatch(r -> r.getStatus() == DebtStatus.DISPUTED);
    }

    @Test
    @DisplayName("an access request returns the whole file, not just the office they walked into")
    void disclosureCrossesTheBoundaryUnderRls() {
        declareAs(operatorA, "500.00");
        declareAs(operatorB, "700.00");

        List<SubjectRightsService.Disclosure> file = TenantContext.runAsResult(operatorA, () -> {
            SubjectRequest raised = rights.raise(SubjectRequestType.ACCESS,
                    IdentifierType.NATIONAL_ID, document, "What do you hold?", STAFF);
            rights.verifyIdentity(raised.getId(), "National ID seen in person", STAFF);
            return rights.disclose(raised.getId(), STAFF);
        });

        // Row-level security governs SELECT as much as INSERT. A read of operator B's records on
        // a connection bound to operator A returns nothing, silently, and the person is told
        // their file contains one entry when it contains two.
        assertThat(file).hasSize(2);
        assertThat(file).extracting(SubjectRightsService.Disclosure::operator)
                .containsExactlyInAnyOrder("RLS Rights A", "RLS Rights B");
    }

    @Test
    @DisplayName("closing a dispute against lifts the suppression at every operator")
    void liftingCrossesTheBoundaryUnderRls() {
        declareAs(operatorA, "500.00");
        declareAs(operatorB, "700.00");

        SubjectRequest request = TenantContext.runAsResult(operatorA, () -> {
            SubjectRequest raised = rights.raise(SubjectRequestType.DISPUTE,
                    IdentifierType.NATIONAL_ID, document, "Not mine", STAFF);
            return rights.verifyIdentity(raised.getId(), "ID seen in person", STAFF);
        });
        TenantContext.runAs(operatorA, () ->
                rights.close(request.getId(), false, "Signed contract produced", STAFF));

        assertThat(recordsOf(operatorA)).allMatch(r -> r.getStatus() == DebtStatus.OUTSTANDING);
        assertThat(recordsOf(operatorB)).allMatch(r -> r.getStatus() == DebtStatus.OUTSTANDING);
    }
}
