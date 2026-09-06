-- =============================================================================
-- V13 — three boards over one workflow, and a status that remembers (plan 021)
--
-- THE VERSION IS V13, AND THE DIRECTORY IS WHAT SAYS SO. `ls` on 2026-09-06:
-- V1..V12. CLAUDE.md's "there is exactly one migration" sentence describes the
-- state on 2026-08-29 and is stale by eleven files; the number is read from the
-- directory, never derived from a document (021 D-a). `out-of-order` is unset in
-- application.yml, so a lower-numbered migration arriving after a higher one is
-- NEVER APPLIED, and Testcontainers builds every test database from empty, so no
-- suite here can show it.
--
-- THREE COLUMNS. issue_status.blocking (021 D-b): the one status per project
-- whose entry records where the card came from — a flag rather than a fourth
-- issue_status.kind, because every percentage, burn-up band and flow band is
-- computed from kind and blocked work is in flight and not done, which is what
-- IN_PROGRESS already means. issue.blocked_from_status_id (D-c): that origin,
-- cleared on exit, a legal target from the blocking status without an
-- issue_transition row. board."position" (D-f): the Work strip lists a project's
-- boards left to right, and the board table had no order of its own —
-- BoardRepository sorted by name, which would have listed Dev before PM.
--
-- THE SEED FUNCTION IS REPLACED, NOT PATCHED, and that is the cost V5's own
-- header names rather than a thing to economise on here: CREATE OR REPLACE
-- FUNCTION replaces the WHOLE BODY, so a block left out of the reproduction
-- below silently stops being seeded for every project created from this
-- migration onward — no error, no failing insert, just a project missing
-- something nobody notices until they open the screen that needs it. The nine
-- blocks are classification_dimension, case_status, configuration, doc_space,
-- project_key_sequence, issue_status, issue_transition, issue_type and the
-- boards. ProjectKeySequenceSchemaTest, BoardSchemaTest and WorkflowSeedTest are
-- the checks that one went missing.
--
-- WHAT CHANGES IN IT: eleven statuses instead of six (021 §2.3), twenty-one
-- legal moves instead of thirty, and THREE boards (PM · Dev · QA) instead of one
-- Team board, with shared columns as the hand-offs (§2.4).
--
-- WHAT DOES NOT CHANGE: any existing project (021 D-l). The seed is idempotent
-- per name and the board block is guarded by NOT EXISTS, so a project that
-- already has a workflow and a board keeps both. The only back-fill flags an
-- existing project's 'Blocked' status, at the foot of this file.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. THE BLOCKING STATUS, AND THE ORIGIN IT REMEMBERS
-- -----------------------------------------------------------------------------
--
-- AT MOST ONE PER PROJECT, as a partial unique index rather than a service rule.
-- "Which status is the blocking one" is read to decide what to write into
-- issue.blocked_from_status_id, so two of them would make that write pick one at
-- random — the same failure board_column.default_status_id's NOT NULL exists to
-- prevent one table over. A partial index is the right shape because the
-- constraint is over the TRUE rows only: every project has ten statuses that are
-- not blocking and they may not collide with each other.

ALTER TABLE issue_status ADD COLUMN blocking BOOLEAN NOT NULL DEFAULT false;
CREATE UNIQUE INDEX uq_issue_status_one_blocking
    ON issue_status (company_id, project_id) WHERE blocking;
COMMENT ON COLUMN issue_status.blocking IS
    'At most one per project, which uq_issue_status_one_blocking enforces over the true rows only. '
    'Entering this status records issue.blocked_from_status_id and leaving it clears that; the '
    'remembered origin is a legal target without an issue_transition row, because listing ten '
    'reverse pairs would make "back to where it was" indistinguishable from "to anywhere" (021 '
    'D-b, D-c). NOT a fourth issue_status.kind: blocked work is in flight and not done, which is '
    'what IN_PROGRESS already means to every percentage in the product.';

