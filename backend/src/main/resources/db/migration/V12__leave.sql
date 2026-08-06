-- Leave: entitlements, balances, requests and approvals.
--
-- Two decisions shape this file.
--
-- First, a balance is kept alongside an append-only ledger. The balance is the number the
-- overdraft check enforces against, so a request does not have to sum a year of history under a
-- lock. The ledger is why it is that number: "you have 12.5 days" is not an answer anybody can
-- argue with, and leave is argued with often. Every movement writes an entry.
--
-- Second, days are reserved when a request is submitted, not when it is approved. Two pending
-- requests that each fit the balance can otherwise both be approved into an overdraft, and the
-- person finds out months later.

CREATE TABLE leave_type (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID          NOT NULL REFERENCES tenant (id),

    code                 VARCHAR(50)   NOT NULL,
    name                 VARCHAR(200)  NOT NULL,

    -- Unpaid leave still consumes a balance and still needs approval; what differs is payroll.
    paid                 BOOLEAN       NOT NULL DEFAULT TRUE,

    -- ANNUAL_GRANT: the whole entitlement lands at the start of the year.
    -- MONTHLY_ACCRUAL: it builds up month by month, which is what most contracts actually say.
    accrual_method       VARCHAR(20)   NOT NULL DEFAULT 'ANNUAL_GRANT',
    entitlement_days     NUMERIC(5, 2) NOT NULL DEFAULT 0,

    -- Days that survive into next year. Anything above this lapses at year end.
    carryover_max_days   NUMERIC(5, 2) NOT NULL DEFAULT 0,

    -- Sick leave beyond a few days usually needs a certificate. Null means never.
    document_after_days  NUMERIC(5, 2),

    allows_half_day      BOOLEAN       NOT NULL DEFAULT TRUE,

    -- Some leave is an entitlement that cannot be refused and can legitimately go negative:
    -- statutory sick leave in most jurisdictions, for instance.
    allows_negative      BOOLEAN       NOT NULL DEFAULT FALSE,

    active               BOOLEAN       NOT NULL DEFAULT TRUE,

    created_at           TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version              BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT leave_type_accrual_valid CHECK (
        accrual_method IN ('ANNUAL_GRANT', 'MONTHLY_ACCRUAL')
    ),
    CONSTRAINT leave_type_entitlement_positive CHECK (entitlement_days >= 0),
    CONSTRAINT leave_type_carryover_positive CHECK (carryover_max_days >= 0),
    CONSTRAINT leave_type_document_positive CHECK (
        document_after_days IS NULL OR document_after_days > 0
    ),
    CONSTRAINT uq_leave_type_code UNIQUE (tenant_id, code)
);

ALTER TABLE leave_type ENABLE ROW LEVEL SECURITY;
CREATE POLICY leave_type_tenant_isolation ON leave_type
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON leave_type TO dip_app;

-- Days the office is closed. A leave request that charges people for a public holiday is a
-- system quietly taking days from them, which is the kind of bug nobody reports and everybody
-- resents.
CREATE TABLE public_holiday (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenant (id),

    holiday_date DATE         NOT NULL,
    name         VARCHAR(200) NOT NULL,

    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version      BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT uq_public_holiday UNIQUE (tenant_id, holiday_date)
);

CREATE INDEX idx_public_holiday_date ON public_holiday (tenant_id, holiday_date);

ALTER TABLE public_holiday ENABLE ROW LEVEL SECURITY;
CREATE POLICY public_holiday_tenant_isolation ON public_holiday
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON public_holiday TO dip_app;

CREATE TABLE leave_balance (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID          NOT NULL REFERENCES tenant (id),
    employee_id     UUID          NOT NULL REFERENCES employee (id),
    leave_type_id   UUID          NOT NULL REFERENCES leave_type (id),

    -- Leave years are calendar years here. A tenant whose year starts in April needs a policy
    -- field before this can serve them, and that is a deliberate gap rather than an oversight.
    leave_year      INT           NOT NULL,

    -- What carried in from last year, already capped.
    opening_days    NUMERIC(6, 2) NOT NULL DEFAULT 0,
    -- Granted or accrued during the year.
    accrued_days    NUMERIC(6, 2) NOT NULL DEFAULT 0,
    -- Approved and consumed.
    taken_days      NUMERIC(6, 2) NOT NULL DEFAULT 0,
    -- Submitted and awaiting a decision. Held here so a second request cannot spend them twice.
    pending_days    NUMERIC(6, 2) NOT NULL DEFAULT 0,
    -- Corrections, TOIL, goodwill. Signed on purpose.
    adjustment_days NUMERIC(6, 2) NOT NULL DEFAULT 0,

    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version         BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT balance_taken_positive CHECK (taken_days >= 0),
    CONSTRAINT balance_pending_positive CHECK (pending_days >= 0),
    CONSTRAINT balance_accrued_positive CHECK (accrued_days >= 0),
    CONSTRAINT uq_leave_balance UNIQUE (tenant_id, employee_id, leave_type_id, leave_year)
);

