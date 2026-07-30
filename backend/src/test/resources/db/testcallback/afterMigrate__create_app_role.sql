-- TEST-ONLY Flyway callback. Runs after every migration in the test suite.
--
-- Creates the unprivileged role the application connects as, so RLS is actually
-- exercised: the migration ran as the owner/superuser (which bypasses RLS), and a
-- suite that also queried as that role would report every isolation test green while
-- the policies did nothing.
--
-- This is NOT in src/main: provisioning database roles belongs to the deployment
-- (compose / Helm / Terraform), not to a migration. The production install guide must
-- create an equivalent role, and must NOT grant it BYPASSRLS or table ownership.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tcms_app') THEN
        CREATE ROLE tcms_app LOGIN PASSWORD 'app-secret';
    END IF;
END $$;

-- Deliberately no BYPASSRLS, no SUPERUSER, no ownership of any table.
ALTER ROLE tcms_app NOBYPASSRLS NOSUPERUSER NOCREATEDB NOCREATEROLE;

GRANT USAGE ON SCHEMA public TO tcms_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO tcms_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO tcms_app;

-- Tables created by later migrations must be reachable too.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tcms_app;
