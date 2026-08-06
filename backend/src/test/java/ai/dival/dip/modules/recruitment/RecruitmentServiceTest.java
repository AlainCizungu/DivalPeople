package ai.dival.dip.modules.recruitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.ContractType;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@RequiresDocker
class RecruitmentServiceTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private RecruitmentService recruitment;

    private UUID tenantA;

    @BeforeEach
    void setUp() {
        tenantA = tenants.save(new Tenant("R A", "r-a-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantA);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private JobRequisition requisition(String number, int headcount) {
        return recruitment.createRequisition(number, "Field Engineer", ContractType.PERMANENT,
                headcount, null, null, null, LocalDate.of(2026, 3, 1), null);
    }

    private JobRequisition openRequisition(String number, int headcount) {
        JobRequisition req = requisition(number, headcount);
        recruitment.submitRequisition(req.getId(), null);
        recruitment.approveRequisition(req.getId(), UUID.randomUUID(), null);
        return recruitment.openRequisition(req.getId(), null);
    }

    private Candidate candidate(String email) {
        return recruitment.registerCandidate("Marie", "Ilunga", email,
                CandidateSource.DIRECT, null);
    }

    // --- requisitions ------------------------------------------------------

    @Test
    @DisplayName("a new requisition starts as a draft with its number normalised")
    void createsDraftRequisition() {
        JobRequisition req = requisition("  req 001 ", 2);

        assertThat(req.getStatus()).isEqualTo(RequisitionStatus.DRAFT);
        assertThat(req.getRequisitionNumber()).isEqualTo("REQ-001");
        assertThat(req.remainingHeadcount()).isEqualTo(2);
    }

    @Test
    @DisplayName("requisition numbers are unique within a tenant")
    void rejectsDuplicateRequisitionNumber() {
        requisition("REQ-001", 1);

        assertThatThrownBy(() -> requisition("req-001", 1))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a requisition cannot be opened before it is approved")
    void refusesToOpenUnapprovedRequisition() {
        JobRequisition req = requisition("REQ-002", 1);

        assertThatThrownBy(() -> recruitment.openRequisition(req.getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("the approver is recorded, not inferred from the actor")
    void recordsApprover() {
        JobRequisition req = requisition("REQ-003", 1);
        UUID approver = UUID.randomUUID();

        recruitment.submitRequisition(req.getId(), null);
        JobRequisition approved =
                recruitment.approveRequisition(req.getId(), approver, UUID.randomUUID());

        assertThat(approved.getApprovedBy()).isEqualTo(approver);
        assertThat(approved.getApprovedAt()).isNotNull();
    }

    // --- candidates --------------------------------------------------------

    @Test
    @DisplayName("registering the same address twice returns the same person")
    void registerIsIdempotentOnEmail() {
        Candidate first = candidate("Marie.Ilunga@example.cd");
        Candidate second = recruitment.registerCandidate("Marie", "Ilunga",
                "  marie.ilunga@EXAMPLE.CD ", CandidateSource.REFERRAL, null);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(first.getEmail()).isEqualTo("marie.ilunga@example.cd");
    }

    @Test
    @DisplayName("a candidate needs an address that could be written to")
    void rejectsCandidateWithoutEmail() {
        assertThatThrownBy(() -> recruitment.registerCandidate("Marie", "Ilunga", "not-an-email",
                CandidateSource.DIRECT, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- applications ------------------------------------------------------

    @Test
    @DisplayName("applications are only accepted while the requisition is open")
    void refusesApplicationToDraftRequisition() {
        JobRequisition req = requisition("REQ-004", 1);
        Candidate marie = candidate("a@example.cd");

        assertThatThrownBy(() ->
                recruitment.apply(req.getId(), marie.getId(), LocalDate.now(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("the same candidate cannot apply twice for one role")
    void refusesDuplicateApplication() {
        JobRequisition req = openRequisition("REQ-005", 1);
        Candidate marie = candidate("b@example.cd");
        recruitment.apply(req.getId(), marie.getId(), LocalDate.now(), null);

        assertThatThrownBy(() ->
                recruitment.apply(req.getId(), marie.getId(), LocalDate.now(), null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("an application cannot skip from applied straight to an offer")
    void refusesIllegalTransition() {
        JobApplication application = application("REQ-006", "c@example.cd");

        assertThatThrownBy(() -> recruitment.moveApplication(
                application.getId(), ApplicationStatus.OFFER, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a rejection must say why")
    void requiresRejectionReason() {
        JobApplication application = application("REQ-007", "d@example.cd");

        assertThatThrownBy(() -> recruitment.moveApplication(
                application.getId(), ApplicationStatus.REJECTED, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a rejection records the reason and the moment it was decided")
    void recordsRejectionReason() {
        JobApplication application = application("REQ-013", "j@example.cd");

        JobApplication rejected = recruitment.moveApplication(application.getId(),
                ApplicationStatus.REJECTED, "No field experience", null);

        assertThat(rejected.getStatus()).isEqualTo(ApplicationStatus.REJECTED);
        assertThat(rejected.getOutcomeReason()).isEqualTo("No field experience");
        assertThat(rejected.getDecidedAt()).isNotNull();
    }

    @Test
    @DisplayName("a closed application stays closed")
    void refusesToReopenFinalApplication() {
        JobApplication application = application("REQ-008", "e@example.cd");
        recruitment.moveApplication(application.getId(), ApplicationStatus.WITHDRAWN, null, null);

        assertThatThrownBy(() -> recruitment.moveApplication(
                application.getId(), ApplicationStatus.SCREENING, null, null))
                .isInstanceOf(ConflictException.class);
    }

    // --- interviews --------------------------------------------------------

    @Test
    @DisplayName("feedback completes an interview")
    void feedbackCompletesInterview() {
        JobApplication application = application("REQ-009", "f@example.cd");
        Interview interview = recruitment.scheduleInterview(application.getId(),
                InterviewStage.TECHNICAL, InterviewMode.VIDEO,
                Instant.now().plus(2, ChronoUnit.DAYS), UUID.randomUUID(), null);

        assertThat(interview.getStatus()).isEqualTo(InterviewStatus.SCHEDULED);

        Interview done = recruitment.submitInterviewFeedback(interview.getId(),
                InterviewRecommendation.YES, 4, "Strong on field diagnostics", null);

        assertThat(done.getStatus()).isEqualTo(InterviewStatus.COMPLETED);
        assertThat(done.hasFeedback()).isTrue();
    }

    @Test
    @DisplayName("a cancelled interview cannot be written up")
    void refusesFeedbackOnCancelledInterview() {
        JobApplication application = application("REQ-010", "g@example.cd");
        Interview interview = recruitment.scheduleInterview(application.getId(),
                InterviewStage.SCREENING, InterviewMode.PHONE,
                Instant.now().plus(1, ChronoUnit.DAYS), null, null);
        recruitment.cancelInterview(interview.getId(), null);

        assertThatThrownBy(() -> recruitment.submitInterviewFeedback(interview.getId(),
                InterviewRecommendation.YES, 5, "n/a", null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a score outside the scale is refused")
    void refusesOutOfRangeScore() {
        JobApplication application = application("REQ-011", "h@example.cd");
        Interview interview = recruitment.scheduleInterview(application.getId(),
                InterviewStage.TECHNICAL, InterviewMode.ON_SITE,
                Instant.now().plus(1, ChronoUnit.DAYS), null, null);

        assertThatThrownBy(() -> recruitment.submitInterviewFeedback(interview.getId(),
                InterviewRecommendation.YES, 9, "excellent", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("interviews cannot be scheduled for a closed application")
    void refusesInterviewOnClosedApplication() {
        JobApplication application = application("REQ-012", "i@example.cd");
        recruitment.moveApplication(application.getId(), ApplicationStatus.REJECTED,
                "Withdrew from process", null);

        assertThatThrownBy(() -> recruitment.scheduleInterview(application.getId(),
                InterviewStage.TECHNICAL, InterviewMode.VIDEO,
                Instant.now().plus(1, ChronoUnit.DAYS), null, null))
                .isInstanceOf(ConflictException.class);
    }

    private JobApplication application(String requisitionNumber, String email) {
        JobRequisition req = openRequisition(requisitionNumber, 1);
        Candidate person = candidate(email);
        return recruitment.apply(req.getId(), person.getId(), LocalDate.now(), null);
    }
}
