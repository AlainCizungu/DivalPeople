package ai.dival.dip.modules.ingest;

import ai.dival.dip.common.error.PolicyRefusedException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Reads the one sheet of an .xlsx file into a grid of text.
 *
 * <p><strong>No new dependency, deliberately.</strong> Apache POI would do this and more, and it
 * arrives with XMLBeans, commons-compress and a transitive tail — several megabytes of parsing
 * surface, reachable from an upload endpoint, in a platform that pins its supply chain by commit
 * SHA because of a compromised action. An .xlsx is a ZIP of XML and the subset needed here is
 * small: shared strings, inline strings, numbers, and cell references. That subset is written out
 * below and everything outside it is refused rather than guessed at.
 *
 * <p>The trade is real and worth stating. POI handles files this does not — multiple sheets,
 * date-formatted cells, the old binary .xls — and this class refuses each of them with a sentence
 * saying so, instead of half-reading them. A refusal an operator can act on is the acceptable
 * failure; a column quietly read wrong is not, because the rows are stored immutably and
 * everything downstream trusts them.
 *
 * <p><strong>Dates are not interpreted.</strong> Excel stores a date as a number plus a display
 * format, and resolving that means reading styles.xml and a table of format ids — the exact place
 * a hand-written reader gets it subtly wrong, and being wrong about a date here would mean being
 * wrong about a retention period. So a date cell arrives as the serial number the file contains.
 * The profiled Vodacom export has no dates at all, so nothing is lost today; when a dated export
 * arrives this needs doing properly, and the import preview will show the bare numbers long
 * before anything is derived from them.
 */
final class XlsxReader {

    /** A ZIP that expands beyond this is refused: the file is a bomb or is not what it claims. */
    private static final long MAX_UNCOMPRESSED_BYTES = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 512;

