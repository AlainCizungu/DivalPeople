package ai.dival.dip.modules.recruitment;

import ai.dival.dip.common.audit.AuditService;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.error.ResourceNotFoundException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.ContractType;
import ai.dival.dip.modules.organizations.OrgUnitService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The hiring pipeline: requisitions, candidates, applications and interviews.
 *
 * <p>Offers are handled separately by {@link OfferService}, because accepting one crosses into
 * Core HR and deserves its own boundary.
 */
@Service
public class RecruitmentService {

    private final JobRequisitionRepository requisitions;
    private final CandidateRepository candidates;
    private final JobApplicationRepository applications;
    private final InterviewRepository interviews;
    private final OrgUnitService orgUnits;
    private final AuditService audit;

    public RecruitmentService(JobRequisitionRepository requisitions,
                              CandidateRepository candidates,
                              JobApplicationRepository applications,
                              InterviewRepository interviews,
                              OrgUnitService orgUnits,
                              AuditService audit) {
        this.requisitions = requisitions;
        this.candidates = candidates;
        this.applications = applications;
        this.interviews = interviews;
        this.orgUnits = orgUnits;
        this.audit = audit;
    }

    // --- requisitions ------------------------------------------------------

    @Transactional(readOnly = true)
    public List<JobRequisition> listRequisitions() {
        return requisitions.findByTenantIdOrderByCreatedAtDesc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public JobRequisition requisition(UUID id) {
        return requisitions.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new RequisitionNotFoundException(id));
    }

    @Transactional
    public JobRequisition createRequisition(String number, String title, ContractType type,
                                            int headcount, UUID orgUnitId, UUID requestedBy,
                                            String description, LocalDate targetStartDate,
                                            UUID actorId) {
        UUID tenantId = TenantContext.require();

        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A requisition needs a title");
        }
        if (headcount < 1) {
            throw new IllegalArgumentException("A requisition must be for at least one person");
        }

