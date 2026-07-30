-- =============================================================================
-- TCMS — Search vectors, integrity triggers, and the per-company seed functions
-- Companion to docs/specs/2026-07-30-tcms-erd.md §10 ("deliberately not in V1")
--
-- This file is DDL ONLY. The reference-data INSERTs (permission catalogue, the six
-- built-in roles, their grants) are V3 — one logical change per migration, and
-- schema and data migrations stay separate files, so a seed correction never has
-- to re-run a trigger definition and vice versa.
--
-- What is here, and why none of it could be in V1:
--   1. search_vector as GENERATED ... STORED on the three tables whose searchable
--      content is entirely same-row, and as a trigger on doc_page, whose content
--      is not (see §1 below — this is the split V1 could not have guessed at).
--   2. The SINGLE-cardinality classification check: the predicate depends on
--      another table, so no unique index can express it (ERD §538).
--   3. The team cycle + company-consistency check (ERD §217).
--   4. tcms_seed_company_defaults / tcms_seed_project_defaults — the seeded
--      vocabularies, as functions rather than INSERTs, because they are
--      COMPANY-owned data and there are no companies at migration time (§5).
-- =============================================================================


-- =============================================================================
-- 1. SEARCH VECTORS
-- =============================================================================
--
-- MEASURED, not assumed — the ERD flagged the IMMUTABLE requirement as the next
-- thing static analysis could not settle. Volatility on Postgres 17.10:
--
--   to_tsvector(regconfig, text)             i  (immutable)  <- usable
--   to_tsvector(text)                        s  (stable)     <- NOT usable
--   jsonb_to_tsvector(regconfig, jsonb, jsonb)  i             <- usable
--   jsonb_to_tsvector(jsonb, jsonb)          s                <- NOT usable
--   setweight(tsvector, "char")              i
--   concat_ws(text, VARIADIC "any")          s  (stable)     <- NOT usable
--
-- The one-argument to_tsvector forms depend on default_text_search_config, which is
-- a session GUC, so Postgres refuses them in a generation expression with
-- "generation expression is not immutable". Confirmed by trying it. Every
-- expression below therefore casts the configuration explicitly: 'english'::regconfig.
--
-- concat_ws is the trap that actually bit while writing this file: it reads as a
-- pure string function, and it is STABLE only because its VARIADIC "any" arguments
-- go through type output functions. Plain || over coalesce() is immutable and is
-- what the multi-field expression below uses instead.
--
-- Weighting follows plan §6h/§6q: title 'A', body 'B'. A match in the title must
-- always outrank one buried in step three, and ts_rank cannot recover a weight
-- that was never stored.
--
-- Why a generated column where possible, rather than a trigger everywhere: a
-- generated column cannot be forgotten. There is no INSERT path that skips it, no
-- trigger to accidentally drop, and no window where the column and the row
-- disagree. Triggers are used only where the content genuinely lives in another
-- table, which a generation expression may not read.
--
-- Why the drop-and-re-add dance: a plain column cannot be converted to a
-- generated one in place (ALTER COLUMN ... SET GENERATED ALWAYS AS is a syntax
-- error), and dropping the column silently drops its GIN index with it. Both
-- indexes are therefore recreated below. Safe here because these tables are
-- empty in every environment: V1 has never been deployed anywhere but CI.

-- test_case — everything searchable is same-row. Step text deliberately NOT
-- included: §6q searches case name and description, and case *steps* live in
-- test_step, which would make this a cross-table trigger for no stated
-- requirement. The display key is served by the trigram index and the
-- short-circuit in §6q, not by the vector.
DROP INDEX test_case_search_idx;
ALTER TABLE test_case DROP COLUMN search_vector;
ALTER TABLE test_case ADD COLUMN search_vector TSVECTOR
    GENERATED ALWAYS AS (
        setweight(to_tsvector('english'::regconfig, coalesce(title, '')), 'A')
        || setweight(to_tsvector('english'::regconfig,
                     coalesce(description, '') || ' ' || coalesce(objective, '') || ' '
                     || coalesce(expected_outcome, '') || ' ' || coalesce(gherkin_source, '')), 'B')
    ) STORED;
