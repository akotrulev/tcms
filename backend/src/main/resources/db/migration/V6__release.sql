-- =============================================================================
-- V6 — releases, and the two keys that point at them (plan 013, module M3 of 009)
--
-- One table, two nullable foreign keys, one trigger and one widened CHECK. What
-- a person can do afterwards that they could not before: plan a version, point
-- epics and stories at it, and ask whether it can ship.
--
-- THE VERSION IS V6, NOT THE V5 THE 009 TABLE FIRST RESERVED. That column has
-- now been wrong twice from two unrelated causes: M5 was placed ahead of M4, and
-- then M1 spent two versions where the table budgeted one. `out-of-order` is
-- unset in application.yml, so a number that has already been used is SILENTLY
-- not applied, and Testcontainers builds every test database from empty and
-- structurally cannot show you the conflict. The directory is the authority;
-- it was re-read on 2026-09-01 and held V1..V5.
--
-- NOTHING IS BACKFILLED (scope D14). test_run.release_version,
-- test_run.release_sort_key and test_plan.milestone_name / milestone_date all
-- keep working exactly as they do, and no statement below reads any of them into
-- this table. The consequence is deliberate and is an acceptance criterion of
-- the module rather than a caveat about it: on the day this merges the roadmap
-- is EMPTY and readiness has no history, until somebody types a version. That is
-- the trade for having no migration to get wrong — milestone names are prose,
-- and version strings across a real instance are not as clean as they look.
--
-- A RELEASE BELONGS TO ONE PROJECT (D13), and the cost is accepted and real: a
-- company shipping 2.4 from a web project and an API project keeps two rows
-- named 2.4, gets two roadmaps and two completion percentages, and CANNOT ask
-- "is 2.4 ready?" across both. The alternative pushes a cross-project visibility
-- filter into every roadmap, completion and readiness query, where a caller may
-- hold a grant on one of the two projects and the number would be computed
-- partly from rows they cannot see. That is the §A1 boundary, and a roadmap is
-- not a good enough reason to blur it.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. THE RELEASE
-- -----------------------------------------------------------------------------
--
-- This is not a new concept — it is three loose ones finally given a table.
-- test_run.release_version has been free text since V1, test_run.release_sort_key
-- has been computed on write and read by nothing, and test_plan.milestone_name
-- has been a denormalised string beside a date. A `release` row is the structured
-- form of a thing this product has carried as strings since the beginning, and
-- all three of those keep working: the text stays the ingestion-friendly form,
-- because CI passes a string and always will.

CREATE TABLE release (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    project_id   UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    version      TEXT        NOT NULL,
    -- Computed on write by com.tcms.api.ReleaseSortKey, which moved there from
    -- com.tcms.execution in this module because `tracker` may not import
    -- `execution` and this is a pure value type with no domain concept of its
    -- own (009 review R6).
    --
    -- THIS is the column the roadmap orders by, and it is a THIRD copy of that
    -- computation's output rather than a reader of the two on test_run and
    -- test_execution: a release's ordering key belongs to the release, not to
    -- each run that happens to mention its name. Those two columns still have no
    -- WHERE and no ORDER BY anywhere in src/main and stay in
    -- ArchitectureRulesTest.READERLESS_BY_DECISION, which is the opposite of
    -- what 009 predicted and is recorded at both entries (plan 013 D-a).
    --
    -- NOT NULL: ReleaseSortKey.of falls back to the raw string for anything it
    -- cannot parse and returns null only for a blank version, which `version`
    -- forbids. A nullable key would put every non-semver release into one
    -- unordered bucket that ORDER BY places arbitrarily, and ix_release_project
    -- leads on it.
    sort_key     TEXT        NOT NULL,
    description  TEXT,
    -- Optional, and release-new.html promises so in as many words. Without one
    -- the release still exists and still appears on the roadmap, in version
    -- order, with a point marker instead of a bar.
    target_date  DATE,
    status       TEXT        NOT NULL DEFAULT 'PLANNED'
                 CHECK (status IN ('PLANNED','RELEASED')),
    released_at  TIMESTAMPTZ,
    -- M9's burn-up baseline, and the reason this column is not merely audit
    -- furniture (009 review R12): "scope added after the release was created" is
    -- the difference between this and issue_fact.release_assigned_at, and it is
    -- the one number in that chart that cannot be reconstructed afterwards.
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT      NOT NULL DEFAULT 0,
    -- A released release has a moment; a planned one does not. Stated as a
    -- constraint rather than left to the service, because this pair is what
    -- every "when did we ship 2.3" question reads, and a RELEASED row with a
    -- null timestamp answers it with silence rather than with an error.
    CONSTRAINT release_released_at_matches_status CHECK (
        (status = 'RELEASED') = (released_at IS NOT NULL))
);
COMMENT ON TABLE release IS
    'The only planning cadence in this product (scope D6). There is no sprint table, no '
    'sprint_issue, no velocity and no burndown, and a release is not a container: an issue points '
    'AT one through a nullable FK, with no join table. It is the one object both halves of this '
    'product care about — delivery asks whether the stories for 2.4 are done, QA already asks '
    'whether the runs for 2.4 passed, and release readiness is those two joined, which is the '
    'question neither a tracker nor a test tool answers alone. Project-scoped per D13.';
