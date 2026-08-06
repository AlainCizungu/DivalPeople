package ai.dival.dip.modules.tix;

import java.util.List;

/**
 * The response an operator receives from a verification inquiry.
 *
 * <p>Note what is absent: no balance, no counterparty operator, no account detail. The exchange
 * answers "is there a confirmed problem, and how confident are we that this is the same person",
 * and nothing more.
 *
 * @param outcome      overall finding
 * @param confidence   0.0–1.0 confidence that the submitted identifiers resolve to one subject
 * @param subjectId    resolved subject, or {@code null} when nothing matched
 * @param statuses     distinct statuses held against the subject by any participating operator
 * @param fraudSignals advisory indicators requiring human review, never findings of misconduct
 */
public record InquiryResult(
        Outcome outcome,
        double confidence,
        java.util.UUID subjectId,
        List<DebtStatus> statuses,
        List<String> fraudSignals) {

    public enum Outcome {
        /** No subject matched the submitted identifiers. */
        NO_MATCH,
        /** Matched, and no adverse record is held. */
        CLEAR,
        /** Matched, and at least one operator holds a confirmed unpaid obligation. */
        OUTSTANDING_DEBT,
        /** Match confidence is below the automatic threshold; a human must review. */
        REVIEW_REQUIRED
    }

    public static InquiryResult noMatch() {
        return new InquiryResult(Outcome.NO_MATCH, 0.0, null, List.of(), List.of());
    }

    public static InquiryResult reviewRequired(java.util.UUID subjectId, double confidence) {
        return new InquiryResult(Outcome.REVIEW_REQUIRED, confidence, subjectId, List.of(), List.of());
    }
}
