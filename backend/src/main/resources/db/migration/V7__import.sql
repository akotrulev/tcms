-- =============================================================================
-- V7 — the Jira offramp (plan 009 module M4, plan 014)
--
-- Two tables, so the report outlives the migration and can be reopened a year
-- later when the source system has been decommissioned, plus one nullable
-- column on issue_comment.
--
-- THE VERSION IS V7, AND THE DIRECTORY IS WHAT SAYS SO. The 009 status table
-- reserves V8 for this module on the assumption that M5 lands first; it has
-- not. `out-of-order` is unset in application.yml, so a number that has already
-- been used is SILENTLY not applied, and Testcontainers builds every test
-- database from empty and structurally cannot show you the conflict. The
-- directory was re-read on 2026-09-01 and held V1..V6, V6 being M3's, so this
-- is V7 and M5 takes V8. That is the third correction to that column in a
-- month, from three unrelated causes — see V6's header for the first two.
--
-- WHAT IS DELIBERATELY NOT HERE: a credential column.
-- Review R14 asked whether the importer may reuse integration.tracker's
-- per-person credential; it may not — ArchitectureRulesTest computes a type's
-- module from its first package segment, so tracker.imports IS tracker, and
-- tracker does not declare integration. The obvious second option was an
-- encrypted column here, the way tracker_identity does it. That is refused
-- too, and for a reason about keys rather than about imports: the cipher is
-- integration.SecretCipher, equally unreachable, so an encrypted column here
-- means a SECOND AES key user in a second module — which SecretCipher's own
-- header names as "how a rotation misses one".
--
-- So the connection is entered per run, carried in the request body, held for
-- the life of the run and written nowhere. import-connect.html already
-- promises exactly that: "The token is used for this import and is not stored
-- afterwards." A column absent by decision is indistinguishable from one
-- nobody thought of, which is why this paragraph is here — and why it is in
-- ERD §7d as well, since the ERD is where somebody looks for it first.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. THE RUN
-- -----------------------------------------------------------------------------

CREATE TABLE import_run (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    provider                TEXT        NOT NULL
                            CHECK (provider IN ('JIRA_CLOUD','JIRA_DATA_CENTER','AZURE_DEVOPS')),
    site_url                TEXT        NOT NULL,
    account_email           TEXT,
    source_project_key      TEXT        NOT NULL,
    status                  TEXT        NOT NULL
                            CHECK (status IN ('RUNNING','COMPLETED','FAILED')),
    issues_imported         INTEGER     NOT NULL DEFAULT 0,
    releases_created        INTEGER     NOT NULL DEFAULT 0,
    comments_imported       INTEGER     NOT NULL DEFAULT 0,
    labels_created          INTEGER     NOT NULL DEFAULT 0,
    dropped_value_count     INTEGER     NOT NULL DEFAULT 0,
    label_per_sprint        BOOLEAN     NOT NULL DEFAULT false,
    backlog_order_preserved BOOLEAN     NOT NULL DEFAULT false,
    status_mapping          JSONB       NOT NULL DEFAULT '[]'::jsonb,
    people                  JSONB       NOT NULL DEFAULT '[]'::jsonb,
    failure_reason          TEXT,
    started_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_by              UUID,
    finished_at             TIMESTAMPTZ,
    created_idempotency_key TEXT
);
COMMENT ON TABLE import_run IS
    'One migration off Jira or Azure DevOps, in flight or finished. It OUTLIVES ITS SOURCE: the '
    'point of storing the mapping and the people rather than re-deriving them is that a year '
    'later the Jira site is gone, its statuses were renamed here, and the report still has to '
    'say what happened. Reports are kept indefinitely (settings-import.html).';
COMMENT ON COLUMN import_run.site_url IS
    'The site, for the record. THERE IS NO TOKEN COLUMN — see this file''s header. The credential '
    'is entered per run and written nowhere, which is what import-connect.html''s "not stored '
    'afterwards" promises.';
COMMENT ON COLUMN import_run.status IS
    'RUNNING -> COMPLETED | FAILED, forwards only. A re-request is a new row, so the record of '
    'what was asked for and what it produced stays one request per row — report_job''s state '
    'machine and its argument. A row stuck RUNNING past tcms.tracker.imports.run-timeout is '
    'moved to FAILED by ImportReaper, because a spinner with no end and no error is what the '
    'ReportJobRunner reaper was added to stop.';
COMMENT ON COLUMN import_run.status_mapping IS
    'The mapping AS APPLIED, each entry carrying the target status''s name and kind captured at '
    'the time. JSONB and not a table with an FK, for the reason the people column shares: a '
    'status is removable through the workflow screen, so an FK would either cascade the report '
    'away or block the removal — and the report''s whole promise is that it outlives what it '
    'names. Read whole by one screen, joined to by nothing.';
