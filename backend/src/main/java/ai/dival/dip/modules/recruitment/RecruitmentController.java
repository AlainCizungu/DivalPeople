package ai.dival.dip.modules.recruitment;

import ai.dival.dip.common.security.Roles;
import ai.dival.dip.modules.employees.ContractType;
import ai.dival.dip.modules.users.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The hiring pipeline.
 *
 * <p>Everything here is restricted to recruiters and HR. Unlike the employee directory there is no
 * open read: candidates are people outside the organisation who applied in confidence, and a
 * colleague browsing who else applied for their own job is exactly the leak to prevent.
 */
@RestController
@RequestMapping("/api/v1/recruitment")
public class RecruitmentController {

    /** Interviewers are ordinary employees; ownership is checked below, not by role. */
    private static final String AUTHENTICATED = "isAuthenticated()";


    private static final String RECRUIT =
            "hasAnyRole('" + Roles.RECRUITER + "', '" + Roles.HR_ADMIN + "', '"
                    + Roles.HR_MANAGER + "', '" + Roles.TENANT_ADMIN + "')";

    private final RecruitmentService recruitment;
    private final CurrentUserService currentUser;

    public RecruitmentController(RecruitmentService recruitment, CurrentUserService currentUser) {
        this.recruitment = recruitment;
        this.currentUser = currentUser;
    }

    // --- requisitions ------------------------------------------------------

    @GetMapping("/requisitions")
    @PreAuthorize(RECRUIT)
    public List<RequisitionResponse> listRequisitions() {
        return recruitment.listRequisitions().stream().map(RequisitionResponse::from).toList();
    }

    @GetMapping("/requisitions/{id}")
    @PreAuthorize(RECRUIT)
    public RequisitionResponse requisition(@PathVariable UUID id) {
        return RequisitionResponse.from(recruitment.requisition(id));
    }

