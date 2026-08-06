package ai.dival.dip.modules.files;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * What a stored object is, as opposed to the bytes themselves.
 *
 * <p>Holding this in the database is what lets authorization and audit run before anything
 * reaches storage. The storage key is random and never derived from the filename: a predictable
 * key in a shared bucket invites guessing at other tenants' documents.
 */
@Entity
@Table(name = "stored_file")
public class StoredFile extends TenantOwnedEntity {

    @Column(name = "storage_key", nullable = false, updatable = false, length = 200)
    private String storageKey;

    @Column(name = "original_filename", nullable = false, updatable = false, length = 300)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, updatable = false, length = 150)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "checksum_sha256", nullable = false, updatable = false, length = 64)
    private String checksumSha256;

    @Column(name = "category", nullable = false, updatable = false, length = 60)
    private String category;

    @Column(name = "uploaded_by", updatable = false)
    private UUID uploadedBy;

    protected StoredFile() {
        // for JPA
    }

    public StoredFile(String storageKey, String originalFilename, String contentType,
                      long sizeBytes, String checksumSha256, String category, UUID uploadedBy) {
        this.storageKey = storageKey;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.checksumSha256 = checksumSha256;
        this.category = category;
        this.uploadedBy = uploadedBy;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public String getCategory() {
        return category;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }
}
