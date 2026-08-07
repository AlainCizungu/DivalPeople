-- Two corrections to the audit log, both from the August 2026 security review.
--
-- 1. Inquiries record WHY. `InquiryRequest.purpose` is @NotBlank and its javadoc says every
--    lookup must be able to answer "why did you look this person up". It was validated and then
--    discarded, because there was nowhere to put it. This is the compensating control for a
--    credit-bureau exchange, and without it a bulk sweep of a competitor's subjects leaves a
--    trail of rows saying only that somebody looked.
--
-- 2. The append-only claim is made true. V1 and V4 both say audit_event is "append-only by
--    privilege, not merely by convention". It was not: V1 line 69 grants UPDATE on ALL TABLES
--    and runs *after* audit_event is created, and the narrower grant on the next line adds
--    nothing — a GRANT is not a reset. Nothing updates audit rows today, which is exactly why
--    nobody noticed the guarantee was decorative.

ALTER TABLE audit_event
    ADD COLUMN detail VARCHAR(500);

COMMENT ON COLUMN audit_event.detail IS
    'Why the action was taken, in the actor''s own words where the API asks for it. Free text, '
    'never parsed. Bounded so a caller cannot use the audit log as storage.';

-- Now actually append-only. REVOKE is the operative statement; the earlier GRANTs never removed
-- anything.
REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM dip_app;

-- Belt and braces: a role that gains UPDATE later through some future GRANT ... ON ALL TABLES
-- still cannot rewrite history, because the rule refuses it outright.
CREATE OR REPLACE RULE audit_event_no_update AS
    ON UPDATE TO audit_event DO INSTEAD NOTHING;

CREATE OR REPLACE RULE audit_event_no_delete AS
    ON DELETE TO audit_event DO INSTEAD NOTHING;
