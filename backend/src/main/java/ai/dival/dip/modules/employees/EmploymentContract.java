package ai.dival.dip.modules.employees;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
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

/**
 * One engagement between a tenant and an employee.
 *
 * <p>Held separately from the employee so a person accumulates a history — a fixed term that
 * became permanent, a promotion, a renewal — instead of each change overwriting the last.
 */
@Entity
@Table(name = "employment_contract")
public class EmploymentContract extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", nullable = false, length = 30)
    private ContractType contractType;

    @Column(name = "job_title", nullable = false, length = 200)
    private String jobTitle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_unit_id")
    private OrgUnit orgUnit;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "probation_end_date")
    private LocalDate probationEndDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ContractStatus status = ContractStatus.DRAFT;

    @Column(name = "expiry_notified_at")
    private Instant expiryNotifiedAt;

    protected EmploymentContract() {
        // for JPA
    }

    public EmploymentContract(Employee employee, ContractType contractType, String jobTitle,
                              LocalDate startDate, LocalDate endDate) {
        this.employee = employee;
        this.contractType = contractType;
        this.jobTitle = jobTitle == null ? null : jobTitle.trim();
        this.startDate = startDate;
        this.endDate = endDate;
        this.status = ContractStatus.DRAFT;
    }

    public void activate() {
        this.status = ContractStatus.ACTIVE;
    }

    /** Reached its end date as planned. */
    public void end() {
        this.status = ContractStatus.ENDED;
    }

    /** Stopped early. Distinct from {@link #end()} because the reason matters to reporting. */
    public void terminate() {
        this.status = ContractStatus.TERMINATED;
    }

    public void setOrgUnit(OrgUnit orgUnit) {
        this.orgUnit = orgUnit;
    }

    public void setProbationEndDate(LocalDate probationEndDate) {
        this.probationEndDate = probationEndDate;
    }

    /** Extends a dated contract. Only meaningful while it is still running. */
    public void extendTo(LocalDate newEndDate) {
        if (newEndDate == null) {
            throw new IllegalArgumentException("An extension needs a new end date");
        }
        if (endDate != null && newEndDate.isBefore(endDate)) {
            throw new IllegalArgumentException("An extension cannot shorten a contract");
        }
        this.endDate = newEndDate;
        // The alert was about the previous date, so it must be allowed to fire again.
        this.expiryNotifiedAt = null;
    }

    public void markExpiryNotified() {
        this.expiryNotifiedAt = Instant.now();
    }

    /** True when the contract is running and its end date falls on or before the given day. */
    public boolean expiresOnOrBefore(LocalDate day) {
        return status.isCurrent() && endDate != null && !endDate.isAfter(day);
    }

    public Employee getEmployee() {
        return employee;
    }

    public ContractType getContractType() {
        return contractType;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public OrgUnit getOrgUnit() {
        return orgUnit;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public LocalDate getProbationEndDate() {
        return probationEndDate;
    }

    public ContractStatus getStatus() {
        return status;
    }

    public Instant getExpiryNotifiedAt() {
        return expiryNotifiedAt;
    }
}
