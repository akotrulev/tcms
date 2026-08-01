-- =============================================================================
-- V18 — every project gets a documentation space, so a page has somewhere to go
--
-- `doc_page.space_id` is NOT NULL REFERENCES doc_space(id), and nothing has ever
-- created a doc_space row: V2's tcms_seed_project_defaults seeds the Feature
-- dimension, the six case statuses and the Default configuration, and stops
-- there. That was correct for as long as nothing wrote a page — which is exactly
-- how long it was invisible.
--
-- So this migration is the documentation module's V5: a seed row that no path
-- created, discovered by the first code that needed it rather than by reading the
-- schema. The same shape, and worth naming as a shape — a NOT NULL FK to a table
-- with no writer is a latent failure that only the feature depending on it can
-- surface.
--
-- -----------------------------------------------------------------------------
-- ONE SPACE PER PROJECT, AND WHY THERE IS NO UNIQUE INDEX SAYING SO
-- -----------------------------------------------------------------------------
--
-- The ERD models doc_space as a container a project may eventually have several
-- of (spaces per team, per release). Today there is exactly one and the API has
-- no notion of a space at all, so DocSpaceRepository takes the project's space
-- and throws if there is none, naming provision-company as the repair — the same
-- contract DisplayKeys has with key_sequence.
--
-- A UNIQUE (project_id) index would make today's "one" permanent, and would have
-- to be dropped by the migration that adds the second space. `WHERE NOT EXISTS`
-- gives idempotence without foreclosing that, which is the same trade V2 makes
-- for the Default configuration.
--
-- -----------------------------------------------------------------------------
-- WHY THE BACKFILL SETS A COMPANY CONTEXT PER COMPANY
-- -----------------------------------------------------------------------------
--
-- doc_space is under FORCE ROW LEVEL SECURITY, which covers the owner this
-- migration runs as (V1; §19). With no context set the policy matches no row, so
-- an INSERT … SELECT FROM project would read nothing, write nothing, and report
-- success — the zero-rows-in-production shape V7 already paid for once. The loop
-- below is V15's, for V15's reason, including the reset to '' at the end so the
-- connection Flyway hands back is context-less rather than holding the last
-- company iterated.
-- =============================================================================


-- =============================================================================
-- 1. THE SEED FUNCTION — V2's body, plus the space
-- =============================================================================
--
-- Reproduced in full rather than patched, because CREATE OR REPLACE replaces the
-- whole body: anything omitted here stops being seeded, silently, for every
-- project created from now on. The three existing blocks are byte-identical to
-- V2's; the doc_space block at the end is the addition.

CREATE OR REPLACE FUNCTION tcms_seed_project_defaults(p_company_id UUID, p_project_id UUID)
    RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_spec RECORD;