-- ON DELETE SET NULL, and it is the same argument V5's board_follows_status_removal
-- trigger makes: the WORKFLOW is upstream, and a remembered origin may not veto a
-- workflow edit. A plain RESTRICT here would let one blocked issue refuse the
-- removal of a status nothing is IN — WorkflowService already refuses that case
-- loudly, by counting issues whose status_id is the departing one, and this column
-- is not a status an issue is in. Losing the origin when the status retires is
-- right: the Unblock button then offers the ordinary workflow moves.
ALTER TABLE issue ADD COLUMN blocked_from_status_id UUID
    REFERENCES issue_status(id) ON DELETE SET NULL;
COMMENT ON COLUMN issue.blocked_from_status_id IS
    'Where the issue was before it entered the project''s blocking status; NULL otherwise. Unblock '
    'returns it here in one click and no decision, because Blocked is a place work waits rather '
    'than a place work goes (021 D-c). ON DELETE SET NULL rather than RESTRICT: a remembered '
    'origin may not veto a workflow edit, which is the direction V5''s board_follows_status_removal '
    'already settles for a board.';


-- -----------------------------------------------------------------------------
-- 2. A BOARD'S PLACE IN THE STRIP
-- -----------------------------------------------------------------------------
--
-- The Work strip's segments are the project's boards in this order (021 D-f), and
-- without the column BoardRepository sorted them by name — which lists Dev before
-- PM and QA last, so the hand-off reads right to left. DEFAULT 0 rather than a
-- back-filled sequence: every existing project has exactly one board, and one
-- board at position zero is the whole of what "in order" means for it.
--
-- Not unique per project. A created board takes max + 1, two created in the same
-- instant may tie, and the tie is broken by name — the same reason issue.rank is
-- deliberately not unique.

ALTER TABLE board ADD COLUMN "position" INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN board."position" IS
    'Left to right in the Work strip (021 D-f). A created board takes max + 1 and nothing reorders '
    'them yet. Deliberately not unique: two boards created in one instant may tie, and board.name '
    'breaks it — issue.rank''s reasoning, one table over.';


-- -----------------------------------------------------------------------------
-- 3. THE SEED — ELEVEN STATUSES, TWENTY-ONE MOVES, THREE BOARDS
-- -----------------------------------------------------------------------------
--
-- THE COLUMN LAYOUT IS jsonb, NOT A NESTED ARRAY, and the choice is forced.
-- Postgres arrays are RECTANGULAR: ARRAY[ARRAY['New'], ARRAY['In progress','Rework']]
-- is a runtime error, so a nested-array literal would have to pad every short
-- column out to the widest one with a repeated name and rely on `= ANY` tolerating
-- the duplicate. A jsonb array of {"name","statuses"} objects says what it means,
-- and jsonb_array_elements WITH ORDINALITY gives the column position for free.

