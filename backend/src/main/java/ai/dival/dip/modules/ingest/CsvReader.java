package ai.dival.dip.modules.ingest;

import ai.dival.dip.common.error.PolicyRefusedException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns delivered bytes into rows, on the server.
 *
 * <p><strong>Why the server parses and not the browser.</strong> The batch's checksum is of the
 * file as received, and its whole purpose is to let an auditor holding the operator's copy ask
 * "is this the file you sent us". If the browser parsed the file and posted rows as JSON, the
 * stored rows and the checksummed bytes would be two separate claims with nothing tying them
 * together — a page could send one file's bytes and another file's rows and the platform could
 * not tell. Provenance would be decoration. So the bytes arrive, and the rows are derived from
 * the same bytes that were hashed.
 *
 * <p>CSV only. XLSX needs a library, a new dependency, and decisions about how a spreadsheet's
 * typed cells become text — all of which should be made while looking at a real Vodacom export
 * rather than in advance of one.
 *
 * <p>Hand-written rather than a dependency because the format is small and the failure modes are
 * specific to what telecom systems actually emit: a UTF-8 byte order mark from Excel on Windows,
 * CRLF line endings, quoted fields containing commas and newlines, and doubled quotes inside
 * quoted fields. Each of those has a test.
 */
final class CsvReader {

    /** Excel writes this at the start of a UTF-8 CSV and it is invisible in every editor. */
    private static final char BYTE_ORDER_MARK = '﻿';

    private CsvReader() {
    }

    /**
     * @return one map per data row, header to cell, preserving column order
     * @throws PolicyRefusedException when the file has no header, no data rows, duplicate column
     *         names, or a row whose length disagrees with the header
     */
    static List<Map<String, String>> read(byte[] content) {
        List<List<String>> rows = split(new String(content, StandardCharsets.UTF_8));
        if (rows.isEmpty()) {
            throw new PolicyRefusedException("The file is empty.");
        }

        List<String> header = rows.get(0);
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
        for (int i = 1; i < rows.size(); i++) {
            List<String> cells = rows.get(i);
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

    /** Splits into rows and cells, honouring quotes. */
    private static List<List<String>> split(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (i == 0 && c == BYTE_ORDER_MARK) {
                continue;
            }

            if (quoted) {
                if (c == '"') {
                    // A doubled quote inside a quoted field is one literal quote, not the end.
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
                continue;
            }

            switch (c) {
                case '"' -> quoted = true;
                case ',' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                }
                case '\r' -> {
                    // CRLF from Windows. The \n on the next pass ends the row; swallowing the \r
                    // here stops it becoming a trailing character on the last cell, which would
                    // make every identifier in the file fail to match by one invisible byte.
                }
                case '\n' -> {
                    row.add(cell.toString());
                    cell.setLength(0);
                    rows.add(row);
                    row = new ArrayList<>();
                }
                default -> cell.append(c);
            }
        }

        // Whatever is left when the text ends is the final cell of the final row, unless the file
        // ended with a newline and there is nothing pending.
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }
}
