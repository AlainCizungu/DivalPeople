-- ---------------------------------------------------------------------------
-- Sector, city and street address on a subject.
--
-- Counsel, August 2026, on how to tell one company from another: match on the dénomination, the
-- secteur d'activité, the adresse (zone opérationnelle) and the RCCM and/or the tax number. Two of
-- those four the registry has never held — and they are the same two the resolution screen has
-- reported as *never available* on every case it has ever shown. His answer and the screen's own
-- admission are one finding reached from two directions.
--
-- They matter more since the register number stopped being decisive. An RCCM is reissued when a
-- company amends its statutes, so a conflicting one is now advisory rather than conclusive, which
-- puts pairs in front of a reviewer that the old rule silently discarded. That is more honest and
-- it is not more informative: on a name and a register number alone, two homonymous companies and
-- one re-registered company look identical. These three columns are what tell them apart.
--
-- ---------------------------------------------------------------------------
-- Whose values are these?
--
-- A subject is registry-wide. Two operators declaring one company by national document land on one
-- row, and nothing arbitrates between "Transport et logistique" and "Logistique" if they disagree.
--
-- So the rule is **learned once, never overwritten**: a blank column is filled by whoever supplies
-- it first, and a later declaration carrying a different value leaves it alone. Last-writer-wins
-- would let one participant silently rewrite another's view of a company it cannot see, which is
-- the disclosure the whole exchange is built to prevent — running backwards.
--
-- The cost is stated rather than hidden: a stale address is not corrected by a fresher one. The
-- subject rights path is how a company changes what is held about it, and that path has a person
-- on it. A column that quietly reflected the most recent declarant would have no such person.
-- ---------------------------------------------------------------------------

ALTER TABLE tix_subject
    -- Free text rather than a code list. There is no Congolese sector taxonomy that both a telecom
    -- billing system and a bank would already hold, and inventing one here would mean every
    -- operator mapping their vocabulary onto ours before a single row imported. Compared loosely,
    -- weighted lightly, and worth more than the nothing that is held today.
    ADD COLUMN sector VARCHAR(120),

    -- Separate from the street, because a city compares reliably and a street does not. "Kinshasa"
    -- against "Goma" is a real disagreement; "12 av. Kasa-Vubu" against "12, avenue Kasa Vubu" is
    -- one address typed by two clerks.
    ADD COLUMN city VARCHAR(120),

    ADD COLUMN street_address VARCHAR(300);

COMMENT ON COLUMN tix_subject.sector IS
    'Operator-reported line of business. Learned once from whoever supplies it first and never '
    'overwritten: no participant may rewrite another''s view of a company it cannot see.';

COMMENT ON COLUMN tix_subject.city IS
    'City or commune of operation. Compared as an equality, unlike street_address.';

COMMENT ON COLUMN tix_subject.street_address IS
    'Street address, compared loosely. A difference here weighs nothing — free-text addresses '
    'differ between two clerks at least as often as between two companies.';

-- The resolution scan blocks on the name and then compares; nothing queries these directly, so
-- they carry no index. Adding one now would be a guess about a query nobody has written.
