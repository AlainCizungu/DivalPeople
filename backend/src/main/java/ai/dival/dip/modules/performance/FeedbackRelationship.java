package ai.dival.dip.modules.performance;

/** How the author knows the person they are writing about. */
public enum FeedbackRelationship {

    PEER,

    /** Somebody who reports to the subject. The most useful and least often asked. */
    DIRECT_REPORT,

    MANAGER,
    SKIP_LEVEL,

    /** A client, contractor or colleague at another organisation. */
    EXTERNAL
}
