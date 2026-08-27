-- ---------------------------------------------------------------------------
-- Obligations have a life, not just an end.
--
-- Until now a row came into existence by declaring a default. There is a
-- reporting threshold that refuses anything under 100 USD, and no way at all
-- to say "this account exists and is being paid" — so the network held only
-- bad news, and these two applicants were indistinguishable to it:
--
--   Company A   1 default in 2 obligations    50% problematic
--   Company B   1 default in 48 obligations    2% problematic
--
-- They are not the same borrower. Worse, neither could be told apart from a
-- company with one default and no other history whatsoever.
--
-- WHY A SEPARATE TABLE RATHER THAN COLUMNS ON tix_debt_record.
--
-- A declared debt is a legally weighted artifact: it carries a retention
-- clock, dispute rights, and an Article 214 duty to tell everyone who enquired
-- if it turns out to be wrong. "Paid as agreed" carries none of that. Folding
-- the two together would push every routine monthly event through the whole
-- rights machinery, and would make the volume of that machinery a hundred
-- times larger for no gain.
--
-- So: a relationship is the ACCOUNT an operator holds with a subject. A debt
-- record remains the ADVERSE DECLARATION about one. They are different things
-- and the schema now says so. A debt record may point at the relationship it
-- concerns, and does not have to — telecoms will send default files long
-- before they send account books.
--
-- STATUS IS DERIVED, NEVER STORED.
--
-- The events are append-only and dated. A status column can be quietly edited
-- to say a company always paid on time; an event log has to be contradicted in
-- the open, by another dated row that stays there. That is the property that
-- makes a payment history worth anything to a lender, and it is the same rule
-- the provenance spine already follows.
-- ---------------------------------------------------------------------------

CREATE TABLE tix_relationship (
    id                UUID PRIMARY KEY,
    tenant_id         UUID        NOT NULL REFERENCES tenant (id),
    subject_id        UUID        NOT NULL REFERENCES tix_subject (id),

    -- The operator's own reference for this account. Scoped to the operator,
    -- exactly as account references on debt records are: two operators may
    -- legitimately both call an account "0001".
    account_reference TEXT        NOT NULL,

    -- What kind of obligation this is. Free text for the same reason
    -- tix_subject.sector is: a telecom's "POSTPAID" and a bank's "TERM LOAN"
    -- have no shared taxonomy anybody would recognise, and inventing one here
    -- would force every participant to lie slightly.
    product           TEXT        NOT NULL,

    currency          VARCHAR(3)  NOT NULL,

    opened_on         DATE        NOT NULL,

    -- Set when a CLOSED or SETTLED event arrives. Null while the account runs.
    -- Denormalised from the events on purpose and ONLY for retention: the purge
    -- has to find dormant accounts without replaying every event of every
    -- account in the network.
    closed_on         DATE,

    -- When this row stops being visible. Same discipline as a debt record: the
    -- date is set once, by policy, and the purge is what enforces it.
    retention_until   DATE        NOT NULL,

    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    version           BIGINT      NOT NULL DEFAULT 0
);

-- One account reference per operator. The partial-index lesson from account
-- references on debt records applies unchanged: scoped to the tenant, so two
-- operators' identical references stay separate.
CREATE UNIQUE INDEX uq_tix_relationship_reference
    ON tix_relationship (tenant_id, account_reference);

-- The network-wide reads all start from the subject.
CREATE INDEX ix_tix_relationship_subject ON tix_relationship (subject_id);
CREATE INDEX ix_tix_relationship_retention ON tix_relationship (retention_until);

ALTER TABLE tix_relationship ENABLE ROW LEVEL SECURITY;

-- Exchange mode reads, exactly as tix_debt_record does, and for the same
-- reason: the whole point is that several operators' histories can be counted
-- together. It appears only in USING, so a transaction that can read across
-- operators still cannot write outside its own tenant.
DROP POLICY IF EXISTS tix_relationship_tenant_isolation ON tix_relationship;
CREATE POLICY tix_relationship_tenant_isolation ON tix_relationship
    USING (tenant_id = app_current_tenant() OR app_exchange_mode())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON tix_relationship TO dip_app;


