package ai.dival.dip.modules.learning;

/** How a course is delivered. Kept because it changes who can be booked on it and when. */
public enum DeliveryMode {

    ONLINE,
    CLASSROOM,

    /** Supervised practice. The one that cannot be done at a desk during a quiet afternoon. */
    ON_THE_JOB,

    /** Run by somebody else entirely, and usually the one that issues a real certificate. */
    EXTERNAL
}
