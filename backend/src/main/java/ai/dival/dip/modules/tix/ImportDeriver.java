package ai.dival.dip.modules.tix;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.ingest.ImportBatch;
import ai.dival.dip.modules.ingest.IngestService;
import ai.dival.dip.modules.ingest.RawRecord;
import ai.dival.dip.modules.ingest.SourceMapping;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Turning delivered rows into records, at last.
 *
 * <p>This is the join the platform has been missing since the provenance spine was built: a file
 * could be uploaded, stored, checksummed, profiled and published, and none of it reached the
 * exchange. The exposure screen has been honestly reporting zero imported records the whole time.
 *
 * <p>It waited on two answers, and both are now given rather than guessed. The delivery's as-at
 * date comes from the operator, on upload, because the export carries no dates and deriving one
 * from the aging buckets would have put 4,262 of 4,290 rows on the same day. And which column is
 * the amount comes from the operator's mapping, because the file has ten numeric columns and only
 * they know which is the balance.
 *
 * <p><strong>Every row goes through {@link DebtRecordService}, the same path a typed declaration
 * takes.</strong> The reporting threshold, the dunning requirement, the refusal of a future date,
 * the one-open-record-per-subject rule — all of them apply. An import that could enter the
 * registry through a side door would be a way to put people into a national database without
 * passing the controls that decide who belongs in it.
 *
 * <p>Lives in {@code tix} rather than {@code ingest}, and the direction is not arbitrary: tix
 * already depends on ingest for {@code RecordOrigin}, and putting this the other way round would
 * make the two modules mutually dependent. Ingest accepts and remembers; tix decides what enters
 * the registry. That is the same boundary the rest of the design keeps.
 */
@Service
public class ImportDeriver {

    private final IngestService ingest;
    private final DebtRecordService debtRecords;
    private final AuditService audit;

    /**
     * One transaction per row.
     *
     * <p>Expensive, and the alternative does not work. A row refused for being below the threshold
     * throws, and an exception caught inside a shared transaction still leaves it marked
     * rollback-only — so a single refused row would take the whole delivery down at commit, having
     * reported success for four thousand others. Spring's marker does not care that the exception
     * was handled.
     *
     * <p>{@code REQUIRES_NEW} rather than {@code REQUIRED} for the reason
     * {@link SubjectRightsService} documents at length: it must start a real transaction whatever
     * the caller is doing, or the isolation this depends on quietly disappears the first time
     * somebody annotates a method above it.
     */
    private final TransactionTemplate perRow;

