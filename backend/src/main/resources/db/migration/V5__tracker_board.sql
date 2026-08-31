-- =============================================================================
-- V5 — the board (plan 012, module M2 of 009)
--
-- Six tables and one column. What a person can do afterwards that they could
-- not before: work a board with real columns, rank a backlog, and label an issue.
--
-- THE VERSION IS V5, NOT THE V4 THE 009 TABLE RESERVES. M1 took two versions —
-- V3 created `issue` and V4 dropped `work_item`, which could only land in the
-- commit that deleted the @Entity, because ddl-auto: validate refuses to boot a
-- context whose entity has no table. `out-of-order` is unset, so a V4 written
-- here would be silently skipped on every instance that ran M1, and
-- Testcontainers builds from empty and would never show it.
--
-- A BOARD IS A LENS, NOT A CONTAINER (scope §12.1). Nothing below has a foreign
-- key FROM `issue` TO a board, and that is the whole design: an issue appears on
-- every board whose filter matches it, at the column claiming its status.
-- Dropping a board loses no work because no board ever owned any. The one thing
-- an issue does gain here is `rank`, which is project-wide and not per-board —
-- two boards over one project cannot disagree about what is next, for the same
-- reason D2 gives for one canonical status.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. BOARDS AND THEIR COLUMNS
-- -----------------------------------------------------------------------------

CREATE TABLE board (
    id           UUID        PRIMARY KEY,
    company_id   UUID        NOT NULL REFERENCES company(id),
    project_id   UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name         TEXT        NOT NULL,
    -- RELEASE is deliberately absent until M3. A swimlane axis whose column is
    -- NULL for every row renders one lane called "None", which reads as a broken
    -- board rather than as an unbuilt feature.
    swimlane_by  TEXT        NOT NULL DEFAULT 'NONE'
                 CHECK (swimlane_by IN ('NONE','EPIC','ASSIGNEE','LABEL','PRIORITY')),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT      NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uq_board_name ON board (company_id, project_id, lower(name));
CREATE INDEX ix_board_project ON board (company_id, project_id);

CREATE TABLE board_column (
    id         UUID    PRIMARY KEY,
    company_id UUID    NOT NULL REFERENCES company(id),
    board_id   UUID    NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    name       TEXT    NOT NULL,
    "position" INTEGER NOT NULL,
    -- NULL is "no limit", and it is NOT zero. A zero limit would mean a column
    -- permanently in breach, which the header would then shout about forever.
    wip_limit  INTEGER CHECK (wip_limit IS NULL OR wip_limit >= 1),
    -- The status a drop into this column MEANS (D2). NOT NULL: a column whose
    -- default is unset is a column a drag cannot resolve, and "pick one at
    -- random" is how a stakeholder board silently sets Blocked.
    default_status_id UUID NOT NULL REFERENCES issue_status(id)
);
CREATE UNIQUE INDEX uq_board_column_position ON board_column (company_id, board_id, "position");
CREATE INDEX ix_board_column_board ON board_column (company_id, board_id);

-- THE CLAIM TABLE, AND THE HALF OF THE RULE POSTGRES CAN ENFORCE.
--
-- "Every status is claimed by exactly one column" (story B2, review R13) is two
-- statements and only one of them is expressible here:
--
--   * "at most one"  -> uq_board_column_status below, a real unique index.
--   * "at least one" -> a service rule. It is a cross-TABLE assertion (every row
--     of issue_status has a matching row here, per board) and Postgres has no
--     cross-table CHECK. A trigger was considered and refused: it would fire
--     mid-transaction while a board is being rebuilt column by column, so the
--     legal intermediate state of a PATCH would be rejected.
--
-- board_id is denormalised onto this row so the unique index can exist at all —
-- without it the constraint would have to span board_column, which a unique
-- index cannot do.
CREATE TABLE board_column_status (
    company_id UUID NOT NULL REFERENCES company(id),
    board_id   UUID NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    column_id  UUID NOT NULL REFERENCES board_column(id) ON DELETE CASCADE,
    status_id  UUID NOT NULL REFERENCES issue_status(id) ON DELETE CASCADE,
    PRIMARY KEY (company_id, column_id, status_id)
);
CREATE UNIQUE INDEX uq_board_column_status ON board_column_status (company_id, board_id, status_id);
CREATE INDEX ix_board_column_status_column ON board_column_status (company_id, column_id);

-- Per-board swimlane state that belongs to a PERSON, not to the board: which
-- lanes this viewer has collapsed. Separate from board.swimlane_by, which is the
-- board's own configuration, for derived_item_annotation's reason — collapsing a
-- lane is not editing the board, and a shared collapse state would mean one
-- person's tidying hides work from everybody else.
CREATE TABLE board_swimlane (
    company_id  UUID    NOT NULL REFERENCES company(id),
    board_id    UUID    NOT NULL REFERENCES board(id) ON DELETE CASCADE,
    user_id     UUID    NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    lane_key    TEXT    NOT NULL,
    collapsed   BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY (company_id, board_id, user_id, lane_key)
);

-- -----------------------------------------------------------------------------
-- 1b. THE BOARD FOLLOWS THE WORKFLOW, IT DOES NOT VETO IT
-- -----------------------------------------------------------------------------
--
-- FOUND BY THE GATE, NOT BY READING (plan 012 D-l). Without this, the plain
-- RESTRICT on board_column.default_status_id lets a BOARD refuse a WORKFLOW edit:
-- WorkflowService deletes an issue_status row when a status is dropped from the
-- PUT, and the seeded board holds a column defaulting to it, so the delete is
-- refused and the endpoint 500s. Thirty-eight tests said so on the first run of
-- this migration's gate, across WorkflowEndpointTest and ProjectCreateEndpointTest.
--
-- The direction is D2's. The canonical status lives on the issue and a board only
-- MAPS statuses onto columns, so the workflow is upstream: a status leaves, and
-- every board rearranges itself around that. A board that could veto it would
-- make "which statuses does this project have" answerable differently depending
-- on how somebody had configured a lens over them.
--
-- WHY A TRIGGER, given the plan refused one for the claim rule. That refusal is
-- about VALIDATION — a trigger asserting "every status is claimed" would fire
-- mid-transaction while a board is rebuilt column by column and reject the legal
-- intermediate state of a PATCH. This is REPAIR, on a table a board PATCH does
-- not write, so it cannot see that intermediate state at all. It is here rather
-- than in BoardConfigService for tcms_seed_project_defaults' reason: the repair
-- has to happen on every path that removes a status, including the ones written
-- later, and a caller that forgets it does not fail — it corrupts a board.
--
-- ON DELETE CASCADE on default_status_id was the one-line alternative and is
-- wrong for the folded board: a stakeholder column claiming In Progress, In
-- Review and Blocked would VANISH when In Progress was retired, taking two live
-- statuses off the board with it and leaving them claimed by nothing — silently.
-- A column is deleted here only when the departing status was the last thing it
-- claimed, which for the seeded one-status-per-column board is every time.
CREATE FUNCTION tcms_board_follows_status_removal() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    -- `status_id <> OLD.id` matters: this is a BEFORE trigger, so the cascade that
    -- removes the departing status's own claim rows has not run yet and they are
    -- still visible. Lowest position wins, so the column keeps meaning the earliest
    -- status it still holds rather than whichever row the planner returned first.
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
                    WHERE bcs.column_id = c.id AND bcs.status_id <> OLD.id);

    DELETE FROM board_column c WHERE c.default_status_id = OLD.id;

    RETURN OLD;