CREATE INDEX idx_leave_balance_employee
    ON leave_balance (tenant_id, employee_id, leave_year);

ALTER TABLE leave_balance ENABLE ROW LEVEL SECURITY;
CREATE POLICY leave_balance_tenant_isolation ON leave_balance
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON leave_balance TO dip_app;

CREATE TABLE leave_request (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL REFERENCES tenant (id),
    employee_id       UUID          NOT NULL REFERENCES employee (id),
    leave_type_id     UUID          NOT NULL REFERENCES leave_type (id),

    start_date        DATE          NOT NULL,
    end_date          DATE          NOT NULL,

    -- Half days at either end. A day-granular system forces people to burn a whole day on a
    -- morning appointment, so they stop recording it and the data goes quietly wrong.
    half_day_start    BOOLEAN       NOT NULL DEFAULT FALSE,
    half_day_end      BOOLEAN       NOT NULL DEFAULT FALSE,

    -- Working days, computed at submission with the holiday calendar of that moment. Stored
    -- rather than recomputed: a holiday declared later must not silently change what somebody
    -- was charged.
    days              NUMERIC(5, 2) NOT NULL,
    leave_year        INT           NOT NULL,

    reason            VARCHAR(2000),
    -- Sick notes and the like. The file lives in stored_file with its own access rules.
    document_id       UUID REFERENCES stored_file (id),

    status            VARCHAR(20)   NOT NULL DEFAULT 'SUBMITTED',
    approver_id       UUID REFERENCES employee (id),
    decided_at        TIMESTAMPTZ,
    decision_notes    VARCHAR(2000),

    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version           BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT leave_request_status_valid CHECK (
        status IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'CANCELLED')
    ),
    CONSTRAINT leave_request_dates_ordered CHECK (end_date >= start_date),
    CONSTRAINT leave_request_days_positive CHECK (days > 0),
    -- A decision with no author and no timestamp cannot be appealed against.
    CONSTRAINT leave_request_decision_recorded CHECK (
        status IN ('SUBMITTED', 'CANCELLED') OR decided_at IS NOT NULL
    ),
    -- Refusing somebody's leave without saying why is the kind of silence that ends up in front
    -- of a labour inspector.
    CONSTRAINT leave_request_rejection_has_reason CHECK (
        status <> 'REJECTED' OR decision_notes IS NOT NULL
    )
);

CREATE INDEX idx_leave_request_employee
    ON leave_request (tenant_id, employee_id, start_date DESC);

-- Drives the approver's queue and the "who is off this week" view.
CREATE INDEX idx_leave_request_pending ON leave_request (tenant_id, status, start_date)
    WHERE status IN ('SUBMITTED', 'APPROVED');

ALTER TABLE leave_request ENABLE ROW LEVEL SECURITY;
CREATE POLICY leave_request_tenant_isolation ON leave_request
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON leave_request TO dip_app;

-- Why the balance is what it is.
--
-- Append-only by intent: there is no UPDATE grant below, and corrections are made by writing a
-- further entry rather than editing history. A leave balance that can be quietly rewritten is
-- worth nothing in a dispute.
CREATE TABLE leave_ledger_entry (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID          NOT NULL REFERENCES tenant (id),
    balance_id    UUID          NOT NULL REFERENCES leave_balance (id),

    entry_type    VARCHAR(20)   NOT NULL,
    -- Signed. Positive adds to what somebody has, negative spends it.
    days          NUMERIC(6, 2) NOT NULL,

    -- The request that caused it, when there was one.
    request_id    UUID REFERENCES leave_request (id),
    reason        VARCHAR(500),
    actor_id      UUID,

    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version       BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT ledger_entry_type_valid CHECK (
        entry_type IN ('OPENING', 'ACCRUAL', 'GRANT', 'TAKEN', 'RETURNED',
                       'ADJUSTMENT', 'LAPSED')
    ),
    CONSTRAINT ledger_entry_days_nonzero CHECK (days <> 0)
);

CREATE INDEX idx_ledger_balance ON leave_ledger_entry (tenant_id, balance_id, created_at);

ALTER TABLE leave_ledger_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY leave_ledger_entry_tenant_isolation ON leave_ledger_entry
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
-- No UPDATE, no DELETE. The ledger is written once.
GRANT SELECT, INSERT ON leave_ledger_entry TO dip_app;
