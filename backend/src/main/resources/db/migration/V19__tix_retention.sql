-- Retention for the exchange.
--
-- The March 2026 terms of reference require erasure once a debt is regularised, roughly three
-- years for a simple unpaid and five in case of récidive. Until now nothing expired: a record
-- entered once was visible for as long as the database existed. A registry of people's debts
-- that never forgets is the single most likely reason for a regulator to close the platform, and
-- it is not a feature anybody would notice missing — everything works, forever, which is the
-- problem.

ALTER TABLE tix_debt_record ADD COLUMN retention_until DATE;

-- Existing rows get the shorter of the two periods. Choosing three years rather than five for a
-- backfill is deliberate: nobody recorded whether these were repeat defaults, and inventing the
-- longer retention for a record we cannot classify would keep people listed on an assumption.
-- Where the evidence is missing, the shorter period is the honest one.
UPDATE tix_debt_record
   SET retention_until = default_date + INTERVAL '3 years'
 WHERE retention_until IS NULL;

ALTER TABLE tix_debt_record ALTER COLUMN retention_until SET NOT NULL;

-- The purge scans by this column across every tenant, nightly.
CREATE INDEX idx_tix_debt_retention ON tix_debt_record (retention_until);

-- ---------------------------------------------------------------------------
-- Erasure needs privileges that were never granted.
--
-- V2 gave dip_app SELECT, INSERT and UPDATE on these three tables and no DELETE, because at the
-- time nothing in the system ever deleted anything. "Erasure" implemented as an UPDATE that hides
-- a row is not erasure; it is the same personal data with a flag on it, and it would not survive
-- the first question from a data protection authority about what the platform actually holds.
--
-- Subjects and identifiers are included because a subject is only in the exchange to carry debt
-- records. Erasing every record about a person and leaving their name, date of birth and national
-- ID number behind would be the worst of both outcomes: no lawful basis for the data, and no
-- record explaining why it is there.
-- ---------------------------------------------------------------------------
GRANT DELETE ON tix_debt_record TO dip_app;
GRANT DELETE ON tix_subject TO dip_app;
GRANT DELETE ON tix_subject_identifier TO dip_app;
