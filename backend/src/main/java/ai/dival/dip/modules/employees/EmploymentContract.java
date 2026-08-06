package ai.dival.dip.modules.employees;

import ai.dival.dip.common.error.ConflictException;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "probation_outcome", length = 20)
    private ProbationOutcome probationOutcome;

    @Column(name = "probation_decided_at")
    private Instant probationDecidedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "probation_decided_by")
    private Employee probationDecidedBy;

    @Column(name = "probation_notes", length = 2000)
    private String probationNotes;

    @Column(name = "probation_notified_at")
    private Instant probationNotifiedAt;

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

    /**
     * Records how probation ended.
     *
     * <p>A decision, not a date passing. In most jurisdictions an unconfirmed probation quietly
     * becomes a confirmed one, so leaving it unrecorded still decides the outcome — it just leaves
     * nobody who can be shown to have decided it.
     *
     * @param decidedBy the person who made the call, kept separate from whoever typed it in
     */
    public void decideProbation(ProbationOutcome outcome, String notes, Employee decidedBy) {
        if (outcome == null) {
            throw new IllegalArgumentException("A probation decision needs an outcome");
        }
        if (probationEndDate == null) {
            throw new ConflictException("This contract has no probation period");
        }
        if (probationOutcome != null) {
            throw new ConflictException("Probation has already been decided");
        }
        if (!status.isCurrent()) {
            throw new ConflictException("This contract is not running");
        }
        // Ending someone's employment on probation is the one outcome that must be explained.
        if (outcome == ProbationOutcome.FAILED && (notes == null || notes.isBlank())) {
            throw new IllegalArgumentException("A failed probation needs a reason");
        }

        this.probationOutcome = outcome;
        this.probationNotes = notes;
        this.probationDecidedBy = decidedBy;
        this.probationDecidedAt = Instant.now();
    }

    /**
     * Gives probation more time.
     *
     * <p>Clears the decision so the extended period ends in a decision of its own. An extension
     * that closed the question would be an extension in name only.
     */
    public void extendProbation(LocalDate newProbationEnd) {
        if (newProbationEnd == null) {
            throw new IllegalArgumentException("An extension needs a new probation end date");
        }
        if (probationEndDate != null && newProbationEnd.isBefore(probationEndDate)) {
            throw new IllegalArgumentException("An extension cannot shorten probation");
        }
        if (newProbationEnd.isBefore(startDate)) {
            throw new IllegalArgumentException("Probation cannot end before the contract starts");
        }
        this.probationEndDate = newProbationEnd;
        this.probationOutcome = null;
        this.probationDecidedAt = null;
        this.probationDecidedBy = null;
        this.probationNotifiedAt = null;
    }

    public void markProbationNotified() {
        this.probationNotifiedAt = Instant.now();
    }

    /** Running, on probation, with the end in sight and nobody having decided yet. */
    public boolean probationUndecidedBy(LocalDate day) {
        return status.isCurrent()
                && probationEndDate != null
                && probationOutcome == null
                && !probationEndDate.isAfter(day);
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

    public ProbationOutcome getProbationOutcome() {
        return probationOutcome;
    }

    public Instant getProbationDecidedAt() {
        return probationDecidedAt;
    }

    public Employee getProbationDecidedBy() {
        return probationDecidedBy;
    }

    public String getProbationNotes() {
        return probationNotes;
    }

    public Instant getProbationNotifiedAt() {
        return probationNotifiedAt;
    }
}