CREATE OR REPLACE FUNCTION tcms_seed_project_defaults(p_company_id UUID, p_project_id UUID)
    RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_spec RECORD;
    v_board RECORD;
    v_col RECORD;
    v_board_id UUID;
    v_column_id UUID;
    v_written INT;
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

    -- The seven default execution statuses. `kind` is what lets reporting aggregate
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
            ('Untested', 'UNTESTED', '#9ca3af', false, false, 5),
            ('WIP',      'WIP',      '#2563eb', false, false, 6)
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

    -- V2. The per-project issue-key counter. Structural, like the configuration
    -- and the doc space above: without this row ProjectKeys.allocate matches
    -- nothing and the first issue in the project cannot be numbered. Its absence
    -- is an EMPTY RESULT rather than an error — an UPDATE ... RETURNING that
    -- matches no row is not a failure — which is exactly why the row is created
    -- here, once, rather than repaired by an INSERT ... ON CONFLICT on the hot
    -- path of every issue creation forever.
    INSERT INTO project_key_sequence (company_id, project_id, next_value)
    VALUES (p_company_id, p_project_id, 1)
    ON CONFLICT (company_id, project_id) DO NOTHING;

    -- V13. The default workflow — ELEVEN STATUSES, NINE ON ONE LINE AND TWO OFF IT
    -- (021 §2.3), replacing V5's six.
    --
    --   New → Refining → Ready to develop → In progress → In review
    --       → Ready for test → In test → Ready to deploy → Done
    --   Rework    IN_PROGRESS, off the line: the tester's return path
    --   Blocked   IN_PROGRESS, off the line, and the project's blocking status
    --
    -- Ready to deploy sits between In test and Done because TESTED IS NOT SHIPPED
    -- (D-l): it is the tester's last word, and the move to Done is the deploy
    -- confirmation the PO makes — which is also the figure release readiness reads.
    --
    -- The kinds are V5's three and no more. Blocked is IN_PROGRESS for the reason
    -- V5 already gave and D-b restates: something blocked is started and not done,
    -- and a kind of its own would have to be given a meaning by every percentage in
    -- the product, which has none to give it.
    --
    -- Idempotent per NAME, like V5's and V3's. An existing project keeps its own
    -- workflow (D-l): this function only ever inserts a name the project does not
    -- already have, and the projects that have 'Blocked' already are the ones the
    -- back-fill at the foot of this file flags.
    FOR v_spec IN
        SELECT * FROM (VALUES
            ('New',              'NOT_STARTED', 0,  false),
            ('Refining',         'NOT_STARTED', 1,  false),
            ('Ready to develop', 'NOT_STARTED', 2,  false),
            ('In progress',      'IN_PROGRESS', 3,  false),
            ('In review',        'IN_PROGRESS', 4,  false),
            ('Ready for test',   'IN_PROGRESS', 5,  false),
            ('In test',          'IN_PROGRESS', 6,  false),
            ('Ready to deploy',  'IN_PROGRESS', 7,  false),
            ('Done',             'DONE',        8,  false),
            ('Rework',           'IN_PROGRESS', 9,  false),
            ('Blocked',          'IN_PROGRESS', 10, true)
        ) AS s(name, kind, position, blocking)
    LOOP
        INSERT INTO issue_status (id, company_id, project_id, name, kind, "position", blocking)
        SELECT gen_random_uuid(), p_company_id, p_project_id,
               v_spec.name, v_spec.kind, v_spec.position, v_spec.blocking
         WHERE NOT EXISTS (
            SELECT 1 FROM issue_status
             WHERE company_id = p_company_id AND project_id = p_project_id AND name = v_spec.name);
    END LOOP;

    -- THE LEGAL MOVES — TWENTY-ONE, AND STILL AN ALLOW-LIST: every pair not
    -- inserted here is refused.
    --
    --   8  along the line          position n → n + 1, for n = 0 .. 7
    --   3  into Rework             from In review, In test, Ready to deploy
    --   1  out of Rework           to In progress
    --   9  into Blocked            from every status that is neither DONE nor Blocked itself
    --
    -- TWENTY-ONE AND NOT THE TWENTY-TWO PLAN 021 COUNTS. The plan's tally reads
    -- "10 into Blocked" from §2.4's "into it from every non-done status", and there
    -- are ten non-done statuses — but one of them IS Blocked, and a self-pair is
    -- refused by ck_issue_transition_distinct and would mean nothing anyway. The
    -- RULE is what the plan states and this implements; only the arithmetic was off
    -- by the status counting itself as its own source.
    --
    -- OUT of Blocked is not a row. issue.blocked_from_status_id is the destination
    -- and TransitionService treats it as legal without a pair (D-c) — ten reverse
    -- pairs would make "back to where it was" indistinguishable from "to anywhere",
    -- which is the whole distinction the column buys.
    --
    -- OUT OF DONE IS NOT A ROW EITHER, AND THAT IS A CONSEQUENCE WORTH STATING
    -- RATHER THAN LEAVING TO BE DISCOVERED. The line runs one way, Rework is reached
    -- only from In review, In test and Ready to deploy, and Blocked is reached only
    -- from a non-DONE status — so in the SEEDED workflow Done is terminal and a
    -- finished issue cannot be reopened through the API at all. V5's six-status set
    -- allowed it (everything reached Backlog, so a mis-closed issue could be put
    -- back). 021 §2.3 enumerates the moves and lists none out of Done; this file
    -- implements what it enumerates. A project that wants a reopen adds the pair in
    -- Settings → Workflow, which already exists, and the decision to seed one is a
    -- product call rather than a thing this migration should make on its own.
    FOR v_spec IN
        SELECT f.id AS from_id, t.id AS to_id
          FROM issue_status f
          JOIN issue_status t
            ON t.company_id = f.company_id AND t.project_id = f.project_id AND t.id <> f.id
         WHERE f.company_id = p_company_id AND f.project_id = p_project_id
           AND (
                 (t."position" = f."position" + 1 AND f."position" <= 7)
              OR (t.name = 'Rework'  AND f.name IN ('In review', 'In test', 'Ready to deploy'))
              OR (f.name = 'Rework'  AND t.name = 'In progress')
              OR (t.name = 'Blocked' AND f.kind <> 'DONE' AND f.name <> 'Blocked')
           )
    LOOP
        INSERT INTO issue_transition (company_id, project_id, from_status_id, to_status_id)
        SELECT p_company_id, p_project_id, v_spec.from_id, v_spec.to_id
         WHERE NOT EXISTS (
            SELECT 1 FROM issue_transition
             WHERE from_status_id = v_spec.from_id AND to_status_id = v_spec.to_id);
    END LOOP;

    -- SIX TYPES ACROSS THREE LEVELS, and note that TWO of them are named for a
    -- task.
    --
    -- D29: the depth rule stands — a TASK-level issue needs a story-level parent —
    -- but the catalogue seeds a STORY-level type called Task, so a solo or
    -- internal project never needs an Epic and a Story before "a thing to do".
    -- D12 amended: migrated work_item rows become the STORY-level one, so a user
    -- who typed "task" still sees "task". Orphan TASK-level issues stay refused.
    --
    -- settings-general.html lists FIVE — Epic, Story, Task, Bug, Spike — and
    -- story.html calls a story's children "Sub-tasks" while issue-new.html offers
    -- "Add sub-task". Those cannot both be true with one Task type once D29 puts
    -- Task at STORY level, so there are six rows and two of them render as `T`.
    -- RECORDED AS A DISAGREEMENT WITH THE MOCKUP, resolved in the direction the
    -- depth rule requires; the settings screen lists all six.
    --
    -- Spike is counts_toward_completion = false (D8). Epic is false because an
    -- epic is what completion is computed FOR, never a member of the count.
    FOR v_spec IN
        SELECT * FROM (VALUES
            ('Epic',     'EPIC',  false, 'E', 0),
            ('Story',    'STORY', true,  'S', 1),
            ('Task',     'STORY', true,  'T', 2),
            ('Bug',      'STORY', true,  'B', 3),
            ('Spike',    'STORY', false, '?', 4),
            ('Sub-task', 'TASK',  true,  'T', 5)
        ) AS s(name, level, counts, icon, position)
    LOOP
        INSERT INTO issue_type
            (id, company_id, project_id, name, level, counts_toward_completion, icon_code, "position")
        SELECT gen_random_uuid(), p_company_id, p_project_id, v_spec.name, v_spec.level,
               v_spec.counts, v_spec.icon, v_spec.position
         WHERE NOT EXISTS (
            SELECT 1 FROM issue_type
             WHERE company_id = p_company_id AND project_id = p_project_id AND name = v_spec.name);
    END LOOP;

    -- V13. THREE BOARDS OVER ONE WORKFLOW (021 §2.4), replacing V5's one Team
    -- board with one column per status.
    --
    -- A BOARD CLAIMS A SUBSET NOW (021 R7, D-e). V5's board showed the whole
    -- lifecycle because every status had to be claimed by every board; that rule
    -- is gone, so PM, Dev and QA each show the part of the line their reader works
    -- on and nothing else. The protection the rule gave — no card invisible
    -- everywhere — is a READ now, not a refusal: getBoardCoverage reports a status
    -- no board claims, and the three seeded here between them claim all eleven.
    --
    -- SHARED COLUMNS ARE THE HAND-OFFS, and that is the whole design: the same
    -- status is the last working column of one board and the first of the next, so
    -- a drag on one board IS the arrival on the other — no second action, nothing
    -- to notify. Ready to develop hands PM to Dev, Ready for test hands Dev to QA,
    -- Ready to deploy hands QA back to PM, Rework is QA's return path to Dev, and
    -- Blocked is the last column of all three because a blocked card is everyone's
    -- problem and should be visible wherever anyone looks.
    --
    -- Done is on the PM BOARD ONLY (D-l): shipping is the PO's call, so the board
    -- a developer or tester looks at does not fill with finished work.
    --
    -- The first status of a column's `statuses` list is that column's
    -- default_status_id — the status a drop into it MEANS — which is why the
    -- Dev board's In progress column lists In progress before Rework: dropping a
    -- card there means "somebody is working on it", never "it came back".
    --
    -- Guarded by NOT EXISTS on `board` like V5's single board was, and for the
    -- same reason: an existing project keeps the board it has (D-l), and re-running
    -- this function is how the create path and any later back-fill agree about what
    -- a seeded project contains.
    IF NOT EXISTS (SELECT 1 FROM board
                    WHERE company_id = p_company_id AND project_id = p_project_id) THEN
        FOR v_board IN
            SELECT * FROM (VALUES
                ('PM board', 0, '[
                    {"name": "New",              "statuses": ["New"]},
                    {"name": "Refining",         "statuses": ["Refining"]},
                    {"name": "Ready to develop", "statuses": ["Ready to develop"]},
                    {"name": "Ready to deploy",  "statuses": ["Ready to deploy"]},
                    {"name": "Done",             "statuses": ["Done"]},
                    {"name": "Blocked",          "statuses": ["Blocked"]}
                 ]'::jsonb),
                ('Dev board', 1, '[
                    {"name": "Ready to develop", "statuses": ["Ready to develop"]},
                    {"name": "In progress",      "statuses": ["In progress", "Rework"]},
                    {"name": "In review",        "statuses": ["In review"]},
                    {"name": "Ready for test",   "statuses": ["Ready for test"]},
                    {"name": "Blocked",          "statuses": ["Blocked"]}
                 ]'::jsonb),
                ('QA board', 2, '[
                    {"name": "Ready for test",   "statuses": ["Ready for test"]},
                    {"name": "In test",          "statuses": ["In test"]},
                    {"name": "Rework",           "statuses": ["Rework"]},
                    {"name": "Ready to deploy",  "statuses": ["Ready to deploy"]},
                    {"name": "Blocked",          "statuses": ["Blocked"]}
                 ]'::jsonb)
            ) AS b(name, pos, columns)
        LOOP
            v_board_id := gen_random_uuid();
            INSERT INTO board (id, company_id, project_id, name, swimlane_by, "position")
            VALUES (v_board_id, p_company_id, p_project_id, v_board.name, 'EPIC', v_board.pos);

            FOR v_col IN
                SELECT value AS spec, ordinality - 1 AS pos
                  FROM jsonb_array_elements(v_board.columns) WITH ORDINALITY
            LOOP
                v_column_id := gen_random_uuid();
                INSERT INTO board_column
                    (id, company_id, board_id, name, "position", wip_limit, default_status_id)
                SELECT v_column_id, p_company_id, v_board_id, v_col.spec ->> 'name',
                       v_col.pos, NULL, s.id
                  FROM issue_status s
                 WHERE s.company_id = p_company_id AND s.project_id = p_project_id
                   AND s.name = (v_col.spec -> 'statuses' ->> 0);

                -- LOUD RATHER THAN DANGLING. The SELECT above matches nothing if the
                -- project renamed the status this column defaults to, and the claim
                -- INSERT below would then name a column_id no row has — a foreign key
                -- violation whose message names neither the board nor the status. The
                -- layout above is this file's own, so a miss is a typo in it.
                GET DIAGNOSTICS v_written = ROW_COUNT;
                IF v_written <> 1 THEN
                    RAISE EXCEPTION 'seeding % for project %: no status named % to default to',
                        v_board.name, p_project_id, (v_col.spec -> 'statuses' ->> 0);
                END IF;

                INSERT INTO board_column_status (company_id, board_id, column_id, status_id)
                SELECT p_company_id, v_board_id, v_column_id, s.id
                  FROM issue_status s
                 WHERE s.company_id = p_company_id AND s.project_id = p_project_id
                   AND s.name IN (
                        SELECT jsonb_array_elements_text(v_col.spec -> 'statuses'));
            END LOOP;
        END LOOP;
    END IF;
