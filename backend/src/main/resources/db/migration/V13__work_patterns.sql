-- Work patterns: how much of a week somebody actually works.
--
-- Until now the working week was configuration, one setting for the whole tenant, so a person on
-- a four-day week was charged five days for a week off and accrued leave as though they were
-- full time. That is not a rounding error — it is a quarter of their entitlement, taken from
-- them by a default.
--
-- A pattern gives each day a fraction: 1 for a full day, 0.5 for a half day, 0 for a day not
-- worked. Fractions rather than a set of days, because a four-and-a-half day week is common and
-- a boolean cannot express it.

CREATE TABLE work_pattern (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id  UUID          NOT NULL REFERENCES tenant (id),

    code       VARCHAR(50)   NOT NULL,
    name       VARCHAR(200)  NOT NULL,

    monday     NUMERIC(3, 2) NOT NULL DEFAULT 0,
    tuesday    NUMERIC(3, 2) NOT NULL DEFAULT 0,
    wednesday  NUMERIC(3, 2) NOT NULL DEFAULT 0,
    thursday   NUMERIC(3, 2) NOT NULL DEFAULT 0,
    friday     NUMERIC(3, 2) NOT NULL DEFAULT 0,
    saturday   NUMERIC(3, 2) NOT NULL DEFAULT 0,
    sunday     NUMERIC(3, 2) NOT NULL DEFAULT 0,

    active     BOOLEAN       NOT NULL DEFAULT TRUE,

    created_at TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version    BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT pattern_fractions_valid CHECK (
        monday BETWEEN 0 AND 1 AND tuesday BETWEEN 0 AND 1 AND wednesday BETWEEN 0 AND 1
            AND thursday BETWEEN 0 AND 1 AND friday BETWEEN 0 AND 1
            AND saturday BETWEEN 0 AND 1 AND sunday BETWEEN 0 AND 1
    ),
    -- A pattern with no working days would make every leave request cost nothing and every
    -- accrual zero. That is not a part-time contract, it is a broken row.
    CONSTRAINT pattern_has_a_working_day CHECK (
        monday + tuesday + wednesday + thursday + friday + saturday + sunday > 0
    ),
    CONSTRAINT uq_work_pattern_code UNIQUE (tenant_id, code)
);

ALTER TABLE work_pattern ENABLE ROW LEVEL SECURITY;
CREATE POLICY work_pattern_tenant_isolation ON work_pattern
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON work_pattern TO dip_app;

-- Nullable, and null means full time on the tenant's configured working week. Making it
-- mandatory would have required inventing a pattern for every employee already on file, and a
-- migration that guesses at somebody's contract is worse than a null.
ALTER TABLE employee
    ADD COLUMN work_pattern_id UUID REFERENCES work_pattern (id);

CREATE INDEX idx_employee_work_pattern ON employee (tenant_id, work_pattern_id)
    WHERE work_pattern_id IS NOT NULL;
