package ai.dival.dip.modules.recruitment;

/** Where a candidate came from. Drives cost-per-hire and referral payouts later. */
public enum CandidateSource {
    DIRECT,
    REFERRAL,
    JOB_BOARD,
    AGENCY,

    /** An existing employee applying for another role. */
    INTERNAL,

    OTHER
}
