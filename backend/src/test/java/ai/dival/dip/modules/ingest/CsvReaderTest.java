package ai.dival.dip.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.dival.dip.common.error.PolicyRefusedException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The parser, against what real exported files actually contain.
 *
 * <p>No database and no Spring context, so it can be exhaustive cheaply. Every case here is
 * something a telecom billing system genuinely emits, not a hypothetical: Excel's byte order
 * mark, Windows line endings, company names with commas in them, quotes inside quoted fields,
 * and a trailing blank line.
 */
class CsvReaderTest {

    private static List<Map<String, String>> read(String csv) {
        return CsvReader.read(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a plain file becomes rows keyed by header")
    void plainFile() {
        List<Map<String, String>> rows = read("""
                Customer,Balance,Currency
                Grand Horizon SARL,184000,USD
                Atlas Distribution,96200,USD
                """);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("Customer", "Grand Horizon SARL")
                .containsEntry("Balance", "184000")
                .containsEntry("Currency", "USD");
    }

    @Test
    @DisplayName("column order is preserved, because it is how a person finds the field they mean")
    void columnOrderSurvives() {
        // Telecom exports routinely have several columns whose names are equally plausible
        // ("Amount", "Total", "Balance"). Position is often the only way to tell them apart when
        // writing a mapping.
        assertThat(read("Zebra,Alpha,Middle\n1,2,3\n").get(0).keySet())
                .containsExactly("Zebra", "Alpha", "Middle");
    }

    @Test
    @DisplayName("a company name containing a comma stays one value")
    void quotedCommas() {
        List<Map<String, String>> rows =
                read("Customer,Balance\n\"Société Générale d'Alimentation, SARL\",5000\n");

        assertThat(rows.get(0)).containsEntry("Customer", "Société Générale d'Alimentation, SARL");
        assertThat(rows.get(0)).containsEntry("Balance", "5000");
    }

    @Test
    @DisplayName("a doubled quote inside a quoted field is one literal quote")
    void escapedQuotes() {
        assertThat(read("Name\n\"The \"\"Big\"\" Company\"\n").get(0))
                .containsEntry("Name", "The \"Big\" Company");
    }

    @Test
    @DisplayName("a newline inside a quoted field does not start a new row")
    void newlineInsideQuotes() {
        // Addresses in exported files do this constantly.
        List<Map<String, String>> rows = read("Name,Address\nAcme,\"12 Avenue\nKinshasa\"\n");

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("Address", "12 Avenue\nKinshasa");
    }

    @Test
    @DisplayName("Windows line endings do not leave a carriage return on the last cell")
    void windowsLineEndings() {
        List<Map<String, String>> rows = read("Customer,Ref\r\nAcme,CD-1234\r\n");

        // A trailing \r would make "CD-1234\r" fail to match "CD-1234" by one invisible byte —
        // the kind of defect that shows up as "identity resolution is unreliable".
        assertThat(rows.get(0)).containsEntry("Ref", "CD-1234");
    }

    @Test
    @DisplayName("Excel's byte order mark does not become part of the first column name")
    void byteOrderMark() {
        List<Map<String, String>> rows = read("﻿Customer,Balance\nAcme,100\n");

        // Without this the first column is named "﻿Customer", every mapping referring to
        // "Customer" silently finds nothing, and the file looks like it imported fine.
        assertThat(rows.get(0)).containsKey("Customer");
    }

    @Test
    @DisplayName("a trailing blank line is not a customer")
    void trailingBlankLine() {
        assertThat(read("Customer,Balance\nAcme,100\n\n")).hasSize(1);
    }

    @Test
    @DisplayName("a file with no trailing newline keeps its last row")
    void noTrailingNewline() {
        assertThat(read("Customer,Balance\nAcme,100")).hasSize(1);
    }

    @Test
    @DisplayName("an empty cell is kept as empty, not dropped")
    void emptyCellsSurvive() {
        // Missing values are data. Dropping the key would make "no balance recorded" and "column
        // absent from the file" indistinguishable downstream.
        assertThat(read("Customer,Balance,Ref\nAcme,,CD-1\n").get(0))
                .containsEntry("Balance", "");
    }

    @Test
    @DisplayName("a row with the wrong number of cells is refused, not padded")
    void raggedRowIsRefused() {
        assertThatThrownBy(() -> read("Customer,Balance\nAcme,100,extra\n"))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("Row 2");
    }

    @Test
    @DisplayName("two columns with the same name are refused")
    void duplicateHeadersAreRefused() {
        // One would overwrite the other when the row became a map, silently losing a column.
        assertThatThrownBy(() -> read("Balance,Balance\n1,2\n"))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("share a name");
    }

    @Test
    @DisplayName("a column with no heading is refused")
    void unnamedColumnIsRefused() {
        assertThatThrownBy(() -> read("Customer,,Balance\na,b,c\n"))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("Column 2");
    }

    @Test
    @DisplayName("a header with no rows beneath it is refused")
    void headerOnlyIsRefused() {
        assertThatThrownBy(() -> read("Customer,Balance\n"))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("no rows");
    }

    @Test
    @DisplayName("an empty file is refused")
    void emptyFileIsRefused() {
        assertThatThrownBy(() -> read(""))
                .isInstanceOf(PolicyRefusedException.class);
    }

    @Test
    @DisplayName("headers are trimmed, values are not")
    void trimmingIsAsymmetric() {
        // A header with a stray space is a formatting artefact; a value with one might be
        // significant, and deciding that for the operator is not this class's job. Whatever the
        // file said is what gets stored.
        Map<String, String> row = read("Customer , Balance\n Acme ,100\n").get(0);

        assertThat(row).containsKey("Customer").containsKey("Balance");
        assertThat(row).containsEntry("Customer", " Acme ");
    }
}
