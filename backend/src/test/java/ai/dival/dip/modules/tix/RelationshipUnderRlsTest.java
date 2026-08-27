package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The obligation lifecycle, asked from the side that can get it wrong.
 *
 * <p>Run as {@code dip_app} so row-level security is actually enforced. Every ordinary integration
 * test here connects as the schema owner and would pass whether or not the policies exist — and
 * two of the properties defended here are policies and privileges rather than code: that an
 * operator cannot see a competitor's accounts, and that nobody can edit or remove a single
 * inconvenient event.
 *
 * <p><strong>On the transaction gymnastics.</strong> {@code TenantAwareDataSource} binds the tenant
 * as each connection is handed out, so the context has to be set <em>before</em> a transaction
 * begins. A {@code @Transactional} test method starts its transaction before the body runs, which
 * would acquire a connection bound to no tenant and make every insert fail the policy — so the
 * tests that need one transaction use a {@link TransactionTemplate} inside
 * {@code TenantContext.runAs} instead.
 */
@RequiresDocker
@TestPropertySource(properties = {
        "spring.datasource.username=dip_app",
        "spring.datasource.password=dip_app",
})
class RelationshipUnderRlsTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private RelationshipService accounts;
    @Autowired
    private SubjectResolver resolver;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate inOneTransaction;

    private UUID operatorA;
    private UUID operatorB;

    @BeforeEach
    void setUp() {
        operatorA = tenants.save(new Tenant("Lifecycle A", "lc-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        operatorB = tenants.save(new Tenant("Lifecycle B", "lc-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // --- fixtures -----------------------------------------------------------

    /** Resolves a subject into existence without declaring a debt against it. */
    private Subject subjectNamed(String name) {
        DeclarationRequest request = new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.RCCM, "CD/KIN/RCCM/" + UUID.randomUUID())),
                name, Subject.SubjectType.BUSINESS, null, "CD",
                new BigDecimal("500.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(30), true);

        return TenantContext.runAsResult(operatorA, () -> resolver.resolve(request).subject());
    }

    private void report(UUID operator, Subject subject, String reference, ObligationEvent code) {
        TenantContext.runAs(operator, () -> accounts.report(
                subject, reference, "POSTPAID", "USD", LocalDate.now().minusYears(1),
                code, LocalDate.now().minusMonths(1), DateSource.REPORTED, null));
    }

    // --- the boundary -------------------------------------------------------

    @Test
    @DisplayName("an operator's own accounts are its own")
    void ownAccountsAreScopedToTheOperator() {
        Subject subject = subjectNamed("Grand Horizon SARL");

        report(operatorA, subject, "ACC-" + UUID.randomUUID(), ObligationEvent.OPENED);
        report(operatorB, subject, "ACC-" + UUID.randomUUID(), ObligationEvent.OPENED);

        assertThat(TenantContext.runAsResult(operatorA,
                () -> accounts.ownAccounts(subject.getId())))
                .as("a book that counts a rival's accounts is the end of the exchange")
                .hasSize(1);
    }

    @Test
    @DisplayName("two operators may use the same account reference")
    void referencesAreScopedToTheOperatorThatIssuedThem() {
        Subject subject = subjectNamed("Atlas Distribution SARL");

        // The lesson from account references on debt records, which is why the unique index is on
        // (tenant_id, account_reference). An index that forgot the tenant would make the second
        // operator to import a book fail on a collision with a competitor it cannot see.
        report(operatorA, subject, "0001", ObligationEvent.OPENED);
        report(operatorB, subject, "0001", ObligationEvent.OPENED);

        assertThat(TenantContext.runAsResult(operatorA, () -> accounts.ownAccounts(subject.getId())))
                .hasSize(1);
        assertThat(TenantContext.runAsResult(operatorB, () -> accounts.ownAccounts(subject.getId())))
                .hasSize(1);
    }

    // --- the network read ---------------------------------------------------

    @Test
    @DisplayName("the network history counts every operator's accounts, and names none")
    void historyReadsAcrossOperators() {
        Subject subject = subjectNamed("Trans-Congo Négoce SARL");

        for (int i = 0; i < 4; i++) {
            String reference = "A-" + UUID.randomUUID();
            report(operatorA, subject, reference, ObligationEvent.OPENED);
            report(operatorA, subject, reference, ObligationEvent.PAID_AS_AGREED);
            report(operatorA, subject, reference, ObligationEvent.CLOSED);
        }
        String bad = "B-" + UUID.randomUUID();
        report(operatorB, subject, bad, ObligationEvent.OPENED);
        report(operatorB, subject, bad, ObligationEvent.DEFAULTED);

        ObligationHistory history = TenantContext.runAsResult(operatorA,
                () -> accounts.historyAcrossNetwork(subject.getId()));

        // Without exchange mode this would read four accounts and no adverse history at all — a
        // wrong answer that looks entirely plausible, which is why this test runs as dip_app
        // rather than as the schema owner.
        assertThat(history.accountsObserved()).isEqualTo(5);
        assertThat(history.accountsAdverse()).isEqualTo(1);
        assertThat(history.institutionsContributing()).isEqualTo(2);
        assertThat(history.performancePercent()).isEqualTo(80);
    }

    @Test
    @DisplayName("asking for the network history does not widen the caller's own book")
    void exchangeModeDoesNotLeak() {
        Subject subject = subjectNamed("Kin Logistique SARL");
        report(operatorA, subject, "A-" + UUID.randomUUID(), ObligationEvent.OPENED);
        report(operatorB, subject, "B-" + UUID.randomUUID(), ObligationEvent.OPENED);
        report(operatorB, subject, "B-" + UUID.randomUUID(), ObligationEvent.OPENED);

        // Both reads inside ONE transaction, which is what makes this a real test: exchange mode
        // is SET LOCAL, so a network read that joined the caller's transaction would leave
        // cross-operator reads switched on for whatever came next.
        List<Relationship> own = TenantContext.runAsResult(operatorA,
                () -> inOneTransaction.execute(status -> {
                    accounts.historyAcrossNetwork(subject.getId());
                    return accounts.ownAccounts(subject.getId());
                }));

        assertThat(own)
                .as("the flag must die with its own transaction, not the caller's")
                .hasSize(1);
    }

    // --- append-only --------------------------------------------------------

    @Test
    @DisplayName("the application may read and insert events, and nothing else")
    @Transactional(readOnly = true)
    void theHistoryIsAppendOnlyByPrivilege() {
        // Asserted against the catalogue rather than by attempting an update and catching an
        // exception. An UPDATE that fails could fail for several reasons — a constraint, a
        // policy, a typo — and the assertion would pass for the wrong one. This asks the database
        // the exact question the migration answers: what may dip_app do to this table?
        //
        // A history whose worst event can be edited into a better one is not evidence, and
        // withholding DELETE is what stops somebody removing the single event that made a record
        // look bad while leaving the account standing and apparently clean. Erasure happens at the
        // account, all at once, by cascade.
        @SuppressWarnings("unchecked")
        List<String> granted = entityManager.createNativeQuery(
                        "select privilege_type from information_schema.table_privileges "
                                + "where grantee = 'dip_app' and table_name = "
                                + "'tix_relationship_event'")
                .getResultList();

        assertThat(granted)
                .as("the append-only rule is a grant, not a convention somebody has to remember")
                .containsExactlyInAnyOrder("SELECT", "INSERT");
    }

    @Test
    @DisplayName("accounts may be deleted, because retention has to erase them")
    @Transactional(readOnly = true)
    void theAccountItselfCanBeErased() {
        @SuppressWarnings("unchecked")
        List<String> granted = entityManager.createNativeQuery(
                        "select privilege_type from information_schema.table_privileges "
                                + "where grantee = 'dip_app' and table_name = 'tix_relationship'")
                .getResultList();

        assertThat(granted)
                .as("the purge removes the account and the cascade takes its events with it")
                .contains("DELETE");
    }
}
