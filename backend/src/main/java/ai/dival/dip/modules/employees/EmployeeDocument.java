package ai.dival.dip.modules.employees;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What a stored file means for a particular employee.
 *
 * <p>The bytes and their metadata live in the files module. This holds the id rather than an ORM
 * relation, so employees do not take a persistence dependency on files — the two modules stay
 * independently changeable, which is the whole point of the boundary.
 */
@Entity
@Table(name = "employee_document")
public class EmployeeDocument extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Column(name = "stored_file_id", nullable = false, updatable = false)
    private UUID storedFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 40)
    private DocumentType documentType;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "issued_on")
    private LocalDate issuedOn;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "expiry_notified_at")
    private Instant expiryNotifiedAt;

    protected EmployeeDocument() {
        // for JPA
    }

    public EmployeeDocument(Employee employee, UUID storedFileId, DocumentType documentType,
                            String title, LocalDate issuedOn, LocalDate expiresOn) {
        this.employee = employee;
        this.storedFileId = storedFileId;
        this.documentType = documentType;
        this.title = title == null ? null : title.trim();
        this.issuedOn = issuedOn;
        this.expiresOn = expiresOn;
    }

    /** Replacing the expiry date means the previous warning no longer applies. */
    public void renewUntil(LocalDate newExpiry) {
        if (newExpiry == null) {
            throw new IllegalArgumentException("A renewal needs a new expiry date");
        }
        this.expiresOn = newExpiry;
        this.expiryNotifiedAt = null;
    }

    public void markExpiryNotified() {
        this.expiryNotifiedAt = Instant.now();
    }

    public boolean expiresOnOrBefore(LocalDate day) {
        return expiresOn != null && !expiresOn.isAfter(day);
    }

    public Employee getEmployee() {
        return employee;
    }

    public UUID getStoredFileId() {
        return storedFileId;
    }

    public DocumentType getDocumentType() {
        return documentType;
    }

    public String getTitle() {
        return title;
    }

    public LocalDate getIssuedOn() {
        return issuedOn;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public Instant getExpiryNotifiedAt() {
        return expiryNotifiedAt;
    }
}
