package ai.dival.dip.modules.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ordering the database checks, checked without the database.
 *
 * <p>{@code tix_match_candidate} carries {@code CHECK (subject_low_id < subject_high_id)} so a
 * pair is one row however the scan found it. Java and PostgreSQL do not agree on what that means:
 * {@code UUID.compareTo} reads the most significant bits as a <strong>signed</strong> long, and
 * PostgreSQL compares {@code uuid} as unsigned bytes. They disagree on almost exactly half of all
 * random pairs.
 *
 * <p>Half is the worst rate for diagnosing. A tenth reads as a flake and gets retried; everything
 * failing is found in the first run. Half looked like a feature that worked in some tests and not
 * in others, and cost a round trip spent comparing the tests to each other.
 *
 * <p>No Docker here on purpose. The rule is arithmetic, and the eight tests below fix it in the
 * one place where a future change to the constructor cannot quietly undo it.
 */
class PairOrderingTest {

    @Test
    @DisplayName("the top bit is a magnitude, not a sign")
    void theSignedComparisonIsTheBug() {
        // 0x8000... is the smallest id with the high bit set. Postgres sorts it after 0x7fff...;
        // Java sorts it before, because as a signed long it is negative.
        UUID highBitSet = new UUID(0x8000000000000000L, 0L);
        UUID highBitClear = new UUID(0x7fffffffffffffffL, -1L);

        assertThat(highBitSet.compareTo(highBitClear))
                .as("what Java thinks, and the reason this class exists")
                .isNegative();
        assertThat(MatchCandidate.compareAsDatabase(highBitSet, highBitClear))
                .as("what the CHECK constraint thinks")
                .isPositive();
    }

    @Test
    @DisplayName("the low bits break a tie, unsigned as well")
    void theLowBitsAreAlsoUnsigned() {
        UUID lowBitSet = new UUID(1L, 0x8000000000000000L);
        UUID lowBitClear = new UUID(1L, 0x7fffffffffffffffL);

        assertThat(MatchCandidate.compareAsDatabase(lowBitSet, lowBitClear)).isPositive();
        assertThat(MatchCandidate.compareAsDatabase(lowBitClear, lowBitSet)).isNegative();
    }

    @Test
    @DisplayName("a pair arrives in the order the constraint demands, given either way round")
    void theConstructorOrdersEitherWayRound() {
        UUID a = new UUID(0x8000000000000000L, 0L);
        UUID b = new UUID(0x7fffffffffffffffL, -1L);

        MatchCandidate oneWay = candidate(a, b);
        MatchCandidate theOther = candidate(b, a);

        assertThat(oneWay.getSubjectLowId()).isEqualTo(theOther.getSubjectLowId());
        assertThat(oneWay.getSubjectHighId()).isEqualTo(theOther.getSubjectHighId());
        assertThat(MatchCandidate.compareAsDatabase(
                oneWay.getSubjectLowId(), oneWay.getSubjectHighId()))
                .as("subject_low_id < subject_high_id, as the database reads it")
                .isNegative();
    }

    @Test
    @DisplayName("and does so for every random pair, which the old comparison managed half the time")
    void theOrderingHoldsForRandomIds() {
        for (int i = 0; i < 5_000; i++) {
            MatchCandidate pair = candidate(UUID.randomUUID(), UUID.randomUUID());
            assertThat(MatchCandidate.compareAsDatabase(
                    pair.getSubjectLowId(), pair.getSubjectHighId()))
                    .isNegative();
        }
    }

    @Test
    @DisplayName("comparing is consistent: reversing the arguments reverses the answer")
    void theComparisonIsAntisymmetric() {
        for (int i = 0; i < 1_000; i++) {
            UUID left = UUID.randomUUID();
            UUID right = UUID.randomUUID();
            assertThat(Integer.signum(MatchCandidate.compareAsDatabase(left, right)))
                    .isEqualTo(-Integer.signum(MatchCandidate.compareAsDatabase(right, left)));
        }
    }

    @Test
    @DisplayName("an id equals itself")
    void identityIsZero() {
        UUID one = UUID.randomUUID();
        assertThat(MatchCandidate.compareAsDatabase(one, one)).isZero();
    }

    @Test
    @DisplayName("a subject is not a candidate match for itself")
    void aSubjectCannotPairWithItself() {
        UUID one = UUID.randomUUID();
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> candidate(one, one))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the confidence is stored at the scale the column holds")
    void confidenceIsRoundedToTheColumn() {
        // NUMERIC(4,3). Handing Postgres more precision than the column has would round it there
        // instead, which is the same answer arrived at somewhere nobody can see.
        MatchCandidate pair = new MatchCandidate(UUID.randomUUID(), UUID.randomUUID(),
                0.5024999, "[]", "DIP-MR-1", Instant.now());

        assertThat(pair.getConfidence().scale()).isEqualTo(3);
        assertThat(pair.getConfidence().doubleValue()).isEqualTo(0.502);
    }

    private static MatchCandidate candidate(UUID first, UUID second) {
        return new MatchCandidate(first, second, 0.5, "[]", "DIP-MR-1", Instant.now());
    }
}
