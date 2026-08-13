-- ---------------------------------------------------------------------------
-- A case in the review queue must never make somebody impossible to erase.
--
-- V28 pointed tix_match_candidate at tix_subject with plain foreign keys, which default to NO
-- ACTION. The consequence was immediate and went well beyond the resolution feature: the nightly
-- retention purge could no longer delete a subject anybody had ever been compared against, and
-- neither could an erasure request. Five purge tests and a rights test caught it, which is the
-- correct place for it to be caught and not the correct place for it to have been thought about.
--
-- The right to erasure is not a queue's to veto. Whatever a reviewer has or has not got round to,
-- a person whose records have gone is entitled to go with them — and a candidate row naming an
-- erased subject would itself be retained personal data, which is the opposite of what the
-- erasure achieved.
--
-- So the case goes when the subject goes. The decision is not lost with it: RESOLUTION_DECIDED is
-- written to the audit trail, which is where an account of who did what belongs and which is not
-- keyed on a subject that no longer exists.
--
-- Worth being explicit that this does not reopen the argument V28 settled. Nothing here makes a
-- *merge* delete anything — an absorbed subject still survives as a pointer, precisely so the
-- decision that absorbed it stays visible. This is about erasure, which is a different act with a
-- different answer.
-- ---------------------------------------------------------------------------

ALTER TABLE tix_match_candidate
    DROP CONSTRAINT IF EXISTS tix_match_candidate_subject_low_id_fkey;
ALTER TABLE tix_match_candidate
    DROP CONSTRAINT IF EXISTS tix_match_candidate_subject_high_id_fkey;

ALTER TABLE tix_match_candidate
    ADD CONSTRAINT tix_match_candidate_subject_low_fk
        FOREIGN KEY (subject_low_id) REFERENCES tix_subject (id) ON DELETE CASCADE;
ALTER TABLE tix_match_candidate
    ADD CONSTRAINT tix_match_candidate_subject_high_fk
        FOREIGN KEY (subject_high_id) REFERENCES tix_subject (id) ON DELETE CASCADE;

-- ---------------------------------------------------------------------------
-- And the merge pointer, for the same reason and with a different answer.
--
-- SET NULL rather than CASCADE, because these are two different subjects. Erasing the survivor
-- must not erase the record of somebody who merely turned out to be the same person — that would
-- make one erasure request quietly remove a second person from the registry.
--
-- What the absorbed row becomes is a subject with no records and no pointer, which the nightly
-- purge then sweeps on its own terms.
-- ---------------------------------------------------------------------------
ALTER TABLE tix_subject
    DROP CONSTRAINT IF EXISTS tix_subject_merged_into_subject_id_fkey;
ALTER TABLE tix_subject
    ADD CONSTRAINT tix_subject_merged_into_fk
        FOREIGN KEY (merged_into_subject_id) REFERENCES tix_subject (id) ON DELETE SET NULL;
