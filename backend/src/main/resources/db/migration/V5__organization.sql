-- Organization structure.
--
-- One self-referencing table rather than separate tables for legal entities, branches,
-- departments and cost centers. Real organisations nest these inconsistently — a branch inside a
-- legal entity in one country, a department that owns branches in another — and separate tables
-- force a fixed hierarchy that the first customer contradicts. A typed tree bends instead.

CREATE TABLE org_unit (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID         NOT NULL REFERENCES tenant (id),

    -- Self-reference. NULL means a root, which must be a legal entity.
    parent_id  UUID REFERENCES org_unit (id),

    unit_type  VARCHAR(30)  NOT NULL,

    -- Stable handle used in payroll exports, imports and integrations. Unique per tenant, which
    -- is why it is not the primary key: two tenants may legitimately both have a "HQ".
    code       VARCHAR(50)  NOT NULL,
    name       VARCHAR(200) NOT NULL,

    -- Denormalised for display ordering and for cheap "how deep is this" checks. Maintained by
    -- the service; subtree queries use a recursive CTE rather than trusting it.
    depth      INT          NOT NULL DEFAULT 0,

    active     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version    BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT org_unit_type_valid CHECK (
        unit_type IN ('LEGAL_ENTITY', 'BRANCH', 'DEPARTMENT', 'COST_CENTER', 'LOCATION')
    ),
    CONSTRAINT org_unit_root_is_legal_entity CHECK (
        parent_id IS NOT NULL OR unit_type = 'LEGAL_ENTITY'
    ),
    CONSTRAINT org_unit_not_own_parent CHECK (parent_id IS DISTINCT FROM id),
    CONSTRAINT uq_org_unit_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_org_unit_tenant ON org_unit (tenant_id);
CREATE INDEX idx_org_unit_parent ON org_unit (tenant_id, parent_id);

-- Tenant isolation. USING and WITH CHECK both required, per ADR 0002 and enforced by
-- scripts/check_architecture.py.
ALTER TABLE org_unit ENABLE ROW LEVEL SECURITY;

CREATE POLICY org_unit_tenant_isolation ON org_unit
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE ON org_unit TO dip_app;
