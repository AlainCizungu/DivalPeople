-- Turning delivered rows into records, without pretending to know things we do not.
--
-- Two problems have blocked this since the Vodacom export was profiled, and they are different
-- kinds of problem.
--
-- The first: the file contains no dates at all. Not a default date, not an invoice date, not a
-- write-off date. Meanwhile tix_debt_record.default_date is NOT NULL and the retention clock runs
-- from it. The temptation is to derive a date from the aging bucket, which would give 4,262 of
-- 4,290 rows the same one and a retention expiry clustered on a single day — a guessed date is a
-- guessed retention period, and retention is the control that decides when somebody stops being
-- unbankable.
--
-- So the operator is asked instead. They know what their file is as at; we do not. reported_as_at
-- moves the assumption to the party who can actually answer, and default_date_source records that
-- the resulting date was derived rather than reported — because the expensive mistake is not
-- assuming a date, it is forgetting that it was assumed. Raw rows are immutable and every derived
-- record names the row it came from, so when real dates arrive the fix is to re-derive rather than
-- to guess which records to correct.
--
-- The second problem — which column is the amount, which is the identifier — is not ours to
-- decide either. That arrives in V25, with the table the operator writes their answer into.

-- --------------------------------------------------------------------------
-- When the delivery is as at.
-- --------------------------------------------------------------------------

-- Nullable, because batches already exist and the column cannot be invented for them. New
-- deliveries require it at the API, and the derivation refuses to run without it — which is the
-- honest arrangement: an old batch is not wrong, it is unmappable until somebody says what it is.
ALTER TABLE import_batch ADD COLUMN reported_as_at DATE;

COMMENT ON COLUMN import_batch.reported_as_at IS
    'What the operator says the file reflects. Supplied, never inferred from the filename.';

-- A file cannot describe the future, and a date in it would start a retention clock that has not
-- begun. The same rule declaration already applies to a default date.
ALTER TABLE import_batch
    ADD CONSTRAINT import_batch_as_at_not_future
        CHECK (reported_as_at IS NULL OR reported_as_at <= CURRENT_DATE);

-- --------------------------------------------------------------------------
-- Where a record's default date came from.
-- --------------------------------------------------------------------------

ALTER TABLE tix_debt_record
    ADD COLUMN default_date_source VARCHAR(20) NOT NULL DEFAULT 'REPORTED';

COMMENT ON COLUMN tix_debt_record.default_date_source IS
    'REPORTED: the declaring operator gave this date. DERIVED: computed from the batch''s '
    'reported_as_at because the source file carried none.';

ALTER TABLE tix_debt_record
    ADD CONSTRAINT tix_debt_date_source_valid
        CHECK (default_date_source IN ('REPORTED', 'DERIVED'));

-- A derived date can only exist on a record that came from a file. An API declaration carries a
-- date the operator typed, so calling it derived would be a lie the schema can prevent.
ALTER TABLE tix_debt_record
    ADD CONSTRAINT tix_debt_derived_only_on_import
        CHECK (default_date_source = 'REPORTED' OR origin = 'IMPORT');

-- Finding everything that needs re-deriving once real dates arrive. The whole point of recording
-- the distinction is being able to run this query.
CREATE INDEX idx_tix_debt_derived_dates ON tix_debt_record (tenant_id)
    WHERE default_date_source = 'DERIVED';
