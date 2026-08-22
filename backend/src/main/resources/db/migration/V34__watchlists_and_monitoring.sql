-- ---------------------------------------------------------------------------
-- Watchlists, and what monitoring them produces.
--
-- Two things that had been one. A watchlist answers "who do I care about" — a
-- bank keeps its corporate loan book separate from its collections portfolio,
-- and the two are watched for different reasons and read by different people.
-- Monitoring answers "what changed about them", and until now the answer was a
-- notification: a sentence sent once, unfindable afterwards, with nothing
-- recording what the figures had been before.
--
-- An alert is a record because an institution that acts on one has to be able
-- to show, later, what it was told and when. A notification is a nudge; this is
-- evidence.
-- ---------------------------------------------------------------------------

CREATE TABLE tix_watchlist (
    id          UUID PRIMARY KEY,
    tenant_id   UUID        NOT NULL REFERENCES tenant (id),

    name        TEXT        NOT NULL,

    -- Why this group exists, distinct from why any one subject is in it. "Corporate loan
    -- customers, monitored for the life of the facility" is a different statement from "this
    -- company is being watched because we are considering financing it", and both are worth
    -- keeping.
    purpose     TEXT        NOT NULL,

    created_by  UUID,
    created_at  TIMESTAMPTZ NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0
);

-- Case-insensitive, because "Collections" and "collections" are one list to everybody except a
-- database, and two lists with the same name is how subjects end up split across them.
CREATE UNIQUE INDEX uq_tix_watchlist_name
    ON tix_watchlist (tenant_id, lower(name));

ALTER TABLE tix_watchlist ENABLE ROW LEVEL SECURITY;

-- No exchange-mode exception, exactly as the entries have none. Which companies a participant
-- groups under "customers under review" is a commercial intention rather than a fact about a
-- debtor, and the exchange has no business reading it.
DROP POLICY IF EXISTS tix_watchlist_tenant_isolation ON tix_watchlist;
CREATE POLICY tix_watchlist_tenant_isolation ON tix_watchlist
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE, DELETE ON tix_watchlist TO dip_app;

-- ---------------------------------------------------------------------------
-- Entries gain a home, and a memory of the score.
-- ---------------------------------------------------------------------------

-- Nullable, and it stays nullable. Every watch that existed before this migration belongs to no
-- list, and inventing a "Default" group to put them in would be writing a name nobody chose into
-- somebody's workspace. The screen calls them unfiled and offers to move them.
--
-- ON DELETE SET NULL rather than CASCADE: deleting a group must not silently stop monitoring the
-- subjects that were in it. Losing the folder is not the same as deciding to stop watching, and
-- one of those is a decision somebody should have to make deliberately.
ALTER TABLE tix_watchlist_entry
    ADD COLUMN watchlist_id UUID REFERENCES tix_watchlist (id) ON DELETE SET NULL;

CREATE INDEX idx_tix_watch_entry_list
    ON tix_watchlist_entry (tenant_id, watchlist_id);

-- The DIP Risk Indicator as it stood at the last sweep. Nullable because the exchange withholds
-- the indicator when it will not confirm the identity, and because every row that existed before
-- this migration has never had one recorded.
ALTER TABLE tix_watchlist_entry
    ADD COLUMN last_score INTEGER;

-- ---------------------------------------------------------------------------
-- Alerts.
-- ---------------------------------------------------------------------------

CREATE TABLE tix_monitoring_alert (
    id                     UUID PRIMARY KEY,
    tenant_id              UUID        NOT NULL REFERENCES tenant (id),

    -- Cascades with the entry. An alert about a watch nobody keeps any more is a retained
    -- statement about a company for no current purpose.
    entry_id               UUID        NOT NULL REFERENCES tix_watchlist_entry (id) ON DELETE CASCADE,
    subject_id             UUID        NOT NULL REFERENCES tix_subject (id) ON DELETE CASCADE,

    raised_at              TIMESTAMPTZ NOT NULL,

    -- Before and after, both stored. The whole value of an alert is the movement, and a row that
    -- kept only the new figures would leave "42 to 61" reconstructable solely by trusting that
    -- nothing else changed in between.
    previous_outcome       TEXT,
    current_outcome        TEXT        NOT NULL,
    previous_institutions  INTEGER,
    current_institutions   INTEGER     NOT NULL,
    previous_score         INTEGER,
    current_score          INTEGER,

    -- How loud this should be. Derived at the moment it was raised and stored rather than
    -- recomputed on read: the rule will be tuned, and an alert re-graded by a later rule would
    -- rewrite what somebody was told at the time.
    severity               TEXT        NOT NULL,

    -- The investigation. Null until somebody says they have looked.
    acknowledged_at        TIMESTAMPTZ,
    acknowledged_by        UUID,
    acknowledgement_note   TEXT,

    created_at             TIMESTAMPTZ NOT NULL,
    version                BIGINT      NOT NULL DEFAULT 0
);

-- The queue reads open alerts, newest first; the subject page reads one company's history.
CREATE INDEX idx_tix_alert_open
    ON tix_monitoring_alert (tenant_id, acknowledged_at, raised_at DESC);
CREATE INDEX idx_tix_alert_subject
    ON tix_monitoring_alert (tenant_id, subject_id);

ALTER TABLE tix_monitoring_alert ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS tix_alert_tenant_isolation ON tix_monitoring_alert;
CREATE POLICY tix_alert_tenant_isolation ON tix_monitoring_alert
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE ON tix_monitoring_alert TO dip_app;
