package ai.dival.dip.modules.ingest;

import ai.dival.dip.common.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One row of a delivered file, exactly as it arrived.
 *
 * <p>This is the bottom of the lineage. Every figure the platform ever shows about an exposure
 * should be traceable to one of these, and the value of that is entirely in the row being
 * unaltered: evidence that can be edited after the fact is not evidence. V20 enforces it twice —
 * the application role has no UPDATE privilege, and a database rule discards one anyway, because
 * a REVOKE cannot bind the account that owns the schema.
 *
 * <p><strong>No setters, and no entity-level mutation of any kind.</strong> There is nothing to
 * change here by design. If a row is wrong, the correct action is a new batch, not an edit.
 *
 * <p>The payload is stored as JSON and left uninterpreted. Normalising on the way in would mean
 * inventing a column layout before anybody has seen a real Vodacom export, and the roadmap says
 * plainly to define mappings from the real spreadsheets. Cells are kept as text because that is
 * what a spreadsheet cell is until somebody decides what it means — and deciding later, in code
 * that can be corrected, is the difference between a bad mapping being a bug and it being data
 * loss.
 */
@Entity
@Table(name = "raw_record")
public class RawRecord {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "batch_id", nullable = false, updatable = false)
    private ImportBatch batch;

    /** 1-based, as a person reading the spreadsheet counts, so a rejection can name a line. */
    @Column(name = "row_number", nullable = false, updatable = false)
    private int rowNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    protected RawRecord() {
        // for JPA
    }

    /**
     * @param payload the row as JSON. Serialised by the caller rather than accepted as a Map, so
     *                that exactly what will be stored is what was checked — passing a Map would
     *                leave the JSON representation to Hibernate and Jackson defaults, and the
     *                stored bytes are the thing under audit here.
     */
    public RawRecord(ImportBatch batch, int rowNumber, String payload) {
        this.tenantId = TenantContext.require();
        this.batch = batch;
        this.rowNumber = rowNumber;
        this.payload = payload;
        this.receivedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public ImportBatch getBatch() {
        return batch;
    }

    public int getRowNumber() {
        return rowNumber;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }
}
