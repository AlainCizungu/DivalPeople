-- A rights case is erased with the person it is about.
--
-- V21 gave subject_request a foreign key to tix_subject and stopped there, which broke erasure:
-- RetentionPurge deletes subjects nothing refers to any more, and a person whose debt records
-- have all been erased may still have a case on file. The delete is refused by the constraint, so
-- the nightly purge fails for that tenant and keeps failing — and the failure is a log line at
-- 02:15 that nobody reads.
--
-- Found by the test suite rather than by review: SubjectRightsTest commits, so the subjects it
-- erased were still there when RetentionPurgeTest ran, and five unrelated tests started failing.
-- Cross-test interference is usually a nuisance; this time it was the only reason anybody noticed.
--
-- ON DELETE CASCADE is the right answer rather than a convenience. A case carries the person's
-- identity evidence and their own words about their situation — it is their personal data, and
-- when the exchange has no lawful reason to hold anything about them, that includes the record of
-- them asking. What survives is the audit trail, which records that a case was raised, verified
-- and decided, and carries the identifiers of neither the person nor their documents. That is the
-- correct division: the evidence that a process happened outlives the personal data it concerned.

ALTER TABLE subject_request
    DROP CONSTRAINT subject_request_subject_id_fkey;

ALTER TABLE subject_request
    ADD CONSTRAINT subject_request_subject_id_fkey
    FOREIGN KEY (subject_id) REFERENCES tix_subject (id) ON DELETE CASCADE;

-- The same reasoning one level down. A debt record points at the case that suppressed it; if the
-- case is erased with the person, a record still pointing at it would block that too. In practice
-- the records are erased first, but relying on the order in which two independent deletions happen
-- is exactly the kind of assumption that holds until it does not.
ALTER TABLE tix_debt_record
    DROP CONSTRAINT tix_debt_record_suppressed_by_request_id_fkey;

ALTER TABLE tix_debt_record
    ADD CONSTRAINT tix_debt_record_suppressed_by_request_id_fkey
    FOREIGN KEY (suppressed_by_request_id) REFERENCES subject_request (id) ON DELETE SET NULL;
