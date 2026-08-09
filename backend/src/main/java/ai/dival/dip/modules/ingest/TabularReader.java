package ai.dival.dip.modules.ingest;

import ai.dival.dip.common.error.PolicyRefusedException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a delivered file into rows, whatever shape it arrived in.
 *
 * <p>Two things happen here that {@link CsvReader} did not do. The format is decided from the
 * bytes rather than the filename, because an operator renaming an export does not change what is
 * inside it. And the header is <em>found</em> rather than assumed to be line one, because the real
 * Vodacom export puts a blank row, an unlabelled total and another blank row above it — a file the
 * previous parser refused outright, which was the parser being right about its own rules and
 * useless about the only file we have.
 *
 * <p>The rows are still stored exactly as read. Nothing here interprets a column.
 */
final class TabularReader {

    /**
     * How far above the header a preamble may reach.
     *
     * <p>Bounded on purpose. A file whose table starts twenty rows down is not a table with a
     * heading on it, and hunting further would eventually find something header-shaped in a
     * covering note.
     */
    private static final int MAX_PREAMBLE_ROWS = 20;

    private TabularReader() {
    }

    /**
     * @return one map per data row, header to cell, preserving column order
     * @throws PolicyRefusedException when no header can be found, there are no data rows,
     *         two columns share a name, a column has no heading, or a row does not line up
     */
    static List<Map<String, String>> read(byte[] content) {
        if (XlsxReader.looksLikeLegacyXls(content)) {
            throw new PolicyRefusedException(
                    "This is the old binary .xls format. Open it in Excel and save it as .xlsx, "
                            + "or export it as CSV.");
        }
        List<List<String>> grid = XlsxReader.looksLikeXlsx(content)
                ? XlsxReader.grid(content)
                : CsvReader.grid(content);
        return rowsFrom(grid);
    }

    /**
     * Builds keyed rows from a grid, after deciding which line is the header.
     *
     * <p>Row numbers in every message below count from the top of the file, including whatever
     * preamble was skipped, so that a refusal names a line the operator can go and look at.
     */
    static List<Map<String, String>> rowsFrom(List<List<String>> grid) {
        if (grid.isEmpty()) {
            throw new PolicyRefusedException("The file is empty.");
        }

        int headerIndex = findHeader(grid);
        List<String> header = new ArrayList<>(grid.get(headerIndex));

        for (int i = 0; i < header.size(); i++) {
            String name = header.get(i).trim();
            if (name.isEmpty()) {
                throw new PolicyRefusedException(
                        "Column " + (i + 1) + " has no name. Every column needs a heading, "
                                + "because the heading is how a mapping refers to it.");
            }
            header.set(i, name);
        }
        if (header.stream().distinct().count() != header.size()) {
            throw new PolicyRefusedException(
                    "Two columns share a name. One would silently overwrite the other when the "
                            + "row became a map, so the file is refused rather than half-stored.");
        }

        List<Map<String, String>> parsed = new ArrayList<>();
        for (int i = headerIndex + 1; i < grid.size(); i++) {
            List<String> cells = grid.get(i);
            // A row that is entirely empty is the blank line at the end of almost every exported
            // file. Skipping it is not leniency about malformed data — it is refusing to treat a
            // trailing newline as a customer.
            if (cells.stream().allMatch(cell -> cell.trim().isEmpty())) {
                continue;
            }
            if (cells.size() != header.size()) {
                throw new PolicyRefusedException(
                        "Row " + (i + 1) + " has " + cells.size() + " cells but the header has "
                                + header.size() + ". A row that does not line up with the header "
                                + "cannot be stored honestly, because which column each value "
                                + "belongs to would be a guess.");
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                row.put(header.get(c), cells.get(c));
            }
            parsed.add(row);
        }

        if (parsed.isEmpty()) {
            throw new PolicyRefusedException("The file has a header but no rows.");
        }
        return parsed;
    }

    /**
     * The index of the header row.
     *
     * <p><strong>Deliberately unclever.</strong> The temptation is to score each row and pick the
     * most header-looking one, and that is how a parser starts disagreeing with the person who
     * made the file. The rule is narrow enough to state in a sentence: skip a leading row only
     * when it is <em>narrower than the file's widest row</em> and holds nothing but numbers — or
     * next to nothing at all.
     *
     * <p>The second half of that was learned from the file rather than imagined. The rule here
     * first said "fewer than two values", on the belief that a spreadsheet total is one figure
     * floating in a column. The real export's total is <em>eleven</em> figures — a grand total in
     * the first column and a subtotal under each of the ten aging buckets — so that rule promoted
     * the totals line to header, and the four columns it has no subtotal for became unnamed
     * columns. The file was refused for having no headings when its headings were two rows lower.
     *
     * <p>What separates the two rows is not how full they are. It is that a heading is a name and
     * a total is a number, and that the real header spans every column while a total spans only
     * the ones worth totalling. Both conditions are required: a full-width row is never skipped,
     * so a genuine header of years — {@code Name, 2023, 2024} — stays the header, and a header
     * with a missing name is still refused by name rather than quietly stepped over in favour of
     * the first row of data. A single-column CSV keeps its one-cell header, because a file that
     * is never wider than one has no row narrower than its widest.
     */
    private static int findHeader(List<List<String>> grid) {
        int widest = 0;
        for (List<String> row : grid) {
            widest = Math.max(widest, filled(row));
        }

        int limit = Math.min(grid.size(), MAX_PREAMBLE_ROWS);
        for (int i = 0; i < limit; i++) {
            List<String> row = grid.get(i);
            int count = filled(row);
            boolean narrower = count < widest;
            if (!narrower || (count >= 2 && !allNumbers(row))) {
                return i;
            }
        }
        throw new PolicyRefusedException(
                "No header row was found in the first " + limit + " rows. A header is a row of "
                        + "column names above the data; without one there is no way to say what "
                        + "any value means.");
    }

    /**
     * Whether every value in the row is a figure rather than a name.
     *
     * <p>An empty row satisfies this trivially, which is correct: it is certainly not a row of
     * names. Used only to recognise a totals line above the table, never to interpret data.
     */
    private static boolean allNumbers(List<String> row) {
        for (String cell : row) {
            if (cell == null || cell.trim().isEmpty()) {
                continue;
            }
            if (!isNumber(cell)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Tolerant on purpose.
     *
     * <p>A spreadsheet writes a total with thousands separators and sometimes a non-breaking
     * space, and none of that makes it a column name. Erring towards "this is a number" only ever
     * skips a row above the table; erring the other way leaves the totals line as the header,
     * which is the bug this exists to prevent.
     */
    private static boolean isNumber(String cell) {
        String bare = cell.replace(",", "").replace(" ", "").replace("\u00a0", "");
        if (bare.isEmpty()) {
            return false;
        }
        try {
            new BigDecimal(bare);
            return true;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** Cells with something in them. */
    private static int filled(List<String> row) {
        int count = 0;
        for (String cell : row) {
            if (cell != null && !cell.trim().isEmpty()) {
                count++;
            }
        }
        return count;
    }
}
