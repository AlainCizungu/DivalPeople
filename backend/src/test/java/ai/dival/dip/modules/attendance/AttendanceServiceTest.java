package ai.dival.dip.modules.attendance;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@Transactional
@RequiresDocker
class AttendanceServiceTest extends AbstractIntegrationTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 6, 1);
    private static final ZoneId ZONE = ZoneId.of("Africa/Kinshasa");

    @Autowired
    private TenantRepository tenants;
    @Autowired
    private EmployeeService employees;
    @Autowired
    private AttendanceService attendance;

    private Employee employee;

    @BeforeEach
    void setUp() {
        UUID tenantId = tenants.save(new Tenant("AT", "at-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantId);
        employee = employees.hire("EMP-800", "Didier", "Lokwa", LocalDate.of(2024, 2, 5),
                null, null);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Instant at(LocalDate day, int hour, int minute) {
        return day.atTime(LocalTime.of(hour, minute)).atZone(ZONE).toInstant();
    }

    private TimeEntry shift(LocalDate day, int fromHour, int toHour, int breakMinutes) {
        return attendance.record(employee.getId(), day, at(day, fromHour, 0),
                at(day, toHour, 0), breakMinutes, TimeEntrySource.MANUAL, null, null);
    }

    // --- clocking ----------------------------------------------------------

    @Test
    @DisplayName("clocking in leaves an entry open until somebody clocks out")
    void clockInLeavesEntryOpen() {
        TimeEntry entry = attendance.clockIn(employee.getId(), at(MONDAY, 8, 0),
                TimeEntrySource.WEB, null);

        assertThat(entry.isOpen()).isTrue();
        assertThat(entry.getEndedAt()).isNull();
        // An open entry has no worked time yet; guessing at one would inflate a live timesheet.
        assertThat(entry.workedMinutes()).isZero();
        assertThat(attendance.openEntry(employee.getId())).isPresent();
    }

    @Test
    @DisplayName("nobody can be clocked in twice at once")
    void refusesSecondClockIn() {
        attendance.clockIn(employee.getId(), at(MONDAY, 8, 0), TimeEntrySource.WEB, null);

        assertThatThrownBy(() -> attendance.clockIn(employee.getId(), at(MONDAY, 9, 0),
                TimeEntrySource.WEB, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("clocking out records the worked time, breaks removed")
    void clockOutSubtractsBreak() {
        attendance.clockIn(employee.getId(), at(MONDAY, 8, 0), TimeEntrySource.WEB, null);

        TimeEntry closed = attendance.clockOut(employee.getId(), at(MONDAY, 17, 0), 60, null);

        assertThat(closed.spanMinutes()).isEqualTo(540);
        assertThat(closed.workedMinutes()).isEqualTo(480);
        assertThat(closed.isOpen()).isFalse();
    }

    @Test
    @DisplayName("clocking out without being clocked in is refused")
    void refusesClockOutWithoutClockIn() {
        assertThatThrownBy(() ->
                attendance.clockOut(employee.getId(), at(MONDAY, 17, 0), 0, null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a break longer than the shift is refused")
    void refusesBreakLongerThanShift() {
        assertThatThrownBy(() -> shift(MONDAY, 9, 12, 240))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a shift cannot end before it starts")
    void refusesReversedShift() {
        assertThatThrownBy(() -> attendance.record(employee.getId(), MONDAY,
                at(MONDAY, 17, 0), at(MONDAY, 8, 0), 0, TimeEntrySource.MANUAL, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- overlaps ----------------------------------------------------------

    @Test
    @DisplayName("two entries cannot cover the same hour")
    void refusesOverlappingEntries() {
        shift(MONDAY, 8, 12, 0);

        assertThatThrownBy(() -> shift(MONDAY, 11, 15, 0))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("two entries that merely touch are allowed")
    void allowsAdjacentEntries() {
        shift(MONDAY, 8, 12, 0);
        TimeEntry afternoon = shift(MONDAY, 12, 17, 0);

        assertThat(afternoon.workedMinutes()).isEqualTo(300);
    }

    @Test
    @DisplayName("an open entry blocks anything recorded after it")
    void openEntryBlocksLaterRecord() {
        attendance.clockIn(employee.getId(), at(MONDAY, 8, 0), TimeEntrySource.WEB, null);

        // Somebody still clocked in overlaps anything starting after them, which is the honest
        // reading of an entry with no end.
        assertThatThrownBy(() -> shift(MONDAY, 14, 18, 0))
                .isInstanceOf(ConflictException.class);
    }

    // --- amendments --------------------------------------------------------

    @Test
    @DisplayName("a correction replaces the entry and leaves the original visible")
    void amendmentSupersedesRatherThanOverwrites() {
        TimeEntry original = shift(MONDAY, 8, 17, 60);

        TimeEntry corrected = attendance.amend(original.getId(), at(MONDAY, 8, 0),
                at(MONDAY, 19, 0), 60, "Stayed for the tower callout", null);

        assertThat(original.isSuperseded()).isTrue();
        assertThat(corrected.getSupersedes().getId()).isEqualTo(original.getId());
        assertThat(corrected.getAmendReason()).isEqualTo("Stayed for the tower callout");
        assertThat(corrected.workedMinutes()).isEqualTo(600);

        // The live view shows only the correction; the history shows both.
        assertThat(attendance.between(employee.getId(), MONDAY, MONDAY)).hasSize(1);
        assertThat(attendance.historyFor(employee.getId(), MONDAY)).hasSize(2);
    }

    @Test
    @DisplayName("a correction must say why")
    void amendmentNeedsReason() {
        TimeEntry original = shift(MONDAY, 8, 17, 60);

        assertThatThrownBy(() -> attendance.amend(original.getId(), at(MONDAY, 8, 0),
                at(MONDAY, 19, 0), 60, "  ", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("an entry cannot be corrected twice; correct the correction")
    void refusesAmendingSupersededEntry() {
        TimeEntry original = shift(MONDAY, 8, 17, 60);
        attendance.amend(original.getId(), at(MONDAY, 8, 0), at(MONDAY, 19, 0), 60,
                "Late callout", null);

        assertThatThrownBy(() -> attendance.amend(original.getId(), at(MONDAY, 8, 0),
                at(MONDAY, 20, 0), 60, "Later still", null))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("a correction does not collide with the entry it replaces")
    void amendmentIgnoresItsOwnOriginal() {
        TimeEntry original = shift(MONDAY, 8, 17, 60);

        TimeEntry corrected = attendance.amend(original.getId(), at(MONDAY, 8, 30),
                at(MONDAY, 17, 30), 60, "Arrived late", null);

        assertThat(corrected.workedMinutes()).isEqualTo(480);
    }

    // --- isolation ---------------------------------------------------------

    @Test
    @DisplayName("one tenant's attendance is invisible to another")
    void entriesDoNotCrossTenants() {
        TimeEntry entry = shift(MONDAY, 8, 17, 60);

        UUID tenantB = tenants.save(new Tenant("AT B", "at-b-" + UUID.randomUUID(),
                Tenant.Edition.ENTERPRISE, "en")).getId();
        TenantContext.set(tenantB);

        assertThatThrownBy(() -> attendance.get(entry.getId()))
                .isInstanceOf(AttendanceService.TimeEntryNotFoundException.class);
    }

    @Test
    @DisplayName("entries come back in the order they happened")
    void entriesAreOrdered() {
        shift(MONDAY, 13, 17, 0);
        shift(MONDAY, 8, 12, 0);

        List<TimeEntry> found = attendance.between(employee.getId(), MONDAY, MONDAY);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).getStartedAt()).isBefore(found.get(1).getStartedAt());
    }
}
