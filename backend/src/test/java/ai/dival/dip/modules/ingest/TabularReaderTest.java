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
    @DisplayName("a totals row LABELLED total is preamble too, not only an unlabelled one")
    void skipsALabelledTotalsRow() {
        // The case the rule above did not cover, found by a test delivery rather than by reading.
        // "TOTAL" is a word, so allNumbers says this is not a totals line, so it was taken as the
        // header — six cells against a real header of eleven, and every row in the file was then
        // refused for not lining up with a header two rows above the one it had.
        //
        // A header cannot be narrower than the table. rowsFrom enforces exactly that on every data
        // row, so a shorter row is preamble whatever words are in it.
        List<Map<String, String>> rows = read("""
                TELECOM MOKILI - BALANCE AGEE
                Extraction du 31/08/2026

                ,,,,TOTAL,38412.00
                No compte,Client,Ville,Statut,30 jours,60 jours,90 jours,Balance,Date facture
                00001,Trans-Congo,Kinshasa,SUSPENDU,,,2356.00,2356.00,02/12/2025
                """);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("Client", "Trans-Congo");
        assertThat(rows.get(0).values()).doesNotContain("38412.00");
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
    @DisplayName("a header with a missing name is still the header")
    void doesNotStepOverABadHeader() {
        // The tempting rule — skip a row that looks worse than the one below it — would step over
        // this header and promote the first row of data, so the file would be keyed by a, b and c.
        // That the row below is reachable under "Customer" is the whole assertion.
        Map<String, String> row = read("Customer,,Balance\na,b,c\n").get(0);

        assertThat(row).containsEntry("Customer", "a");
        assertThat(row.keySet()).containsExactly("Customer", "Column B", "Balance");
    }

    // --- columns nobody labelled --------------------------------------------

    @Test
    @DisplayName("an unlabelled column with nothing in it is padding and is dropped")
    void emptyUnnamedColumnsAreDropped() {
        // What a spreadsheet leaves behind after somebody deletes a column. Keeping it would put
        // an empty field called "Column C" on every row of the registry.
        List<Map<String, String>> rows = read("""
                Customer,Balance,,Ref
                Acme,100,,CD-1
                Atlas,200,,CD-2
                """);

        assertThat(rows.get(0).keySet()).containsExactly("Customer", "Balance", "Ref");
    }

    @Test
    @DisplayName("an unlabelled column with data in it is kept, named for where it sits")
    void populatedUnnamedColumnsAreKept() {
        // The Orange export's shape: a write-off flag on a handful of rows under no heading at
        // all. Dropping it would silently discard delivered data, which is the one thing this
        // reader must not do — an operator who sent a column is entitled to see it come back.
        List<Map<String, String>> rows = read("""
                Customer,Balance,
                Acme,100,write off
                Atlas,200,
                """);

        assertThat(rows.get(0)).containsEntry("Column C", "write off");
        assertThat(rows.get(1)).containsEntry("Column C", "");
    }

    @Test
    @DisplayName("only the empty ones go, when a file has both kinds")
    void paddingGoesAndContentStays() {
        List<Map<String, String>> rows = read("""
                Customer,,Balance,
                Acme,,100,note
                """);

        assertThat(rows.get(0).keySet()).containsExactly("Customer", "Balance", "Column D");
        assertThat(rows.get(0)).containsEntry("Column D", "note");
    }

    @Test
    @DisplayName("a positional name that collides with a real heading is refused by name")
    void aCollidingPositionalNameIsRefused() {
        // Vanishingly rare and worth its own sentence anyway. Left to the duplicate check, this
        // would report that two columns share a name, when the file in front of the operator
        // contains that name exactly once.
        assertThatThrownBy(() -> read("Column B,,Balance\na,b,c\n"))
                .isInstanceOf(PolicyRefusedException.class)
                .hasMessageContaining("Column B");
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
