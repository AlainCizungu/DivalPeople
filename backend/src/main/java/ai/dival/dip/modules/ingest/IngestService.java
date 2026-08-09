package ai.dival.dip.modules.ingest;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final SourceDatasetRepository sources;
    private final ImportBatchRepository batches;
    private final RawRecordRepository rawRecords;
    private final SourceMappingRepository mappings;
    private final AuditService audit;
    private final ObjectMapper json;

    public IngestService(SourceDatasetRepository sources, ImportBatchRepository batches,
                         RawRecordRepository rawRecords, SourceMappingRepository mappings,
                         AuditService audit, ObjectMapper json) {
        this.sources = sources;
        this.batches = batches;
        this.rawRecords = rawRecords;
        this.mappings = mappings;
        this.audit = audit;
        this.json = json;
    }

    // --- mappings -----------------------------------------------------------

    /**
     * Records what this operator says its columns mean.
     *
     * <p>Supersedes rather than edits. A published delivery was derived through whichever mapping
     * was current when it ran, so rewriting one in place would leave that batch untraceable to the
     * rules that produced it — and immutable raw rows pointing at a mapping that has silently
     * changed are provenance pointing at nothing.
     *
     * <p>The columns are not checked against a delivery here. A mapping can legitimately be defined
     * before the first file arrives, and the check that matters happens where it can say something
     * useful: at derivation, against the actual header, naming the column that is missing.
     */
    @Transactional
    public SourceMapping defineMapping(UUID sourceId, String identifierColumn,
                                       String identifierType, String nameColumn,
                                       String amountColumn, String currency,
                                       String serviceCategory, String subjectType, UUID actorId) {
        UUID tenantId = TenantContext.require();
        SourceDataset source = sources.findByIdAndTenantId(sourceId, tenantId)
                .orElseThrow(() -> new SourceNotFoundException(sourceId));

        int nextVersion = mappings
                .findByTenantIdAndDataSourceIdAndSupersededAtIsNull(tenantId, sourceId)
                .map(current -> {
                    current.supersede();
                    return current.getVersionNumber() + 1;
                })
                .orElse(1);

        SourceMapping saved = mappings.save(new SourceMapping(
                source, identifierColumn, identifierType, nameColumn, amountColumn,
                currency, serviceCategory, subjectType, nextVersion, actorId));

        audit.record("INGEST_MAPPING_DEFINED", "SourceMapping", saved.getId().toString(),
                AuditService.OUTCOME_SUCCESS, actorId,
                "v" + nextVersion + " for " + source.getCode() + ": identifier="
                        + saved.getIdentifierColumn() + ", name=" + saved.getNameColumn()
                        + ", amount=" + saved.getAmountColumn());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<SourceMapping> currentMapping(UUID sourceId) {
        return mappings.findByTenantIdAndDataSourceIdAndSupersededAtIsNull(
                TenantContext.require(), sourceId);
    }

    /** Every version, newest first — how a batch published months ago is explained. */
    @Transactional(readOnly = true)
    public List<SourceMapping> mappingHistory(UUID sourceId) {
        return mappings.findByTenantIdAndDataSourceIdOrderByVersionNumberDesc(
                TenantContext.require(), sourceId);
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
     * @param content      the file exactly as received, used for the checksum and nothing else
     * @param rows         one map per row, header to cell, in file order
     * @param reportedAsAt what the operator says the delivery reflects. Not inferred from the
     *                     filename and not guessed from the contents — the file has no dates in
     *                     it, which is the entire reason this parameter exists
     */
    @Transactional
    public ImportBatch receive(UUID sourceId, String filename, byte[] content,
                              List<Map<String, String>> rows, LocalDate reportedAsAt,
                              UUID actorId) {
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
                new ImportBatch(source, filename, checksum, content.length, reportedAsAt,
                        actorId));

        long startedAt = System.nanoTime();

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

        // Measured rather than assumed. JDBC batching was turned on for exactly this loop, and
        // the honest way to know whether it helped is a number in the log rather than a claim in
        // a commit message. Note that the rows are still in the persistence context here: the
        // batches go to the database when the transaction commits, after this line, so this
        // measures the work of building them and not the write itself.
        log.info("Received {} rows from {} in {} ms (excluding commit)",
                rowNumber, filename, (System.nanoTime() - startedAt) / 1_000_000);
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

    /**
     * Counts what is in a delivery, without deciding what any of it means.
     *
     * <p>Reads every row rather than a sample. A fill rate computed from the first two hundred rows
     * of a spreadsheet is a fill rate for the first two hundred rows, and the columns that matter
     * in a real export are precisely the ones that are empty at the top — the aging buckets in the
     * Vodacom file are populated on a few dozen rows out of four thousand.
     *
     * <p>The whole batch is held in memory to do it, which the upload limit already implies: the
     * rows were parsed in memory to get here.
     */
    @Transactional(readOnly = true)
    public BatchProfiler.Profile profileOf(UUID batchId) {
        requireOwnBatch(batchId);
        List<Map<String, String>> rows = rawRecords.findByBatchIdOrderByRowNumberAsc(batchId)
                .stream()
                .map(record -> fromJson(record.getPayload(), record.getRowNumber()))
                .toList();
        return BatchProfiler.profile(rows);
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

    /**
     * Reads a stored row back.
     *
     * <p>The counterpart of {@link #toJson}. A row that cannot be read is a defect in this
     * platform rather than in the operator's file — the payload was written by
     * {@code writeValueAsString} and nothing may update it — so it fails loudly instead of being
     * skipped, which would quietly shrink every count on the profile.
     */
    private Map<String, String> fromJson(String payload, int rowNumber) {
        try {
            return json.readValue(payload, new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Stored row " + rowNumber + " is not readable as JSON", ex);
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
