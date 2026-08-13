package ai.dival.dip.modules.anomalies;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * How one of an operator's own users has been using the exchange.
 *
 * <p>Four numbers and a verdict. Nothing here identifies a subject, and that is deliberate: this
 * screen is about the person asking, not the people asked about, and a list of who somebody looked
 * up would be a second copy of the audit trail with a worse justification.
 *
 * @param actorId    the user, or null for inquiries made before local user records existed
 * @param inquiries  how many they made in the window
 * @param noMatch    how many resolved to nobody. The tell: the exchange records the subject when
 *                   it confirms a match and null when it does not, so a caller guessing
 *                   identifiers produces a long row of nulls
 * @param refused    how many the rate limiter turned away. A person doing their job reaches that
 *                   limit approximately never
 * @param lastAsked  when they last used it, so a spike from last month is not read as today's
 * @param flags      what looks unusual, and empty for almost everybody
 */
public record InquiryBehaviour(UUID actorId, long inquiries, long noMatch, long refused,
                               Instant lastAsked, List<BehaviourFlag> flags) {

    public InquiryBehaviour {
        flags = List.copyOf(flags);
    }

    /**
     * The proportion that found nobody.
     *
     * <p>Zero rather than a division by zero for somebody who has made no inquiries, which cannot
     * happen through this query — they would not be in the audit trail — and is worth not
     * crashing on anyway.
     */
    public double noMatchRatio() {
        return inquiries == 0 ? 0.0 : (double) noMatch / inquiries;
    }
}