END $$;

CREATE TRIGGER board_follows_status_removal
    BEFORE DELETE ON issue_status
    FOR EACH ROW EXECUTE FUNCTION tcms_board_follows_status_removal();


-- -----------------------------------------------------------------------------
-- 2. LABELS
-- -----------------------------------------------------------------------------
--
-- Trello's primary scanning device (§12.2), and DELIBERATELY NOT a taxonomy.
-- This project already has classification_dimension and case_tag for controlled
-- vocabulary; a label is the informal, fast-moving axis, which is why it is
-- project-scoped, needs no dimension, and is deleted without a migration plan.
--
-- color holds a DESIGN-TOKEN NAME, never a hex value. Status and label colour is
-- data the palette owns (§6u) — a stored #RRGGBB is invisible in the other
-- colour scheme, and this codebase swaps the whole palette on [data-color-scheme].
--
-- THE COLUMN IS `color` AND NOT THE `color_token` PLAN 012 FIRST WROTE (D-i).
-- responseCredentials.test.ts reddens on any response property whose name
-- contains `token`, and its exemption list is a list of disclosure decisions,
-- which a design token is not. The wire property was renamed on the first run of
-- T1's gate; the column follows the wire so JPA needs no override and no layer
-- spells it differently from its neighbour.

CREATE TABLE issue_label (
    id          UUID        PRIMARY KEY,
    company_id  UUID        NOT NULL REFERENCES company(id),
    project_id  UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name        TEXT        NOT NULL,
    color       TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by  UUID
);
CREATE UNIQUE INDEX uq_issue_label_name ON issue_label (company_id, project_id, lower(name));

