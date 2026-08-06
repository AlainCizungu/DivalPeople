package ai.dival.dip.modules.recruitment;

import java.util.Set;

/**
 * Where an application has reached.
 *
 * <p>The permitted transitions are declared here rather than left to each caller. A pipeline
 * where any status can follow any other cannot be reported on, and quietly loses candidates who
 * were moved backwards by mistake.
 */
public enum ApplicationStatus {

    APPLIED,
    SCREENING,
    INTERVIEWING,
    OFFER,
    HIRED,

    /** Turned down by the employer. Always carries a reason. */
    REJECTED,

    /** The candidate walked away. Recorded separately, because it says something different. */
    WITHDRAWN;

    private static final Set<ApplicationStatus> FINAL_STATES = Set.of(HIRED, REJECTED, WITHDRAWN);

    public boolean isFinal() {
        return FINAL_STATES.contains(this);
    }

    /** Whether this status may follow the given one. */
    public boolean canFollow(ApplicationStatus previous) {
        if (previous.isFinal()) {
            return false;
        }
        // A candidate may drop out or be rejected at any point before a decision is final.
        if (this == REJECTED || this == WITHDRAWN) {
            return true;
        }
        return switch (previous) {
            case APPLIED -> this == SCREENING || this == INTERVIEWING;
            case SCREENING -> this == INTERVIEWING;
            case INTERVIEWING -> this == OFFER;
            case OFFER -> this == HIRED;
            default -> false;
        };
    }
}
