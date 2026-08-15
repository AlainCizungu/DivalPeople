package ai.dival.dip.modules.risk;

import java.math.BigDecimal;

/**
 * Everything the indicator is allowed to look at.
 *
 * <p>Primitives and this module's own enums, and not one type from {@code tix}. That is the
 * dependency direction the whole module rests on: risk knows nothing about debt records, subjects
 * or identifier formats, so it can be read, argued with and unit-tested by somebody who has never
 * opened the telecom module — and so a bank's model reviewer can be handed one file.
 *
 * <p><strong>Nothing here is a fact the enquirer was not already entitled to.</strong> That is the
 * constraint that makes an exchange-wide indicator safe to publish at all. A score is a number
 * that moves, and a number that moves with a rival's activity can be watched over time and
 * differenced back into that activity. Every input below is either something the answer already
 * carries, or a band coarse enough that no sequence of readings recovers the underlying value.
 *
 * <p>Note what is missing: no operator identity, no dates, no count of records. Dates are absent
 * because a date is a joinable fact and a band is not.
 *
 * <p><strong>An amount is here now, and it is the one input that deserved an argument.</strong>
 * Until August 2026 the currency of the operator files was unknown, so exposure could not be
 * weighed at all; counsel then confirmed both are USD. That settles whether the number means
 * anything. It does not settle whether the number may be disclosed — the exchange has never
 * reported an amount, deliberately, because an amount tells a competitor the size of a rival's
 * commercial relationship. So the model takes the total and reports a band four steps wide. What
 * leaves the platform is "somewhere between ten and a hundred thousand dollars", never a figure,
 * and no sequence of readings differences back into one. Counsel asked for the exact amount; this
 * is deliberately less than he asked for, pending a decision that is not the model's to take.
 *
 * @param anyOutstanding             at least one participant reports an unpaid obligation
 * @param anySettled                 at least one obligation on record was settled
 * @param institutionsWithOutstanding how many participants report something unpaid; never which
 * @param longestOverdueDays         age of the oldest unpaid obligation, or a negative number
 *                                   when nothing is unpaid. Days rather than a reporting band
 *                                   because the reporting bands come from an operator's export
 *                                   format and the risk thresholds are a modelling choice; tying
 *                                   the two together would mean a change of file format silently
 *                                   changed everybody's assessment
 * @param identity                   how firmly the subject was matched
 * @param fraudSignalCount           advisory indicators raised, never findings
 * @param outstandingUsd             everything unpaid, added up, in US dollars — or null when
 *                                   nothing is unpaid or the file mixes currencies, which are two
 *                                   different silences and the model says which
 */
public record RiskInputs(
        boolean anyOutstanding,
        boolean anySettled,
        int institutionsWithOutstanding,
        long longestOverdueDays,
        IdentityStrength identity,
        int fraudSignalCount,
        BigDecimal outstandingUsd) {

    public RiskInputs {
        if (identity == null) {
            throw new IllegalArgumentException(
                    "How the subject was matched is part of the assessment, so it cannot be "
                            + "absent. A caller with nothing to say should say NAME_ONLY.");
        }
        if (institutionsWithOutstanding < 0 || fraudSignalCount < 0) {
            throw new IllegalArgumentException("Counts cannot be negative");
        }
        if (outstandingUsd != null && outstandingUsd.signum() < 0) {
            throw new IllegalArgumentException(
                    "A negative total is a credit balance, which the registry refuses on the way "
                            + "in. Reaching the model means something upstream let one through.");
        }
    }
}
