package ai.dival.dip.modules.resolution;

/**
 * Where a candidate pair has got to.
 *
 * <p>Four states, and the third is the one that earns its place. A reviewer looking at two records
 * that might be one person has a third honest answer available — <em>I cannot tell from this</em> —
 * and a queue offering only yes and no forces that answer into one of the other two. It gets
 * forced into "no", because rejecting feels safer than merging, and the pair leaves the queue
 * looking decided.
 */
public enum MatchStatus {

    /** Found by the scan, not yet looked at. */
    OPEN,

    /** One subject. The records have been moved onto the survivor. */
    CONFIRMED,

    /** Two subjects. Not the same person or company, whatever the confidence said. */
    REJECTED,

    /**
     * Somebody looked and could not tell.
     *
     * <p>Stays out of the queue and stays undecided, which is the true state. A case here is
     * usually waiting on something outside the platform — an operator confirming a date of birth,
     * a customer being telephoned — and the note says what.
     */
    INVESTIGATING
}
