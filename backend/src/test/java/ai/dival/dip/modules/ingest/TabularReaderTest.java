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
 * Finding the header, and — more importantly — not finding one that is not there.
 *
 * <p>Header detection is the kind of feature that is judged by what it refuses to do. Every case
 * below where the answer is "row 1" exists because a cleverer rule would have picked something
 * else and been wrong about a real file, silently, with the wrong row stored as data.
 */
class TabularReaderTest {

    private static List<Map<String, String>> read(String csv) {
        return TabularReader.read(csv.getBytes(StandardCharsets.UTF_8));
    }

    // --- the preamble it exists for -----------------------------------------

    @Test
    @DisplayName("blank rows and an unlabelled total above the header are skipped")
    void skipsTheExportPreamble() {
        List<Map<String, String>> rows = read("""
                ,,
                ,,12383011.42
                ,,
                Bsr,Status A,Balance
                Grand Horizon SARL,Write off,184000
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("Bsr", "Grand Horizon SARL");
        // The total is not a customer and must not become one.
        assertThat(rows.get(0).values()).doesNotContain("12383011.42");
    }

    @Test
    @DisplayName("a totals row wider than two columns is still preamble, not the header")
    void skipsAWideTotalsRow() {
        // This is the row the real export actually has, and the one the first version of this
        // rule got wrong: a grand total in the first column, a subtotal under each aging bucket,
        // and nothing under the columns that are not amounts. Eleven values in the file, which
        // "fewer than two values" read as a header — and then the file was refused for the gaps
        // between them, told it had no column headings when its headings were two rows lower.
        List<Map<String, String>> rows = read("""
                ,,,,
                12383011.42,,,,20286.87
                ,,,,
                Bsr,Status A,BPR_0,Balance,Not Due
                Grand Horizon SARL,Write off,V0172109,184000,0
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("BPR_0", "V0172109");
        assertThat(rows.get(0).values()).doesNotContain("12383011.42");
    }

    @Test
    @DisplayName("a header whose names are numbers is still the header")
    void numericHeadingsAreNotPreamble() {
        // Both halves of the rule are load-bearing. Skipping every all-numeric row would step
        // over this one and store the first customer as the column names.
        List<Map<String, String>> rows = read("""
                Customer,2023,2024
                Acme,100,200
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("2024", "200");
    }

    @Test
    @DisplayName("a leading blank line does not become a nameless header")
    void skipsLeadingBlankLines() {
        assertThat(read("\n\nCustomer,Balance\nAcme,100\n")).hasSize(1);
    }

    // --- what it must not do ------------------------------------------------

    @Test
    @DisplayName("a header with a missing name is still the header, and still refused by name")
    void doesNotStepOverABadHeader() {
        // The tempting rule — skip a row that looks worse than the one below it — would step over
        // this header, promote the first row of data, and report "no rows" for a file whose real
        // problem is an unnamed column. The operator would be told the wrong thing about their
        // own file.
        assertThatThrownBy(() -> read("Customer,,Balance\na,b,c\n"))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("Column 2");
    }

    @Test
    @DisplayName("a single-column file keeps its one-cell header")
    void singleColumnFilesAreNotPreamble() {
        // One filled cell is the signature of a preamble row — unless the file is never wider
        // than one, in which case it is simply the file.
        assertThat(read("Name\nAcme\n").get(0)).containsEntry("Name", "Acme");
    }

    @Test
    @DisplayName("a data row that happens to be sparse is not mistaken for preamble")
    void sparseDataRowsSurvive() {
        // Detection stops at the header and never looks again. A row further down with one value
        // in it is a customer with missing fields, and dropping it would lose a debt.
        List<Map<String, String>> rows = read("""
                Customer,Balance,Ref
                Acme,100,CD-1
                Sparse,,
                """);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsEntry("Customer", "Sparse").containsEntry("Balance", "");
    }

    @Test
    @DisplayName("a file whose first row is the header is unaffected")
    void ordinaryFilesAreUntouched() {
        assertThat(read("Customer,Balance\nAcme,100\n").get(0)).containsEntry("Customer", "Acme");
    }

    // --- when there is no header at all -------------------------------------

    @Test
    @DisplayName("a file that is all preamble is refused, not searched forever")
    void refusesWhenNoHeaderIsFound() {
        StringBuilder covering = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            covering.append("note,,\n");
        }
        covering.append("Customer,Balance,Ref\nAcme,100,CD-1\n");

        // The header is on row 26. Hunting that far would eventually find something
        // header-shaped inside a covering note, so the window is bounded and the file is refused
        // with the reason.
        assertThatThrownBy(() -> read(covering.toString()))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("No header row was found");
    }

    // --- row numbers still point at the file --------------------------------

    @Test
    @DisplayName("a refusal names the line in the file, counting the preamble")
    void rowNumbersCountFromTheTopOfTheFile() {
        // The operator opens the file and goes to that line. A number relative to the header
        // would send them two rows short.
        assertThatThrownBy(() -> read("""
                ,,
                ,,999
                Customer,Balance,Ref
                Acme,100,CD-1,extra
                """))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("Row 4");
    }

    // --- dispatch ------------------------------------------------------------

    @Test
    @DisplayName("plain text is read as CSV")
    void csvStillWorks() {
        assertThat(read("Customer,Balance\nAcme,100\n")).hasSize(1);
    }

    @Test
    @DisplayName("an empty file is refused")
    void emptyFileIsRefused() {
        assertThatThrownBy(() -> read(""))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("empty");
    }
}