CREATE INDEX test_case_search_idx ON test_case USING GIN (search_vector);
COMMENT ON COLUMN test_case.search_vector IS
    'Generated, not trigger-maintained: every searchable field is same-row, so there is no INSERT '
    'path that can skip it. title weighted A, description/objective/expected outcome/Gherkin B '
    '(plan §6q). Never compute to_tsvector() at query time — that defeats the GIN index entirely.';

-- condition and shared_step — title plus the JSONB body, both same-row. This is
-- the library typeahead corpus (§6h): hundreds to low tens of thousands of rows,
-- queried on every keystroke.
DROP INDEX condition_search_idx;
ALTER TABLE condition DROP COLUMN search_vector;
ALTER TABLE condition ADD COLUMN search_vector TSVECTOR
    GENERATED ALWAYS AS (
        setweight(to_tsvector('english'::regconfig, coalesce(title, '')), 'A')
        || setweight(jsonb_to_tsvector('english'::regconfig, body, '["string"]'::jsonb), 'B')
    ) STORED;
CREATE INDEX condition_search_idx ON condition USING GIN (search_vector);
COMMENT ON COLUMN condition.search_vector IS
    'title A + body B. The ''["string"]'' filter makes jsonb_to_tsvector index string leaves only — '
    'without it the JSON keys ("action", "expected") become search terms and match everything.';

DROP INDEX shared_step_search_idx;
ALTER TABLE shared_step DROP COLUMN search_vector;
ALTER TABLE shared_step ADD COLUMN search_vector TSVECTOR
    GENERATED ALWAYS AS (
        setweight(to_tsvector('english'::regconfig, coalesce(title, '')), 'A')
        || setweight(jsonb_to_tsvector('english'::regconfig, body, '["string"]'::jsonb), 'B')
    ) STORED;
CREATE INDEX shared_step_search_idx ON shared_step USING GIN (search_vector);

-- doc_page — the one that CANNOT be generated. A page's text is in doc_block
-- rows, and a generation expression may only read its own row. Trigger-maintained
-- from two directions, which is exactly the fragility a generated column avoids
-- and the reason the other three do not use this path.
CREATE FUNCTION tcms_doc_page_search_vector(p_page_id UUID) RETURNS TSVECTOR
    LANGUAGE sql STABLE AS $$
    SELECT setweight(to_tsvector('english'::regconfig, coalesce(p.title, '')), 'A')
           || setweight(
                coalesce(
                    (SELECT jsonb_to_tsvector('english'::regconfig,
                                              jsonb_agg(b.content), '["string"]'::jsonb)
                       FROM doc_block b
                      WHERE b.page_id = p.id),
                    ''::tsvector),
                'B')
      FROM doc_page p
     WHERE p.id = p_page_id;
$$;
COMMENT ON FUNCTION tcms_doc_page_search_vector(UUID) IS
    'One recompute used by both triggers below, so the page-side and block-side paths can never '
    'produce different vectors for the same page.';

CREATE FUNCTION tcms_doc_page_search_vector_trigger() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    -- INSERT: no blocks exist yet, so this is title-only and the block trigger
    -- fills in the rest as content arrives. UPDATE OF title: blocks are re-read.
    NEW.search_vector :=
        setweight(to_tsvector('english'::regconfig, coalesce(NEW.title, '')), 'A')
        || setweight(
             coalesce((SELECT jsonb_to_tsvector('english'::regconfig,
                                               jsonb_agg(b.content), '["string"]'::jsonb)
                         FROM doc_block b WHERE b.page_id = NEW.id),
                      ''::tsvector),
             'B');
    RETURN NEW;
END $$;

CREATE TRIGGER doc_page_search_vector
    BEFORE INSERT OR UPDATE OF title ON doc_page
    FOR EACH ROW EXECUTE FUNCTION tcms_doc_page_search_vector_trigger();

