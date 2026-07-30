-- =============================================================================
-- V5 — a provisioned company must be able to allocate its first display key
--
-- DEFECT (found while writing provision-company)
--
-- Five entity types carry a company-scoped display key — TC- TS- TP- TR- DOC-
-- (plan §6t, ERD "key_sequence — display-key allocation, per company"). The
-- allocator the ERD specifies is deliberately a single statement, so concurrent
-- creation cannot issue a duplicate:
--
--     UPDATE key_sequence SET next_value = next_value + :n
--      WHERE company_id = :c AND entity_type = :t
--     RETURNING next_value - :n;
--
-- Nothing created those rows. V1 declares the table, V2's tcms_seed_company_defaults
-- seeds the classification vocabularies and V3 seeds the instance-level permission
-- catalogue — no path writes a key_sequence row. A company provisioned before this
-- migration therefore has none, and the statement above matches zero rows and
-- returns nothing.
--
-- Severity: it fails closed, and it fails in the shape that invites the wrong
-- repair. An UPDATE ... RETURNING that returns no row is not an error — it is an
-- empty result — so the caller sees "could not allocate a key" and the tempting fix
-- is MAX(key_seq) + 1, which is exactly the racing allocator the ERD rejects. The
-- second tempting fix is an INSERT ... ON CONFLICT at allocation time, which puts a
-- write on the hot path of every case creation forever to cover a one-time setup
-- step. Seeding at provisioning is the cheap correct place.
--
-- WHY HERE AND NOT IN THE CLI: same argument as the vocabularies (V2 §4). These are
-- company-owned rows under FORCE ROW LEVEL SECURITY, they are product constants
-- rather than operator input, and they should be versioned in a migration and
-- reviewed like schema. Putting them in Java would also mean a second provisioning
-- path — a test fixture, a future restore tool — could create a company that looks
-- complete and cannot allocate a key.
--
-- The prefixes are NOT configurable per company. A client renaming TC- to TEST-
-- would break every key already printed in a report, linked in a ticket and pasted
-- into a chat, and display keys are immutable by contract (plan §6t).
--
-- CREATE OR REPLACE restates the whole function: Postgres has no partial-body
-- replacement, so the classification block below is V2's, unchanged, and the
-- key_sequence block is the addition. Idempotent as before, so an existing company
-- is topped up by a re-run of provision-company rather than needing a backfill.
-- =============================================================================

CREATE OR REPLACE FUNCTION tcms_seed_company_defaults(p_company_id UUID) RETURNS void
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
    -- (tcms_seed_project_defaults).
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

    -- The five display-key counters (V5). next_value starts at 1, so the first
    -- allocation issues TC-1 and not TC-0 — the keys are user-facing and 1-based
    -- (plan §6t). No zero-padding anywhere: key_seq owns ordering, key is display
    -- only, and TC-10000 sorting before TC-9999 as text is why those are separate
    -- columns at all.
    --
    -- There is deliberately NO row for executions, conditions, shared steps,
    -- defects, reviews, artifacts or configurations. Each is reached from its
    -- parent, and every extra prefix is another counter to contend on and another
    -- ambiguous token in search. The CHECK constraint on entity_type is the list.
    INSERT INTO key_sequence (company_id, entity_type, prefix, next_value)
    SELECT p_company_id, s.entity_type, s.prefix, 1
      FROM (VALUES
            ('CASE',     'TC-'),
            ('SUITE',    'TS-'),
            ('PLAN',     'TP-'),
            ('RUN',      'TR-'),
            ('DOC_PAGE', 'DOC-')
      ) AS s(entity_type, prefix)
    ON CONFLICT (company_id, entity_type) DO NOTHING;
END $$;

COMMENT ON FUNCTION tcms_seed_company_defaults(UUID) IS
    'Called once per company by provision-company, and by nothing else. Seeds the classification '
    'vocabularies and the five display-key counters. Idempotent: re-running adds only what is '
    'missing, so a company provisioned before a vocabulary or a counter was added can be topped up '
    'by re-running the CLI rather than by a backfill migration.';