COMMENT ON COLUMN release.sort_key IS
    'Computed on write from version by com.tcms.api.ReleaseSortKey — %05d.%05d.%05d.<precedence>, '
    'so 1.10 sorts after 1.9 AND 4.3.0-rc.1 before 4.3.0. Getting this wrong is silent: text '
    'collation answers about a release from three months ago with a number that looks exactly '
    'right. Never accepted from a caller; Release.rename is the one place it and `version` change.';
COMMENT ON COLUMN release.created_at IS
    'M9''s burn-up baseline (009 review R12), not audit furniture. "Scope added after the release '
    'was created" is the count of issues whose issue_fact.release_assigned_at is later than this, '
    'and that comparison is the one line of the burn-up chart that cannot be reconstructed after '
    'the fact. Never edited.';

-- lower(version), so two rows differing only in capitals are one release rather
-- than one version rendered twice. Leading company_id as every composite index
-- in this schema does — the RLS predicate is on it and an index that does not
-- lead with it is not used by the filtered scan; SchemaConventionTest checks it
-- mechanically.
CREATE UNIQUE INDEX uq_release_version ON release (company_id, project_id, lower(version));
CREATE INDEX ix_release_project ON release (company_id, project_id, sort_key);


-- -----------------------------------------------------------------------------
-- 2. WHAT POINTS AT A RELEASE
-- -----------------------------------------------------------------------------
--
-- A NULLABLE FK AND NO JOIN TABLE (D6). A story or a task either has a target
-- release or it does not; there is no issue_release, and there will not be one.
--
-- ON DELETE SET NULL rather than RESTRICT, and that is a product decision rather
-- than a default: a release is a PLAN, and deleting a plan must not require
-- deleting or reassigning the work first. The issues survive with no target,
-- which is exactly the state they were in before anybody scheduled them and
-- exactly what the backlog's "Unscheduled" section renders.
ALTER TABLE issue ADD COLUMN release_id UUID REFERENCES release(id) ON DELETE SET NULL;
CREATE INDEX ix_issue_release ON issue (company_id, project_id, release_id);

COMMENT ON COLUMN issue.release_id IS
    'The target release, or NULL. Nullable on purpose: not all work belongs to a version, and '
    'requiring one would force a junk release per stray bug — the same argument scope §4 makes for '
    'an optional epic. There is no join table (D6): an issue has at most one target release, and '
    'a version shipped from two projects is two releases (D13), so a many-to-many would model a '
    'relationship this product has decided does not exist.';

-- THE FK FROM AN EXECUTION TABLE INTO A TRACKER TABLE, AND IT IS ALLOWED
-- (009 review R6). ArchUnit governs JAVA IMPORTS, not DDL, and scope §3's "no
-- foreign key" cost applies to POLYMORPHIC references — issue_link.target_id,
-- which points at four kinds of row and can have no FK at all — which this is
-- not. `execution` never imports `tracker`: it holds a UUID and the database
-- keeps it honest.
--
-- It sits BESIDE release_version, which keeps working and is not deprecated: CI
-- passes a string and always will, and every existing release filter in this
-- product is an exact match on that text.
ALTER TABLE test_run ADD COLUMN release_id UUID REFERENCES release(id) ON DELETE SET NULL;
CREATE INDEX ix_test_run_release ON test_run (company_id, release_id);

COMMENT ON COLUMN test_run.release_id IS
    'Resolved from release_version by tcms_test_run_release_link() on insert, and NULL when no '
    'release row of that version existed at the time — which, given D14''s no-backfill, is every '
    'run in every existing instance. THAT IS WHY READINESS DOES NOT READ THIS COLUMN: it matches '
    'execution_fact.release_version as text, so a version with three hundred executions against it '
    'is never reported as untested (plan 013 D-b). This column is here for M9''s burn-up and M4''s '
    'importer, and because adding a foreign key to this table later is a lock on the busiest table '
    'in the product. It is deliberately NOT mapped on the TestRun entity: a mapped column needs a '
    'reader and a writer or an entry in one of ArchitectureRulesTest''s two by-decision lists, and '
    '§38''s rule stands — mapping a column nothing here maintains is how it comes to disagree with '
    'the field it derives from.';

