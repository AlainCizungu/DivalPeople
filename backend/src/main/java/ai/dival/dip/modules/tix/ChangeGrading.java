package ai.dival.dip.modules.tix;

/**
 * How loudly a change should ask for attention.
 *
 * <p>Static and dependency-free so it can be tested as what it is: a rule about numbers. This
 * codebase has no mocks — pure logic is made static and tested plain, and everything else is an
 * integration test — and a grading rule that needed a database to exercise would be a rule nobody
 * exercises at the boundaries.
 *
 * <p><strong>The rule is published because somebody will disagree with it.</strong> A collections
 * manager who thinks a nine-point move deserves attention should be able to see that the threshold
 * is ten and say so, rather than concluding the alerting is erratic. Every number here is arguable
 * and none of it is hidden.
 */
public final class ChangeGrading {

    /**
     * A move of this many indicator points is material on its own.
     *
     * <p>The indicator runs 0–100 over five weighted factors. Ten points is roughly one factor
     * crossing a band, which is the smallest movement that reliably means something happened
     * rather than a figure drifting over a boundary.
     */
    static final int MATERIAL_SCORE_MOVE = 10;

    /** Below this, a move is real but not worth interrupting anybody's morning for. */
    static final int NOTABLE_SCORE_MOVE = 4;

    private ChangeGrading() {
    }

    /**
     * Grades one night's movement.
     *
     * <p>Ordered worst-first, and the order is the rule. A subject that both became unpaid and
     * gained an institution is material because of the first, and evaluating the smaller
     * conditions first would grade it by whichever happened to be checked earliest.
     *
     * <p><strong>Only deterioration is material.</strong> An indicator that fell twenty points is a
     * company that improved, and waking somebody at the same volume for good news is how a queue
     * stops being read. It is still recorded, at the bottom grade, because a large improvement is
     * exactly the sort of thing somebody later wants to find — a facility declined the week before
     * a subject's debts were settled is a decision worth being able to explain.
     */
    public static MonitoringAlert.Severity grade(WatchlistEntry.Change change) {
        if (change.isFirstLook() || !change.isSomething()) {
            return MonitoringAlert.Severity.INFORMATIONAL;
        }

        boolean nowUnpaid = change.currentOutcome() == InquiryResult.Outcome.OUTSTANDING_DEBT
                && change.previousOutcome() != InquiryResult.Outcome.OUTSTANDING_DEBT;
        int move = change.scoreMovement();

        if (nowUnpaid || move >= MATERIAL_SCORE_MOVE) {
            return MonitoringAlert.Severity.MATERIAL;
        }

        boolean moreInstitutions = change.previousInstitutions() != null
                && change.currentInstitutions() > change.previousInstitutions();

        if (moreInstitutions || move >= NOTABLE_SCORE_MOVE) {
            return MonitoringAlert.Severity.NOTABLE;
        }

        return MonitoringAlert.Severity.INFORMATIONAL;
    }
}