    public ImportDeriver(IngestService ingest, DebtRecordService debtRecords, AuditService audit,
                         PlatformTransactionManager transactionManager) {
        this.ingest = ingest;
        this.debtRecords = debtRecords;
        this.audit = audit;
        this.perRow = new TransactionTemplate(transactionManager);
        this.perRow.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Derives debt records from every row of a published batch.
     *
     * @param dunningEvidence the operator asserting that the contractual chase ran before these
     *                        were reported. Required and recorded against whoever clicked: a typed
     *                        declaration carries this assertion per record, and an import has to
     *                        carry it too or four thousand records enter the registry with a
     *                        guarantee nobody made
     */
    public Report derive(UUID batchId, boolean dunningEvidence, UUID actorId) {
        // Bound before anything reads a row: every query below is tenant-scoped and would find
        // nothing useful without it.
        TenantContext.require();

        if (!dunningEvidence) {
            throw new PolicyRefusedException(
                    "Confirm that the contractual dunning process ran for these accounts before "
                            + "deriving records from them. A default may not be reported without "
                            + "it, and importing does not change that.");
        }

        ImportBatch batch = ingest.batchFor(batchId);
        LocalDate asAt = batch.getReportedAsAt();
        if (asAt == null) {
            throw new PolicyRefusedException(
                    "This delivery does not say what date it reflects, so nothing derived from it "
                            + "would have a retention clock. It was uploaded before that was "
                            + "asked for; upload it again to give it one.");
        }

        SourceMapping mapping = ingest.currentMapping(batch.getDataSource().getId())
                .orElseThrow(() -> new PolicyRefusedException(
                        "No mapping has been defined for this source, so nobody has said which "
                                + "column is the amount. Define one and derive again."));

        List<RawRecord> rows = ingest.rowsOf(batchId);
        if (mapping.identifiesByName()) {
            refuseIfNamesRepeat(mapping, rows);
        }

        List<Refusal> refusals = new ArrayList<>();
        int created = 0;

        for (RawRecord row : rows) {
            Map<String, String> cells = ingest.cellsOf(row);
            try {
                UUID rawRecordId = row.getId();
                DeclarationRequest request = buildRequest(mapping, cells, asAt);
                perRow.executeWithoutResult(status -> debtRecords.declareFromImport(
                        request, rawRecordId, DateSource.DERIVED, actorId));
                created++;
            } catch (RuntimeException refused) {
                // Caught per row and reported rather than thrown. A delivery of four thousand rows
                // where six are below the threshold should import three thousand nine hundred and
                // ninety-four and say which six it did not — the alternative is an operator
                // deleting rows from a spreadsheet by trial and error.
                refusals.add(new Refusal(row.getRowNumber(), refused.getMessage()));
            }
        }

        audit.record("TIX_BATCH_DERIVED", "ImportBatch", batchId.toString(),
                AuditService.OUTCOME_SUCCESS, actorId,
                created + " record(s) created, " + refusals.size() + " refused; mapping v"
                        + mapping.getVersionNumber() + "; dates derived from " + asAt);

        return new Report(rows.size(), created, refusals.size(),
                List.copyOf(refusals.stream().limit(MAX_REPORTED_REFUSALS).toList()),
                refusals.size() <= MAX_REPORTED_REFUSALS, asAt, mapping.getVersionNumber());
    }

    /**
     * One row, read through the mapping.
     *
     * <p>The amount is parsed here rather than left to the declaration, so a cell reading
     * {@code #N/A} is refused with a sentence naming the column instead of a number-format
     * exception from somewhere further down.
     */
    private DeclarationRequest buildRequest(SourceMapping mapping, Map<String, String> cells,
                                            LocalDate asAt) {
        String name = mapping.nameIn(cells);
        if (name.isBlank()) {
            throw new PolicyRefusedException(
                    "No value in \"" + mapping.getNameColumn() + "\".");
        }

        // Either the operator named a column holding an identifier, or they said this delivery
        // has none and the name is what identifies somebody. In the second case the name is used
        // twice — once as the subject's name and once as the operator-scoped key — which is
        // exactly what identifying by name means, rather than an accident worth hiding.
        IdentifierType type;
        String identifier;
        if (mapping.identifiesByName()) {
            type = IdentifierType.REPORTED_NAME;
            identifier = name;
        } else {
            type = IdentifierType.valueOf(mapping.getIdentifierType());
            identifier = mapping.identifierIn(cells);
            if (identifier.isBlank()) {
                throw new PolicyRefusedException(
                        "No value in \"" + mapping.getIdentifierColumn() + "\", so there is "
                                + "nothing to resolve this row to a subject.");
            }
        }

        String rawAmount = mapping.rawAmountIn(cells);
        BigDecimal amount;
        try {
            amount = new BigDecimal(rawAmount.replace(" ", "").replace(",", ""));
        } catch (NumberFormatException notANumber) {
            throw new PolicyRefusedException(
                    "\"" + mapping.getAmountColumn() + "\" reads \"" + rawAmount
                            + "\", which is not an amount.");
        }
        if (amount.signum() <= 0) {
            // Three rows of the profiled export are negative: the customer is in credit. Refused
            // rather than dropped, so the count is visible — "we silently ignored some rows" is a
            // bad sentence in an audit, and this is decision 4 in TIX_SOURCE_PROFILE.md arriving
            // as a refusal somebody can read.
            throw new PolicyRefusedException(
                    "\"" + mapping.getAmountColumn() + "\" is " + rawAmount
                            + ". A credit balance is not a debt.");
        }

        return new DeclarationRequest(
                List.of(new DeclarationRequest.SubmittedIdentifier(type, identifier)),
                name,
                Subject.SubjectType.valueOf(mapping.getSubjectType()),
                null,
                null,
                amount,
                mapping.getCurrency(),
                mapping.getServiceCategory(),
                // The whole reason the operator was asked for a date on upload.
                asAt,
                true);
    }

    /**
     * Refuses the whole delivery when two of its rows carry the same name.
     *
     * <p>The one guard that makes identifying by name defensible, and it has to refuse everything
     * rather than skip the offending rows. Two different companies resolving to one subject is the
     * worst outcome this system has — worse than importing nothing, because the debts of one land
     * on the other and every screen goes on working. Importing the rest and reporting two refusals
     * would leave the operator believing the delivery succeeded.
     *
     * <p>Checked before a single record is written, so nothing is half-imported and there is
     * nothing to undo.
     *
     * <p>Compared on the same normalisation the identifier itself uses, because that is what will
     * decide whether two rows collide once they are stored. Comparing the raw text would pass a
     * file that the database then merges.
     *
     * <p><strong>The limit this does not cover</strong>, said plainly rather than left to be
     * discovered: it sees one delivery. Two companies of the same name arriving in two deliveries
     * months apart resolve to one subject and nothing here notices. Only an identifier fixes that,
     * which is the argument for asking the operator for one.
     */
    private void refuseIfNamesRepeat(SourceMapping mapping, List<RawRecord> rows) {
        Map<String, Integer> seen = new HashMap<>();
        for (RawRecord row : rows) {
            String name = mapping.nameIn(ingest.cellsOf(row));
            if (name.isBlank()) {
                continue;
            }
            Integer first = seen.putIfAbsent(SubjectIdentifier.normalizeValue(name), row.getRowNumber());
            if (first != null) {
                throw new PolicyRefusedException(
                        "Rows " + first + " and " + row.getRowNumber() + " both read \"" + name
                                + "\" in \"" + mapping.getNameColumn() + "\". This mapping "
                                + "identifies subjects by name, so importing both would record "
                                + "two companies as one. Nothing has been imported. Either they "
                                + "are the same customer twice, or this delivery needs a column "
                                + "that tells them apart.");
            }
        }
    }

    /** Enough for somebody to see the pattern; the counts beside them are complete. */
    private static final int MAX_REPORTED_REFUSALS = 200;

    /**
     * What the derivation did.
     *
     * @param complete false when there were more refusals than {@code refusals} lists. The counts
     *                 stay exact; only the listing is truncated
     * @param asAt     the date every derived record's clock starts from, repeated back so nobody
     *                 has to go and look it up to understand the result
     */
    public record Report(int rows, int created, int refused, List<Refusal> refusals,
                         boolean complete, LocalDate asAt, int mappingVersion) {
    }

    public record Refusal(int rowNumber, String reason) {
    }
}
