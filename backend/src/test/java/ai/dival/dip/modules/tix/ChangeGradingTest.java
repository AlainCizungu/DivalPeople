package ai.dival.dip.modules.tix;

import static ai.dival.dip.modules.tix.InquiryResult.Outcome.CLEAR;
import static ai.dival.dip.modules.tix.InquiryResult.Outcome.NO_MATCH;
import static ai.dival.dip.modules.tix.InquiryResult.Outcome.OUTSTANDING_DEBT;
import static ai.dival.dip.modules.tix.InquiryResult.Outcome.REVIEW_REQUIRED;
import static ai.dival.dip.modules.tix.MonitoringAlert.Severity.INFORMATIONAL;
import static ai.dival.dip.modules.tix.MonitoringAlert.Severity.MATERIAL;
import static ai.dival.dip.modules.tix.MonitoringAlert.Severity.NOTABLE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the nightly sweep decides is worth waking somebody for.
 *
 * <p><strong>This was the only new backend logic that shipped without tests</strong>, and it has
 * been running nightly on a deployed instance since. It is also load-bearing for DIP Credit
 * Intelligence: "material deterioration detected" is this function, and a bank asked to trust that
 * signal is entitled to have it be exercised.
 *
 * <p>Plain JUnit, no Spring, no Docker. The rule is arithmetic over a record — this codebase makes
 * pure logic static and tests it as what it is, so a grading rule that needed a database would be
 * a rule nobody tests at its boundaries. The boundaries are the entire point here: the difference
 * between nine points and ten is the difference between an email at seven in the morning and no
 * email at all.
 *
 * <p>The asymmetry between improvement and deterioration gets its own section, because it is the
 * property most likely to be broken by somebody making the rule "simpler" with an absolute value.
 */
class ChangeGradingTest {

    /** Every field populated and nothing moving; each test alters only what it is about. */
    private static WatchlistEntry.Change change(
            InquiryResult.Outcome previousOutcome, InquiryResult.Outcome currentOutcome,
            Integer previousInstitutions, int currentInstitutions,
            Integer previousScore, Integer currentScore) {
        return new WatchlistEntry.Change(previousOutcome, currentOutcome,
                previousInstitutions, currentInstitutions, previousScore, currentScore);
    }

    @Nested
    @DisplayName("silence")
    class Silence {

        @Test
        @DisplayName("the first look grades nothing, however alarming the subject")
        void firstLookIsNeverAnAlert() {
            // currentOutcome null is how the sweep says "there is no previous state". A subject
            // added to a watchlist while already owing four institutions must not generate a
            // material alert on the night it is added — nothing changed, somebody just started
            // watching, and an alert here would mean every bulk import of a watchlist arrives as
            // a wall of alarms.
            assertThat(ChangeGrading.grade(change(null, null, null, 4, null, 90)))
                    .isEqualTo(INFORMATIONAL);
        }

        @Test
        @DisplayName("a night in which nothing moved is not an alert")
        void anUnchangedWorldIsNotNews() {
            assertThat(ChangeGrading.grade(change(CLEAR, CLEAR, 2, 2, 40, 40)))
                    .isEqualTo(INFORMATIONAL);
        }
    }

    @Nested
    @DisplayName("becoming unpaid")
    class BecomingUnpaid {

        @Test
        @DisplayName("clear to outstanding is material, with no help from the score")
        void theOutcomeAloneIsEnough() {
            // Scores identical: this is the outcome transition on its own.
            assertThat(ChangeGrading.grade(change(CLEAR, OUTSTANDING_DEBT, 1, 1, 50, 50)))
                    .isEqualTo(MATERIAL);
        }

        @Test
        @DisplayName("no match to outstanding is material — they were not there, now they owe")
        void appearingAsUnpaidIsMaterial() {
            assertThat(ChangeGrading.grade(change(NO_MATCH, OUTSTANDING_DEBT, 0, 1, null, 60)))
                    .isEqualTo(MATERIAL);
        }

        @Test
        @DisplayName("already outstanding and still outstanding is not material again")
        void stayingUnpaidIsNotANewEvent() {
            // The rule reads "became unpaid", not "is unpaid". Otherwise every subject with a live
            // debt would generate a material alert every night until it was settled, and the queue
            // would be unreadable within a week.
            assertThat(ChangeGrading.grade(change(OUTSTANDING_DEBT, OUTSTANDING_DEBT, 2, 2, 70, 70)))
                    .isEqualTo(INFORMATIONAL);
        }

        @Test
        @DisplayName("outstanding to clear is not material — that is somebody paying")
        void settlingIsNotAnEmergency() {
            assertThat(ChangeGrading.grade(change(OUTSTANDING_DEBT, CLEAR, 2, 2, 70, 70)))
                    .isEqualTo(INFORMATIONAL);
        }
    }

    @Nested
    @DisplayName("the indicator moving")
    class ScoreMovement {

        @Test
        @DisplayName("ten points up is material and nine is not")
        void theMaterialBoundaryIsExact() {
            assertThat(ChangeGrading.grade(change(CLEAR, CLEAR, 1, 1, 40, 50)))
                    .as("ten points, the published threshold")
                    .isEqualTo(MATERIAL);
            assertThat(ChangeGrading.grade(change(CLEAR, CLEAR, 1, 1, 40, 49)))
                    .as("nine points is real, and not worth interrupting a morning for")
                    .isEqualTo(NOTABLE);
        }

