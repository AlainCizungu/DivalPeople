-- Payroll preparation.
--
-- Read this before changing anything here.
--
-- WHAT THIS IS NOT. There is no statutory tax calculation in this module, and adding one
-- casually would be the most dangerous thing anybody could do to this codebase. Income tax,
-- social security and their thresholds are jurisdiction-specific, change by decree, and getting
-- them wrong means underpaying people or under-remitting to a revenue authority — both of which
-- carry personal liability for somebody. What this module provides is the *mechanism*: deduction
-- components with a rate, applied in a defined order, producing a payslip that reconciles. The
-- rates themselves are configuration a qualified accountant enters and verifies. See
-- docs/PAYROLL_SCOPE.md.
--
-- THREE RULES THIS SCHEMA ENFORCES.
--
-- Compensation is effective-dated and never overwritten. A payroll run for March must use the
-- salary that was in force in March, however many raises have happened since. A row that gets
-- updated in place makes every historical payslip unexplainable.
--
-- A payslip is a snapshot. Its lines carry the component's code and name as text rather than a
-- foreign key, so renaming "Transport allowance" next year does not rewrite what somebody was
-- told they were paid last year.
--
-- The totals are the sum of the lines. Nothing computes gross or net independently, which is what
-- makes a payslip reconcile by construction rather than by hope.

CREATE TABLE compensation (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID           NOT NULL REFERENCES tenant (id),
    employee_id    UUID           NOT NULL REFERENCES employee (id),

    -- The window this salary applies to. effective_to null means "still in force".
    effective_from DATE           NOT NULL,
    effective_to   DATE,

    base_amount    NUMERIC(18, 2) NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    pay_frequency  VARCHAR(20)    NOT NULL DEFAULT 'MONTHLY',

    -- Why it changed. A salary history where every entry says nothing is a history nobody can
    -- defend in a pay-equity review.
    reason         VARCHAR(500),

    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version        BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT compensation_amount_positive CHECK (base_amount > 0),
    CONSTRAINT compensation_frequency_valid CHECK (
        pay_frequency IN ('MONTHLY', 'FORTNIGHTLY', 'WEEKLY', 'DAILY', 'HOURLY')
    ),
    CONSTRAINT compensation_period_ordered CHECK (
        effective_to IS NULL OR effective_to >= effective_from
    )
);

-- One open-ended salary per person. Two rows with no end date is how somebody gets paid twice or
-- paid the wrong figure depending on which row the query happened to reach first.
CREATE UNIQUE INDEX uq_compensation_current
    ON compensation (tenant_id, employee_id)
    WHERE effective_to IS NULL;

CREATE INDEX idx_compensation_employee
    ON compensation (tenant_id, employee_id, effective_from DESC);

ALTER TABLE compensation ENABLE ROW LEVEL SECURITY;
CREATE POLICY compensation_tenant_isolation ON compensation
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON compensation TO dip_app;

