-- ---------------------------------------------------------------------------
-- An identifier that means something at one operator and nothing anywhere else.
--
-- The real Vodacom export identifies every account by BPR_0 — values like V0172109. That is not
-- an RCCM, not a tax number and not any national document; it is Vodacom's own customer
-- reference. Until now the mapping form offered no type for it, so importing that file meant
-- declaring the account number to be an RCCM, which is false in the registry that other operators
-- read.
--
-- Adding the type is the small half. The dangerous half is that every identifier in this table has
-- been globally unique on (type, value), which is precisely what makes an identifier resolve one
-- subject across the exchange — correct for a national document, and catastrophic for an account
-- number. Two operators number their customers from one upwards. Airtel's account 100234 and
-- Vodacom's account 100234 are two unrelated businesses, and under the old constraint the second
-- one declared would resolve to the first one's subject and inherit its debts.
--
-- So the scope is part of the identity. A national document is unique across the exchange; an
-- account reference is unique only within the operator that issued it, and the two rules are
-- carried by two partial unique indexes rather than by application code that could forget.
-- ---------------------------------------------------------------------------

ALTER TABLE tix_subject_identifier
    ADD COLUMN owner_tenant_id UUID REFERENCES tenant (id);

COMMENT ON COLUMN tix_subject_identifier.owner_tenant_id IS
    'The operator that issued this reference, for identifiers that are only meaningful inside '
    'one operator. NULL for national documents, which belong to no operator and resolve across '
    'the whole exchange.';

ALTER TABLE tix_subject_identifier DROP CONSTRAINT tix_identifier_type_valid;
ALTER TABLE tix_subject_identifier ADD CONSTRAINT tix_identifier_type_valid CHECK (
    identifier_type IN ('MSISDN', 'NATIONAL_ID', 'PASSPORT', 'DRIVER_LICENSE',
                        'VOTER_CARD', 'RCCM', 'TAX_NUMBER', 'ACCOUNT_REFERENCE')
);

-- The scope is not optional and not a free choice: it follows from the type. Written as an
-- equality between two booleans so that both mistakes are caught by one constraint — a national
-- document narrowed to a single operator, and an account reference loose in the national
-- namespace. The second is the one that merges two companies.
ALTER TABLE tix_subject_identifier ADD CONSTRAINT tix_identifier_scope_matches_type CHECK (
    (identifier_type = 'ACCOUNT_REFERENCE') = (owner_tenant_id IS NOT NULL)
);

-- Replaced by two partial indexes. A plain UNIQUE (identifier_type, normalized_value) would still
-- be enforced across operators, and a UNIQUE including owner_tenant_id would stop enforcing
-- anything for national documents, because NULLs are never equal to each other in a unique index:
-- the same national ID could then be declared any number of times.
ALTER TABLE tix_subject_identifier DROP CONSTRAINT uq_tix_identifier;

CREATE UNIQUE INDEX uq_tix_identifier_national
    ON tix_subject_identifier (identifier_type, normalized_value)
    WHERE owner_tenant_id IS NULL;

CREATE UNIQUE INDEX uq_tix_identifier_scoped
    ON tix_subject_identifier (owner_tenant_id, identifier_type, normalized_value)
    WHERE owner_tenant_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Visibility.
--
-- V4 left this table without row-level security and said why: it is the shared spine several
-- operators hold records against, and a subject's national documents are how they find each
-- other. That reasoning holds for exactly the rows it was written about, and not for these.
--
-- An account reference is one operator's internal numbering. No other operator has any business
-- reading it, and there is no exchange-mode exception below — unlike tix_debt_record, where
-- reading across operators is the entire product. A competitor who could resolve an account
-- number would learn which of their rival's customers exist, one number at a time.
--
-- National rows keep the behaviour they have always had: owner_tenant_id IS NULL passes for
-- everybody. Nothing that works today stops working.
-- ---------------------------------------------------------------------------
ALTER TABLE tix_subject_identifier ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tix_identifier_scope_isolation ON tix_subject_identifier;
CREATE POLICY tix_identifier_scope_isolation ON tix_subject_identifier
    USING (owner_tenant_id IS NULL OR owner_tenant_id = app_current_tenant())
    WITH CHECK (owner_tenant_id IS NULL OR owner_tenant_id = app_current_tenant());

-- ---------------------------------------------------------------------------
-- The mapping form has to be able to offer the new type, or none of the above is reachable.
-- ---------------------------------------------------------------------------
ALTER TABLE source_mapping DROP CONSTRAINT source_mapping_identifier_type_valid;
ALTER TABLE source_mapping ADD CONSTRAINT source_mapping_identifier_type_valid CHECK (
    identifier_type IN ('MSISDN', 'NATIONAL_ID', 'PASSPORT', 'DRIVER_LICENSE',
                        'VOTER_CARD', 'RCCM', 'TAX_NUMBER', 'ACCOUNT_REFERENCE')
);
