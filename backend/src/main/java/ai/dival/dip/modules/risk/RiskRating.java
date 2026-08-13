package ai.dival.dip.modules.risk;

/**
 * How badly one factor reads.
 *
 * <p>Four values and no number, because the number belongs to the indicator as a whole. A factor
 * reported as "17 of 30" invites the reader to do arithmetic the model has already done, and to
 * compare two factors whose scales were never meant to be compared.
 *
 * <p>{@link #NOT_ASSESSED} is the value that earns this enum its keep. Two of the seven factors
 * are deliberately left out of every assessment — one because the data is not trustworthy and one
 * because disclosing it would undo a protection — and both of those are different from "we looked
 * and found nothing". A factor silently omitted reads as a factor that came back clean.
 *
 * <p>The direction is risk, not quality. {@link #LOW} always means little risk, including for
 * identity confidence, where the screen renders it as "strong" — the label is the reader's
 * vocabulary and this is the model's.
 */
public enum RiskRating {

    /** Deliberately excluded from the assessment, for a reason the response states. */
    NOT_ASSESSED,

    LOW,
    MODERATE,
    HIGH
}
