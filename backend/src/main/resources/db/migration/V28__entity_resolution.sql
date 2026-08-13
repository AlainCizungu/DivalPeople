-- ---------------------------------------------------------------------------
-- Two records about one person, and no number anywhere that says so.
--
-- The DRC has no single identifier covering everybody. There is no number a bank, a telecom
-- operator and a utility all hold for the same individual, and the two real operator exports prove
-- it: one keys its customers by its own account numbers, the other by name alone. A registry that
-- treats that as a defect to apologise for cannot answer the only question anybody asks. This is
-- the other option — making the resolution the product.
--
-- Nothing here decides anything on its own. A candidate is a pair of subjects with a confidence
-- and a list of signals, waiting for a person. Merging moves one company's defaults onto another
-- company's file, across institutions that cannot see each other, and the cost of being wrong is
-- somebody refused credit for a debt that is not theirs.
-- ---------------------------------------------------------------------------

-- ---------------------------------------------------------------------------
-- A subject that turned out to be somebody the registry already knew.
--
-- A pointer rather than a delete, and the difference matters three ways. A merge that erases the
-- absorbed subject also erases the candidate row that recorded the decision, so the one action
-- most in need of an audit trail would be the one that destroys it. An inquiry carrying the old
-- subject's identifier would stop resolving rather than arriving at the survivor. And a merge
-- decided in error would be unrecoverable, which is not a property to give the riskiest button in
-- the product.
--
-- The absorbed row keeps its identity and its history and stops being an answer.
-- ---------------------------------------------------------------------------
ALTER TABLE tix_subject
    ADD COLUMN merged_into_subject_id UUID REFERENCES tix_subject (id);

COMMENT ON COLUMN tix_subject.merged_into_subject_id IS
    'Set when this subject was decided to be the same person or company as another. The row '
    'survives so the decision remains auditable and reversible; reads follow the pointer.';

-- A subject cannot absorb itself. Cheap to check and it turns a resolution bug that would silently
-- orphan every record on the row into a write that fails loudly.
ALTER TABLE tix_subject ADD CONSTRAINT tix_subject_merge_not_self
    CHECK (merged_into_subject_id IS NULL OR merged_into_subject_id <> id);

-- Every read that resolves an identifier has to ask whether the subject it landed on is still an
-- answer, and almost all of them are, so this index earns its keep on the exception.
CREATE INDEX idx_tix_subject_merged_into
    ON tix_subject (merged_into_subject_id)
    WHERE merged_into_subject_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- The review queue.
--
-- NOT tenant-owned, and that is the decision the whole feature turns on. A candidate pair spans
-- two operators by construction: the interesting case is Vodacom's record and Orange's record
-- being one person. Showing that to either operator would hand one participant the other's
-- customer name and phone number, which is exactly the disclosure the exchange exists to prevent
-- and would end it in a single screen.
--
-- So the resolution happens at the registry, by PLATFORM_ADMIN, which is how a credit bureau
-- actually works: the bureau holds the data and resolves it, and participants receive the benefit
-- as a better match rather than as a rival's file. tix_subject carries no tenant_id for the same
-- reason and V4 says so; this table follows it.
-- ---------------------------------------------------------------------------
CREATE TABLE tix_match_candidate (
    id                  UUID PRIMARY KEY,

    -- Ordered at write time so a pair is one row however it was found. Without this, a scan that
    -- happened to compare B against A would open a second case about the same two records, and a
    -- reviewer could decide one of them each way.
    subject_low_id      UUID        NOT NULL REFERENCES tix_subject (id),
    subject_high_id     UUID        NOT NULL REFERENCES tix_subject (id),

    confidence          NUMERIC(4, 3) NOT NULL,

    -- The signals as the scorer produced them, kept rather than recomputed. A decision made in
    -- 2026 has to be explainable in 2029, and by then the weights will have moved — recomputing
    -- would show the reviewer's conclusion beside evidence they never saw.
    signals             JSONB       NOT NULL,
    model_version       TEXT        NOT NULL,

    status              TEXT        NOT NULL,
    detected_at         TIMESTAMPTZ NOT NULL,
    decided_at          TIMESTAMPTZ,
    decided_by          UUID,
    note                TEXT,

    CONSTRAINT tix_match_pair_ordered CHECK (subject_low_id < subject_high_id),
    CONSTRAINT tix_match_confidence_range CHECK (confidence >= 0 AND confidence <= 1),
    CONSTRAINT tix_match_status_valid CHECK (
        status IN ('OPEN', 'CONFIRMED', 'REJECTED', 'INVESTIGATING')
    ),

    -- A decision names a time and a person, or it is not a decision. INVESTIGATING counts: sending
    -- a case for investigation is a judgement somebody made and should be accountable for.
    CONSTRAINT tix_match_decision_recorded CHECK (
        (status = 'OPEN') = (decided_at IS NULL AND decided_by IS NULL)
    )
);

-- One live case per pair. Partial rather than total: a pair rejected today and found again in a
-- year, after one of them gained an RCCM, deserves a fresh look — but only once at a time.
CREATE UNIQUE INDEX uq_tix_match_open_pair
    ON tix_match_candidate (subject_low_id, subject_high_id)
    WHERE status = 'OPEN';

-- The queue, worst uncertainty first.
CREATE INDEX idx_tix_match_open
    ON tix_match_candidate (status, confidence DESC)
    WHERE status = 'OPEN';

COMMENT ON TABLE tix_match_candidate IS
    'Pairs of subjects that may be one subject, for review at the registry. Deliberately not '
    'tenant-owned: a pair spans two operators, and showing one participant the other''s customer '
    'is the disclosure the exchange exists to prevent.';
