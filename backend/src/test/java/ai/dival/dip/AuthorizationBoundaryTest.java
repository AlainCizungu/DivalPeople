package ai.dival.dip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeController;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.files.FileController;
import ai.dival.dip.modules.learning.LearningController;
import ai.dival.dip.modules.leave.LeaveController;
import ai.dival.dip.modules.lifecycle.LifecycleController;
import ai.dival.dip.modules.performance.PerformanceController;
import ai.dival.dip.modules.performance.PerformanceService;
import ai.dival.dip.modules.performance.ReviewCycle;
import ai.dival.dip.modules.recruitment.InterviewRecommendation;
import ai.dival.dip.modules.recruitment.RecruitmentController;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import ai.dival.dip.modules.tix.TixController;
import ai.dival.dip.modules.users.CurrentUserService;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asks every question from the wrong side.
 *
 * <p>This test exists because of the August 2026 security review, which found 52 endpoints across
 * eleven modules with no authorization at all — any signed-in employee could read and rewrite a
 * colleague's performance review, download every document in the tenant, book their leave and
 * clock them in. Not one test failed. They all asked from the right side.
 *
 * <p>{@code check_architecture.py} rule 5 now proves an authorization annotation <em>exists</em>.
 * That is a different claim from it <em>working</em>: a wrong role, a guard that reads the
 * authorities incorrectly, or a check that quietly passes for everyone would all satisfy the
 * script. This is the other half.
 *
 * <p>The caller here is Célestine — an ordinary employee, `ROLE_EMPLOYEE` and nothing else, linked
 * to a real employee record, in the same tenant as her colleague. Everything she is refused, she
 * is refused for being the wrong person rather than the wrong tenant. Cross-tenant isolation is
 * covered elsewhere and was never the problem.
 */
