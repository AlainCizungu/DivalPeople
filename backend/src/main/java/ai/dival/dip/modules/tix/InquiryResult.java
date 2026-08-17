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
 * <p><strong>{@code contributors} is the exception, and it is empty in every deployment that has
 * not deliberately turned it on.</strong> It carries the paragraph above in reverse: the named
 * operators and, if a second switch is also on, what each is owed. It exists because the product
 * owner asked for the named view; it ships empty because the sentence four paragraphs up is what
 * every participant was shown, and a promise is not renegotiated by a code change. See
 * {@link DisclosureProperties}. A reader of this response cannot tell a deployment that withheld
 * the list from one where nobody else reports the subject — {@code institutionCount} answers that,
 * and answers it the same way either way.
 *
 * @param outcome          overall finding, which is the whole answer
 * @param subjectId        resolved subject; {@code null} unless the match was confirmed
 * @param statuses         distinct statuses held against the subject by any participating operator
 * @param institutionCount how many operators hold a record that counts; never which
 * @param fraudSignals     advisory indicators requiring human review, never findings of misconduct
 * @param indicator        the DIP Risk Indicator and every factor behind it; null without a
 *                         confirmed match
 * @param contributors     the operators behind {@code institutionCount}, named. <strong>Empty
 *                         unless the deployment switched naming on</strong>, which is the shipped
 *                         state and the state every participant was told about
 */
public record InquiryResult(
        Outcome outcome,
        UUID subjectId,
        List<DebtStatus> statuses,
        int institutionCount,
        List<String> fraudSignals,
        RiskIndicator indicator,
        List<Contributor> contributors) {

    /**
     * The shape every caller wrote before naming existed, and the shape that stays correct.
     *
     * <p>Kept so that adding a disclosure did not require editing the places that make a verdict
     * carrying no disclosure at all. A new call site that wants contributors has to say so.
     */
    public InquiryResult(Outcome outcome, UUID subjectId, List<DebtStatus> statuses,
                         int institutionCount, List<String> fraudSignals, RiskIndicator indicator) {
        this(outcome, subjectId, statuses, institutionCount, fraudSignals, indicator, List.of());
    }

    /**
     * One named operator's position in another operator's book.
     *
     * <p><strong>Never constructed unless {@link DisclosureProperties#canName()}.</strong>
     *
     * <p>{@code owed} is null when naming is on and pricing is off, which is the coherent middle
     * setting: a lender learns who else is in the room without learning the size of anybody's
     * position. Null rather than zero, because zero is a number and would be read as one.
     *
     * @param institution the operator's name as the tenant registry holds it
     * @param owed        what that operator is owed, or null when amounts stay withheld
     * @param currency    the currency of {@code owed}, or null with it
     * @param records     how many of that operator's records count toward the answer. Disclosed
     *                    with the name because it is the shape of the exposure — one large invoice
     *                    and forty small ones are different facts about the same total
     */
    public record Contributor(String institution, String owed, String currency, int records) {
    }

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
