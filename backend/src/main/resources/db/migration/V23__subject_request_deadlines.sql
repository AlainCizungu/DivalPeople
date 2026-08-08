-- Statutory deadlines on subject requests.
--
-- The Code du numérique gives the controller a fixed time to answer, and the periods differ by
-- what is being asked: article 210 allows sixty days to supply a copy of what is held, while
-- articles 213, 214 and 215 allow thirty for opposition, rectification and erasure. Article 214
-- makes missing the deadline itself grounds for a complaint to the Autorité de protection des
-- données — so a case with no due date is not merely untidy, it is a liability nobody can see.
--
-- Stored rather than computed on read. The deadline that applies is the one that applied when the
-- case was opened; if a regulation later changes the period, cases already in the queue must not
-- silently acquire a new one, and a formula in Java would give them exactly that.

ALTER TABLE subject_request ADD COLUMN due_at TIMESTAMPTZ;

-- Existing rows get the period their type would have been given. There are only development rows
-- at this point, and backfilling from raised_at is what the application would have computed.
UPDATE subject_request
   SET due_at = raised_at + INTERVAL '60 days'
 WHERE request_type = 'ACCESS';

UPDATE subject_request
   SET due_at = raised_at + INTERVAL '30 days'
 WHERE due_at IS NULL;

ALTER TABLE subject_request ALTER COLUMN due_at SET NOT NULL;

-- A deadline that could be pushed back would not be a deadline. The column is never updated by
-- the application; this makes that a rule of the database rather than a habit of the code.
ALTER TABLE subject_request
    ADD CONSTRAINT subject_request_due_after_raised CHECK (due_at > raised_at);

-- Finding what is overdue is the query the queue screen will run on every load, and it runs
-- inside a tenant.
CREATE INDEX idx_subject_request_due ON subject_request (tenant_id, due_at)
    WHERE status IN ('RECEIVED', 'IDENTITY_VERIFIED');