-- A COLUMN NOTHING WRITES IS A COLUMN THAT LIES, so this one gets a trigger
-- rather than a service. It is tcms_issue_rank_default's argument one table
-- over: the resolution has to happen on EVERY creation path, including the CI
-- ingestion door and the ones written later, and a caller that forgets does not
-- fail — it records a run against no release at all, silently.
--
-- Doing it in Java is not available anyway: `execution` may not import
-- `tracker`, so TestRunService cannot resolve a release without a new SPI in a
-- foundation module for one nullable id.
--
-- BEFORE INSERT ONLY. TestRun maps release_version as updatable = false, so
-- there is no update path that could change the answer, and a BEFORE UPDATE arm
-- would re-resolve on every unrelated edit for nothing. The consequence is
-- stated rather than hidden: a run recorded BEFORE its release row exists keeps
-- a NULL forever, which is the whole of D-b.
--
-- THIS IS NOT A BACKFILL. It fires on rows written from here on; no existing row
-- is touched, and D14 stands.
--
-- The SELECT runs as the INVOKER, so it is subject to the same policy as the
-- INSERT that fired it — it can only see releases inside the company whose
-- context is open, which is what makes the match mean "this project's release"
-- rather than "some company's".
CREATE FUNCTION tcms_test_run_release_link() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.release_id IS NULL AND NEW.release_version IS NOT NULL THEN
        SELECT r.id INTO NEW.release_id
          FROM release r
         WHERE r.company_id = NEW.company_id
           AND r.project_id = NEW.project_id
           AND lower(r.version) = lower(NEW.release_version);
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER test_run_release_link
    BEFORE INSERT ON test_run
    FOR EACH ROW EXECUTE FUNCTION tcms_test_run_release_link();


-- -----------------------------------------------------------------------------
-- 3. THE SIXTH SWIMLANE AXIS
-- -----------------------------------------------------------------------------
--
-- V5 left RELEASE out deliberately, and said why: "a swimlane axis whose column
-- is NULL for every row renders one lane called None, which reads as a broken
-- board rather than as an unbuilt feature". The column now exists, so the axis
-- does.
--
-- board_swimlane_by_check is the name Postgres gives an inline column CHECK —
-- <table>_<column>_check — and it was CONFIRMED against a real Postgres 17
-- rather than assumed, because a DROP CONSTRAINT naming a constraint that does
-- not exist fails on every instance and passes every Testcontainers run that
-- builds the table from this file.
ALTER TABLE board DROP CONSTRAINT board_swimlane_by_check;
ALTER TABLE board ADD CONSTRAINT board_swimlane_by_check
    CHECK (swimlane_by IN ('NONE','EPIC','ASSIGNEE','LABEL','PRIORITY','RELEASE'));


-- -----------------------------------------------------------------------------
-- 4. ROW LEVEL SECURITY — one more forced policy
-- -----------------------------------------------------------------------------
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing,
-- but FORCE is what makes the policy apply to a table's OWNER too, and
-- tcms_owner is what Flyway connects as. Without it a migration or an owner-run
-- query reads every company's rows, which is the trap
-- docs/specs/tenancy-invariants.md names.
--
-- The predicate is byte-identical to V1's, including the nullif() —
-- SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate compares them
-- and fails on a divergent one, and it fails equally on an explicit WITH CHECK,
-- which is why there is none: a policy with USING and no WITH CHECK reuses USING
-- as the insert check, so the read boundary IS the write boundary and there is
-- no second clause for somebody to relax later.
--
-- `issue`, `test_run` and `board` already carry their policies; a column added
-- to a protected table is protected by it.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['release'];
BEGIN
    FOREACH t IN ARRAY protected LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                        WHERE table_schema = current_schema() AND table_name = t) THEN
            RAISE EXCEPTION 'the RLS list names a table that does not exist: %', t;
        END IF;
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format(
            'CREATE POLICY company_isolation ON %I USING '
            '(company_id = nullif(current_setting(''app.current_company'', true), '''')::uuid)', t);
    END LOOP;

    IF array_length(protected, 1) <> 1 THEN
        RAISE EXCEPTION 'expected 1 new company-isolated table, found %',
            array_length(protected, 1);
    END IF;
END $$;


-- -----------------------------------------------------------------------------
-- 5. tcms_seed_project_defaults IS DELIBERATELY NOT TOUCHED
-- -----------------------------------------------------------------------------
--
-- V5's header explains why the whole function is restated whenever it changes:
-- CREATE OR REPLACE FUNCTION replaces the BODY, so a block left out of the
-- reproduction silently stops being seeded for every project created from that
-- migration onward — no error, no failing insert, just a project missing
-- something nobody notices until they open the screen that needs it.
--
-- It is not restated here because NOTHING IS ADDED TO IT. A seeded release would
-- be a version this product invented for a client — 0.1? 1.0? — and D14's whole
-- argument is that guessing a version string is worse than an empty list a
-- person fills in. The empty roadmap is a screen's job to explain, not a
-- migration's job to paper over.
