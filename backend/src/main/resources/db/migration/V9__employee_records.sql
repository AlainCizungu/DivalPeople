-- Dependents, emergency contacts and employee documents.
--
-- These hold personal data about people who are not the employee and never agreed to anything
-- with the employer — children, next of kin. Retention and erasure therefore have to follow the
-- employee: when their record is erased, these go with it. Country-specific legal review is
-- required before production, per docs/SECURITY_MODEL.md.

CREATE TABLE employee_dependent (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenant (id),
    employee_id   UUID         NOT NULL REFERENCES employee (id),

    full_name     VARCHAR(300) NOT NULL,
    relationship  VARCHAR(30)  NOT NULL,
    date_of_birth DATE,

    -- Whether they are named on insurance or pension. Drives benefit enrolment later.
    beneficiary   BOOLEAN      NOT NULL DEFAULT FALSE,

    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version       BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT dependent_relationship_valid CHECK (
        relationship IN ('SPOUSE', 'PARTNER', 'CHILD', 'PARENT', 'SIBLING', 'OTHER')
    )
);

CREATE INDEX idx_dependent_employee ON employee_dependent (tenant_id, employee_id);

ALTER TABLE employee_dependent ENABLE ROW LEVEL SECURITY;
CREATE POLICY employee_dependent_tenant_isolation ON employee_dependent
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON employee_dependent TO dip_app;


-- ---------------------------------------------------------------------------
-- Emergency contacts.
--
-- Read in the worst moment of someone's working life, usually by a person who has never opened
-- this screen before. A phone number is therefore mandatory: a contact nobody can reach is not
-- a contact.
-- ---------------------------------------------------------------------------
CREATE TABLE employee_emergency_contact (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenant (id),
    employee_id     UUID         NOT NULL REFERENCES employee (id),

    full_name       VARCHAR(300) NOT NULL,
    relationship    VARCHAR(100) NOT NULL,
    phone           VARCHAR(40)  NOT NULL,
    alternate_phone VARCHAR(40),
    email           VARCHAR(320),

    -- 1 is who to call first. Unique per employee so the order is never ambiguous.
    priority        INT          NOT NULL DEFAULT 1,

    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version         BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT emergency_contact_priority_positive CHECK (priority >= 1),
    CONSTRAINT uq_emergency_contact_priority UNIQUE (tenant_id, employee_id, priority)
);

CREATE INDEX idx_emergency_contact_employee
    ON employee_emergency_contact (tenant_id, employee_id, priority);

ALTER TABLE employee_emergency_contact ENABLE ROW LEVEL SECURITY;
CREATE POLICY employee_emergency_contact_tenant_isolation ON employee_emergency_contact
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON employee_emergency_contact TO dip_app;


-- ---------------------------------------------------------------------------
-- Employee documents.
--
-- The bytes live in stored_file; this records what a given file *is* to a given person. Linked
-- by id rather than a foreign-key relation in the ORM, so the employees module does not take a
-- persistence dependency on the files module.
--
-- Many of these expire — work permits, visas, professional certifications — and an expired work
-- permit is a compliance problem, not an administrative one. Hence the same alert treatment as
-- contracts.
-- ---------------------------------------------------------------------------
CREATE TABLE employee_document (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL REFERENCES tenant (id),
    employee_id        UUID         NOT NULL REFERENCES employee (id),

    stored_file_id     UUID         NOT NULL REFERENCES stored_file (id),

    document_type      VARCHAR(40)  NOT NULL,
    title              VARCHAR(300) NOT NULL,
    issued_on          DATE,
    expires_on         DATE,

    expiry_notified_at TIMESTAMPTZ,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version            BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT document_type_valid CHECK (
        document_type IN ('CONTRACT', 'IDENTITY', 'WORK_PERMIT', 'VISA', 'CERTIFICATION',
                          'QUALIFICATION', 'MEDICAL', 'PAYSLIP', 'OTHER')
    ),
    CONSTRAINT document_expiry_after_issue CHECK (
        expires_on IS NULL OR issued_on IS NULL OR expires_on >= issued_on
    ),
    -- One file is one document. Attaching the same object twice makes retention ambiguous.
    CONSTRAINT uq_employee_document_file UNIQUE (tenant_id, stored_file_id)
);

CREATE INDEX idx_employee_document_employee
    ON employee_document (tenant_id, employee_id, created_at DESC);

CREATE INDEX idx_employee_document_expiry ON employee_document (tenant_id, expires_on)
    WHERE expires_on IS NOT NULL;

ALTER TABLE employee_document ENABLE ROW LEVEL SECURITY;
CREATE POLICY employee_document_tenant_isolation ON employee_document
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());
GRANT SELECT, INSERT, UPDATE ON employee_document TO dip_app;
