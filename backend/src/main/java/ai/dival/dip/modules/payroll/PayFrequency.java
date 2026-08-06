package ai.dival.dip.modules.payroll;

/** How often somebody is paid. Fixes what a "period" of their base salary means. */
public enum PayFrequency {

    MONTHLY,
    FORTNIGHTLY,
    WEEKLY,
    DAILY,
    HOURLY;

    /** How many of these periods fall in a year, for converting a rate between frequencies. */
    public int periodsPerYear() {
        return switch (this) {
            case MONTHLY -> 12;
            case FORTNIGHTLY -> 26;
            case WEEKLY -> 52;
            // Working days and hours, not calendar ones: a daily rate is per day worked.
            case DAILY -> 260;
            case HOURLY -> 2080;
        };
    }
}
