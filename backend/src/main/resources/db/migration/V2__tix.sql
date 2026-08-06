-- TIX — Telecom Information Exchange.
--
-- Subjects are shared across operators by design: several operators may hold records
-- against the same person. Everything an operator *asserts* about a subject is tenant-owned
-- and carries a row-level security policy.

-- ---------------------------------------------------------------------------
-- Subjects: the shared spine of the exchange. Not tenant-owned.
-- ---------------------------------------------------------------------------
CREATE TABLE tix_subject (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_type    VARCHAR(20)  NOT NULL,
    full_name       VARCHAR(300) NOT NULL,
    normalized_name VARCHAR(300) NOT NULL,
    date_of_birth   DATE,
    -- VARCHAR rather than CHAR: CHAR is a distinct JDBC type code and would fail Hibernate's
    -- schema validation against a plain String field, besides padding values on read.
    nationality     VARCHAR(2),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT tix_subject_type_valid CHECK (subject_type IN ('INDIVIDUAL', 'BUSINESS'))
);

CREATE INDEX idx_tix_subject_normalized_name ON tix_subject (normalized_name);
CREATE INDEX idx_tix_subject_dob ON tix_subject (date_of_birth);

-- ---------------------------------------------------------------------------
-- Identifiers.
--
-- The unique constraint is what makes an identifier resolve to exactly one subject,
-- and what makes a reused document detectable rather than silently duplicated.
-- ---------------------------------------------------------------------------
CREATE TABLE tix_subject_identifier (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id       UUID         NOT NULL REFERENCES tix_subject (id) ON DELETE CASCADE,
    identifier_type  VARCHAR(30)  NOT NULL,
    normalized_value VARCHAR(200) NOT NULL,
    CONSTRAINT tix_identifier_type_valid CHECK (
        identifier_type IN ('MSISDN', 'NATIONAL_ID', 'PASSPORT', 'DRIVER_LICENSE',
                            'VOTER_CARD', 'RCCM', 'TAX_NUMBER')
    ),
    CONSTRAINT uq_tix_identifier UNIQUE (identifier_type, normalized_value)
);

CREATE INDEX idx_tix_identifier_subject ON tix_subject_identifier (subject_id);

-- ---------------------------------------------------------------------------
-- Debt records. Tenant-owned: declared and settled only by the owning operator.
-- ---------------------------------------------------------------------------
CREATE TABLE tix_debt_record (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID           NOT NULL REFERENCES tenant (id),
    subject_id       UUID           NOT NULL REFERENCES tix_subject (id),
    status           VARCHAR(30)    NOT NULL,
    amount           NUMERIC(18, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    service_category VARCHAR(60)    NOT NULL,
    default_date     DATE           NOT NULL,
    dunning_evidence BOOLEAN        NOT NULL DEFAULT FALSE,
    settled_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT tix_debt_status_valid CHECK (
        status IN ('OUTSTANDING', 'SETTLED', 'DISPUTED', 'UNDER_INVESTIGATION', 'CLEARED')
    ),
    CONSTRAINT tix_debt_amount_positive CHECK (amount > 0),
    -- A record may not be declared without evidence that dunning ran first.
    CONSTRAINT tix_debt_requires_dunning CHECK (dunning_evidence = TRUE)
);

CREATE INDEX idx_tix_debt_tenant ON tix_debt_record (tenant_id);
CREATE INDEX idx_tix_debt_subject_status ON tix_debt_record (subject_id, status);
CREATE UNIQUE INDEX uq_tix_debt_open_per_operator
    ON tix_debt_record (tenant_id, subject_id)
    WHERE status = 'OUTSTANDING';

-- ---------------------------------------------------------------------------
-- Row-level security.
--
-- Ordinary access is confined to the caller's tenant. The exchange reads across
-- operators through a separate, audited path; grant dip_exchange to that connection
-- only if you later split the pool.
-- ---------------------------------------------------------------------------
ALTER TABLE tix_debt_record ENABLE ROW LEVEL SECURITY;

CREATE POLICY tix_debt_tenant_isolation ON tix_debt_record
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID);

GRANT SELECT, INSERT, UPDATE ON tix_debt_record TO dip_app;
GRANT SELECT, INSERT, UPDATE ON tix_subject TO dip_app;
GRANT SELECT, INSERT, UPDATE ON tix_subject_identifier TO dip_app;
