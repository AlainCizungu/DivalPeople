package ai.dival.dip.modules.ingest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * What is actually in a delivered file, counted rather than described.
 *
 * <p>The Vodacom export was profiled by hand in August 2026 — fill rates, distinct counts, which
 * columns were spreadsheet working residue, that the aging buckets summed to the balance on every
 * row. That analysis took an afternoon, produced {@code docs/TIX_SOURCE_PROFILE.md}, and is the
 * single most useful thing anybody has done with the data. This is that afternoon, as a function.
 *
 * <p><strong>It describes and never interprets.</strong> No column is identified as an amount, an
 * identifier or a date; that mapping is Phase 2's next piece and depends on decisions nobody has
 * taken. Counting how many cells are filled requires no such decision, which is exactly why this
 * can exist now.
 *
 * <p>Pure and static, so it has a test that runs in milliseconds against the shapes real telecom
 * exports have — a column that is always the same word, one that is mostly {@code #N/A}, one whose
 * numbers must not be turned into doubles.
 */
final class BatchProfiler {

    /**
     * Above this many distinct values, the values themselves are not shown.
     *
     * <p>A column with eight or fewer distinct values is a vocabulary — {@code Write off},
     * {@code Active}, {@code Inactive} — and seeing it is most of what profiling is for. A column
     * with four thousand is a list of customers, and a screen full of those teaches nothing that
     * the distinct count has not already said.
     */
    private static final int VOCABULARY_LIMIT = 8;

    private BatchProfiler() {
    }

    static Profile profile(List<Map<String, String>> rows) {
        // Column order comes from the rows themselves and is preserved throughout. In a real
        // export several columns have equally plausible names, and position is often the only way
        // to tell which "Amount" somebody means.
        Map<String, List<String>> byColumn = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            row.forEach((column, value) ->
                    byColumn.computeIfAbsent(column, key -> new ArrayList<>())
                            .add(value == null ? "" : value));
        }

        List<Column> columns = new ArrayList<>();
        byColumn.forEach((name, values) -> columns.add(describe(name, values, rows.size())));
        return new Profile(rows.size(), List.copyOf(columns));
    }

    private static Column describe(String name, List<String> values, int totalRows) {
        List<String> present = values.stream().filter(v -> !v.isBlank()).toList();

        // Against the whole batch, not against the rows that happened to carry this key. A column
        // missing from some rows would otherwise report a fill rate on its own private
        // denominator, and every column on the screen would be measuring something different.
        int blank = totalRows - present.size();
        LinkedHashSet<String> distinct = new LinkedHashSet<>(present);

        int shortest = present.stream().mapToInt(String::length).min().orElse(0);
        int longest = present.stream().mapToInt(String::length).max().orElse(0);

        List<BigDecimal> numbers = asNumbers(present);
        boolean numeric = !present.isEmpty() && numbers != null;

        String total = null;
        String minimum = null;
        String maximum = null;
        if (numeric) {
            // BigDecimal, and the total is a string on the way out. This is the figure somebody
            // reconciles against the operator's own books; through a double it would come back
            // ending in 0.0000001 and cost an afternoon of somebody's trust.
            BigDecimal sum = BigDecimal.ZERO;
            for (BigDecimal number : numbers) {
                sum = sum.add(number);
            }
            total = sum.toPlainString();
            minimum = numbers.stream().min(Comparator.naturalOrder())
                    .orElseThrow().toPlainString();
            maximum = numbers.stream().max(Comparator.naturalOrder())
                    .orElseThrow().toPlainString();
        }

        List<ValueCount> vocabulary = List.of();
        if (!distinct.isEmpty() && distinct.size() <= VOCABULARY_LIMIT) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            present.forEach(value -> counts.merge(value, 1, Integer::sum));
            List<ValueCount> ordered = new ArrayList<>();
            counts.forEach((value, count) -> ordered.add(new ValueCount(value, count)));
            ordered.sort(Comparator.comparingInt(ValueCount::count).reversed());
            vocabulary = List.copyOf(ordered);
        }

        return new Column(name, present.size(), blank, distinct.size(), numeric,
                total, minimum, maximum, shortest, longest, vocabulary);
    }

    /**
     * The values as numbers, or null if any of them is not one.
     *
     * <p>All-or-nothing on purpose. A column where four thousand rows parse and one says
     * {@code #N/A} is not a numeric column with an outlier — it is a column somebody has to look
     * at, and reporting a total over the rows that happened to parse would hide exactly that.
     */
    private static List<BigDecimal> asNumbers(List<String> present) {
        List<BigDecimal> numbers = new ArrayList<>(present.size());
        for (String value : present) {
            try {
                numbers.add(new BigDecimal(value.trim()));
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return numbers;
    }

    /**
     * @param rows how many data rows the batch holds, so a fill rate can be read as a proportion
     */
    record Profile(int rows, List<Column> columns) {
    }

    /**
     * One column, described.
     *
     * @param filled        cells with something in them
     * @param blank         cells that are empty or whitespace
     * @param distinct      distinct non-blank values; equal to {@code filled} means a candidate
     *                      key, and {@code 1} means a constant
     * @param numeric       true only when <em>every</em> non-blank value parses as a number
     * @param total         the sum, as a decimal string; null unless numeric
     * @param vocabulary    the values themselves, only for columns few enough to be a vocabulary
     *                      rather than a customer list
     */
    record Column(String column, int filled, int blank, int distinct, boolean numeric,
                  String total, String minimum, String maximum,
                  int shortestLength, int longestLength, List<ValueCount> vocabulary) {
    }

    record ValueCount(String value, int count) {
    }
}