CREATE TABLE issue_label_assignment (
    company_id UUID        NOT NULL REFERENCES company(id),
    issue_id   UUID        NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    label_id   UUID        NOT NULL REFERENCES issue_label(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, issue_id, label_id)
);
CREATE INDEX ix_issue_label_assignment_label ON issue_label_assignment (company_id, label_id);

-- -----------------------------------------------------------------------------
-- 3. THE FRACTIONAL RANK
-- -----------------------------------------------------------------------------
--
-- NUMERIC WITHOUT A PRECISION, which in Postgres is arbitrary precision. That is
-- the whole point: a midpoint between two neighbours ALWAYS exists, so inserting
-- one row NEVER rewrites another — which an INTEGER position column cannot say,
-- and rewriting every row below the drop is how a backlog drag becomes a
-- thousand-row UPDATE that two people cannot do at once.
--
-- Growth is real and bounded by the service, not by the type: RankService
-- respaces the project at 1024, 2048, 3072 … when a midpoint would need more
-- than 12 fractional digits. LexoRank was considered and refused — same
-- unbounded growth, plus a string-collation dependency nothing else here has.
--
-- NOT NULL with a backfill, rather than nullable: a NULL rank sorts to one end
-- and the backlog would open with every pre-upgrade issue in a block, in
-- creation order, looking ranked when nobody ranked it.

ALTER TABLE issue ADD COLUMN rank NUMERIC;

-- THE BACKFILL IS A LOOP OVER COMPANY, and this is the trap V3's own header
-- names one table over. `issue` has carried FORCE ROW LEVEL SECURITY since V3
-- and Flyway connects as tcms_owner, which holds no BYPASSRLS — so a single
-- UPDATE with app.current_company unset matches ZERO ROWS and reports success.
-- It would not even fail loudly afterwards on a database whose `issue` table is
-- empty, which every Testcontainers run is: the SET NOT NULL below would pass
-- and the defect would first appear on the one instance that has data.
DO $$
DECLARE
    c UUID;
BEGIN
    FOR c IN SELECT id FROM company LOOP
        PERFORM set_config('app.current_company', c::text, true);
        UPDATE issue i
           SET rank = ranked.seq * 1024
          FROM (
            SELECT id, row_number() OVER (PARTITION BY project_id ORDER BY created_at, id) AS seq
              FROM issue
          ) ranked
         WHERE i.id = ranked.id AND i.rank IS NULL;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);
END $$;

ALTER TABLE issue ALTER COLUMN rank SET NOT NULL;

-- A DEFAULT NOBODY CAN WRITE AS A DEFAULT, so it is a trigger.
--
-- D-d says a new issue takes max(rank) + 1024. A column DEFAULT cannot express
-- that — it may not read another row — and the alternatives are worse in ways
-- that are silent:
--
--   * A constant default (0, or 1024) makes every new issue tie with every other
--     new issue, and a backlog ordered by a column where half the rows share one
--     value is ordered by nothing. It would also read as ranked.
--   * Assigning it in IssueService only is a rule that holds for the callers who
--     remember. This schema already made the opposite choice for the issue-key
--     counter and for the seeded workflow, and for the same reason: one
--     definition, on every creation path, including the ones written later.
--
-- Ties are still possible under concurrency — two inserts into one project in
-- the same instant read the same max — and that is accepted rather than locked
-- against: a tie is broken by created_at, and taking a project-wide lock on
-- every issue insert to avoid one would cost more than it buys. Nothing here
-- claims rank is unique, and no index asserts it.
--
-- The SELECT inside runs as the INVOKER, so it is subject to the same policy as
-- the INSERT that fired it — the row it reads is always inside the company whose
-- context is open, which is what makes max() mean "this project's last rank"
-- rather than "some company's".
CREATE FUNCTION tcms_issue_rank_default() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.rank IS NULL THEN
        SELECT coalesce(max(i.rank), 0) + 1024 INTO NEW.rank
          FROM issue i
         WHERE i.company_id = NEW.company_id AND i.project_id = NEW.project_id;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER issue_rank_default
    BEFORE INSERT ON issue
    FOR EACH ROW EXECUTE FUNCTION tcms_issue_rank_default();

