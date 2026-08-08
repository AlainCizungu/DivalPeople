package ai.dival.dip.modules.ingest;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accepting data, and remembering where it came from.
 *
 * <p>Deliberately stops short of interpreting anything. Rows arrive as cells and are stored as
 * cells; no column is mapped, no amount is parsed, no currency is inferred. That boundary is not
 * laziness — the roadmap requires mappings to be defined from the real telecom exports, and a
 * mapping invented now would be wrong in ways nobody could see until real data arrived and
 * disagreed with it. What this class guarantees is that when the mapping is written, the rows it
 * runs against are exactly what the operator sent.
 */
@Service
public class IngestService {

    private final SourceDatasetRepository sources;
    private final ImportBatchRepository batches;
    private final RawRecordRepository rawRecords;
    private final AuditService audit;
    private final ObjectMapper json;

    public IngestService(SourceDatasetRepository sources, ImportBatchRepository batches,
                         RawRecordRepository rawRecords, AuditService audit, ObjectMapper json) {
        this.sources = sources;
        this.batches = batches;
        this.rawRecords = rawRecords;
        this.audit = audit;
        this.json = json;
    }

    // --- sources ------------------------------------------------------------

    @Transactional
    public SourceDataset registerSource(String code, String name, SourceKind kind, UUID actorId) {
        UUID tenantId = TenantContext.require();
        String normalized = SourceDataset.normalizeCode(code);

        sources.findByTenantIdAndCode(tenantId, normalized).ifPresent(existing -> {
            throw new ConflictException(
                    "A source with code " + normalized + " already exists for this organisation. "
                            + "Successive deliveries of the same export should share one source, "
                            + "so that their history is one history.");
        });

        SourceDataset saved = sources.save(new SourceDataset(normalized, name, kind));
        audit.recordSuccess("INGEST_SOURCE_REGISTERED", "SourceDataset",
                saved.getId().toString(), actorId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SourceDataset> listSources() {
        return sources.findByTenantIdOrderByNameAsc(TenantContext.require());
    }

    // --- batches ------------------------------------------------------------

    /**
     * Stores a delivery and its rows verbatim.
     *
     * <p>The batch and every row commit together. A half-stored import is worse than a refused
     * one: the rows that made it would look like a complete file, and the count on the batch
     * would be the only clue otherwise.
     *
     * @param content the file exactly as received, used for the checksum and nothing else
     * @param rows    one map per row, header to cell, in file order
     */
    @Transactional
    public ImportBatch receive(UUID sourceId, String filename, byte[] content,
                              List<Map<String, String>> rows, UUID actorId) {
        UUID tenantId = TenantContext.require();

        SourceDataset source = sources.findByIdAndTenantId(sourceId, tenantId)
                .orElseThrow(() -> new SourceNotFoundException(sourceId));
        if (!source.isActive()) {
            throw new PolicyRefusedException(
                    "Source " + source.getCode() + " is no longer active and cannot accept "
                            + "deliveries.");
        }
        if (rows.isEmpty()) {
            throw new PolicyRefusedException("The file contains no rows.");
        }

        String checksum = sha256(content);

        // Checked before storing anything. The partial unique index in V20 is the real guarantee,
        // but it fires at flush time with a Postgres message; an operator who uploaded the same
        // file twice deserves a sentence naming the batch that already has it.
        batches.findByTenantIdAndChecksumSha256AndStatus(tenantId, checksum, BatchStatus.PUBLISHED)
                .ifPresent(existing -> {
                    throw new ConflictException(
                            "This exact file is already published as batch " + existing.getId()
                                    + ", received " + existing.getReceivedAt()
                                    + ". Publishing it again would double every exposure in it.");
                });

        ImportBatch batch = batches.save(
                new ImportBatch(source, filename, checksum, content.length, actorId));

        int rowNumber = 0;
        for (Map<String, String> row : rows) {
            // 1-based: row 1 is the first row of data as a person reading the file would count,
            // so a rejection can name a line the operator can actually go and look at.
            rowNumber++;
            rawRecords.save(new RawRecord(batch, rowNumber, toJson(row, rowNumber)));
        }
        batch.recordRowCount(rowNumber);

        audit.record("INGEST_BATCH_RECEIVED", "ImportBatch", batch.getId().toString(),
                AuditService.OUTCOME_SUCCESS, actorId,
                filename + "; " + rowNumber + " rows; sha256 " + checksum);
        return batch;
    }

    @Transactional
    public ImportBatch validate(UUID batchId, UUID actorId) {
        ImportBatch batch = requireOwnBatch(batchId);
        batch.markValidated();
        audit.recordSuccess("INGEST_BATCH_VALIDATED", "ImportBatch", batchId.toString(), actorId);
        return batch;
    }

    @Transactional
    public ImportBatch publish(UUID batchId, UUID actorId) {
        ImportBatch batch = requireOwnBatch(batchId);
        batch.publish();
        audit.record("INGEST_BATCH_PUBLISHED", "ImportBatch", batchId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId,
                batch.getRowCount() + " rows from " + batch.getFilename());
        return batch;
    }

    @Transactional
    public ImportBatch reject(UUID batchId, String reason, UUID actorId) {
        if (reason == null || reason.isBlank()) {
            // Symmetrical with leave refusals and payroll rejections elsewhere: a refusal the
            // person on the other end cannot act on is an unhelpful one.
            throw new PolicyRefusedException("Say why the batch is being rejected.");
        }
        ImportBatch batch = requireOwnBatch(batchId);
        batch.reject();
        audit.record("INGEST_BATCH_REJECTED", "ImportBatch", batchId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId, reason);
        return batch;
    }

    /**
     * Withdraws a published batch.
     *
     * <p>Does not yet remove anything derived, because nothing is derived from a batch yet — the
     * mapping that would create exposures from these rows is Phase 2's next piece and needs the
     * real file. When it exists, deletion of the derived records belongs here, and the raw rows
     * still stay: that this file was once live is part of the history.
     */
    @Transactional
    public ImportBatch revert(UUID batchId, String reason, UUID actorId) {
        ImportBatch batch = requireOwnBatch(batchId);
        batch.revert();
        audit.record("INGEST_BATCH_REVERTED", "ImportBatch", batchId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId, reason);
        return batch;
    }

    @Transactional(readOnly = true)
    public List<ImportBatch> listBatches() {
        return batches.findByTenantIdOrderByReceivedAtDesc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public List<RawRecord> rowsOf(UUID batchId) {
        requireOwnBatch(batchId);
        return rawRecords.findByBatchIdOrderByRowNumberAsc(batchId);
    }

    private ImportBatch requireOwnBatch(UUID batchId) {
        return batches.findByIdAndTenantId(batchId, TenantContext.require())
                .orElseThrow(() -> new BatchNotFoundException(batchId));
    }

    // --- helpers ------------------------------------------------------------

    /**
     * SHA-256 of the file as received.
     *
     * <p>Of the bytes, not of the parsed rows, and the distinction matters. An auditor holding
     * the operator's copy is asking "is this the file you sent us" — a digest of our
     * interpretation could not answer that, and would change if we ever improved the parser.
     */
    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM is required to provide SHA-256.
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Serialises a row, preserving column order.
     *
     * <p>LinkedHashMap because JSONB does not preserve key order and the column order of the
     * original file is information — it is how a person finds the field they mean when the
     * headers are ambiguous, which in real telecom exports they usually are. Storing an ordered
     * copy means the order survives even though the JSONB representation will not honour it.
     */
    private String toJson(Map<String, String> row, int rowNumber) {
        try {
            return json.writeValueAsString(new LinkedHashMap<>(row));
        } catch (JsonProcessingException ex) {
            throw new PolicyRefusedException(
                    "Row " + rowNumber + " could not be stored: its contents are not "
                            + "representable as JSON.");
        }
    }

    public static class SourceNotFoundException extends ResourceNotFoundException {
        public SourceNotFoundException(UUID id) {
            super("Source not found: " + id);
        }
    }

    /** Deliberately does not reveal whether the batch exists under another operator. */
    public static class BatchNotFoundException extends ResourceNotFoundException {
        public BatchNotFoundException(UUID id) {
            super("Import batch not found: " + id);
        }
    }
}
