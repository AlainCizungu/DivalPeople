package ai.dival.dip.modules.executive;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shaping of the activity series, checked without a database.
 *
 * <p>No {@code @RequiresDocker} and no mock — this codebase has neither anywhere, and neither is
 * needed, because the arranging was made static and given its inputs. Every figure on the executive
 * screen is counted by another module; what is left to get wrong here is which months exist and
 * what a silent month looks like, and those are precisely the mistakes that render beautifully and
 * still mislead a board.
 */
class ActivitySeriesTest {

    private static final YearMonth AUGUST_2026 = YearMonth.of(2026, 8);

    @Test
    @DisplayName("the window is thirteen months and ends on the month asked about")
    void theWindowEndsWhereItWasAsked() {
        List<ExecutiveService.Month> series =
                ExecutiveService.series(AUGUST_2026, List.of(), List.of(), List.of());

        // Thirteen rather than twelve, so this month can be read against the same month a year ago
        // without the chart running out at exactly the point somebody wants to look.
        assertThat(series).hasSize(13);
        assertThat(series.get(0).month()).isEqualTo("2025-08");
        assertThat(series.get(12).month()).isEqualTo("2026-08");
    }

    @Test
    @DisplayName("a month in which nothing happened is a row of zeroes, not a missing row")
    void quietMonthsSurvive() {
        List<ExecutiveService.Month> series = ExecutiveService.series(AUGUST_2026,
                List.of(row("2025-09-01T00:00:00Z", 400), row("2026-08-01T00:00:00Z", 12)),
                List.of(row("2026-08-01T00:00:00Z", 31)),
                List.of());

        // The defect this test exists to prevent. A series that dropped its silent months would
        // draw a line straight from September to August and read as steady work through a year in
        // which the operator sent nothing. The gap is the finding; smoothing it is the lie, and it
        // is the more flattering of the two.
        assertThat(series).hasSize(13);
        assertThat(series.get(1).declared()).as("September 2025").isEqualTo(400);
        assertThat(series.get(2).declared()).as("October 2025, silent").isZero();
        assertThat(series.get(11).declared()).as("July 2026, silent").isZero();
        assertThat(series.get(12).declared()).as("August 2026").isEqualTo(12);
        assertThat(series.get(12).inquiries()).isEqualTo(31);
    }

    @Test
    @DisplayName("the three series are independent: a busy month for one is not a busy month for all")
    void seriesDoNotContaminateEachOther() {
        List<ExecutiveService.Month> series = ExecutiveService.series(AUGUST_2026,
                List.of(row("2026-08-01T00:00:00Z", 5)),
                List.of(row("2026-07-01T00:00:00Z", 90)),
                List.of(row("2026-07-01T00:00:00Z", 40)));

        ExecutiveService.Month july = series.get(11);
        ExecutiveService.Month august = series.get(12);

        assertThat(july.declared()).isZero();
        assertThat(july.inquiries()).isEqualTo(90);
        // Forty refusals in a month is either an allowance set too low for how the institution
        // works or a sweep somebody should ask about. Shown beside the answered ones rather than
        // hidden, because both readings want a person to look.
        assertThat(july.refused()).isEqualTo(40);
        assertThat(august.declared()).isEqualTo(5);
        assertThat(august.inquiries()).isZero();
        assertThat(august.refused()).isZero();
    }

    @Test
    @DisplayName("anything older than the window is dropped rather than piled onto the first month")
    void olderRowsDoNotAccumulateOnTheEdge() {
        List<ExecutiveService.Month> series = ExecutiveService.series(AUGUST_2026,
                List.of(row("2019-01-01T00:00:00Z", 9_000), row("2025-08-01T00:00:00Z", 7)),
                List.of(), List.of());

        // The query already filters by date, so this row should never arrive — but if it ever did,
        // clamping it into the first bucket would put nine thousand declarations on a month that
        // saw seven, and the chart would be spectacularly wrong in a way nobody could explain.
        assertThat(series.get(0).declared()).isEqualTo(7);
        assertThat(series.stream().mapToLong(ExecutiveService.Month::declared).sum())
                .as("the stray row contributes nowhere")
                .isEqualTo(7);
    }

    @Test
    @DisplayName("a month boundary is read in UTC, not in whatever zone the machine is set to")
    void monthsAreBucketedInUtc() {
        // 31 August, late evening UTC. Read in a zone ahead of UTC this instant is already
        // September, and the last day of every month would land in the following bucket — a
        // defect that appears once a month, in one cell, and is almost impossible to reproduce on
        // the machine of whoever reports it. The application's clock is Clock.systemUTC(), so the
        // bucketing has to agree with it.
        List<ExecutiveService.Month> series = ExecutiveService.series(AUGUST_2026,
                List.of(row("2026-08-31T23:30:00Z", 3)), List.of(), List.of());

        assertThat(series.get(12).month()).isEqualTo("2026-08");
        assertThat(series.get(12).declared()).isEqualTo(3);
    }

    @Test
    @DisplayName("two rows landing in one month are added rather than one replacing the other")
    void rowsInTheSameMonthAccumulate() {
        // The grouped query returns one row per month, so this is defensive. It is worth having
        // because merge() and put() look identical at a glance and only one of them is right: the
        // wrong one silently reports the last row instead of the total.
        List<ExecutiveService.Month> series = ExecutiveService.series(AUGUST_2026,
                List.of(row("2026-08-01T00:00:00Z", 10), row("2026-08-01T00:00:00Z", 5)),
                List.of(), List.of());

        assertThat(series.get(12).declared()).isEqualTo(15);
    }

    /**
     * One row as the driver returns it.
     *
     * <p>{@code java.sql.Timestamp} rather than {@code Instant}, because that is what a native
     * grouped query actually hands back. A fixture using the tidier type would be testing a
     * conversion the production code never performs, and the cast that does run would go unchecked.
     */
    private static Object[] row(String instant, long total) {
        return new Object[] {Timestamp.from(Instant.parse(instant)), total};
    }
}