-- Everything that is added to or taken off a payslip.
CREATE TABLE pay_component (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID           NOT NULL REFERENCES tenant (id),

    code           VARCHAR(50)    NOT NULL,
    name           VARCHAR(200)   NOT NULL,
    component_type VARCHAR(30)    NOT NULL,

    -- How the amount is arrived at. PERCENT_OF_BASE is the one statutory deductions use, which
    -- is why the rate lives here and not in code.
    calculation    VARCHAR(30)    NOT NULL DEFAULT 'FIXED',
    default_amount NUMERIC(18, 2),
    percentage     NUMERIC(7, 4),

    -- Whether this earning forms part of the base a percentage deduction is taken from. Getting
    -- this wrong is the quietest way to compute the wrong tax.
    taxable        BOOLEAN        NOT NULL DEFAULT TRUE,

    -- Lines are applied in this order, so a deduction that depends on an earlier one is
    -- deterministic rather than dependent on insertion order.
    sort_order     INT            NOT NULL DEFAULT 100,

    active         BOOLEAN        NOT NULL DEFAULT TRUE,

    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version        BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT component_type_valid CHECK (
        component_type IN ('EARNING', 'DEDUCTION', 'EMPLOYER_CONTRIBUTION')
    ),
    CONSTRAINT component_calculation_valid CHECK (
        calculation IN ('FIXED', 'PERCENT_OF_BASE', 'PERCENT_OF_GROSS', 'PER_HOUR', 'MANUAL')
    ),
    CONSTRAINT component_percentage_range CHECK (
        percentage IS NULL OR (percentage >= 0 AND percentage <= 100)
    ),
    CONSTRAINT component_amount_positive CHECK (
        default_amount IS NULL OR default_amount >= 0
    ),
    -- A percentage calculation with no percentage silently contributes nothing, which is worse
    -- than failing: the payslip still balances and the money is simply absent.
    CONSTRAINT component_percentage_present CHECK (
        calculation NOT IN ('PERCENT_OF_BASE', 'PERCENT_OF_GROSS') OR percentage IS NOT NULL
    ),
    CONSTRAINT uq_component_code UNIQUE (tenant_id, code)
);

ALTER TABLE pay_component ENABLE ROW LEVEL SECURITY;
CREATE POLICY pay_component_tenant_isolation ON pay_component
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON pay_component TO dip_app;

-- A component that applies to one person every period, with an optional override.
CREATE TABLE employee_pay_component (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID           NOT NULL REFERENCES tenant (id),
    employee_id    UUID           NOT NULL REFERENCES employee (id),
    component_id   UUID           NOT NULL REFERENCES pay_component (id),

    -- Effective-dated for the same reason compensation is: a benefit that started in June must
    -- not appear on May's payslip when it is reprinted.
    effective_from DATE           NOT NULL,
    effective_to   DATE,

    amount         NUMERIC(18, 2),
    percentage     NUMERIC(7, 4),
    notes          VARCHAR(500),

    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version        BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT employee_component_period_ordered CHECK (
        effective_to IS NULL OR effective_to >= effective_from
    ),
    CONSTRAINT employee_component_amount_positive CHECK (amount IS NULL OR amount >= 0),
    CONSTRAINT employee_component_percentage_range CHECK (
        percentage IS NULL OR (percentage >= 0 AND percentage <= 100)
    )
);

CREATE UNIQUE INDEX uq_employee_component_current
    ON employee_pay_component (tenant_id, employee_id, component_id)
    WHERE effective_to IS NULL;

CREATE INDEX idx_employee_component
    ON employee_pay_component (tenant_id, employee_id, effective_from DESC);

ALTER TABLE employee_pay_component ENABLE ROW LEVEL SECURITY;
CREATE POLICY employee_pay_component_tenant_isolation ON employee_pay_component
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON employee_pay_component TO dip_app;

CREATE TABLE payroll_period (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenant (id),

    name           VARCHAR(200) NOT NULL,
    period_start   DATE        NOT NULL,
    period_end     DATE        NOT NULL,
    -- When money actually leaves. Distinct from period_end: most payrolls pay in arrears.
    payment_date   DATE        NOT NULL,

    status         VARCHAR(20) NOT NULL DEFAULT 'DRAFT',

    calculated_at  TIMESTAMPTZ,
    approver_id    UUID REFERENCES employee (id),
    approved_at    TIMESTAMPTZ,
    paid_at        TIMESTAMPTZ,
    notes          VARCHAR(2000),

    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version        BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT period_status_valid CHECK (
        status IN ('DRAFT', 'CALCULATED', 'APPROVED', 'PAID', 'CANCELLED')
    ),
    CONSTRAINT period_dates_ordered CHECK (period_end >= period_start),
    CONSTRAINT period_approved_has_approver CHECK (
        status NOT IN ('APPROVED', 'PAID') OR (approver_id IS NOT NULL AND approved_at IS NOT NULL)
    ),
    CONSTRAINT period_paid_has_time CHECK (status <> 'PAID' OR paid_at IS NOT NULL),
    CONSTRAINT uq_period_start UNIQUE (tenant_id, period_start)
);

