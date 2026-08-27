package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.PlatformDate;
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
 * Erasure, proved by looking for the data afterwards.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}. The purge manages its own
 * transactions per tenant, and a rolled-back test would assert against deletions that never
 * committed — the same blindness that let the payroll rollback bug pass three hundred green tests.
 * Isolation comes from fresh tenants and randomised identifiers per test instead.
 */
@RequiresDocker
class RetentionPurgeTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecordService;
    @Autowired
    private DebtRecordRepository debtRecords;
    @Autowired
    private SubjectRepository subjects;
    @Autowired
    private RetentionPurge purge;
    @Autowired
    private RelationshipService relationships;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private UUID operatorA;
    private UUID operatorB;

    @BeforeEach
    void setUp() {
        operatorA = tenants.save(new Tenant("Purge A", "purge-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        operatorB = tenants.save(new Tenant("Purge B", "purge-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a record past its retention period is deleted, not hidden")
    void expiredRecordsAreErased() {
        String document = document();
        UUID recordId = TenantContext.runAsResult(operatorA,
                () -> debtRecordService.declare(declaration(document), null).record().getId());

        // Four years on: past the three-year period for a first default.
        purge.purgeAsOf(PlatformDate.today().plusYears(4));

        // findById, not a service call. A service could be hiding it; the row itself must be gone.
        assertThat(debtRecords.findById(recordId))
                .as("erasure means the row is not there, not that a query stopped returning it")
                .isEmpty();
    }

    @Test
    @DisplayName("a record still inside its period is untouched")
    void liveRecordsSurvive() {
        // Declared against a default that happened today, so the three-year period has years to
        // run. The shared helper below deliberately backdates by four years, which would have
        // made this test pass or fail for reasons unrelated to what it claims to check.
        String document = document();
        UUID recordId = TenantContext.runAsResult(operatorA,
                () -> debtRecordService.declare(declaredToday(document), null).record().getId());

        purge.purgeAsOf(PlatformDate.today().plusYears(1));

        assertThat(debtRecords.findById(recordId)).isPresent();
    }

    @Test
    @DisplayName("the person is erased once no operator holds a record about them")
    void orphanedSubjectsAreErasedToo() {
        String document = document();
        UUID subjectId = TenantContext.runAsResult(operatorA,
                () -> debtRecordService.declare(declaration(document), null)
                        .record().getSubject().getId());

        purge.purgeAsOf(PlatformDate.today().plusYears(4));

        // The tail of erasure, and the part that is easy to forget: deleting every record about
        // somebody while keeping their name, date of birth and national ID number leaves personal
        // data with no lawful basis and nothing left explaining why it is held.
        assertThat(subjects.findById(subjectId)).isEmpty();
        assertThat(subjects.findByNationalIdentifier(IdentifierType.NATIONAL_ID,
                SubjectIdentifier.normalizeValue(document)))
                .as("the identity document goes with the person")
                .isEmpty();
    }

    @Test
    @DisplayName("a person another operator still holds a record about is NOT erased")
    void subjectsStillListedElsewhereSurvive() {
        // The test that matters most in this class, and the one that catches the bug this code
        // had while it was being written: the orphan query counts debt records, debt records are
        // under row-level security, and asked from the wrong context the count comes back zero
        // for everybody. That version erased the entire registry on the first nightly sweep and
        // every other test here still passed, because they only ever had one operator's data.
        //
        // Operator A's record expires; operator B's does not. The subject must survive.
        String document = document();

        UUID subjectId = TenantContext.runAsResult(operatorA,
                () -> debtRecordService.declare(declaration(document), null)
                        .record().getSubject().getId());

        UUID recordOfB = TenantContext.runAsResult(operatorB, () -> debtRecordService.declare(
                new DeclarationRequest(
                        List.of(new DeclarationRequest.SubmittedIdentifier(
                                IdentifierType.NATIONAL_ID, document)),
                        "Jean Kabila", Subject.SubjectType.INDIVIDUAL,
                        LocalDate.of(1990, 5, 12), "CD",
                        new BigDecimal("400.00"), "USD", "POSTPAID",
                        // Declared today, so it is still live when A's four-year-old one is not.
                        PlatformDate.today(), true), null).record().getId());

        // A's record is a first default declared four years ago; B's is from today.
        purge.purgeAsOf(PlatformDate.today().plusYears(4));

        assertThat(debtRecords.findById(recordOfB))
                .as("operator B's record is nowhere near its retention period")
                .isPresent();
        assertThat(subjects.findById(subjectId))
                .as("somebody another operator still legitimately lists must not be erased")
                .isPresent();
    }

    @Test
    @DisplayName("an erased record leaves an audit trail that it was erased")
    void erasureIsEvidenced() {
        String document = document();
        UUID recordId = TenantContext.runAsResult(operatorA,
                () -> debtRecordService.declare(declaration(document), null).record().getId());

        purge.purgeAsOf(PlatformDate.today().plusYears(4));

        // An erasure nobody can evidence is indistinguishable from data loss, and the difference
        // matters to exactly the person who asks whether the platform still holds their data.
        assertThat(auditActionsFor(recordId)).contains("TIX_RECORD_ERASED");
    }

    private List<String> auditActionsFor(UUID recordId) {
        return jdbc.queryForList("select action from audit_event where resource_id = ?",
                String.class, recordId.toString());
    }

    // --- accounts, which the sweep learned about late ------------------------

    @Test
    @DisplayName("a clean payer is not an orphan, even with no debt record anywhere")
    void anAccountHoldsSomebodyInTheRegistry() {
        String document = document();
        UUID subjectId = TenantContext.runAsResult(operatorA,
                () -> debtRecordService.declare(declaration(document), null)
                        .record().getSubject().getId());

        // An account that runs for years yet, on a subject whose only adverse record is about to
        // expire. This is the company the lifecycle model exists to describe: a spotless payer,
        // known to the network, with nothing held against them.
        // Opened today, so the interim retention — the adverse rule, borrowed until § 4 of
        // docs/CREDIT_INTELLIGENCE.md is answered — leaves it live for three years. The purge
        // below runs at one year, by which time the four-year-old debt record has expired and
        // this has not. Getting these two clocks the wrong way round makes the test pass for the
        // wrong reason, or fail for one.
        TenantContext.runAs(operatorA, () -> relationships.report(
                subjects.findById(subjectId).orElseThrow(),
                "ACC-" + UUID.randomUUID(), "POSTPAID", "USD",
                PlatformDate.today(),
                ObligationEvent.PAID_AS_AGREED, PlatformDate.today().minusMonths(1),
                DateSource.REPORTED, null));

        purge.purgeAsOf(PlatformDate.today().plusYears(1));

        // Before accounts were added to the orphan query this swept them away nightly, and the
        // only reason anybody noticed is that the foreign key made it fail loudly instead.
        assertThat(subjects.findById(subjectId))
                .as("a good payment history is a reason to keep somebody, not to forget them")
                .isPresent();
    }

    @Test
    @DisplayName("an account past its own retention is erased, and takes its history with it")
    void expiredAccountsAreErased() {
        String document = document();
        UUID subjectId = TenantContext.runAsResult(operatorA,
                () -> debtRecordService.declare(declaration(document), null)
                        .record().getSubject().getId());

        UUID accountId = TenantContext.runAsResult(operatorA, () -> relationships.report(
                        subjects.findById(subjectId).orElseThrow(),
                        "ACC-" + UUID.randomUUID(), "POSTPAID", "USD",
                        // Opened four years ago: the three-year period ran out a year back.
                        PlatformDate.today().minusYears(4),
                        ObligationEvent.PAID_AS_AGREED, PlatformDate.today().minusYears(4),
                        DateSource.REPORTED, null)
                .getRelationship().getId());

        purge.purgeAsOf(PlatformDate.today().plusYears(1));

        assertThat(jdbc.queryForObject(
                "select count(*) from tix_relationship where id = ?", Integer.class, accountId))
                .as("the account itself is gone, not merely hidden")
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from tix_relationship_event where relationship_id = ?",
                Integer.class, accountId))
                .as("and its events went with it, by cascade — erasure is all of a history or "
                        + "none of it, because nobody may remove one inconvenient event")
                .isZero();
    }

    private static String document() {
        return "CD-" + UUID.randomUUID();
    }

    /** A default that happened today: comfortably inside every retention period. */
    private static DeclarationRequest declaredToday(String document) {
        return new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, document)),
                "Jean Kabila", Subject.SubjectType.INDIVIDUAL, LocalDate.of(1990, 5, 12), "CD",
                new BigDecimal("150.00"), "USD", "POSTPAID", PlatformDate.today(), true);
    }

    private static DeclarationRequest declaration(String document) {
        return new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, document)),
                "Jean Kabila", Subject.SubjectType.INDIVIDUAL, LocalDate.of(1990, 5, 12), "CD",
                new BigDecimal("150.00"), "USD", "POSTPAID",
                // Four years ago, so a three-year period has run out by the time the purge runs.
                PlatformDate.today().minusYears(4), true);
    }
}
