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
import ai.dival.dip.modules.learning.Course;
import ai.dival.dip.modules.learning.CourseEnrolment;
import ai.dival.dip.modules.learning.DeliveryMode;
import ai.dival.dip.modules.learning.LearningController;
import ai.dival.dip.modules.learning.LearningService;
import ai.dival.dip.modules.organizations.OrgUnitService;
import ai.dival.dip.modules.payroll.PayFrequency;
import ai.dival.dip.modules.payroll.PayrollController;
import ai.dival.dip.modules.payroll.PayrollPeriod;
import ai.dival.dip.modules.payroll.PayrollService;
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
import ai.dival.dip.modules.selfservice.SelfServiceController;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import ai.dival.dip.modules.users.CurrentUserService;
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
import org.springframework.security.oauth2.jwt.Jwt;

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
    private CurrentUserService currentUser;

    @Autowired
    private EmployeeController employeeController;
    @Autowired
    private RecruitmentController recruitmentController;
    @Autowired
    private LifecycleController lifecycleController;
    @Autowired
    private LeaveController leaveController;
    @Autowired
    private SelfServiceController selfServiceController;
    @Autowired
    private AttendanceController attendanceController;
    @Autowired
    private PerformanceService performance;
    @Autowired
    private PerformanceController performanceController;
    @Autowired
    private LearningService learning;
    @Autowired
    private LearningController learningController;
    @Autowired
    private PayrollService payrollService;
    @Autowired
    private PayrollController payrollController;

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

            performanceController.reviewsFor(employee.getId());
            performanceController.reviewsToWrite(manager.getId());
            performanceController.review(review.getId());
            assertThat(performanceController.feedback(review.getId())).isNotEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("learning responses map outside a transaction")
    void learningResponsesMap() {
        Course course = learning.createCourse("TOWER-SAFETY", "Tower climbing safety",
                DeliveryMode.CLASSROOM, null, "National Safety Board", 480, true, 24, 70, null);
        CourseEnrolment enrolment = learning.enrol(employee.getId(), course.getId(),
                LocalDate.now().minusMonths(1), null);
        learning.complete(enrolment.getId(), LocalDate.now().minusDays(1), 85, null, null);

        assertThatCode(() -> {
            assertThat(learningController.courses(true)).isNotEmpty();

            var held = learningController.enrolmentsFor(employee.getId());
            assertThat(held).isNotEmpty();
            // The names, not just the ids.
            assertThat(held.get(0).employeeName()).isNotBlank();
            assertThat(held.get(0).courseTitle()).isNotBlank();

            assertThat(learningController.enrolmentsOn(course.getId())).isNotEmpty();
            learningController.course(course.getId());

            // The compliance report reaches across every employee, so it is the one most likely
            // to touch an unfetched association.
            learningController.compliance(LocalDate.now());
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("payroll responses map outside a transaction")
    void payrollResponsesMap() {
        payrollService.setCompensation(employee.getId(), LocalDate.of(2024, 2, 5),
                new java.math.BigDecimal("1000"), "USD", PayFrequency.MONTHLY, "Test", null);

        var pension = payrollService.createComponent("PENSION", "Pension",
                ai.dival.dip.modules.payroll.ComponentType.DEDUCTION,
                ai.dival.dip.modules.payroll.CalculationMethod.PERCENT_OF_BASE, null,
                new java.math.BigDecimal("5"), false, 200, null);
        payrollService.assign(employee.getId(), pension.getId(), LocalDate.of(2024, 2, 5),
                null, null, null, null);

        LocalDate start = LocalDate.of(2026, 6, 1);
        PayrollPeriod period = payrollService.createPeriod("June 2026", start,
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 5), null);
        payrollService.calculate(period.getId(), PayFrequency.MONTHLY, null);

        assertThatCode(() -> {
            assertThat(payrollController.periods()).isNotEmpty();
            assertThat(payrollController.components()).isNotEmpty();

            var history = payrollController.compensation(employee.getId());
            assertThat(history).isNotEmpty();
            // The name, not just the id.
            assertThat(history.get(0).employeeName()).isNotBlank();

            assertThat(payrollController.assignments(employee.getId())).isNotEmpty();

            var slips = payrollController.payslipsIn(period.getId());
            assertThat(slips).isNotEmpty();
            // The lines are a lazy collection, so this is the one most likely to break.
            assertThat(slips.get(0).lines()).isNotEmpty();

            payrollController.payslipsFor(employee.getId());
            payrollController.payslip(slips.get(0).id());
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("self-service responses map outside a transaction")
    void selfServiceResponsesMap() {
        // A real token, because self-service resolves the person from one. The tenant-admin
        // principal the rest of this class uses is a plain string and would not reach the
        // employee record behind it.
        String subject = UUID.randomUUID().toString();
        signInAs(subject, "Didier Lokwa");
        employees.linkUserAccount(employee.getId(), currentUser.requireCurrentUser().getId(), null);

        payrollService.setCompensation(employee.getId(), LocalDate.of(2024, 2, 5),
                new BigDecimal("1200"), "USD", PayFrequency.MONTHLY, "Test", null);
        PayrollPeriod period = payrollService.createPeriod("Self-service June 2026",
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30),
                LocalDate.of(2026, 7, 5), null);
        payrollService.calculate(period.getId(), PayFrequency.MONTHLY, null);
        payrollService.approve(period.getId(), manager.getId(), "Checked", null);

        assertThatCode(() -> {
            assertThat(selfServiceController.me().employeeNumber()).isEqualTo("EMP-002");
            selfServiceController.team();

            // Lines and period name are both reached through associations, so this is the read
            // most likely to break the way five screens once did.
            var slips = selfServiceController.payslips();
            assertThat(slips).isNotEmpty();
            assertThat(slips.get(0).periodName()).isNotBlank();
            assertThat(slips.get(0).lines()).isNotEmpty();
            selfServiceController.payslip(slips.get(0).id());

            selfServiceController.leaveTypes();
            selfServiceController.leaveBalances(2026);
            selfServiceController.leaveRequests();
            selfServiceController.timesheets();
            selfServiceController.attendance(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
            selfServiceController.goals();
            selfServiceController.reviews();
            selfServiceController.training();
        }).doesNotThrowAnyException();
    }

    /** A JWT principal, the way the resource server presents one. */
    private void signInAs(String subject, String name) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("email", subject + "@example.test")
                .claim("name", name)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(jwt, "n/a", "ROLE_TENANT_ADMIN", "ROLE_EMPLOYEE"));
    }
}
