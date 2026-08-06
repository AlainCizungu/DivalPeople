package ai.dival.dip.modules.performance;

/**
 * A five-point scale.
 *
 * <p>Five rather than four, so there is a genuine middle. A scale with no midpoint forces every
 * ordinary year into either praise or criticism, and managers respond by inflating.
 */
public enum Rating {

    UNSATISFACTORY,
    DEVELOPING,

    /** The expected outcome, and the one most people should get. */
    MEETS,

    EXCEEDS,
    OUTSTANDING
}