-- Leading company_id, as every composite index in this schema does — the RLS
-- predicate is on it and an index that does not lead with it is not used by the
-- filtered scan. SchemaConventionTest checks this mechanically. It is also what
-- makes the trigger above an index scan rather than a sequential one.
CREATE INDEX ix_issue_rank ON issue (company_id, project_id, rank);


-- -----------------------------------------------------------------------------
-- 4. ROW LEVEL SECURITY — six more forced policies
-- -----------------------------------------------------------------------------
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing, but
-- FORCE is what makes the policy apply to a table's OWNER too, and tcms_owner is
-- what Flyway connects as. Without it a migration or an owner-run query reads
-- every company's rows, which is the trap docs/specs/tenancy-invariants.md names.
--
-- The predicate is byte-identical to V1's, including the nullif() —
-- SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate compares them and
-- fails on a divergent one, and it fails equally on an explicit WITH CHECK, which
-- is why there is none: a policy with USING and no WITH CHECK reuses USING as the
-- insert check, so the read boundary IS the write boundary and there is no second
-- clause for somebody to relax later.
--
-- Enabled BEFORE section 6 writes rows, as V3 does: every loop below sets
-- app.current_company before it reads or writes anything.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY[
        'board', 'board_column', 'board_column_status', 'board_swimlane',
        'issue_label', 'issue_label_assignment'
    ];
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

    IF array_length(protected, 1) <> 6 THEN
        RAISE EXCEPTION 'expected 6 new company-isolated tables, found %',
            array_length(protected, 1);
    END IF;
END $$;


-- -----------------------------------------------------------------------------
-- 5. THE DEFAULT BOARD — one more block in tcms_seed_project_defaults
-- -----------------------------------------------------------------------------
--
-- THE WHOLE FUNCTION IS RESTATED, and that is the cost V3's header already
-- accepted rather than a thing to economise on here: CREATE OR REPLACE FUNCTION
-- replaces the BODY, so a block left out of this reproduction silently stops
-- being seeded for every project created from this migration onward — no error,
-- no failing insert, just a project that is missing something nobody notices
-- until they open the screen that needs it. The eight blocks below are
-- classification_dimension, case_status, configuration, doc_space,
-- project_key_sequence, issue_status, issue_transition and issue_type; the ninth
-- is the board this migration adds. ProjectKeySequenceSchemaTest and
-- BoardSchemaTest are the checks that one went missing.
--
-- NOT A ProjectCreatedEvent (011 D-b), and there is still none to build: a Java
-- event type may not cross a module boundary here, and outbox delivery has a
-- one-minute floor, so a board seeded that way would leave a brand-new project
-- with `?view=board` rendering nothing for up to a minute.
CREATE OR REPLACE FUNCTION tcms_seed_project_defaults(p_company_id UUID, p_project_id UUID)
    RETURNS void
    LANGUAGE plpgsql AS $$
