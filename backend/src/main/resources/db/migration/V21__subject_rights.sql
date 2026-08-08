-- The rights of the people in the registry.
--
-- The terms of reference require "gestion des droits des personnes concernées (accès,
-- rectification, effacement)" and none of it existed. The exchange could record that somebody
-- owed money and had no way for that person to find out, contest it, or have it removed. A
-- registry that can only be written to by the parties with an interest in listing people is not
-- a credit-risk instrument, it is an accusation nobody can answer.
--
-- Modelled as a CASE rather than as a self-service action, because the people in this database
-- are not users of it. Somebody walks into an operator's office or contacts AJF; a staff member
-- opens a request, records how they checked the person is who they say, and the platform tracks
-- it to a decision that has to be justified. Identity verification stays a human judgement — a
-- form that let anyone assert an identity would be a worse control than none, because it would
-- look like one.

CREATE TABLE subject_request (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- The operator whose staff opened the case. The rights themselves are not tenant-scoped —
    -- a person's data is spread across every operator that declared against them — but the case
    -- belongs to whoever is handling it, and that is who may see and progress it.
    tenant_id              UUID         NOT NULL REFERENCES tenant (id),
    subject_id             UUID         NOT NULL REFERENCES tix_subject (id),
    request_type           VARCHAR(20)  NOT NULL,
    status                 VARCHAR(20)  NOT NULL,
    -- What the person actually asked for, in their words where possible.
    detail                 VARCHAR(2000),
    raised_by              UUID,
    raised_at              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    -- How somebody satisfied themselves this is the right person. Free text on purpose: "national
    -- ID CD-1234-5678 seen in person, photograph matches" is evidence a regulator can assess, and
    -- a dropdown of three options is not.
    identity_verified_by   UUID,
    identity_verified_at   TIMESTAMPTZ,
    identity_evidence      VARCHAR(500),

    decided_by             UUID,
    decided_at             TIMESTAMPTZ,
    decision_reason        VARCHAR(1000),
    version                BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT subject_request_type_valid CHECK (
        request_type IN ('ACCESS', 'RECTIFICATION', 'ERASURE', 'DISPUTE')
    ),
    CONSTRAINT subject_request_status_valid CHECK (
        status IN ('RECEIVED', 'IDENTITY_VERIFIED', 'UPHELD', 'REFUSED', 'WITHDRAWN')
    ),

    -- Identity is verified before anything is decided, and the evidence is not optional. Without
    -- this a case could be upheld — suppressing records or erasing them — on nobody's word.
    CONSTRAINT subject_request_verified_has_evidence CHECK (
        (identity_verified_at IS NULL)
            OR (identity_verified_by IS NOT NULL AND identity_evidence IS NOT NULL)
    ),

    -- A decision names its author and its grounds. "Refused" with no reason is the outcome a
    -- person cannot appeal, and appealing is the point of writing rights down.
    CONSTRAINT subject_request_decided_is_justified CHECK (
        (status NOT IN ('UPHELD', 'REFUSED'))
            OR (decided_by IS NOT NULL AND decided_at IS NOT NULL
                AND decision_reason IS NOT NULL AND identity_verified_at IS NOT NULL)
    )
);

CREATE INDEX idx_subject_request_tenant ON subject_request (tenant_id);
CREATE INDEX idx_subject_request_subject ON subject_request (subject_id);
CREATE INDEX idx_subject_request_status ON subject_request (status);

ALTER TABLE subject_request ENABLE ROW LEVEL SECURITY;
CREATE POLICY subject_request_tenant_isolation ON subject_request
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE ON subject_request TO dip_app;

-- ---------------------------------------------------------------------------
-- Records suppressed because somebody is contesting them.
--
-- DISPUTED already existed as a status and already excluded a record from inquiry results — what
-- did not exist was any way for the person concerned to cause it. The dispute endpoint required
-- TIX_DECLARANT, which means an operator disputing its own record: the control was pointing the
-- wrong way round, and the party a dispute exists to protect was the one party who could not
-- raise one.
--
-- This column records which request suppressed a record, so lifting the suppression when a case
-- closes affects exactly the records that case touched, and an auditor can see why any given
-- record is invisible.
-- ---------------------------------------------------------------------------
ALTER TABLE tix_debt_record
    ADD COLUMN suppressed_by_request_id UUID REFERENCES subject_request (id);

CREATE INDEX idx_tix_debt_suppressed_by ON tix_debt_record (suppressed_by_request_id);