    @PostMapping("/requisitions")
    @PreAuthorize(RECRUIT)
    public ResponseEntity<RequisitionResponse> createRequisition(
            @Valid @RequestBody CreateRequisitionRequest request) {
        JobRequisition created = recruitment.createRequisition(
                request.requisitionNumber(), request.title(), request.contractType(),
                request.headcount(), request.orgUnitId(), request.requestedBy(),
                request.description(), request.targetStartDate(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED).body(RequisitionResponse.from(created));
    }

    @PostMapping("/requisitions/{id}/submit")
    @PreAuthorize(RECRUIT)
    public RequisitionResponse submit(@PathVariable UUID id) {
        return RequisitionResponse.from(recruitment.submitRequisition(id, actorId()));
    }

    /**
     * Approving headcount is a spending decision, so it sits above the recruiter role.
     */
    @PostMapping("/requisitions/{id}/approve")
    @PreAuthorize("hasAnyRole('" + Roles.HR_ADMIN + "', '" + Roles.HR_MANAGER + "', '"
            + Roles.TENANT_ADMIN + "')")
    public RequisitionResponse approve(@PathVariable UUID id,
                                       @Valid @RequestBody ApproveRequest request) {
        return RequisitionResponse.from(
                recruitment.approveRequisition(id, request.approverEmployeeId(), actorId()));
    }

    @PostMapping("/requisitions/{id}/open")
    @PreAuthorize(RECRUIT)
    public RequisitionResponse open(@PathVariable UUID id) {
        return RequisitionResponse.from(recruitment.openRequisition(id, actorId()));
    }

    @PostMapping("/requisitions/{id}/hold")
    @PreAuthorize(RECRUIT)
    public RequisitionResponse hold(@PathVariable UUID id) {
        return RequisitionResponse.from(recruitment.holdRequisition(id, actorId()));
    }

    @PostMapping("/requisitions/{id}/cancel")
    @PreAuthorize(RECRUIT)
    public RequisitionResponse cancel(@PathVariable UUID id) {
        return RequisitionResponse.from(recruitment.cancelRequisition(id, actorId()));
    }

    // --- candidates --------------------------------------------------------

    @GetMapping("/candidates")
    @PreAuthorize(RECRUIT)
    public List<CandidateResponse> listCandidates() {
        return recruitment.listCandidates().stream().map(CandidateResponse::from).toList();
    }

    @GetMapping("/candidates/{id}")
    @PreAuthorize(RECRUIT)
    public CandidateResponse candidate(@PathVariable UUID id) {
        return CandidateResponse.from(recruitment.candidate(id));
    }

    @PostMapping("/candidates")
    @PreAuthorize(RECRUIT)
    public ResponseEntity<CandidateResponse> registerCandidate(
            @Valid @RequestBody RegisterCandidateRequest request) {
        Candidate candidate = recruitment.registerCandidate(
                request.firstName(), request.lastName(), request.email(),
                request.source(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(CandidateResponse.from(candidate));
    }

    // --- applications ------------------------------------------------------

    @GetMapping("/requisitions/{id}/applications")
    @PreAuthorize(RECRUIT)
    public List<ApplicationResponse> applications(@PathVariable UUID id) {
        return recruitment.applicationsFor(id).stream().map(ApplicationResponse::from).toList();
    }

    @GetMapping("/applications/{id}")
    @PreAuthorize(RECRUIT)
    public ApplicationResponse application(@PathVariable UUID id) {
        return ApplicationResponse.from(recruitment.application(id));
    }

    @PostMapping("/applications")
    @PreAuthorize(RECRUIT)
    public ResponseEntity<ApplicationResponse> apply(@Valid @RequestBody ApplyRequest request) {
        JobApplication application = recruitment.apply(
                request.requisitionId(), request.candidateId(),
                request.appliedOn() == null ? LocalDate.now() : request.appliedOn(),
                actorId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApplicationResponse.from(application));
    }

    @PostMapping("/applications/{id}/status")
    @PreAuthorize(RECRUIT)
    public ApplicationResponse moveApplication(@PathVariable UUID id,
                                               @Valid @RequestBody MoveApplicationRequest request) {
        return ApplicationResponse.from(
                recruitment.moveApplication(id, request.status(), request.reason(), actorId()));
    }

    // --- interviews --------------------------------------------------------

    @GetMapping("/applications/{id}/interviews")
    @PreAuthorize(RECRUIT)
    public List<InterviewResponse> interviews(@PathVariable UUID id) {
        return recruitment.interviewsFor(id).stream().map(InterviewResponse::from).toList();
    }

    @PostMapping("/applications/{id}/interviews")
    @PreAuthorize(RECRUIT)
    public ResponseEntity<InterviewResponse> scheduleInterview(
            @PathVariable UUID id, @Valid @RequestBody ScheduleInterviewRequest request) {
        Interview interview = recruitment.scheduleInterview(
                id, request.stage(), request.mode(), request.scheduledAt(),
                request.interviewerEmployeeId(), actorId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(InterviewResponse.from(interview));
    }

    /**
     * Feedback is open to any authenticated member.
     *
     * <p>Interviewers are usually the hiring team, not recruiters, and requiring a recruitment
     * role to write up your own interview means the recruiter types it in for you — which is how
     * feedback stops being the interviewer's words.
     */
    @PostMapping("/interviews/{id}/feedback")
    @PreAuthorize(AUTHENTICATED)
    public InterviewResponse submitFeedback(@PathVariable UUID id,
                                            @Valid @RequestBody FeedbackRequest request) {
        return InterviewResponse.from(recruitment.submitInterviewFeedback(
                id, request.recommendation(), request.score(), request.comments(), actorId()));
    }

    @PostMapping("/interviews/{id}/cancel")
    @PreAuthorize(RECRUIT)
    public InterviewResponse cancelInterview(@PathVariable UUID id) {
        return InterviewResponse.from(recruitment.cancelInterview(id, actorId()));
    }

    private UUID actorId() {
        return currentUser.currentUserIdOrNull();
    }

    // --- requests ----------------------------------------------------------

    public record CreateRequisitionRequest(
            @NotBlank String requisitionNumber,
            @NotBlank String title,
            @NotNull ContractType contractType,
            @Min(1) int headcount,
            UUID orgUnitId,
            UUID requestedBy,
            String description,
            LocalDate targetStartDate) {
    }

    public record ApproveRequest(@NotNull UUID approverEmployeeId) {
    }

    public record RegisterCandidateRequest(
            @NotBlank String firstName,
            @NotBlank String lastName,
            @NotBlank String email,
            CandidateSource source) {
    }

    public record ApplyRequest(
            @NotNull UUID requisitionId,
            @NotNull UUID candidateId,
            LocalDate appliedOn) {
    }

    public record MoveApplicationRequest(
            @NotNull ApplicationStatus status,
            String reason) {
    }

    public record ScheduleInterviewRequest(
            @NotNull InterviewStage stage,
            InterviewMode mode,
            @NotNull Instant scheduledAt,
            UUID interviewerEmployeeId) {
    }

    public record FeedbackRequest(
            @NotNull InterviewRecommendation recommendation,
            Integer score,
            String comments) {
    }

    // --- responses ---------------------------------------------------------

    public record RequisitionResponse(
            UUID id,
            String requisitionNumber,
            String title,
            ContractType contractType,
            int headcount,
            int filledCount,
            RequisitionStatus status,
            UUID orgUnitId,
            String orgUnitName,
            UUID requestedBy,
            UUID approvedBy,
            String description,
            LocalDate targetStartDate) {

        static RequisitionResponse from(JobRequisition requisition) {
            return new RequisitionResponse(
                    requisition.getId(),
                    requisition.getRequisitionNumber(),
                    requisition.getTitle(),
                    requisition.getContractType(),
                    requisition.getHeadcount(),
                    requisition.getFilledCount(),
                    requisition.getStatus(),
                    requisition.getOrgUnit() == null ? null : requisition.getOrgUnit().getId(),
                    requisition.getOrgUnit() == null ? null : requisition.getOrgUnit().getName(),
                    requisition.getRequestedBy(),
                    requisition.getApprovedBy(),
                    requisition.getDescription(),
                    requisition.getTargetStartDate());
        }
    }

    public record CandidateResponse(
            UUID id,
            String firstName,
            String lastName,
            String email,
            String phone,
            CandidateSource source,
            String notes,
            UUID employeeId) {

        static CandidateResponse from(Candidate candidate) {
            return new CandidateResponse(
                    candidate.getId(),
                    candidate.getFirstName(),
                    candidate.getLastName(),
                    candidate.getEmail(),
                    candidate.getPhone(),
                    candidate.getSource(),
                    candidate.getNotes(),
                    candidate.getEmployeeId());
        }
    }

    public record ApplicationResponse(
            UUID id,
            UUID requisitionId,
            String requisitionTitle,
            UUID candidateId,
            String candidateName,
            ApplicationStatus status,
            LocalDate appliedOn,
            String outcomeReason) {

        static ApplicationResponse from(JobApplication application) {
            return new ApplicationResponse(
                    application.getId(),
                    application.getRequisition().getId(),
                    application.getRequisition().getTitle(),
                    application.getCandidate().getId(),
                    application.getCandidate().displayName(),
                    application.getStatus(),
                    application.getAppliedOn(),
                    application.getOutcomeReason());
        }
    }

    public record InterviewResponse(
            UUID id,
            UUID applicationId,
            InterviewStage stage,
            InterviewMode mode,
            Instant scheduledAt,
            UUID interviewerId,
            InterviewStatus status,
            InterviewRecommendation recommendation,
            Integer score,
            String comments,
            Instant submittedAt) {

        static InterviewResponse from(Interview interview) {
            return new InterviewResponse(
                    interview.getId(),
                    interview.getApplication().getId(),
                    interview.getStage(),
                    interview.getMode(),
                    interview.getScheduledAt(),
                    interview.getInterviewerId(),
                    interview.getStatus(),
                    interview.getRecommendation(),
                    interview.getScore(),
                    interview.getComments(),
                    interview.getSubmittedAt());
        }
    }
}
