package ai.dival.dip.modules.files;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.users.CurrentUserService;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Uploading and downloading tenant documents.
 *
 * <p>Bytes are served through the API rather than by redirecting to storage. That keeps
 * authorization and the audit entry on the same request as the read. Pre-signed URLs are the
 * right answer once storage is S3-compatible, and will need their own expiry and audit design.
 */
@RestController
@RequestMapping("/api/v1/files")
@PreAuthorize("hasAnyRole('" + Roles.HR_ADMIN + "', '" + Roles.HR_MANAGER
        + "', '" + Roles.TENANT_ADMIN + "')")
public class FileController {



    private final FileService files;
    private final CurrentUserService currentUser;

    public FileController(FileService files, CurrentUserService currentUser) {
        this.files = files;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<FileResponse> upload(@RequestParam("file") MultipartFile file,
                                               @RequestParam("category") String category)
            throws IOException {
        StoredFile stored = files.upload(
                file.getBytes(),
                file.getOriginalFilename(),
                file.getContentType(),
                category,
                actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(FileResponse.from(stored));
    }

    @GetMapping
    public List<FileResponse> listByCategory(@RequestParam("category") String category) {
        return files.listByCategory(category).stream().map(FileResponse::from).toList();
    }

    @GetMapping("/{id}")
    public FileResponse metadata(@PathVariable UUID id) {
        return FileResponse.from(files.metadata(id));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<Resource> download(@PathVariable UUID id) {
        FileService.FileContent content = files.download(id, actorId());

        // attachment, not inline: a document rendered in the browser origin is a stored-XSS
        // vector for any type the browser will execute.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(content.metadata().getOriginalFilename())
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CONTENT_TYPE, content.metadata().getContentType())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(content.metadata().getContentType()))
                .body(new ByteArrayResource(content.content()));
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    /** The storage key is never exposed: it is an internal locator, not a client concern. */
    public record FileResponse(
            UUID id,
            String filename,
            String contentType,
            long sizeBytes,
            String checksumSha256,
            String category,
            UUID uploadedBy,
            Instant createdAt) {

        static FileResponse from(StoredFile file) {
            return new FileResponse(
                    file.getId(),
                    file.getOriginalFilename(),
                    file.getContentType(),
                    file.getSizeBytes(),
                    file.getChecksumSha256(),
                    file.getCategory(),
                    file.getUploadedBy(),
                    file.getCreatedAt());
        }
    }
}
