package ai.dival.dip.common.audit;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import ai.dival.dip.modules.tix.ExchangeService;
import ai.dival.dip.modules.tix.IdentifierType;
import ai.dival.dip.modules.tix.InquiryRequest;
import ai.dival.dip.modules.tix.InquiryResult;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The audit log's two promises, both of which were untrue until V18.
 *
 * <p>A TIX inquiry validated a {@code purpose} and discarded it, so the exchange's only
 * accountability control recorded that somebody looked at somebody and nothing else. And both V1
 * and V4 claimed the table was "append-only by privilege, not merely by convention" while
 * {@code dip_app} held UPDATE on it — because {@code GRANT ... ON ALL TABLES} ran after the table
 * was created and the later, narrower grant added nothing. A GRANT is not a reset.
 *
 * <p>Neither was noticed for eighteen migrations, because nothing tried. Both are the kind of
 * claim that stays true-looking until an auditor asks.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}: audit rows are written with
 * REQUIRES_NEW so they survive the rollback of whatever produced them, and a rolled-back test
 * would be asserting against a transaction that never committed.
 */
@RequiresDocker
class AuditTrailTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private AuditService audit;
    @Autowired
    private AuditEventRepository events;
    @Autowired
    private ExchangeService exchange;
    @Autowired
    private EntityManager entityManager;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = tenants.save(new Tenant("AU", "au-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);
    }

    @AfterEach
    void tearDown() {
        // No cleanup of audit rows, and not by oversight: this class commits, so the obvious
        // teardown is to delete what it wrote — and the rule under test would silently ignore
        // the DELETE, leaving the rows behind while appearing to work. Isolation comes from a
        // fresh tenant per test instead, and every query here is scoped to it.
        //
        // Writing that teardown first and noticing it could not work is how this comment exists.
        TenantContext.clear();
    }

    @Test
    @DisplayName("an inquiry records why it was made")
    void inquiryRecordsItsPurpose() {
        // No subject matches, which is the important case: an operator sweeping identifiers to
        // learn which ones exist produces nothing but no-matches, and that is exactly the
        // pattern an auditor needs to be able to see.
        InquiryResult result = exchange.inquire(new InquiryRequest(
                List.of(new InquiryRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, "CD-NOBODY-" + UUID.randomUUID())),
                null,
                "Pre-contract affordability check for application 4471"), null);

        assertThat(result.outcome()).isEqualTo(InquiryResult.Outcome.NO_MATCH);

        AuditEvent recorded = onlyEvent("Subject");
        assertThat(recorded.getDetail())
                .as("the purpose the caller stated, which used to be validated and thrown away")
                .isEqualTo("Pre-contract affordability check for application 4471");
    }

    @Test
    @DisplayName("a reason longer than the column is truncated, not dropped with the event")
    void anOverlongReasonDoesNotLoseTheEvent() {
        audit.record("TEST_ACTION", "Thing", null, AuditService.OUTCOME_SUCCESS, null,
                "x".repeat(900));

        // Losing an audit row to protect a column width would be the wrong trade in every
        // direction.
        assertThat(onlyEvent("Thing").getDetail()).hasSize(500).endsWith("...");
    }

    @Test
    @DisplayName("a blank reason is stored as absent rather than as an empty string")
    void blankReasonBecomesNull() {
        audit.record("TEST_ACTION", "Thing", null, AuditService.OUTCOME_SUCCESS, null, "   ");
        assertThat(onlyEvent("Thing").getDetail()).isNull();
    }

    @Test
    @DisplayName("an audit row cannot be rewritten, whoever is asking")
    void auditRowsCannotBeUpdated() {
        audit.record("TEST_ACTION", "Thing", "the-original", AuditService.OUTCOME_DENIED, null,
                "refused because the caller was not the subject");
        UUID id = onlyEvent("Thing").getId();

        // Straight SQL, deliberately. The REVOKE protects against the application role, but these
        // tests connect as the schema OWNER (ADR 0002) — which is the account a REVOKE cannot
        // stop. The DO INSTEAD NOTHING rules are what make the claim true for everybody, and this
        // is the only way to find out whether they do.
        int reported = entityManager.createNativeQuery(
                        "UPDATE audit_event SET outcome = 'SUCCESS', detail = 'nothing to see' "
                                + "WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();

        entityManager.clear();
        AuditEvent after = events.findById(id).orElseThrow();

        assertThat(reported).as("the rule discards the statement").isZero();
        assertThat(after.getOutcome()).isEqualTo(AuditService.OUTCOME_DENIED);
        assertThat(after.getDetail()).isEqualTo("refused because the caller was not the subject");
    }

    @Test
    @DisplayName("an audit row cannot be deleted either")
    void auditRowsCannotBeDeleted() {
        audit.record("TEST_ACTION", "Thing", null, AuditService.OUTCOME_SUCCESS, null, "keep me");
        UUID id = onlyEvent("Thing").getId();

        entityManager.createNativeQuery("DELETE FROM audit_event WHERE id = :id")
                .setParameter("id", id)
                .executeUpdate();
        entityManager.clear();

        // Covering the tracks is the first thing anybody does, so this matters more than the
        // update case.
        assertThat(events.findById(id)).isPresent();
    }

    private AuditEvent onlyEvent(String resourceType) {
        List<AuditEvent> found =
                events.findByTenantIdAndResourceTypeOrderByOccurredAtDesc(tenantId, resourceType);
        assertThat(found).as("exactly one audit event for " + resourceType).hasSize(1);
        return found.get(0);
    }
}
