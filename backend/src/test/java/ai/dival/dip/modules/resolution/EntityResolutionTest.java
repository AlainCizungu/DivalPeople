package ai.dival.dip.modules.resolution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import ai.dival.dip.modules.tix.DebtRecordRepository;
import ai.dival.dip.modules.tix.DebtRecordService;
import ai.dival.dip.modules.tix.DeclarationRequest;
import ai.dival.dip.modules.tix.IdentifierType;
import ai.dival.dip.modules.tix.Subject;
import ai.dival.dip.modules.tix.SubjectRepository;
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
 * Two operators' records about one person, and the decision that joins them.
 *
 * <p>Not {@code @Transactional}: the merge opens a transaction per operator, on purpose, because
 * row-level security scopes writes to one tenant. A test transaction wrapped around it would test
 * a different thing from what production does.
 *
 * <p>Every fixture name carries a suffix. Subjects are shared across the whole registry and this
 * class is not transactional, so an unsuffixed name outlives it and sits in the registry answering
 * somebody else's inquiry test.
 */
@RequiresDocker
class EntityResolutionTest extends AbstractIntegrationTest {

    private static final UUID REVIEWER = UUID.randomUUID();

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private DebtRecordRepository records;
    @Autowired
    private EntityResolutionService resolution;
    @Autowired
    private SubjectRepository subjects;

    private UUID vodacom;
    private UUID orange;
    private String suffix;

