package ai.dival.dip.modules.leave;

import ai.dival.dip.common.tenancy.TenantContext;
import ai.dival.dip.modules.employees.WorkPattern;
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
 * over Easter, or counts a Saturday, or bills somebody on a four-day week for five days, is
 * quietly taking days from them — the kind of bug nobody reports and everybody resents.
 *
 * <p>Three things reduce what a day costs: the office being closed for a public holiday, the
 * person not working that weekday, and a half day marked at either end. A day already free is not
 * discounted twice.
 */
@Component
public class WorkingDayCalculator {

    private static final BigDecimal HALF = new BigDecimal("0.5");

    private final PublicHolidayRepository holidays;

    /** Used for anybody with no pattern of their own, which is most people. */
    private final Set<DayOfWeek> defaultWorkingDays;

    private final BigDecimal fullTimeWeeklyDays;

    public WorkingDayCalculator(PublicHolidayRepository holidays,
                                @Value("${dip.hr.working-days:MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY}")
                                List<DayOfWeek> defaultWorkingDays) {
        this.holidays = holidays;
        this.defaultWorkingDays = defaultWorkingDays.isEmpty()
                ? EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)
                : EnumSet.copyOf(defaultWorkingDays);
        this.fullTimeWeeklyDays = BigDecimal.valueOf(this.defaultWorkingDays.size());
    }

    /**
     * What a stretch of dates costs the person with this pattern.
     *
     * @param pattern how much of a week they work; null means full time on the default week
     */
    @Transactional(readOnly = true)
    public BigDecimal countDays(WorkPattern pattern, LocalDate start, LocalDate end,
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
            days = days.add(fractionOn(pattern, day, closed));
        }

        // Half of whatever that day was worth, not a flat half day: somebody who works Wednesday
        // mornings and takes that morning off has used a quarter of a normal day, and charging
        // them half would take more than they had.
        if (halfDayStart) {
            days = days.subtract(fractionOn(pattern, start, closed).multiply(HALF));
        }
        // A single-day request marked at both ends is still half that day, not zero.
        if (halfDayEnd && !end.equals(start)) {
            days = days.subtract(fractionOn(pattern, end, closed).multiply(HALF));
        }

        return days.max(BigDecimal.ZERO);
    }

    /** Full time on the default working week. */
    @Transactional(readOnly = true)
    public BigDecimal countDays(LocalDate start, LocalDate end,
                                boolean halfDayStart, boolean halfDayEnd) {
        return countDays(null, start, end, halfDayStart, halfDayEnd);
    }

    /**
     * What one calendar date is worth to this person: nothing when the office is closed or they
     * do not work that day, otherwise their share of it.
     */
    public BigDecimal fractionOn(WorkPattern pattern, LocalDate day, Set<LocalDate> closedDates) {
        if (closedDates.contains(day)) {
            return BigDecimal.ZERO;
        }
        if (pattern == null) {
            return defaultWorkingDays.contains(day.getDayOfWeek())
                    ? BigDecimal.ONE
                    : BigDecimal.ZERO;
        }
        return pattern.fractionOn(day.getDayOfWeek());
    }

    /**
     * The share of a full-time entitlement this pattern earns.
     *
     * <p>Pro-rating both sides is what keeps it fair: four fifths of the days, and a week off
     * costs four. A part-timer and a full-timer end up with the same number of weeks away.
     */
    public BigDecimal shareOfFullTime(WorkPattern pattern) {
        return pattern == null ? BigDecimal.ONE : pattern.shareOfFullTime(fullTimeWeeklyDays);
    }

    @Transactional(readOnly = true)
    public Set<LocalDate> holidayDates(LocalDate from, LocalDate to) {
        return holidays.findByTenantIdAndHolidayDateBetween(TenantContext.require(), from, to)
                .stream()
                .map(PublicHoliday::getHolidayDate)
                .collect(Collectors.toUnmodifiableSet());
    }

    public Set<DayOfWeek> getDefaultWorkingDays() {
        return Set.copyOf(defaultWorkingDays);
    }

    public BigDecimal getFullTimeWeeklyDays() {
        return fullTimeWeeklyDays;
    }
}
