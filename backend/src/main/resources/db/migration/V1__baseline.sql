-- Baseline platform schema: tenants and audit.
--
-- Conventions, per docs/DATABASE_DESIGN.md:
--   * UUID primary keys
--   * every tenant-owned table carries tenant_id and a row-level security policy
--   * timestamps are timestamptz, stored in UTC
--   * migrations are forward-only; never edit an applied file

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ---------------------------------------------------------------------------
-- Tenants. Not itself tenant-owned: this is the root of the tenancy model.
-- ---------------------------------------------------------------------------
CREATE TABLE tenant (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name           VARCHAR(200) NOT NULL,
    slug           VARCHAR(100) NOT NULL UNIQUE,
    edition        VARCHAR(40)  NOT NULL,
    default_locale VARCHAR(10)  NOT NULL DEFAULT 'en',
    active         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT tenant_edition_valid CHECK (
        edition IN ('BANKING', 'NGO', 'TELECOM', 'GOVERNMENT', 'HEALTHCARE', 'ENTERPRISE')
    ),
    CONSTRAINT tenant_locale_valid CHECK (default_locale IN ('en', 'fr'))
);

-- ---------------------------------------------------------------------------
-- Audit. Append-only: no UPDATE or DELETE grant is ever issued on this table.
-- tenant_id is nullable because platform-level events have no tenant.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_event (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Deliberately NOT a foreign key. Audit rows are written in their own transaction so they
    -- survive the rollback of the operation they describe; a FK would make that write fail
    -- whenever the referenced row is not yet committed. An audit log must also outlive the
    -- records it describes, so cascading deletes from tenant would be actively wrong.
    tenant_id     UUID,
    actor_id      UUID,
    action        VARCHAR(100) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,
    resource_id   VARCHAR(100),
    outcome       VARCHAR(20)  NOT NULL,
    request_id    VARCHAR(64),
    ip_address    VARCHAR(45),
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT audit_outcome_valid CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILURE'))
);

CREATE INDEX idx_audit_tenant_time ON audit_event (tenant_id, occurred_at DESC);
CREATE INDEX idx_audit_resource ON audit_event (resource_type, resource_id);
CREATE INDEX idx_audit_actor ON audit_event (actor_id, occurred_at DESC);

-- ---------------------------------------------------------------------------
-- Application role.
--
-- The application connects as dip_app, which is NOT the table owner, so the
-- row-level security policies added alongside each tenant-owned table actually
-- bind. Flyway continues to run as the owner and is unaffected.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'dip_app') THEN
        CREATE ROLE dip_app NOLOGIN;
    END IF;
END
$$;

GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA public TO dip_app;
GRANT SELECT, INSERT ON audit_event TO dip_app;
GRANT USAGE ON SCHEMA public TO dip_app;
