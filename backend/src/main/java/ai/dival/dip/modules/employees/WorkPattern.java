package ai.dival.dip.modules.employees;

import ai.dival.dip.common.tenancy.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.util.Locale;

/**
 * How much of a week somebody works.
 *
 * <p>Each day carries a fraction: 1 for a full day, 0.5 for a half day, 0 for a day not worked.
 * Fractions rather than a set of days, because a four-and-a-half day week is common and a boolean
 * cannot express it.
 *
 * <p>Lives in the employees module rather than in leave, because it describes the employment
 * relationship. Leave is the first thing to need it; payroll will be the second.
 */
@Entity
@Table(name = "work_pattern")
public class WorkPattern extends TenantOwnedEntity {

    private static final BigDecimal MAX = BigDecimal.ONE;

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "monday", nullable = false, precision = 3, scale = 2)
    private BigDecimal monday = BigDecimal.ZERO;

    @Column(name = "tuesday", nullable = false, precision = 3, scale = 2)
    private BigDecimal tuesday = BigDecimal.ZERO;

    @Column(name = "wednesday", nullable = false, precision = 3, scale = 2)
    private BigDecimal wednesday = BigDecimal.ZERO;

    @Column(name = "thursday", nullable = false, precision = 3, scale = 2)
    private BigDecimal thursday = BigDecimal.ZERO;

    @Column(name = "friday", nullable = false, precision = 3, scale = 2)
    private BigDecimal friday = BigDecimal.ZERO;

    @Column(name = "saturday", nullable = false, precision = 3, scale = 2)
    private BigDecimal saturday = BigDecimal.ZERO;

    @Column(name = "sunday", nullable = false, precision = 3, scale = 2)
    private BigDecimal sunday = BigDecimal.ZERO;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected WorkPattern() {
        // for JPA
    }

    public WorkPattern(String code, String name) {
        this.code = normalizeCode(code);
        this.name = name == null ? null : name.trim();
        this.active = true;
    }

    public static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "-");
    }

    /** A conventional Monday-to-Friday week, for tenants that only need the one pattern. */
    public static WorkPattern fullTime(String code, String name) {
        WorkPattern pattern = new WorkPattern(code, name);
        pattern.setWeek(BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
        return pattern;
    }

    public void setWeek(BigDecimal monday, BigDecimal tuesday, BigDecimal wednesday,
                        BigDecimal thursday, BigDecimal friday, BigDecimal saturday,
                        BigDecimal sunday) {
        this.monday = check(monday, "Monday");
        this.tuesday = check(tuesday, "Tuesday");
        this.wednesday = check(wednesday, "Wednesday");
        this.thursday = check(thursday, "Thursday");
        this.friday = check(friday, "Friday");
        this.saturday = check(saturday, "Saturday");
        this.sunday = check(sunday, "Sunday");

        if (weeklyDays().signum() <= 0) {
            // Every leave request would cost nothing and every accrual would be zero. That is
            // not a part-time contract, it is a broken row.
            throw new IllegalArgumentException("A work pattern needs at least one working day");
        }
    }

    private static BigDecimal check(BigDecimal fraction, String day) {
        BigDecimal value = fraction == null ? BigDecimal.ZERO : fraction;
        if (value.signum() < 0 || value.compareTo(MAX) > 0) {
            throw new IllegalArgumentException(
                    day + " must be between 0 and 1, not " + value);
        }
        return value;
    }

    /** How much of a normal day this person works on a given weekday. */
    public BigDecimal fractionOn(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> monday;
            case TUESDAY -> tuesday;
            case WEDNESDAY -> wednesday;
            case THURSDAY -> thursday;
            case FRIDAY -> friday;
            case SATURDAY -> saturday;
            case SUNDAY -> sunday;
        };
    }

    /** Days worked in a full week. 5 for a conventional week, 4 for a four-day one. */
    public BigDecimal weeklyDays() {
        return monday.add(tuesday).add(wednesday).add(thursday).add(friday)
                .add(saturday).add(sunday);
    }

    /**
     * This pattern as a share of a full-time week.
     *
     * <p>Used to pro-rate entitlement, which is what keeps the arithmetic fair in both
     * directions: somebody on four days a year gets four fifths of the days, and a week off
     * costs them four. Both sides scale, so a part-timer and a full-timer get the same number of
     * weeks away.
     */
    public BigDecimal shareOfFullTime(BigDecimal fullTimeWeeklyDays) {
        if (fullTimeWeeklyDays == null || fullTimeWeeklyDays.signum() <= 0) {
            throw new IllegalArgumentException("A full-time week must be a positive number of days");
        }
        return weeklyDays().divide(fullTimeWeeklyDays, 4, RoundingMode.HALF_UP);
    }

    public void rename(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A work pattern needs a name");
        }
        this.name = name.trim();
    }

    /** Retired rather than deleted: employees already point at it. */
    public void retire() {
        this.active = false;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getMonday() {
        return monday;
    }

    public BigDecimal getTuesday() {
        return tuesday;
    }

    public BigDecimal getWednesday() {
        return wednesday;
    }

    public BigDecimal getThursday() {
        return thursday;
    }

    public BigDecimal getFriday() {
        return friday;
    }

    public BigDecimal getSaturday() {
        return saturday;
    }

    public BigDecimal getSunday() {
        return sunday;
    }

    public boolean isActive() {
        return active;
    }
}
