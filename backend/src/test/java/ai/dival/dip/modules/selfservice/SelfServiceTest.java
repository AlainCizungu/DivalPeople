package ai.dival.dip.modules.selfservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.AccessRefusedException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.CurrentEmployee;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.leave.AccrualMethod;
import ai.dival.dip.modules.leave.LeaveBalanceService;
import ai.dival.dip.modules.leave.LeaveRequest;
import ai.dival.dip.modules.leave.LeaveRequestService;
import ai.dival.dip.modules.leave.LeaveRequestStatus;
import ai.dival.dip.modules.leave.LeaveType;
import ai.dival.dip.modules.leave.LedgerEntryType;
import ai.dival.dip.modules.payroll.PayFrequency;
import ai.dival.dip.modules.payroll.PayrollPeriod;
import ai.dival.dip.modules.payroll.PayrollService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import ai.dival.dip.modules.users.CurrentUserService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;

/**
 * The boundary self-service rests on: a person reaches their own record and nobody else's.
 *
 * <p>Two employees in one tenant, each with a sign-in, each with pay and leave. Every test asks
 * the same question from the wrong side and expects to be refused.
 *
 * <p>The controller is called directly rather than over HTTP. That is deliberate: it means the
 * refusals proved here are the controller's own, not an accident of a route or a filter that a
 * future refactor could move.
 */