    /** Excel's own limits. A reference beyond them is malformed, not merely large. */
    private static final int MAX_COLUMNS = 16_384;
    private static final int MAX_ROWS = 1_048_576;

    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] OLE2_MAGIC =
            {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0};

    private XlsxReader() {
    }

    /** Whether these bytes are a ZIP container, which is what an .xlsx is. */
    static boolean looksLikeXlsx(byte[] content) {
        return startsWith(content, ZIP_MAGIC);
    }

    /** The old binary format, which is a different thing entirely and cannot be read here. */
    static boolean looksLikeLegacyXls(byte[] content) {
        return startsWith(content, OLE2_MAGIC);
    }

    private static boolean startsWith(byte[] content, byte[] magic) {
        if (content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (content[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return one list per sheet row, cells in column order, gaps filled with empty strings
     */
    static List<List<String>> grid(byte[] content) {
        Map<String, byte[]> parts = unzip(content);

        List<String> sheets = parts.keySet().stream()
                .filter(name -> name.startsWith("xl/worksheets/") && name.endsWith(".xml"))
                .sorted()
                .toList();

        if (sheets.isEmpty()) {
            throw new PolicyRefusedException(
                    "This file is a ZIP archive but does not contain a spreadsheet. If it was "
                            + "exported from Excel, save it again as .xlsx.");
        }
        if (sheets.size() > 1) {
            // Refused rather than defaulting to the first. Which sheet holds the data is a
            // question with a real answer that only the operator has, and quietly importing one
            // of several would be a decision made by alphabetical order.
            throw new PolicyRefusedException(
                    "This workbook has " + sheets.size() + " sheets. Send a file containing only "
                            + "the sheet to be imported, so that which data was loaded is not a "
                            + "guess.");
        }

        List<String> shared = sharedStrings(parts.get("xl/sharedStrings.xml"));
        return sheet(parts.get(sheets.get(0)), shared);
    }

    // --- the container ------------------------------------------------------

    private static Map<String, byte[]> unzip(byte[] content) {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        long total = 0;
        int entries = 0;

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new PolicyRefusedException(
                            "This archive contains more than " + MAX_ENTRIES + " parts, which no "
                                    + "spreadsheet does.");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                // Only the parts that are read. Everything else — images, printer settings,
                // calculation chains — is left in the archive rather than expanded into memory.
                String name = entry.getName();
                if (!name.equals("xl/sharedStrings.xml")
                        && !(name.startsWith("xl/worksheets/") && name.endsWith(".xml"))) {
                    continue;
                }

                byte[] part = zip.readAllBytes();
                total += part.length;
                if (total > MAX_UNCOMPRESSED_BYTES) {
                    // A few kilobytes of ZIP can expand to gigabytes of XML. The upload limit
                    // governs the compressed size and cannot see this.
                    throw new PolicyRefusedException(
                            "This file expands to more than "
                                    + (MAX_UNCOMPRESSED_BYTES / 1024 / 1024)
                                    + " MB when opened, which is more than can be read safely.");
                }
                parts.put(name, part);
            }
        } catch (PolicyRefusedException refused) {
            throw refused;
        } catch (Exception ex) {
            throw new PolicyRefusedException(
                    "This file could not be opened as a spreadsheet. It may be corrupt, "
                            + "password-protected, or not really an .xlsx file.");
        }
        return parts;
    }

    /**
     * An XML reader that will not fetch anything.
     *
     * <p>The bytes come from an upload. A parser that resolves DTDs or external entities on
     * untrusted input reads local files and makes outbound requests on the attacker's behalf,
     * which is the whole of XXE — and a spreadsheet has no legitimate use for either.
     */
    private static XMLStreamReader parser(byte[] xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);
        return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    // --- the parts ----------------------------------------------------------

    /** The workbook's string table. Absent when every cell is a number or an inline string. */
    private static List<String> sharedStrings(byte[] xml) {
        List<String> strings = new ArrayList<>();
        if (xml == null) {
            return strings;
        }
        try {
            XMLStreamReader reader = parser(xml);
            StringBuilder current = null;
            boolean inText = false;
            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String name = reader.getLocalName();
                        if (name.equals("si")) {
                            current = new StringBuilder();
                        } else if (name.equals("t")) {
                            inText = true;
                        }
                    }
                    case XMLStreamConstants.CHARACTERS -> {
                        if (inText && current != null) {
                            current.append(reader.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        String name = reader.getLocalName();
                        if (name.equals("t")) {
                            inText = false;
                        } else if (name.equals("si") && current != null) {
                            // Rich text splits one string across several <t> runs; appending
                            // rather than replacing is what keeps "Société Générale" whole when
                            // half of it happens to be bold.
                            strings.add(current.toString());
                            current = null;
                        }
                    }
                    default -> {
                        // Nothing else in this part carries meaning.
                    }
                }
            }
            reader.close();
        } catch (XMLStreamException ex) {
            throw new PolicyRefusedException("The workbook's text could not be read: it is malformed.");
        }
        return strings;
    }

    /** One worksheet, as a rectangular grid of text. */
    private static List<List<String>> sheet(byte[] xml, List<String> shared) {
        List<List<String>> rows = new ArrayList<>();
        try {
            XMLStreamReader reader = parser(xml);

            List<String> row = null;
            StringBuilder cell = null;
            boolean capturing = false;
            String cellType = null;
            int column = -1;

            while (reader.hasNext()) {
                switch (reader.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        switch (reader.getLocalName()) {
                            case "row" -> {
                                row = new ArrayList<>();
                                if (rows.size() >= MAX_ROWS) {
                                    throw new PolicyRefusedException(
                                            "This sheet has more rows than a worksheet can hold.");
                                }
                            }
                            case "c" -> {
                                cellType = reader.getAttributeValue(null, "t");
                                // Excel omits empty cells entirely, so the position has to come
                                // from the reference. Reading cells in document order and
                                // appending would shift every column after the first gap — and
                                // the aging columns in the real export are mostly gaps.
                                column = columnOf(reader.getAttributeValue(null, "r"));
                                cell = new StringBuilder();
                            }
                            case "v", "t" -> capturing = true;
                            default -> {
                                // <f> holds a formula. Not captured: what is wanted is the
                                // cached result in <v>, not the expression that produced it.
                            }
                        }
                    }
                    case XMLStreamConstants.CHARACTERS -> {
                        if (capturing && cell != null) {
                            cell.append(reader.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        switch (reader.getLocalName()) {
                            case "v", "t" -> capturing = false;
                            case "c" -> {
                                if (row != null && cell != null && column >= 0) {
                                    place(row, column, value(cell.toString(), cellType, shared));
                                }
                                cell = null;
                                cellType = null;
                                column = -1;
                            }
                            case "row" -> {
                                if (row != null) {
                                    rows.add(row);
                                    row = null;
                                }
                            }
                            default -> {
                                // Nothing else is structural here.
                            }
                        }
                    }
                    default -> {
                        // Comments, whitespace, processing instructions.
                    }
                }
            }
            reader.close();
        } catch (PolicyRefusedException refused) {
            throw refused;
        } catch (XMLStreamException ex) {
            throw new PolicyRefusedException("The sheet could not be read: its XML is malformed.");
        }
        return square(rows);
    }

    /** Puts a value at its column, padding the gaps Excel left out. */
    private static void place(List<String> row, int column, String value) {
        while (row.size() <= column) {
            row.add("");
        }
        row.set(column, value);
    }

    /**
     * Pads every row to the width of the widest.
     *
     * <p>Excel ends a row at its last non-empty cell, so rows genuinely have different lengths in
     * the file. Left alone, that reaches the reader's ragged-row check and every real export is
     * refused for a difference the operator cannot see in Excel and did not cause.
     */
    private static List<List<String>> square(List<List<String>> rows) {
        int width = 0;
        for (List<String> row : rows) {
            width = Math.max(width, row.size());
        }
        for (List<String> row : rows) {
            while (row.size() < width) {
                row.add("");
            }
        }
        return rows;
    }

    /** {@code "BPR_0"} lives at {@code "C4"}; this turns "C4" into 2. */
    private static int columnOf(String reference) {
        if (reference == null || reference.isEmpty()) {
            return -1;
        }
        int column = 0;
        for (int i = 0; i < reference.length(); i++) {
            char c = reference.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                column = column * 26 + (c - 'A' + 1);
                if (column > MAX_COLUMNS) {
                    throw new PolicyRefusedException(
                            "Cell reference " + reference + " is beyond the last column a "
                                    + "worksheet can have.");
                }
            } else if (c >= 'a' && c <= 'z') {
                column = column * 26 + (c - 'a' + 1);
            } else {
                break;
            }
        }
        return column - 1;
    }

    /**
     * A cell's text.
     *
     * <p>Numbers are normalised through BigDecimal rather than double, from the decimal string
     * the file already contains. An account reference of 15 digits survives; via double it would
     * come back as 1.23456789012345E14 and stop matching anything.
     */
    private static String value(String raw, String type, List<String> shared) {
        if (raw.isEmpty()) {
            return "";
        }
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(raw.trim());
                return index >= 0 && index < shared.size() ? shared.get(index) : "";
            } catch (NumberFormatException ex) {
                return "";
            }
        }
        if ("inlineStr".equals(type) || "str".equals(type)) {
            return raw;
        }
        if ("b".equals(type)) {
            return "1".equals(raw.trim()) ? "TRUE" : "FALSE";
        }
        if ("e".equals(type)) {
            // #N/A and friends. Kept verbatim: the real export has #N/A in three columns, and
            // blanking them would turn "the lookup failed" into "there was nothing here".
            return raw;
        }
        try {
            return new BigDecimal(raw.trim()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
            // Not a number after all. Whatever the file said is what gets stored.
            return raw;
        }
    }
}
