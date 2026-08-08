-- The provenance spine.
--
-- Until now tix_debt_record was the origin of truth: an operator declared a debt through the API
-- and nothing recorded where the figure came from. AGENTS.md rules 4 and 5 — immutable raw
-- imports, preserved provenance — were aspirations against this schema rather than descriptions
-- of it, and DATABASE_DESIGN.md requires the lineage
--
--     source organization → import batch → immutable raw record → canonical entity → exposure
--
-- so that every displayed figure can be traced to a row somebody sent us. This migration builds
-- the first three links. Nothing here parses a file: the payload is stored exactly as received,
-- because the column mappings must come from the real Vodacom export rather than from a guess.

-- ---------------------------------------------------------------------------
-- Where data comes from.
--
-- Tenant-owned: an operator defines its own datasets, and one operator's source definitions are
-- no business of another's. Called data_source to match DATABASE_DESIGN.md.
-- ---------------------------------------------------------------------------
CREATE TABLE data_source (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL REFERENCES tenant (id),
    code        VARCHAR(60)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    kind        VARCHAR(30)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT data_source_kind_valid CHECK (kind IN ('SPREADSHEET', 'API', 'MANUAL')),
    CONSTRAINT uq_data_source_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_data_source_tenant ON data_source (tenant_id);

-- ---------------------------------------------------------------------------
-- One delivery of data.
--
-- A batch is the unit an import is accepted, inspected, published or reverted as. Keeping it
-- separate from the rows is what makes "undo that import" a sentence rather than a project.
-- ---------------------------------------------------------------------------
CREATE TABLE import_batch (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenant (id),
    data_source_id  UUID         NOT NULL REFERENCES data_source (id),
    filename        VARCHAR(300) NOT NULL,
    -- Of the file as received, before anything was parsed out of it. This is what makes a
    -- re-upload detectable and what an auditor compares against the operator's own copy.
    checksum_sha256 VARCHAR(64)  NOT NULL,
    byte_size       BIGINT       NOT NULL,
    row_count       INTEGER      NOT NULL DEFAULT 0,
    status          VARCHAR(20)  NOT NULL,
    uploaded_by     UUID,
    received_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT import_batch_status_valid CHECK (
        status IN ('RECEIVED', 'VALIDATED', 'PUBLISHED', 'REJECTED', 'REVERTED')
    ),
    CONSTRAINT import_batch_checksum_shape CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT import_batch_published_has_time CHECK (
        (status = 'PUBLISHED') = (published_at IS NOT NULL)
    )
);

CREATE INDEX idx_import_batch_tenant ON import_batch (tenant_id);
CREATE INDEX idx_import_batch_source ON import_batch (data_source_id);

-- The same file may not be published twice. Partial, so a file rejected on its first attempt can
-- be corrected and sent again — the constraint is about what is live, not about what was ever
-- tried. Rule 17 asks imports to be idempotent or safely retryable; this is the "safely" half,
-- and it is enforced by the database rather than by whoever remembers to check.
CREATE UNIQUE INDEX uq_import_batch_published_checksum
    ON import_batch (tenant_id, checksum_sha256)
    WHERE status = 'PUBLISHED';

-- ---------------------------------------------------------------------------
-- The rows themselves, exactly as they arrived.
--
-- payload is JSONB and deliberately unmapped. Normalising on the way in would mean inventing a
-- column layout before seeing a real Vodacom export, and the roadmap is explicit: define mappings
-- from the real spreadsheets, not from a generic schema. Whatever the file says is what is kept;
-- interpretation happens later and downstream, where it can be corrected without losing the
-- original.
-- ---------------------------------------------------------------------------
CREATE TABLE raw_record (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID        NOT NULL REFERENCES tenant (id),
    batch_id     UUID        NOT NULL REFERENCES import_batch (id),
    -- 1-based, as a person reading the spreadsheet would count. An operator asked to check a
    -- rejected row should be able to open their file and go to that line.
    row_number   INTEGER     NOT NULL,
    payload      JSONB       NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT raw_record_row_positive CHECK (row_number >= 1),
    CONSTRAINT uq_raw_record_row UNIQUE (batch_id, row_number)
);

CREATE INDEX idx_raw_record_tenant ON raw_record (tenant_id);
CREATE INDEX idx_raw_record_batch ON raw_record (batch_id);

-- ---------------------------------------------------------------------------
-- Immutability, and what it does NOT mean.
--
-- A raw record may never be rewritten. That is the whole point: it is the evidence that the
-- number we display came from something the operator actually sent, and evidence that can be
-- edited after the fact is not evidence. Enforced the way audit_event is — by REVOKE for the
-- application role and by a rule that binds the owner too, because a REVOKE cannot stop the
-- account that owns the schema.
--
-- DELETE is deliberately NOT blocked. Immutable and permanent are different properties, and
-- conflating them here would be a serious mistake: these rows carry personal data about people
-- who never consented to being in a registry, retention periods apply to them, and a row that
-- cannot be deleted is a row that cannot be erased. Unchangeable while it exists; erasable when
-- its period ends.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE RULE raw_record_no_update AS ON UPDATE TO raw_record DO INSTEAD NOTHING;

-- ---------------------------------------------------------------------------
-- Row-level security. Same shape as everything else tenant-owned.
--
-- No exchange-mode escape hatch on any of these, and that is deliberate. tix_debt_record has one
-- because the whole purpose of the exchange is to read statuses across operators. Raw source
-- rows are the opposite: they are one operator's file, they contain far more than the exchange
-- ever shares, and no cross-operator question needs them. Granting an escape hatch nobody needs
-- is how one appears in a query later.
-- ---------------------------------------------------------------------------
ALTER TABLE data_source ENABLE ROW LEVEL SECURITY;
CREATE POLICY data_source_tenant_isolation ON data_source
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE import_batch ENABLE ROW LEVEL SECURITY;
CREATE POLICY import_batch_tenant_isolation ON import_batch
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

ALTER TABLE raw_record ENABLE ROW LEVEL SECURITY;
CREATE POLICY raw_record_tenant_isolation ON raw_record
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON data_source TO dip_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON import_batch TO dip_app;
-- No UPDATE. The rule above already discards one; withholding the privilege as well means the
-- application cannot even attempt it, and the two together survive somebody dropping the rule.
GRANT SELECT, INSERT, DELETE ON raw_record TO dip_app;

-- ---------------------------------------------------------------------------
-- audit_event: row-level security, nineteen migrations late.
--
-- Found by widening the architecture check in this same change. Rule 3 only recognised entities
-- extending TenantOwnedEntity, and AuditEvent declares its own tenant_id because audit rows are
-- written outside the operation they describe and carry no version column. So the one table whose
-- whole purpose is accountability was the one table the policy check could not see, and it has
-- had no policy since V1.
--
-- The application scopes its own reads by tenant, so nothing has leaked. But that is exactly the
-- single-control situation rule 3 exists to prevent: one query written without a tenant predicate
-- and one operator reads another's audit trail — including which subjects they inquired about,
-- which is competitive intelligence about a competitor's customers.
--
-- tenant_id is nullable here on purpose (system events happen with no tenant bound), so:
--   USING      — a tenant sees only its own rows. Rows with no tenant belong to the platform and
--                are read by an operator with database access, not through the application.
--   WITH CHECK — allows the NULL case, because refusing it would mean the application could not
--                record a failure that happened before a tenant was resolved, which is precisely
--                when you most want a record.
--
-- Deliberately no exchange-mode clause. Nothing about the exchange needs to read across
-- operators' audit trails, and a flag that exists gets used.
-- ---------------------------------------------------------------------------
ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;

CREATE POLICY audit_event_tenant_isolation ON audit_event
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant() OR tenant_id IS NULL);