CREATE FUNCTION tcms_doc_block_search_vector_trigger() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_page_id UUID := coalesce(NEW.page_id, OLD.page_id);
BEGIN
    -- Updates search_vector and NOTHING else, which is what keeps this from
    -- recursing: the doc_page trigger above fires only on INSERT or on an UPDATE
    -- naming the title column.
    UPDATE doc_page
       SET search_vector = tcms_doc_page_search_vector(v_page_id)
     WHERE id = v_page_id;
    RETURN NULL;
END $$;

CREATE TRIGGER doc_block_search_vector
    AFTER INSERT OR UPDATE OR DELETE ON doc_block
    FOR EACH ROW EXECUTE FUNCTION tcms_doc_block_search_vector_trigger();

COMMENT ON COLUMN doc_page.search_vector IS
    'Trigger-maintained, NOT generated, because the content lives in doc_block and a generation '
    'expression may only read its own row. Maintained from both sides: the page trigger on title '
    'change, the block trigger on any content change.';


-- =============================================================================
-- 2. SINGLE-CARDINALITY CLASSIFICATION
-- =============================================================================
--
-- ERD §538: enforcing "one value per SINGLE dimension" needs
-- UNIQUE (case_id, dimension_id) WHERE <dimension is SINGLE>, and a partial-index
-- predicate cannot reach classification_dimension. Hence a trigger.
--
-- It also closes a hole the denormalization itself opens, which is the more
-- interesting half: case_classification.dimension_id is a COPY of
-- classification_value.dimension_id, so a caller that writes a MULTI dimension's
-- id alongside a SINGLE dimension's value bypasses the check entirely and leaves
-- the row lying about which axis it classifies. The copy is verified here rather
-- than trusted.

CREATE FUNCTION tcms_enforce_classification_integrity() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_value_dimension_id UUID;
    v_value_company_id   UUID;
    v_cardinality        TEXT;
    v_conflicting        INT;
BEGIN
    SELECT dimension_id, company_id
      INTO v_value_dimension_id, v_value_company_id
      FROM classification_value
     WHERE id = NEW.value_id;

    IF v_value_dimension_id IS NULL THEN
        -- Under RLS a foreign-company value is invisible rather than absent, so
        -- this covers both. The FK alone cannot: it is checked past the policy.
        RAISE EXCEPTION 'classification value % is not visible in this company', NEW.value_id
            USING ERRCODE = '23503';
    END IF;

    IF v_value_company_id <> NEW.company_id THEN
        RAISE EXCEPTION 'classification value % belongs to company %, not %',
            NEW.value_id, v_value_company_id, NEW.company_id
            USING ERRCODE = '23514';
    END IF;

    IF NEW.dimension_id <> v_value_dimension_id THEN
        RAISE EXCEPTION
            'case_classification.dimension_id (%) does not match the value''s dimension (%) — '
            'the denormalized copy must agree, or the SINGLE-cardinality check below is bypassable',
            NEW.dimension_id, v_value_dimension_id
            USING ERRCODE = '23514';
    END IF;

    SELECT cardinality INTO v_cardinality
      FROM classification_dimension WHERE id = v_value_dimension_id;

    IF v_cardinality <> 'SINGLE' THEN
        RETURN NEW;
    END IF;

    -- Serialise classification changes for this case. Two concurrent inserts
    -- would otherwise each see zero existing rows and both commit; there is no
    -- row to lock for a value that does not exist yet, so the parent is the lock.
    PERFORM 1 FROM test_case WHERE id = NEW.case_id FOR NO KEY UPDATE;

    SELECT count(*) INTO v_conflicting
      FROM case_classification
     WHERE case_id = NEW.case_id
       AND dimension_id = NEW.dimension_id
       AND value_id <> NEW.value_id;

    IF v_conflicting > 0 THEN
        RAISE EXCEPTION
            'case % already has a value for SINGLE-cardinality dimension % — replace it, do not add',
            NEW.case_id, NEW.dimension_id
            USING ERRCODE = '23505';
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER case_classification_integrity
    BEFORE INSERT OR UPDATE ON case_classification
    FOR EACH ROW EXECUTE FUNCTION tcms_enforce_classification_integrity();


