package ai.dival.dip.modules.attendance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.AbstractIntegrationTest;
import ai.dival.dip.RequiresDocker;
import ai.dival.dip.common.error.ConflictException;
import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeService;
import ai.dival.dip.modules.employees.WorkPattern;
import ai.dival.dip.modules.leave.AccrualMethod;
import ai.dival.dip.modules.leave.LeaveBalanceService;
import ai.dival.dip.modules.leave.LeaveRequest;
import ai.dival.dip.modules.leave.LeaveRequestService;
import ai.dival.dip.modules.leave.LeaveType;
import ai.dival.dip.modules.leave.LedgerEntryType;
import ai.dival.dip.modules.leave.PublicHolidayService;
import ai.dival.dip.modules.tenants.Tenant;
import ai.dival.dip.modules.tenants.TenantRepository;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The arithmetic a payslip rests on.
 *
 * <p>A standard day is 480 minutes and the weekly ceiling 2700, matching the defaults. Leave is
 * booked forward because leave that has already begun cannot be cancelled, but the timesheet
 * itself is computed over whatever week the leave lands in.
 */
@Transactional
@RequiresDocker
class TimesheetServiceTest extends AbstractIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");
    private static final int DAY = 480;

    /** A Monday four weeks out: far enough ahead that leave booked in it has not started. */
    private static final LocalDate MONDAY =
            LocalDate.now().plusWeeks(4).with(DayOfWeek.MONDAY);

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private AttendanceService attendance;
    @Autowired
    private TimesheetService timesheets;
    @Autowired
    private LeaveBalanceService balances;
    @Autowired
    private LeaveRequestService leave;
    @Autowired
    private PublicHolidayService holidays;

    private Employee employee;
    private Employee manager;
    private LeaveType annual;

    @BeforeEach
    void setUp() {
        UUID tenantId = tenants.save(new Tenant("TS", "ts-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);

        manager = employees.hire("EMP-900", "Sylvie", "Mbala", LocalDate.of(2019, 1, 7),
                null, null);
        employee = employees.hire("EMP-901", "Didier", "Lokwa", LocalDate.of(2024, 2, 5),
                null, null);
        employees.setManager(employee.getId(), manager.getId(), null);

        annual = balances.createType("ANNUAL", "Annual leave", new BigDecimal("20"),
                AccrualMethod.ANNUAL_GRANT, null);
        balances.grant(employee.getId(), annual.getId(), MONDAY.getYear(), new BigDecimal("20"),
                LedgerEntryType.GRANT, "entitlement", null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Instant at(LocalDate day, int hour) {
        return day.atTime(LocalTime.of(hour, 0)).atZone(ZONE).toInstant();
    }

    /** A conventional day: 08:00 to 17:00 with an hour unpaid. */
    private void workDay(LocalDate day, int fromHour, int toHour) {
        attendance.record(employee.getId(), day, at(day, fromHour), at(day, toHour),
                60, TimeEntrySource.MANUAL, null, null);
    }

    /** A short shift with no break, for the Saturdays. */
    private void workShift(LocalDate day, int fromHour, int toHour) {
        attendance.record(employee.getId(), day, at(day, fromHour), at(day, toHour),
                0, TimeEntrySource.MANUAL, null, null);
    }

    private void workFullWeek() {
        for (int i = 0; i < 5; i++) {
            workDay(MONDAY.plusDays(i), 8, 17);
        }
    }

    private TimesheetService.Totals week() {
        return timesheets.preview(employee.getId(), MONDAY, MONDAY.plusDays(6));
    }

    // --- the ordinary week -------------------------------------------------

    @Test
    @DisplayName("a full week worked matches what was expected, with nothing owed either way")
    void ordinaryWeekBalances() {
        workFullWeek();

        TimesheetService.Totals totals = week();

        assertThat(totals.worked()).isEqualTo(5 * DAY);
        assertThat(totals.expected()).isEqualTo(5 * DAY);
        assertThat(totals.overtime()).isZero();
        assertThat(totals.absent()).isZero();
    }

    @Test
    @DisplayName("a day not worked and not explained shows as absence")
    void unexplainedDayIsAbsence() {
        for (int i = 0; i < 4; i++) {
            workDay(MONDAY.plusDays(i), 8, 17);
        }

        TimesheetService.Totals totals = week();

        assertThat(totals.worked()).isEqualTo(4 * DAY);
        assertThat(totals.expected()).isEqualTo(5 * DAY);
        assertThat(totals.absent()).isEqualTo(DAY);
        assertThat(totals.overtime()).isZero();
    }

    @Test
    @DisplayName("weekends are neither expected nor absence")
    void weekendsAreNotExpected() {
        workFullWeek();
        // Saturday, worked voluntarily.
        workShift(MONDAY.plusDays(5), 9, 13);

        TimesheetService.Totals totals = week();

        assertThat(totals.expected()).isEqualTo(5 * DAY);
        assertThat(totals.worked()).isEqualTo(5 * DAY + 240);
        assertThat(totals.absent()).isZero();
        // Everything beyond the five expected days is overtime.
        assertThat(totals.overtime()).isEqualTo(240);
    }

    // --- what excuses a day ------------------------------------------------

    @Test
    @DisplayName("a public holiday is owed but is not absence")
    void holidayIsNotAbsence() {
        holidays.add(MONDAY.plusDays(2), "Founders' Day", null);
        workDay(MONDAY, 8, 17);
        workDay(MONDAY.plusDays(1), 8, 17);
        workDay(MONDAY.plusDays(3), 8, 17);
        workDay(MONDAY.plusDays(4), 8, 17);

        TimesheetService.Totals totals = week();

        assertThat(totals.expected()).isEqualTo(5 * DAY);
        assertThat(totals.holiday()).isEqualTo(DAY);
        assertThat(totals.worked()).isEqualTo(4 * DAY);
        // Four days worked, one closed: nothing unexplained.
        assertThat(totals.absent()).isZero();
    }

    @Test
    @DisplayName("approved leave is owed but is not absence")
    void approvedLeaveIsNotAbsence() {
        LeaveRequest request = leave.submit(employee.getId(), annual.getId(),
                MONDAY.plusDays(2), MONDAY.plusDays(2), false, false, null, null, null);
        leave.approve(request.getId(), manager.getId(), null, null);

        workDay(MONDAY, 8, 17);
        workDay(MONDAY.plusDays(1), 8, 17);
        workDay(MONDAY.plusDays(3), 8, 17);
        workDay(MONDAY.plusDays(4), 8, 17);

        TimesheetService.Totals totals = week();

        assertThat(totals.leave()).isEqualTo(DAY);
        assertThat(totals.worked()).isEqualTo(4 * DAY);
        // Turning an approved holiday into a disciplinary conversation is the failure here.
        assertThat(totals.absent()).isZero();
    }

    @Test
    @DisplayName("leave still awaiting a decision does not excuse the day")
    void pendingLeaveIsNotAnExcuse() {
        leave.submit(employee.getId(), annual.getId(), MONDAY.plusDays(2), MONDAY.plusDays(2),
                false, false, null, null, null);

        workDay(MONDAY, 8, 17);
        workDay(MONDAY.plusDays(1), 8, 17);
        workDay(MONDAY.plusDays(3), 8, 17);
        workDay(MONDAY.plusDays(4), 8, 17);

        TimesheetService.Totals totals = week();

        assertThat(totals.leave()).isZero();
        assertThat(totals.absent()).isEqualTo(DAY);
    }

    // --- overtime ----------------------------------------------------------

    @Test
    @DisplayName("staying late beyond what was owed is overtime")
    void lateEveningsAreOvertime() {
        workDay(MONDAY, 8, 19);
        for (int i = 1; i < 5; i++) {
            workDay(MONDAY.plusDays(i), 8, 17);
        }

        TimesheetService.Totals totals = week();

        assertThat(totals.worked()).isEqualTo(5 * DAY + 120);
        assertThat(totals.overtime()).isEqualTo(120);
        assertThat(totals.absent()).isZero();
    }

    @Test
    @DisplayName("leave shrinks what was owed, so hours worked around it count as overtime")
    void leaveReducesWhatIsOwed() {
        LeaveRequest request = leave.submit(employee.getId(), annual.getId(),
                MONDAY, MONDAY, false, false, null, null, null);
        leave.approve(request.getId(), manager.getId(), null, null);

        // Four ordinary days around one day of approved leave.
        for (int i = 1; i < 5; i++) {
            workDay(MONDAY.plusDays(i), 8, 17);
        }
        // Then a Saturday.
        workShift(MONDAY.plusDays(5), 8, 12);

        TimesheetService.Totals totals = week();

        assertThat(totals.expected()).isEqualTo(5 * DAY);
        assertThat(totals.leave()).isEqualTo(DAY);
        assertThat(totals.worked()).isEqualTo(4 * DAY + 240);
        // Owed after the leave excuse is four days; anything beyond that is overtime.
        assertThat(totals.overtime()).isEqualTo(240);
    }

    @Test
    @DisplayName("a part-timer's expectation follows their pattern")
    void partTimeExpectationIsSmaller() {
        WorkPattern fourDay = employees.createWorkPattern("4-DAY", "Four-day week",
                new BigDecimal[] {
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}, null);
        employees.setWorkPattern(employee.getId(), fourDay.getId(), null);

        for (int i = 0; i < 4; i++) {
            workDay(MONDAY.plusDays(i), 8, 17);
        }

        TimesheetService.Totals totals = week();

        assertThat(totals.expected()).isEqualTo(4 * DAY);
        assertThat(totals.worked()).isEqualTo(4 * DAY);
        assertThat(totals.absent()).isZero();
        assertThat(totals.overtime()).isZero();
    }

    @Test
    @DisplayName("a part-timer working their day off is in overtime immediately")
    void partTimerWorkingDayOffIsOvertime() {
        WorkPattern fourDay = employees.createWorkPattern("4-DAY", "Four-day week",
                new BigDecimal[] {
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO}, null);
        employees.setWorkPattern(employee.getId(), fourDay.getId(), null);

        for (int i = 0; i < 5; i++) {
            workDay(MONDAY.plusDays(i), 8, 17);
        }

        TimesheetService.Totals totals = week();

        assertThat(totals.expected()).isEqualTo(4 * DAY);
        assertThat(totals.worked()).isEqualTo(5 * DAY);
        assertThat(totals.overtime()).isEqualTo(DAY);
    }

    // --- the sheet ---------------------------------------------------------

    @Test
    @DisplayName("building a week freezes the figures onto a sheet")
    void buildFreezesTotals() {
        workFullWeek();

        Timesheet sheet = timesheets.buildWeek(employee.getId(), MONDAY.plusDays(2), null);

        assertThat(sheet.getPeriodStart()).isEqualTo(MONDAY);
        assertThat(sheet.getPeriodEnd()).isEqualTo(MONDAY.plusDays(6));
        assertThat(sheet.getWorkedMinutes()).isEqualTo(5 * DAY);
        assertThat(sheet.getStatus()).isEqualTo(TimesheetStatus.DRAFT);
    }

    @Test
    @DisplayName("a submitted sheet cannot be recalculated underneath its approver")
    void refusesRebuildAfterSubmission() {
        workFullWeek();
        Timesheet sheet = timesheets.buildWeek(employee.getId(), MONDAY, null);
        timesheets.submit(sheet.getId(), null);

        assertThatThrownBy(() -> timesheets.buildWeek(employee.getId(), MONDAY, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("nobody approves their own timesheet")
    void refusesSelfApproval() {
        workFullWeek();
        Timesheet sheet = timesheets.buildWeek(employee.getId(), MONDAY, null);
        timesheets.submit(sheet.getId(), null);

        assertThatThrownBy(() ->
                timesheets.approve(sheet.getId(), employee.getId(), null, null))
                .isInstanceOf(TimesheetService.SelfApprovalException.class);
    }

    @Test
    @DisplayName("refusing a timesheet must say why")
    void refusalNeedsReason() {
        workFullWeek();
        Timesheet sheet = timesheets.buildWeek(employee.getId(), MONDAY, null);
        timesheets.submit(sheet.getId(), null);

        assertThatThrownBy(() -> timesheets.reject(sheet.getId(), manager.getId(), "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a refused sheet can be corrected and resubmitted")
    void refusedSheetCanBeRebuilt() {
        workFullWeek();
        Timesheet sheet = timesheets.buildWeek(employee.getId(), MONDAY, null);
        timesheets.submit(sheet.getId(), null);
        timesheets.reject(sheet.getId(), manager.getId(), "Saturday is missing", null);

        assertThat(sheet.getStatus()).isEqualTo(TimesheetStatus.REJECTED);

        workShift(MONDAY.plusDays(5), 9, 13);
        Timesheet rebuilt = timesheets.buildWeek(employee.getId(), MONDAY, null);
        timesheets.submit(rebuilt.getId(), null);

        assertThat(rebuilt.getId()).isEqualTo(sheet.getId());
        assertThat(rebuilt.getWorkedMinutes()).isEqualTo(5 * DAY + 240);
        // A fresh submission starts a fresh decision rather than carrying the old refusal.
        assertThat(rebuilt.getStatus()).isEqualTo(TimesheetStatus.SUBMITTED);
        assertThat(rebuilt.getDecisionNotes()).isNull();
    }

    @Test
    @DisplayName("hours inside an approved sheet cannot be changed underneath it")
    void approvedPeriodIsClosedToEdits() {
        workFullWeek();
        Timesheet sheet = timesheets.buildWeek(employee.getId(), MONDAY, null);
        timesheets.submit(sheet.getId(), null);
        timesheets.approve(sheet.getId(), manager.getId(), null, null);

        assertThatThrownBy(() -> workDay(MONDAY.plusDays(5), 9, 13))
                .isInstanceOf(ConflictException.class);
    }
}
