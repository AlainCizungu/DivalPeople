-- Notifications.
--
-- The body is stored as a message KEY plus parameters, never as rendered text. A contract expiry
-- raised while an HR administrator is working in French must still read correctly to a colleague
-- whose account is in English, and a notification written months ago must follow whatever
-- language the reader chooses today. Rendering at read time is the only way that works.

CREATE TABLE notification (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID         NOT NULL REFERENCES tenant (id),

    -- The user this is addressed to. Not a foreign key for the same reason audit is not: a
    -- notification is a record of something that happened and must outlive account cleanup.
    recipient_id UUID         NOT NULL,

    -- Translation key, e.g. 'notification.contractExpiring'. Resolved by the client against its
    -- message catalogue, so the same row renders in either language.
    message_key  VARCHAR(200) NOT NULL,

    -- JSON object of substitution values: names, dates, counts.
    params       TEXT         NOT NULL DEFAULT '{}',

    severity     VARCHAR(20)  NOT NULL DEFAULT 'INFO',

    -- What the notification is about, so the UI can link to it.
    resource_type VARCHAR(100),
    resource_id   VARCHAR(100),

    read_at      TIMESTAMPTZ,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    version      BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT notification_severity_valid CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL'))
);

CREATE INDEX idx_notification_recipient ON notification (tenant_id, recipient_id, created_at DESC);

-- Partial index: the unread badge is the hottest query and only ever looks at unread rows.
CREATE INDEX idx_notification_unread ON notification (tenant_id, recipient_id)
    WHERE read_at IS NULL;

ALTER TABLE notification ENABLE ROW LEVEL SECURITY;

CREATE POLICY notification_tenant_isolation ON notification
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

GRANT SELECT, INSERT, UPDATE ON notification TO dip_app;
