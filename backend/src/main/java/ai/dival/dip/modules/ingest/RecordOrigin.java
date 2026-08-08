package ai.dival.dip.modules.ingest;

/**
 * Where a derived record came from.
 *
 * <p>Lives in {@code ingest} rather than {@code tix} because it describes how data entered the
 * platform, which is this module's subject, and because every future module that derives records
 * from imports will need the same vocabulary.
 */
public enum RecordOrigin {

    /** Derived from a row in an import batch. The row must be named. */
    IMPORT,

    /**
     * Declared directly through the API.
     *
     * <p>Not a missing provenance. The audit trail carries who declared it, when, from where and
     * under which request id — a different evidence chain from a spreadsheet row, and a real one.
     * Modelling it as an origin rather than as a null is what stops "imported, source unknown"
     * from existing as a silent third state.
     */
    API_DECLARATION
}
