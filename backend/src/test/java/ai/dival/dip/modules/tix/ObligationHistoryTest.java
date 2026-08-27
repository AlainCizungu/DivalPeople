package ai.dival.dip.modules.tix;

import static ai.dival.dip.modules.tix.ObligationEvent.CLOSED;
import static ai.dival.dip.modules.tix.ObligationEvent.DEFAULTED;
import static ai.dival.dip.modules.tix.ObligationEvent.DISPUTED;
import static ai.dival.dip.modules.tix.ObligationEvent.LATE_30;
import static ai.dival.dip.modules.tix.ObligationEvent.LATE_60;
import static ai.dival.dip.modules.tix.ObligationEvent.LATE_90_PLUS;
import static ai.dival.dip.modules.tix.ObligationEvent.OPENED;
import static ai.dival.dip.modules.tix.ObligationEvent.PAID_AS_AGREED;
import static ai.dival.dip.modules.tix.ObligationEvent.PERFORMING;
import static ai.dival.dip.modules.tix.ObligationEvent.RESTRUCTURED;
import static ai.dival.dip.modules.tix.ObligationEvent.SETTLED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The rule that turns accounts into a payment history.
 *
 * <p>This is the number a credit officer acts on, so the arguable decisions get a test each and
 * the test says which decision it is defending. Every one of them could reasonably have gone the
 * other way, and the point of writing them down is that changing one has to be deliberate.
 */
class ObligationHistoryTest {

    private static ObligationHistory.Account account(String institution, ObligationEvent... events) {
        return new ObligationHistory.Account(institution, List.of(events));
    }

