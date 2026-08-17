package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.PlatformDate;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The records list names its subject, and still does so outside a transaction.
 *
 * <p><strong>Deliberately not {@code @Transactional}, and that is the whole point.</strong> The
 * records response gained {@code subjectName} and {@code subjectType}, both of which live on a lazy
 * association. Mapping happens in the controller, after the service's transaction has closed, and
 * with {@code open-in-view: false} a lazy proxy there is a {@code LazyInitializationException} and a
 * 500 on the screen. A test wrapped in a transaction keeps the session open and passes while the
 * application fails — which is exactly how this class of defect reaches a demo.
 *
 * <p>Called through the controller rather than the service, because the controller is where the
 * mapping lives. It also means {@code @PreAuthorize} runs, so a wrong role fails here too.
 *
 * <p>Three paths produce this response and all three are covered: the list (a join fetch), settle
 * and dispute (initialised inside their transaction), and a fresh declaration (whose subject is the
 * resolver's own entity rather than a proxy). They fail differently, so testing one proves nothing
 * about the other two.
 */
@RequiresDocker
class RecordListMappingTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private TixController tix;
    @Autowired
    private DebtRecordService debtRecords;

    private UUID operator;
    private String rccm;

    @BeforeEach
    void setUp() {
        // Both roles: declaring needs one and listing needs the other, and without an
        // authentication the method-security proxy refuses before any mapping runs — the test
        // would pass for the wrong reason.
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("record-list-test", "n/a",
                        "ROLE_TIX_DECLARANT", "ROLE_TIX_INQUIRER"));

        operator = tenants.save(new Tenant("Mapping Operator", "map-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        TenantContext.set(operator);
        rccm = "CD/KIN/RCCM/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the list names the subject and says what kind it is, outside any transaction")
    void theListCarriesTheSubject() {
        declare("Trans-Congo Mapping SARL", Subject.SubjectType.BUSINESS);

        // If the subject were still a proxy here, this line throws LazyInitializationException
        // rather than failing an assertion — which is precisely the failure worth catching.
        assertThatCode(tix::listOwnDebtRecords).doesNotThrowAnyException();

        List<TixController.DebtRecordResponse> listed = tix.listOwnDebtRecords();
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).subjectName())
                .as("the list used to show an amount against a bare uuid")
                .isEqualTo("Trans-Congo Mapping SARL");
        assertThat(listed.get(0).subjectType()).isEqualTo(Subject.SubjectType.BUSINESS);
    }

    @Test
    @DisplayName("a person and a company sit in one list, told apart by their type")
    void bothKindsInOneList() {
        // The reason Businesses and Individuals stopped being two screens. One list, one question,
        // and the kind is a column.
        declare("Trans-Congo Mapping SARL", Subject.SubjectType.BUSINESS);
        declareOther("Jean Mapping Kabila", Subject.SubjectType.INDIVIDUAL);

        List<TixController.DebtRecordResponse> listed = tix.listOwnDebtRecords();

        assertThat(listed).hasSize(2);
        assertThat(listed).extracting(TixController.DebtRecordResponse::subjectType)
                .containsExactlyInAnyOrder(
                        Subject.SubjectType.BUSINESS, Subject.SubjectType.INDIVIDUAL);
    }

    @Test
    @DisplayName("settling and disputing map the same way, and they load the subject differently")
    void settleAndDisputeAlsoCarryTheSubject() {
        UUID first = declare("Settle Mapping SARL", Subject.SubjectType.BUSINESS);
        UUID second = declareOther("Dispute Mapping SARL", Subject.SubjectType.BUSINESS);

        // These do not go through the join-fetched finder. They load by id and are mapped after
        // their own transaction commits, so if the subject were left as a proxy this is where it
        // would surface — on an action, not on a page load.
        assertThat(tix.settle(first).getBody().subjectName()).isEqualTo("Settle Mapping SARL");
        assertThat(tix.dispute(second).getBody().subjectName())
                .isEqualTo("Dispute Mapping SARL");
    }

    private UUID declare(String name, Subject.SubjectType type) {
        return debtRecords.declare(request(name, type, rccm), null).record().getId();
    }

    /**
     * A second subject, carrying the document its kind actually has.
     *
     * <p>A company gets an RCCM and a person gets a national id. Declaring a person against a
     * company register number would be exercising a shape the registry has no business accepting.
     */
    private UUID declareOther(String name, Subject.SubjectType type) {
        boolean person = type == Subject.SubjectType.INDIVIDUAL;
        return debtRecords.declare(
                request(name, type,
                        person ? IdentifierType.NATIONAL_ID : IdentifierType.RCCM,
                        person ? "CD-" + UUID.randomUUID()
                               : "CD/KIN/RCCM/" + UUID.randomUUID().toString().substring(0, 8)),
                null).record().getId();
    }

    private static DeclarationRequest request(String name, Subject.SubjectType type,
                                              String document) {
        return request(name, type, IdentifierType.RCCM, document);
    }

    private static DeclarationRequest request(String name, Subject.SubjectType type,
                                              IdentifierType idType, String document) {
        return new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(idType, document)),
                name, type, null, "CD",
                new BigDecimal("500.00"), "USD", "POSTPAID",
                PlatformDate.today().minusDays(30), true);
    }
}
