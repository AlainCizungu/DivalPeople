package ai.dival.dip.modules.ingest;

/** How data reaches the platform from a source. */
public enum SourceKind {

    /** XLSX or CSV, uploaded. The only one that exists today. */
    SPREADSHEET,

    /** A machine-to-machine feed. Phase 7. */
    API,

    /** Keyed in by a person, including the TIX declaration form. */
    MANUAL
}
