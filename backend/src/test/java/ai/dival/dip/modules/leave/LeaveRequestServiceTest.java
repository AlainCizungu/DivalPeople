package ai.dival.dip.modules.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** June 2026 again: the 1st is a Monday, so the week arithmetic reads cleanly. */
@Transactional
@RequiresDocker
class LeaveRequestServiceTest extends AbstractIntegrationTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 1);
    private static final LocalDate FRIDAY = LocalDate.of(2026, 6, 5);
    private static final int YEAR = 2026;

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private LeaveBalanceService balances;
    @Autowired
    private LeaveRequestService requests;

    private Employee employee;
    private Employee manager;
    private LeaveType annual;

    @BeforeEach
    void setUp() {
        UUID tenantId = tenants.save(new Tenant("R A", "lr-a-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);

        manager = employees.hire("EMP-500", "Sylvie", "Mbala", LocalDate.of(2019, 1, 7),
                null, null);
        employee = employees.hire("EMP-501", "Didier", "Lokwa", LocalDate.of(2024, 2, 5),
                null, null);
        employees.setManager(employee.getId(), manager.getId(), null);

        annual = balances.createType("ANNUAL", "Annual leave", new BigDecimal("20"),
                AccrualMethod.ANNUAL_GRANT, null);
        balances.grant(employee.getId(), annual.getId(), YEAR, new BigDecimal("20"),
                LedgerEntryType.GRANT, "2026 entitlement", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private LeaveRequest submitWeek() {
        return requests.submit(employee.getId(), annual.getId(), MONDAY, FRIDAY,
                false, false, "Family visit", null, null);
    }

    private LeaveBalance balance() {
        return balances.balanceFor(employee.getId(), annual.getId(), YEAR);
    }

    // --- submitting --------------------------------------------------------

    @Test
    @DisplayName("submitting reserves the days before anybody has approved anything")
    void reservesOnSubmission() {
        LeaveRequest request = submitWeek();

        assertThat(request.getStatus()).isEqualTo(LeaveRequestStatus.SUBMITTED);
        assertThat(request.getDays()).isEqualByComparingTo("5");
        assertThat(balance().getPendingDays()).isEqualByComparingTo("5");
        assertThat(balance().getTakenDays()).isEqualByComparingTo("0");
        assertThat(balance().available()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("two pending requests cannot both spend the same days")
    void reservationsAccumulate() {
        // 20 days of entitlement, then three weeks requested one after another.
        requests.submit(employee.getId(), annual.getId(), MONDAY, FRIDAY, false, false,
                null, null, null);
        requests.submit(employee.getId(), annual.getId(), LocalDate.of(2026, 6, 8),
                LocalDate.of(2026, 6, 19), false, false, null, null, null);

        assertThat(balance().available()).isEqualByComparingTo("5");

        // A fourth week would be 5 more days against 5 remaining, which fits; a fifth would not.
        requests.submit(employee.getId(), annual.getId(), LocalDate.of(2026, 6, 22),
                LocalDate.of(2026, 6, 26), false, false, null, null, null);

        assertThatThrownBy(() -> requests.submit(employee.getId(), annual.getId(),
                LocalDate.of(2026, 6, 29), LocalDate.of(2026, 7, 3), false, false,
                null, null, null))
                .isInstanceOf(LeaveBalance.InsufficientLeaveException.class);
    }

    @Test
    @DisplayName("a request that overlaps one already in flight is refused")
    void refusesOverlap() {
        submitWeek();

        assertThatThrownBy(() -> requests.submit(employee.getId(), annual.getId(),
                LocalDate.of(2026, 6, 3), LocalDate.of(2026, 6, 10), false, false,
                null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a range of only weekends is refused rather than recorded as zero days")
    void refusesRangeWithNoWorkingDays() {
        assertThatThrownBy(() -> requests.submit(employee.getId(), annual.getId(),
                LocalDate.of(2026, 6, 6), LocalDate.of(2026, 6, 7), false, false,
                null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a request crossing the year end is refused, not silently split")
    void refusesRequestAcrossYearEnd() {
        assertThatThrownBy(() -> requests.submit(employee.getId(), annual.getId(),
                LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 8), false, false,
                null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("leave that needs a certificate cannot be submitted without one")
    void refusesMissingDocument() {
        LeaveType sick = balances.createType("SICK", "Sick leave", new BigDecimal("10"),
                AccrualMethod.ANNUAL_GRANT, null);
        sick.configure(true, BigDecimal.ZERO, new BigDecimal("2"), true, true);

        assertThatThrownBy(() -> requests.submit(employee.getId(), sick.getId(), MONDAY, FRIDAY,
                false, false, null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("leave that allows a negative balance is not refused for want of days")
    void allowsNegativeWhereThePolicySaysSo() {
        LeaveType sick = balances.createType("SICK", "Sick leave", BigDecimal.ZERO,
                AccrualMethod.ANNUAL_GRANT, null);
        sick.configure(true, BigDecimal.ZERO, null, true, true);

        LeaveRequest request = requests.submit(employee.getId(), sick.getId(), MONDAY, FRIDAY,
                false, false, null, null, null);

        assertThat(request.getDays()).isEqualByComparingTo("5");
        assertThat(balances.balanceFor(employee.getId(), sick.getId(), YEAR).available())
                .isEqualByComparingTo("-5");
    }

    // --- deciding ----------------------------------------------------------

    @Test
    @DisplayName("approving turns the reservation into days taken")
    void approvingConsumes() {
        LeaveRequest request = submitWeek();

        LeaveRequest approved =
                requests.approve(request.getId(), manager.getId(), "Enjoy", null);

        assertThat(approved.getStatus()).isEqualTo(LeaveRequestStatus.APPROVED);
        assertThat(approved.getApprover().getId()).isEqualTo(manager.getId());
        assertThat(approved.getDecidedAt()).isNotNull();
        assertThat(balance().getPendingDays()).isEqualByComparingTo("0");
        assertThat(balance().getTakenDays()).isEqualByComparingTo("5");
        assertThat(balance().available()).isEqualByComparingTo("15");
    }

    @Test
    @DisplayName("nobody approves their own leave")
    void refusesSelfApproval() {
        LeaveRequest request = submitWeek();

        assertThatThrownBy(() ->
                requests.approve(request.getId(), employee.getId(), null, null))
                .isInstanceOf(LeaveRequestService.SelfApprovalException.class);
    }

    @Test
    @DisplayName("a refusal must say why")
    void refusalNeedsReason() {
        LeaveRequest request = submitWeek();

        assertThatThrownBy(() -> requests.reject(request.getId(), manager.getId(), "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a refused request returns the reserved days")
    void rejectingReturnsDays() {
        LeaveRequest request = submitWeek();

        requests.reject(request.getId(), manager.getId(), "Two people already off", null);

        assertThat(balance().getPendingDays()).isEqualByComparingTo("0");
        assertThat(balance().getTakenDays()).isEqualByComparingTo("0");
        assertThat(balance().available()).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("a request cannot be decided twice")
    void refusesSecondDecision() {
        LeaveRequest request = submitWeek();
        requests.approve(request.getId(), manager.getId(), null, null);

        assertThatThrownBy(() ->
                requests.reject(request.getId(), manager.getId(), "Changed my mind", null))
                .isInstanceOf(ConflictException.class);
    }

    // --- cancelling --------------------------------------------------------

    @Test
    @DisplayName("withdrawing a pending request releases the reservation without a ledger entry")
    void cancellingPendingLeavesLedgerAlone() {
        LeaveRequest request = submitWeek();
        int before = balances.ledgerFor(balance().getId()).size();

        requests.cancel(request.getId(), null);

        assertThat(balance().available()).isEqualByComparingTo("20");
        // Days that were never spent do not need explaining.
        assertThat(balances.ledgerFor(balance().getId())).hasSize(before);
    }

    @Test
    @DisplayName("cancelling approved leave refunds it and says so in the ledger")
    void cancellingApprovedRefunds() {
        LeaveRequest request = submitWeek();
        requests.approve(request.getId(), manager.getId(), null, null);

        requests.cancel(request.getId(), null);

        assertThat(balance().getTakenDays()).isEqualByComparingTo("0");
        assertThat(balance().available()).isEqualByComparingTo("20");
        assertThat(balances.ledgerFor(balance().getId()))
                .extracting(LeaveLedgerEntry::getEntryType)
                .contains(LedgerEntryType.RETURNED);
    }

    @Test
    @DisplayName("leave that has already begun cannot be cancelled away")
    void refusesCancelAfterLeaveBegan() {
        LocalDate started = LocalDate.now().minusWeeks(2).with(DayOfWeek.MONDAY);
        LeaveRequest request = requests.submit(employee.getId(), annual.getId(),
                started, started.plusDays(1), false, false, null, null, null);
        requests.approve(request.getId(), manager.getId(), null, null);

        assertThatThrownBy(() -> requests.cancel(request.getId(), null))
                .isInstanceOf(ConflictException.class);
    }

    // --- the ledger --------------------------------------------------------

    @Test
    @DisplayName("the ledger explains the balance rather than restating it")
    void ledgerExplainsTheBalance() {
        LeaveRequest request = submitWeek();
        requests.approve(request.getId(), manager.getId(), null, null);
        balances.adjust(employee.getId(), annual.getId(), YEAR, new BigDecimal("2"),
                "Time off in lieu for the weekend callout", null);

        List<LeaveLedgerEntry> entries = balances.ledgerFor(balance().getId());

        assertThat(entries).extracting(LeaveLedgerEntry::getEntryType)
                .containsExactly(LedgerEntryType.GRANT, LedgerEntryType.TAKEN,
                        LedgerEntryType.ADJUSTMENT);
        assertThat(entries.stream()
                .map(LeaveLedgerEntry::getDays)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(balance().available());
    }

    @Test
    @DisplayName("an adjustment must carry a reason")
    void adjustmentNeedsReason() {
        assertThatThrownBy(() -> balances.adjust(employee.getId(), annual.getId(), YEAR,
                new BigDecimal("2"), "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
