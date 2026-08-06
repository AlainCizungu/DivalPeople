-- Employees and their employment contracts.
--
-- This is the most sensitive table in the platform: names, dates of birth and national
-- identifiers for real people. Row-level security applies as everywhere else, and the columns
-- flagged below are candidates for field-level encryption once a key service is in place
-- (see docs/SECURITY_MODEL.md).

CREATE TABLE employee (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID         NOT NULL REFERENCES tenant (id),

    -- The number payroll and the customer's own paperwork already use. Unique per tenant, not
    -- globally: two employers legitimately both have an "EMP-001".
    employee_number  VARCHAR(50)  NOT NULL,

    first_name       VARCHAR(150) NOT NULL,
    last_name        VARCHAR(150) NOT NULL,
    -- What the person is actually called, which is often not their legal first name.
    preferred_name   VARCHAR(150),

    date_of_birth    DATE,                    -- PII
    national_id      VARCHAR(100),            -- PII
    personal_email   VARCHAR(320),            -- PII
    phone            VARCHAR(40),             -- PII

    -- Where they sit in the organisation, and who they report to. The manager reference is what
    -- makes reporting lines: it is a chain through employees, not a separate structure.
    org_unit_id      UUID REFERENCES org_unit (id),
    manager_id       UUID REFERENCES employee (id),

    -- The login, when there is one. Field staff and workers without a company account are
    -- employees with no user record at all, so this is nullable by design.
    user_account_id  UUID REFERENCES user_account (id),

    status           VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    hire_date        DATE         NOT NULL,
    termination_date DATE,

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version          BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT employee_status_valid CHECK (
        status IN ('ACTIVE', 'ON_LEAVE', 'SUSPENDED', 'TERMINATED')
    ),
    CONSTRAINT employee_not_own_manager CHECK (manager_id IS DISTINCT FROM id),
    CONSTRAINT employee_termination_after_hire CHECK (
        termination_date IS NULL OR termination_date >= hire_date
    ),
    -- A terminated employee without a leaving date is a record nobody can reason about.
    CONSTRAINT employee_terminated_has_date CHECK (
        status <> 'TERMINATED' OR termination_date IS NOT NULL
    ),
    CONSTRAINT uq_employee_number UNIQUE (tenant_id, employee_number)
);

CREATE INDEX idx_employee_tenant ON employee (tenant_id, last_name, first_name);
CREATE INDEX idx_employee_org_unit ON employee (tenant_id, org_unit_id);
CREATE INDEX idx_employee_manager ON employee (tenant_id, manager_id);

-- One login belongs to at most one employee. Partial, because most employees have no login and
-- NULLs must not collide.
CREATE UNIQUE INDEX uq_employee_user_account ON employee (tenant_id, user_account_id)
    WHERE user_account_id IS NOT NULL;

ALTER TABLE employee ENABLE ROW LEVEL SECURITY;

CREATE POLICY employee_tenant_isolation ON employee
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE ON employee TO dip_app;


-- ---------------------------------------------------------------------------
-- Employment contracts.
--
-- Separate from the employee because a person accumulates several over time — a fixed term that
-- becomes permanent, a promotion, a renewal. Keeping them apart is what makes job history
-- possible instead of overwriting the past.
-- ---------------------------------------------------------------------------
CREATE TABLE employment_contract (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL REFERENCES tenant (id),
    employee_id        UUID         NOT NULL REFERENCES employee (id),

    contract_type      VARCHAR(30)  NOT NULL,
    job_title          VARCHAR(200) NOT NULL,
    org_unit_id        UUID REFERENCES org_unit (id),

    start_date         DATE         NOT NULL,
    end_date           DATE,
    probation_end_date DATE,

    status             VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',

    -- When the expiry alert last went out, so a daily scan does not re-notify every morning.
    expiry_notified_at TIMESTAMPTZ,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version            BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT contract_type_valid CHECK (
        contract_type IN ('PERMANENT', 'FIXED_TERM', 'PART_TIME', 'INTERNSHIP', 'CONSULTANT')
    ),
    CONSTRAINT contract_status_valid CHECK (
        status IN ('DRAFT', 'ACTIVE', 'ENDED', 'TERMINATED')
    ),
    CONSTRAINT contract_end_after_start CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT contract_probation_within_term CHECK (
        probation_end_date IS NULL OR probation_end_date >= start_date
    ),
    -- A fixed-term contract with no end date is a permanent contract wearing the wrong label,
    -- and it would never appear in an expiry scan.
    CONSTRAINT contract_fixed_term_has_end CHECK (
        contract_type <> 'FIXED_TERM' OR end_date IS NOT NULL
    )
);

CREATE INDEX idx_contract_employee ON employment_contract (tenant_id, employee_id, start_date DESC);

-- Drives the expiry scan: dated contracts that are still running.
CREATE INDEX idx_contract_expiry ON employment_contract (tenant_id, end_date)
    WHERE status = 'ACTIVE' AND end_date IS NOT NULL;

-- One active contract per person at a time. Concurrent engagements are real but rare, and
-- allowing them silently makes "what is this person's job title" unanswerable.
CREATE UNIQUE INDEX uq_contract_one_active ON employment_contract (tenant_id, employee_id)
    WHERE status = 'ACTIVE';

ALTER TABLE employment_contract ENABLE ROW LEVEL SECURITY;

CREATE POLICY employment_contract_tenant_isolation ON employment_contract
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE ON employment_contract TO dip_app;
