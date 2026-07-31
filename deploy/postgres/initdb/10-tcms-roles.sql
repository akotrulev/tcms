-- The privilege split, as the deployment owns it.
--
-- This file was written because the split existed ONLY in the test suite, in a Flyway
-- afterMigrate callback whose own comment said "the production install guide must create
-- an equivalent role" — and nothing did. This is that path.
--
-- The direction has since reversed: the suite now mirrors THIS file, via
-- backend/src/test/resources/db/testinit/tcms-roles.sql, run as a Testcontainers init
-- script before Flyway. The callback is deleted. It created tcms_app *after* migrating and
-- left the owner a superuser, so the suite could not see anything that depends on the
-- owner not being one — which is exactly how V7's defect reached production. When you
-- change this file, change that one.
--
-- Two roles, and the difference between them is what the entire RLS design rests on:
--
--   tcms_owner  owns the database and every object in it. Flyway connects as this role,
--               because migrations create tables, policies and functions. NOT a
--               superuser — a superuser bypasses RLS unconditionally, and a migration
--               role that can bypass isolation is one accident away from a data path
--               that does too.
--   tcms_app    the application pool. Owns nothing, creates nothing, holds no BYPASSRLS.
--               DML only. This is the role every one of the 56 FORCE ROW LEVEL SECURITY
--               policies is actually evaluated against.
--
-- Collapsing these into one role is the tempting simplification and it is the wrong one.
-- FORCE does cover the owner, so isolation would survive — but the application would then
-- hold ALTER POLICY and DROP POLICY on all 56 tables, i.e. the isolation layer would be
-- writable by the thing it constrains.
--
-- Runs once, on an empty data directory, via the postgres image's
-- /docker-entrypoint-initdb.d hook, as the POSTGRES_USER superuser. It is NOT re-run on a
-- container restart — only when the volume is new.

\set ON_ERROR_STOP on

-- Passwords come from the environment, so no credential is committed. \getenv fails the
-- script under ON_ERROR_STOP when the variable is absent, rather than interpolating a
-- literal and creating a role nobody can log in as.
\getenv owner_password TCMS_OWNER_PASSWORD
\getenv app_password   TCMS_APP_PASSWORD

CREATE ROLE tcms_owner LOGIN PASSWORD :'owner_password'
    NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

CREATE ROLE tcms_app LOGIN PASSWORD :'app_password'
    NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

CREATE DATABASE tcms OWNER tcms_owner;

REVOKE ALL ON DATABASE tcms FROM PUBLIC;
GRANT CONNECT ON DATABASE tcms TO tcms_app;

\connect tcms

-- From PG15 the public schema is owned by pg_database_owner, so tcms_owner already holds
-- CREATE here by virtue of owning the database — including for the three extensions V1
-- installs. ltree, pg_trgm and citext are all *trusted* extensions (PG13+), which is the
-- reason a non-superuser owner is viable at all; an untrusted extension would force the
-- superuser migration role this file exists to avoid.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO tcms_app;

-- Default privileges rather than GRANT ON ALL TABLES: this runs BEFORE Flyway, so there
-- is nothing to grant on yet. The test callback uses the ALL TABLES form because it runs
-- after migration — same intent, opposite ordering. The default-privileges form also
-- covers every table a future migration adds, with no follow-up grant to forget.
ALTER DEFAULT PRIVILEGES FOR ROLE tcms_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tcms_app;

ALTER DEFAULT PRIVILEGES FOR ROLE tcms_owner IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO tcms_app;

-- Deliberately NOT granted: TRUNCATE (bypasses row-level DELETE policies entirely),
-- CREATE on schema public, and any privilege on tables owned by anyone other than
-- tcms_owner.

-- Functions created by later migrations. V7's tcms_companies_for_user is SECURITY
-- DEFINER and revokes EXECUTE from PUBLIC, so the application role needs it granted;
-- V7 does that itself when the role already exists, which it does here. The default
-- privilege below covers any later function that follows the same pattern.
--
-- Note this is NOT a blanket loosening: functions get EXECUTE to PUBLIC by default in
-- Postgres, so the restrictive step is each function's own REVOKE. This only ensures
-- tcms_app keeps what those REVOKEs take away from everyone.
ALTER DEFAULT PRIVILEGES FOR ROLE tcms_owner IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO tcms_app;
