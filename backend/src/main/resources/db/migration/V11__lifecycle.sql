-- Joining and leaving: the checklists that turn a hire or a departure into work somebody owns,
-- and the probation decision that has to be made rather than left to a date passing.
--
-- The shape worth explaining is the copy. A checklist template is a starting point, not a
-- foreign key: when a checklist is raised, its items are copied into the employee's own list.
-- Editing the template next year must not rewrite what somebody was actually asked to do last
-- year. The instance is the record of what happened; the template is only how it began.

CREATE TABLE checklist_template (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL REFERENCES tenant (id),

    code           VARCHAR(50)  NOT NULL,
    name           VARCHAR(200) NOT NULL,
    checklist_type VARCHAR(20)  NOT NULL,

    -- Retired rather than deleted. A template that raised a hundred checklists is referenced by
    -- every one of them in spirit, and deleting it would make those lists unexplainable.
    active         BOOLEAN      NOT NULL DEFAULT TRUE,

    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version        BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT template_type_valid CHECK (
        checklist_type IN ('ONBOARDING', 'OFFBOARDING')
    ),
    CONSTRAINT uq_template_code UNIQUE (tenant_id, code)
);

ALTER TABLE checklist_template ENABLE ROW LEVEL SECURITY;
CREATE POLICY checklist_template_tenant_isolation ON checklist_template
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON checklist_template TO dip_app;

CREATE TABLE checklist_template_item (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenant (id),
    template_id     UUID         NOT NULL REFERENCES checklist_template (id),

    sort_order      INT          NOT NULL,
    title           VARCHAR(200) NOT NULL,
    instructions    VARCHAR(2000),
    category        VARCHAR(30)  NOT NULL,

    -- The role expected to carry it out. A role rather than a person, because a template
    -- naming an individual breaks the day they leave.
    owner_role      VARCHAR(50),

    -- Days from the anchor date: negative for work that must happen before someone starts,
    -- which is most of what makes a first day go well.
    due_offset_days INT          NOT NULL DEFAULT 0,

    -- A checklist cannot be closed while a mandatory item is outstanding. Revoking building
    -- access is not a nice-to-have.
    mandatory       BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT template_item_category_valid CHECK (
        category IN ('PAPERWORK', 'EQUIPMENT', 'ACCESS', 'PAYROLL', 'TRAINING',
                     'INTRODUCTION', 'COMPLIANCE', 'OTHER')
    ),
    CONSTRAINT uq_template_item_position UNIQUE (tenant_id, template_id, sort_order)
);

CREATE INDEX idx_template_item_template
    ON checklist_template_item (tenant_id, template_id, sort_order);

ALTER TABLE checklist_template_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY checklist_template_item_tenant_isolation ON checklist_template_item
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON checklist_template_item TO dip_app;

CREATE TABLE employee_checklist (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL REFERENCES tenant (id),
    employee_id    UUID         NOT NULL REFERENCES employee (id),

    checklist_type VARCHAR(20)  NOT NULL,

    -- The template's name at the moment of copying. Not a foreign key: this records which list
    -- was used, and must keep saying so after the template is renamed or retired.
    template_name  VARCHAR(200) NOT NULL,

    -- Hire date for onboarding, last working day for offboarding. Every due date hangs off it.
    anchor_date    DATE         NOT NULL,

    status         VARCHAR(20)  NOT NULL DEFAULT 'IN_PROGRESS',
    completed_at   TIMESTAMPTZ,

    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version        BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT checklist_type_valid CHECK (
        checklist_type IN ('ONBOARDING', 'OFFBOARDING')
    ),
    CONSTRAINT checklist_status_valid CHECK (
        status IN ('IN_PROGRESS', 'COMPLETED', 'CANCELLED')
    ),
    CONSTRAINT checklist_completed_has_time CHECK (
        status <> 'COMPLETED' OR completed_at IS NOT NULL
    )
);

-- One running list of each kind per person. Two open onboardings means two sets of owners each
-- assuming the other did it.
CREATE UNIQUE INDEX uq_checklist_one_open
    ON employee_checklist (tenant_id, employee_id, checklist_type)
    WHERE status = 'IN_PROGRESS';

CREATE INDEX idx_checklist_employee
    ON employee_checklist (tenant_id, employee_id, created_at DESC);