    /** {@code count} clean, completed accounts at one institution. */
    private static List<ObligationHistory.Account> clean(String institution, int count) {
        List<ObligationHistory.Account> accounts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            accounts.add(account(institution, OPENED, PERFORMING, PAID_AS_AGREED, CLOSED));
        }
        return accounts;
    }

    @Nested
    @DisplayName("the case this whole model exists for")
    class TheMotivatingCase {

        @Test
        @DisplayName("one default in two accounts is not one default in forty-eight")
        void volumeOfGoodHistoryIsTheWholePoint() {
            List<ObligationHistory.Account> thin = new ArrayList<>(clean("orange", 1));
            thin.add(account("orange", OPENED, DEFAULTED));

            List<ObligationHistory.Account> deep = new ArrayList<>(clean("orange", 47));
            deep.add(account("orange", OPENED, DEFAULTED));

            ObligationHistory a = ObligationHistory.of(thin);
            ObligationHistory b = ObligationHistory.of(deep);

            // Before this model existed both of these were "a company with a default", and the
            // exchange could not tell them apart. That is the defect the lifecycle was built for.
            assertThat(a.performancePercent())
                    .as("two accounts is too little to express as a ratio at all")
                    .isNull();
            assertThat(a.depth()).isEqualTo(ObligationHistory.Depth.THIN);

            assertThat(b.performancePercent()).isEqualTo(98);
            assertThat(b.depth()).isEqualTo(ObligationHistory.Depth.DEEP);
        }
    }

    @Nested
    @DisplayName("counting accounts, not events")
    class AccountsNotEvents {

        @Test
        @DisplayName("a noisy operator cannot improve anybody's history")
        void monthlyReportingDoesNotAccumulate() {
            // One account, reported as performing twelve times. If events were counted, an
            // operator that sends a monthly book would make its customers look better than a
            // company that quietly repaid four loans elsewhere — and the strength of a payment
            // history would become a fact about the reporter's file cadence.
            List<ObligationEvent> monthly = new ArrayList<>();
            monthly.add(OPENED);
            for (int month = 0; month < 12; month++) {
                monthly.add(PERFORMING);
            }

            ObligationHistory loud = ObligationHistory.of(
                    List.of(new ObligationHistory.Account("orange", monthly)));

            assertThat(loud.accountsObserved())
                    .as("thirteen events, one account")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("settlement does not erase what happened")
    class SettlementIsNotErasure {

        @Test
        @DisplayName("an account that defaulted and was paid counts in both columns")
        void bothFactsSurvive() {
            List<ObligationHistory.Account> accounts = new ArrayList<>(clean("orange", 5));
            accounts.add(account("vodacom", OPENED, LATE_90_PLUS, DEFAULTED, SETTLED));

            ObligationHistory history = ObligationHistory.of(accounts);

            assertThat(history.accountsAdverse())
                    .as("paying afterwards must not be a way to rewrite the record")
                    .isEqualTo(1);
            assertThat(history.accountsSettled())
                    .as("and clearing a debt you fell behind on is worth reporting")
                    .isEqualTo(1);
            assertThat(history.performancePercent())
                    .as("five clean of six observed")
                    .isEqualTo(83);
        }
    }

    @Nested
    @DisplayName("disputed accounts")
    class Disputes {

        @Test
        @DisplayName("a disputed account is withheld from the ratio entirely, and said so")
        void aDisputeIsNotCountedEitherWay() {
            List<ObligationHistory.Account> accounts = new ArrayList<>(clean("orange", 5));
            accounts.add(account("vodacom", OPENED, DEFAULTED, DISPUTED));

            ObligationHistory history = ObligationHistory.of(accounts);

            // Not in the denominator, so an erroneous report cannot drag somebody's percentage
            // down for as long as the dispute takes to resolve. That would make exercising a
            // statutory right a punishment for exercising it.
            assertThat(history.accountsObserved()).isEqualTo(5);
            assertThat(history.accountsAdverse()).isZero();
            assertThat(history.accountsWithheld())
                    .as("published, so the reader knows the picture is incomplete")
                    .isEqualTo(1);
            assertThat(history.performancePercent()).isEqualTo(100);
        }

        @Test
        @DisplayName("an institution contributing only a disputed account is not a contributor")
        void breadthIsNotInflatedByContestedRecords() {
            List<ObligationHistory.Account> accounts = new ArrayList<>(clean("orange", 5));
            accounts.add(account("vodacom", OPENED, DEFAULTED, DISPUTED));

            assertThat(ObligationHistory.of(accounts).institutionsContributing())
                    .as("evidence from two institutions, one of which nobody is standing behind")
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("thin files")
    class ThinFiles {

        @Test
        @DisplayName("no history at all is NONE, and no percentage")
        void anEmptyFileClaimsNothing() {
            ObligationHistory history = ObligationHistory.of(List.of());

            assertThat(history.depth()).isEqualTo(ObligationHistory.Depth.NONE);
            assertThat(history.hasPercentage()).isFalse();
            assertThat(history.accountsObserved()).isZero();
        }

        @Test
        @DisplayName("a perfect record over four accounts still publishes no percentage")
        void perfectAndThinIsStillThin() {
            // The protection for the borrower this platform exists to help. "100% over four" reads
            // as a considered assessment and is one event away from 75%. Saying nothing is the
            // honest answer, and the depth is what the screen shows instead.
            ObligationHistory history = ObligationHistory.of(clean("orange", 4));

            assertThat(history.hasPercentage()).isFalse();
            assertThat(history.depth()).isEqualTo(ObligationHistory.Depth.THIN);
            assertThat(history.accountsObserved()).isEqualTo(4);
        }

        @Test
        @DisplayName("the boundary is exactly the published constant")
        void fiveAccountsIsWhereARatioBegins() {
            assertThat(ObligationHistory.MINIMUM_ACCOUNTS_FOR_A_RATIO).isEqualTo(5);
            assertThat(ObligationHistory.of(clean("orange", 4)).hasPercentage()).isFalse();
            assertThat(ObligationHistory.of(clean("orange", 5)).hasPercentage()).isTrue();
        }

        @Test
        @DisplayName("depth bands: moderate below twenty, deep at twenty")
        void depthBandsAreExact() {
            assertThat(ObligationHistory.of(clean("orange", 19)).depth())
                    .isEqualTo(ObligationHistory.Depth.MODERATE);
            assertThat(ObligationHistory.of(clean("orange", 20)).depth())
                    .isEqualTo(ObligationHistory.Depth.DEEP);
        }
    }

    @Nested
    @DisplayName("what counts as adverse")
    class Adversity {

        @Test
        @DisplayName("a restructuring counts against, and that is a decision")
        void restructuringIsNotNeutral() {
            List<ObligationHistory.Account> accounts = new ArrayList<>(clean("orange", 5));
            accounts.add(account("rawbank", OPENED, LATE_60, RESTRUCTURED, PERFORMING));

            // A restructuring is a lender and a borrower agreeing that the obligation as written
            // could not be paid. Treating it as neutral would let an institution launder a bad
            // book by rescheduling it, and the whole history would quietly become worthless.
            assertThat(ObligationHistory.of(accounts).accountsAdverse()).isEqualTo(1);
            assertThat(RESTRUCTURED.isAdverse()).isTrue();
        }

        @Test
        @DisplayName("one late payment marks the account, and marks it once")
        void anAccountIsAdverseAtMostOnce() {
            List<ObligationHistory.Account> accounts = new ArrayList<>(clean("orange", 5));
            accounts.add(account("orange", OPENED, LATE_30, LATE_60, LATE_90_PLUS, DEFAULTED));

            ObligationHistory history = ObligationHistory.of(accounts);

            assertThat(history.accountsAdverse())
                    .as("four adverse events on one account is one bad account")
                    .isEqualTo(1);
            assertThat(history.performancePercent()).isEqualTo(83);
        }

        @Test
        @DisplayName("opening and closing an account says nothing about conduct")
        void neutralEventsAreNeutral() {
            assertThat(OPENED.weight()).isEqualTo(ObligationEvent.Weight.NEITHER);
            assertThat(CLOSED.weight()).isEqualTo(ObligationEvent.Weight.NEITHER);
            assertThat(DISPUTED.weight()).isEqualTo(ObligationEvent.Weight.NEITHER);

            // Otherwise a company that opens many accounts looks good, or bad, for opening them.
            ObligationHistory history = ObligationHistory.of(List.of(
                    account("orange", OPENED, CLOSED),
                    account("orange", OPENED, CLOSED),
                    account("orange", OPENED, CLOSED),
                    account("orange", OPENED, CLOSED),
                    account("orange", OPENED, CLOSED)));

            assertThat(history.accountsAdverse()).isZero();
            assertThat(history.performancePercent()).isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("performing")
    class Performing {

        @Test
        @DisplayName("a closed account is not performing — it is finished")
        void completedIsNotTheSameAsRunning() {
            ObligationHistory history = ObligationHistory.of(List.of(
                    account("orange", OPENED, PERFORMING),
                    account("orange", OPENED, PAID_AS_AGREED, CLOSED)));

            assertThat(history.accountsPerforming())
                    .as("one running and clean; the other is a completed obligation")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("an account that has gone wrong is not performing, whatever came after")
        void adversityDisqualifiesPerforming() {
            ObligationHistory history = ObligationHistory.of(List.of(
                    account("orange", OPENED, LATE_30, PERFORMING)));

            assertThat(history.accountsPerforming()).isZero();
        }
    }

    @Nested
    @DisplayName("breadth")
    class Breadth {

        @Test
        @DisplayName("distinct institutions are counted, and only a count leaves")
        void breadthIsACountAndNothingElse() {
            ObligationHistory history = ObligationHistory.of(List.of(
                    account("orange", OPENED, PERFORMING),
                    account("orange", OPENED, PERFORMING),
                    account("vodacom", OPENED, PERFORMING),
                    account("rawbank", OPENED, PAID_AS_AGREED, CLOSED)));

            assertThat(history.institutionsContributing()).isEqualTo(3);

            // The result type has nowhere to put an institution's identity, which is the same
            // property NetworkService holds down: forty clean months with one telecom is a
            // narrower statement than forty across three sectors, and saying so must not require
            // naming any of them.
            assertThat(ObligationHistory.class.getRecordComponents())
                    .noneMatch(component -> component.getType() == String.class);
        }
    }
}
