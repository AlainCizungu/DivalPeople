package ai.dival.dip.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
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
 * Reading a batch the way the controller actually reads it: after the transaction has closed.
 *
 * <p><strong>Deliberately not {@code @Transactional}</strong>, and that is the entire test. Every
 * other test in this package is, so the persistence context stays open for their whole duration
 * and a lazy association can always be walked. Production does not work that way: the service
 * commits, the session closes, and only then does the controller build a response.
 *
 * <p>The defect this was written for: {@code BatchResponse.from} reads the source's code through
 * {@code batch.getDataSource()}, which is a lazy proxy. The imports screen returned "Internal
 * Server Error" on every load. Fourteen tests covered ingest and not one of them saw it, because
 * all fourteen held a session open.
 *
 * <p>It was also intermittent in the worst way. A batch whose source was already in the
 * persistence context — registered moments earlier in the same request — reads perfectly. Only a
 * batch from an earlier session fails, so the screen worked during development and broke the first
 * time somebody came back to it the next day.
 */
@RequiresDocker
class BatchResponseOutsideTransactionTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private IngestService ingest;

    private UUID operator;

    @BeforeEach
    void setUp() {
        operator = tenants.save(new Tenant("Lazy A", "lazy-" + UUID.randomUUID(),
                Tenant.Edition.TELECOM, "fr")).getId();
        TenantContext.set(operator);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("the batch list can be turned into a response after the session has closed")
    void listingDoesNotNeedAnOpenSession() {
        deliver();

        List<ImportBatch> batches = ingest.listBatches();

        // The call the controller makes. Without the join fetch this throws
        // LazyInitializationException, which reaches the browser as "Internal Server Error" and
        // says nothing about a lazy association.
        assertThatCode(() -> batches.forEach(IngestController.BatchResponse::from))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the source code survives into the response, rather than merely not throwing")
    void theResponseCarriesTheSourceCode() {
        String code = "SRC-" + UUID.randomUUID();
        deliver(code);

        IngestController.BatchResponse response =
                IngestController.BatchResponse.from(ingest.listBatches().get(0));

        // Asserted because "does not throw" would also pass if the code came back null. What the
        // screen needs is the operator's own name for the source, beside the file it delivered.
        assertThat(response.sourceCode()).isEqualTo(code);
    }

    @Test
    @DisplayName("one batch read by id is the same story as the list")
    void readingOneBatchDoesNotNeedAnOpenSession() {
        UUID batchId = deliver().getId();

        assertThatCode(() -> IngestController.BatchResponse.from(ingest.batchFor(batchId)))
                .doesNotThrowAnyException();
    }

    private ImportBatch deliver() {
        return deliver("SRC-" + UUID.randomUUID());
    }

    private ImportBatch deliver(String code) {
        UUID sourceId = ingest.registerSource(code, "An export", SourceKind.SPREADSHEET, null)
                .getId();
        return ingest.receive(sourceId, "export-" + UUID.randomUUID() + ".xlsx",
                UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
                List.of(Map.of("Customer", "Grand Horizon SARL", "Balance", "18400.50")),
                LocalDate.of(2026, 3, 31), null);
    }
}
