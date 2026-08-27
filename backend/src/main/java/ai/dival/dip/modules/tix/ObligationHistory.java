package ai.dival.dip.modules.tix;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What a subject's accounts add up to.
 *
 * <p>The number a credit officer will actually act on, so the rule producing it is one static
 * function over plain values, tested without a database, and readable by somebody who wants to
 * argue with it. That is the same treatment {@link ChangeGrading} gets and for the same reason: a
 * rule that can only be exercised through Postgres is a rule nobody exercises at its boundaries.
 *
 * <p><strong>Accounts are counted, not events.</strong> If an operator sends "still performing"
 * every month for every account, counting events would mean a company with one large telecom
 * account out-scores a company that quietly repaid four loans elsewhere — the first has 48 rows a
 * year and the second has four. So an account contributes exactly once however loudly it is
 * reported, and the loudness of a participant's reporting cannot move anybody's history.
 *
 * <p><strong>Adversity is not erased by settlement.</strong> An account that defaulted and was
 * later paid counts in both {@link #accountsAdverse()} and {@link #accountsSettled()}. Letting a
 * settlement remove the default would make paying a way to rewrite history, and the fact that
 * somebody fell behind and then cleared it is worth reporting in its own right.
 *
 * <p><strong>A disputed account is withheld entirely.</strong> Not counted good, not counted bad,
 * not in the denominator. Its facts are by definition not agreed, and letting an erroneous report
 * drag somebody's percentage down for the months a dispute takes to resolve would make the
 * dispute process a punishment. The count of withheld accounts is published so the reader knows
 * the picture is incomplete.
 *
 * <p><strong>A thin file gets no percentage at all.</strong> Two accounts and no defaults is a
 * perfect record and almost no evidence, and "100%" over a denominator of two invites exactly the
 * confidence it does not earn. Below {@link #MINIMUM_ACCOUNTS_FOR_A_RATIO} the percentage is null
 * and the depth says why. This is the protection for the borrower DIP exists to help: in a market
 * where private credit is scarce, the interesting applicant is the one with almost no file, and a
 * model that turned thinness into a low score would price them out on the strength of nothing.
 */
public record ObligationHistory(
        int accountsObserved,
        int accountsAdverse,
        int accountsSettled,
        int accountsPerforming,
        int accountsWithheld,
        int institutionsContributing,
        Integer performancePercent,
        Depth depth) {

    /**
     * Below this many accounts, no percentage is published.
     *
     * <p>Five, and it is arguable — which is why it is a named constant with this note rather than
     * a literal. It is the point at which one bad account stops moving the figure by twenty points
     * or more. Four accounts with one default reads as 75%, which sounds like a considered
     * assessment and is one event away from 100% or 50%.
     */
    public static final int MINIMUM_ACCOUNTS_FOR_A_RATIO = 5;

    /** How much there is to go on. Published beside the percentage, never instead of it. */
    public enum Depth {
        /** Nothing the network can see. */
        NONE,
        /** Some history, too little to express as a ratio. */
        THIN,
        /** Enough for a percentage, not enough to lean on. */
        MODERATE,
        /** A record long enough that one more account will not move it. */
        DEEP
    }

    private static final int MODERATE_UPPER_BOUND = 20;

    /**
     * One account, reduced to what the history needs.
     *
     * @param institution any stable key for the reporting operator. It is used to count distinct
     *                    contributors and never leaves this class — the result carries a number,
     *                    not a set, which is the same discipline {@code NetworkService} follows
     *                    for exactly the same reason.
     * @param events      every event on the account. Order does not matter here; this asks what
     *                    happened to the account, not in what sequence.
     */
    public record Account(String institution, Collection<ObligationEvent> events) {

        public boolean isDisputed() {
            return events.contains(ObligationEvent.DISPUTED);
        }

        public boolean hasAdverse() {
            return events.stream().anyMatch(ObligationEvent::isAdverse);
        }

        public boolean wasSettled() {
            return events.contains(ObligationEvent.SETTLED);
        }

        /**
         * Running, and nothing has gone wrong with it.
         *
         * <p>Closed accounts are excluded: an account that ended without incident is a completed
         * obligation, which is a different and stronger statement than one that has not gone wrong
         * yet.
         */
        public boolean isPerforming() {
            return !hasAdverse()
                    && events.stream().noneMatch(ObligationEvent::closesTheAccount);
        }
    }

    public static ObligationHistory of(List<Account> accounts) {
        Set<String> contributors = new LinkedHashSet<>();
        int observed = 0;
        int adverse = 0;
        int settled = 0;
        int performing = 0;
        int withheld = 0;

        for (Account account : accounts) {
            if (account.isDisputed()) {
                withheld++;
                // Deliberately before the contributor is counted. An institution whose only
                // account here is disputed is not contributing to this history, and counting it
                // would inflate the breadth of evidence with a record nobody is standing behind.
                continue;
            }

            contributors.add(account.institution());
            observed++;
            if (account.hasAdverse()) {
                adverse++;
            }
            if (account.wasSettled()) {
                settled++;
            }
            if (account.isPerforming()) {
                performing++;
            }
        }

        return new ObligationHistory(
                observed, adverse, settled, performing, withheld, contributors.size(),
                percentFor(observed, adverse), depthFor(observed));
    }

    /**
     * The share of accounts that never went wrong, or null when there is too little to say.
     *
     * <p>Integer percent rather than a decimal. A payment history is not precise to a tenth of a
     * percent and printing it that way would claim an accuracy the underlying reporting does not
     * have.
     */
    private static Integer percentFor(int observed, int adverse) {
        if (observed < MINIMUM_ACCOUNTS_FOR_A_RATIO) {
            return null;
        }
        return Math.round(100f * (observed - adverse) / observed);
    }

    private static Depth depthFor(int observed) {
        if (observed == 0) {
            return Depth.NONE;
        }
        if (observed < MINIMUM_ACCOUNTS_FOR_A_RATIO) {
            return Depth.THIN;
        }
        return observed < MODERATE_UPPER_BOUND ? Depth.MODERATE : Depth.DEEP;
    }

    /** Whether a percentage was published. Saves every caller a null check spelled differently. */
    public boolean hasPercentage() {
        return performancePercent != null;
    }
}
