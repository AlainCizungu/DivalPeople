-- Performance: goals, review cycles, reviews and feedback.
--
-- Three decisions shape this file, and all three are about who can see what, when.
--
-- First, a review is written blind. The employee's self-assessment and the manager's assessment
-- are both stored from the start, but neither is readable by the other side until both have been
-- submitted. A manager who reads the self-assessment first is anchored by it; an employee who can
-- see the manager's draft writes to it rather than about their year.
--
-- Second, a review has to be shared before it means anything. A rating that reaches a pay
-- decision without the person having read it is the thing employment tribunals are made of.
--
-- Third, acknowledgement is not agreement. They are separate columns because a signature saying
-- "I have read this" and one saying "I accept this" are different statements, and conflating them
-- takes away the only disagreement the record can hold.

CREATE TABLE review_cycle (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID          NOT NULL REFERENCES tenant (id),

    name           VARCHAR(200)  NOT NULL,
    period_start   DATE          NOT NULL,
    period_end     DATE          NOT NULL,

    -- When reviews are due. Distinct from period_end: a cycle covering the year to December is
    -- usually written in January.
    due_on         DATE,

    status         VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',

    created_at     TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version        BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT cycle_status_valid CHECK (
        status IN ('DRAFT', 'OPEN', 'CLOSED', 'CANCELLED')
    ),
    CONSTRAINT cycle_period_ordered CHECK (period_end >= period_start),
    CONSTRAINT uq_cycle_name UNIQUE (tenant_id, name)
);

ALTER TABLE review_cycle ENABLE ROW LEVEL SECURITY;
CREATE POLICY review_cycle_tenant_isolation ON review_cycle
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON review_cycle TO dip_app;

CREATE TABLE goal (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID          NOT NULL REFERENCES tenant (id),
    employee_id      UUID          NOT NULL REFERENCES employee (id),

    -- Nullable: a goal can outlive the cycle it was written in, and plenty of goals are set
    -- outside any cycle at all. Forcing one would push people into inventing cycles.
    cycle_id         UUID REFERENCES review_cycle (id),

    title            VARCHAR(200)  NOT NULL,
    description      VARCHAR(4000),

    -- How success will be recognised. Free text on purpose: a goal whose measure has to fit a
    -- dropdown ends up measuring whatever fits.
    measure          VARCHAR(1000),

    -- Relative importance within the set. Not constrained to sum to anything, because most
    -- organisations do not work that way and a constraint people route around is worse than none.
    weight           NUMERIC(5, 2) NOT NULL DEFAULT 1,

    -- Cascading goals: this one supports that one. Self-referencing within a tenant.
    supports_goal_id UUID REFERENCES goal (id),

    target_date      DATE,
    progress_percent INT           NOT NULL DEFAULT 0,

    status           VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    -- Why it ended the way it did. Required for anything other than achievement, because
    -- "missed" with no explanation is a record that can only be read uncharitably.
    outcome_notes    VARCHAR(2000),
    closed_at        TIMESTAMPTZ,

    created_at       TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version          BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT goal_status_valid CHECK (
        status IN ('DRAFT', 'ACTIVE', 'ACHIEVED', 'PARTIALLY_MET', 'MISSED', 'CANCELLED')
    ),
    CONSTRAINT goal_progress_within_range CHECK (progress_percent BETWEEN 0 AND 100),
    CONSTRAINT goal_weight_positive CHECK (weight > 0),
    CONSTRAINT goal_not_its_own_parent CHECK (supports_goal_id IS NULL OR supports_goal_id <> id),
    CONSTRAINT goal_closure_explained CHECK (
        status NOT IN ('PARTIALLY_MET', 'MISSED', 'CANCELLED') OR outcome_notes IS NOT NULL
    ),
    CONSTRAINT goal_closed_has_time CHECK (
        status IN ('DRAFT', 'ACTIVE') OR closed_at IS NOT NULL
    )
);

CREATE INDEX idx_goal_employee ON goal (tenant_id, employee_id, created_at DESC);
CREATE INDEX idx_goal_cycle ON goal (tenant_id, cycle_id) WHERE cycle_id IS NOT NULL;

ALTER TABLE goal ENABLE ROW LEVEL SECURITY;
CREATE POLICY goal_tenant_isolation ON goal
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON goal TO dip_app;

