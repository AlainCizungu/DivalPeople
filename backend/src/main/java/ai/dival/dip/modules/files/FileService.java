package ai.dival.dip.modules.files;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Uploading and retrieving files.
 *
 * <p>Order matters here: validate, then hash, then store, then record. Writing bytes before the
 * metadata row exists risks an orphan in storage that nothing knows about and nothing cleans up.
 *
 * <p>Every read is audited. Documents are the most sensitive thing the platform holds — identity
 * papers, payslips, investigation material — and "who opened this" is the question that gets
 * asked after an incident.
 */
@Service
public class FileService {

    private final StoredFileRepository files;
    private final FileStorage storage;
    private final FileProperties properties;
    private final AuditService audit;

    public FileService(StoredFileRepository files, FileStorage storage,
                       FileProperties properties, AuditService audit) {
        this.files = files;
        this.storage = storage;
        this.properties = properties;
        this.audit = audit;
    }

    /**
     * Stores an upload.
     *
     * <p>The whole payload is read into memory to hash it before writing. That bounds memory by
     * the configured maximum size, which is why the size limit is enforced first.
     */
    @Transactional
    public StoredFile upload(byte[] content, String originalFilename, String contentType,
                             String category, UUID actorId) {
        UUID tenantId = TenantContext.require();

        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("The uploaded file is empty");
        }
        if (content.length > properties.maxSizeBytes()) {
            throw new FileTooLargeException(properties.maxSizeBytes());
        }

        String normalizedType = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();
        if (!properties.allowedContentTypes().contains(normalizedType)) {
            throw new UnsupportedFileTypeException(normalizedType);
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("A file category is required");
        }

        String checksum = sha256(content);

        // Random, and namespaced by tenant so an operator inspecting the bucket can tell whose
        // object is whose without consulting the database.
        String storageKey = tenantId + "/" + UUID.randomUUID();

        try (InputStream stream = new ByteArrayInputStream(content)) {
            storage.store(storageKey, stream, content.length, normalizedType);
        } catch (IOException ex) {
            throw new FileStorageException("Could not store the uploaded file", ex);
        }

        StoredFile stored = files.save(new StoredFile(
                storageKey, safeFilename(originalFilename), normalizedType,
                content.length, checksum, category.trim(), actorId));

        audit.recordSuccess("FILE_UPLOADED", "StoredFile", stored.getId().toString(), actorId);
        return stored;
    }

    @Transactional(readOnly = true)
    public StoredFile metadata(UUID id) {
        return files.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new FileNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<StoredFile> listByCategory(String category) {
        return files.findByTenantIdAndCategoryOrderByCreatedAtDesc(TenantContext.require(), category);
    }

    /** Reads the bytes of a file the caller's tenant owns, recording that it was opened. */
    @Transactional
    public FileContent download(UUID id, UUID actorId) {
        StoredFile file = metadata(id);
        byte[] content;
        try (InputStream stream = storage.read(file.getStorageKey())) {
            content = stream.readAllBytes();
        } catch (IOException ex) {
            throw new FileStorageException("Could not read the stored file", ex);
        }

        audit.recordSuccess("FILE_DOWNLOADED", "StoredFile", id.toString(), actorId);
        return new FileContent(file, content);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required and unavailable", ex);
        }
    }

    /**
     * Keeps the name for display only, stripped of anything path-like.
     *
     * <p>It never reaches the filesystem — the storage key does — but it is echoed back to
     * browsers in a download header, so it must not carry separators or control characters.
     */
    private String safeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "file";
        }
        String cleaned = filename.replaceAll("[\\\\/\\p{Cntrl}\"]", "").trim();
        if (cleaned.isBlank()) {
            return "file";
        }
        return cleaned.length() > 300 ? cleaned.substring(0, 300) : cleaned;
    }

    public record FileContent(StoredFile metadata, byte[] content) {
    }

    public static class FileNotFoundException extends ResourceNotFoundException {
        public FileNotFoundException(UUID id) {
            super("File not found: " + id);
        }
    }

    public static class FileTooLargeException extends IllegalArgumentException {
        public FileTooLargeException(long maxBytes) {
            super("The file exceeds the maximum size of " + maxBytes + " bytes");
        }
    }

    public static class UnsupportedFileTypeException extends IllegalArgumentException {
        public UnsupportedFileTypeException(String contentType) {
            super("Files of type '" + contentType + "' are not accepted");
        }
    }

    public static class FileStorageException extends RuntimeException {
        public FileStorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
