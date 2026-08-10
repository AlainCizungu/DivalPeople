package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.ingest.BatchStatus;
import ai.dival.dip.modules.ingest.IngestService;
import ai.dival.dip.modules.ingest.SourceKind;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Taking a delivery back, and taking back what it created.
 *
 * <p>Withdrawing a published batch used to retract the file and leave every record derived from it
 * live in the exchange. The javadoc on {@code IngestService.revert} had said for months that
 * deleting them "belongs here" once anything was derived from a batch; that day came, the note
 * stayed, and in between an operator could formally withdraw a delivery while the companies in it
 * went on being reported as in default.
 *
 * <p>Not hypothetical either. The currency of the amount column in both real exports is unconfirmed
 * — if it turns out to be francs rather than dollars, every imported record is wrong by a factor of
 * about 2,800 and the only correct response is to take the delivery back and send it again.
 */
@RequiresDocker
class ImportReversalTest extends AbstractIntegrationTest {

    private static final UUID STAFF = UUID.randomUUID();

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private IngestService ingest;
    @Autowired
    private ImportDeriver deriver;
    @Autowired
    private SubjectRightsService rights;
    @Autowired
    private DebtRecordRepository records;
    @Autowired
    private DebtRecordService debtRecords;
    @Autowired
    private ExchangeService exchange;

    private UUID operator;
    private UUID other;
    private String suffix;

    @BeforeEach
    void setUp() {
        operator = tenants.save(new Tenant("Revert A", "rev-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        other = tenants.save(new Tenant("Revert B", "rev-b-" + UUID.randomUUID(),
                Tenant.Edition.BANKING, "fr")).getId();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantContext.set(operator);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("the records the delivery created are gone from the operator's own book")
    void revertRemovesTheDerivedRecords() {
        UUID batch = deliverAndDerive();
        assertThat(records.findByTenantId(operator)).hasSize(2);

        ImportDeriver.Reversal reversal =
                deriver.revert(batch, "Sent in francs, not dollars.", STAFF);

        assertThat(reversal.recordsRemoved()).isEqualTo(2);
        assertThat(records.findByTenantId(operator)).isEmpty();
    }

    @Test
    @DisplayName("and gone from the exchange, which is the half that matters to the company")
    void revertRemovesThemFromTheExchange() {
        UUID batch = deliverAndDerive();
        assertThat(ask("CD/KIN/RCCM/" + suffix + "-1").outcome())
                .isEqualTo(InquiryResult.Outcome.OUTSTANDING_DEBT);

        deriver.revert(batch, "Sent in francs, not dollars.", STAFF);

        // The defect in one assertion. Before this, the operator had withdrawn the file and the
        // company was still being reported as in default to everybody who asked.
        //
        // CLEAR rather than NO_MATCH, and the difference is worth knowing: the subject survives
        // its last record until the nightly purge sweeps subjects nobody holds anything against.
        // So the exchange says "matched, and no adverse record", which is exactly true.
        assertThat(ask("CD/KIN/RCCM/" + suffix + "-1").outcome())
                .isEqualTo(InquiryResult.Outcome.CLEAR);
    }

    @Test
    @DisplayName("the delivered rows stay, because the file having been live is part of the history")
    void theRawRowsSurvive() {
        UUID batch = deliverAndDerive();

        ImportDeriver.Reversal reversal = deriver.revert(batch, "Wrong period.", STAFF);

        assertThat(reversal.rows()).isEqualTo(2);
        assertThat(ingest.rowsOf(batch))
                .as("the checksum and the rows are what an auditor compares against the "
                        + "operator's own copy; deleting them would erase the evidence too")
                .hasSize(2);
        assertThat(ingest.batchFor(batch).getStatus()).isEqualTo(BatchStatus.REVERTED);
    }

    @Test
    @DisplayName("a disputed record refuses the whole reversal, and says how many")
    void aContestedRecordRefusesTheReversal() {
        UUID batch = deliverAndDerive();

        rights.raise(SubjectRequestType.DISPUTE, IdentifierType.RCCM,
                "CD/KIN/RCCM/" + suffix + "-1", "I paid this in March.", STAFF);

        // Deleting it would close an open case with a statutory deadline by deleting the thing the
        // case is about. Whoever is disputing gets a decision, not a disappearance.
        assertThatThrownBy(() -> deriver.revert(batch, "Wrong currency.", STAFF))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("disputed");

        assertThat(records.findByTenantId(operator))
                .as("nothing was removed before the refusal")
                .hasSize(2);
    }

    @Test
    @DisplayName("another operator's records are untouched by this operator's reversal")
    void otherOperatorsAreUnaffected() {
        UUID batch = deliverAndDerive();
        TenantContext.runAs(other, () -> declareByHand("CD/KIN/RCCM/" + suffix + "-1"));

        deriver.revert(batch, "Wrong currency.", STAFF);

        assertThat(TenantContext.runAsResult(other, () -> records.findByTenantId(other)))
                .as("a reversal is scoped to the delivery, and a delivery belongs to one operator")
                .hasSize(1);
    }

    @Test
    @DisplayName("after a reversal the same file can be sent again")
    void theFileCanBeResent() {
        UUID batch = deliverAndDerive();
        deriver.revert(batch, "Sent in francs, not dollars.", STAFF);

        // The whole point of the feature: correct the mapping, send the delivery again. If the old
        // records survived, every row of the new one would be refused for a subject that already
        // has an open record.
        UUID again = deliverAndDerive();

        assertThat(again).isNotEqualTo(batch);
        assertThat(records.findByTenantId(operator)).hasSize(2);
    }

    // --- fixtures -----------------------------------------------------------

    private Map<String, String> row(String rccm, String name, String balance) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("RCCM", rccm);
        cells.put("Bsr", name);
        cells.put("Balance", balance);
        return cells;
    }

    /** A published, derived delivery of two rows. */
    private UUID deliverAndDerive() {
        UUID sourceId = ingest.registerSource("SRC-" + UUID.randomUUID(), "An export",
                SourceKind.SPREADSHEET, null).getId();
        ingest.defineMapping(sourceId, "RCCM", "RCCM", "Bsr", "Balance",
                "USD", "POSTPAID", "BUSINESS", null);

        UUID batchId = ingest.receive(sourceId, "export-" + UUID.randomUUID() + ".xlsx",
                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
                List.of(row("CD/KIN/RCCM/" + suffix + "-1", "Grand Horizon SARL", "18400.50"),
                        row("CD/KIN/RCCM/" + suffix + "-2", "Atlas Distribution", "9620.25")),
                LocalDate.now().minusDays(1), null).getId();
        ingest.validate(batchId, null);
        ingest.publish(batchId, null);
        deriver.derive(batchId, true, STAFF);
        return batchId;
    }

    private void declareByHand(String rccm) {
        debtRecords.declare(new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(IdentifierType.RCCM, rccm)),
                "Grand Horizon SARL", Subject.SubjectType.BUSINESS, null, "CD",
                new java.math.BigDecimal("500.00"), "USD", "POSTPAID",
                LocalDate.now().minusDays(30), true), null);
    }

    private InquiryResult ask(String rccm) {
        return TenantContext.runAsResult(other, () -> exchange.inquire(new InquiryRequest(
                List.of(new InquiryRequest.SubmittedIdentifier(IdentifierType.RCCM, rccm)),
                null, "Credit application, file 4471"), UUID.randomUUID()));
    }
}