-- =============================================================================
-- 3. TEAM HIERARCHY — CYCLES AND COMPANY CONSISTENCY
-- =============================================================================
--
-- ERD §217. A cycle breaks the org-chart render and hangs any recursive query,
-- and parent_team_id crossing a company boundary would let one client's team
-- appear inside another's org chart — an isolation leak that RLS cannot catch,
-- because both rows are legitimately readable by whoever wrote the edge.

CREATE FUNCTION tcms_enforce_team_hierarchy() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    v_parent_company_id UUID;
    v_cycle_depth       INT;
BEGIN
    IF NEW.parent_team_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF NEW.parent_team_id = NEW.id THEN
        RAISE EXCEPTION 'team % cannot be its own parent', NEW.id USING ERRCODE = '23514';
    END IF;

    SELECT company_id INTO v_parent_company_id FROM team WHERE id = NEW.parent_team_id;

    IF v_parent_company_id IS NULL THEN
        RAISE EXCEPTION 'parent team % is not visible in this company', NEW.parent_team_id
            USING ERRCODE = '23503';
    END IF;

    IF v_parent_company_id <> NEW.company_id THEN
        RAISE EXCEPTION 'team nesting may not cross companies: parent % is in company %, child in %',
            NEW.parent_team_id, v_parent_company_id, NEW.company_id
            USING ERRCODE = '23514';
    END IF;

    -- The depth guard is not paranoia about our own writes: if a cycle ever DOES
    -- get in (a direct psql edit, a future migration), an unbounded recursive CTE
    -- here would hang the transaction that tries to fix it.
    WITH RECURSIVE ancestors AS (
        SELECT t.id, t.parent_team_id, 1 AS depth
          FROM team t WHERE t.id = NEW.parent_team_id
        UNION ALL
        SELECT t.id, t.parent_team_id, a.depth + 1
          FROM team t JOIN ancestors a ON t.id = a.parent_team_id
         WHERE a.depth < 64
    )
    SELECT coalesce(max(depth), 0) INTO v_cycle_depth FROM ancestors WHERE id = NEW.id;

    IF v_cycle_depth > 0 THEN
        RAISE EXCEPTION 'team % is already an ancestor of %, so this edge would create a cycle',
            NEW.id, NEW.parent_team_id
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END $$;

CREATE TRIGGER team_hierarchy_integrity
    BEFORE INSERT OR UPDATE OF parent_team_id, company_id ON team
    FOR EACH ROW EXECUTE FUNCTION tcms_enforce_team_hierarchy();

COMMENT ON FUNCTION tcms_enforce_team_hierarchy() IS
    'Cycle + company-consistency check on team.parent_team_id (ERD §217). Permissions never cascade '
    'through this edge — it is org-chart structure only — so this protects rendering and recursive '
    'queries, not authorization.';


-- =============================================================================
-- 4. THE SEEDED VOCABULARIES, AS FUNCTIONS
-- =============================================================================
--
-- WHY FUNCTIONS AND NOT INSERTs. Classification dimensions, execution statuses
-- and the default configuration are COMPANY-OWNED data, and:
--
--   * there are no companies at migration time, so there is nothing to seed
--     against. Each client gets its own COPY of the vocabulary at provisioning
--     time, because one client renaming "Compatibility" must not rename it for
--     another (plan §6d, §6s);
--   * every one of these tables has FORCE ROW LEVEL SECURITY, so an INSERT
--     needs app.current_company set — including for the migration role, which
--     FORCE covers too. A migration that set the GUC to seed a placeholder
--     company would be encoding a fixture as schema.
--
-- So the vocabulary content lives here — versioned, reviewable, changed through a
-- migration like everything else — and provision-company calls these once per
-- company and once per project. Callers must already hold the company context;
-- both functions refuse to run without it rather than silently writing rows that
-- the policy will then hide.

