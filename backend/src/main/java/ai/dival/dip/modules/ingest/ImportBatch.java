package ai.dival.dip.modules.ingest;

import ai.dival.dip.common.error.PolicyRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One delivery of data from one source.
 *
 * <p>The unit an import is accepted, inspected, published or withdrawn as. Everything derived
 * from a file points back at its batch, which is what makes "undo that import" a sentence rather
 * than a project — and what lets an operator see what the platform made of a file before anybody
 * else is affected by it.
 *
 * <p>The state machine is enforced here rather than in a service, because these transitions are
 * the whole meaning of the type. A batch that could go from PUBLISHED back to RECEIVED would make
 * the published-checksum uniqueness index meaningless.
 */
@Entity
@Table(name = "import_batch")
public class ImportBatch {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "data_source_id", nullable = false, updatable = false)
    private SourceDataset dataSource;

    @Column(name = "filename", nullable = false, length = 300)
    private String filename;

    /**
     * SHA-256 of the file as received, before anything was parsed out of it.
     *
     * <p>Of the bytes, deliberately — not of the parsed rows. An auditor comparing this against
     * the operator's own copy is asking "is this the file you sent", and a checksum of our
     * interpretation could not answer that.
     */
    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BatchStatus status;

    /**
     * What the operator says the delivery reflects.
     *
     * <p>Supplied, never inferred. The profiled export carries no dates, so something has to
     * establish when its balances were true — and the choice was between DIP guessing from an
     * aging bucket and the operator saying. Deriving would have given 4,262 of 4,290 rows the same
     * date and a retention expiry clustered on one day; asking costs one field and moves the
     * assumption to the only party who can answer.
     *
     * <p>Nullable because batches predate the column. The derivation refuses to run without it,
     * which is the honest arrangement: an old batch is not wrong, it is unmappable until somebody
     * says what it is.
     */
    @Column(name = "reported_as_at")
    private LocalDate reportedAsAt;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ImportBatch() {
        // for JPA
    }

    public ImportBatch(SourceDataset dataSource, String filename, String checksumSha256,
                       long byteSize, LocalDate reportedAsAt, UUID uploadedBy) {
        this.tenantId = TenantContext.require();
        this.dataSource = dataSource;
        this.filename = filename;
        this.checksumSha256 = checksumSha256;
        this.byteSize = byteSize;
        // Validated here rather than only at the database, so an operator uploading a file dated
        // next week is told why instead of receiving a constraint name.
        if (reportedAsAt != null && reportedAsAt.isAfter(LocalDate.now())) {
            throw new PolicyRefusedException(
                    "A delivery cannot be as at a date in the future. Every record derived from "
                            + "it would start its retention clock before the clock had begun.");
        }
        this.reportedAsAt = reportedAsAt;
        this.uploadedBy = uploadedBy;
        this.status = BatchStatus.RECEIVED;
        this.receivedAt = Instant.now();
    }

    void recordRowCount(int rows) {
        this.rowCount = rows;
    }

    /** Checked and ready to publish. Row-level problems, if any, are known by now. */
    public void markValidated() {
        requireStatus(BatchStatus.RECEIVED, "validated");
        this.status = BatchStatus.VALIDATED;
    }

    /** Refused. The raw rows stay so the operator can be shown which ones and why. */
    public void reject() {
        if (status == BatchStatus.PUBLISHED) {
            throw new PolicyRefusedException(
                    "A published batch cannot be rejected. Revert it instead, which removes what "
                            + "was derived from it and records that it was once live.");
        }
        this.status = BatchStatus.REJECTED;
    }

    public void publish() {
        requireStatus(BatchStatus.VALIDATED, "published");
        this.status = BatchStatus.PUBLISHED;
        this.publishedAt = Instant.now();
    }

    /**
     * Withdraws a published batch.
     *
     * <p>{@code publishedAt} is deliberately left in place. The database requires it to be set
     * exactly when the status is PUBLISHED, so this clears it to satisfy that — but the fact that
     * the batch was once live survives in the status itself and in the audit trail. Erasing the
     * evidence that data was published for a period would defeat the purpose of the trail.
     */
    public void revert() {
        requireStatus(BatchStatus.PUBLISHED, "reverted");
        this.status = BatchStatus.REVERTED;
        this.publishedAt = null;
    }

    private void requireStatus(BatchStatus required, String action) {
        if (status != required) {
            throw new PolicyRefusedException(
                    "A batch can only be " + action + " from " + required + "; this one is "
                            + status + ".");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public SourceDataset getDataSource() {
        return dataSource;
    }

    public String getFilename() {
        return filename;
    }

    public String getChecksumSha256() {
        return checksumSha256;
    }

    public long getByteSize() {
        return byteSize;
    }

    public int getRowCount() {
        return rowCount;
    }

    public BatchStatus getStatus() {
        return status;
    }

    /** Null on batches delivered before the column existed. Those cannot be derived from. */
    public LocalDate getReportedAsAt() {
        return reportedAsAt;
    }

    public UUID getUploadedBy() {
        return uploadedBy;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }
}