CREATE INDEX idx_period_status ON payroll_period (tenant_id, status, period_start DESC);

ALTER TABLE payroll_period ENABLE ROW LEVEL SECURITY;
CREATE POLICY payroll_period_tenant_isolation ON payroll_period
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON payroll_period TO dip_app;

CREATE TABLE payslip (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID           NOT NULL REFERENCES tenant (id),
    period_id          UUID           NOT NULL REFERENCES payroll_period (id),
    employee_id        UUID           NOT NULL REFERENCES employee (id),

    -- Snapshotted at calculation, not read live. A payslip must say what it said the day it was
    -- issued, whatever has changed about the person since.
    employee_number    VARCHAR(50)    NOT NULL,
    employee_name      VARCHAR(300)   NOT NULL,
    base_amount        NUMERIC(18, 2) NOT NULL,
    currency           VARCHAR(3)     NOT NULL,

    gross_earnings     NUMERIC(18, 2) NOT NULL DEFAULT 0,
    total_deductions   NUMERIC(18, 2) NOT NULL DEFAULT 0,
    employer_cost      NUMERIC(18, 2) NOT NULL DEFAULT 0,
    net_pay            NUMERIC(18, 2) NOT NULL DEFAULT 0,

    -- Carried from leave and attendance so the figures behind an absence deduction are visible on
    -- the payslip rather than only in another module.
    unpaid_leave_days  NUMERIC(6, 2)  NOT NULL DEFAULT 0,
    absent_minutes     INT            NOT NULL DEFAULT 0,
    overtime_minutes   INT            NOT NULL DEFAULT 0,

    created_at         TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version            BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT payslip_totals_positive CHECK (
        gross_earnings >= 0 AND total_deductions >= 0 AND employer_cost >= 0
    ),
    -- Net is gross less deductions, always. Anything else means the lines and the total disagree,
    -- which is the one failure a payslip must never have.
    CONSTRAINT payslip_reconciles CHECK (net_pay = gross_earnings - total_deductions),
    CONSTRAINT uq_payslip_per_period UNIQUE (tenant_id, period_id, employee_id)
);

CREATE INDEX idx_payslip_employee ON payslip (tenant_id, employee_id);

ALTER TABLE payslip ENABLE ROW LEVEL SECURITY;
CREATE POLICY payslip_tenant_isolation ON payslip
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON payslip TO dip_app;

CREATE TABLE payslip_line (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID           NOT NULL REFERENCES tenant (id),
    payslip_id     UUID           NOT NULL REFERENCES payslip (id),

    -- Copied, not referenced. Renaming a component next year must not rewrite what somebody was
    -- told they were paid last year.
    component_code VARCHAR(50)    NOT NULL,
    component_name VARCHAR(200)   NOT NULL,
    component_type VARCHAR(30)    NOT NULL,

    -- How this line was arrived at, kept so a query about a figure has an answer that does not
    -- require re-running the calculation.
    basis          VARCHAR(200),
    quantity       NUMERIC(12, 2),
    rate           NUMERIC(18, 4),
    amount         NUMERIC(18, 2) NOT NULL,

    sort_order     INT            NOT NULL DEFAULT 100,

    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version        BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT line_type_valid CHECK (
        component_type IN ('EARNING', 'DEDUCTION', 'EMPLOYER_CONTRIBUTION')
    ),
    CONSTRAINT line_amount_positive CHECK (amount >= 0)
);

CREATE INDEX idx_line_payslip ON payslip_line (tenant_id, payslip_id, sort_order);

ALTER TABLE payslip_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY payslip_line_tenant_isolation ON payslip_line
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON payslip_line TO dip_app;
