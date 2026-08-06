-- Local user records.
--
-- Identity is owned by the OIDC provider; this table is the platform's local reference to a
-- person so that domain rows can point at an actor, membership can be listed, and audit entries
-- resolve to someone rather than to an opaque token subject.
--
-- Authentication and authorization remain the provider's job. Nothing here grants access.

CREATE TABLE user_account (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenant (id),

    -- The OIDC 'sub' claim. Stable for the lifetime of the identity and never reused, which is
    -- why it is the join key rather than email — email changes, and changing it must not orphan
    -- a person's history.
    subject       VARCHAR(255) NOT NULL,

    email         VARCHAR(320),
    display_name  VARCHAR(300),

    -- Snapshot of realm roles at last sign-in, for display and member lists only.
    -- NOT authoritative: authorization is decided from the access token on each request, so a
    -- role revoked in the provider takes effect immediately regardless of what is stored here.
    roles         VARCHAR(1000) NOT NULL DEFAULT '',

    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    last_seen_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version       BIGINT       NOT NULL DEFAULT 0,

    -- One identity maps to one tenant, matching the single tenant_id claim the provider issues.
    -- Supporting a person in several tenants means a separate membership table and an ADR.
    CONSTRAINT uq_user_account_subject UNIQUE (subject)
);

CREATE INDEX idx_user_account_tenant ON user_account (tenant_id);
CREATE INDEX idx_user_account_email ON user_account (tenant_id, email);

-- Tenant isolation, defence in depth alongside the application-level scoping.
ALTER TABLE user_account ENABLE ROW LEVEL SECURITY;

CREATE POLICY user_account_tenant_isolation ON user_account
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID);

GRANT SELECT, INSERT, UPDATE ON user_account TO dip_app;

-- audit_event.actor_id now refers to user_account.id rather than a raw provider subject.
-- Still no foreign key, for the reason given in V1: audit is written in its own transaction and
-- must outlive the rows it describes.
COMMENT ON COLUMN audit_event.actor_id IS
    'user_account.id of the acting user. Intentionally not a foreign key: audit is append-only, '
    'written in a separate transaction, and must survive deletion of the referenced record.';
