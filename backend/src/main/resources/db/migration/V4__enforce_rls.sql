-- Make row-level security actually bind.
--
-- Until now the policies added alongside each tenant-owned table were inert, because the
-- application connected as the schema owner and owners bypass RLS. This migration gives the
-- application its own non-owner login and rewrites the policies to be precise about the one
-- place where crossing tenants is legitimate.
--
-- See docs/adr/0002-shared-schema-multitenancy.md.

-- ---------------------------------------------------------------------------
-- Application login.
--
-- The password comes from a Flyway placeholder so it is not committed. Configure it with
-- spring.flyway.placeholders.dip_app_password (DIP_APP_DB_PASSWORD in the environment).
-- ---------------------------------------------------------------------------
ALTER ROLE dip_app WITH LOGIN PASSWORD '${dip_app_password}';

GRANT USAGE ON SCHEMA public TO dip_app;
GRANT SELECT, INSERT, UPDATE ON tenant TO dip_app;
GRANT SELECT, INSERT, UPDATE ON user_account TO dip_app;
GRANT SELECT, INSERT, UPDATE ON tix_subject TO dip_app;
GRANT SELECT, INSERT, UPDATE ON tix_subject_identifier TO dip_app;
GRANT SELECT, INSERT, UPDATE ON tix_debt_record TO dip_app;

-- Append-only by privilege, not merely by convention: no UPDATE, no DELETE.
GRANT SELECT, INSERT ON audit_event TO dip_app;

-- ---------------------------------------------------------------------------
-- Session helpers.
--
-- The tenant of the current request, and the flag that marks a transaction as an exchange
-- read. Both are session settings written by the application; missing or empty means NULL,
-- which makes every policy comparison false and therefore fails closed.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION app_current_tenant() RETURNS UUID
    LANGUAGE sql STABLE
    AS $$ SELECT NULLIF(current_setting('app.tenant_id', TRUE), '')::UUID $$;

-- TRUE only inside a transaction that explicitly opted in with SET LOCAL. Transaction-scoped
-- by construction, so the flag cannot survive onto a pooled connection.
CREATE OR REPLACE FUNCTION app_exchange_mode() RETURNS BOOLEAN
    LANGUAGE sql STABLE
    AS $$ SELECT COALESCE(current_setting('app.exchange', TRUE), '') = 'on' $$;

COMMENT ON FUNCTION app_exchange_mode() IS
    'TIX reads debt records across operators. Set with SET LOCAL inside the reading '
    'transaction only. It relaxes reads, never writes.';

-- ---------------------------------------------------------------------------
-- Policies.
--
-- USING governs which rows are visible; WITH CHECK governs which rows may be written.
-- Exchange mode appears only in USING, so a transaction that can read across operators
-- still cannot write outside its own tenant.
-- ---------------------------------------------------------------------------
DROP POLICY IF EXISTS tix_debt_tenant_isolation ON tix_debt_record;
CREATE POLICY tix_debt_tenant_isolation ON tix_debt_record
    USING (tenant_id = app_current_tenant() OR app_exchange_mode())
    WITH CHECK (tenant_id = app_current_tenant());

DROP POLICY IF EXISTS user_account_tenant_isolation ON user_account;
CREATE POLICY user_account_tenant_isolation ON user_account
    USING (tenant_id = app_current_tenant())
    WITH CHECK (tenant_id = app_current_tenant());

-- tix_subject and tix_subject_identifier carry no tenant_id and are deliberately shared: they
-- are the spine several operators hold records against. What an operator *asserts* about a
-- subject is tenant-owned and covered above.
