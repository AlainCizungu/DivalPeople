-- ---------------------------------------------------------------------------
-- A delivery that names its customers and numbers none of them.
--
-- The Orange export has 342 rows and no identifier of any kind: no account reference, no RCCM, no
-- tax number, no usable phone number. Its first column is a row number and its second is the
-- customer name. Every mapping so far has been required to name an identifier column, so that file
-- could not be described at all.
--
-- Identity therefore has two sources, and which one is in use is recorded rather than inferred. A
-- mapping either names a column holding an identifier, or it says identity comes from the name.
--
-- <strong>Identity by name never crosses the exchange.</strong> It resolves a subject inside the
-- operator that reported it and nowhere else, for the same reason an account reference does: two
-- companies in different operators' books may carry the same registered name, and there is nothing
-- in either file that says whether they are one company or two. The exchange's whole value rests
-- on not guessing about that.
--
-- The risk this carries is real and is guarded at derivation rather than here: if two rows of one
-- delivery share a name, the delivery is refused whole. Two companies quietly becoming one is the
-- worst outcome this system has, and a per-file check is the only place it can be caught before
-- anything is written. Orange's 342 names happen to be 342 distinct names; the guard exists for
-- the file where they are not.
-- ---------------------------------------------------------------------------

ALTER TABLE source_mapping ALTER COLUMN identifier_column DROP NOT NULL;
ALTER TABLE source_mapping ALTER COLUMN identifier_type   DROP NOT NULL;

COMMENT ON COLUMN source_mapping.identifier_column IS
    'The column holding an identifier, or NULL when this delivery carries none and identity comes '
    'from the name column instead.';

-- Both or neither. A mapping naming a column without saying what kind of identifier is in it
-- cannot be applied, and a type with no column to read is a setting that does nothing — either
-- would be a mapping that looks complete on screen and refuses at derivation.
ALTER TABLE source_mapping ADD CONSTRAINT source_mapping_identifier_complete CHECK (
    (identifier_column IS NULL) = (identifier_type IS NULL)
);

-- The distinctness rule has to survive a null. Written as three separate comparisons so that the
-- absence of an identifier column leaves the name/amount rule fully in force: in SQL, NULL <> 'x'
-- is unknown rather than false, and a CHECK passes on unknown — so the first two comparisons
-- simply stop applying when there is no identifier column, which is what is wanted.
ALTER TABLE source_mapping DROP CONSTRAINT source_mapping_columns_distinct;
ALTER TABLE source_mapping ADD CONSTRAINT source_mapping_columns_distinct CHECK (
    (identifier_column IS NULL OR identifier_column <> name_column)
        AND (identifier_column IS NULL OR identifier_column <> amount_column)
        AND name_column <> amount_column
);

-- ---------------------------------------------------------------------------
-- What a name-identified subject is keyed on.
--
-- Reuses the scoping added in V26 rather than inventing a second mechanism: REPORTED_NAME is an
-- operator-scoped identifier, subject to the same partial unique index, the same CHECK tying scope
-- to type, and the same row-level security. So the database already guarantees that one operator
-- cannot hold two subjects under one name, and that no other operator can read it.
--
-- It is deliberately absent from source_mapping's list of choosable types. Nobody selects this on
-- a form; it exists only as the consequence of a mapping that declares identity by name, which
-- means there is exactly one way for such a row to come into being.
--
-- Honest about what it is: the weakest identity in the system. A registered name is not a document
-- and nobody issued it. Two different companies in one operator's book carrying the same name
-- would resolve to one subject, and the per-delivery guard catches that only within a single file.
-- Across two deliveries months apart it is not caught, and that is a known limit rather than a
-- solved problem.
-- ---------------------------------------------------------------------------
ALTER TABLE tix_subject_identifier DROP CONSTRAINT tix_identifier_type_valid;
ALTER TABLE tix_subject_identifier ADD CONSTRAINT tix_identifier_type_valid CHECK (
    identifier_type IN ('MSISDN', 'NATIONAL_ID', 'PASSPORT', 'DRIVER_LICENSE',
                        'VOTER_CARD', 'RCCM', 'TAX_NUMBER', 'ACCOUNT_REFERENCE',
                        'REPORTED_NAME')
);

ALTER TABLE tix_subject_identifier DROP CONSTRAINT tix_identifier_scope_matches_type;
ALTER TABLE tix_subject_identifier ADD CONSTRAINT tix_identifier_scope_matches_type CHECK (
    (identifier_type IN ('ACCOUNT_REFERENCE', 'REPORTED_NAME')) = (owner_tenant_id IS NOT NULL)
);
