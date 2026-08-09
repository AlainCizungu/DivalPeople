package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.ingest.ImportBatch;
import ai.dival.dip.modules.ingest.IngestService;
import ai.dival.dip.modules.ingest.RecordOrigin;
import ai.dival.dip.modules.ingest.SourceKind;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * The join that was missing: delivered rows becoming records.
 *
 * <p>Shaped after the real Vodacom export — an account reference unique on every row, a trading
 * name, a balance, and a status column that reads {@code Write off} throughout. The rows that get
 * refused here are the ones the profile predicted would be: 13.7% of that file is below the
 * reporting threshold and three balances are negative.
 *
 * <p>Not {@code @Transactional}. The derivation runs each row in its own transaction, because an
 * exception caught inside a shared one still marks it rollback-only — a single refused row would
 * otherwise take the whole delivery down at commit, having reported success for everything else.
 */
@RequiresDocker
class ImportDeriverTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private IngestService ingest;
    @Autowired
    private ImportDeriver deriver;
    @Autowired
    private DebtRecordRepository records;

    private UUID operator;
    private UUID sourceId;
    private String suffix;

    /** What the operator says the delivery reflects. Fixed, so no test depends on today. */
    private static final LocalDate AS_AT = LocalDate.now().minusDays(120);

    @BeforeEach
    void setUp() {
        operator = tenants.save(new Tenant("Derive A", "der-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        suffix = UUID.randomUUID().toString().substring(0, 8);
        TenantContext.set(operator);
        sourceId = ingest.registerSource("SRC-" + UUID.randomUUID(), "Write-off export",
                SourceKind.SPREADSHEET, null).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Map<String, String> row(String ref, String name, String balance) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("BPR_0", ref);
        cells.put("Bsr", name);
        cells.put("Balance", balance);
        cells.put("Status A", "Write off");
        return cells;
    }

    private void defineMapping() {
        ingest.defineMapping(sourceId, "BPR_0", "RCCM", "Bsr", "Balance",
                "USD", "POSTPAID", "BUSINESS", null);
    }

    private ImportBatch upload(List<Map<String, String>> rows, LocalDate asAt) {
        return ingest.receive(sourceId, "export-" + UUID.randomUUID() + ".xlsx",
                UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                rows, asAt, null);
    }

    private List<Map<String, String>> threeGoodRows() {
        return List.of(
                row("V" + suffix + "01", "Grand Horizon SARL " + suffix, "18400.50"),
                row("V" + suffix + "02", "Atlas Distribution " + suffix, "9620.25"),
                row("V" + suffix + "03", "Kin Logistique " + suffix, "4310.00"));
    }

    // --- the happy path -----------------------------------------------------

    @Test
    @DisplayName("every row of a mapped delivery becomes a record")
    void rowsBecomeRecords() {
        defineMapping();
        ImportBatch batch = upload(threeGoodRows(), AS_AT);

        ImportDeriver.Report report = deriver.derive(batch.getId(), true, null);

        assertThat(report.rows()).isEqualTo(3);
        assertThat(report.created()).isEqualTo(3);
        assertThat(report.refused()).isZero();
        assertThat(records.findByTenantId(operator)).hasSize(3);
    }

    @Test
    @DisplayName("a derived record names the row it came from and says its date was worked out")
    void provenanceSurvives() {
        defineMapping();
        ImportBatch batch = upload(threeGoodRows(), AS_AT);
        deriver.derive(batch.getId(), true, null);

        assertThat(records.findByTenantId(operator)).allSatisfy(record -> {
            // V20 requires an IMPORT to name its source row; this is that requirement arriving as
            // a fact rather than a constraint.
            assertThat(record.getOrigin()).isEqualTo(RecordOrigin.IMPORT);
            assertThat(record.getRawRecordId()).isNotNull();
            // The point of the whole exercise: when real dates arrive, these are the records to
            // re-derive, and this is the query that finds them.
            assertThat(record.getDefaultDateSource()).isEqualTo(DateSource.DERIVED);
            assertThat(record.getDefaultDate()).isEqualTo(AS_AT);
        });
    }

    @Test
    @DisplayName("the retention clock starts from the date the operator gave, not from today")
    void retentionRunsFromTheAsAtDate() {
        defineMapping();
        ImportBatch batch = upload(threeGoodRows(), AS_AT);
        deriver.derive(batch.getId(), true, null);

        // Three years for a first default, from the default date. Running it from today would
        // keep somebody listed for however long the operator took to send the file.
        assertThat(records.findByTenantId(operator)).allSatisfy(record ->
                assertThat(record.getRetentionUntil()).isEqualTo(AS_AT.plusYears(3)));
    }

    // --- the rules still apply ----------------------------------------------

    @Test
    @DisplayName("a row below the reporting threshold is refused, and the rest still import")
    void thresholdStillApplies() {
        defineMapping();
        List<Map<String, String>> rows = new ArrayList<>(threeGoodRows());
        rows.add(row("V" + suffix + "04", "Boutique Nzuzi " + suffix, "42.00"));
        ImportBatch batch = upload(rows, AS_AT);

        ImportDeriver.Report report = deriver.derive(batch.getId(), true, null);

        // 13.7% of the real file is below the floor. An import that skipped the threshold would be
        // a way into the registry that a typed declaration does not have.
        assertThat(report.created()).isEqualTo(3);
        assertThat(report.refused()).isEqualTo(1);
        assertThat(report.refusals()).singleElement().satisfies(refusal -> {
            assertThat(refusal.rowNumber()).isEqualTo(4);
            assertThat(refusal.reason()).contains("100");
        });
    }

    @Test
    @DisplayName("a credit balance is refused rather than silently dropped")
    void creditBalancesAreRefused() {
        defineMapping();
        List<Map<String, String>> rows = new ArrayList<>(threeGoodRows());
        rows.add(row("V" + suffix + "05", "Crédit SARL " + suffix, "-500.00"));
        ImportBatch batch = upload(rows, AS_AT);

        ImportDeriver.Report report = deriver.derive(batch.getId(), true, null);

        // Three rows of the profiled export are negative. "We silently ignored some rows" is a bad
        // sentence in an audit; this is decision 4 arriving as something somebody can read.
        assertThat(report.refused()).isEqualTo(1);
        assertThat(report.refusals()).singleElement().satisfies(refusal ->
                assertThat(refusal.reason()).contains("credit balance"));
    }

    @Test
    @DisplayName("an unreadable amount names the column rather than throwing a parse error")
    void unparseableAmountsAreNamed() {
        defineMapping();
        List<Map<String, String>> rows = new ArrayList<>(threeGoodRows());
        rows.add(row("V" + suffix + "06", "Descoped SARL " + suffix, "#N/A"));
        ImportBatch batch = upload(rows, AS_AT);

        ImportDeriver.Report report = deriver.derive(batch.getId(), true, null);

        assertThat(report.refusals()).singleElement().satisfies(refusal -> {
            assertThat(refusal.reason()).contains("Balance");
            assertThat(refusal.reason()).contains("#N/A");
        });
    }

    @Test
    @DisplayName("a row with no identifier cannot be resolved, and says so")
    void missingIdentifierIsRefused() {
        defineMapping();
        List<Map<String, String>> rows = new ArrayList<>(threeGoodRows());
        rows.add(row("", "Sans Référence " + suffix, "5000.00"));
        ImportBatch batch = upload(rows, AS_AT);

        ImportDeriver.Report report = deriver.derive(batch.getId(), true, null);

        assertThat(report.created()).isEqualTo(3);
        assertThat(report.refusals()).singleElement().satisfies(refusal ->
                assertThat(refusal.reason()).contains("BPR_0"));
    }

    @Test
    @DisplayName("one refused row does not roll back the ones that succeeded")
    void refusalsDoNotPoisonTheTransaction() {
        defineMapping();
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("V" + suffix + "A", "Below Floor " + suffix, "1.00"));
        rows.addAll(threeGoodRows());
        rows.add(row("V" + suffix + "B", "Also Below " + suffix, "2.00"));
        ImportBatch batch = upload(rows, AS_AT);

        ImportDeriver.Report report = deriver.derive(batch.getId(), true, null);

        // The reason each row gets its own transaction. An exception caught inside a shared one
        // still marks it rollback-only, so this would otherwise report three created and then
        // commit nothing.
        assertThat(report.created()).isEqualTo(3);
        assertThat(records.findByTenantId(operator)).hasSize(3);
    }

    // --- what it refuses to start ------------------------------------------

    @Test
    @DisplayName("a delivery with no mapping is refused with something to do about it")
    void unmappedDeliveryIsRefused() {
        ImportBatch batch = upload(threeGoodRows(), AS_AT);

        assertThatThrownBy(() -> deriver.derive(batch.getId(), true, null))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("No mapping has been defined");
    }

    @Test
    @DisplayName("a delivery with no as-at date is refused, because nothing would expire")
    void deliveryWithoutADateIsRefused() {
        defineMapping();
        ImportBatch batch = upload(threeGoodRows(), null);

        assertThatThrownBy(() -> deriver.derive(batch.getId(), true, null))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("retention clock");
    }

    @Test
    @DisplayName("importing does not waive the dunning requirement")
    void dunningMustBeAsserted() {
        defineMapping();
        ImportBatch batch = upload(threeGoodRows(), AS_AT);

        // A typed declaration carries this assertion per record. Four thousand records entering
        // the registry with a guarantee nobody made is the failure this prevents.
        assertThatThrownBy(() -> deriver.derive(batch.getId(), false, null))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("dunning");

        assertThat(records.findByTenantId(operator)).isEmpty();
    }

    @Test
    @DisplayName("a mapping naming a column the delivery lacks says which one")
    void mappingAgainstTheWrongHeaderIsNamed() {
        ingest.defineMapping(sourceId, "ACCOUNT_REF", "RCCM", "Bsr", "Balance",
                "USD", "POSTPAID", "BUSINESS", null);
        ImportBatch batch = upload(threeGoodRows(), AS_AT);

        ImportDeriver.Report report = deriver.derive(batch.getId(), true, null);

        // Every row fails, and each says the same useful thing: the column is not there. Silently
        // deriving with a blank identifier would refuse them all for a reason with no connection
        // to the cause.
        assertThat(report.created()).isZero();
        assertThat(report.refusals()).allSatisfy(refusal ->
                assertThat(refusal.reason()).contains("ACCOUNT_REF"));
    }

    @Test
    @DisplayName("the report says which mapping and which date produced it")
    void reportExplainsItself() {
        defineMapping();
        ImportBatch batch = upload(threeGoodRows(), AS_AT);

        ImportDeriver.Report report = deriver.derive(batch.getId(), true, null);

        assertThat(report.asAt()).isEqualTo(AS_AT);
        assertThat(report.mappingVersion()).isEqualTo(1);
        assertThat(report.complete()).isTrue();
    }
}