BEGIN
    PERFORM tcms_assert_company_context(p_company_id);

    IF NOT EXISTS (SELECT 1 FROM project WHERE id = p_project_id) THEN
        RAISE EXCEPTION 'project % is not visible in company %', p_project_id, p_company_id
            USING ERRCODE = '23503';
    END IF;

    -- Feature is the one PROJECT-scoped dimension, and the only hierarchical and
    -- unlocked one: two client products have different feature trees, and the
    -- tree is meant to be edited (plan §6d). Seeded empty — inventing a client's
    -- feature taxonomy for them is how a controlled vocabulary gets worked around.
    INSERT INTO classification_dimension
        (id, company_id, project_id, key, name, cardinality, controlled, hierarchical, locked, position)
    SELECT gen_random_uuid(), p_company_id, p_project_id, 'feature', 'Feature',
           'MULTI', true, true, false, 3
     WHERE NOT EXISTS (
        SELECT 1 FROM classification_dimension
         WHERE company_id = p_company_id AND project_id = p_project_id AND key = 'feature');

    -- The six default execution statuses. `kind` is what lets reporting aggregate
    -- a project's custom statuses without knowing their names, and counts_as_pass
    -- is separate from kind because a project may add a "Passed with comment".
    -- Colours match the frontend design tokens deliberately: status colour is
    -- load-bearing data in a TCMS and is NOT brandable (§6u).
    FOR v_spec IN
        SELECT * FROM (VALUES
            ('Passed',   'PASSED',   '#2e7d5b', true,  true,  0),
            ('Failed',   'FAILED',   '#c0392b', true,  false, 1),
            ('Blocked',  'BLOCKED',  '#b7791f', true,  false, 2),
            ('Skipped',  'SKIPPED',  '#6b7280', true,  false, 3),
            ('Retest',   'RETEST',   '#7c5cbf', false, false, 4),
            ('Untested', 'UNTESTED', '#9ca3af', false, false, 5)
        ) AS s(name, kind, color, is_final, counts_as_pass, position)
    LOOP
        INSERT INTO case_status
            (id, company_id, project_id, name, kind, color, is_final, counts_as_pass, position)
        SELECT gen_random_uuid(), p_company_id, p_project_id, v_spec.name, v_spec.kind,
               v_spec.color, v_spec.is_final, v_spec.counts_as_pass, v_spec.position
         WHERE NOT EXISTS (
            SELECT 1 FROM case_status
             WHERE company_id = p_company_id AND project_id = p_project_id AND name = v_spec.name);
    END LOOP;

    -- Every project gets a Default configuration so test_run.configuration_id can
    -- stay NOT NULL all the way through the rollup read models — suite_run_summary
    -- and plan_snapshot key on it, and a nullable PK column is not expressible.
    INSERT INTO configuration (id, company_id, project_id, name, attributes, is_default)
    SELECT gen_random_uuid(), p_company_id, p_project_id, 'Default', '{}'::jsonb, true
     WHERE NOT EXISTS (
        SELECT 1 FROM configuration WHERE project_id = p_project_id AND is_default);

    -- V18. One documentation space, for the same structural reason as the
    -- configuration above: doc_page.space_id is NOT NULL, so without this row the
    -- project cannot hold a page at all.
    --
    -- It is NOT the "inventing the client's structure" mistake that keeps a
    -- Default *suite* from being seeded here. A suite is a decision about how
    -- somebody's tests are organised and appears in their tree; the space is a
    -- container the API does not expose, has no name anybody reads, and holds
    -- every page whatever its shape. The tree the client actually organises is
    -- doc_page.parent_id, and this seeds none of that.
    INSERT INTO doc_space (id, company_id, project_id, name)
    SELECT gen_random_uuid(), p_company_id, p_project_id, 'Documentation'
     WHERE NOT EXISTS (
        SELECT 1 FROM doc_space WHERE project_id = p_project_id);
END $$;

COMMENT ON FUNCTION tcms_seed_project_defaults(UUID, UUID) IS
    'Called once per project, by project creation and by provision-company for the first project. '
    'Idempotent, and refuses a project it cannot see — which under RLS is also a project in '
    'another company. Seeds the Feature dimension, the six case statuses, the Default '
    'configuration and (V18) the documentation space.';


-- =============================================================================
-- 2. THE BACKFILL — every project that already exists
-- =============================================================================
--
-- Without it, documentation works on projects created after this release and 404s
-- on every project created before it, which is the worst of the three possible
-- states: it looks like a bug in the feature rather than a missing row.
--
-- Re-uses the seed function rather than issuing its own INSERT, so there is one
-- definition of what a seeded project has. The function is idempotent, so
-- re-seeding a project that already has everything writes nothing.

DO $$
DECLARE
    v_company RECORD;
    v_project RECORD;
    v_total   integer := 0;
BEGIN
    FOR v_company IN SELECT id FROM company ORDER BY id LOOP
        PERFORM set_config('app.current_company', v_company.id::text, true);

        FOR v_project IN SELECT id FROM project ORDER BY id LOOP
            PERFORM tcms_seed_project_defaults(v_company.id, v_project.id);
            v_total := v_total + 1;
        END LOOP;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);

    RAISE NOTICE 'V18: re-seeded % existing project(s) so each has a documentation space', v_total;
END $$;
