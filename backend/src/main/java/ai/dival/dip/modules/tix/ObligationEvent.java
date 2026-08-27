package ai.dival.dip.modules.tix;

/**
 * What can happen to an account, and what each one means for a payment history.
 *
 * <p>The vocabulary a participant reports in. It lives in Java rather than as a database enum or a
 * check constraint because it will grow — a bank's restructuring and a microfinance cooperative's
 * rescheduling are not a telecom's vocabulary, and each addition should be a code review rather
 * than a migration.
 *
 * <p><strong>Every value declares how it counts.</strong> That is the whole reason this is an enum
 * with behaviour rather than a list of strings: "what fraction of this company's obligations went
 * wrong" is the number a credit officer will act on, and the rule producing it has to be readable
 * in one place by somebody who wants to disagree with it.
 *
 * <p>Three categories, and the third is the one people forget:
 *
 * <ul>
 *   <li><strong>Adverse</strong> — something went wrong. Counts against.
 *   <li><strong>Favourable</strong> — an obligation was met. Counts for.
 *   <li><strong>Neither</strong> — an account opened, or was disputed, or was closed. These are
 *       facts about the account rather than about behaviour, and counting them either way would
 *       mean a company that opens many accounts looks good, or bad, for opening them.
 * </ul>
 */
public enum ObligationEvent {

    /** The account exists. Says nothing about anybody's conduct. */
    OPENED(Weight.NEITHER, false),

    /**
     * Running, and not behind.
     *
     * <p>Favourable, and it is the value most likely to be argued about. A monthly "still fine"
     * from an operator is weaker evidence than a completed obligation, and if every operator sends
     * one every month for every account, a large customer of one telecom accumulates a spotless
     * history faster than a company that quietly paid off three loans elsewhere. {@link
     * ObligationHistory} is where that is dealt with, by counting accounts rather than events.
     */
    PERFORMING(Weight.FAVOURABLE, false),

    /** An obligation met in full, on the agreed terms. The strongest favourable signal here. */
    PAID_AS_AGREED(Weight.FAVOURABLE, false),

    LATE_30(Weight.ADVERSE, true),
    LATE_60(Weight.ADVERSE, true),
    LATE_90_PLUS(Weight.ADVERSE, true),

    /**
     * The terms were changed because the original ones were not being met.
     *
     * <p>Adverse, and deliberately so, though it is the least obviously adverse value here. A
     * restructuring is a lender and a borrower agreeing that the obligation as written could not
     * be paid. It is better than a default and it is not neutral, and a model that treated it as
     * neutral would let an institution launder a bad book by rescheduling it.
     */
    RESTRUCTURED(Weight.ADVERSE, true),

    /** The obligation was not met. */
    DEFAULTED(Weight.ADVERSE, true),

    /**
     * A previously adverse obligation was paid.
     *
     * <p>Favourable — somebody who fell behind and then cleared the debt has demonstrated
     * something a company that never fell behind has not. It does not erase the adverse events
     * before it: both stay in the log and both count, which is what stops settlement being a way
     * to rewrite history.
     */
    SETTLED(Weight.FAVOURABLE, false),

    /** The account ended. Says nothing on its own; how it ended is in the events before it. */
    CLOSED(Weight.NEITHER, false),

    /**
     * The subject contests something about this account.
     *
     * <p>Neither, and this is a rights decision rather than a modelling one. A disputed account is
     * one whose facts are not agreed, and counting it against somebody while they are contesting
     * it would let an erroneous report damage them for as long as the dispute takes to resolve.
     * {@link ObligationHistory} withholds the account entirely while a dispute is open.
     */
    DISPUTED(Weight.NEITHER, false);

    private final Weight weight;
    private final boolean adverse;

    ObligationEvent(Weight weight, boolean adverse) {
        this.weight = weight;
        this.adverse = adverse;
    }

    /** Whether this event counts for, against, or neither. */
    public Weight weight() {
        return weight;
    }

    /** Whether something went wrong. Equivalent to {@code weight() == ADVERSE}, and easier to read. */
    public boolean isAdverse() {
        return adverse;
    }

    /** Whether the account stops running when this arrives. */
    public boolean closesTheAccount() {
        return this == CLOSED || this == SETTLED;
    }

    public enum Weight {
        FAVOURABLE,
        ADVERSE,
        NEITHER
    }
}