-- ---------------------------------------------------------------------------
-- Attach existing debt records to where they came from.
--
-- origin is NOT NULL and the check ties it to raw_record_id: an IMPORT must name the row it came
-- from, and an API_DECLARATION must not pretend to have one. A nullable foreign key on its own
-- would have let "imported, provenance unknown" exist as a silent third state, which is exactly
-- the state rule 5 exists to prevent.
--
-- API_DECLARATION is a first-class origin rather than a gap. An operator declaring through the
-- API is a legitimate door and stays one; its provenance is the audit trail — who called, when,
-- from where, with which request id — rather than a spreadsheet row. Saying so in the schema is
-- better than leaving a null and hoping somebody remembers what it meant.
-- ---------------------------------------------------------------------------
ALTER TABLE tix_debt_record ADD COLUMN origin VARCHAR(20);
ALTER TABLE tix_debt_record ADD COLUMN raw_record_id UUID REFERENCES raw_record (id);

UPDATE tix_debt_record SET origin = 'API_DECLARATION' WHERE origin IS NULL;

ALTER TABLE tix_debt_record ALTER COLUMN origin SET NOT NULL;

ALTER TABLE tix_debt_record ADD CONSTRAINT tix_debt_origin_valid
    CHECK (origin IN ('IMPORT', 'API_DECLARATION'));

ALTER TABLE tix_debt_record ADD CONSTRAINT tix_debt_origin_matches_source
    CHECK (
        (origin = 'IMPORT' AND raw_record_id IS NOT NULL)
     OR (origin = 'API_DECLARATION' AND raw_record_id IS NULL)
    );

CREATE INDEX idx_tix_debt_raw_record ON tix_debt_record (raw_record_id);
