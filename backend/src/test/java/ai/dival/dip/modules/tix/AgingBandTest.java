package ai.dival.dip.modules.tix;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The band edges, one day either side of every one of them.
 *
 * <p>No database, so this is nearly free — and aging is exactly the kind of arithmetic that is
 * wrong by one day for a year before anybody notices, because every individual figure looks
 * reasonable. An operator reading its own book cannot tell that the 90-day column is quietly
 * borrowing from the 60-day one.
 */
class AgingBandTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 8);

    /** A debt that fell due {@code days} ago. */
    private static AgingBand after(long days) {
        return AgingBand.of(TODAY.minusDays(days), TODAY);
    }

    @Test
    @DisplayName("a debt that fell due today is in the first band, not somewhere older")
    void dueTodayIsTheYoungestBand() {
        assertThat(after(0)).isEqualTo(AgingBand.DAYS_30);
    }

    @Test
    @DisplayName("a debt not yet due is NOT_DUE rather than nought days old")
    void notYetDue() {
        // Unreachable by declaration, which refuses a future default date. Reachable by import,
        // where the source file has a "Not Due" column of its own.
        assertThat(AgingBand.of(TODAY.plusDays(1), TODAY)).isEqualTo(AgingBand.NOT_DUE);
    }

    @Test
    @DisplayName("every edge is upper-inclusive: 30 is in the 30 band, 31 is not")
    void edgesAreUpperInclusive() {
        assertThat(after(30)).isEqualTo(AgingBand.DAYS_30);
        assertThat(after(31)).isEqualTo(AgingBand.DAYS_60);

        assertThat(after(60)).isEqualTo(AgingBand.DAYS_60);
        assertThat(after(61)).isEqualTo(AgingBand.DAYS_90);

        assertThat(after(90)).isEqualTo(AgingBand.DAYS_90);
        assertThat(after(91)).isEqualTo(AgingBand.DAYS_120);

        assertThat(after(120)).isEqualTo(AgingBand.DAYS_120);
        assertThat(after(121)).isEqualTo(AgingBand.DAYS_150);

        assertThat(after(150)).isEqualTo(AgingBand.DAYS_150);
        assertThat(after(151)).isEqualTo(AgingBand.DAYS_180);

        assertThat(after(180)).isEqualTo(AgingBand.DAYS_180);
        assertThat(after(181)).isEqualTo(AgingBand.DAYS_270);

        assertThat(after(270)).isEqualTo(AgingBand.DAYS_270);
        assertThat(after(271)).isEqualTo(AgingBand.OVER_270);
    }

    @Test
    @DisplayName("everything past 270 days lands in one band, including well past 360")
    void theOldestBandIsUnbounded() {
        // Named OVER_270 rather than after 360, because the source file's last two labels are
        // "270 days" and "360 + days" with nothing between them and no statement of where a
        // 300-day debt belongs. A band called "360+" that in fact starts at 271 would be a lie
        // told by a label. See docs/TIX_SOURCE_PROFILE.md.
        assertThat(after(300)).isEqualTo(AgingBand.OVER_270);
        assertThat(after(365)).isEqualTo(AgingBand.OVER_270);
        assertThat(after(4000)).isEqualTo(AgingBand.OVER_270);
    }

    @Test
    @DisplayName("no age falls outside every band")
    void everyAgeHasABand() {
        // The bands must partition the number line. A gap would not throw; it would return null
        // and become a NullPointerException in an EnumMap months later.
        for (long days = -5; days <= 500; days++) {
            assertThat(after(days))
                    .as("age of %d days", days)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("a leap day does not shift a band")
    void spansALeapDay() {
        // 29 February 2028 sits inside this range. ChronoUnit.DAYS counts real days, so 30 days
        // before 5 March 2028 is 4 February — the point being that nothing here does month
        // arithmetic, which is where this class would otherwise go wrong once a year.
        LocalDate afterLeap = LocalDate.of(2028, 3, 5);
        assertThat(AgingBand.of(afterLeap.minusDays(30), afterLeap)).isEqualTo(AgingBand.DAYS_30);
        assertThat(AgingBand.of(afterLeap.minusDays(31), afterLeap)).isEqualTo(AgingBand.DAYS_60);
    }
}