CREATE FUNCTION tcms_assert_company_context(p_company_id UUID) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_active TEXT := current_setting('app.current_company', true);
BEGIN
    IF v_active IS NULL OR v_active = '' THEN
        RAISE EXCEPTION 'no company context is active — open one before seeding company-owned rows'
            USING ERRCODE = '42501';
    END IF;
    IF v_active::uuid <> p_company_id THEN
        RAISE EXCEPTION 'company context is % but the seed targets % — RLS would hide every row written',
            v_active, p_company_id
            USING ERRCODE = '42501';
    END IF;
END $$;

-- ltree labels accept only alphanumerics and underscore, so "UI / E2E" cannot be
-- a path label as written. Slugged here rather than at each call site, so the
-- path a value gets is a property of the schema and not of whoever seeded it.
CREATE FUNCTION tcms_ltree_label(p_name TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE AS $$
    SELECT trim(both '_' from regexp_replace(lower(p_name), '[^a-z0-9]+', '_', 'g'));
$$;

CREATE FUNCTION tcms_seed_company_defaults(p_company_id UUID) RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_dimension_id UUID;
    v_value        TEXT;
    v_position     INT;
    v_spec         RECORD;
BEGIN
    PERFORM tcms_assert_company_context(p_company_id);

    -- Test Type and Test Level are SEPARATE axes on purpose (plan §6d): an API
    -- test is almost always functional, and a non-functional API test is a
    -- performance test at the API layer. One enum forces a false choice and
    -- destroys the type × level coverage matrix.
    --
    -- All three are locked = true: a client editing them is fine, but reporting
    -- comparability across that client's projects is the reason they are
    -- company-level rather than project-level, and unlocking them per project
    -- would give that away. Feature is per-project and unlocked
    -- (tcms_seed_project_defaults below).
    FOR v_spec IN
        SELECT * FROM (VALUES
            ('test_type', 'Test Type', 'SINGLE', 0,
             ARRAY['Functional','Performance','Security','Usability',
                   'Accessibility','Compatibility','Localization','Recovery']),
            ('test_level', 'Test Level', 'SINGLE', 1,
             ARRAY['Unit','Component','API','Integration','UI / E2E','Database','Contract']),
            ('execution_phase', 'Execution Phase', 'MULTI', 2,
             ARRAY['Smoke','Sanity','Regression','Acceptance','Exploratory'])
        ) AS s(key, name, cardinality, position, values)
    LOOP
        SELECT id INTO v_dimension_id
          FROM classification_dimension
         WHERE company_id = p_company_id AND project_id IS NULL AND key = v_spec.key;

        IF v_dimension_id IS NULL THEN
            v_dimension_id := gen_random_uuid();
            INSERT INTO classification_dimension
                (id, company_id, project_id, key, name, cardinality,
                 controlled, hierarchical, locked, position)
            VALUES
                (v_dimension_id, p_company_id, NULL, v_spec.key, v_spec.name, v_spec.cardinality,
                 true, false, true, v_spec.position);
        END IF;

        v_position := 0;
        FOREACH v_value IN ARRAY v_spec.values LOOP
            INSERT INTO classification_value
                (id, company_id, dimension_id, parent_id, path, name, color, position)
            SELECT gen_random_uuid(), p_company_id, v_dimension_id, NULL,
                   tcms_ltree_label(v_value)::ltree, v_value, NULL, v_position
             WHERE NOT EXISTS (
                SELECT 1 FROM classification_value
                 WHERE dimension_id = v_dimension_id AND name = v_value);
            v_position := v_position + 1;
        END LOOP;
    END LOOP;
END $$;

COMMENT ON FUNCTION tcms_seed_company_defaults(UUID) IS
    'Called once per company by provision-company. Idempotent: re-running adds only what is '
    'missing, so a company provisioned before a vocabulary was extended can be topped up.';

CREATE FUNCTION tcms_seed_project_defaults(p_company_id UUID, p_project_id UUID) RETURNS void
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
END $$;

COMMENT ON FUNCTION tcms_seed_project_defaults(UUID, UUID) IS
    'Called once per project, by project creation and by provision-company for the first project. '
    'Idempotent, and refuses a project it cannot see — which under RLS is also a project in '
    'another company.';
