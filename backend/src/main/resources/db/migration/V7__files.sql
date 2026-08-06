-- File metadata.
--
-- Bytes live in object storage; this table is the record of what they are, who uploaded them and
-- which tenant owns them. Keeping metadata in the database is what allows authorization and
-- audit to happen before anything touches the bytes.

CREATE TABLE stored_file (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL REFERENCES tenant (id),

    -- Randomised key, never derived from the filename. A predictable key in a shared bucket is
    -- an invitation to guess at other tenants' documents.
    storage_key       VARCHAR(200)  NOT NULL UNIQUE,

    -- What the user called it. Display only: never used to build a path.
    original_filename VARCHAR(300)  NOT NULL,

    content_type      VARCHAR(150)  NOT NULL,
    size_bytes        BIGINT        NOT NULL,

    -- SHA-256 of the stored bytes: detects corruption and identifies duplicates without
    -- re-reading the object. VARCHAR, not CHAR: CHAR is a distinct JDBC type code and fails
    -- Hibernate's schema validation against a plain String field.
    checksum_sha256   VARCHAR(64)   NOT NULL,

    -- What the file is for, so retention and access rules can differ by purpose.
    category          VARCHAR(60)   NOT NULL,

    uploaded_by       UUID,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version           BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT stored_file_size_positive CHECK (size_bytes > 0)
);

CREATE INDEX idx_stored_file_tenant ON stored_file (tenant_id, created_at DESC);
CREATE INDEX idx_stored_file_category ON stored_file (tenant_id, category);
CREATE INDEX idx_stored_file_checksum ON stored_file (tenant_id, checksum_sha256);

ALTER TABLE stored_file ENABLE ROW LEVEL SECURITY;

CREATE POLICY stored_file_tenant_isolation ON stored_file
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE ON stored_file TO dip_app;