DECLARE
    v_spec RECORD;
    v_status RECORD;
    v_board_id UUID;
    v_column_id UUID;
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

    -- V3. The default workflow (D2, story B1).
    --
    -- SIX STATUSES, and the set is the one settings-workflow.html renders. A
    -- brand-new project arrives with exactly this so nobody has to configure
    -- anything before creating their first issue — which is B1's second
    -- criterion, and the reason this is structural rather than "a default
    -- somebody chose".
    --
    -- Blocked is IN_PROGRESS, not a fourth kind. Something blocked is started and
    -- not done; a kind of its own would have to be given a meaning by every
    -- percentage in the product, and the honest answer is that it has none.
    FOR v_spec IN
        SELECT * FROM (VALUES
            ('Backlog',     'NOT_STARTED', 0),
            ('Ready',       'NOT_STARTED', 1),
            ('In Progress', 'IN_PROGRESS', 2),
            ('In Review',   'IN_PROGRESS', 3),
            ('Blocked',     'IN_PROGRESS', 4),
            ('Done',        'DONE',        5)
        ) AS s(name, kind, position)
    LOOP
        INSERT INTO issue_status (id, company_id, project_id, name, kind, "position")
        SELECT gen_random_uuid(), p_company_id, p_project_id, v_spec.name, v_spec.kind, v_spec.position
         WHERE NOT EXISTS (
            SELECT 1 FROM issue_status
             WHERE company_id = p_company_id AND project_id = p_project_id AND name = v_spec.name);
    END LOOP;

    -- The legal moves. AN ALLOW-LIST: every pair not inserted here is refused.
    --
    -- The shape is "anything may go anywhere except INTO Done, which only In
    -- Review reaches" — settings-workflow.html's right-hand panel states exactly
    -- that: from In Progress an issue may go to In Review, Blocked or Ready, and
    -- "Not to Done — a review gate stands between them". Backlog is reachable
    -- from everywhere so a mis-filed issue can be put back.
    FOR v_spec IN
        SELECT f.id AS from_id, t.id AS to_id
          FROM issue_status f
          JOIN issue_status t
            ON t.company_id = f.company_id AND t.project_id = f.project_id AND t.id <> f.id
         WHERE f.company_id = p_company_id AND f.project_id = p_project_id
           AND (t.name <> 'Done' OR f.name = 'In Review')
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
    -- V5. The project's default board (story B2, D2).
    --
    -- SEEDED, NOT OPTIONAL — the same structural argument the configuration and
    -- the doc space above make. `?view=board` has nothing to render without a
    -- board row, and "create your first board" as the entry to the main screen
    -- of the product is a configuration step nobody asked for. One column per
    -- status, which is settings-boards.html's "Team board": one status per column
    -- means a drop has no ambiguity about which status it means.
    --
    -- The STAKEHOLDER board is not seeded. It is the same six statuses folded
    -- into three columns, and which three is a decision about who is looking —
    -- exactly the "inventing the client's structure" mistake that keeps a Default
    -- *suite* out of this function. settings-boards.html is where it is made.
    --
    -- Guarded by NOT EXISTS like every block above it, and that is what lets the
    -- backfill at the foot of V5 call this function rather than restate it: one
    -- definition of what a seeded project contains, which is the rule V3's own
    -- backfill states and the reason ProjectCreateService calls this instead of
    -- inlining its inserts.
    IF NOT EXISTS (SELECT 1 FROM board
                    WHERE company_id = p_company_id AND project_id = p_project_id) THEN
        v_board_id := gen_random_uuid();
        INSERT INTO board (id, company_id, project_id, name, swimlane_by)
        VALUES (v_board_id, p_company_id, p_project_id, 'Team board', 'EPIC');

        FOR v_status IN
            SELECT id, name, "position" FROM issue_status
            WHERE company_id = p_company_id AND project_id = p_project_id
            ORDER BY "position"
        LOOP
            v_column_id := gen_random_uuid();
            INSERT INTO board_column (id, company_id, board_id, name, "position",
                                      wip_limit, default_status_id)
            VALUES (v_column_id, p_company_id, v_board_id, v_status.name, v_status."position",
                    NULL, v_status.id);
            INSERT INTO board_column_status (company_id, board_id, column_id, status_id)
            VALUES (p_company_id, v_board_id, v_column_id, v_status.id);
        END LOOP;
    END IF;
END $$;


-- -----------------------------------------------------------------------------
-- 6. A DEFAULT BOARD FOR EVERY PROJECT THAT ALREADY EXISTS
-- -----------------------------------------------------------------------------
--
-- V3's loop, and the same shape for the same reason. set_config with is_local =
-- true, and the company set per iteration: the seed function asserts the company
-- context and every INSERT it makes is under FORCE ROW LEVEL SECURITY, so a loop
-- that did not set it would read zero projects, insert nothing, and report
-- success. `company` is on the ERD §0.1 exception list and carries no policy, so
-- it can be walked; setting the GUC per company then makes that company's
-- projects visible through the ordinary policy. set_config's third argument is
-- transaction-local and Flyway runs a migration in one transaction, so the
-- setting is gone when this file ends.
--
-- Calling the function rather than restating its inserts is the point, and it is
-- what the NOT EXISTS guard on the board block above buys: every block is
-- idempotent, so a project that already has a board keeps it and one that does
-- not gets exactly what a project created tomorrow would get.
DO $$
DECLARE
    c UUID;
    p UUID;
BEGIN
    FOR c IN SELECT id FROM company LOOP
        PERFORM set_config('app.current_company', c::text, true);
        FOR p IN SELECT id FROM project WHERE company_id = c LOOP
            PERFORM tcms_seed_project_defaults(c, p);
        END LOOP;
    END LOOP;
    PERFORM set_config('app.current_company', '', true);
END $$;


-- -----------------------------------------------------------------------------
-- 7. THE DATA DICTIONARY
-- -----------------------------------------------------------------------------
--
-- SchemaCommentTest reads these and resolves every identifier they name against
-- the live catalog, so a sentence here cannot outlive the structure it describes.