CREATE TABLE performance_review (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id             UUID          NOT NULL REFERENCES tenant (id),
    cycle_id              UUID          NOT NULL REFERENCES review_cycle (id),
    employee_id           UUID          NOT NULL REFERENCES employee (id),
    reviewer_id           UUID          NOT NULL REFERENCES employee (id),

    -- Written by the employee. Stored from the moment it is saved, but not readable by the
    -- reviewer until they have submitted their own — see self_submitted_at below.
    self_assessment       VARCHAR(8000),
    self_submitted_at     TIMESTAMPTZ,

    reviewer_assessment   VARCHAR(8000),
    reviewer_submitted_at TIMESTAMPTZ,

    -- The reviewer's rating, and what calibration changed it to. Both kept: an adjustment that
    -- erases the original leaves nobody able to see that calibration happened at all.
    proposed_rating       VARCHAR(20),
    calibrated_rating     VARCHAR(20),
    calibration_notes     VARCHAR(2000),
    calibrated_at         TIMESTAMPTZ,

    status                VARCHAR(20)   NOT NULL DEFAULT 'PENDING',

    -- Shared with the employee. Until this is set they have not seen it, and it must not inform
    -- any decision about them.
    shared_at             TIMESTAMPTZ,

    -- "I have read this" and "I agree with this" are different statements, kept apart on purpose.
    acknowledged_at       TIMESTAMPTZ,
    employee_response     VARCHAR(4000),
    employee_disagrees    BOOLEAN       NOT NULL DEFAULT FALSE,

    created_at            TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version               BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT review_status_valid CHECK (
        status IN ('PENDING', 'IN_PROGRESS', 'BOTH_SUBMITTED', 'CALIBRATED', 'SHARED',
                   'ACKNOWLEDGED')
    ),
    CONSTRAINT review_rating_valid CHECK (
        proposed_rating IS NULL OR proposed_rating IN
            ('UNSATISFACTORY', 'DEVELOPING', 'MEETS', 'EXCEEDS', 'OUTSTANDING')
    ),
    CONSTRAINT review_calibrated_rating_valid CHECK (
        calibrated_rating IS NULL OR calibrated_rating IN
            ('UNSATISFACTORY', 'DEVELOPING', 'MEETS', 'EXCEEDS', 'OUTSTANDING')
    ),
    -- Nobody reviews themselves. A self-assessment is a field on this row, not a review.
    CONSTRAINT review_reviewer_is_not_subject CHECK (reviewer_id <> employee_id),
    -- Changing somebody's rating in calibration has to say why.
    CONSTRAINT review_calibration_explained CHECK (
        calibrated_rating IS NULL OR calibrated_rating = proposed_rating
            OR calibration_notes IS NOT NULL
    ),
    CONSTRAINT review_shared_before_acknowledged CHECK (
        acknowledged_at IS NULL OR shared_at IS NOT NULL
    ),
    CONSTRAINT uq_review_per_cycle UNIQUE (tenant_id, cycle_id, employee_id)
);

CREATE INDEX idx_review_employee ON performance_review (tenant_id, employee_id);
CREATE INDEX idx_review_reviewer ON performance_review (tenant_id, reviewer_id, status);

ALTER TABLE performance_review ENABLE ROW LEVEL SECURITY;
CREATE POLICY performance_review_tenant_isolation ON performance_review
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON performance_review TO dip_app;

-- Feedback from people who are not the reviewer.
--
-- The author is always recorded. Whether the subject gets to see who wrote it is a separate
-- decision: feedback nobody can attribute is unaccountable, and feedback whose author is always
-- exposed is feedback nobody gives honestly. Storing the author and controlling attribution
-- separately is the only arrangement that serves both.
CREATE TABLE review_feedback (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID          NOT NULL REFERENCES tenant (id),
    review_id         UUID          NOT NULL REFERENCES performance_review (id),

    author_id         UUID          NOT NULL REFERENCES employee (id),
    relationship      VARCHAR(20)   NOT NULL,

    comments          VARCHAR(4000) NOT NULL,

    -- False means the subject sees the words but not the name. HR always sees both.
    attributed        BOOLEAN       NOT NULL DEFAULT FALSE,
    submitted_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),

    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    version           BIGINT        NOT NULL DEFAULT 0,

    CONSTRAINT feedback_relationship_valid CHECK (
        relationship IN ('PEER', 'DIRECT_REPORT', 'MANAGER', 'SKIP_LEVEL', 'EXTERNAL')
    ),
    -- One piece of feedback per person per review. A second submission is an edit, not a
    -- second voice, and counting it twice would weight one colleague above the rest.
    CONSTRAINT uq_feedback_author UNIQUE (tenant_id, review_id, author_id)
);

CREATE INDEX idx_feedback_review ON review_feedback (tenant_id, review_id);

ALTER TABLE review_feedback ENABLE ROW LEVEL SECURITY;
CREATE POLICY review_feedback_tenant_isolation ON review_feedback
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON review_feedback TO dip_app;