COMMENT ON COLUMN import_run.people IS
    'Source author -> matched user, or unmatched. Same argument as status_mapping: a user can be '
    'deactivated, and the name is what the report has to be able to say.';
COMMENT ON COLUMN import_run.backlog_order_preserved IS
    'FALSE means issue.rank came from the BEFORE INSERT trigger — max(rank) + 1024 in insert '
    'order, WHICH IS AN ORDERING NOBODY CHOSE. The report says so rather than letting the '
    'backlog read as imported (plan 014 D-i).';
CREATE INDEX ix_import_run_project ON import_run (company_id, project_id, started_at DESC);
CREATE UNIQUE INDEX uq_import_run_idempotency
    ON import_run (company_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
-- One completed import per source project per project: there is no delta import
-- and no reconciliation, so a second run would try to create a second
-- PROJ-4812 and die on uq_issue_key at minute four. This turns that into a 409
-- a person can read. Partial on COMPLETED, so a FAILED run may be retried.
CREATE UNIQUE INDEX uq_import_run_completed_source
    ON import_run (company_id, project_id, provider, site_url, source_project_key)
    WHERE status = 'COMPLETED';


-- -----------------------------------------------------------------------------
-- 2. WHAT WAS DROPPED — ROWS, NOT A PARAGRAPH
-- -----------------------------------------------------------------------------

CREATE TABLE import_dropped_field (
    id            UUID    PRIMARY KEY,
    company_id    UUID    NOT NULL REFERENCES company(id),
    import_run_id UUID    NOT NULL REFERENCES import_run(id) ON DELETE CASCADE,
    field_name    TEXT    NOT NULL,
    field_type    TEXT    NOT NULL,
    value_count   INTEGER NOT NULL,
    reason        TEXT    NOT NULL,
    "position"    INTEGER NOT NULL,
    CONSTRAINT uq_import_dropped_field UNIQUE (import_run_id, field_name)
);
COMMENT ON TABLE import_dropped_field IS
    'D15''s report, one row per field type the import did not bring across. ROWS AND NOT A '
    'RENDERED STRING: a year on, the question is "how many Story Points values did we drop '
    'across every import", and a paragraph cannot answer it. THE SAME LIST THE PREVIEW SHOWED — '
    'one derivation in tracker/imports/DroppedFields.java feeds both, and '
    'ImportReportMatchesPreviewTest asserts the two responses are equal element for element.';
COMMENT ON COLUMN import_dropped_field.reason IS
    'Why, in the words the preview used, STORED rather than looked up at read time. The sentence '
    'is part of what was said at the time, so editing the catalogue must not retroactively '
    'rewrite a report — the argument issue.issue_key''s comment makes about composed keys.';
CREATE INDEX ix_import_dropped_field_run ON import_dropped_field (company_id, import_run_id, "position");


-- -----------------------------------------------------------------------------
-- 3. THE AUTHOR WHO IS NOT A USER HERE
-- -----------------------------------------------------------------------------

ALTER TABLE issue_comment ADD COLUMN external_author TEXT;
COMMENT ON COLUMN issue_comment.external_author IS
    'The source system''s own author string for an imported comment whose author matched no '
    'app_user by email — j.arnold@old-vendor.test. NEVER SET TOGETHER WITH created_by, so no '
    'screen has to choose between them. import-report.html''s People panel is the promise: an '
    'unmatched author is preserved as text rather than reassigned to somebody who did not write '
    'it. NOT a prefix on the body, which would edit somebody''s words to carry metadata and '
    'would come back out as content in the next export.';


-- -----------------------------------------------------------------------------
-- 4. ROW LEVEL SECURITY — two more forced policies
-- -----------------------------------------------------------------------------
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing,
-- but FORCE is what makes the policy apply to a table's OWNER too, and
-- tcms_owner is what Flyway connects as. Without it a migration or an
-- owner-run query reads every company's rows, which is the trap
-- docs/specs/tenancy-invariants.md names.
--
-- The predicate is byte-identical to V1's, including the nullif() —
-- SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate compares them
-- and fails on a divergent one, and it fails equally on an explicit WITH CHECK,
-- which is why there is none: a policy with USING and no WITH CHECK reuses
-- USING as the insert check, so the read boundary IS the write boundary and
-- there is no second clause for somebody to relax later.
--
-- issue_comment already carries its policy; a column added to a protected
-- table is protected by it.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['import_run', 'import_dropped_field'];
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

    IF array_length(protected, 1) <> 2 THEN
        RAISE EXCEPTION 'expected 2 new company-isolated tables, found %',
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
-- ProjectKeySequenceSchemaTest is the check that one went missing.
--
-- Nothing is added to it here, and it is not reproduced "to be safe" — a
-- reproduction is the only way this file could break it. An import is
-- something a person starts once, not a per-project default, and there is no
-- row a new project should have.