ALTER TABLE employee_checklist ENABLE ROW LEVEL SECURITY;
CREATE POLICY employee_checklist_tenant_isolation ON employee_checklist
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON employee_checklist TO dip_app;

CREATE TABLE checklist_item (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL REFERENCES tenant (id),
    checklist_id        UUID         NOT NULL REFERENCES employee_checklist (id),

    sort_order          INT          NOT NULL,
    title               VARCHAR(200) NOT NULL,
    instructions        VARCHAR(2000),
    category            VARCHAR(30)  NOT NULL,

    -- Resolved to a person when the list is raised, so "who is doing this" has an answer
    -- rather than a role nobody feels named by.
    assignee_id         UUID REFERENCES employee (id),
    due_on              DATE,
    mandatory           BOOLEAN      NOT NULL DEFAULT FALSE,

    status              VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    completed_at        TIMESTAMPTZ,
    completed_by        UUID REFERENCES employee (id),

    -- Why it was skipped or where it is stuck. A blocked item with no explanation is a task
    -- nobody can pick up.
    notes               VARCHAR(2000),

    -- When the overdue alert last went out, so the daily scan does not re-notify every morning.
    overdue_notified_at TIMESTAMPTZ,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version             BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT item_category_valid CHECK (
        category IN ('PAPERWORK', 'EQUIPMENT', 'ACCESS', 'PAYROLL', 'TRAINING',
                     'INTRODUCTION', 'COMPLIANCE', 'OTHER')
    ),
    CONSTRAINT item_status_valid CHECK (
        status IN ('PENDING', 'DONE', 'BLOCKED', 'NOT_APPLICABLE')
    ),
    CONSTRAINT item_done_has_time CHECK (status <> 'DONE' OR completed_at IS NOT NULL),
    -- Skipping a mandatory step is a decision that has to be explained, and blocking anything
    -- without saying why leaves the next person nothing to act on.
    CONSTRAINT item_exception_has_notes CHECK (
        status NOT IN ('BLOCKED', 'NOT_APPLICABLE') OR notes IS NOT NULL
    ),
    CONSTRAINT uq_item_position UNIQUE (tenant_id, checklist_id, sort_order)
);

CREATE INDEX idx_item_checklist ON checklist_item (tenant_id, checklist_id, sort_order);

-- Drives the overdue scan.
CREATE INDEX idx_item_due ON checklist_item (tenant_id, due_on)
    WHERE status = 'PENDING' AND due_on IS NOT NULL;

ALTER TABLE checklist_item ENABLE ROW LEVEL SECURITY;
CREATE POLICY checklist_item_tenant_isolation ON checklist_item
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON checklist_item TO dip_app;

-- Probation.
--
-- The date already existed on the contract. What was missing is the decision: probation that
-- simply lapses leaves nobody able to say whether the person passed, and in most jurisdictions
-- an unconfirmed probation quietly becomes a confirmed one. Recording the outcome, who made the
-- call and when makes that a choice instead of an accident.
ALTER TABLE employment_contract
    ADD COLUMN probation_outcome      VARCHAR(20),
    ADD COLUMN probation_decided_at   TIMESTAMPTZ,
    ADD COLUMN probation_decided_by   UUID REFERENCES employee (id),
    ADD COLUMN probation_notes        VARCHAR(2000),
    ADD COLUMN probation_notified_at  TIMESTAMPTZ;

ALTER TABLE employment_contract
    ADD CONSTRAINT contract_probation_outcome_valid CHECK (
        probation_outcome IS NULL
            OR probation_outcome IN ('CONFIRMED', 'EXTENDED', 'FAILED')
    ),
    -- An outcome with no author and no timestamp is a rumour.
    ADD CONSTRAINT contract_probation_decision_recorded CHECK (
        probation_outcome IS NULL OR probation_decided_at IS NOT NULL
    ),
    -- There is no outcome for a probation that was never set.
    ADD CONSTRAINT contract_probation_outcome_needs_period CHECK (
        probation_outcome IS NULL OR probation_end_date IS NOT NULL
    ),
    -- Ending someone's employment on probation is the one outcome that must be explained.
    ADD CONSTRAINT contract_probation_failure_has_notes CHECK (
        probation_outcome <> 'FAILED' OR probation_notes IS NOT NULL
    );

CREATE INDEX idx_contract_probation ON employment_contract (tenant_id, probation_end_date)
    WHERE probation_end_date IS NOT NULL AND probation_outcome IS NULL;
