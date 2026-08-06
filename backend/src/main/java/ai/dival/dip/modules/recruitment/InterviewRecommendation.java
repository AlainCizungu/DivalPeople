package ai.dival.dip.modules.recruitment;

/**
 * An interviewer's view.
 *
 * <p>A recommendation, never a decision. Hiring outcomes are decided by people reviewing the
 * whole picture — the same principle that keeps AI advisory throughout the platform applies to a
 * single interviewer's opinion.
 */
public enum InterviewRecommendation {
    STRONG_YES,
    YES,
    NO,
    STRONG_NO;

    public boolean isPositive() {
        return this == STRONG_YES || this == YES;
    }
}
