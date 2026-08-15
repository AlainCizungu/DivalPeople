package ai.dival.dip.modules.executive;

import static org.assertj.core.api.Assertions.assertThat;

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
                rows(row("2025-09", 400), row("2026-08", 12)),
                rows(row("2026-08", 31)),
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
                rows(row("2026-08", 5)),
                rows(row("2026-07", 90)),
                rows(row("2026-07", 40)));

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
                rows(row("2019-01", 9_000), row("2025-08", 7)),
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
    @DisplayName("a month outside the window's end is dropped, not clamped onto the last row")
    void newerRowsDoNotPileOntoTheEnd() {
        List<ExecutiveService.Month> series = ExecutiveService.series(AUGUST_2026,
                rows(row("2026-08", 3), row("2026-12", 500)), List.of(), List.of());

        assertThat(series.get(12).declared()).isEqualTo(3);
        assertThat(series.stream().mapToLong(ExecutiveService.Month::declared).sum()).isEqualTo(3);
    }

    // What is NOT tested here, said out loud rather than left as a gap.
    //
    // This class used to assert that the last evening of a month lands in that month rather than
    // the next one. It cannot any more, and the reason is a fix rather than a regression: the
    // bucketing moved into SQL, where "at time zone 'UTC'" makes date_trunc zone-free, because
    // date_trunc over a timestamptz otherwise truncates in the *session* time zone. The old test
    // asserted UTC bucketing performed in Java and passed while the query was doing something
    // else — a test that is green for a reason unrelated to the thing it names is worse than no
    // test, and this is the shape that produces one.
    //
    // Covering it now needs a real Postgres with a session zone deliberately set away from UTC.
    // Worth having and not written; recorded here so the absence is a decision.

    @Test
    @DisplayName("two rows landing in one month are added rather than one replacing the other")
    void rowsInTheSameMonthAccumulate() {
        // The grouped query returns one row per month, so this is defensive. It is worth having
        // because merge() and put() look identical at a glance and only one of them is right: the
        // wrong one silently reports the last row instead of the total.
        List<ExecutiveService.Month> series = ExecutiveService.series(AUGUST_2026,
                rows(row("2026-08", 10), row("2026-08", 5)),
                List.of(), List.of());

        assertThat(series.get(12).declared()).isEqualTo(15);
    }

    /**
     * One row as the query returns it: a {@code YYYY-MM} string and a count.
     *
     * <p>It used to build a {@code java.sql.Timestamp}, on the assumption that a native query over
     * a truncated {@code timestamptz} hands one back. The assumption was wrong on a real request
     * and the fixture could not tell, because a fixture supplies whatever type it chooses — which
     * is the standing weakness of testing a driver boundary without the driver. The query now
     * selects text, so there is nothing left to assume.
     */
    private static Object[] row(String month, long total) {
        return new Object[] {month, total};
    }

    /**
     * A list of rows, typed.
     *
     * <p>{@code List.of(row(...))} does not compile: a single {@code Object[]} handed to a varargs
     * method is spread rather than wrapped, so the inferred type is {@code List<Object>} and the
     * call will not match. This exists so that mistake is made once, here, rather than at each
     * of the 8 call sites below.
     */
    private static List<Object[]> rows(Object[]... items) {
        return List.of(items);
    }
}
