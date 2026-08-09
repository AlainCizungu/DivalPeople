package ai.dival.dip.modules.ingest;

import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Ingestion API.
 *
 * <p>Guarded by {@code TIX_DECLARANT}, and that is a considered choice rather than the obvious
 * one. The product documents name a "data steward" role, and publishing a batch is arguably a
 * larger act than declaring one record. But nothing derived is created from a batch yet, so
 * publishing currently has no effect any other operator can see — and inventing a role no
 * deployment's Keycloak has yet would mean this screen returning 403 until somebody recreates a
 * container. <strong>When publishing starts creating exposures, this needs revisiting</strong>:
 * at that point one action makes hundreds of people visible to competitors, and it should require
 * more than the right to report a single default.
 *
 * <p>That moment is now close. An upload carries the date the delivery is as at, which exists only
 * so that derived records have a retention clock — so the derivation is the next piece, and this
 * paragraph is the note to act on when it lands.
 */
@RestController
@RequestMapping("/api/v1/ingest")
public class IngestController {

    /**
     * Deliberately modest. Nothing streams yet — the file is read into memory, parsed, and every
     * row is inserted in one transaction, so a very large upload would hold all of it at once. A
     * limit that reflects what the code can actually do is more honest than one that reflects
     * what we would like it to do.
     */
    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;

    private final IngestService ingest;
    private final CurrentUserService currentUser;

    public IngestController(IngestService ingest, CurrentUserService currentUser) {
        this.ingest = ingest;
        this.currentUser = currentUser;
    }

    // --- sources ------------------------------------------------------------

    @GetMapping("/sources")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public List<SourceResponse> listSources() {
        return ingest.listSources().stream().map(SourceResponse::from).toList();
    }