@Transactional
@RequiresDocker
class SelfServiceTest extends AbstractIntegrationTest {

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 6, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 6, 30);

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private PayrollService payroll;
    @Autowired
    private LeaveBalanceService balances;
    @Autowired
    private LeaveRequestService leaveRequests;
    @Autowired
    private CurrentUserService currentUser;
    @Autowired
    private CurrentEmployee currentEmployee;
    @Autowired
    private SelfServiceController me;

    private Employee sylvie;
    private Employee didier;
    private String sylvieSubject;
    private String didierSubject;
    private String strangerSubject;

    @BeforeEach
    void setUp() {
        UUID tenantId = tenants.save(new Tenant("SS", "ss-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);

        sylvie = employees.hire("EMP-001", "Sylvie", "Mbala", LocalDate.of(2019, 1, 7),
                null, null);
        didier = employees.hire("EMP-002", "Didier", "Lokwa", LocalDate.of(2024, 2, 5),
                null, null);

        sylvieSubject = UUID.randomUUID().toString();
        didierSubject = UUID.randomUUID().toString();
        strangerSubject = UUID.randomUUID().toString();

        // Each sign-in provisions on its first authenticated request, exactly as in production.
        employees.linkUserAccount(sylvie.getId(), accountFor(sylvieSubject, "Sylvie Mbala"), null);
        employees.linkUserAccount(didier.getId(), accountFor(didierSubject, "Didier Lokwa"), null);
        accountFor(strangerSubject, "Amina Wasso");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    // --- who am I ----------------------------------------------------------

    @Test
    @DisplayName("the portal shows the person the token belongs to")
    void resolvesTheSignedInEmployee() {
        signIn(sylvieSubject, "Sylvie Mbala");

        assertThat(me.me().employeeNumber()).isEqualTo("EMP-001");
        assertThat(me.me().displayName()).isEqualTo("Sylvie Mbala");
    }

    @Test
    @DisplayName("a sign-in with no employee record is told so, not shown an empty portal")
    void unlinkedSignInIsRefusedClearly() {
        signIn(strangerSubject, "Amina Wasso");

        assertThatThrownBy(() -> me.me())
                .isInstanceOf(CurrentEmployee.NotAnEmployeeException.class);
    }

    @Test
    @DisplayName("nobody is anybody else, however the token is dressed up")
    void identityComesFromTheSubjectNotTheName() {
        // Same display name, different subject. Only the subject decides.
        signIn(didierSubject, "Sylvie Mbala");

        assertThat(me.me().employeeNumber()).isEqualTo("EMP-002");
        assertThat(currentEmployee.isSelf(sylvie.getId())).isFalse();
        assertThat(currentEmployee.isSelf(didier.getId())).isTrue();
    }

    // --- pay ---------------------------------------------------------------

    @Test
    @DisplayName("a person sees their own payslip and not their colleague's")
    void payslipsAreOnesOwn() {
        PayrollPeriod period = paidPeriodForBoth();

        signIn(sylvieSubject, "Sylvie Mbala");
        assertThat(me.payslips()).hasSize(1);
        assertThat(me.payslips().get(0).netPay()).isEqualByComparingTo("3000");

        signIn(didierSubject, "Didier Lokwa");
        assertThat(me.payslips()).hasSize(1);
        assertThat(me.payslips().get(0).netPay()).isEqualByComparingTo("1000");

        assertThat(period.getStatus().isVisibleToEmployee()).isTrue();
    }

    @Test
    @DisplayName("asking for a colleague's payslip by id is refused")
    void anotherPersonsPayslipIsRefused() {
        paidPeriodForBoth();
        UUID sylviesPayslip = payslipIdFor(sylvie);

        signIn(didierSubject, "Didier Lokwa");

        assertThatThrownBy(() -> me.payslip(sylviesPayslip))
                .isInstanceOf(AccessRefusedException.class);
    }

    @Test
    @DisplayName("a payslip from a run nobody has signed off is not shown to the person")
    void unapprovedPayslipsAreWithheld() {
        payThem(sylvie, new BigDecimal("3000"));
        PayrollPeriod unsigned = payroll.createPeriod("June 2026", PERIOD_START, PERIOD_END,
                PERIOD_END.plusDays(5), null);
        payroll.calculate(unsigned.getId(), PayFrequency.MONTHLY, null);
        UUID calculated = payslipIdFor(sylvie);

        signIn(sylvieSubject, "Sylvie Mbala");

        // The figures exist and payroll can see them. The person they are about cannot, yet.
        assertThat(me.payslips()).isEmpty();
        assertThatThrownBy(() -> me.payslip(calculated))
                .isInstanceOf(AccessRefusedException.class);
    }

    // --- leave -------------------------------------------------------------

    @Test
    @DisplayName("booking leave books it for the person asking, with no id to get wrong")
    void bookingLeaveIsAlwaysForOneself() {
        LeaveType annual = annualLeaveFor(didier);

        signIn(didierSubject, "Didier Lokwa");
        var booked = me.bookLeave(new SelfServiceController.BookLeave(annual.getId(),
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 11),
                false, false, "Family", null)).getBody();

        assertThat(booked).isNotNull();
        assertThat(booked.days()).isEqualByComparingTo("5");
        assertThat(me.leaveRequests()).hasSize(1);

        // And it landed on Didier, which is the whole point.
        assertThat(leaveRequests.forEmployee(didier.getId())).hasSize(1);
        assertThat(leaveRequests.forEmployee(sylvie.getId())).isEmpty();
    }

    @Test
    @DisplayName("cancelling somebody else's leave is refused")
    void cannotCancelAnotherPersonsLeave() {
        LeaveType annual = annualLeaveFor(sylvie);
        LeaveRequest hers = leaveRequests.submit(sylvie.getId(), annual.getId(),
                LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 11), false, false,
                "Family", null, null);

        signIn(didierSubject, "Didier Lokwa");

        assertThatThrownBy(() -> me.cancelLeave(hers.getId()))
                .isInstanceOf(AccessRefusedException.class);
        assertThat(leaveRequests.get(hers.getId()).getStatus())
                .isEqualTo(LeaveRequestStatus.SUBMITTED);
    }

    @Test
    @DisplayName("a balance query answers for the caller, not for whoever is asked about")
    void balancesAreOnesOwn() {
        annualLeaveFor(sylvie);

        signIn(didierSubject, "Didier Lokwa");
        assertThat(me.leaveBalances(2026)).isEmpty();

        signIn(sylvieSubject, "Sylvie Mbala");
        assertThat(me.leaveBalances(2026)).hasSize(1);
    }

    // --- the team ----------------------------------------------------------

    @Test
    @DisplayName("a manager sees their reports and not the whole company")
    void teamIsDirectReportsOnly() {
        employees.setManager(didier.getId(), sylvie.getId(), null);

        signIn(sylvieSubject, "Sylvie Mbala");
        assertThat(me.team()).extracting(SelfServiceController.TeamMemberResponse::employeeNumber)
                .containsExactly("EMP-002");

        // Didier manages nobody, and that is an answer rather than a failure.
        signIn(didierSubject, "Didier Lokwa");
        assertThat(me.team()).isEmpty();
    }

    // --- helpers -----------------------------------------------------------

    private PayrollPeriod paidPeriodForBoth() {
        payThem(sylvie, new BigDecimal("3000"));
        payThem(didier, new BigDecimal("1000"));

        PayrollPeriod period = payroll.createPeriod("June 2026", PERIOD_START, PERIOD_END,
                PERIOD_END.plusDays(5), null);
        payroll.calculate(period.getId(), PayFrequency.MONTHLY, null);

        // Approved by somebody who is not on it, because nobody approves a payroll they are paid by.
        Employee approver = employees.hire("EMP-900", "Amina", "Wasso",
                LocalDate.of(2018, 5, 1), null, null);
        payroll.approve(period.getId(), approver.getId(), "Checked", null);
        return payroll.period(period.getId());
    }

    private void payThem(Employee employee, BigDecimal amount) {
        payroll.setCompensation(employee.getId(), LocalDate.of(2024, 2, 5), amount, "USD",
                PayFrequency.MONTHLY, "Test salary", null);
    }

    private UUID payslipIdFor(Employee employee) {
        return payroll.payslipsFor(employee.getId()).get(0).getId();
    }

    private LeaveType annualLeaveFor(Employee employee) {
        LeaveType annual = balances.createType("ANNUAL-" + employee.getEmployeeNumber(),
                "Annual leave", new BigDecimal("30"), AccrualMethod.ANNUAL_GRANT, null);
        balances.grant(employee.getId(), annual.getId(), 2026, new BigDecimal("30"),
                LedgerEntryType.GRANT, "Entitlement", null);
        return annual;
    }

    private UUID accountFor(String subject, String name) {
        signIn(subject, name);
        UUID id = currentUser.requireCurrentUser().getId();
        SecurityContextHolder.clearContext();
        return id;
    }

    /** Puts a JWT principal in the context, the way the resource server does in production. */
    private void signIn(String subject, String name, String... roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject(subject)
                .claim("email", subject + "@example.test")
                .claim("name", name)
                .build();

        String[] authorities = Arrays.stream(roles.length == 0 ? new String[] {"EMPLOYEE"} : roles)
                .map(role -> "ROLE_" + role)
                .toArray(String[]::new);

        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken(jwt, "n/a", authorities));
    }
}
