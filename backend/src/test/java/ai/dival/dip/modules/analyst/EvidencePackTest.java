package ai.dival.dip.modules.analyst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.audit.AuditEventRepository;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import ai.dival.dip.modules.tix.DebtRecordService;
import ai.dival.dip.modules.tix.DeclarationRequest;
import ai.dival.dip.modules.tix.IdentifierType;
import ai.dival.dip.modules.tix.Subject;
import ai.dival.dip.modules.tix.SubjectRequestType;
import ai.dival.dip.modules.tix.SubjectRightsService;
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
 * The pack, and the boundary it must not cross.
 *
 * <p>An evidence pack is the grounding a generated summary would rest on, so the interesting
 * assertions are not about what it contains — that is two existing endpoints stapled together, both
 * already tested. They are about what it <strong>cannot</strong> contain, and about it costing what
 * an inquiry costs.
 *
 * <p>The failure this class exists to prevent is the one that would arrive later and quietly: an
 * analyst given a database connection because the pack was "internal", answering "which companies
 * does the other operator report" perfectly, and looking exactly the same on screen.
 */
@RequiresDocker
class EvidencePackTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private SubjectRightsService rights;
    @Autowired
    private EvidencePackService packs;
    @Autowired
    private AuditEventRepository audit;

    private UUID vodacom;
    private UUID orange;
    private UUID bank;
    private String rccm;
    private static final UUID ANALYST = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        vodacom = tenants.save(new Tenant("Pack A", "pk-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        orange = tenants.save(new Tenant("Pack B", "pk-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        bank = tenants.save(new Tenant("Pack Bank", "pk-c-" + UUID.randomUUID(),
                Tenant.Edition.BANKING, "fr")).getId();
        rccm = "CD/KIN/RCCM/" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("the pack carries this operator's own amounts and no other operator's")
    void amountsStopAtTheBoundary() {
        declare(vodacom, "1450.00", 200);
        declare(orange, "999999.00", 400);

        EvidencePackService.EvidencePack pack = assemble(vodacom);

        // Its own file, in full, because it is its own.
        assertThat(pack.held().records()).hasSize(1);
        assertThat(pack.held().records().get(0).amount()).isEqualTo("1450.00");

        // And the other operator reduced to the one number the exchange discloses. The figure
        // 999999.00 must appear nowhere in the pack, in any field, at any precision — this is the
        // assertion that would fail the day somebody gives the analyst a database connection.
        assertThat(pack.exchange().institutionCount()).isEqualTo(2);
        assertThat(pack.toString()).doesNotContain("999999");
        assertThat(pack.absent())
                .contains(Absence.OTHER_OPERATORS_AMOUNTS_ARE_NOT_DISCLOSED,
                        Absence.OTHER_OPERATORS_ARE_NOT_NAMED);
    }

    @Test
    @DisplayName("no operator is named anywhere in the pack")
    void institutionsAreNeverNamed() {
        declare(vodacom, "500.00", 200);
        declare(orange, "700.00", 300);

        EvidencePackService.EvidencePack pack = assemble(vodacom);

        // Rendered rather than inspected field by field, because the leak this guards against
        // would arrive as a new field somebody added helpfully rather than as a change to one of
        // the fields being checked today.
        assertThat(pack.toString()).doesNotContain("Pack B", "pk-b-", orange.toString());
    }

    @Test
    @DisplayName("assembling a pack is charged and recorded exactly like an inquiry")
    void thePackCostsAnInquiry() {
        declare(vodacom, "500.00", 200);

        long before = inquiries(vodacom);
        assemble(vodacom);
        long after = inquiries(vodacom);

        // One inquiry, with the purpose the caller gave. A pack that reached the exchange without
        // charging the allowance would be a way to query it with the throttle off and the trail
        // thinned, and it would look identical on screen — which is the whole reason this is
        // asserted on the audit rows rather than trusted to a comment.
        assertThat(after - before).isEqualTo(1);
    }

    @Test
    @DisplayName("a pack has to say why it is being assembled")
    void purposeIsRequired() {
        UUID subject = declare(vodacom, "500.00", 200);

        assertThatThrownBy(() -> TenantContext.runAsResult(vodacom,
                () -> packs.forSubject(subject, "   ", ANALYST)))
                .isInstanceOf(PolicyRefusedException.class);

        // And the refusal costs nothing: a rejected pack must not spend an inquiry.
        assertThat(inquiries(vodacom)).isZero();
    }

    @Test
    @DisplayName("a company this operator holds nothing about is not found, rather than not yours")
    void anUnheldSubjectIsRefusedBeforeTheExchangeIsAsked() {
        UUID theirs = declare(orange, "500.00", 200);

        // The bank holds no record against this company. It is refused as not found rather than
        // as not yours, because the two must be indistinguishable: "not yours" confirms the
        // company exists, which turns the analyst into the enumeration tool the search
        // deliberately is not.
        assertThatThrownBy(() -> TenantContext.runAsResult(bank,
                () -> packs.forSubject(theirs, "Credit application", ANALYST)))
                .isInstanceOf(ResourceNotFoundException.class);

        // And the refusal happens before the exchange is asked, so a caller cannot spend one
        // inquiry per guess to find out which companies exist.
        assertThat(inquiries(bank)).isZero();
    }

    @Test
    @DisplayName("a contested record is withheld, and the pack says records can be")
    void contestedRecordsAreWithheldAndSaidSo() {
        declare(vodacom, "500.00", 200);
        declare(orange, "800.00", 300);

        TenantContext.runAs(orange, () -> rights.raise(
                SubjectRequestType.DISPUTE, IdentifierType.RCCM, rccm,
                "This was settled in March.", null));

        EvidencePackService.EvidencePack pack = assemble(vodacom);

        // Nought, not one. A dispute raised at Orange suppresses Vodacom's record about the same
        // company too — the harm of being wrongly listed accrues daily, so reporting stops
        // everywhere the day somebody contests it and before anybody decides who is right.
        //
        // I wrote this expecting one, on the assumption that a dispute suppresses only the
        // disputing operator's record. InstitutionCountTest carries a note saying its first
        // version made exactly the same assumption. Recording it twice because it is the thing
        // about this platform that is most often guessed wrong, and because it makes the caveat
        // below far more important than it looks: a contested subject does not merely go quieter,
        // it goes silent, and silence here is emphatically not absence of debt.
        assertThat(pack.exchange().institutionCount()).isZero();

        // Vodacom's own file is untouched. Suppression governs what the exchange tells others,
        // not what an operator may see of its own records.
        assertThat(pack.held().records()).hasSize(1);
        assertThat(pack.absent()).contains(Absence.CONTESTED_RECORDS_ARE_WITHHELD);
    }

    @Test
    @DisplayName("every pack says that no model wrote it")
    void nothingHereWasGenerated() {
        declare(vodacom, "500.00", 200);

        EvidencePackService.EvidencePack pack = assemble(vodacom);

        // The menu entry says "Dival AI analyst". A reader is entitled to know that nothing in
        // front of them was generated, and to be told it by the pack rather than by a footnote
        // somebody might remove.
        assertThat(pack.absent()).contains(Absence.NO_MODEL_PRODUCED_THIS);
        assertThat(pack.packVersion()).isEqualTo(EvidencePackService.PACK_VERSION);
    }

    @Test
    @DisplayName("a company with no national document says so, and the exchange will not confirm it")
    void aNameOnlySubjectNamesItsOwnGap() {
        UUID subject = TenantContext.runAsResult(vodacom, () -> debtRecords.declare(
                new DeclarationRequest(
                        List.of(new DeclarationRequest.SubmittedIdentifier(
                                IdentifierType.ACCOUNT_REFERENCE, "V" + UUID.randomUUID())),
                        "Sans Papiers SARL " + UUID.randomUUID(),
                        Subject.SubjectType.BUSINESS, null, "CD",
                        new BigDecimal("500.00"), "USD", "POSTPAID",
                        LocalDate.now().minusDays(200), true), null).record().getSubject().getId());

        EvidencePackService.EvidencePack pack = TenantContext.runAsResult(vodacom,
                () -> packs.forSubject(subject, "Credit review", ANALYST));

        // An account reference identifies this company to this operator and to nobody else, so
        // there is nothing to ask the exchange with. The pack names the concrete thing to go and
        // collect rather than reporting a quiet answer as a clean one.
        assertThat(pack.absent()).contains(Absence.NO_NATIONAL_DOCUMENT_IS_HELD);
    }

    // --- helpers ------------------------------------------------------------

    private EvidencePackService.EvidencePack assemble(UUID operator) {
        return TenantContext.runAsResult(operator, () -> packs.forSubject(
                subjectOf(operator), "Credit application, file 4471", ANALYST));
    }

    private UUID subjectOf(UUID operator) {
        return TenantContext.runAsResult(operator,
                () -> debtRecords.listOwn().get(0).getSubject().getId());
    }

    private UUID declare(UUID operator, String amount, int daysOverdue) {
        return TenantContext.runAsResult(operator, () -> debtRecords.declare(
                new DeclarationRequest(
                        List.of(new DeclarationRequest.SubmittedIdentifier(
                                IdentifierType.RCCM, rccm)),
                        "Kivu Pack " + rccm.substring(rccm.length() - 8),
                        Subject.SubjectType.BUSINESS, null, "CD",
                        new BigDecimal(amount), "USD", "POSTPAID",
                        LocalDate.now().minusDays(daysOverdue), true), null)
                .record().getSubject().getId());
    }

    private long inquiries(UUID operator) {
        return audit.findByTenantIdOrderByOccurredAtDesc(operator).stream()
                .filter(event -> event.getAction().equals("TIX_INQUIRY"))
                .count();
    }
}
