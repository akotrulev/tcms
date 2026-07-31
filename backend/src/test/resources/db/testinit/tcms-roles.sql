-- TEST-ONLY. The suite's copy of the deployment's privilege split, run as a
-- Testcontainers init script — i.e. BEFORE Flyway, which is the same ordering the
-- deployment has (initdb → Flyway) and the opposite of what this suite used to do.
--
-- MIRRORS deploy/postgres/initdb/10-tcms-roles.sql. Read that file for the reasoning;
-- only the differences are argued here. Keep the two in step: this one is the only thing
-- that will tell you when the deployment's arrangement stops working.
--
-- WHY THIS FILE REPLACED A FLYWAY afterMigrate CALLBACK
--
-- The old harness created tcms_app *after* migrating, and migrated as the container's
-- default role — a SUPERUSER. A superuser bypasses RLS unconditionally, FORCE included,
-- so every policy in the schema was advisory under test and mandatory in production.
-- That is not a hypothetical gap: it hid a HIGH defect through 171 green tests (V7's
-- SECURITY DEFINER company lookup, which returned zero rows in production; see the V8
-- header). The suite could not have caught it, because the suite did not have
-- production's roles.
--
-- Three roles exist here, where the deployment has two:
--
--   tcms_super  the container's own superuser. Creates the two real roles, and is then
--               used ONLY as ground truth in assertions ("what should this query not have
--               been able to see"). Nothing under test ever connects as it. The
--               deployment has no equivalent and needs none — initdb runs as it there.
--   tcms_owner  owns the database and everything Flyway creates. NOT a superuser, so
--               FORCE ROW LEVEL SECURITY actually covers it, exactly as in production.
--   tcms_app    the application pool. Owns nothing, creates nothing, no BYPASSRLS.
--
-- Two deliberate departures from the deployment file, both forced by the mechanism:
--
--   * Passwords are literals, not \getenv. Testcontainers executes this over JDBC, where
--     psql meta-commands (\getenv, \connect, \set) do not exist. These credentials reach
--     a container that lives for one test run and publishes a random port; the
--     deployment's file is the one that must never carry a credential.
--   * No CREATE DATABASE / \connect. Testcontainers has already created `tcms` and
--     connected us to it, so ownership is transferred instead. ALTER DATABASE ... OWNER
--     is what makes tcms_owner the pg_database_owner, which is in turn what gives it
--     CREATE on schema public (PG15+) — the same indirection the deployment relies on,
--     rather than an ALTER SCHEMA shortcut that would let the deployment's version rot.

CREATE ROLE tcms_owner LOGIN PASSWORD 'owner-secret'
    NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

CREATE ROLE tcms_app LOGIN PASSWORD 'app-secret'
    NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

ALTER DATABASE tcms OWNER TO tcms_owner;

REVOKE ALL ON DATABASE tcms FROM PUBLIC;
GRANT CONNECT ON DATABASE tcms TO tcms_app;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO tcms_app;

-- Default privileges rather than GRANT ON ALL TABLES, because this runs before Flyway so
-- there is nothing to grant on yet — and because it is the mechanism the deployment uses.
-- The old afterMigrate callback used the ALL TABLES form, which would have masked a
-- broken ALTER DEFAULT PRIVILEGES in the deployment script indefinitely.
ALTER DEFAULT PRIVILEGES FOR ROLE tcms_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tcms_app;

ALTER DEFAULT PRIVILEGES FOR ROLE tcms_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO tcms_app;

-- Functions created by later migrations, e.g. V8's tcms_companies_for_user, which revokes
-- EXECUTE from PUBLIC. V8 also grants it to tcms_app directly — conditionally, because the
-- role's existence is a deployment concern; here it does exist, so that branch is now
-- exercised by the suite for the first time.
ALTER DEFAULT PRIVILEGES FOR ROLE tcms_owner IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO tcms_app;

-- Deliberately NOT granted, matching the deployment: TRUNCATE (it bypasses row-level
-- DELETE policies entirely) and CREATE on schema public. PrivilegeSplitTest asserts both.
