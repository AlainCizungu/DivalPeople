package ai.dival.dip.modules.performance;

public enum ReviewStatus {

    PENDING,

    /** At least one side has written something. Neither can read the other yet. */
    IN_PROGRESS,

    /** Both submitted. Only now does each become readable to the other. */
    BOTH_SUBMITTED,

    CALIBRATED,

    /** The employee can see it. Until this point it must inform no decision about them. */
    SHARED,

    ACKNOWLEDGED;

    public boolean isVisibleToEmployee() {
        return this == SHARED || this == ACKNOWLEDGED;
    }

    public boolean isOpenForWriting() {
        return this == PENDING || this == IN_PROGRESS;
    }
}