END $$;


-- -----------------------------------------------------------------------------
-- 3b. THE REMOVAL TRIGGER'S TWO HALVES DISAGREED, AND THE SEED IS WHAT SHOWED IT
-- -----------------------------------------------------------------------------
--
-- FOUND BY THE GATE, NOT BY READING — the second time this trigger has been.
-- Thirty-eight tests failed the first run of V13's gate with
--
--   null value in column "default_status_id" of relation "board_column"
--
-- raised from inside tcms_board_follows_status_removal itself. Its UPDATE and
-- its EXISTS guard asked two different questions:
--
--   * the EXISTS asked "does any OTHER claim row survive on this column?"
--   * the subquery asked "does any other claim row survive WHOSE STATUS ALSO
--     SURVIVES?", because it JOINs issue_status.
--
-- Those differ during a multi-row DELETE. The trigger is BEFORE ROW, so when a
-- whole workflow goes in one statement it fires once per status, and by the time
-- it fires for the last one the earlier statuses are gone while their claim rows
-- have not necessarily been cascaded away yet. The EXISTS then sees a stale claim
-- row, the JOIN filters it out, the subquery returns NULL, and the UPDATE writes
-- NULL into a NOT NULL column.
--
-- IT WAS UNREACHABLE UNTIL V13 AND IS ROUTINE AFTER IT. V5's seeded board had one
-- status per column, so its EXISTS was always false and the UPDATE never ran; the
-- only folded column in the suite was one BoardSchemaTest builds by hand and
-- retires one status at a time. V13 seeds a folded column into every project —
-- the Dev board's "In progress", which claims In progress AND Rework (§2.4) — so
-- every path that clears a project's workflow now goes through it.
--
-- The fix is to make the guard ask the subquery's question. A column whose only
-- surviving claims point at statuses that are themselves being deleted is a
-- column with nothing left to default to, which is exactly the case the DELETE
-- below already handles correctly.
CREATE OR REPLACE FUNCTION tcms_board_follows_status_removal() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    UPDATE board_column c
       SET default_status_id = (
            SELECT s.id
              FROM board_column_status bcs
              JOIN issue_status s ON s.id = bcs.status_id
             WHERE bcs.column_id = c.id AND bcs.status_id <> OLD.id
             ORDER BY s."position"
             LIMIT 1)
     WHERE c.default_status_id = OLD.id
       AND EXISTS (SELECT 1 FROM board_column_status bcs
                     JOIN issue_status s ON s.id = bcs.status_id
                    WHERE bcs.column_id = c.id AND bcs.status_id <> OLD.id);

    DELETE FROM board_column c WHERE c.default_status_id = OLD.id;

    RETURN OLD;
