package ai.dival.dip.dev;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.attendance.AttendanceService;
import ai.dival.dip.modules.attendance.TimeEntrySource;
import ai.dival.dip.modules.attendance.Timesheet;
import ai.dival.dip.modules.attendance.TimesheetRepository;
import ai.dival.dip.modules.attendance.TimesheetService;
import ai.dival.dip.modules.employees.Employee;
import ai.dival.dip.modules.employees.EmployeeRepository;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds two weeks of attendance for operator A.
 *
 * <p>Deliberately imperfect: one late evening, one short day, one correction with a reason, and a
 * part-timer whose four-day week shows a smaller expectation. A board where every week balances
 * exactly proves nothing about the arithmetic.
 *
 * <p>Local profile only.
 */
@Component
@Profile("local")
@ConditionalOnProperty(name = "dip.local.seed-attendance", havingValue = "true")
@Order(22) // after leave, before TIX
public class LocalAttendanceSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalAttendanceSeeder.class);

    private final AttendanceService attendance;
    private final TimesheetService timesheets;
    private final TimesheetRepository timesheetRepository;
    private final EmployeeRepository employees;
    private final TransactionTemplate transactionTemplate;
    private final ZoneId zone;

    public LocalAttendanceSeeder(AttendanceService attendance, TimesheetService timesheets,
                                 TimesheetRepository timesheetRepository,
                                 EmployeeRepository employees,
                                 TransactionTemplate transactionTemplate,
                                 @Value("${dip.hr.timezone:Africa/Kinshasa}") String zone) {
        this.attendance = attendance;
        this.timesheets = timesheets;
        this.timesheetRepository = timesheetRepository;
        this.employees = employees;
        this.transactionTemplate = transactionTemplate;
        this.zone = ZoneId.of(zone);
    }

    @Override
    public void run(ApplicationArguments args) {
        UUID tenantId = LocalTenantSeeder.OPERATOR_A;

        TenantContext.runAs(tenantId, () -> transactionTemplate.executeWithoutResult(status -> {
            if (!timesheetRepository.findByTenantIdAndStatusOrderByPeriodStartAsc(
                    tenantId, ai.dival.dip.modules.attendance.TimesheetStatus.DRAFT).isEmpty()) {
                return;
            }

            Employee director = employee(tenantId, "EMP-001");
            Employee engineer = employee(tenantId, "EMP-002");
            Employee analyst = employee(tenantId, "EMP-003");
            if (director == null || engineer == null) {
                log.info("No employees to seed attendance for");
                return;
            }

            LocalDate lastWeek = LocalDate.now().minusWeeks(1).with(DayOfWeek.MONDAY);
            LocalDate thisWeek = LocalDate.now().with(DayOfWeek.MONDAY);

            // A clean week for the engineer, then a week with a late evening in it.
            week(engineer, lastWeek, -1);
            week(engineer, thisWeek, 2);

            // The analyst works four days, so their week is expected to be shorter. Seeded so
            // the part-time expectation is visible on screen and not only in a test.
            if (analyst != null) {
                fourDayWeek(analyst, lastWeek);
            }

            // A correction with a reason, so the amendment trail has something in it.
            attendance.between(engineer.getId(), lastWeek, lastWeek).stream()
                    .findFirst()
                    .ifPresent(entry -> attendance.amend(
                            entry.getId(),
                            entry.getStartedAt(),
                            entry.getEndedAt().plusSeconds(1800),
                            entry.getBreakMinutes(),
                            "Stayed to finish the tower callout", null));

            // Last week submitted and waiting, so the approval queue is not empty.
            Timesheet submitted = timesheets.buildWeek(engineer.getId(), lastWeek, null);
            timesheets.submit(submitted.getId(), null);
            timesheets.buildWeek(engineer.getId(), thisWeek, null);

            log.info("Seeded two weeks of attendance for operator A, one awaiting a decision");
        }));
    }

    /** Monday to Friday, 08:00–17:00 with an hour unpaid. {@code lateOn} adds hours to one day. */
    private void week(Employee employee, LocalDate monday, int lateOn) {
        for (int i = 0; i < 5; i++) {
            LocalDate day = monday.plusDays(i);
            if (day.isAfter(LocalDate.now())) {
                // Nobody has worked tomorrow yet. Seeding the future would make every screen
                // show hours that have not happened.
                return;
            }
            int endHour = i == lateOn ? 20 : 17;
            attendance.record(employee.getId(), day, at(day, 8), at(day, endHour), 60,
                    TimeEntrySource.BIOMETRIC, null, null);
        }
    }

    private void fourDayWeek(Employee employee, LocalDate monday) {
        for (int i = 0; i < 4; i++) {
            LocalDate day = monday.plusDays(i);
            if (day.isAfter(LocalDate.now())) {
                return;
            }
            attendance.record(employee.getId(), day, at(day, 8), at(day, 17), 60,
                    TimeEntrySource.WEB, null, null);
        }
    }

    private Instant at(LocalDate day, int hour) {
        return day.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant();
    }

    private Employee employee(UUID tenantId, String number) {
        return employees.findByTenantIdAndEmployeeNumber(tenantId, number).orElse(null);
    }
}
