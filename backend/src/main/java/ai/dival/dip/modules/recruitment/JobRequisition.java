package ai.dival.dip.modules.recruitment;

import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import ai.dival.dip.modules.employees.ContractType;
import ai.dival.dip.modules.organizations.OrgUnit;
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
import java.util.Locale;
import java.util.UUID;

/**
 * Authorisation to hire for a role.
 *
 * <p>A requisition is a budget commitment before it is a job advert, which is why approval sits
 * between drafting and opening. Headcount is a count rather than a flag because field roles are
 * routinely opened for several people, and the first hire should not close the requisition.
 */
@Entity
@Table(name = "job_requisition")
public class JobRequisition extends TenantOwnedEntity {

    @Column(name = "requisition_number", nullable = false, length = 50)
    private String requisitionNumber;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_unit_id")
    private OrgUnit orgUnit;

    @Column(name = "headcount", nullable = false)
    private int headcount = 1;

    @Column(name = "filled_count", nullable = false)
    private int filledCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", nullable = false, length = 30)
    private ContractType contractType;

    @Column(name = "description", length = 4000)
    private String description;

    @Column(name = "target_start_date")
    private LocalDate targetStartDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RequisitionStatus status = RequisitionStatus.DRAFT;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    protected JobRequisition() {
        // for JPA
    }

    public JobRequisition(String requisitionNumber, String title, ContractType contractType,
                          int headcount, UUID requestedBy) {
        this.requisitionNumber = normalizeNumber(requisitionNumber);
        this.title = title == null ? null : title.trim();
        this.contractType = contractType;
        this.headcount = headcount;
        this.requestedBy = requestedBy;
        this.status = RequisitionStatus.DRAFT;
    }

    public static String normalizeNumber(String number) {
        if (number == null) {
            return "";
        }
        return number.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    public void submitForApproval() {
        if (status != RequisitionStatus.DRAFT) {
            throw new ConflictException("Only a draft requisition can be submitted for approval");
        }
        this.status = RequisitionStatus.PENDING_APPROVAL;
    }

    /**
     * Records sign-off. The approver is stored because "who authorised this headcount" is the
     * first question asked when a budget is reviewed.
     */
    public void approve(UUID approverId) {
        if (status != RequisitionStatus.PENDING_APPROVAL) {
            throw new ConflictException("Only a requisition awaiting approval can be approved");
        }
        this.status = RequisitionStatus.APPROVED;
        this.approvedBy = approverId;
        this.approvedAt = Instant.now();
    }

    public void open() {
        if (status != RequisitionStatus.APPROVED && status != RequisitionStatus.ON_HOLD) {
            throw new ConflictException("Only an approved or paused requisition can be opened");
        }
        this.status = RequisitionStatus.OPEN;
    }

    public void putOnHold() {
        if (status != RequisitionStatus.OPEN) {
            throw new ConflictException("Only an open requisition can be put on hold");
        }
        this.status = RequisitionStatus.ON_HOLD;
    }

    public void cancel() {
        if (status.isClosed()) {
            throw new ConflictException("This requisition is already closed");
        }
        this.status = RequisitionStatus.CANCELLED;
    }

    /**
     * Records a hire against this requisition, closing it once the authorised headcount is met.
     *
     * @return {@code true} when this hire filled the requisition
     */
    public boolean recordHire() {
        if (filledCount >= headcount) {
            throw new ConflictException("This requisition has no remaining headcount");
        }
        filledCount++;
        if (filledCount == headcount) {
            status = RequisitionStatus.FILLED;
            return true;
        }
        return false;
    }

    public void setOrgUnit(OrgUnit orgUnit) {
        this.orgUnit = orgUnit;
    }

    public void describe(String description, LocalDate targetStartDate) {
        this.description = description;
        this.targetStartDate = targetStartDate;
    }

    public int remainingHeadcount() {
        return headcount - filledCount;
    }

    public String getRequisitionNumber() {
        return requisitionNumber;
    }

    public String getTitle() {
        return title;
    }

    public OrgUnit getOrgUnit() {
        return orgUnit;
    }

    public int getHeadcount() {
        return headcount;
    }

    public int getFilledCount() {
        return filledCount;
    }

    public ContractType getContractType() {
        return contractType;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getTargetStartDate() {
        return targetStartDate;
    }

    public RequisitionStatus getStatus() {
        return status;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public UUID getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }
}
