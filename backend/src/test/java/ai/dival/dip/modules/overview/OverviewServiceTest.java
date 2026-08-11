package ai.dival.dip.modules.overview;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.ingest.IngestService;
import ai.dival.dip.modules.ingest.SourceKind;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import ai.dival.dip.modules.tix.DebtRecordService;
import ai.dival.dip.modules.tix.DeclarationRequest;
import ai.dival.dip.modules.tix.IdentifierType;
import ai.dival.dip.modules.tix.Subject;
import ai.dival.dip.modules.tix.SubjectRequestType;
import ai.dival.dip.modules.tix.SubjectRightsService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The front door's figures, and the two ways a dashboard lies.
 *
 * <p>The first is counting somebody else's data. Every figure here is scoped to one operator, and
 * a platform where a dashboard leaks a rival's totals is finished before it starts.
 *
 * <p>The second is quieter and is what most of these tests are about: <strong>answering a question
 * the caller was not allowed to ask, with a zero.</strong> "No overdue cases" and "overdue cases
 * are not yours to see" are different statements, and the first is far more reassuring. So a
 * section the caller's roles do not cover comes back absent, and the screen says so in words.
 */
@RequiresDocker
class OverviewServiceTest extends AbstractIntegrationTest {

    private static final UUID STAFF = UUID.randomUUID();

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private OverviewService overview;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private SubjectRightsService rights;
    @Autowired
    private IngestService ingest;

    private UUID operator;
    private UUID other;
    private String suffix;

    @BeforeEach
    void setUp() {
        operator = tenants.save(new Tenant("Overview A", "ov-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        other = tenants.save(new Tenant("Overview B", "ov-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantContext.set(operator);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("the register counts this operator's records and nobody else's")
    void theRegisterIsScopedToTheCaller() {
        declare("1");
        declare("2");
        TenantContext.runAs(other, () -> declare("3"));

        OverviewService.Overview result = overview.forCaller(true, true);

        assertThat(result.register().total())
                .as("a dashboard that counts a rival's book is the end of the exchange")
                .isEqualTo(2);
        assertThat(result.register().outstanding()).isEqualTo(2);
    }

    @Test
    @DisplayName("a caller who cannot declare gets no register at all, rather than nought")
    void theRegisterIsAbsentWithoutTheRole() {
        declare("1");

        OverviewService.Overview result = overview.forCaller(false, false);

        // Absent, not zero. Zero would tell an inquirer that their organisation has declared
        // nothing, which is a claim about the data rather than about their permissions.
        assertThat(result.register()).isNull();
        assertThat(result.rights()).isNull();
        assertThat(result.deliveries()).isNull();
    }

    @Test
    @DisplayName("an open case is counted, and a decided one stops being counted")
    void openCasesAreCounted() {
        declare("1");
        UUID caseId = rights.raise(SubjectRequestType.DISPUTE, IdentifierType.RCCM,
                rccm("1"), "I paid this in March.", STAFF).getId();

        assertThat(overview.forCaller(true, true).rights().open()).isEqualTo(1);

        rights.verifyIdentity(caseId, "National ID seen in person.", STAFF);
        rights.close(caseId, false, "The invoices were produced.", STAFF);

        assertThat(overview.forCaller(true, true).rights().open())
                .as("a decided case is not waiting on anybody")
                .isZero();
    }

    @Test
    @DisplayName("a withdrawn case is closed too, and stops nagging")
    void withdrawnCasesAreNotOpen() {
        declare("1");
        UUID caseId = rights.raise(SubjectRequestType.DISPUTE, IdentifierType.RCCM,
                rccm("1"), "I paid this in March.", STAFF).getId();

        rights.withdraw(caseId, "Called on 14 May and asked to drop it.", STAFF);

        // The queue exists to show what needs a decision. Somebody who stopped pursuing their case
        // needs none, and leaving it on the count would train whoever reads this to ignore it.
        assertThat(overview.forCaller(true, true).rights().open()).isZero();
    }

    @Test
    @DisplayName("a delivery left unvalidated is counted as waiting on somebody")
    void abandonedDeliveriesAreCounted() {
        UUID sourceId = ingest.registerSource("SRC-" + UUID.randomUUID(), "An export",
                SourceKind.SPREADSHEET, null).getId();
        ingest.receive(sourceId, "export-" + UUID.randomUUID() + ".xlsx",
                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
                List.of(Map.of("Customer", "Grand Horizon " + suffix, "Balance", "18400.50")),
                LocalDate.now().minusDays(1), null);

        OverviewService.Overview result = overview.forCaller(true, true);

        assertThat(result.deliveries().awaitingValidation()).isEqualTo(1);
        assertThat(result.deliveries().awaitingPublication()).isZero();
        assertThat(result.deliveries().published()).isZero();
    }

    @Test
    @DisplayName("nothing awaits erasure while every record is inside its retention period")
    void nothingAwaitsErasureNormally() {
        declare("1");

        // The figure that should read nought every morning. If it does not, the nightly purge has
        // stopped and no other screen in the product would say so.
        assertThat(overview.forCaller(true, true).register().awaitingErasure()).isZero();
    }

    @Test
    @DisplayName("the figures carry the date they were counted on")
    void theOverviewSaysWhenItWasCounted() {
        // So a tab left open overnight is visibly stale rather than quietly wrong.
        assertThat(overview.forCaller(false, false).asOf()).isEqualTo(LocalDate.now());
    }

    private String rccm(String n) {
        return "CD/KIN/RCCM/" + suffix + "-" + n;
    }

    private void declare(String n) {
        debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(IdentifierType.RCCM, rccm(n))),
                "Grand Horizon " + suffix + "-" + n, Subject.SubjectType.BUSINESS, null, "CD",
                new BigDecimal("500.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(30), true), null);
    }
}
