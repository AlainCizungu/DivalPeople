-- Learning: courses, enrolments, and the certifications that come out of them.
--
-- The reason this module earns its place is not the catalogue. It is the question "who is not
-- allowed to climb a tower next week", which is a compliance question with a legal answer, and
-- which needs three things a course list alone cannot give: what is mandatory, who has completed
-- it, and whose certificate has since lapsed.
--
-- A failed attempt is kept. Deleting it and letting somebody re-enrol would mean the record could
-- not distinguish "passed first time" from "passed on the fourth attempt", which is exactly the
-- distinction an investigation after an incident asks about.

CREATE TABLE course (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID          NOT NULL REFERENCES tenant (id),

    code            VARCHAR(50)   NOT NULL,
    title           VARCHAR(200)  NOT NULL,
    description     VARCHAR(4000),

    -- Who delivers it. Free text: half of these are external bodies whose names change.
    provider        VARCHAR(200),
    delivery_mode   VARCHAR(20)   NOT NULL DEFAULT 'ONLINE',
    duration_minutes INT,

    -- Everybody must hold this one. The flag is what makes the compliance question answerable
    -- without a separate requirements table, which is deliberately deferred: per-role
    -- requirements are a real need but a bigger design, and guessing at it now would be worse
    -- than a flag that is honest about its scope.
    mandatory       BOOLEAN       NOT NULL DEFAULT FALSE,

    -- How long a pass stays valid. Null means it never expires; anything else drives the sweep.
    validity_months INT,

    -- A pass mark, where the course has one.
    pass_score      INT,

    active          BOOLEAN       NOT NULL DEFAULT TRUE,

    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version         BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT course_delivery_valid CHECK (
        delivery_mode IN ('ONLINE', 'CLASSROOM', 'ON_THE_JOB', 'EXTERNAL')
    ),
    CONSTRAINT course_duration_positive CHECK (duration_minutes IS NULL OR duration_minutes > 0),
    CONSTRAINT course_validity_positive CHECK (validity_months IS NULL OR validity_months > 0),
    CONSTRAINT course_pass_score_valid CHECK (
        pass_score IS NULL OR pass_score BETWEEN 0 AND 100
    ),
    CONSTRAINT uq_course_code UNIQUE (tenant_id, code)
);

ALTER TABLE course ENABLE ROW LEVEL SECURITY;
CREATE POLICY course_tenant_isolation ON course
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON course TO dip_app;

CREATE TABLE course_enrolment (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenant (id),
    employee_id         UUID        NOT NULL REFERENCES employee (id),
    course_id           UUID        NOT NULL REFERENCES course (id),

    status              VARCHAR(20) NOT NULL DEFAULT 'ENROLLED',

    enrolled_on         DATE        NOT NULL DEFAULT CURRENT_DATE,
    started_at          TIMESTAMPTZ,
    completed_on        DATE,
    score               INT,

    -- Computed from the course's validity at completion, and then left alone. Shortening the
    -- validity period next year must not retrospectively invalidate a certificate somebody
    -- already holds.
    expires_on          DATE,

    -- The certificate itself, where there is one. Lives in stored_file with its own access rules.
    certificate_file_id UUID REFERENCES stored_file (id),

    notes               VARCHAR(2000),

    -- When the expiry alert last went out, so the daily sweep does not re-notify every morning.
    expiry_notified_at  TIMESTAMPTZ,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version             BIGINT      NOT NULL DEFAULT 0,

    CONSTRAINT enrolment_status_valid CHECK (
        status IN ('ENROLLED', 'IN_PROGRESS', 'COMPLETED', 'FAILED', 'WITHDRAWN', 'EXPIRED')
    ),
    CONSTRAINT enrolment_score_valid CHECK (score IS NULL OR score BETWEEN 0 AND 100),
    CONSTRAINT enrolment_completed_has_date CHECK (
        status NOT IN ('COMPLETED', 'FAILED', 'EXPIRED') OR completed_on IS NOT NULL
    ),
    CONSTRAINT enrolment_completed_after_enrolled CHECK (
        completed_on IS NULL OR completed_on >= enrolled_on
    ),
    CONSTRAINT enrolment_expiry_after_completion CHECK (
        expires_on IS NULL OR completed_on IS NULL OR expires_on > completed_on
    )
);

-- One live enrolment per person per course. A failed or expired one stays on the record and does
-- not block a fresh attempt, which is the whole reason this is a partial index rather than a
-- plain unique constraint.
CREATE UNIQUE INDEX uq_enrolment_one_live
    ON course_enrolment (tenant_id, employee_id, course_id)
    WHERE status IN ('ENROLLED', 'IN_PROGRESS');

CREATE INDEX idx_enrolment_employee
    ON course_enrolment (tenant_id, employee_id, status);

-- Drives the expiry sweep and the "whose certificate has lapsed" question.
CREATE INDEX idx_enrolment_expiry ON course_enrolment (tenant_id, expires_on)
    WHERE status = 'COMPLETED' AND expires_on IS NOT NULL;

ALTER TABLE course_enrolment ENABLE ROW LEVEL SECURITY;
CREATE POLICY course_enrolment_tenant_isolation ON course_enrolment
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON course_enrolment TO dip_app;