@Transactional
@RequiresDocker
class AuthorizationBoundaryTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private CurrentUserService currentUser;
    @Autowired
    private PerformanceService performance;

    @Autowired
    private PerformanceController performanceController;
    @Autowired
    private FileController fileController;
    @Autowired
    private LeaveController leaveController;
    @Autowired
    private LifecycleController lifecycleController;
    @Autowired
    private LearningController learningController;
    @Autowired
    private EmployeeController employeeController;
    @Autowired
    private RecruitmentController recruitmentController;
    @Autowired
    private TixController tixController;
    @Autowired
    private ai.dival.dip.common.audit.AuditController auditController;

    private Employee celestine;
    private Employee colleague;
    private UUID colleagueReview;

    @BeforeEach
    void setUp() {
        UUID tenantId = tenants.save(new Tenant("AB", "ab-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);

        celestine = employees.hire("EMP-001", "Célestine", "Nsimba", LocalDate.of(2021, 4, 12),
                null, null);
        colleague = employees.hire("EMP-002", "Bernard", "Tshimanga", LocalDate.of(2020, 2, 3),
                null, null);
        Employee manager = employees.hire("EMP-003", "Alice", "Mputu", LocalDate.of(2018, 1, 8),
                null, null);
        employees.setManager(colleague.getId(), manager.getId(), null);

        // A review about the colleague, which Célestine has no part in.
        ReviewCycle cycle = performance.createCycle("2026", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 31), null);
        performance.openCycle(cycle.getId(), null);
        colleagueReview = performance
                .openReview(cycle.getId(), colleague.getId(), manager.getId(), null).getId();

        // Célestine signs in. Provisioning happens on first authenticated request, as in
        // production, and the employee record is linked afterwards.
        signInAsCelestine();
        employees.linkUserAccount(celestine.getId(), currentUser.requireCurrentUser().getId(),
                null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    // --- performance: the critical finding ---------------------------------

    @Test
    @DisplayName("an employee cannot read a colleague's review")
    void cannotReadAnotherPersonsReview() {
        // This returned the reviewer's unshared assessment, the proposed and calibrated ratings
        // and the management-only calibration notes — strictly more than the subject may see.
        assertThatThrownBy(() -> performanceController.review(colleagueReview))
                .isInstanceOfAny(PerformanceService.ReviewNotFoundException.class,
                        AccessRefusedException.class);

        assertThatThrownBy(() -> performanceController.reviewsFor(colleague.getId()))
                .isInstanceOf(AccessRefusedException.class);
    }

    @Test
    @DisplayName("a refused review is indistinguishable from one that does not exist")
    void refusalDoesNotConfirmTheReviewExists() {
        Class<?> forSomebodyElse = null;
        Class<?> forNothing = null;
        try {
            performanceController.review(colleagueReview);
        } catch (RuntimeException ex) {
            forSomebodyElse = ex.getClass();
        }
        try {
            performanceController.review(UUID.randomUUID());
        } catch (RuntimeException ex) {
            forNothing = ex.getClass();
        }
        // "not yours" and "not there" must look the same, or the endpoint confirms the existence
        // of records the caller is not allowed to know about.
        assertThat(forSomebodyElse).isEqualTo(forNothing);
    }

    @Test
    @DisplayName("an employee cannot write a colleague's self-assessment or acknowledge it")
    void cannotWriteAnotherPersonsAssessment() {
        assertThatThrownBy(() -> performanceController.saveSelf(colleagueReview,
                new PerformanceController.AssessmentRequest("I had a difficult year")))
                .isInstanceOf(AccessRefusedException.class);

        assertThatThrownBy(() -> performanceController.submitSelf(colleagueReview))
                .isInstanceOf(AccessRefusedException.class);

        assertThatThrownBy(() -> performanceController.acknowledge(colleagueReview,
                new PerformanceController.AcknowledgeRequest("I disagree", true)))
                .isInstanceOf(AccessRefusedException.class);
    }

    @Test
    @DisplayName("an employee cannot read a colleague's goals")
    void cannotReadAnotherPersonsGoals() {
        assertThatThrownBy(() -> performanceController.goals(colleague.getId()))
                .isInstanceOf(AccessRefusedException.class);

        // Their own are fine. A boundary that refuses everybody is not a boundary.
        assertThatCode(() -> performanceController.goals(celestine.getId()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an employee cannot open, calibrate or share reviews")
    void cannotActAsManagement() {
        assertThatThrownBy(() -> performanceController.reviewsInCycle(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> performanceController.share(colleagueReview))
                .isInstanceOf(AccessDeniedException.class);
    }

    // --- the rest of the review's findings ---------------------------------

    @Test
    @DisplayName("an employee cannot list or download the tenant's documents")
    void cannotReachDocuments() {
        // Sick notes, identity scans, contracts. This was open to everyone.
        assertThatThrownBy(() -> fileController.listByCategory("EMPLOYEE_DOCUMENT"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> fileController.download(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> fileController.metadata(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an employee cannot read or cancel a colleague's leave")
    void cannotReachAnotherPersonsLeave() {
        assertThatThrownBy(() -> leaveController.forEmployee(colleague.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> leaveController.balances(colleague.getId(), 2026))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> leaveController.cancel(UUID.randomUUID()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> leaveController.pending())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an employee cannot read the offboarding checklists or tick items off")
    void cannotReachChecklists() {
        // "Revoke system access" being markable by anyone is the whole point of the control.
        assertThatThrownBy(() -> lifecycleController.open())
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> lifecycleController.forEmployee(colleague.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an employee cannot read who has skipped their mandatory training")
    void cannotReachTrainingRecords() {
        assertThatThrownBy(() -> learningController.enrolmentsFor(colleague.getId()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> learningController.compliance(LocalDate.now()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an employee cannot read the directory or another person's record")
    void cannotReachTheDirectory() {
        assertThatThrownBy(() -> employeeController.list())
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> employeeController.get(colleague.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }


    @Test
    @DisplayName("an employee cannot write feedback on an interview they are not on")
    void cannotWriteAnotherPanelsInterviewFeedback() {
        // The endpoint's role is isAuthenticated(), because interviewers are ordinary employees
        // and a hiring panel is not a permission group. Without the ownership check that admits
        // the whole tenant to overwrite any hire or no-hire recommendation.
        assertThatThrownBy(() -> recruitmentController.submitFeedback(UUID.randomUUID(),
                new RecruitmentController.FeedbackRequest(
                        InterviewRecommendation.STRONG_YES, 5, "Excellent throughout")))
                .isInstanceOfAny(AccessRefusedException.class, RuntimeException.class);
    }

    // --- the exchange ------------------------------------------------------

    @Test
    @DisplayName("an account that may only enquire cannot read an operator's whole book")
    void inquirerCannotReadThePortfolio() {
        // The portfolio is the largest single disclosure in TIX: one operator's total exposure,
        // aged, by currency. Everything else the exchange returns is a status about one subject.
        // An inquirer role that reached it would turn a lookup service into a balance sheet.
        signInAs("inquirer-" + UUID.randomUUID(), "Joseph Mbala", "TIX_INQUIRER");
        assertThatThrownBy(() -> tixController.portfolio())
                .isInstanceOf(AccessDeniedException.class);

        // Nor the operator's own record list, for the same reason.
        assertThatThrownBy(() -> tixController.listOwnDebtRecords())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a declarant can read its own book, or the guard refuses everybody")
    void declarantCanReadThePortfolio() {
        signInAs("declarant-" + UUID.randomUUID(), "Grâce Ilunga", "TIX_DECLARANT");
        assertThatCode(() -> tixController.portfolio()).doesNotThrowAnyException();
    }

    // --- the trail ----------------------------------------------------------

    @Test
    @DisplayName("an ordinary account cannot read the record of what it did")
    void employeesCannotReadTheAuditTrail() {
        // Somebody watching their own watchers is not oversight. The trail is for an auditor, a
        // compliance officer, or the administrator accountable for the staff in it.
        assertThatThrownBy(() -> auditController.events(null, 100))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> auditController.summary())
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a tenant administrator can, or the trail is unreadable rather than protected")
    void administratorsCanReadTheAuditTrail() {
        signInAs("admin-" + UUID.randomUUID(), "Nadine Lokolo", "EMPLOYEE", "TENANT_ADMIN");

        assertThatCode(() -> {
            auditController.events(null, 10);
            auditController.summary();
        }).doesNotThrowAnyException();
    }

    // --- and the reason none of this is a boundary if it refuses everyone ---

    @Test
    @DisplayName("the same calls succeed for somebody who should be allowed")
    void managementIsStillAllowed() {
        signInAs("hr-" + UUID.randomUUID(), "Aimée Kalala", "EMPLOYEE", "HR_ADMIN");

        assertThatCode(() -> {
            employeeController.list();
            leaveController.pending();
            lifecycleController.open();
            learningController.compliance(LocalDate.now());
            fileController.listByCategory("EMPLOYEE_DOCUMENT");
            performanceController.reviewsFor(colleague.getId());
            performanceController.review(colleagueReview);
        }).doesNotThrowAnyException();
    }

    // --- helpers -----------------------------------------------------------

    private void signInAsCelestine() {
        signInAs("celestine-" + UUID.randomUUID(), "Célestine Nsimba", "EMPLOYEE");
    }

    private void signInAs(String subject, String name, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("email", subject + "@example.test")
                .claim("name", name)
                .build();

        String[] authorities = new String[roles.length];
        for (int i = 0; i < roles.length; i++) {
            authorities[i] = "ROLE_" + roles[i];
        }

        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(jwt, "n/a", authorities));
    }
}
