-- ---------------------------------------------------------------------------
-- Which institution an email address belongs to.
--
-- Until now every account was created by hand: the platform operator ran
-- kcadm, or a tenant administrator filled in a form. Both put a person in the
-- loop for every single joiner, and neither survives fifteen banks with normal
-- staff turnover.
--
-- So: a person registers with their own work address, proves they can read
-- mail at it, and DIP looks up which institution that domain belongs to. They
-- never say which institution they are joining, and there is no field in which
-- they could. That is the same rule the rest of the platform follows — the
-- tenant is decided by the server, never asserted by the request — applied to
-- the one moment where a person previously could not act for themselves.
--
-- WHY THIS TABLE AND NOT A COLUMN ON tenant.
--
-- Institutions have more than one domain. A group with two trading names, a
-- bank that acquired another and still runs both mail systems, a telecom whose
-- staff are on the parent company's domain. A single column would force every
-- one of those to be wrong.
--
-- ONE DOMAIN BELONGS TO ONE INSTITUTION, AND THE DATABASE ENFORCES IT.
--
-- The unique index below is global, not per-tenant, and it is the single most
-- important line in this file. Without it, two institutions could both claim
-- vodacom.cd, and the lookup that decides whose credit records a new joiner
-- can read would depend on which row came back first. There is no sensible
-- behaviour in that situation, so it is made unrepresentable.
--
-- That also means the constraint is deliberately NOT scoped by tenant, unlike
-- every other unique index in this schema. A conflict across two institutions
-- is exactly the thing worth refusing.
--
-- WHO MAY WRITE HERE.
--
-- The platform operator only. A tenant administrator claiming their own
-- domain sounds harmless and is not: nothing stops them claiming a
-- competitor's, and the reward is every future joiner from that competitor
-- landing inside their book. Proving control of a domain properly means DNS,
-- and DNS verification is worth building when institutions onboard themselves
-- — at six participants, the operator adding one row when an institution signs
-- its contract is both less work and a stronger guarantee.
--
-- The RLS policy still carries tenant_id so an institution can be shown its
-- own domains, and WITH CHECK still refuses cross-tenant writes, but the
-- application guards these endpoints with PLATFORM_ADMIN on top of that.
-- ---------------------------------------------------------------------------

CREATE TABLE tenant_email_domain (
    id         UUID PRIMARY KEY,
    tenant_id  UUID        NOT NULL REFERENCES tenant (id),

    -- Stored lower-cased. Domains are case-insensitive and a mixed-case row
    -- would simply never match, silently, which is the worst failure this
    -- table has available to it.
    domain     TEXT        NOT NULL,

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version    BIGINT      NOT NULL DEFAULT 0,

    -- Belt and braces with the application, which lower-cases before writing.
    -- A row inserted by hand during an incident is exactly when this matters.
    CONSTRAINT tenant_email_domain_lowercase CHECK (domain = lower(domain)),

    -- Not a validating regular expression, on purpose: the set of things that
    -- are a real domain is larger than anything short enough to read here, and
    -- an over-strict pattern would reject a customer. This refuses only what
    -- cannot possibly be a domain.
    CONSTRAINT tenant_email_domain_shaped CHECK (
        domain LIKE '%.%'
        AND domain NOT LIKE '%@%'
        AND domain NOT LIKE '% %'
        AND length(domain) BETWEEN 4 AND 253
    )
);

-- Global, not per-tenant. See the note above; this is the line that makes
-- "which institution does this address belong to" a question with one answer.
CREATE UNIQUE INDEX uq_tenant_email_domain ON tenant_email_domain (domain);

CREATE INDEX idx_tenant_email_domain_tenant ON tenant_email_domain (tenant_id);

ALTER TABLE tenant_email_domain ENABLE ROW LEVEL SECURITY;

-- Exchange mode in USING, and only in USING.
--
-- The lookup that matters happens for somebody who has just registered and has
-- no tenant at all — that is the entire point of it — so the read cannot be
-- scoped by app_current_tenant(). WITH CHECK stays scoped, so a transaction
-- that can read every institution's domains still cannot write a row into
-- another institution's name.
DROP POLICY IF EXISTS tenant_email_domain_tenant_isolation ON tenant_email_domain;
CREATE POLICY tenant_email_domain_tenant_isolation ON tenant_email_domain
    USING (tenant_id = app_current_tenant() OR app_exchange_mode())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, DELETE ON tenant_email_domain TO dip_app;

COMMENT ON TABLE tenant_email_domain IS
    'Email domains that identify an institution. A person registering with an address at one of '
    'these, once the address is verified, joins that institution with no roles and no access '
    'until an administrator there grants some. Written by the platform operator only.';

COMMENT ON COLUMN tenant_email_domain.domain IS
    'Lower-cased, no at-sign, no leading dot. Matched against the part of a verified email '
    'address after the at-sign, exactly and not as a suffix: a subdomain is a different domain '
    'and matching it loosely would let mail.vodacom.cd.attacker.com in.';
