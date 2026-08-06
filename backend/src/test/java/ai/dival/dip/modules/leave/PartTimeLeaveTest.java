package ai.dival.dip.modules.leave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.employees.WorkPattern;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Part-time people, who the first version of this module quietly overcharged.
 *
 * <p>The claim being tested is that both sides scale: somebody on four days a week earns four
 * fifths of the entitlement and is charged four days for a week off, so they get the same number
 * of weeks away as a full-timer. If only one side scaled, the module would be taking a quarter of
 * their leave and nobody would notice until they ran out in October.
 */
@Transactional
@RequiresDocker
class PartTimeLeaveTest extends AbstractIntegrationTest {

    private static final LocalDate MONDAY = anchorMonday();
    private static final LocalDate FRIDAY = MONDAY.plusDays(4);

    private static LocalDate anchorMonday() {
        return LocalDate.now().plusWeeks(4).with(DayOfWeek.MONDAY);
    }

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private LeaveBalanceService balances;
    @Autowired
    private LeaveRequestService requests;
    @Autowired
    private WorkingDayCalculator calculator;

    private LeaveType annual;
    private int sequence;

    @BeforeEach
    void setUp() {
        UUID tenantId = tenants.save(new Tenant("PT", "pt-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);

        annual = balances.createType("ANNUAL", "Annual leave", new BigDecimal("20"),
                AccrualMethod.ANNUAL_GRANT, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Employee hire() {
        return employees.hire("EMP-7" + String.format("%02d", ++sequence), "Didier", "Lokwa",
                LocalDate.of(2024, 2, 5), null, null);
    }

    /** Monday to Thursday, Friday off. */
    private WorkPattern fourDayWeek() {
        return employees.createWorkPattern("4-DAY", "Four-day week", new BigDecimal[] {
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}, null);
    }

    private Employee partTimer(WorkPattern pattern) {
        Employee employee = hire();
        employees.setWorkPattern(employee.getId(), pattern.getId(), null);
        return employee;
    }

    // --- what a week costs -------------------------------------------------

    @Test
    @DisplayName("a week off costs a four-day worker four days, not five")
    void weekCostsFourDays() {
        Employee employee = partTimer(fourDayWeek());
        balances.grant(employee.getId(), annual.getId(), MONDAY.getYear(),
                new BigDecimal("16"), LedgerEntryType.GRANT, "entitlement", null);

        LeaveRequest request = requests.submit(employee.getId(), annual.getId(),
                MONDAY, FRIDAY, false, false, null, null, null);

        assertThat(request.getDays()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("a full-timer is still charged five for the same week")
    void fullTimerIsUnaffected() {
        Employee employee = hire();
        balances.grant(employee.getId(), annual.getId(), MONDAY.getYear(),
                new BigDecimal("20"), LedgerEntryType.GRANT, "entitlement", null);

        LeaveRequest request = requests.submit(employee.getId(), annual.getId(),
                MONDAY, FRIDAY, false, false, null, null, null);

        assertThat(request.getDays()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("a day somebody does not work is not charged")
    void nonWorkingDayIsFree() {
        WorkPattern pattern = fourDayWeek();

        // Friday alone: a working day for most people, nothing for this one.
        assertThat(calculator.countDays(pattern, FRIDAY, FRIDAY, false, false))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a half day comes off in proportion to the day worked")
    void halfDayScalesWithTheDay() {
        WorkPattern half = employees.createWorkPattern("WED-AM", "Wednesday mornings only",
                new BigDecimal[] {
                        BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("0.5"),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO},
                null);

        LocalDate wednesday = MONDAY.plusDays(2);

        assertThat(calculator.countDays(half, wednesday, wednesday, false, false))
                .isEqualByComparingTo("0.5");
        // Half of half a day is a quarter. Charging a flat half day would take more than the
        // person had that day.
        assertThat(calculator.countDays(half, wednesday, wednesday, true, true))
                .isEqualByComparingTo("0.25");
    }

    @Test
    @DisplayName("a request only covering days somebody does not work is refused")
    void refusesRangeOfNonWorkingDays() {
        Employee employee = partTimer(fourDayWeek());
        balances.grant(employee.getId(), annual.getId(), MONDAY.getYear(),
                new BigDecimal("16"), LedgerEntryType.GRANT, "entitlement", null);

        // Friday to Sunday: their day off, then the weekend.
        assertThatThrownBy(() -> requests.submit(employee.getId(), annual.getId(),
                MONDAY.plusDays(4), MONDAY.plusDays(6), false, false, null, null, null))
                .isInstanceOf(ConflictException.class);
    }

    // --- what they earn ----------------------------------------------------

    @Test
    @DisplayName("a four-day worker earns four fifths of the entitlement")
    void entitlementIsProRated() {
        WorkPattern pattern = fourDayWeek();

        assertThat(calculator.shareOfFullTime(pattern)).isEqualByComparingTo("0.8");
        assertThat(pattern.weeklyDays()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("somebody with no pattern earns a full entitlement")
    void noPatternMeansFullTime() {
        assertThat(calculator.shareOfFullTime(null)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("both sides scale, so a part-timer gets the same weeks away as anybody else")
    void bothSidesScale() {
        WorkPattern pattern = fourDayWeek();

        // Four fifths of twenty days is sixteen; a week costs four; sixteen over four is four
        // weeks — the same four weeks a full-timer gets from twenty days at five a week.
        BigDecimal entitlement = new BigDecimal("20").multiply(calculator.shareOfFullTime(pattern));
        BigDecimal weekCost = calculator.countDays(pattern, MONDAY, FRIDAY, false, false);

        assertThat(entitlement).isEqualByComparingTo("16");
        assertThat(weekCost).isEqualByComparingTo("4");
        assertThat(entitlement.divide(weekCost, 2, java.math.RoundingMode.HALF_UP))
                .isEqualByComparingTo("4");
    }

    // --- the pattern itself ------------------------------------------------

    @Test
    @DisplayName("a pattern with no working days is refused")
    void refusesEmptyPattern() {
        assertThatThrownBy(() -> employees.createWorkPattern("NONE", "Nothing at all",
                new BigDecimal[] {
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a day cannot be worth more than a whole day")
    void refusesFractionAboveOne() {
        assertThatThrownBy(() -> employees.createWorkPattern("TOO-MUCH", "Overtime as a pattern",
                new BigDecimal[] {
                        new BigDecimal("1.5"), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO}, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("clearing the pattern puts somebody back on the default week")
    void clearingRestoresFullTime() {
        Employee employee = partTimer(fourDayWeek());
        assertThat(employee.getWorkPattern()).isNotNull();

        employees.setWorkPattern(employee.getId(), null, null);

        assertThat(employee.getWorkPattern()).isNull();
        assertThat(calculator.countDays(employee.getWorkPattern(), MONDAY, FRIDAY, false, false))
                .isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("changing the pattern does not rewrite leave already requested")
    void pastRequestsKeepTheirCost() {
        Employee employee = hire();
        balances.grant(employee.getId(), annual.getId(), MONDAY.getYear(),
                new BigDecimal("20"), LedgerEntryType.GRANT, "entitlement", null);

        LeaveRequest request = requests.submit(employee.getId(), annual.getId(),
                MONDAY, FRIDAY, false, false, null, null, null);
        assertThat(request.getDays()).isEqualByComparingTo("5");

        employees.setWorkPattern(employee.getId(), fourDayWeek().getId(), null);

        // Still five. What somebody was charged is a fact about the past, and a contract change
        // today must not quietly rewrite it.
        assertThat(request.getDays()).isEqualByComparingTo("5");
    }
}
