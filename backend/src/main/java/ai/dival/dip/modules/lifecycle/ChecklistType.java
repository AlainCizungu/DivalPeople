package ai.dival.dip.modules.lifecycle;

/** Joining or leaving. The two moments where nothing being owned by anybody shows up fastest. */
public enum ChecklistType {

    ONBOARDING,

    /**
     * Leaving. Carries more risk than onboarding: an onboarding step missed is an inconvenience,
     * an offboarding step missed is a former employee who can still open the door.
     */
    OFFBOARDING
}
