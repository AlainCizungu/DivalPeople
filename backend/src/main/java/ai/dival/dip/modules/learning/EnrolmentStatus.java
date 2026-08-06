package ai.dival.dip.modules.learning;

public enum EnrolmentStatus {

    ENROLLED,
    IN_PROGRESS,
    COMPLETED,

    /**
     * Attempted and not passed. Kept rather than deleted: a record that cannot distinguish
     * "passed first time" from "passed on the fourth attempt" cannot answer the question an
     * investigation asks after an incident.
     */
    FAILED,

    WITHDRAWN,

    /** Passed once, but the certificate has since lapsed. Not the same as never having done it. */
    EXPIRED;

    /** Whether this enrolment is still being worked on, and so blocks a second one. */
    public boolean isLive() {
        return this == ENROLLED || this == IN_PROGRESS;
    }

    public boolean isFinished() {
        return !isLive();
    }

    /** Whether this counts as currently holding the qualification. */
    public boolean isValidQualification() {
        return this == COMPLETED;
    }
}
