-- What the columns of a delivery mean.
--
-- The roadmap has said since the beginning that mappings come from the real telecom exports rather
-- than from a guess, and V24's date work removed the last reason to keep waiting. This is where
-- the answer is written down — by the operator, not by us.
--
-- **DIP does not decide which column is the amount.** It cannot: the profiled export has `Balance`
-- and nine aging buckets, all numeric, and only somebody at the operator knows that the buckets
-- sum to the balance rather than the other way round. What the platform can do is show the
-- evidence — which columns are unique, which are constant, which are entirely numeric — and record
-- the choice somebody made while looking at it.
--
-- Superseded rather than edited. A delivery is derived through whichever mapping was current when
-- it was published; rewriting one in place would make an already-published batch untraceable to
-- the rules that produced it, which is the same failure the immutable raw rows exist to prevent.

CREATE TABLE source_mapping (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL REFERENCES tenant (id),
    data_source_id      UUID         NOT NULL REFERENCES data_source (id),

    -- Column names exactly as they appear in the delivered header. Not normalised: the header is
    -- what the operator sees in their own spreadsheet, and a mapping naming something they cannot
    -- find is a mapping they cannot check.
    identifier_column   VARCHAR(200) NOT NULL,
    identifier_type     VARCHAR(30)  NOT NULL,
    name_column         VARCHAR(200) NOT NULL,
    amount_column       VARCHAR(200) NOT NULL,

    -- Fixed for the source, because the file says neither. Inventing a column for a currency the
    -- export does not carry would be a mapping to nothing.
    currency            VARCHAR(3)   NOT NULL,
    service_category    VARCHAR(60)  NOT NULL,
    subject_type        VARCHAR(20)  NOT NULL,

    version_number      INTEGER      NOT NULL DEFAULT 1,
    defined_by          UUID,
    defined_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    superseded_at       TIMESTAMPTZ,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT source_mapping_identifier_type_valid CHECK (identifier_type IN (
        'MSISDN', 'NATIONAL_ID', 'PASSPORT', 'DRIVER_LICENSE', 'VOTER_CARD', 'RCCM', 'TAX_NUMBER')),
    CONSTRAINT source_mapping_subject_type_valid CHECK (subject_type IN ('INDIVIDUAL', 'BUSINESS')),
    CONSTRAINT source_mapping_superseded_after_defined
        CHECK (superseded_at IS NULL OR superseded_at >= defined_at),
    -- Three different columns, or the mapping is describing one thing as three. Caught here
    -- because a mapping naming the same column as both the identifier and the amount would derive
    -- records that look plausible and are nonsense.
    CONSTRAINT source_mapping_columns_distinct CHECK (
        identifier_column <> name_column
            AND identifier_column <> amount_column
            AND name_column <> amount_column)
);

-- One current mapping per source. A partial unique index rather than a flag, so "current" is a
-- property the database enforces rather than one the application remembers to maintain.
CREATE UNIQUE INDEX uq_source_mapping_current
    ON source_mapping (data_source_id)
    WHERE superseded_at IS NULL;

CREATE INDEX idx_source_mapping_tenant ON source_mapping (tenant_id, data_source_id);

ALTER TABLE source_mapping ENABLE ROW LEVEL SECURITY;

CREATE POLICY source_mapping_tenant_isolation ON source_mapping
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID);

-- UPDATE is granted for one field only in practice: superseded_at. The application supersedes by
-- stamping the old row and inserting a new one, and there is no path that rewrites a mapping's
-- columns. Stating that here rather than revoking UPDATE is deliberate — a REVOKE that the
-- supersession then has to work around would be a rule nobody could follow.
GRANT SELECT, INSERT, UPDATE ON source_mapping TO dip_app;
