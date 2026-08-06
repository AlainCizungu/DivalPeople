package ai.dival.dip.modules.leave;

import ai.dival.dip.common.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * How many days a stretch of dates actually costs somebody.
 *
 * <p>This is the arithmetic people check. A system that charges a full week for Monday to Friday
 * over Easter, or counts a Saturday, is quietly taking days from them — the kind of bug nobody
 * reports and everybody resents. Weekends and public holidays are free; half days at either end
 * cost half.
 *
 * <p>The working week is configuration rather than a per-employee pattern. That is a real
 * limitation: somebody on a four-day week is charged as though they work five. Per-employee work
 * patterns are recorded in the delivery plan and are not pretended at here.
 */
@Component
public class WorkingDayCalculator {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final PublicHolidayRepository holidays;
    private final Set<DayOfWeek> workingDays;

    public WorkingDayCalculator(PublicHolidayRepository holidays,
                                @Value("${dip.hr.working-days:MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY}")
                                List<DayOfWeek> workingDays) {
        this.holidays = holidays;
        this.workingDays = workingDays.isEmpty()
                ? EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
                : EnumSet.copyOf(workingDays);
    }

    /**
     * Working days between two dates inclusive, minus any half days.
     *
     * <p>A half day only comes off when that end is itself a working day. Marking a Saturday
     * start as a half day would otherwise subtract half a day that was never charged.
     */
    @Transactional(readOnly = true)
    public BigDecimal countDays(LocalDate start, LocalDate end,
                                boolean halfDayStart, boolean halfDayEnd) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("A leave request needs a start and an end date");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("Leave cannot end before it starts");
        }

        Set<LocalDate> closed = holidayDates(start, end);

        BigDecimal days = BigDecimal.ZERO;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            if (isWorkingDay(day, closed)) {
                days = days.add(BigDecimal.ONE);
            }
        }

        if (halfDayStart && isWorkingDay(start, closed)) {
            days = days.subtract(HALF);
        }
        // A single-day request marked as a half day at both ends is still half a day, not zero.
        if (halfDayEnd && !end.equals(start) && isWorkingDay(end, closed)) {
            days = days.subtract(HALF);
        }

        return days.max(BigDecimal.ZERO);
    }

    /** True when the office is open and the calendar is not closed. */
    public boolean isWorkingDay(LocalDate day, Set<LocalDate> closedDates) {
        return workingDays.contains(day.getDayOfWeek()) && !closedDates.contains(day);
    }

    @Transactional(readOnly = true)
    public Set<LocalDate> holidayDates(LocalDate from, LocalDate to) {
        return holidays.findByTenantIdAndHolidayDateBetween(TenantContext.require(), from, to)
                .stream()
                .map(PublicHoliday::getHolidayDate)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<DayOfWeek> getWorkingDays() {
        return Set.copyOf(workingDays);
    }
}
