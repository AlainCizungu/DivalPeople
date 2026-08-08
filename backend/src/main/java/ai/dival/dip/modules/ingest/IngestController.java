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
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
    public ResponseEntity<BatchResponse> upload(@RequestParam("file") MultipartFile file,
                                                @RequestParam("sourceId") UUID sourceId)
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
        List<Map<String, String>> rows = CsvReader.read(content);
        String filename = originalFilename(file);

        return ResponseEntity.status(HttpStatus.CREATED).body(BatchResponse.from(
                ingest.receive(sourceId, filename, content, rows, actorId())));
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
    public record RowResponse(UUID id, int rowNumber, String payload) {
        static RowResponse from(RawRecord record) {
            return new RowResponse(record.getId(), record.getRowNumber(), record.getPayload());
        }
    }
}
