package ai.dival.dip.modules.ingest;

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
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepting a delivery and remembering exactly what it said.
 *
 * <p>The claims worth proving here are not about parsing — nothing is parsed. They are that a
 * batch and its rows arrive together or not at all, that the same file cannot go live twice, that
 * a row survives byte for byte, and that one operator's file is invisible to another.
 */
@Transactional
@RequiresDocker
class IngestServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private IngestService ingest;
    @Autowired
    private RawRecordRepository rawRecords;

    private UUID operatorA;
    private UUID operatorB;
    private UUID sourceId;

    @BeforeEach
    void setUp() {
        operatorA = tenants.save(new Tenant("Ingest A", "ingest-a-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        operatorB = tenants.save(new Tenant("Ingest B", "ingest-b-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        TenantContext.set(operatorA);
        sourceId = ingest.registerSource("VODACOM_POSTPAID", "Vodacom postpaid receivables",
                SourceKind.SPREADSHEET, null).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static byte[] file(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    private static List<Map<String, String>> twoRows() {
        return List.of(
                Map.of("Customer", "Grand Horizon SARL", "Balance", "184000", "Currency", "USD"),
                Map.of("Customer", "Atlas Distribution", "Balance", "96200", "Currency", "USD"));
    }

    @Test
    @DisplayName("a delivery stores every row it claims to have")
    void rowsAreStoredWithTheBatch() {
        ImportBatch batch = ingest.receive(sourceId, "aug-2026.xlsx", file("bytes"), twoRows(), null);

        assertThat(batch.getRowCount()).isEqualTo(2);
        assertThat(rawRecords.countByBatchId(batch.getId())).isEqualTo(2);
        assertThat(batch.getStatus()).isEqualTo(BatchStatus.RECEIVED);
    }

    @Test
    @DisplayName("rows are numbered from one, as a person reads the file")
    void rowsAreNumberedFromOne() {
        ImportBatch batch = ingest.receive(sourceId, "aug.xlsx", file("bytes"), twoRows(), null);

        // Zero-based numbering here would mean an operator told "row 4 is invalid" opens their
        // spreadsheet and corrects the wrong line.
        assertThat(ingest.rowsOf(batch.getId()))
                .extracting(RawRecord::getRowNumber)
                .containsExactly(1, 2);
    }

    @Test
    @DisplayName("a row comes back saying exactly what it said")
    void payloadSurvivesUnchanged() {
        ingest.receive(sourceId, "aug.xlsx", file("bytes"),
                List.of(Map.of("Customer", "Société Générale d'Alimentation, SARL")), null);

        // Comma, accents and the apostrophe all intact. Real telecom exports are full of these,
        // and a normalisation applied on the way in is one nobody can undo later.
        assertThat(ingest.listBatches().get(0)).isNotNull();
        assertThat(rawRecords.findAll())
                .anyMatch(r -> r.getPayload().contains("Société Générale d'Alimentation, SARL"));
    }

    @Test
    @DisplayName("the checksum is of the file, not of what we made of it")
    void checksumIsOfTheBytes() {
        ImportBatch batch = ingest.receive(sourceId, "a.xlsx", file("the-original-bytes"),
                twoRows(), null);

        // Independently computed, so this test fails if the implementation changes what it digests.
        assertThat(batch.getChecksumSha256())
                .isEqualTo(IngestService.sha256("the-original-bytes".getBytes(StandardCharsets.UTF_8)))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the same file cannot be published twice")
    void republishingTheSameFileIsRefused() {
        byte[] content = file("identical");
        ImportBatch first = ingest.receive(sourceId, "aug.xlsx", content, twoRows(), null);
        ingest.validate(first.getId(), null);
        ingest.publish(first.getId(), null);

        // Every exposure in the file would otherwise be counted twice, which in a registry of
        // debts means people appearing to owe double.
        assertThatThrownBy(() -> ingest.receive(sourceId, "aug-copy.xlsx", content, twoRows(), null))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already published");
    }

    @Test
    @DisplayName("a file refused once can be corrected and sent again")
    void rejectionDoesNotBanTheFileForever() {
        byte[] content = file("identical");
        ImportBatch first = ingest.receive(sourceId, "aug.xlsx", content, twoRows(), null);
        ingest.reject(first.getId(), "Balance column is empty on 200 rows", null);

        // The uniqueness rule is about what is live, not about what was ever attempted.
        assertThatCode(() -> ingest.receive(sourceId, "aug.xlsx", content, twoRows(), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an empty file is refused rather than stored as a batch of nothing")
    void emptyDeliveryIsRefused() {
        assertThatThrownBy(() -> ingest.receive(sourceId, "empty.xlsx", file(""), List.of(), null))
                .isInstanceOf(PolicyRefusedException.class);
    }

    @Test
    @DisplayName("a batch cannot be published without being validated first")
    void publishRequiresValidation() {
        ImportBatch batch = ingest.receive(sourceId, "aug.xlsx", file("b"), twoRows(), null);

        assertThatThrownBy(() -> ingest.publish(batch.getId(), null))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("VALIDATED");
    }

    @Test
    @DisplayName("a published batch is reverted, never rejected")
    void publishedBatchesCannotBeRejected() {
        ImportBatch batch = ingest.receive(sourceId, "aug.xlsx", file("b"), twoRows(), null);
        ingest.validate(batch.getId(), null);
        ingest.publish(batch.getId(), null);

        assertThatThrownBy(() -> ingest.reject(batch.getId(), "changed my mind", null))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("Revert");
    }

    @Test
    @DisplayName("a rejection must say why")
    void rejectionNeedsAReason() {
        ImportBatch batch = ingest.receive(sourceId, "aug.xlsx", file("b"), twoRows(), null);

        assertThatThrownBy(() -> ingest.reject(batch.getId(), "  ", null))
                .isInstanceOf(PolicyRefusedException.class);
    }

    @Test
    @DisplayName("one operator's delivery is invisible to another")
    void batchesDoNotCrossOperators() {
        ImportBatch ofA = ingest.receive(sourceId, "aug.xlsx", file("b"), twoRows(), null);

        // Asked as operator B, A's batch does not exist — and the refusal says "not found" rather
        // than "not yours", which would confirm it is there.
        TenantContext.runAs(operatorB, () ->
                assertThatThrownBy(() -> ingest.rowsOf(ofA.getId()))
                        .isInstanceOf(IngestService.BatchNotFoundException.class));
    }

    @Test
    @DisplayName("two operators may use the same source code without colliding")
    void sourceCodesAreScopedToTheOperator() {
        // Every telecom will call their export something like POSTPAID. Uniqueness is per
        // operator, not global, or the second participant to join cannot name anything sensibly.
        assertThatCode(() -> TenantContext.runAs(operatorB, () ->
                ingest.registerSource("VODACOM_POSTPAID", "Their own export",
                        SourceKind.SPREADSHEET, null)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a source code is registered once per operator")
    void duplicateSourceCodeIsRefused() {
        assertThatThrownBy(() -> ingest.registerSource("vodacom_postpaid ", "Same thing again",
                SourceKind.SPREADSHEET, null))
                .as("normalisation means casing and spacing do not create a second source")
                .isInstanceOf(ConflictException.class);
    }
}
