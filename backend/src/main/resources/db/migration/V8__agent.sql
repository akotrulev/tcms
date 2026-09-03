-- =============================================================================
-- V8 — agent execution (plan 009 module M5, plan 015)
--
-- THE VERSION IS V8, AND THE DIRECTORY IS WHAT SAYS SO. The 009 table reserved
-- V8 for M5 on the assumption M4 took V7 — which it did (2026-09-03, plan 014),
-- so this is genuinely V8. `out-of-order` is unset in application.yml, so a
-- reused number is SILENTLY not applied and Testcontainers builds every test
-- database from empty and cannot show the conflict. Re-read on 2026-09-03:
-- V1..V7, V7 being M4's. This is V8.
--
-- One reference table (project_repository, D16), two columns on issue
-- (agent_eligible per D30, pr_url), and one on project_settings
-- (review_enabled_issue per D33). NO agent principal, NO orchestrator table, NO
-- runner, NO forge adapter, NO model-credential column (D25, D26): an assistant
-- is an api_token the caller owns carrying the Agent role, which V1 already has.
-- =============================================================================

-- 1. REPOSITORIES — reference data (D16). A project spans many; each has its own
--    build command. The server knows WHICH repositories; the local MCP config
--    knows WHERE they are checked out (D25's amendment). No local path here.
CREATE TABLE project_repository (
    id             UUID        PRIMARY KEY,
    company_id     UUID        NOT NULL REFERENCES company(id),
    project_id     UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name           TEXT        NOT NULL,
    url            TEXT        NOT NULL,
    default_branch TEXT        NOT NULL,
    subdirectory   TEXT        NOT NULL DEFAULT '/',
    build_command  TEXT        NOT NULL,
    position       INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by     UUID,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by     UUID,
    CONSTRAINT uq_project_repository_name UNIQUE (company_id, project_id, name)
);
COMMENT ON TABLE project_repository IS
    'Reference data (D16): the repositories a project spans and how each is built. This product '
    'never holds, clones or builds a repository — this is what a new joiner or an assistant is '
    'told to check out. A project with none is normal, so tcms_seed_project_defaults seeds none. '
    'THERE IS NO LOCAL PATH COLUMN: the checkout path is machine-specific and lives in the '
    'developer''s MCP config, not here (D25). THERE IS NO CREDENTIAL COLUMN: this product opens '
    'no PR and clones nothing.';
COMMENT ON COLUMN project_repository.position IS
    'Order within the project, from 0. The lowest-position row renders as "primary" '
    '(settings-repositories.html); primary is computed, not stored — a second boolean would be a '
    'second source of truth for the same fact.';
COMMENT ON COLUMN project_repository.build_command IS
    'The verify command for THIS repository (D16). One command per repository, never one per '
    'project: TCMS is itself three stacks with three gates and no root build, and a single '
    'wrapper would say "the build broke" rather than which stack.';

CREATE INDEX ix_project_repository_project
    ON project_repository (company_id, project_id, position);

-- 2. ISSUE — two columns. agent_eligible defaults false on every existing issue.
ALTER TABLE issue ADD COLUMN agent_eligible BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE issue ADD COLUMN pr_url TEXT;
COMMENT ON COLUMN issue.agent_eligible IS
    'D30 — a hint that this issue is a good candidate for an LLM, and the default filter of the '
    'assistant''s queue. A boolean and a filter, NOT an assignment target (D26): assignment stays '
    'person to person.';
COMMENT ON COLUMN issue.pr_url IS
    'The pull request an assistant (or a person) opened, reported back through PATCH. This product '
    'opens no PR — the local agent does, with the developer''s own git (D25). The change is '
    'recorded on the issue''s history, shown as "attached PR".';

-- 3. PROJECT_SETTINGS — the review-to-close gate. Off by default (D33) so a
--    one-person company can still close its own work (A6); a lead turns it on.
ALTER TABLE project_settings
    ADD COLUMN review_enabled_issue BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN project_settings.review_enabled_issue IS
    'D33 — "Require review to close issues". OFF BY DEFAULT. When true and '
    'author_may_approve_own_work is false, an issue''s assignee may not move it into a DONE-kind '
    'status; a second person must. Off by default because the opposite would dead-end a one-person '
    'company the moment its own assistant worked a task (A6).';

-- 4. ROW LEVEL SECURITY — one more forced policy, predicate byte-identical to V1
--    (SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate compares it,
--    and fails equally on an explicit WITH CHECK — a USING-only policy reuses
--    USING as the insert check, so the read boundary IS the write boundary).
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing,
-- but FORCE is what makes the policy apply to a table's OWNER too, and
-- tcms_owner is what Flyway connects as. Without it a migration or an
-- owner-run query reads every company's rows (docs/specs/tenancy-invariants.md).
--
-- issue and project_settings already carry their V1 policies; a column added to
-- a protected table is protected by it.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['project_repository'];
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
        RAISE EXCEPTION 'expected 1 new company-isolated table, found %', array_length(protected, 1);
    END IF;
END $$;

-- 5. tcms_seed_project_defaults IS DELIBERATELY NOT TOUCHED
--
-- A project with no repositories is the normal, opt-in case (D16): there is no
-- row a new project should be seeded with. The function is not restated "to be
-- safe" — a reproduction is the only way this file could break it, since
-- CREATE OR REPLACE FUNCTION replaces the whole body (V5's header, V7's §5).
--
-- The app role (tcms_app) needs no explicit GRANT here: the two-role split
-- grants it SELECT/INSERT/UPDATE/DELETE on every new table in schema public
-- through ALTER DEFAULT PRIVILEGES FOR ROLE tcms_owner, set up before Flyway
-- runs (deploy/postgres/initdb/10-tcms-roles.sql). V5/V6/V7 add tables and
-- likewise grant nothing — this file follows that idiom.
