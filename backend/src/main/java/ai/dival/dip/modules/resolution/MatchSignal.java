package ai.dival.dip.modules.resolution;

/**
 * One line of the matching-signals list.
 *
 * <p>Three verdicts and not two, which is the whole design of this record. A signal that agrees, a
 * signal that disagrees, and a signal <strong>nobody could evaluate</strong> are three different
 * statements, and collapsing the third into "did not agree" is how a comparison quietly reports
 * missing data as evidence.
 *
 * @param code       which signal
 * @param verdict    what it said, or that it could not speak
 * @param weight     what it contributed to the confidence, as a proportion. Negative for a
 *                   conflict, zero for a signal that carries no evidence either way, and always
 *                   zero when the verdict is {@link Verdict#UNAVAILABLE}
 */
public record MatchSignal(MatchSignalCode code, Verdict verdict, double weight) {

    public enum Verdict {
        /** The two records agree on this. */
        AGREES,
        /** The two records disagree on this. */
        CONFLICTS,
        /** Present on both and evidence for neither — see DIFFERENT_ACCOUNT_REFERENCES. */
        NEUTRAL,
        /**
         * Neither record carries the attribute, so nothing was compared.
         *
         * <p>Distinct from CONFLICTS in the way that matters most: a reviewer who reads "city:
         * did not match" concludes the two people live in different places. A reviewer who reads
         * "city: not held" concludes the platform needs better deliveries, which is true and
         * actionable.
         */
        UNAVAILABLE
    }

    public MatchSignal {
        if (verdict == Verdict.UNAVAILABLE && weight != 0.0) {
            throw new IllegalArgumentException(
                    "A signal nobody could evaluate cannot have moved the confidence");
        }
    }

    static MatchSignal agrees(MatchSignalCode code, double weight) {
        return new MatchSignal(code, Verdict.AGREES, weight);
    }

    static MatchSignal conflicts(MatchSignalCode code, double weight) {
        return new MatchSignal(code, Verdict.CONFLICTS, weight);
    }

    static MatchSignal neutral(MatchSignalCode code) {
        return new MatchSignal(code, Verdict.NEUTRAL, 0.0);
    }

    static MatchSignal unavailable(MatchSignalCode code) {
        return new MatchSignal(code, Verdict.UNAVAILABLE, 0.0);
    }
}
