package ai.dival.dip.modules.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The profiler, against the shapes the real Vodacom export actually has.
 *
 * <p>No database and no Spring context, so it can be exhaustive for nothing. Every case here is
 * taken from {@code docs/TIX_SOURCE_PROFILE.md}: a column that is the same word on all 4,290 rows,
 * one that duplicates another exactly, one that is {@code #N/A} on most rows, aging buckets that
 * are empty except for a few dozen, and account references long enough that a double would ruin
 * them.
 */
class BatchProfilerTest {

    private static Map<String, String> row(String... pairs) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put(pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private static BatchProfiler.Column column(BatchProfiler.Profile profile, String name) {
        return profile.columns().stream()
                .filter(c -> c.column().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no column " + name));
    }

    private static final List<Map<String, String>> EXPORT = List.of(
            row("Bsr", "Grand Horizon", "Status A", "Write off", "Balance", "184000.50",
                    "Not Due", "", "PWC", "V0001", "Vaccounts", "1"),
            row("Bsr", "Atlas Distribution", "Status A", "Write off", "Balance", "96200.25",
                    "Not Due", "", "PWC", "#N/A", "Vaccounts", "1"),
            row("Bsr", "Kin Logistique", "Status A", "Write off", "Balance", "4310.25",
                    "Not Due", "  ", "PWC", "#N/A", "Vaccounts", "1"));

    @Test
    @DisplayName("the row count is the row count, not the number of columns")
    void countsRows() {
        assertThat(BatchProfiler.profile(EXPORT).rows()).isEqualTo(3);
    }

    @Test
    @DisplayName("column order survives, because position is how a person finds the field")
    void preservesColumnOrder() {
        // Real exports have several columns whose names are equally plausible. Sorting them
        // alphabetically would destroy the only reliable way to tell them apart.
        assertThat(BatchProfiler.profile(EXPORT).columns())
                .extracting(BatchProfiler.Column::column)
                .containsExactly("Bsr", "Status A", "Balance", "Not Due", "PWC", "Vaccounts");
    }

    @Test
    @DisplayName("whitespace is blank, not a value")
    void whitespaceCountsAsBlank() {
        // "Not Due" uses "-" and spaces for zero throughout the real file. Counting "  " as
        // present would report a fill rate of 33% for a column that holds nothing.
        BatchProfiler.Column notDue = column(BatchProfiler.profile(EXPORT), "Not Due");

        assertThat(notDue.filled()).isZero();
        assertThat(notDue.blank()).isEqualTo(3);
        assertThat(notDue.distinct()).isZero();
    }

    @Test
    @DisplayName("a column that is always the same word says so, and shows the word")
    void constantColumnsShowTheirVocabulary() {
        BatchProfiler.Column status = column(BatchProfiler.profile(EXPORT), "Status A");

        assertThat(status.distinct()).isEqualTo(1);
        assertThat(status.vocabulary()).extracting(BatchProfiler.ValueCount::value)
                .containsExactly("Write off");
        assertThat(status.vocabulary()).extracting(BatchProfiler.ValueCount::count)
                .containsExactly(3);
    }

    @Test
    @DisplayName("a vocabulary is ordered by how common each value is")
    void vocabularyIsOrderedByFrequency() {
        BatchProfiler.Column pwc = column(BatchProfiler.profile(EXPORT), "PWC");

        assertThat(pwc.vocabulary()).extracting(BatchProfiler.ValueCount::value)
                .containsExactly("#N/A", "V0001");
    }

    @Test
    @DisplayName("a column with too many distinct values shows counts and no values")
    void highCardinalityColumnsShowNoValues() {
        // The line between a vocabulary and a customer list. "Bsr" is a trading name; three of
        // them here and four thousand in the real file, and neither belongs on a summary screen.
        List<Map<String, String>> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(row("Name", "Business " + i));
        }
        BatchProfiler.Column names = column(BatchProfiler.profile(many), "Name");

        assertThat(names.distinct()).isEqualTo(20);
        assertThat(names.vocabulary()).isEmpty();
    }

    @Test
    @DisplayName("a numeric column is totalled exactly")
    void numericColumnsAreTotalled() {
        BatchProfiler.Column balance = column(BatchProfiler.profile(EXPORT), "Balance");

        assertThat(balance.numeric()).isTrue();
        // 184000.50 + 96200.25 + 4310.25. In doubles this is 284511.00000000006, and the figure
        // an operator reconciles against their own books has to be the figure.
        assertThat(balance.total()).isEqualTo("284511.00");
        assertThat(balance.minimum()).isEqualTo("4310.25");
        assertThat(balance.maximum()).isEqualTo("184000.50");
    }

    @Test
    @DisplayName("one #N/A makes a column non-numeric, and it is not totalled anyway")
    void oneBadValueMakesTheWholeColumnText() {
        // All-or-nothing on purpose. A total over the rows that happened to parse would hide the
        // one row somebody needs to look at, while looking authoritative.
        List<Map<String, String>> mixed = List.of(
                row("Balance", "100.00"),
                row("Balance", "200.00"),
                row("Balance", "#N/A"));
        BatchProfiler.Column balance = column(BatchProfiler.profile(mixed), "Balance");

        assertThat(balance.numeric()).isFalse();
        assertThat(balance.total()).isNull();
    }

    @Test
    @DisplayName("a long account reference is not turned into a double")
    void longNumbersSurvive() {
        List<Map<String, String>> refs = List.of(
                row("Ref", "123456789012345"),
                row("Ref", "123456789012346"));
        BatchProfiler.Column ref = column(BatchProfiler.profile(refs), "Ref");

        assertThat(ref.maximum()).isEqualTo("123456789012346");
        assertThat(ref.distinct()).isEqualTo(2);
    }

    @Test
    @DisplayName("distinct equal to filled is what a candidate identifier looks like")
    void identifierColumnsAreVisible() {
        BatchProfiler.Column bsr = column(BatchProfiler.profile(EXPORT), "Bsr");

        // The finding that mattered most in the real profile: BPR_0 was unique on every one of
        // 4,290 rows, which is what made it the thing to key entity resolution on.
        assertThat(bsr.filled()).isEqualTo(bsr.distinct());
    }

    @Test
    @DisplayName("text columns report the shortest and longest value they hold")
    void textLengthsAreReported() {
        BatchProfiler.Column bsr = column(BatchProfiler.profile(EXPORT), "Bsr");

        // How the real file's truncation at 35 characters was spotted: every long name was
        // exactly the same length.
        //
        // Written against the actual strings rather than a literal 13 and 18, because the first
        // version of this test asserted the wrong one of the three by counting in my head.
        assertThat(bsr.shortestLength()).isEqualTo("Grand Horizon".length());
        assertThat(bsr.longestLength()).isEqualTo("Atlas Distribution".length());
        assertThat("Grand Horizon".length())
                .as("the shortest of the three, which is the point of the assertion above")
                .isLessThan("Kin Logistique".length());
    }

    // --- rows that cannot become records ------------------------------------

    @Test
    @DisplayName("a clean batch reports nothing wrong with it")
    void noIssuesInACleanBatch() {
        BatchProfiler.Issues issues = BatchProfiler.profile(EXPORT).issues();

        assertThat(issues.any()).isFalse();
        assertThat(issues.findings()).isEmpty();
        assertThat(issues.complete()).isTrue();
    }

    @Test
    @DisplayName("a row with nothing in it is reported, and is not a customer")
    void emptyRowsAreFound() {
        List<Map<String, String>> rows = List.of(
                row("Ref", "V1", "Balance", "100"),
                row("Ref", "", "Balance", "  "));

        BatchProfiler.Issues issues = BatchProfiler.profile(rows).issues();

        assertThat(issues.emptyRows()).isEqualTo(1);
        assertThat(issues.findings()).extracting(BatchProfiler.Finding::issue)
                .containsExactly(BatchProfiler.Issue.EMPTY_ROW);
        assertThat(issues.findings()).first()
                .satisfies(finding -> assertThat(finding.rowNumber()).isEqualTo(2));
    }

    @Test
    @DisplayName("a duplicate names the row it duplicates, so somebody can compare them")
    void duplicatesNameTheOriginal() {
        List<Map<String, String>> rows = List.of(
                row("Ref", "V1", "Balance", "100"),
                row("Ref", "V2", "Balance", "200"),
                row("Ref", "V1", "Balance", "100"));

        BatchProfiler.Issues issues = BatchProfiler.profile(rows).issues();

        assertThat(issues.duplicateRows()).isEqualTo(1);
        assertThat(issues.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.issue()).isEqualTo(BatchProfiler.Issue.DUPLICATE_ROW);
            assertThat(finding.rowNumber()).isEqualTo(3);
            // Pointing at row 1 is the whole value of the finding. "Row 3 is a duplicate" sends
            // somebody hunting; "row 3 duplicates row 1" does not.
            assertThat(finding.detail()).isEqualTo("1");
        });
    }

    @Test
    @DisplayName("a gap in the only unique column is a row no mapping can resolve")
    void gapsInACandidateIdentifierAreFound() {
        // Ref is unique wherever it is filled, which is what a candidate key looks like — it is
        // how BPR_0 was found in the real export. A row without one has no identifier, and that
        // is true whichever column eventually turns out to be the amount.
        List<Map<String, String>> rows = List.of(
                row("Ref", "V1", "Name", "Alpha"),
                row("Ref", "V2", "Name", "Beta"),
                row("Ref", "", "Name", "Gamma"));

        BatchProfiler.Issues issues = BatchProfiler.profile(rows).issues();

        assertThat(issues.keyColumns()).containsExactly("Ref");
        assertThat(issues.rowsMissingIdentifier()).isEqualTo(1);
        assertThat(issues.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.issue()).isEqualTo(BatchProfiler.Issue.MISSING_IDENTIFIER);
            assertThat(finding.rowNumber()).isEqualTo(3);
            assertThat(finding.column()).isEqualTo("Ref");
        });
    }

    @Test
    @DisplayName("a column that repeats values is not treated as an identifier")
    void repeatedValuesAreNotACandidateKey() {
        // Status A is "Write off" on every row of the real file. It has no gaps and it is not a
        // key; flagging every blank in a status column would bury the finding that matters.
        List<Map<String, String>> rows = List.of(
                row("Status", "Write off", "Ref", "V1"),
                row("Status", "Write off", "Ref", "V2"),
                row("Status", "", "Ref", "V3"));

        assertThat(BatchProfiler.profile(rows).issues().keyColumns()).isEmpty();
        assertThat(BatchProfiler.profile(rows).issues().rowsMissingIdentifier()).isZero();
    }

    @Test
    @DisplayName("counts stay exact when the listing is truncated, and it says so")
    void countsAreCompleteEvenWhenTheListIsNot() {
        List<Map<String, String>> rows = new java.util.ArrayList<>();
        rows.add(row("Ref", "V0", "Name", "seed"));
        for (int i = 0; i < 400; i++) {
            rows.add(row("Ref", "", "Name", "Business " + i));
        }

        BatchProfiler.Issues issues = BatchProfiler.profile(rows).issues();

        // A truncated report that did not say it was truncated would be a wrong one.
        assertThat(issues.rowsMissingIdentifier()).isEqualTo(400);
        assertThat(issues.findings()).hasSize(200);
        assertThat(issues.complete()).isFalse();
    }

    @Test
    @DisplayName("an empty file profiles to nothing rather than throwing")
    void emptyInput() {
        BatchProfiler.Profile profile = BatchProfiler.profile(List.of());

        assertThat(profile.rows()).isZero();
        assertThat(profile.columns()).isEmpty();
    }

    @Test
    @DisplayName("a column absent from some rows is still counted against every row")
    void raggedColumnsAreCountedAgainstTheWholeBatch() {
        // TabularReader refuses ragged rows, so this cannot arrive from a file today. It can
        // arrive from a batch stored before that check existed, and a fill rate that silently
        // used a different denominator per column would be worse than useless.
        List<Map<String, String>> ragged = List.of(
                row("A", "1", "B", "x"),
                row("A", "2"));
        BatchProfiler.Column b = column(BatchProfiler.profile(ragged), "B");

        assertThat(b.filled()).isEqualTo(1);
        // The denominator is the batch, so filled + blank is the row count for every column.
        assertThat(b.blank()).isEqualTo(1);
        assertThat(BatchProfiler.profile(ragged).rows()).isEqualTo(2);
    }
}