        String normalized = JobRequisition.normalizeNumber(number);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("A requisition number is required");
        }
        if (requisitions.findByTenantIdAndRequisitionNumber(tenantId, normalized).isPresent()) {
            throw new ConflictException("Requisition number already in use: " + normalized);
        }

        JobRequisition requisition =
                new JobRequisition(normalized, title, type, headcount, requestedBy);
        if (orgUnitId != null) {
            requisition.setOrgUnit(orgUnits.get(orgUnitId));
        }
        requisition.describe(description, targetStartDate);

        JobRequisition saved = requisitions.save(requisition);
        audit.recordSuccess("REQUISITION_CREATED", "JobRequisition",
                saved.getId().toString(), actorId);
        return saved;
    }

    @Transactional
    public JobRequisition submitRequisition(UUID id, UUID actorId) {
        JobRequisition requisition = requisition(id);
        requisition.submitForApproval();
        audit.recordSuccess("REQUISITION_SUBMITTED", "JobRequisition", id.toString(), actorId);
        return requisition;
    }

    /**
     * Approves the headcount.
     *
     * <p>The approver is recorded separately from the actor: they are the same person today, but
     * an approval whose authoriser is inferred from an audit log is not an approval.
     */
    @Transactional
    public JobRequisition approveRequisition(UUID id, UUID approverEmployeeId, UUID actorId) {
        JobRequisition requisition = requisition(id);
        requisition.approve(approverEmployeeId);
        audit.recordSuccess("REQUISITION_APPROVED", "JobRequisition", id.toString(), actorId);
        return requisition;
    }

    @Transactional
    public JobRequisition openRequisition(UUID id, UUID actorId) {
        JobRequisition requisition = requisition(id);
        requisition.open();
        audit.recordSuccess("REQUISITION_OPENED", "JobRequisition", id.toString(), actorId);
        return requisition;
    }

    @Transactional
    public JobRequisition holdRequisition(UUID id, UUID actorId) {
        JobRequisition requisition = requisition(id);
        requisition.putOnHold();
        audit.recordSuccess("REQUISITION_HELD", "JobRequisition", id.toString(), actorId);
        return requisition;
    }

    @Transactional
    public JobRequisition cancelRequisition(UUID id, UUID actorId) {
        JobRequisition requisition = requisition(id);
        requisition.cancel();
        audit.recordSuccess("REQUISITION_CANCELLED", "JobRequisition", id.toString(), actorId);
        return requisition;
    }

    // --- candidates --------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Candidate> listCandidates() {
        return candidates.findByTenantIdOrderByLastNameAscFirstNameAsc(TenantContext.require());
    }

    @Transactional(readOnly = true)
    public Candidate candidate(UUID id) {
        return candidates.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new CandidateNotFoundException(id));
    }

    /**
     * Records a candidate, or returns the one already on file for that address.
     *
     * <p>Idempotent on email, because someone applying for a second role is the same person and
     * splitting them into two records loses the history that makes them worth recognising.
     */
    @Transactional
    public Candidate registerCandidate(String firstName, String lastName, String email,
                                       CandidateSource source, UUID actorId) {
        UUID tenantId = TenantContext.require();

        if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank()) {
            throw new IllegalArgumentException("A candidate needs a first and last name");
        }
        String normalizedEmail = Candidate.normalizeEmail(email);
        if (normalizedEmail.isBlank() || !normalizedEmail.contains("@")) {
            throw new IllegalArgumentException("A candidate needs a valid email address");
        }

        return candidates.findByTenantIdAndEmail(tenantId, normalizedEmail)
                .orElseGet(() -> {
                    Candidate created = candidates.save(
                            new Candidate(firstName, lastName, normalizedEmail, source));
                    audit.recordSuccess("CANDIDATE_REGISTERED", "Candidate",
                            created.getId().toString(), actorId);
                    return created;
                });
    }

    // --- applications ------------------------------------------------------

    @Transactional(readOnly = true)
    public List<JobApplication> applicationsFor(UUID requisitionId) {
        requisition(requisitionId);
        return applications.findByTenantIdAndRequisitionIdOrderByCreatedAtDesc(
                TenantContext.require(), requisitionId);
    }

    @Transactional(readOnly = true)
    public JobApplication application(UUID id) {
        return applications.findByIdAndTenantId(id, TenantContext.require())
                .orElseThrow(() -> new ApplicationNotFoundException(id));
    }

    @Transactional
    public JobApplication apply(UUID requisitionId, UUID candidateId, LocalDate appliedOn,
                                UUID actorId) {
        UUID tenantId = TenantContext.require();
        JobRequisition requisition = requisition(requisitionId);
        Candidate candidate = candidate(candidateId);

        if (!requisition.getStatus().acceptsApplications()) {
            throw new ConflictException(
                    "This requisition is not open for applications");
        }
        applications.findByTenantIdAndRequisitionIdAndCandidateId(
                tenantId, requisitionId, candidateId).ifPresent(existing -> {
            throw new ConflictException("This candidate has already applied for this role");
        });

        JobApplication application = applications.save(
                new JobApplication(requisition, candidate, appliedOn));
        audit.recordSuccess("APPLICATION_RECEIVED", "JobApplication",
                application.getId().toString(), actorId);
        return application;
    }

    /**
     * Advances or closes an application.
     *
     * <p>A rejection must carry a reason. A pipeline that cannot say why people were turned down
     * cannot be reviewed for bias, which is the whole point of recording it.
     */
    @Transactional
    public JobApplication moveApplication(UUID id, ApplicationStatus next, String reason,
                                          UUID actorId) {
        JobApplication application = application(id);
        application.moveTo(next, reason);
        audit.recordSuccess("APPLICATION_" + next, "JobApplication", id.toString(), actorId);
        return application;
    }

    // --- interviews --------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Interview> interviewsFor(UUID applicationId) {
        application(applicationId);
        return interviews.findByTenantIdAndApplicationIdOrderByScheduledAtAsc(
                TenantContext.require(), applicationId);
    }

    @Transactional
    public Interview scheduleInterview(UUID applicationId, InterviewStage stage,
                                       InterviewMode mode, Instant scheduledAt,
                                       UUID interviewerEmployeeId, UUID actorId) {
        JobApplication application = application(applicationId);

        if (application.getStatus().isFinal()) {
            throw new ConflictException("This application is already closed");
        }
        if (scheduledAt == null) {
            throw new IllegalArgumentException("An interview needs a time");
        }

        Interview interview = interviews.save(
                new Interview(application, stage, mode, scheduledAt, interviewerEmployeeId));
        audit.recordSuccess("INTERVIEW_SCHEDULED", "Interview",
                interview.getId().toString(), actorId);
        return interview;
    }

    @Transactional
    public Interview submitInterviewFeedback(UUID interviewId,
                                             InterviewRecommendation recommendation,
                                             Integer score, String comments, UUID actorId) {
        Interview interview = interviews.findByIdAndTenantId(interviewId, TenantContext.require())
                .orElseThrow(() -> new InterviewNotFoundException(interviewId));
        interview.submitFeedback(recommendation, score, comments);
        audit.recordSuccess("INTERVIEW_FEEDBACK", "Interview", interviewId.toString(), actorId);
        return interview;
    }

    @Transactional
    public Interview cancelInterview(UUID interviewId, UUID actorId) {
        Interview interview = interviews.findByIdAndTenantId(interviewId, TenantContext.require())
                .orElseThrow(() -> new InterviewNotFoundException(interviewId));
        interview.cancel();
        audit.recordSuccess("INTERVIEW_CANCELLED", "Interview", interviewId.toString(), actorId);
        return interview;
    }

    public static class RequisitionNotFoundException extends ResourceNotFoundException {
        public RequisitionNotFoundException(UUID id) {
            super("Requisition not found: " + id);
        }
    }

    public static class CandidateNotFoundException extends ResourceNotFoundException {
        public CandidateNotFoundException(UUID id) {
            super("Candidate not found: " + id);
        }
    }

    public static class ApplicationNotFoundException extends ResourceNotFoundException {
        public ApplicationNotFoundException(UUID id) {
            super("Application not found: " + id);
        }
    }

    public static class InterviewNotFoundException extends ResourceNotFoundException {
        public InterviewNotFoundException(UUID id) {
            super("Interview not found: " + id);
        }
    }
}
