package ai.dival.dip.modules.recruitment;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.ContractType;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.employees.EmploymentContractService;
import ai.dival.dip.modules.organizations.OrgUnitService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Offers, and the handover from recruitment to employment.
 *
 * <p>Kept apart from {@link RecruitmentService} because accepting an offer is where this module
 * stops describing a process and starts creating people: an employee, a contract, a filled
 * requisition. That crossing deserves a visible seam.
 */
@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    private final JobOfferRepository offers;
    private final RecruitmentService recruitment;
    private final EmployeeService employees;
    private final EmploymentContractService contracts;
    private final OrgUnitService orgUnits;
    private final AuditService audit;

    public OfferService(JobOfferRepository offers, RecruitmentService recruitment,
                        EmployeeService employees, EmploymentContractService contracts,
                        OrgUnitService orgUnits, AuditService audit) {
        this.offers = offers;
        this.recruitment = recruitment;
        this.employees = employees;
        this.contracts = contracts;
        this.orgUnits = orgUnits;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<JobOffer> forApplication(UUID applicationId) {
        recruitment.application(applicationId);
        return offers.findByTenantIdAndApplicationIdOrderByCreatedAtDesc(
                TenantContext.require(), applicationId);
    }

    @Transactional(readOnly = true)
    public JobOffer get(UUID id) {
        return offers.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new OfferNotFoundException(id));
    }

    @Transactional
    public JobOffer draft(UUID applicationId, String jobTitle, ContractType contractType,
                          LocalDate proposedStartDate, LocalDate proposedEndDate,
                          BigDecimal salaryAmount, String salaryCurrency, UUID orgUnitId,
                          LocalDate expiresOn, UUID actorId) {
        JobApplication application = recruitment.application(applicationId);

        if (application.getStatus().isFinal()) {
            throw new ConflictException("This application is already closed");
        }
        if (jobTitle == null || jobTitle.isBlank()) {
            throw new IllegalArgumentException("An offer needs a job title");
        }
        if (proposedStartDate == null) {
            throw new IllegalArgumentException("An offer needs a proposed start date");
        }
        if (contractType == null) {
            throw new IllegalArgumentException("An offer needs a contract type");
        }

        JobOffer offer = new JobOffer(
                application, jobTitle, contractType, proposedStartDate, proposedEndDate);
        offer.setCompensation(salaryAmount, salaryCurrency);
        if (orgUnitId != null) {
            offer.setOrgUnit(orgUnits.get(orgUnitId));
        }
        offer.setExpiry(expiresOn);

        JobOffer saved = offers.save(offer);
        audit.recordSuccess("OFFER_DRAFTED", "JobOffer", saved.getId().toString(), actorId);
        return saved;
    }

    /** Sending the offer also moves the application, so the two cannot disagree. */
    @Transactional
    public JobOffer send(UUID id, UUID actorId) {
        JobOffer offer = get(id);
        offer.send();

        JobApplication application = offer.getApplication();
        if (application.getStatus() != ApplicationStatus.OFFER) {
            application.moveTo(ApplicationStatus.OFFER, null);
        }

        audit.recordSuccess("OFFER_SENT", "JobOffer", id.toString(), actorId);
        return offer;
    }

    /**
     * The candidate accepts, and becomes an employee.
     *
     * <p>One transaction covering the offer, the application, the requisition headcount, the
     * employee record and their first contract. A hire that half-happens leaves somebody starting
     * work on Monday with no contract, or a requisition that can be filled twice.
     *
     * @param employeeNumber the number payroll will use; recruitment does not invent it
     */
    @Transactional
    public Employee acceptAndHire(UUID offerId, String employeeNumber, UUID actorId) {
        JobOffer offer = get(offerId);
        offer.accept();

        JobApplication application = offer.getApplication();
        Candidate candidate = application.getCandidate();
        JobRequisition requisition = application.getRequisition();

        // Refuses if the authorised headcount is already used up.
        boolean nowFilled = requisition.recordHire();

        Employee employee = employees.hire(
                employeeNumber,
                candidate.getFirstName(),
                candidate.getLastName(),
                offer.getProposedStartDate(),
                offer.getOrgUnit() == null ? null : offer.getOrgUnit().getId(),
                actorId);

        // Drafted, not activated: the contract takes effect on the agreed start date, and someone
        // who has accepted an offer for next quarter is not yet working here.
        contracts.draft(
                employee.getId(),
                offer.getContractType(),
                offer.getJobTitle(),
                offer.getProposedStartDate(),
                offer.getProposedEndDate(),
                offer.getOrgUnit() == null ? null : offer.getOrgUnit().getId(),
                null,
                actorId);

        application.moveTo(ApplicationStatus.HIRED, null);
        candidate.linkEmployee(employee.getId());

        audit.recordSuccess("OFFER_ACCEPTED", "JobOffer", offerId.toString(), actorId);
        // Ids, not names. A log stream has different retention, access control and export
        // paths from the database that row-level security so carefully protects, and an id is
        // joinable by anyone with legitimate access and meaningless to anyone without.
        log.info("Hired candidate {} as employee {}{}",
                candidate.getId(), employee.getEmployeeNumber(),
                nowFilled ? "; requisition now filled" : "");

        return employee;
    }

    @Transactional
    public JobOffer decline(UUID id, String reason, UUID actorId) {
        JobOffer offer = get(id);
        offer.decline();

        // The candidate said no, so the application closes as withdrawn rather than rejected —
        // the distinction matters when reviewing why offers fail.
        offer.getApplication().moveTo(ApplicationStatus.WITHDRAWN, reason);

        audit.recordSuccess("OFFER_DECLINED", "JobOffer", id.toString(), actorId);
        return offer;
    }

    @Transactional
    public JobOffer withdraw(UUID id, String reason, UUID actorId) {
        JobOffer offer = get(id);
        offer.withdraw();
        offer.getApplication().moveTo(ApplicationStatus.REJECTED,
                reason == null || reason.isBlank() ? "Offer withdrawn by the employer" : reason);
        audit.recordSuccess("OFFER_WITHDRAWN", "JobOffer", id.toString(), actorId);
        return offer;
    }

    /**
     * Marks offers whose deadline has passed.
     *
     * <p>Swept rather than derived on read, so a lapsed offer reads the same on every screen
     * instead of depending on which code path asked.
     */
    @Transactional
    public int expireLapsedOffers(LocalDate today) {
        List<JobOffer> lapsed = offers.findLapsed(TenantContext.require(), today);
        lapsed.forEach(JobOffer::expire);
        return lapsed.size();
    }

    public static class OfferNotFoundException extends ResourceNotFoundException {
        public OfferNotFoundException(UUID id) {
            super("Offer not found: " + id);
        }
    }
}
