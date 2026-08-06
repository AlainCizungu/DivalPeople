package ai.dival.dip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.attendance.AttendanceController;
import ai.dival.dip.modules.attendance.AttendanceService;
import ai.dival.dip.modules.attendance.TimeEntrySource;
import ai.dival.dip.modules.attendance.TimesheetService;
import ai.dival.dip.modules.employees.ContractType;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeController;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.employees.EmploymentContractService;
import ai.dival.dip.modules.leave.AccrualMethod;
import ai.dival.dip.modules.leave.LeaveBalanceService;
import ai.dival.dip.modules.leave.LeaveController;
import ai.dival.dip.modules.leave.LeaveRequestService;
import ai.dival.dip.modules.leave.LeaveType;
import ai.dival.dip.modules.leave.LedgerEntryType;
import ai.dival.dip.modules.lifecycle.ChecklistType;
import ai.dival.dip.modules.lifecycle.ItemCategory;
import ai.dival.dip.modules.lifecycle.LifecycleController;
import ai.dival.dip.modules.lifecycle.LifecycleService;
import ai.dival.dip.modules.organizations.OrgUnitService;
import ai.dival.dip.modules.performance.FeedbackRelationship;
import ai.dival.dip.modules.performance.PerformanceController;
import ai.dival.dip.modules.performance.PerformanceService;
import ai.dival.dip.modules.performance.Rating;
import ai.dival.dip.modules.performance.ReviewCycle;
import ai.dival.dip.modules.organizations.OrgUnitType;
import ai.dival.dip.modules.recruitment.CandidateSource;
import ai.dival.dip.modules.recruitment.InterviewMode;
import ai.dival.dip.modules.recruitment.InterviewStage;
import ai.dival.dip.modules.recruitment.RecruitmentController;
import ai.dival.dip.modules.recruitment.RecruitmentService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Builds every list response the way a real request does: outside a transaction.
 *
 * <p>This exists because of a bug that reached the browser. Every other test in this project
 * calls a service from inside a test transaction and asserts on the entity, so a response record
 * touching a lazy association was never exercised — and with {@code open-in-view: false}, that is
 * a {@code LazyInitializationException} and a 500 on every screen.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}. That is the entire point: the
 * mapping has to survive the transaction ending, which is the condition a controller runs under.
 * A future response record that reaches for an unfetched association fails here rather than in
 * somebody's browser.
 *
 * <p>The controllers are called rather than their mapping methods, which is both closer to a real
 * request and the only way to reach package-private {@code from} methods from here. It also means
 * {@code @PreAuthorize} is exercised, so an endpoint whose roles are wrong fails here too.
 */
@RequiresDocker
class ResponseMappingTest extends AbstractIntegrationTest {

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private EmploymentContractService contracts;
    @Autowired
    private OrgUnitService orgUnits;
    @Autowired
    private RecruitmentService recruitment;
    @Autowired
    private LifecycleService lifecycle;
    @Autowired
    private LeaveBalanceService balances;
    @Autowired
    private LeaveRequestService leave;
    @Autowired
    private AttendanceService attendance;
    @Autowired
    private TimesheetService timesheets;

    @Autowired
    private EmployeeController employeeController;
    @Autowired
    private RecruitmentController recruitmentController;
    @Autowired
    private LifecycleController lifecycleController;
    @Autowired
    private LeaveController leaveController;
    @Autowired
    private AttendanceController attendanceController;
    @Autowired
    private PerformanceService performance;
    @Autowired
    private PerformanceController performanceController;

    private UUID tenantId;
    private Employee employee;
    private Employee manager;

    @BeforeEach
    void setUp() {
        // Tenant admin reaches every read endpoint here. Without an authentication the
        // method-security proxy would refuse before any mapping ran, and the test would
        // pass for the wrong reason.
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("response-mapping-test", "n/a",
                        "ROLE_TENANT_ADMIN"));