        @Test
        @DisplayName("four points up is notable and three is not")
        void theNotableBoundaryIsExact() {
            assertThat(ChangeGrading.grade(change(CLEAR, CLEAR, 1, 1, 40, 44)))
                    .isEqualTo(NOTABLE);
            assertThat(ChangeGrading.grade(change(CLEAR, CLEAR, 1, 1, 40, 43)))
                    .isEqualTo(INFORMATIONAL);
        }

        @Test
        @DisplayName("a large improvement is recorded, never raised")
        void improvementIsNotDeterioration() {
            // The property most at risk from a well-meant simplification. A subject whose
            // indicator fell twenty points is a company that settled its debts; grading that
            // MATERIAL because "twenty is more than ten" would wake a collections desk to tell it
            // somebody paid, and a queue that cries about good news stops being read.
            //
            // It is still INFORMATIONAL rather than dropped: a facility declined the week before
            // a subject's obligations were settled is a decision somebody later has to explain.
            assertThat(ChangeGrading.grade(change(OUTSTANDING_DEBT, CLEAR, 3, 1, 80, 60)))
                    .isEqualTo(INFORMATIONAL);
        }

        @Test
        @DisplayName("a withheld indicator is not a fall to zero")
        void aMissingScoreMovesNothing() {
            // The exchange declines to score a subject whose identity it will not confirm.
            // Treating that as a drop would say a company improved when what happened is that DIP
            // stopped being sure who they were.
            assertThat(ChangeGrading.grade(change(REVIEW_REQUIRED, REVIEW_REQUIRED, 1, 1, 90, null)))
                    .isEqualTo(INFORMATIONAL);
            assertThat(ChangeGrading.grade(change(REVIEW_REQUIRED, REVIEW_REQUIRED, 1, 1, null, 90)))
                    .isEqualTo(INFORMATIONAL);
        }
    }

    @Nested
    @DisplayName("institutions")
    class Institutions {

        @Test
        @DisplayName("another institution beginning to report is notable")
        void oneMoreInstitutionIsNotable() {
            assertThat(ChangeGrading.grade(change(OUTSTANDING_DEBT, OUTSTANDING_DEBT, 1, 2, 60, 60)))
                    .isEqualTo(NOTABLE);
        }

        @Test
        @DisplayName("an institution stopping is not notable on that basis")
        void fewerInstitutionsIsNotAWarning() {
            // Something did change, so this is not silence — but a contributor whose record aged
            // out or was settled is not a warning about the subject.
            assertThat(ChangeGrading.grade(change(OUTSTANDING_DEBT, OUTSTANDING_DEBT, 3, 2, 60, 60)))
                    .isEqualTo(INFORMATIONAL);
        }

        @Test
        @DisplayName("an unknown previous count cannot be compared, and is not guessed at zero")
        void anAbsentPreviousCountIsNotZero() {
            // grade() requires previousInstitutions to be non-null before comparing, and this is
            // why: treating null as zero would make the first sweep that learns a count read as
            // "institutions increased from none", which is a fact about our records rather than
            // about the subject.
            assertThat(ChangeGrading.grade(change(CLEAR, CLEAR, null, 3, 40, 40)))
                    .isEqualTo(INFORMATIONAL);
        }
    }

    @Nested
    @DisplayName("more than one thing at once")
    class Precedence {

        @Test
        @DisplayName("the worst applicable grade wins, whatever order it is checked in")
        void materialOutranksNotable() {
            // Both conditions hold: became unpaid, and gained an institution. Ordered worst-first
            // in the implementation, and this is the test that says so — evaluating the smaller
            // condition first would grade a subject by whichever check happened to run earliest.
            assertThat(ChangeGrading.grade(change(CLEAR, OUTSTANDING_DEBT, 1, 3, 40, 45)))
                    .isEqualTo(MATERIAL);
        }

        @Test
        @DisplayName("a notable score move outranks a mere institution change")
        void bothNotablePathsAgree() {
            assertThat(ChangeGrading.grade(change(CLEAR, CLEAR, 3, 2, 40, 45)))
                    .as("the score rose five and an institution left; the rise is what matters")
                    .isEqualTo(NOTABLE);
        }
    }

    @Nested
    @DisplayName("the published thresholds")
    class PublishedRule {

        @Test
        @DisplayName("the numbers on screen are the numbers in the rule")
        void thresholdsAreWhatTheDocumentationClaims() {
            // The class javadoc publishes these so that a collections manager who thinks nine
            // points deserves attention can argue with the threshold instead of concluding the
            // alerting is erratic. If somebody retunes them, the documentation has to move too.
            assertThat(ChangeGrading.MATERIAL_SCORE_MOVE).isEqualTo(10);
            assertThat(ChangeGrading.NOTABLE_SCORE_MOVE).isEqualTo(4);
            assertThat(ChangeGrading.NOTABLE_SCORE_MOVE)
                    .as("notable must be the smaller of the two, or the grades invert")
                    .isLessThan(ChangeGrading.MATERIAL_SCORE_MOVE);
        }
    }
}