CREATE TABLE tix_relationship_event (
    id              UUID PRIMARY KEY,
    tenant_id       UUID        NOT NULL REFERENCES tenant (id),
    relationship_id UUID        NOT NULL REFERENCES tix_relationship (id) ON DELETE CASCADE,

    -- OPENED, PERFORMING, PAID_AS_AGREED, LATE_30, LATE_60, LATE_90_PLUS,
    -- RESTRUCTURED, DEFAULTED, SETTLED, CLOSED, DISPUTED.
    --
    -- Not a database enum. Adding a value to a PostgreSQL enum type is a
    -- migration, and this vocabulary will grow as banks and microfinance join
    -- — a check constraint here would be a second place to change and a first
    -- place to forget. The Java enum is the vocabulary; this column stores
    -- what it was told.
    code            VARCHAR(20) NOT NULL,

    -- The date the thing happened, which is not the date it was reported. A
    -- telecom sending March's book in May must be able to say so, and the
    -- difference between the two is exactly what a payment history is about.
    occurred_on     DATE        NOT NULL,

    -- Whether occurred_on came from the file or was inferred, matching the
    -- provenance rule debt records already follow: REPORTED or DERIVED.
    date_source     VARCHAR(20) NOT NULL,

    -- Where it came from, when it came from a delivery rather than a form.
    raw_record_id   UUID,

    created_at      TIMESTAMPTZ NOT NULL
);

-- Reading a relationship means reading its events in order, always.
CREATE INDEX ix_tix_relationship_event_timeline
    ON tix_relationship_event (relationship_id, occurred_on, created_at);

ALTER TABLE tix_relationship_event ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tix_relationship_event_tenant_isolation ON tix_relationship_event;
CREATE POLICY tix_relationship_event_tenant_isolation ON tix_relationship_event
    USING (tenant_id = app_current_tenant() OR app_exchange_mode())
    WITH CHECK (tenant_id = app_current_tenant());

-- No UPDATE and no DELETE. The append-only rule is enforced by the grant
-- rather than by a comment or by everybody remembering: a correction is a new
-- dated event that contradicts the old one, and the old one stays.
--
-- DELETE is withheld even from the retention purge, which removes the parent
-- relationship and lets ON DELETE CASCADE take the events with it. That is
-- deliberate: erasure happens at the account, so nobody can quietly remove the
-- one event that made a history look bad while leaving the account standing.
GRANT SELECT, INSERT ON tix_relationship_event TO dip_app;


-- ---------------------------------------------------------------------------
-- Existing debt records gain an optional link to the account they concern.
--
-- Nullable and staying nullable. Operators send default files today and will
-- send account books later, if at all; a debt record that cannot name its
-- account is the normal case for a long time yet, and a NOT NULL here would
-- mean no operator could declare anything until it had first uploaded its
-- entire book.
-- ---------------------------------------------------------------------------
ALTER TABLE tix_debt_record
    ADD COLUMN relationship_id UUID REFERENCES tix_relationship (id);

CREATE INDEX ix_tix_debt_record_relationship
    ON tix_debt_record (relationship_id)
    WHERE relationship_id IS NOT NULL;

COMMENT ON COLUMN tix_debt_record.relationship_id IS
    'The account this adverse declaration concerns, when the operator has told us about the '
    'account. Null is normal: a default file arrives without one.';

COMMENT ON TABLE tix_relationship IS
    'An account an operator holds with a subject. The obligation itself, whether or not anything '
    'has gone wrong with it. Adverse declarations live in tix_debt_record and carry the retention '
    'clock and dispute rights that this table does not.';

COMMENT ON TABLE tix_relationship_event IS
    'What happened to an account, dated and append-only. Current status is derived by replaying '
    'these, never stored: a status column can be edited to say a company always paid on time.';
