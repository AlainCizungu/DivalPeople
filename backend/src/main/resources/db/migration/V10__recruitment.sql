-- Recruitment: requisitions, candidates, applications, interviews and offers.
--
-- Candidates are the one group here who are not employees and have no relationship with the
-- employer at all. Most will be rejected, and their data should not be kept indefinitely on the
-- chance they apply again. Retention rules per country are required before production; the
-- schema keeps candidate data separable so it can be erased without touching hiring statistics.

CREATE TABLE job_requisition (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL REFERENCES tenant (id),

    -- The reference recruiters and hiring managers quote to each other.
    requisition_number VARCHAR(50)  NOT NULL,

    title             VARCHAR(200)  NOT NULL,
    org_unit_id       UUID REFERENCES org_unit (id),

    -- How many people this requisition is authorised to hire. More than one is common for
    -- field roles, and filling it should close the requisition, not the first hire.
    headcount         INT           NOT NULL DEFAULT 1,
    filled_count      INT           NOT NULL DEFAULT 0,

    contract_type     VARCHAR(30)   NOT NULL,
    description       VARCHAR(4000),
    target_start_date DATE,

    status            VARCHAR(30)   NOT NULL DEFAULT 'DRAFT',

    -- Who asked, who approved. Budget approval is the point of a requisition.
    requested_by      UUID REFERENCES employee (id),
    approved_by       UUID REFERENCES employee (id),
    approved_at       TIMESTAMPTZ,

    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version           BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT requisition_status_valid CHECK (
        status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'OPEN', 'ON_HOLD', 'FILLED', 'CANCELLED')
    ),
    CONSTRAINT requisition_contract_type_valid CHECK (
        contract_type IN ('PERMANENT', 'FIXED_TERM', 'PART_TIME', 'INTERNSHIP', 'CONSULTANT')
    ),
    CONSTRAINT requisition_headcount_positive CHECK (headcount >= 1),
    CONSTRAINT requisition_filled_within_headcount CHECK (
        filled_count >= 0 AND filled_count <= headcount
    ),
    -- An approved requisition without an approver is an unapproved requisition.
    CONSTRAINT requisition_approved_has_approver CHECK (
        status NOT IN ('APPROVED', 'OPEN', 'FILLED') OR approved_at IS NOT NULL
    ),
    CONSTRAINT uq_requisition_number UNIQUE (tenant_id, requisition_number)
);

CREATE INDEX idx_requisition_tenant ON job_requisition (tenant_id, status);

ALTER TABLE job_requisition ENABLE ROW LEVEL SECURITY;
CREATE POLICY job_requisition_tenant_isolation ON job_requisition
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON job_requisition TO dip_app;


-- ---------------------------------------------------------------------------
-- Candidates.
--
-- A person, not an application. Someone who applies for three roles is one candidate with three
-- applications, which is what makes "have we seen them before" answerable.
-- ---------------------------------------------------------------------------
CREATE TABLE candidate (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID          NOT NULL REFERENCES tenant (id),

    first_name    VARCHAR(150)  NOT NULL,
    last_name     VARCHAR(150)  NOT NULL,
    email         VARCHAR(320)  NOT NULL,
    phone         VARCHAR(40),

    source        VARCHAR(30)   NOT NULL DEFAULT 'DIRECT',
    notes         VARCHAR(4000),

    -- Set when they are hired, linking the candidate to the person they became.
    employee_id   UUID REFERENCES employee (id),

    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version       BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT candidate_source_valid CHECK (
        source IN ('DIRECT', 'REFERRAL', 'JOB_BOARD', 'AGENCY', 'INTERNAL', 'OTHER')
    ),
    -- Email identifies the person, so the same address is the same candidate.
    CONSTRAINT uq_candidate_email UNIQUE (tenant_id, email)
);

CREATE INDEX idx_candidate_tenant ON candidate (tenant_id, last_name, first_name);

ALTER TABLE candidate ENABLE ROW LEVEL SECURITY;
CREATE POLICY candidate_tenant_isolation ON candidate
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON candidate TO dip_app;


-- ---------------------------------------------------------------------------
-- Applications: one candidate against one requisition.
-- ---------------------------------------------------------------------------
CREATE TABLE job_application (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID          NOT NULL REFERENCES tenant (id),
    requisition_id   UUID          NOT NULL REFERENCES job_requisition (id),
    candidate_id     UUID          NOT NULL REFERENCES candidate (id),

    status           VARCHAR(30)   NOT NULL DEFAULT 'APPLIED',
    applied_on       DATE          NOT NULL DEFAULT CURRENT_DATE,

    -- Recorded for every rejection. A pipeline that cannot say why people were turned down
    -- cannot be reviewed for bias.
    outcome_reason   VARCHAR(1000),
    decided_at       TIMESTAMPTZ,

    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version          BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT application_status_valid CHECK (
        status IN ('APPLIED', 'SCREENING', 'INTERVIEWING', 'OFFER', 'HIRED', 'REJECTED', 'WITHDRAWN')
    ),
    CONSTRAINT uq_application_candidate_requisition UNIQUE (tenant_id, requisition_id, candidate_id)
);