END $$;

COMMENT ON FUNCTION tcms_board_follows_status_removal() IS
    'Rearranges every board around a status leaving the workflow: a column that claimed something '
    'else keeps its place and defaults to the earliest status it still holds, and a column that '
    'claimed only the departing one goes with it. The board is a lens and may not veto the '
    'workflow, which a plain RESTRICT on board_column.default_status_id would let it do. Its guard '
    'joins issue_status like its subquery does (V13): during a multi-row DELETE the two questions '
    'differ, because a claim row can outlive the status it points at for the length of one '
    'statement, and the mismatch wrote NULL into a NOT NULL column.';


-- -----------------------------------------------------------------------------
-- 4. BACK-FILL — FLAG AN EXISTING PROJECT'S 'Blocked' STATUS, AND NOTHING ELSE
-- -----------------------------------------------------------------------------
--
-- 021 D-m. V5's six-status seed has a 'Blocked' and every project created before
-- this migration holds one. Without the flag their blocked cards would never
-- remember their origin, so the Unblock button would have nowhere to return them
-- to. No other existing data changes: an existing project keeps its six statuses,
-- its thirty moves and its Team board (D-l), and adds the rest in Settings, where
-- it already can.
--
-- V5 §6'S LOOP, EXACTLY, AND THE TRAP IT NAMES IS THE REASON. issue_status has
-- carried FORCE ROW LEVEL SECURITY since V3 and Flyway connects as tcms_owner,
-- which holds no BYPASSRLS — so a single UPDATE with app.current_company unset
-- matches ZERO ROWS and reports success. `company` is on the ERD §0.1 exception
-- list and carries no policy, so it can be walked; setting the GUC per company
-- then makes that company's rows visible through the ordinary policy.
-- set_config's third argument is transaction-local and Flyway runs a migration in
-- one transaction, so the setting is gone when this file ends.
--
-- TWO ASSERTIONS, BECAUSE ONE OF THEM CANNOT BE WRITTEN HERE. The count this
-- block would most like to compare against is "every 'Blocked' status in the
-- instance", taken before the loop — and that count is itself under RLS with the
-- GUC unset, so it reads ZERO on exactly the database this back-fill matters on
-- and would turn a correct run into a failed migration. What is checked instead:
--
--   * the GUC really took, per company, read back through current_setting. That
--     is the unset-context failure itself, asserted directly rather than inferred
--     from a row count that the same failure would also zero.
--   * the UPDATE touched every row the SELECT saw under that same context — the
--     shape a policy that permits SELECT and not UPDATE would break.
DO $$
DECLARE
    v_company UUID;
    v_expected INT;
    v_rows INT;
    v_seen INT := 0;
    v_flagged INT := 0;
