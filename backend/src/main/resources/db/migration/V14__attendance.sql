-- Attendance: when people were actually at work, and the timesheet that says so for a period.
--
-- Two things this file deliberately does not do.
--
-- It does not price overtime. Attendance records what happened; what an hour is worth is a
-- payroll decision, and putting a multiplier here would mean two systems disagreeing about pay
-- the first time a rate changed.
--
-- It does not overwrite. A correction supersedes the original entry and both stay visible.
-- Attendance is the record people are paid from and disciplined against, so "it always said
-- that" has to be answerable.

CREATE TABLE time_entry (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID        NOT NULL REFERENCES tenant (id),
    employee_id    UUID        NOT NULL REFERENCES employee (id),

    -- The day the shift is counted against, which is not always the calendar date of the clock-in:
    -- a night shift starting at 22:00 belongs to the day it started.
    work_date      DATE        NOT NULL,

    started_at     TIMESTAMPTZ NOT NULL,
    -- Null while somebody is still clocked in. That is the only legitimate null here.
    ended_at       TIMESTAMPTZ,

    -- Unpaid breaks, subtracted from the span. Held separately so the span still shows how long
    -- somebody was on site, which is what a safety or access question asks.
    break_minutes  INT         NOT NULL DEFAULT 0,

    source         VARCHAR(20) NOT NULL DEFAULT 'MANUAL',
    notes          VARCHAR(2000),

    -- A correction points at what it replaces. The original keeps its row and is marked
    -- superseded, so the history reads as a sequence rather than a single mutable truth.
    supersedes_id  UUID REFERENCES time_entry (id),
    superseded     BOOLEAN     NOT NULL DEFAULT FALSE,
    amend_reason   VARCHAR(500),

    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version        BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT time_entry_source_valid CHECK (
        source IN ('WEB', 'MOBILE', 'BIOMETRIC', 'IMPORT', 'MANUAL')
    ),
    CONSTRAINT time_entry_ends_after_start CHECK (ended_at IS NULL OR ended_at > started_at),
    CONSTRAINT time_entry_break_positive CHECK (break_minutes >= 0),
    -- A break longer than the shift would produce negative worked time and a negative payslip.
    CONSTRAINT time_entry_break_within_span CHECK (
        ended_at IS NULL
            OR break_minutes <= EXTRACT(EPOCH FROM (ended_at - started_at)) / 60
    ),
    -- An amendment with no explanation is indistinguishable from tampering.
    CONSTRAINT time_entry_amendment_has_reason CHECK (
        supersedes_id IS NULL OR amend_reason IS NOT NULL
    )
);

CREATE INDEX idx_time_entry_employee_date
    ON time_entry (tenant_id, employee_id, work_date);

-- Nobody is clocked in twice at once. Enforced here as well as in the service, because a double
-- clock-in is how somebody ends up paid twice for the same hour.
CREATE UNIQUE INDEX uq_time_entry_one_open
    ON time_entry (tenant_id, employee_id)
    WHERE ended_at IS NULL AND superseded = FALSE;

ALTER TABLE time_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY time_entry_tenant_isolation ON time_entry
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON time_entry TO dip_app;

-- A period, signed off. Payroll needs something a human agreed to, not a live query whose answer
-- changes after the run.
CREATE TABLE timesheet (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenant (id),
    employee_id     UUID        NOT NULL REFERENCES employee (id),

    period_start    DATE        NOT NULL,
    period_end      DATE        NOT NULL,

    -- All frozen at submission. Recomputing on read would mean a payslip and a screen quietly
    -- disagreeing once somebody amended an entry.
    worked_minutes   INT        NOT NULL DEFAULT 0,
    expected_minutes INT        NOT NULL DEFAULT 0,
    leave_minutes    INT        NOT NULL DEFAULT 0,
    holiday_minutes  INT        NOT NULL DEFAULT 0,
    overtime_minutes INT        NOT NULL DEFAULT 0,
    absent_minutes   INT        NOT NULL DEFAULT 0,

    status          VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    submitted_at    TIMESTAMPTZ,
    approver_id     UUID REFERENCES employee (id),
    decided_at      TIMESTAMPTZ,
    decision_notes  VARCHAR(2000),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version         BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT timesheet_status_valid CHECK (
        status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED')
    ),
    CONSTRAINT timesheet_period_ordered CHECK (period_end >= period_start),
    CONSTRAINT timesheet_minutes_positive CHECK (
        worked_minutes >= 0 AND expected_minutes >= 0 AND leave_minutes >= 0
            AND holiday_minutes >= 0 AND overtime_minutes >= 0 AND absent_minutes >= 0
    ),
    CONSTRAINT timesheet_decision_recorded CHECK (
        status NOT IN ('APPROVED', 'REJECTED') OR decided_at IS NOT NULL
    ),
    -- Refusing somebody's timesheet decides what they are paid. It has to say why.
    CONSTRAINT timesheet_rejection_has_reason CHECK (
        status <> 'REJECTED' OR decision_notes IS NOT NULL
    ),
    CONSTRAINT uq_timesheet_period UNIQUE (tenant_id, employee_id, period_start)
);

CREATE INDEX idx_timesheet_employee ON timesheet (tenant_id, employee_id, period_start DESC);

CREATE INDEX idx_timesheet_pending ON timesheet (tenant_id, status, period_start)
    WHERE status = 'SUBMITTED';

ALTER TABLE timesheet ENABLE ROW LEVEL SECURITY;
CREATE POLICY timesheet_tenant_isolation ON timesheet
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON timesheet TO dip_app;