COMMENT ON TABLE board IS
    'A LENS over a project''s issues, never a container for them. No issue points at a board: an '
    'issue appears on every board whose columns claim its status, so dropping a board loses no '
    'work. Several boards over one project are the point — a team board and a stakeholder board '
    'are the same issues folded differently.';
COMMENT ON COLUMN board.swimlane_by IS
    'The axis the board splits into lanes. RELEASE is deliberately absent until M3 adds the table '
    'behind it: a lane axis that is NULL for every issue renders one lane called None, which reads '
    'as a broken board rather than as an unbuilt feature.';

COMMENT ON TABLE board_column IS
    'One column of a board. Its position orders the columns left to right, and it is unique per '
    'board so two columns cannot claim one slot.';
COMMENT ON COLUMN board_column.default_status_id IS
    'The status a drop into this column MEANS. NOT NULL because a column whose default is unset is '
    'a column a drag cannot resolve, and choosing one of the claimed statuses at random is how a '
    'stakeholder board silently sets an issue to Blocked.';
COMMENT ON COLUMN board_column.wip_limit IS
    'NULL is no limit, and it is NOT zero: a zero limit would put the column permanently in breach, '
    'so its header would shout forever about a state nobody can leave.';

COMMENT ON TABLE board_column_status IS
    'Which statuses a column claims. Every status is claimed by exactly one column per board, and '
    'only half of that is expressible here: uq_board_column_status is at most one, while at least '
    'one is a cross-table assertion Postgres has no CHECK for and BoardConfigService enforces. A '
    'trigger was refused because it would reject the legal intermediate state of a board being '
    'rebuilt column by column. board_id is denormalised onto the row so the unique index can exist '
    'at all.';

COMMENT ON TABLE board_swimlane IS
    'Which lanes one PERSON has collapsed on one board. Separate from board.swimlane_by, which is '
    'the board''s own configuration: collapsing a lane is not editing the board, and a shared '
    'collapse state would mean one person''s tidying hides work from everybody else.';

COMMENT ON TABLE issue_label IS
    'The informal, fast-moving axis, and DELIBERATELY NOT a taxonomy — classification_dimension '
    'and case_tag are what this schema has for controlled vocabulary. A label is project-scoped, '
    'needs no dimension, and is deleted without a migration plan.';
COMMENT ON COLUMN issue_label.color IS
    'A DESIGN-TOKEN NAME, never a hex value. Label and status colour is data the palette owns, and '
    'a stored value would be invisible in the other colour scheme because this product swaps the '
    'whole palette on the document''s colour-scheme attribute. The column is named for the wire '
    'property, which lost its longer name to the check that refuses any response property spelling '
    'a credential.';

COMMENT ON TABLE issue_label_assignment IS
    'Labels on an issue. Putting one on is an issue edit rather than board configuration, which is '
    'why it needs no permission of its own.';

COMMENT ON COLUMN issue.rank IS
    'The backlog position, as arbitrary-precision NUMERIC and deliberately WITHOUT a precision: a '
    'midpoint between two neighbours always exists, so placing one issue never rewrites another. A '
    'fixed scale runs out of midpoints after about a dozen inserts between one pair and then '
    'returns a value equal to a neighbour''s, silently. Growth is bounded by RankService, which '
    'respaces the project when a midpoint would need more than twelve fractional digits. '
    'Project-wide and not per board, so two boards over one project cannot disagree about what is '
    'next. Not unique: concurrent inserts into one project may tie, and created_at breaks it.';

COMMENT ON FUNCTION tcms_issue_rank_default() IS
    'Gives a new issue the next rank in its project when the insert does not name one. A column '
    'DEFAULT cannot express it because it may not read another row, and a constant would make '
    'every new issue tie with every other. Seeded here rather than in one service for the reason '
    'tcms_seed_project_defaults exists: one definition, on every creation path, including the ones '
    'written later.';

COMMENT ON FUNCTION tcms_board_follows_status_removal() IS
    'Rearranges every board around a status leaving the workflow: a column that claimed something '
    'else keeps its place and defaults to the earliest status it still holds, and a column that '
    'claimed only the departing one goes with it. The board is a lens and may not veto the '
    'workflow, which a plain RESTRICT on board_column.default_status_id would let it do.';

COMMENT ON FUNCTION tcms_seed_project_defaults(UUID, UUID) IS
    'Everything a new project gets: the feature dimension, the seven execution statuses, a Default '
    'configuration, a documentation space, the issue-key counter, the six-status workflow with its '
    'transitions and types, and (V5) a default board with one column per status. Called by every '
    'creation path, so a path added later gets all of it without its author remembering.';
