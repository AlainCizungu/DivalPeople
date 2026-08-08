package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * The writing end of the exchange, which did not exist until now.
 *
 * <p>The module shipped able to list, settle and dispute records and unable to create one. Every
 * property it claimed — identity matching, tenant isolation, the audit trail — was being proved
 * against rows the development seeder had written. These tests are about the door an operator
 * actually comes through.
 */
@Transactional
@RequiresDocker
class DeclarationTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private SubjectRepository subjects;

    private UUID operatorA;
    private UUID operatorB;

    @BeforeEach
    void setUp() {
        operatorA = tenants.save(new Tenant("Operator A", "op-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        operatorB = tenants.save(new Tenant("Operator B", "op-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        TenantContext.set(operatorA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a first declaration creates the subject and says so")
    void firstDeclarationCreatesTheSubject() {
        DebtRecordService.Declaration declared = debtRecords.declare(declaration(nationalId()), null);

        assertThat(declared.subjectWasCreated())
                .as("the caller is told when it has opened a file on somebody, not merely added "
                        + "to one")
                .isTrue();
        assertThat(declared.identifiersLearned()).isEqualTo(1);
        assertThat(declared.record().getStatus()).isEqualTo(DebtStatus.OUTSTANDING);
    }

    @Test
    @DisplayName("a second operator declaring the same document lands on the same person")
    void identifiersResolveToOneSubject() {
        String document = nationalId();
        UUID first = debtRecords.declare(declaration(document), null).record().getSubject().getId();

        UUID second = TenantContext.runAsResult(operatorB, () ->
                debtRecords.declare(declaration(document), null).record().getSubject().getId());

        // The shared spine of the exchange. If this ever returns two subjects, one operator's
        // inquiry silently stops seeing the other's records and the whole scheme quietly fails
        // open — the most expensive kind of wrong, because everything still appears to work.
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("the second operator is told the subject already existed")
    void theSecondDeclarationDoesNotClaimToHaveCreatedAnybody() {
        String document = nationalId();
        debtRecords.declare(declaration(document), null);

        DebtRecordService.Declaration second = TenantContext.runAsResult(operatorB, () ->
                debtRecords.declare(declaration(document), null));

        assertThat(second.subjectWasCreated()).isFalse();
    }

    @Test
    @DisplayName("a new document on a known person is learned, not duplicated")
    void newIdentifiersAttachToTheKnownSubject() {
        String document = nationalId();
        UUID subjectId = debtRecords.declare(declaration(document), null)
                .record().getSubject().getId();

        String passport = "P" + UUID.randomUUID().toString().substring(0, 8);
        DebtRecordService.Declaration second = TenantContext.runAsResult(operatorB, () ->
                debtRecords.declare(new DeclarationRequest(
                        List.of(new DeclarationRequest.SubmittedIdentifier(
                                        IdentifierType.NATIONAL_ID, document),
                                new DeclarationRequest.SubmittedIdentifier(
                                        IdentifierType.PASSPORT, passport)),
                        "Jean Kabila", Subject.SubjectType.INDIVIDUAL,
                        LocalDate.of(1990, 5, 12), "CD",
                        new BigDecimal("200.00"), "USD", "POSTPAID",
                        LocalDate.now().minusDays(30), true), null));

        assertThat(second.identifiersLearned()).isEqualTo(1);
        assertThat(second.record().getSubject().getId()).isEqualTo(subjectId);
        assertThat(subjects.findByIdentifier(IdentifierType.PASSPORT,
                SubjectIdentifier.normalizeValue(passport)))
                .get()
                .extracting(Subject::getId)
                .isEqualTo(subjectId);
    }

    @Test
    @DisplayName("documents belonging to two different people are refused, never merged")
    void ambiguousIdentifiersAreRefused() {
        String documentOfOnePerson = nationalId();
        String documentOfAnother = "P" + UUID.randomUUID().toString().substring(0, 8);

        debtRecords.declare(declaration(documentOfOnePerson), null);
        TenantContext.runAs(operatorB, () -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.PASSPORT, documentOfAnother)),
                "Someone Else", Subject.SubjectType.INDIVIDUAL, null, "CD",
                new BigDecimal("300.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(10), true), null));

        // Submitting both together claims they are one person. They are already two, and merging
        // would move one person's debts onto another irreversibly. Refusing is the only safe
        // answer a machine can give.
        assertThatThrownBy(() -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                                IdentifierType.NATIONAL_ID, documentOfOnePerson),
                        new DeclarationRequest.SubmittedIdentifier(
                                IdentifierType.PASSPORT, documentOfAnother)),
                "Jean Kabila", Subject.SubjectType.INDIVIDUAL, null, "CD",
                new BigDecimal("400.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(5), true), null))
                .isInstanceOf(SubjectResolver.AmbiguousSubjectException.class);
    }

    @Test
    @DisplayName("the same operator cannot open a second record against one person")
    void oneOpenRecordPerOperatorPerSubject() {
        String document = nationalId();
        debtRecords.declare(declaration(document), null);

        assertThatThrownBy(() -> debtRecords.declare(declaration(document), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already has an open record");
    }

    @Test
    @DisplayName("two different operators may each hold a record against the same person")
    void differentOperatorsMayBothDeclare() {
        String document = nationalId();
        debtRecords.declare(declaration(document), null);

        assertThatCode(() -> TenantContext.runAs(operatorB,
                () -> debtRecords.declare(declaration(document), null)))
                .as("the exchange exists precisely because several operators hold records "
                        + "against the same subscriber")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a debt below the threshold is refused before anybody is created")
    void belowThresholdCreatesNobody() {
        String document = nationalId();

        assertThatThrownBy(() -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, document)),
                "Too Small", Subject.SubjectType.INDIVIDUAL, null, "CD",
                new BigDecimal("12.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(3), true), null))
                .isInstanceOf(PolicyRefusedException.class);

        // The point of the ordering in declare(). If resolution ran first, a refused declaration
        // would still have written this person into the registry's shared spine — a way to
        // populate a national database with names using requests you know will fail.
        assertThat(subjects.findByIdentifier(IdentifierType.NATIONAL_ID,
                SubjectIdentifier.normalizeValue(document)))
                .as("a refused declaration leaves no trace of the person")
                .isEmpty();
    }

    @Test
    @DisplayName("a declaration without dunning evidence is refused")
    void dunningEvidenceIsRequired() {
        assertThatThrownBy(() -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, nationalId())),
                "No Dunning", Subject.SubjectType.INDIVIDUAL, null, "CD",
                new BigDecimal("500.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(3), false), null))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("dunning");
    }

    @Test
    @DisplayName("a default date in the future is refused")
    void futureDefaultDateIsRefused() {
        // Otherwise the retention clock starts in the future and the record outlives the period
        // the law allows, without anybody editing a retention setting.
        assertThatThrownBy(() -> debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, nationalId())),
                "Time Traveller", Subject.SubjectType.INDIVIDUAL, null, "CD",
                new BigDecimal("500.00"), "USD", "POSTPAID",
                LocalDate.now().plusDays(1), true), null))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("future");
    }

    private static String nationalId() {
        return "CD-" + UUID.randomUUID();
    }

    private static DeclarationRequest declaration(String document) {
        return new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(
                        IdentifierType.NATIONAL_ID, document)),
                "Jean Kabila", Subject.SubjectType.INDIVIDUAL, LocalDate.of(1990, 5, 12), "CD",
                new BigDecimal("150.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(60), true);
    }
}
