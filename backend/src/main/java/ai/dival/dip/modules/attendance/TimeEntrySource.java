package ai.dival.dip.modules.attendance;

/**
 * Where a time entry came from.
 *
 * <p>Kept because trust in a record depends on it: a biometric punch and a figure typed in by a
 * supervisor a week later are not the same evidence, and an auditor will ask which is which.
 */
public enum TimeEntrySource {

    WEB,
    MOBILE,
    BIOMETRIC,

    /** Bulk-loaded from another system. */
    IMPORT,

    /** Entered by hand, usually because somebody forgot to clock. */
    MANUAL
}