CREATE INDEX idx_application_requisition ON job_application (tenant_id, requisition_id, status);
CREATE INDEX idx_application_candidate ON job_application (tenant_id, candidate_id);

ALTER TABLE job_application ENABLE ROW LEVEL SECURITY;
CREATE POLICY job_application_tenant_isolation ON job_application
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON job_application TO dip_app;


-- ---------------------------------------------------------------------------
-- Interviews.
--
-- One row per interviewer: a panel is several rows at the same time, which lets each person
-- record their own view instead of one summary standing in for everybody.
-- ---------------------------------------------------------------------------
CREATE TABLE interview (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL REFERENCES tenant (id),
    application_id UUID         NOT NULL REFERENCES job_application (id),

    stage          VARCHAR(30)  NOT NULL,
    mode           VARCHAR(20)  NOT NULL DEFAULT 'VIDEO',
    scheduled_at   TIMESTAMPTZ  NOT NULL,
    interviewer_id UUID REFERENCES employee (id),

    status         VARCHAR(20)  NOT NULL DEFAULT 'SCHEDULED',

    -- Filled in afterwards. Kept on the interview so a recommendation always has its context.
    recommendation VARCHAR(20),
    score          INT,
    comments       VARCHAR(4000),
    submitted_at   TIMESTAMPTZ,

    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version        BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT interview_stage_valid CHECK (
        stage IN ('SCREENING', 'TECHNICAL', 'PANEL', 'FINAL')
    ),
    CONSTRAINT interview_mode_valid CHECK (mode IN ('ON_SITE', 'PHONE', 'VIDEO')),
    CONSTRAINT interview_status_valid CHECK (
        status IN ('SCHEDULED', 'COMPLETED', 'CANCELLED', 'NO_SHOW')
    ),
    CONSTRAINT interview_recommendation_valid CHECK (
        recommendation IS NULL
        OR recommendation IN ('STRONG_YES', 'YES', 'NO', 'STRONG_NO')
    ),
    CONSTRAINT interview_score_range CHECK (score IS NULL OR (score >= 1 AND score <= 5))
);

CREATE INDEX idx_interview_application ON interview (tenant_id, application_id, scheduled_at);
CREATE INDEX idx_interview_interviewer ON interview (tenant_id, interviewer_id, scheduled_at);

ALTER TABLE interview ENABLE ROW LEVEL SECURITY;
CREATE POLICY interview_tenant_isolation ON interview
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON interview TO dip_app;


-- ---------------------------------------------------------------------------
-- Offers.
--
-- Carries pay, which makes it the most access-sensitive table in recruitment.
-- ---------------------------------------------------------------------------
CREATE TABLE job_offer (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID           NOT NULL REFERENCES tenant (id),
    application_id      UUID           NOT NULL REFERENCES job_application (id),

    job_title           VARCHAR(200)   NOT NULL,
    org_unit_id         UUID REFERENCES org_unit (id),
    contract_type       VARCHAR(30)    NOT NULL,

    salary_amount       NUMERIC(18, 2),
    salary_currency     VARCHAR(3),

    proposed_start_date DATE           NOT NULL,
    -- Only meaningful for fixed-term work, but the offer must carry it: a candidate who accepts
    -- an open-ended offer and receives a six-month contract was not told the truth.
    proposed_end_date   DATE,
    expires_on          DATE,

    status              VARCHAR(20)    NOT NULL DEFAULT 'DRAFT',
    sent_at             TIMESTAMPTZ,
    responded_at        TIMESTAMPTZ,

    created_at          TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    version             BIGINT         NOT NULL DEFAULT 0,

    CONSTRAINT offer_status_valid CHECK (
        status IN ('DRAFT', 'SENT', 'ACCEPTED', 'DECLINED', 'WITHDRAWN', 'EXPIRED')
    ),
    CONSTRAINT offer_contract_type_valid CHECK (
        contract_type IN ('PERMANENT', 'FIXED_TERM', 'PART_TIME', 'INTERNSHIP', 'CONSULTANT')
    ),
    CONSTRAINT offer_salary_positive CHECK (salary_amount IS NULL OR salary_amount > 0),
    -- An amount without a currency is not a salary.
    CONSTRAINT offer_salary_has_currency CHECK (
        salary_amount IS NULL OR salary_currency IS NOT NULL
    ),
    CONSTRAINT offer_dates_ordered CHECK (
        proposed_end_date IS NULL OR proposed_end_date >= proposed_start_date
    ),
    -- Mirrors the same rule on employment_contract, so an offer cannot be accepted into a
    -- contract the contract table would then refuse.
    CONSTRAINT offer_fixed_term_has_end CHECK (
        contract_type <> 'FIXED_TERM' OR proposed_end_date IS NOT NULL
    )
);

CREATE INDEX idx_offer_application ON job_offer (tenant_id, application_id);

-- One live offer per application. Two outstanding offers for the same role is how a candidate
-- ends up with contradictory paperwork.
CREATE UNIQUE INDEX uq_offer_one_open ON job_offer (tenant_id, application_id)
    WHERE status IN ('DRAFT', 'SENT');

ALTER TABLE job_offer ENABLE ROW LEVEL SECURITY;
CREATE POLICY job_offer_tenant_isolation ON job_offer
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON job_offer TO dip_app;
