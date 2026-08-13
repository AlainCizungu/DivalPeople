package ai.dival.dip.modules.resolution;

import java.util.List;

/**
 * What the comparison concluded, and everything it concluded it from.
 *
 * @param confidence 0.0 to 1.0. Never a decision — see {@link #worthReviewing()}
 * @param signals    every signal, in a fixed order, including the ones that could not be
 *                   evaluated. A list that shortened when data was missing would let a reviewer
 *                   mistake an unasked question for an answered one
 */
public record MatchAssessment(double confidence, List<MatchSignal> signals) {

    /**
     * Below this a pair is not worth a person's attention.
     *
     * <p>Two businesses whose only agreement is that they are both businesses in the same country
     * would otherwise sit in the queue for ever, and a review queue nobody can empty is a review
     * queue nobody opens.
     */
    public static final double REVIEW_THRESHOLD = 0.35;

    /**
     * Above this the platform is confident — and still does not act.
     *
     * <p>There is deliberately no automatic merge at any confidence. Merging two subjects moves
     * one company's debts onto another, across institutions that cannot see each other, and the
     * cost of being wrong is somebody refused credit for a default that is not theirs. A number
     * this side of the line means a reviewer will probably agree, not that nobody needs to look.
     */
    public static final double STRONG_CONFIDENCE = 0.85;

    public MatchAssessment {
        signals = List.copyOf(signals);
    }

    public boolean worthReviewing() {
        return confidence >= REVIEW_THRESHOLD;
    }
}
