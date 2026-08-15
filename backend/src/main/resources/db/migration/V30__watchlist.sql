-- ---------------------------------------------------------------------------
-- Watching a company, which is a standing inquiry and is treated as one.
--
-- An operator can already ask the exchange about a subject whenever it likes. A watchlist is not a
-- new power, it is that same question asked on a schedule — and the design decision this table
-- exists to record is that it must not become more than that.
--
-- The temptation is to make a watch a live feed: tell me the moment anything changes. That would
-- disclose something an inquiry never does. The exchange answers "how many institutions report
-- this company", deliberately never which; a notification the same afternoon a rival declares
-- tells a watcher *when*, and when plus a count of two is an attribution by elimination — you know
-- the second institution is not you.
--
-- So a watch is answered by a nightly sweep, it charges the rate limiter like any other inquiry,
-- it is audited under the same action with the same stated purpose, and it reports exactly what an
-- inquiry would have reported that morning. Nothing here is reachable that was not reachable by an
-- operator willing to ask the same question every day by hand.
-- ---------------------------------------------------------------------------

CREATE TABLE tix_watchlist_entry (
    id                 UUID PRIMARY KEY,
    tenant_id          UUID        NOT NULL REFERENCES tenant (id),

    -- Cascades, like the resolution candidate does and for the same reason: a watch naming an
    -- erased subject would be retained personal data about somebody the registry has forgotten,
    -- and the right to erasure is not a watchlist's to veto.
    subject_id         UUID        NOT NULL REFERENCES tix_subject (id) ON DELETE CASCADE,

    -- Why this company is being monitored, in the watcher's own words. Required, exactly as it is
    -- on a single inquiry: monitoring somebody indefinitely for no stated reason is the thing a
    -- regulator objects to, and "we always have" is not a purpose.
    purpose            TEXT        NOT NULL,

    added_by           UUID,

    -- created_at and version because this is a TenantOwnedEntity, and the base class writes both.
    -- created_at is when the watch was opened; there is no second "added_at" to disagree with it.
    created_at         TIMESTAMPTZ NOT NULL,
    version            BIGINT      NOT NULL DEFAULT 0,

    -- A watch stops on its own. Somebody who still needs it renews it and says why again; the
    -- alternative is a list that only ever grows, which is surveillance by accretion.
    expires_at         TIMESTAMPTZ NOT NULL,

    -- What the exchange last said, so the sweep can tell a change from a repetition. Only the two
    -- things an inquiry discloses: nothing here is a fact the watcher could not have asked for.
    last_outcome       TEXT,
    last_institutions  INTEGER,
    last_checked_at    TIMESTAMPTZ,

    CONSTRAINT tix_watch_purpose_not_blank CHECK (length(btrim(purpose)) > 0),
    CONSTRAINT tix_watch_expires_after_opening CHECK (expires_at > created_at)
);

-- One watch per company per operator. A second would double every notification and let two people
-- in the same institution disagree about why it is being monitored.
CREATE UNIQUE INDEX uq_tix_watch_subject
    ON tix_watchlist_entry (tenant_id, subject_id);

-- The sweep reads by expiry; the screen reads by tenant.
CREATE INDEX idx_tix_watch_live
    ON tix_watchlist_entry (tenant_id, expires_at);

ALTER TABLE tix_watchlist_entry ENABLE ROW LEVEL SECURITY;

-- No exchange-mode exception, unlike tix_debt_record. Reading across operators is the product for
-- debt records; for a watchlist it would tell one participant which companies a rival is worried
-- about, which is a commercial intention rather than a fact about a debtor.
DROP POLICY IF EXISTS tix_watch_tenant_isolation ON tix_watchlist_entry;
CREATE POLICY tix_watch_tenant_isolation ON tix_watchlist_entry
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

COMMENT ON TABLE tix_watchlist_entry IS
    'A standing inquiry. Swept nightly, charged against the rate limit and audited under the '
    'inquiry action, so a watch discloses nothing an operator could not have asked for by hand.';
