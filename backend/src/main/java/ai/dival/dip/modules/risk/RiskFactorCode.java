package ai.dival.dip.modules.risk;

/**
 * The seven things the indicator considers, whether or not it can assess them.
 *
 * <p>A code rather than a sentence, because the sentence has to exist in French as well and a
 * message catalogue is where translations live. The API carries the code and the points; the
 * screen carries the words. That also means changing the wording of an explanation never touches
 * a model that a lending decision was made with.
 *
 * <p>The two unassessed factors are here on purpose. Leaving them out of the enum would leave
 * them out of the response, and a bank reading a risk assessment is entitled to know that
 * exposure was not weighed rather than to assume it was weighed and found small.
 */
public enum RiskFactorCode {

    /**
     * Whether anything is currently unpaid, or whether everything on record was settled.
     *
     * <p>The heaviest single assessed factor, and the least clever. Everything else on this list
     * qualifies the question of whether somebody is paying.
     */
    PAYMENT_BEHAVIOUR(25),

    /**
     * How long the oldest unpaid obligation has been overdue.
     *
     * <p>Weighted above payment behaviour in the aggregate because age is what separates a
     * missed invoice from a write-off, and it is the fact the platform holds most reliably: a
     * default date is either reported by the operator or derived from a stated as-at date, and
     * either way it is recorded as which of the two it was.
     */
    DEBT_AGING(30),

    /**
     * How many participating institutions report an unpaid obligation.
     *
     * <p>The only factor that could not exist outside an exchange, and the reason one is worth
     * joining. One operator's unpaid invoice is a dispute; three operators' is a pattern.
     */
    REPORTING_INSTITUTIONS(20),

    /**
     * How firmly the subject was identified in the first place.
     *
     * <p>Not a property of the company — a property of the answer. A file that names its
     * customers and numbers none of them can only be matched on a name, and an assessment resting
     * on a name match should not read as confidently as one resting on an RCCM. Rating this
     * openly is the difference between a cautious answer and a confident wrong one.
     */
    IDENTITY_CONFIDENCE(15),

    /** Advisory indicators such as one identifier appearing under two different subjects. */
    FRAUD_INDICATORS(10),

    /**
     * The size of what is owed. <strong>Not assessed.</strong>
     *
     * <p>The amount column in both real operator exports has no stated currency. If it is francs
     * rather than dollars every figure is out by a factor of about 2,800, and a model that
     * weighted amounts would be wrong by that factor while looking entirely reasonable. Excluded
     * until an operator confirms it, and named in every response so nobody assumes otherwise.
     */
    OUTSTANDING_EXPOSURE(0),

    /**
     * Whether the subject has contested anything. <strong>Not assessed, and not disclosed.</strong>
     *
     * <p>A dispute already removes a record from every answer the exchange gives, before anybody
     * decides who is right, because the harm of being wrongly listed accrues daily. Reporting
     * "this subject disputes things" would hand back through the front door exactly what that
     * design takes out the back — and would make exercising a statutory right a thing that costs
     * you. It is listed so the reader knows disputed records were excluded, and it will carry no
     * weight in any version of this model.
     */
    DISPUTE_HISTORY(0);

    private final int maxPoints;

    RiskFactorCode(int maxPoints) {
        this.maxPoints = maxPoints;
    }

    /**
     * The most this factor can contribute.
     *
     * <p>The assessed five sum to one hundred, which is checked by a test rather than trusted: an
     * indicator described as being out of 100 whose parts add to 95 has a ceiling nobody can
     * reach and a top band nobody ever lands in.
     */
    public int maxPoints() {
        return maxPoints;
    }

    public boolean isAssessed() {
        return maxPoints > 0;
    }
}
