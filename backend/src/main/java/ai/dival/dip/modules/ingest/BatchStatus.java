package ai.dival.dip.modules.ingest;

/**
 * Where a delivery of data has got to.
 *
 * <p>The order matters: nothing derived is created from a batch until it is PUBLISHED, so an
 * operator can upload, look at what the platform made of the file, and change their mind before
 * anybody else is affected by it. An import that takes effect the moment it is uploaded gives
 * nobody that chance.
 */
public enum BatchStatus {

    /** Stored, rows kept verbatim, nothing interpreted yet. */
    RECEIVED,

    /** Checked. Row-level problems are known and can be shown before anything is published. */
    VALIDATED,

    /** Live: derived records exist and other operators can see their statuses. */
    PUBLISHED,

    /** Refused. Nothing derived was created; the rows stay so the operator can be told why. */
    REJECTED,

    /**
     * Published and then withdrawn. Derived records are removed; the raw rows remain, because the
     * fact that this file was once live is itself part of the history.
     */
    REVERTED
}
