package ai.dival.dip.modules.tix;

import ai.dival.dip.modules.risk.RiskIndicator;
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
 * <p>{@code institutionCount} is the one number here that is deliberately disclosed, and the line
 * between it and everything else is the product. An operator learns <em>how many</em> participants
 * report an obligation against this subject — itself included — and never which, never how much,
 * never since when. That is enough to price risk and not enough to reconstruct a rival's book,
 * which is the trade that makes joining the exchange rational for a competitor.
 *
 * <p>It exists because the screen was showing {@code statuses.size()} under a label promising a
 * count of institutions. Two operators both reporting an outstanding debt collapse to one status,
 * so the most valuable answer the exchange can give — more than one of us is owed money by this
 * company — was being reported as one. The number was wrong in the direction that understates
 * risk, which is the worse direction for a credit decision.
 *
 * <p>{@code indicator} is the one thing here that is computed rather than reported, and it obeys
 * the same line. Every input it rests on is a fact this response already carries, or a band too
 * coarse to be differenced back into one — because a number that moves is a number a competitor
 * can watch over time, and an indicator built from anything private would leak that private thing
 * one reading at a time. It is null for every outcome except a confirmed match, for the same
 * reason the subject id is: an answer the exchange is not confident about carries a verdict and
 * nothing else.
 *
 * @param outcome          overall finding, which is the whole answer
 * @param subjectId        resolved subject; {@code null} unless the match was confirmed
 * @param statuses         distinct statuses held against the subject by any participating operator
 * @param institutionCount how many operators hold a record that counts; never which
 * @param fraudSignals     advisory indicators requiring human review, never findings of misconduct
 * @param indicator        the DIP Risk Indicator and every factor behind it; null without a
 *                         confirmed match
 */
public record InquiryResult(
        Outcome outcome,
        UUID subjectId,
        List<DebtStatus> statuses,
        int institutionCount,
        List<String> fraudSignals,
        RiskIndicator indicator) {

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
        return new InquiryResult(Outcome.NO_MATCH, null, List.of(), 0, List.of(), null);
    }

    /**
     * Deliberately carries nothing beyond the verdict. A caller told to review has been told the
     * exchange is not confident, and that is the entire content of the answer.
     */
    public static InquiryResult reviewRequired() {
        return new InquiryResult(Outcome.REVIEW_REQUIRED, null, List.of(), 0, List.of(), null);
    }
}
