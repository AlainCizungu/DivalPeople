package ai.dival.dip.modules.tix;

import java.util.List;
import java.util.UUID;

/**
 * The response an operator receives from a verification inquiry.
 *
 * <p>Note what is absent: no balance, no counterparty operator, no account detail — and since the
 * August 2026 security review, <strong>no confidence score and no subject id below the automatic
 * threshold</strong>. The exchange answers "is there a confirmed problem", and nothing more.
 *
 * <p>The score used to be here as a number between 0 and 1. It is a fine-grained function of how
 * the submitted name compares to the stored one, so a caller could submit one guessed name at a
 * time and read the answer off the score — recovering a competitor's subject's legal name token by
 * token. The exchange makes the decision; it does not hand over the evidence it decided on.
 *
 * <p>{@code subjectId} is withheld when the match needs review, for a related reason: it is a
 * stable handle that correlates the same person across every future inquiry, and handing one out
 * at the exact moment the system says it is <em>not</em> confident is the wrong trade. An operator
 * who needs an answer should submit a stronger identifier.
 *
 * @param outcome      overall finding, which is the whole answer
 * @param subjectId    resolved subject; {@code null} unless the match was confirmed
 * @param statuses     distinct statuses held against the subject by any participating operator
 * @param fraudSignals advisory indicators requiring human review, never findings of misconduct
 */
public record InquiryResult(
        Outcome outcome,
        UUID subjectId,
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
        return new InquiryResult(Outcome.NO_MATCH, null, List.of(), List.of());
    }

    /**
     * Deliberately carries nothing beyond the verdict. A caller told to review has been told the
     * exchange is not confident, and that is the entire content of the answer.
     */
    public static InquiryResult reviewRequired() {
        return new InquiryResult(Outcome.REVIEW_REQUIRED, null, List.of(), List.of());
    }
}
