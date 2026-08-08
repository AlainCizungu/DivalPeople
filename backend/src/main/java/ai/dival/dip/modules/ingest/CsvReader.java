package ai.dival.dip.modules.ingest;

import ai.dival.dip.common.error.PolicyRefusedException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
 * <p>CSV only; {@link XlsxReader} handles spreadsheets and {@link TabularReader} decides which of
 * them a delivery needs. This class now does one job — bytes to a grid of cells — and hands the
 * grid to {@code TabularReader} for the header and the keyed rows, so that a CSV with a preamble
 * above its header is treated the same as a spreadsheet with one.
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
        return TabularReader.rowsFrom(grid(content));
    }

    /** The cells, laid out as they appear, before anything decides which row is the header. */
    static List<List<String>> grid(byte[] content) {
        return split(new String(content, StandardCharsets.UTF_8));
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
