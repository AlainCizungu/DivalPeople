package ai.dival.dip.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Rule 4 — "raw imports are immutable" — asked as a question rather than asserted as a comment.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}: a rolled-back test would prove
 * nothing about what a committed row can be made to do. And deliberately raw SQL, because that is
 * the only way to try the thing the Java API deliberately makes impossible. {@code RawRecord} has
 * no setters at all, so a test written through the entity would be testing the absence of a method
 * rather than the presence of a guarantee — and the guarantee has to hold against somebody who is
 * not using the entity.
 *
 * <p>These tests connect as the schema owner, per ADR 0002. That is the account a REVOKE cannot
 * stop, which is exactly why V20 adds a DO INSTEAD NOTHING rule as well: the privilege protects
 * against the application, the rule protects against everybody.
 */
@RequiresDocker
class RawRecordImmutabilityTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private IngestService ingest;
    @Autowired
    private RawRecordRepository rawRecords;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID rawRecordId;
    private String originalPayload;

    @BeforeEach
    void setUp() {
        UUID operator = tenants.save(new Tenant("Immutable", "imm-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        TenantContext.set(operator);

        UUID sourceId = ingest.registerSource("SRC-" + UUID.randomUUID(), "Export",
                SourceKind.SPREADSHEET, null).getId();
        ImportBatch batch = ingest.receive(sourceId, "aug.xlsx",
                "bytes".getBytes(StandardCharsets.UTF_8),
                List.of(Map.of("Customer", "Grand Horizon SARL", "Balance", "184000")), null);

        RawRecord stored = ingest.rowsOf(batch.getId()).get(0);
        rawRecordId = stored.getId();
        originalPayload = stored.getPayload();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("a stored row cannot be rewritten, whoever is asking")
    void rawRecordsCannotBeUpdated() {
        int reported = jdbc.update(
                "UPDATE raw_record SET payload = '{\"Customer\":\"Somebody Else\"}'::jsonb "
                        + "WHERE id = ?", rawRecordId);

        assertThat(reported).as("the rule discards the statement").isZero();
        assertThat(rawRecords.findById(rawRecordId))
                .get()
                .extracting(RawRecord::getPayload)
                .as("the evidence still says what the operator sent")
                .isEqualTo(originalPayload);
    }

    @Test
    @DisplayName("the row number cannot be moved either")
    void rowNumbersCannotBeReassigned() {
        // Worth its own test: renumbering is the subtle version of tampering. The payload would
        // still be intact while the row it claims to be stopped being true, and a rejection
        // report pointing at line 4 would send the operator to the wrong line.
        jdbc.update("UPDATE raw_record SET row_number = 99 WHERE id = ?", rawRecordId);

        assertThat(rawRecords.findById(rawRecordId))
                .get()
                .extracting(RawRecord::getRowNumber)
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a stored row CAN be deleted, because retention has to be able to erase it")
    void rawRecordsRemainErasable() {
        // The counterpart to immutability, and the reason this is not modelled the way audit_event
        // is. These rows carry personal data about people who never consented to being in a
        // registry; a row that cannot be deleted is a row that cannot be erased when its period
        // ends. Unchangeable while it exists, erasable when it should not.
        jdbc.update("DELETE FROM raw_record WHERE id = ?", rawRecordId);

        assertThat(rawRecords.findById(rawRecordId))
                .as("immutable and permanent are different properties")
                .isEmpty();
    }
}