BEGIN
    FOR v_company IN SELECT id FROM company LOOP
        PERFORM set_config('app.current_company', v_company::text, true);
        IF current_setting('app.current_company', true) IS DISTINCT FROM v_company::text THEN
            RAISE EXCEPTION 'V13 back-fill: app.current_company did not take for company %',
                v_company;
        END IF;

        SELECT count(*) INTO v_expected
          FROM issue_status
         WHERE company_id = v_company AND name = 'Blocked' AND NOT blocking;
        v_seen := v_seen + v_expected;

        UPDATE issue_status SET blocking = true
         WHERE company_id = v_company AND name = 'Blocked' AND NOT blocking;
        GET DIAGNOSTICS v_rows = ROW_COUNT;
        v_flagged := v_flagged + v_rows;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);

    IF v_flagged <> v_seen THEN
        RAISE EXCEPTION 'V13 back-fill flagged % of the % Blocked statuses it could see',
            v_flagged, v_seen;
    END IF;
END $$;


-- -----------------------------------------------------------------------------
-- 5. THE DATA DICTIONARY
-- -----------------------------------------------------------------------------
--
-- SchemaCommentTest reads these and resolves every identifier they name against
-- the live catalog, so a sentence here cannot outlive the structure it describes.

COMMENT ON FUNCTION tcms_seed_project_defaults(UUID, UUID) IS
    'Everything a new project gets: the feature dimension, the seven execution statuses, a Default '
    'configuration, a documentation space, the issue-key counter, the eleven-status workflow with '
    'its twenty-one moves, the six types, and (V13) three boards — PM, Dev and QA — whose shared '
    'columns are the hand-offs between them. Called by every creation path, so a path added later '
    'gets all of it without its author remembering. Idempotent per name, and the board block is '
    'guarded by NOT EXISTS, so an existing project keeps the six-status workflow and the one board '
    'V5 gave it (021 D-l).';

COMMENT ON TABLE board_column_status IS
    'Which statuses a column claims. ~~Every status is claimed by exactly one column per board~~ '
    'and only half of that was expressible here → SUPERSEDED by 021 R7: a board claims a SUBSET, '
    'so "at least one column per status" is no longer a rule at all. What survives is '
    'uq_board_column_status — at most one column per status PER BOARD, which is what makes a '
    'card''s column unambiguous and a drop resolvable. The protection the dropped half gave, that '
    'no card is invisible on every board, is a read now: getBoardCoverage reports a status no '
    'board claims. board_id is denormalised onto the row so the unique index can exist at all.';