    @BeforeEach
    void setUp() {
        vodacom = tenants.save(new Tenant("Res A", "res-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        orange = tenants.save(new Tenant("Res B", "res-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        // The whole UUID, not the eight characters other tests in this repository use.
        //
        // Every fixture here is a Kabamba, so they all land in the same block and every one is
        // compared against every other. Two eight-character hex suffixes sharing three leading
        // characters score 0.857 against each other — over the 0.82 threshold — so a pair from one
        // test would open a case with a pair from another, and which tests failed depended on what
        // UUID.randomUUID happened to produce. It looked like a defect in the scan.
        //
        // A full UUID brings the same comparison to 0.46. The suffixes also carry hyphens, which
        // the scorer flattens to spaces, so the names no longer share three tokens out of four.
        suffix = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("one man in two operators' books becomes one case for a person to look at")
    void theCaseTheFeatureExistsFor() {
        declarePerson(vodacom, "Jean-Pierre Kabamba " + suffix);
        declarePerson(orange, "Jean Pierre Kabamba " + suffix);

        EntityResolutionService.Scan scan = resolution.scan(REVIEWER);

        assertThat(scan.opened()).isPositive();
        assertThat(mine()).hasSize(1);
    }

    @Test
    @DisplayName("scanning twice does not open the case twice")
    void theScanIsIdempotent() {
        declarePerson(vodacom, "Jean-Pierre Kabamba " + suffix);
        declarePerson(orange, "Jean Pierre Kabamba " + suffix);

        resolution.scan(REVIEWER);
        resolution.scan(REVIEWER);

        // Meant to run nightly. A scan that reopened everything it found yesterday would build a
        // queue out of the same pair repeated, and a reviewer would stop opening it.
        assertThat(mine()).hasSize(1);
    }

    @Test
    @DisplayName("confirming moves both operators' records onto one subject")
    void confirmingMergesAcrossOperators() {
        declarePerson(vodacom, "Jean-Pierre Kabamba " + suffix);
        declarePerson(orange, "Jean Pierre Kabamba " + suffix);
        resolution.scan(REVIEWER);
        EntityResolutionService.Case pending = mine().get(0);

        EntityResolutionService.Decision decision = resolution.decide(pending.id(),
                MatchStatus.CONFIRMED, "Same national ID seen on both operators' KYC files.",
                REVIEWER);

        assertThat(decision.survivor()).isNotNull();
        assertThat(decision.moved())
                .as("one record from each operator, repointed inside that operator's own boundary")
                .isGreaterThanOrEqualTo(2);

        // The half that matters: the records are still each operator's own, and now describe one
        // person. Row-level security forbids cross-tenant writes, so this only works because the
        // merge runs a transaction per operator rather than one privileged sweep.
        assertThat(held(vodacom, decision.survivor())).isEqualTo(1);
        assertThat(held(orange, decision.survivor())).isEqualTo(1);
    }

    @Test
    @DisplayName("the absorbed subject survives, pointing at the one that answers for it")
    void nothingIsDeleted() {
        declarePerson(vodacom, "Jean-Pierre Kabamba " + suffix);
        declarePerson(orange, "Jean Pierre Kabamba " + suffix);
        resolution.scan(REVIEWER);
        EntityResolutionService.Case pending = mine().get(0);
        UUID left = pending.left().id();
        UUID right = pending.right().id();

        UUID survivor = resolution.decide(pending.id(), MatchStatus.CONFIRMED,
                "Same national ID on both KYC files.", REVIEWER).survivor();

        // Deleting the absorbed row would delete the case that recorded the decision — the one
        // action most needing an audit trail destroying its own — and make the merge
        // unrecoverable.
        UUID absorbed = survivor.equals(left) ? right : left;
        assertThat(resolution.get(pending.id()).status()).isEqualTo(MatchStatus.CONFIRMED);
        assertThat(List.of(left, right)).contains(absorbed);
    }

    @Test
    @DisplayName("rejecting decides the case and moves nothing")
    void rejectingLeavesTwoSubjects() {
        declarePerson(vodacom, "Jean-Pierre Kabamba " + suffix);
        declarePerson(orange, "Jean Pierre Kabamba " + suffix);
        resolution.scan(REVIEWER);
        EntityResolutionService.Case pending = mine().get(0);

        EntityResolutionService.Decision decision = resolution.decide(pending.id(),
                MatchStatus.REJECTED, "Different dates of birth on the two KYC files.", REVIEWER);

        assertThat(decision.moved()).isZero();
        assertThat(decision.survivor()).isNull();
        assertThat(stillSeparate(pending)).isTrue();
    }

    @Test
    @DisplayName("a reviewer who cannot tell has somewhere to put that")
    void investigatingIsAnOutcome() {
        declarePerson(vodacom, "Jean-Pierre Kabamba " + suffix);
        declarePerson(orange, "Jean Pierre Kabamba " + suffix);
        resolution.scan(REVIEWER);
        EntityResolutionService.Case pending = mine().get(0);

        resolution.decide(pending.id(), MatchStatus.INVESTIGATING,
                "Asked Orange to confirm the date of birth; waiting.", REVIEWER);

        // Out of the queue and still undecided, which is the true state. A queue offering only
        // confirm and reject pushes this answer into "reject", because rejecting feels safer.
        assertThat(resolution.get(pending.id()).status()).isEqualTo(MatchStatus.INVESTIGATING);
        assertThat(mine()).isEmpty();
        assertThat(stillSeparate(pending)).isTrue();
    }

    @Test
    @DisplayName("a decision has to say what the reviewer saw")
    void aDecisionNeedsANote() {
        declarePerson(vodacom, "Jean-Pierre Kabamba " + suffix);
        declarePerson(orange, "Jean Pierre Kabamba " + suffix);
        resolution.scan(REVIEWER);
        UUID caseId = mine().get(0).id();

        // Required on a confirmation as much as on a rejection. The merge is recoverable; the
        // reason somebody believed two people were one is not recoverable from anywhere else.
        assertThatThrownBy(() -> resolution.decide(caseId, MatchStatus.CONFIRMED, "   ", REVIEWER))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("Say what you saw");
    }

    @Test
    @DisplayName("a decision has to name who made it")
    void aDecisionNeedsAnActor() {
        declarePerson(vodacom, "Jean-Pierre Kabamba " + suffix);
        declarePerson(orange, "Jean Pierre Kabamba " + suffix);
        resolution.scan(REVIEWER);
        UUID caseId = mine().get(0).id();

        assertThatThrownBy(() -> resolution.decide(caseId, MatchStatus.CONFIRMED,
                "Same national ID.", null))
                .isInstanceOf(PolicyRefusedException.class);
    }

    @Test
    @DisplayName("a decided case cannot be decided again")
    void oneDecisionPerCase() {
        declarePerson(vodacom, "Jean-Pierre Kabamba " + suffix);
        declarePerson(orange, "Jean Pierre Kabamba " + suffix);
        resolution.scan(REVIEWER);
        EntityResolutionService.Case pending = mine().get(0);
        UUID caseId = pending.id();
        resolution.decide(caseId, MatchStatus.REJECTED, "Different dates of birth.", REVIEWER);

        // Refused before anything is written, which is not where the first version put the check.
        // It merged first and refused afterwards — and the merge is one transaction per operator,
        // so those had already committed by the time the refusal arrived. The case came back as a
        // constraint violation over a half-finished merge instead of a clean refusal, which is how
        // this test found the ordering bug rather than the rule it was written for.
        assertThatThrownBy(() -> resolution.decide(caseId, MatchStatus.CONFIRMED,
                "Changed my mind.", REVIEWER))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("already been decided");

        assertThat(resolution.get(caseId).status())
                .as("and the first decision stands")
                .isEqualTo(MatchStatus.REJECTED);
        assertThat(stillSeparate(pending))
                .as("nothing moved on the way to the refusal")
                .isTrue();
    }

    @Test
    @DisplayName("two unrelated companies never reach the queue")
    void strangersAreNotPaired() {
        declareBusiness(vodacom, "Grand Horizon SARL " + suffix);
        declareBusiness(orange, "Kivu Logistique " + suffix);

        resolution.scan(REVIEWER);

        // A review queue nobody can empty is a review queue nobody opens. These two share no
        // block, so they are never even compared.
        assertThat(mine()).isEmpty();
    }

    @Test
    @DisplayName("a case in the queue does not make somebody impossible to erase")
    void theQueueDoesNotVetoErasure() {
        declarePerson(vodacom, "Jean-Pierre Kabamba " + suffix);
        declarePerson(orange, "Jean Pierre Kabamba " + suffix);
        resolution.scan(REVIEWER);
        UUID subject = mine().get(0).left().id();

        // Its records go first, as the purge does it: a subject is erased once nobody holds
        // anything against them, and tix_debt_record.subject_id would refuse the delete otherwise.
        // The first version of this test deleted the subject out from under two live records and
        // blamed the candidate table for the refusal.
        for (UUID operator : List.of(vodacom, orange)) {
            TenantContext.runAs(operator, () ->
                    records.deleteAll(records.findByTenantIdAndSubjectId(operator, subject)));
        }

        // The right to erasure is not a review queue's to veto, and V28 gave it one by accident:
        // plain foreign keys default to NO ACTION, so a subject anybody had ever been compared
        // against could no longer be deleted. Five retention tests and a rights test found it.
        //
        // Asserted here as well, next to the feature that caused it, because the purge tests will
        // report it as a purge problem and this is the only file that says why.
        assertThatCode(() -> subjects.deleteById(subject))
                .as("the open case goes with the person; the decision stays in the audit trail")
                .doesNotThrowAnyException();
    }

    /** Cases about this test's own subjects, so a shared registry does not make it flaky. */
    private List<EntityResolutionService.Case> mine() {
        return resolution.open(200).stream()
                .filter(pending -> pending.left().fullName().contains(suffix))
                .toList();
    }

    /**
     * How many of this operator's records sit under a subject.
     *
     * <p>Counted through a query rather than by walking {@code record.getSubject()}, which is a
     * lazy association on a detached entity outside a transaction — the exact shape that took a
     * round trip to diagnose in ImportReversalTest.
     */
    private int held(UUID operator, UUID subjectId) {
        return TenantContext.runAsResult(operator, () ->
                records.findByTenantIdAndSubjectId(operator, subjectId).size());
    }

    /** Neither subject in the pair holds records from both operators. */
    private boolean stillSeparate(EntityResolutionService.Case pending) {
        for (UUID subject : List.of(pending.left().id(), pending.right().id())) {
            if (held(vodacom, subject) > 0 && held(orange, subject) > 0) {
                return false;
            }
        }
        return true;
    }

    private void declarePerson(UUID operator, String name) {
        declare(operator, name, Subject.SubjectType.INDIVIDUAL);
    }

    private void declareBusiness(UUID operator, String name) {
        declare(operator, name, Subject.SubjectType.BUSINESS);
    }

    /**
     * A record identified by nothing but a name.
     *
     * <p>Which is the whole situation: the Orange export carries 342 customers and no identifier
     * of any kind, and an account reference resolves inside one operator and nowhere else. Two
     * operators declaring the same person therefore create two subjects, and only a person can say
     * they are one.
     */
    private void declare(UUID operator, String name, Subject.SubjectType type) {
        TenantContext.runAs(operator, () -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.ACCOUNT_REFERENCE,
                        "ACC-" + UUID.randomUUID().toString().substring(0, 8))),
                name, type, null, "CD",
                new BigDecimal("500.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(60), true), null));
    }
}
