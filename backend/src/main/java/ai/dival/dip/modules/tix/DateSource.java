package ai.dival.dip.modules.tix;

/**
 * Where a debt record's default date came from.
 *
 * <p>Exists because the profiled Vodacom export contains no dates at all, and
 * {@code default_date} is NOT NULL with the retention clock running from it. Something has to go
 * in that column for an imported row to become a record — and the expensive mistake is not putting
 * an approximate date there, it is forgetting that it was approximate.
 *
 * <p>Once the two are indistinguishable, a later delivery carrying real dates cannot be
 * reconciled: nobody can say which records to correct. With the distinction recorded, correcting
 * them is a query — every raw row is immutable and every derived record names the row it came
 * from, so the fix is to re-derive rather than to guess.
 */
public enum DateSource {

    /** The declaring operator gave this date. Every API declaration is this. */
    REPORTED,

    /**
     * Computed from the batch's reported-as-at date, because the file carried none.
     *
     * <p>V24 permits this only on a record whose origin is IMPORT. An API declaration carries a
     * date somebody typed, so calling it derived would be a lie the schema can catch.
     *
     * <p><strong>A correction may only ever shorten retention, never extend it.</strong> If a real
     * date later turns out to be after the assumed one, the earlier expiry stands. Otherwise
     * fixing our own approximation would quietly lengthen how long somebody is listed — which
     * {@code docs/TIX_RETENTION.md} names as punishment implemented as a cron job.
     */
    DERIVED
}