        tenantId = tenants.save(new Tenant("RM", "rm-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);

        UUID unit = orgUnits.create(null, OrgUnitType.LEGAL_ENTITY, "RM-HQ", "Head office", null)
                .getId();

        manager = employees.hire("EMP-001", "Sylvie", "Mbala", LocalDate.of(2019, 1, 7),
                unit, null);
        employee = employees.hire("EMP-002", "Didier", "Lokwa", LocalDate.of(2024, 2, 5),
                unit, null);
        employees.setManager(employee.getId(), manager.getId(), null);
        contracts.draft(employee.getId(), ContractType.PERMANENT, "Field Engineer",
                LocalDate.of(2024, 2, 5), null, unit, null, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the employee directory maps outside a transaction")
    void employeeResponsesMap() {
        assertThatCode(() -> {
            var summaries = employeeController.list();
            assertThat(summaries).isNotEmpty();
            // The name, not just the id: reading an id off a proxy never touches the database,
            // which is exactly why this class of bug hides.
            assertThat(summaries.get(0).orgUnitName()).isNotNull();

            employeeController.get(employee.getId());
            employeeController.contracts(employee.getId());
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the recruitment pipeline maps outside a transaction")
    void recruitmentResponsesMap() {
        var requisition = recruitment.createRequisition("REQ-1", "Field Engineer",
                ContractType.PERMANENT, 1, null, null, null, LocalDate.now().plusMonths(1), null);
        recruitment.submitRequisition(requisition.getId(), null);
        recruitment.approveRequisition(requisition.getId(), manager.getId(), null);
        recruitment.openRequisition(requisition.getId(), null);

        var candidate = recruitment.registerCandidate("Marie", "Ilunga", "marie@example.cd",
                CandidateSource.DIRECT, null);
        var application = recruitment.apply(requisition.getId(), candidate.getId(),
                LocalDate.now(), null);
        // Stage is NOT NULL, and this test commits for real rather than rolling back, so a
        // null would fail on insert rather than being quietly tolerated.
        recruitment.scheduleInterview(application.getId(), InterviewStage.SCREENING,
                InterviewMode.VIDEO, Instant.now().plus(2, ChronoUnit.DAYS),
                manager.getId(), null);

        assertThatCode(() -> {
            assertThat(recruitmentController.listRequisitions()).isNotEmpty();
            assertThat(recruitmentController.applications(requisition.getId())).isNotEmpty();
            assertThat(recruitmentController.interviews(application.getId())).isNotEmpty();
            recruitmentController.listCandidates();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("checklists map outside a transaction")
    void lifecycleResponsesMap() {
        var template = lifecycle.createTemplate("ONB", "Standard onboarding",
                ChecklistType.ONBOARDING, null);
        lifecycle.addTemplateItem(template.getId(), "Send the contract", null,
                ItemCategory.PAPERWORK, "HR_ADMIN", -5, true, null);
        var checklist = lifecycle.raise(employee.getId(), template.getId(), LocalDate.now(),
                manager.getId(), null);

        assertThatCode(() -> {
            var open = lifecycleController.open();
            assertThat(open).isNotEmpty();
            assertThat(open.get(0).employeeName()).isNotBlank();

            var detail = lifecycleController.checklist(checklist.getId());
            assertThat(detail.items()).isNotEmpty();
            assertThat(detail.items().get(0).assigneeName()).isNotBlank();

            lifecycleController.listTemplates();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("leave balances and requests map outside a transaction")
    void leaveResponsesMap() {
        LeaveType annual = balances.createType("ANNUAL", "Annual leave", new BigDecimal("20"),
                AccrualMethod.ANNUAL_GRANT, null);
        int year = LocalDate.now().plusWeeks(3).getYear();
        balances.grant(employee.getId(), annual.getId(), year, new BigDecimal("20"),
                LedgerEntryType.GRANT, "entitlement", null);

        LocalDate from = LocalDate.now().plusWeeks(3).with(java.time.DayOfWeek.MONDAY);
        var request = leave.submit(employee.getId(), annual.getId(), from, from.plusDays(2),
                false, false, "Family visit", null, null);
        leave.approve(request.getId(), manager.getId(), null, null);

        assertThatCode(() -> {
            var forYear = leaveController.balances(employee.getId(), year);
            assertThat(forYear).isNotEmpty();
            assertThat(forYear.get(0).leaveTypeName()).isNotBlank();

            var history = leaveController.forEmployee(employee.getId());
            assertThat(history).isNotEmpty();
            assertThat(history.get(0).employeeName()).isNotBlank();
            assertThat(history.get(0).approverName()).isNotBlank();

            leaveController.calendar(from, from.plusDays(6));
            leaveController.ledger(forYear.get(0).id());
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("attendance entries and timesheets map outside a transaction")
    void attendanceResponsesMap() {
        LocalDate monday = LocalDate.now().minusWeeks(1).with(java.time.DayOfWeek.MONDAY);
        attendance.record(employee.getId(), monday,
                monday.atTime(8, 0).atZone(attendance.getZone()).toInstant(),
                monday.atTime(17, 0).atZone(attendance.getZone()).toInstant(),
                60, TimeEntrySource.BIOMETRIC, null, null);
        var sheet = timesheets.buildWeek(employee.getId(), monday, null);
        timesheets.submit(sheet.getId(), null);

        assertThatCode(() -> {
            var entries =
                    attendanceController.entries(employee.getId(), monday, monday.plusDays(6));
            assertThat(entries).isNotEmpty();
            assertThat(entries.get(0).employeeName()).isNotBlank();

            var sheets = attendanceController.timesheets(employee.getId());
            assertThat(sheets).isNotEmpty();
            assertThat(sheets.get(0).employeeName()).isNotBlank();

            attendanceController.pending();
            attendanceController.dayHistory(employee.getId(), monday);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("performance responses map outside a transaction")
    void performanceResponsesMap() {
        ReviewCycle cycle = performance.createCycle("2026 annual", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31), null, null);
        performance.openCycle(cycle.getId(), null);

        var goal = performance.createGoal(employee.getId(), "Cut mean repair time", null,
                "From 6 hours to 4", null, LocalDate.of(2026, 6, 30), cycle.getId(), null, null);
        performance.activateGoal(goal.getId(), null);

        var review = performance.openReview(cycle.getId(), employee.getId(), manager.getId(),
                null);
        performance.saveSelfAssessment(review.getId(), "My year", null);
        performance.submitSelfAssessment(review.getId(), null);
        performance.saveReviewerAssessment(review.getId(), "Their year", Rating.MEETS, null);
        performance.submitReviewerAssessment(review.getId(), null);
        performance.addFeedback(review.getId(), manager.getId(), FeedbackRelationship.MANAGER,
                "Dependable", false, null);

        assertThatCode(() -> {
            assertThat(performanceController.cycles()).isNotEmpty();

            var employeeGoals = performanceController.goals(employee.getId());
            assertThat(employeeGoals).isNotEmpty();
            // The names, not just the ids: reading an id off a proxy never touches the database.
            assertThat(employeeGoals.get(0).employeeName()).isNotBlank();
            assertThat(employeeGoals.get(0).cycleName()).isNotBlank();

            var inCycle = performanceController.reviewsInCycle(cycle.getId());
            assertThat(inCycle).isNotEmpty();
            assertThat(inCycle.get(0).employeeName()).isNotBlank();
            assertThat(inCycle.get(0).reviewerName()).isNotBlank();
            assertThat(inCycle.get(0).cycleName()).isNotBlank();

            performanceController.reviewsFor(employee.getId(), true);
            performanceController.reviewsToWrite(manager.getId());
            performanceController.review(review.getId(), false);
            assertThat(performanceController.feedback(review.getId(), false)).isNotEmpty();
        }).doesNotThrowAnyException();
    }
}
