package ai.dival.dip.modules.recruitment;

/** What happened to a scheduled interview. */
public enum InterviewStatus {
    SCHEDULED,
    COMPLETED,
    CANCELLED,

    /** The candidate did not attend. Distinct from cancelled, which is the employer's doing. */
    NO_SHOW
}
