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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * What the employer is proposing.
 *
 * <p>Carries pay, which makes it the most access-sensitive record in recruitment. Only one offer
 * per application may be open at a time: two outstanding offers is how a candidate ends up with
 * contradictory paperwork and a dispute nobody can settle from the record.
 */
@Entity
@Table(name = "job_offer")
public class JobOffer extends TenantOwnedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id", nullable = false)
    private JobApplication application;

    @Column(name = "job_title", nullable = false, length = 200)
    private String jobTitle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "org_unit_id")
    private OrgUnit orgUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "contract_type", nullable = false, length = 30)
    private ContractType contractType;

    @Column(name = "salary_amount", precision = 18, scale = 2)
    private BigDecimal salaryAmount;

    @Column(name = "salary_currency", length = 3)
    private String salaryCurrency;

    @Column(name = "proposed_start_date", nullable = false)
    private LocalDate proposedStartDate;

    /** Set for fixed-term work only, and carried into the contract on acceptance. */
    @Column(name = "proposed_end_date")
    private LocalDate proposedEndDate;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OfferStatus status = OfferStatus.DRAFT;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "responded_at")
    private Instant respondedAt;

    protected JobOffer() {
        // for JPA
    }

    public JobOffer(JobApplication application, String jobTitle, ContractType contractType,
                    LocalDate proposedStartDate, LocalDate proposedEndDate) {
        this.application = application;
        this.jobTitle = jobTitle == null ? null : jobTitle.trim();
        this.contractType = contractType;
        this.proposedStartDate = proposedStartDate;
        this.status = OfferStatus.DRAFT;
        setTerm(proposedEndDate);
    }

    /**
     * Sets how long the job lasts.
     *
     * <p>The same rule the contract table enforces, applied one step earlier: an offer that cannot
     * legally become a contract should be refused when it is written, not when it is accepted.
     */
    public void setTerm(LocalDate proposedEndDate) {
        if (proposedEndDate != null && proposedStartDate != null
                && proposedEndDate.isBefore(proposedStartDate)) {
            throw new IllegalArgumentException("An offer cannot end before it starts");
        }
        if (contractType != null && contractType.requiresEndDate() && proposedEndDate == null) {
            throw new IllegalArgumentException("A fixed-term offer must have an end date");
        }
        this.proposedEndDate = proposedEndDate;
    }

    public void setCompensation(BigDecimal amount, String currency) {
        if (amount != null && amount.signum() <= 0) {
            throw new IllegalArgumentException("A salary must be positive");
        }
        if (amount != null && (currency == null || currency.isBlank())) {
            throw new IllegalArgumentException("A salary amount needs a currency");
        }
        this.salaryAmount = amount;
        this.salaryCurrency = currency == null ? null : currency.trim().toUpperCase();
    }

    public void setOrgUnit(OrgUnit orgUnit) {
        this.orgUnit = orgUnit;
    }

    public void setExpiry(LocalDate expiresOn) {
        this.expiresOn = expiresOn;
    }

    public void send() {
        if (status != OfferStatus.DRAFT) {
            throw new ConflictException("Only a draft offer can be sent");
        }
        this.status = OfferStatus.SENT;
        this.sentAt = Instant.now();
    }

    public void accept() {
        requireOpenForResponse();
        this.status = OfferStatus.ACCEPTED;
        this.respondedAt = Instant.now();
    }

    public void decline() {
        requireOpenForResponse();
        this.status = OfferStatus.DECLINED;
        this.respondedAt = Instant.now();
    }

    /** Pulled by the employer. Possible before an answer, never after one. */
    public void withdraw() {
        if (!status.isOpen()) {
            throw new ConflictException("Only an open offer can be withdrawn");
        }
        this.status = OfferStatus.WITHDRAWN;
    }

    /** Lapsed without a response. Distinct from declined: silence is not a refusal. */
    public void expire() {
        if (status != OfferStatus.SENT) {
            throw new ConflictException("Only a sent offer can expire");
        }
        this.status = OfferStatus.EXPIRED;
    }

    private void requireOpenForResponse() {
        if (status != OfferStatus.SENT) {
            throw new ConflictException("Only an offer that has been sent can be answered");
        }
    }

    public boolean hasLapsedBy(LocalDate day) {
        return status == OfferStatus.SENT && expiresOn != null && expiresOn.isBefore(day);
    }

    public JobApplication getApplication() {
        return application;
    }

    public String getJobTitle() {
        return jobTitle;
    }

    public OrgUnit getOrgUnit() {
        return orgUnit;
    }

    public ContractType getContractType() {
        return contractType;
    }

    public BigDecimal getSalaryAmount() {
        return salaryAmount;
    }

    public String getSalaryCurrency() {
        return salaryCurrency;
    }

    public LocalDate getProposedStartDate() {
        return proposedStartDate;
    }

    public LocalDate getProposedEndDate() {
        return proposedEndDate;
    }

    public LocalDate getExpiresOn() {
        return expiresOn;
    }

    public OfferStatus getStatus() {
        return status;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getRespondedAt() {
        return respondedAt;
    }
}
