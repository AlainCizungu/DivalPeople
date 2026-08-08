package ai.dival.dip.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.common.error.PolicyRefusedException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The spreadsheet reader, against what Excel actually writes.
 *
 * <p>The workbooks here are built by hand rather than checked in as fixtures, which is the point:
 * every part below is a part the reader claims to understand, written out in the open. A binary
 * fixture would test the same thing while hiding what was being tested — and a real export cannot
 * be committed anyway, because it is somebody's customer list.
 */
class XlsxReaderTest {

    // --- building a workbook ------------------------------------------------

    private static final String NS =
            "http://schemas.openxmlformats.org/spreadsheetml/2006/main";

    private static byte[] workbook(Map<String, String> parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> part : parts.entrySet()) {
                zip.putNextEntry(new ZipEntry(part.getKey()));
                zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("could not build the test workbook", ex);
        }
        return bytes.toByteArray();
    }

    /** One sheet, plus a shared string table, plus the parts a real file carries and we ignore. */
    private static byte[] sheet(String rowsXml, String... strings) {
        StringBuilder sst = new StringBuilder("<sst xmlns=\"" + NS + "\">");
        for (String value : strings) {
            sst.append("<si><t>").append(value).append("</t></si>");
        }
        sst.append("</sst>");

        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<Types/>");
        parts.put("xl/workbook.xml", "<workbook/>");
        parts.put("xl/sharedStrings.xml", sst.toString());
        parts.put("xl/worksheets/sheet1.xml",
                "<worksheet xmlns=\"" + NS + "\"><sheetData>" + rowsXml
                        + "</sheetData></worksheet>");
        return workbook(parts);
    }

    private static List<Map<String, String>> read(byte[] content) {
        return TabularReader.read(content);
    }

    // --- recognising the format ---------------------------------------------

    @Test
    @DisplayName("a workbook is recognised by its bytes, not its name")
    void recognisedByMagic() {
        assertThat(XlsxReader.looksLikeXlsx(sheet("<row/>"))).isTrue();
        assertThat(XlsxReader.looksLikeXlsx("Customer,Balance\n".getBytes(StandardCharsets.UTF_8)))
                .isFalse();
    }

    @Test
    @DisplayName("the old binary .xls is refused with something the operator can do about it")
    void legacyXlsIsRefused() {
        byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0x00, 0x00};

        assertThatThrownBy(() -> read(ole2))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("save it as .xlsx");
    }

    // --- cells ---------------------------------------------------------------

    @Test
    @DisplayName("shared strings are resolved to their text")
    void sharedStrings() {
        byte[] file = sheet(
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c>"
                        + "<c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c>"
                        + "<c r=\"B2\"><v>184000</v></c></row>",
                "Bsr", "Balance", "Grand Horizon SARL");

        assertThat(read(file)).singleElement()
                .satisfies(row -> assertThat(row)
                        .containsEntry("Bsr", "Grand Horizon SARL")
                        .containsEntry("Balance", "184000"));
    }

    @Test
    @DisplayName("an omitted cell does not shift every column after it")
    void gapsKeepTheirPlace() {
        // The single most important property here. Excel writes nothing at all for an empty cell,
        // so reading cells in document order and appending would slide "360 + days" into the
        // "Not Due" column — and in the real export those aging columns are almost all empty.
        byte[] file = sheet(
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c>"
                        + "<c r=\"B1\" t=\"s\"><v>1</v></c>"
                        + "<c r=\"C1\" t=\"s\"><v>2</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>3</v></c>"
                        + "<c r=\"C2\"><v>4262</v></c></row>",
                "Bsr", "Not Due", "360 + days", "Acme");

        Map<String, String> row = read(file).get(0);
        assertThat(row).containsEntry("Bsr", "Acme");
        assertThat(row).containsEntry("Not Due", "");
        assertThat(row).containsEntry("360 + days", "4262");
    }

    @Test
    @DisplayName("short rows are padded rather than refused as ragged")
    void shortRowsArePadded() {
        // Excel ends a row at its last non-empty cell, so rows really do have different lengths.
        // Left alone that trips the ragged-row check and every genuine export is refused for a
        // difference the operator cannot see in Excel and did not cause.
        byte[] file = sheet(
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c>"
                        + "<c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c></row>",
                "Bsr", "Balance", "Acme");

        assertThat(read(file).get(0)).containsEntry("Balance", "");
    }

    @Test
    @DisplayName("a long account reference does not become scientific notation")
    void numbersKeepTheirDigits() {
        // Through a double, 123456789012345 comes back as 1.23456789012345E14 and stops matching
        // anything. The value is parsed from the decimal string the file already contains.
        byte[] file = sheet(
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c>"
                        + "<c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\"><v>123456789012345</v></c>"
                        + "<c r=\"B2\"><v>12383011.42</v></c></row>",
                "Reference", "Balance");

        assertThat(read(file).get(0))
                .containsEntry("Reference", "123456789012345")
                .containsEntry("Balance", "12383011.42");
    }

    @Test
    @DisplayName("a whole number does not gain a decimal point")
    void wholeNumbersStayWhole() {
        byte[] file = sheet(
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\"><v>100.00</v></c></row>"
                        + "<row r=\"3\"><c r=\"A3\"><v>1</v></c></row>",
                "Balance");

        assertThat(read(file)).extracting(row -> row.get("Balance"))
                .containsExactly("100", "1");
    }

    @Test
    @DisplayName("an #N/A cell survives as #N/A")
    void errorCellsSurvive() {
        // Three columns of the real export are #N/A on most rows. Blanking them would turn "the
        // lookup failed" into "there was nothing here", which are different facts.
        byte[] file = sheet(
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c>"
                        + "<c r=\"B1\" t=\"s\"><v>1</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c>"
                        + "<c r=\"B2\" t=\"e\"><v>#N/A</v></c></row>",
                "Bsr", "PWC", "Acme");

        assertThat(read(file).get(0)).containsEntry("PWC", "#N/A");
    }

    @Test
    @DisplayName("an inline string is read like any other text")
    void inlineStrings() {
        byte[] file = sheet(
                "<row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Bsr</t></is></c></row>"
                        + "<row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>Acme</t></is></c></row>");

        assertThat(read(file).get(0)).containsEntry("Bsr", "Acme");
    }

    @Test
    @DisplayName("a name split across formatting runs stays one name")
    void richTextIsJoined() {
        String sst = "<sst xmlns=\"" + NS + "\">"
                + "<si><t>Bsr</t></si>"
                + "<si><r><t>Société </t></r><r><t>Générale</t></r></si>"
                + "</sst>";
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("xl/sharedStrings.xml", sst);
        parts.put("xl/worksheets/sheet1.xml", "<worksheet xmlns=\"" + NS + "\"><sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c></row>"
                + "</sheetData></worksheet>");

        assertThat(read(workbook(parts)).get(0)).containsEntry("Bsr", "Société Générale");
    }

    @Test
    @DisplayName("a formula's cached result is read, not the formula")
    void formulaResultsNotExpressions() {
        byte[] file = sheet(
                "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>"
                        + "<row r=\"2\"><c r=\"A2\"><f>SUM(B1:B9)</f><v>12383011.42</v></c></row>",
                "Balance");

        assertThat(read(file).get(0)).containsEntry("Balance", "12383011.42");
    }

    // --- the workbook --------------------------------------------------------

    @Test
    @DisplayName("a workbook with several sheets is refused rather than guessed at")
    void severalSheetsAreRefused() {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("xl/worksheets/sheet1.xml", "<worksheet/>");
        parts.put("xl/worksheets/sheet2.xml", "<worksheet/>");

        // Which sheet holds the data is a question only the operator can answer. Taking the first
        // would be a decision made by alphabetical order.
        assertThatThrownBy(() -> read(workbook(parts)))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("2 sheets");
    }

    @Test
    @DisplayName("a ZIP that is not a workbook is refused")
    void otherArchivesAreRefused() {
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("notes.txt", "these are not accounts");

        assertThatThrownBy(() -> read(workbook(parts)))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("does not contain a spreadsheet");
    }

    @Test
    @DisplayName("a corrupt workbook is refused with a sentence, not a stack trace")
    void corruptWorkbookIsRefused() {
        byte[] truncated = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00};

        assertThatThrownBy(() -> read(truncated))
                .isInstanceOf(PolicyRefusedException.class);
    }

    // --- the file we actually have -------------------------------------------

    @Test
    @DisplayName("the shape of the real Vodacom export: blank, totals, blank, then the header")
    void theRealFileShape() {
        // Rows 1 and 3 empty, row 2 a single unlabelled total, row 4 the header. Exactly the file
        // profiled in docs/TIX_SOURCE_PROFILE.md, and exactly what the old parser refused.
        byte[] file = sheet(
                "<row r=\"1\"/>"
                        + "<row r=\"2\"><c r=\"C2\"><v>12383011.42</v></c></row>"
                        + "<row r=\"3\"/>"
                        + "<row r=\"4\"><c r=\"A4\" t=\"s\"><v>0</v></c>"
                        + "<c r=\"B4\" t=\"s\"><v>1</v></c>"
                        + "<c r=\"C4\" t=\"s\"><v>2</v></c></row>"
                        + "<row r=\"5\"><c r=\"A5\" t=\"s\"><v>3</v></c>"
                        + "<c r=\"B5\" t=\"s\"><v>4</v></c>"
                        + "<c r=\"C5\"><v>184000</v></c></row>",
                "Bsr", "Status A", "Balance", "Grand Horizon SARL", "Write off");

        List<Map<String, String>> rows = read(file);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0))
                .containsEntry("Bsr", "Grand Horizon SARL")
                .containsEntry("Status A", "Write off")
                .containsEntry("Balance", "184000");
        // The unlabelled total on row 2 is not a customer and must not become one.
        assertThat(rows.get(0).values()).doesNotContain("12383011.42");
    }
}