    @PostMapping("/sources")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public ResponseEntity<SourceResponse> registerSource(
            @Valid @RequestBody RegisterSourceRequest request) {
        SourceDataset saved = ingest.registerSource(
                request.code(), request.name(), request.kind(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(SourceResponse.from(saved));
    }

    // --- mappings -----------------------------------------------------------

    /**
     * Records what this operator says its columns mean.
     *
     * <p>The platform never guesses. The profiled export has a balance column and nine numeric
     * aging buckets, and only somebody at the operator knows the buckets sum to the balance rather
     * than the reverse — so the choice is theirs, and this endpoint is where it is written down.
     */
    @PostMapping("/sources/{id}/mapping")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public ResponseEntity<MappingResponse> defineMapping(
            @PathVariable UUID id, @Valid @RequestBody DefineMappingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(MappingResponse.from(
                ingest.defineMapping(id, request.identifierColumn(), request.identifierType(),
                        request.nameColumn(), request.amountColumn(), request.currency(),
                        request.serviceCategory(), request.subjectType(), actorId())));
    }

    /** The mapping in force, or nothing when the operator has not defined one yet. */
    @GetMapping("/sources/{id}/mapping")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public ResponseEntity<MappingResponse> currentMapping(@PathVariable UUID id) {
        return ingest.currentMapping(id)
                .map(mapping -> ResponseEntity.ok(MappingResponse.from(mapping)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Every version. How a delivery published months ago is explained. */
    @GetMapping("/sources/{id}/mapping/history")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public List<MappingResponse> mappingHistory(@PathVariable UUID id) {
        return ingest.mappingHistory(id).stream().map(MappingResponse::from).toList();
    }

    // --- batches ------------------------------------------------------------

    /**
     * Uploads a delivery.
     *
     * <p>The file arrives as bytes and is parsed here rather than in the browser. The batch's
     * checksum is of exactly the bytes the rows were derived from; if the page had parsed and
     * posted JSON, the checksum and the stored rows would be two unrelated claims.
     */
    @PostMapping("/batches")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public ResponseEntity<BatchResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sourceId") UUID sourceId,
            // Required, and the requirement is the point. The profiled export carries no dates at
            // all, so without this a derived record's retention clock would start from a moment
            // DIP invented. The operator knows what their file reflects; the platform does not.
            @RequestParam("reportedAsAt")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reportedAsAt)
            throws IOException {
        if (file.isEmpty()) {
            throw new PolicyRefusedException("The file is empty.");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new PolicyRefusedException(
                    "The file is larger than " + (MAX_UPLOAD_BYTES / 1024 / 1024)
                            + " MB, which is more than this service can hold in memory while "
                            + "parsing. Split it, or wait for streaming ingestion.");
        }

        byte[] content = file.getBytes();
        // The format is decided from the bytes, not the filename. An operator who renames an
        // export has not changed what is inside it, and a .csv that is really a workbook would
        // otherwise be read as one very long line.
        List<Map<String, String>> rows = TabularReader.read(content);
        String filename = originalFilename(file);

        return ResponseEntity.status(HttpStatus.CREATED).body(BatchResponse.from(
                ingest.receive(sourceId, filename, content, rows, reportedAsAt, actorId())));
    }

    @GetMapping("/batches")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public List<BatchResponse> listBatches() {
        return ingest.listBatches().stream().map(BatchResponse::from).toList();
    }

    /**
     * The rows exactly as stored.
     *
     * <p>Capped, because a batch can hold far more rows than a page should render and an operator
     * inspecting an import wants to see what the first rows look like, not all of them.
     */
    @GetMapping("/batches/{id}/rows")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public List<RowResponse> rows(@PathVariable UUID id,
                                  @RequestParam(name = "limit", defaultValue = "50") int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return ingest.rowsOf(id).stream().limit(capped).map(RowResponse::from).toList();
    }

    /**
     * What is in the delivery, counted.
     *
     * <p>Fill rates, distinct counts, totals for the columns that are entirely numeric, and the
     * vocabulary of the ones small enough to have one. Nothing here identifies a column as an
     * amount or an identifier — that mapping is still unbuilt and still depends on decisions
     * nobody has taken. Counting needs none of them.
     */
    @GetMapping("/batches/{id}/profile")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public BatchProfiler.Profile profile(@PathVariable UUID id) {
        return ingest.profileOf(id);
    }

    @PostMapping("/batches/{id}/validate")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public BatchResponse validate(@PathVariable UUID id) {
        return BatchResponse.from(ingest.validate(id, actorId()));
    }

    @PostMapping("/batches/{id}/publish")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public BatchResponse publish(@PathVariable UUID id) {
        return BatchResponse.from(ingest.publish(id, actorId()));
    }

    @PostMapping("/batches/{id}/reject")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public BatchResponse reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return BatchResponse.from(ingest.reject(id, request.reason(), actorId()));
    }

    @PostMapping("/batches/{id}/revert")
    @PreAuthorize("hasRole('" + Roles.TIX_DECLARANT + "')")
    public BatchResponse revert(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return BatchResponse.from(ingest.revert(id, request.reason(), actorId()));
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    /**
     * The filename, stripped of any path.
     *
     * <p>A browser sends only a name, but a scripted client can send anything, and this string is
     * displayed back to other users of the tenant. Same reasoning as {@code FileService}: the
     * separators go, and an absent name becomes something rather than null.
     */
    private static String originalFilename(MultipartFile file) {
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank()) {
            return "upload.csv";
        }
        String stripped = name.replace("\\", "/");
        stripped = stripped.substring(stripped.lastIndexOf('/') + 1).trim();
        return stripped.isEmpty() ? "upload.csv" : stripped;
    }

    // --- payloads -----------------------------------------------------------

    public record RegisterSourceRequest(
            @NotBlank @Size(max = 60) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull SourceKind kind) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record SourceResponse(UUID id, String code, String name, SourceKind kind,
                                 boolean active) {
        static SourceResponse from(SourceDataset source) {
            return new SourceResponse(source.getId(), source.getCode(), source.getName(),
                    source.getKind(), source.isActive());
        }
    }

    /**
     * @param checksumSha256 shown in full on purpose. It is the operator's means of confirming
     *                       that what the platform holds is the file they sent, and a truncated
     *                       digest cannot be compared against anything.
     */
    public record BatchResponse(UUID id, UUID sourceId, String sourceCode, String filename,
                                String checksumSha256, long byteSize, int rowCount,
                                BatchStatus status, Instant receivedAt, Instant publishedAt) {
        static BatchResponse from(ImportBatch batch) {
            return new BatchResponse(
                    batch.getId(),
                    batch.getDataSource().getId(),
                    batch.getDataSource().getCode(),
                    batch.getFilename(),
                    batch.getChecksumSha256(),
                    batch.getByteSize(),
                    batch.getRowCount(),
                    batch.getStatus(),
                    batch.getReceivedAt(),
                    batch.getPublishedAt());
        }
    }

    /** @param payload the row as stored, verbatim, as a JSON object */
    /**
     * What the operator says the columns mean.
     *
     * <p>Currency and service category are values rather than columns, because the profiled export
     * carries neither and a mapping pointing at a column that does not exist is worse than one
     * that admits the file does not say.
     */
    public record DefineMappingRequest(
            @NotBlank @Size(max = 200) String identifierColumn,
            @NotBlank @Size(max = 30) String identifierType,
            @NotBlank @Size(max = 200) String nameColumn,
            @NotBlank @Size(max = 200) String amountColumn,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotBlank @Size(max = 60) String serviceCategory,
            @NotBlank @Size(max = 20) String subjectType) {
    }

    /** @param current false once a newer version has superseded this one */
    public record MappingResponse(UUID id, int versionNumber, String identifierColumn,
                                  String identifierType, String nameColumn, String amountColumn,
                                  String currency, String serviceCategory, String subjectType,
                                  boolean current, Instant definedAt, Instant supersededAt) {

        static MappingResponse from(SourceMapping mapping) {
            return new MappingResponse(
                    mapping.getId(),
                    mapping.getVersionNumber(),
                    mapping.getIdentifierColumn(),
                    mapping.getIdentifierType(),
                    mapping.getNameColumn(),
                    mapping.getAmountColumn(),
                    mapping.getCurrency(),
                    mapping.getServiceCategory(),
                    mapping.getSubjectType(),
                    mapping.isCurrent(),
                    mapping.getDefinedAt(),
                    mapping.getSupersededAt());
        }
    }

    public record RowResponse(UUID id, int rowNumber, String payload) {
        static RowResponse from(RawRecord record) {
            return new RowResponse(record.getId(), record.getRowNumber(), record.getPayload());
        }
    }
}
