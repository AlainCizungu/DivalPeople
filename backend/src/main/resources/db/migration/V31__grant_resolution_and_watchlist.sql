-- ---------------------------------------------------------------------------
-- The grants V28 and V30 forgot.
--
-- The application connects as dip_app, which owns nothing. Every table since V1 has therefore
-- carried an explicit GRANT beside its definition, and two did not: tix_match_candidate and
-- tix_watchlist_entry. Both migrations applied cleanly, both tables exist with the right columns,
-- the right indexes and the right row-level policies — and every read of either returned
-- "permission denied for table", SQLSTATE 42501.
--
-- Worth being precise about why this was not caught. The architecture check already verifies that
-- a tenant-owned table enables row-level security and carries a WITH CHECK policy, so the *harder*
-- half of the isolation story was enforced and the trivial half was not. RLS restricts which rows
-- a grant lets you see; without the grant there is nothing for it to restrict. A policy on a table
-- nobody may read is a lock on a door with no handle.
--
-- The tests would never have found it either: Testcontainers runs migrations and the suite as the
-- superuser that owns the schema, so a missing grant is invisible there and shows up only in a
-- real deployment, on the first request, as a 500. This is the third defect this year that only
-- the running application could see. A check now enforces the rule for every future table.
-- ---------------------------------------------------------------------------

-- Read, open and decide. No DELETE: a candidate is resolved by recording a decision, never by
-- being removed, and the cascade that clears candidates when a subject is erased runs with the
-- table owner's rights rather than the application's.
GRANT SELECT, INSERT, UPDATE ON tix_match_candidate TO dip_app;

-- DELETE here because unwatching genuinely deletes. A watch that has stopped holds nothing worth
-- keeping — what it observed is in the audit trail — and a row saying "not watching this any more"
-- is personal data retained for no reason.
GRANT SELECT, INSERT, UPDATE, DELETE ON tix_watchlist_entry TO dip_app;
