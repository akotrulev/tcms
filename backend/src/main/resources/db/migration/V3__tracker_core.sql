-- =============================================================================
-- V3 — the tracker (plan 011, module M1 of 009)
--
-- Nine tables, one seeded workflow per project, and the migration of work_item.
-- What a person can do afterwards that they could not before: run a project's
-- work — epics, stories, tasks, transitions, comments, spec links.
--
-- V1 ONLY EVER RUNS ON AN EMPTY DATABASE. This file does not, and neither did V2:
-- it runs against instances that already hold work_item rows people typed, and
-- section 6 migrates every one of them rather than asking anybody to re-enter it.
--
-- THE HIERARCHY IS EXPRESSED IN LEVELS, NOT IN TYPE NAMES (scope §4). issue_type
-- carries level EPIC/STORY/TASK and the rule is "a type may only parent to a type
-- one level above it". Story, Bug and Spike all sit at STORY, so all three hold
-- Tasks and all three may sit under an Epic — and adding Chore or Tech Debt at
-- STORY is a ROW, not a migration and not a service change. A rule written
-- against type NAMES would have to be edited in code the day a fourth
-- story-level type is wanted. Same reasoning issue_status follows by
-- categorising on `kind` rather than on a status's name (D2).
--
-- WHAT THIS FILE DOES NOT DO, and the reason, because a reader will look for it:
-- it does NOT drop work_item and work_item_link. Plan 011 T2 Part D said to, and
-- that statement cannot stand alone — com.tcms.work.WorkItem is still an @Entity
-- mapped to work_item, and hibernate.ddl-auto is `validate`, so a database
-- without the table refuses to boot the Spring context and every integration test
-- in the suite fails before its first assertion. T2 cannot delete that entity
-- either: BoardService reads WorkItemRepository until T3 gives it
-- BoardFacts.assignedIssues. So the two halves are separated the way
-- expand-contract requires — V3 COPIES the rows, T4 deletes the nine `work`
-- classes and drops the two tables in V4, in the same commit. The cost, stated
-- rather than discovered: between this migration and that one a migrated task
-- exists twice, once as a work_item the old board still renders and once as the
-- issue it became. Nothing user-facing ships in that window, and the alternative
-- was a backend gate that could not be run for three tasks.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. THE PROJECT'S VOCABULARY — types, statuses, and the legal moves between them
-- -----------------------------------------------------------------------------
--
-- All three are PER PROJECT and seeded by tcms_seed_project_defaults (section 5),
-- which is the same structural argument case_status, configuration and doc_space
-- already make in that function: without these rows a project cannot hold an
-- issue at all, so they are not "a default somebody chose" but the thing that
-- makes the table usable.

CREATE TABLE issue_type (
    id                         UUID    PRIMARY KEY,
    company_id                 UUID    NOT NULL REFERENCES company(id),
    project_id                 UUID    NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name                       TEXT    NOT NULL,
    level                      TEXT    NOT NULL CHECK (level IN ('EPIC','STORY','TASK')),
    counts_toward_completion   BOOLEAN NOT NULL DEFAULT true,
    icon_code                  TEXT    NOT NULL,
    "position"                 INTEGER NOT NULL,
    CONSTRAINT uq_issue_type_name UNIQUE (company_id, project_id, name)
);
COMMENT ON COLUMN issue_type.level IS
    'EPIC, STORY or TASK. THE DEPTH RULE IS ABOUT THIS COLUMN AND NEVER ABOUT name: a type may '
    'only parent to a type one level above it. Story, Bug and Spike all sit at STORY, so a fourth '
    'story-level type is a row rather than a migration (scope §4). D29 is why a type NAMED Task '
    'also sits at STORY — a one-person company managing its own product should not need an Epic '
    'and a Story before "a thing to do", and orphan TASK-level issues stay refused.';
COMMENT ON COLUMN issue_type.counts_toward_completion IS
    'FALSE for Spike and for every EPIC-level type. A spike is a time-boxed investigation that '
    'produces knowledge, not shippable work, so counting it would let an epic read 40% done having '
    'shipped nothing (D8). It still consumes hours and so still counts toward budget burn — the '
    'two numbers answer different questions and are ALLOWED TO DISAGREE.';
COMMENT ON COLUMN issue_type.icon_code IS
    'E, S, T, B or ? — what the type-icon chip renders. Stored rather than derived from the name, '
    'because a renamed type keeps its icon and a fifth type chooses one.';
CREATE INDEX ix_issue_type_project ON issue_type (company_id, project_id, "position");

CREATE TABLE issue_status (
    id         UUID    PRIMARY KEY,
    company_id UUID    NOT NULL REFERENCES company(id),
    project_id UUID    NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name       TEXT    NOT NULL,
    kind       TEXT    NOT NULL CHECK (kind IN ('NOT_STARTED','IN_PROGRESS','DONE')),
    "position" INTEGER NOT NULL,
    CONSTRAINT uq_issue_status_name UNIQUE (company_id, project_id, name)
);
COMMENT ON TABLE issue_status IS
    'The statuses ONE project uses, in order. D2: one canonical status per issue, and boards map '
    'statuses onto columns rather than owning them — which is what stops two boards over the same '
    'work from disagreeing about where a card is.';
COMMENT ON COLUMN issue_status.kind IS
    'What the status MEANS, so every percentage in the product can be computed without knowing '
    'what this project called its columns. EVERY completion figure is DONE over the counted total; '
    'IN_PROGRESS IS NOT PARTIAL CREDIT (D8) — a number that moves before anything ships is the '
    'number people learn to distrust, and once distrusted it is decoration. The same column '
    'case_status.kind already is for executions, for the same reason.';
CREATE INDEX ix_issue_status_project ON issue_status (company_id, project_id, "position");

CREATE TABLE issue_transition (
    company_id     UUID NOT NULL REFERENCES company(id),
    project_id     UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    from_status_id UUID NOT NULL REFERENCES issue_status(id) ON DELETE CASCADE,
    to_status_id   UUID NOT NULL REFERENCES issue_status(id) ON DELETE CASCADE,
    PRIMARY KEY (from_status_id, to_status_id),
    CONSTRAINT ck_issue_transition_distinct CHECK (from_status_id <> to_status_id)
);
COMMENT ON TABLE issue_transition IS
    'The legal moves. AN ALLOW-LIST, NOT A DENY-LIST: a pair that is not here is refused, so a '
    'status added to a workflow reaches nothing until somebody says where it goes. Story H5 — '
    '"only the moves the project workflow allows are offered", and a forbidden drop on the board '
    'is refused with the reason rather than snapping back in silence.';
COMMENT ON CONSTRAINT ck_issue_transition_distinct ON issue_transition IS
    'A status is always a legal destination from itself and the service says so without a row. '
    'Storing the self-pair would make "no moves allowed" and "only the current one" the same set.';
CREATE INDEX ix_issue_transition_from ON issue_transition (company_id, project_id, from_status_id);


-- -----------------------------------------------------------------------------
-- 2. THE ISSUE, AND WHY THE KEY IS TWO COLUMNS
-- -----------------------------------------------------------------------------

CREATE TABLE issue (
    id                      UUID        PRIMARY KEY,
    company_id              UUID        NOT NULL REFERENCES company(id),
    project_id              UUID        NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    key_seq                 BIGINT      NOT NULL,
    issue_key               TEXT        NOT NULL,
    type_id                 UUID        NOT NULL REFERENCES issue_type(id),
    status_id               UUID        NOT NULL REFERENCES issue_status(id),
    parent_id               UUID        REFERENCES issue(id) ON DELETE RESTRICT,
    title                   TEXT        NOT NULL,
    description             TEXT,
    acceptance_criteria     JSONB       NOT NULL DEFAULT '[]'::jsonb,
    priority                TEXT        NOT NULL DEFAULT 'MEDIUM'
                            CHECK (priority IN ('CRITICAL','HIGH','MEDIUM','LOW')),
    estimate_hours          NUMERIC(8,2),
    start_date              DATE,
    target_date             DATE,
    due_date                DATE,
    assignee_id             UUID        REFERENCES app_user(id),
    reporter_id             UUID        REFERENCES app_user(id),
    status_entered_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    done_at                 TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by              UUID,
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by              UUID,
    deleted_at              TIMESTAMPTZ,
    lock_version            BIGINT      NOT NULL DEFAULT 0,
    created_idempotency_key TEXT,
    CONSTRAINT uq_issue_key UNIQUE (company_id, issue_key),
    CONSTRAINT ck_issue_not_own_parent CHECK (parent_id IS NULL OR parent_id <> id)
);
COMMENT ON TABLE issue IS
    'ONE table for all three levels, with a self-referencing parent_id and an issue_type FK '
    '(scope §4). DELIBERATELY NOT JIRA''S SHAPE: there a Sub-task is a second-class citizen that '
    'cannot be estimated or moved independently. Here a Task is a first-class issue that happens '
    'to have a parent — which is what lets a dev and a QA each hold a Task under one Story.';
COMMENT ON COLUMN issue.id IS
    'Carried from work_item for a migrated row, so derived_item_annotation''s (source_type, '
    'source_id) still resolves. That table survives M1 (D11) and its rows are somebody''s private '
    'notes — a fresh id here would have orphaned every one of them silently.';
COMMENT ON COLUMN issue.key_seq IS
    'The number behind the key, from project_key_sequence via project.ProjectKeys. THE ONLY COLUMN '
    'THAT MAY ORDER ISSUE KEYS — sorting on issue_key puts ACME-100 before ACME-2, which is the '
    'defect this column exists to make unavailable rather than merely discouraged.';
COMMENT ON COLUMN issue.issue_key IS
    'ACME-124. STORED rather than composed on read from project.key || key_seq, and the reason is '
    'the same one doc_link.external_host records: what is rendered and what is typed into a commit '
    'message must not be derived from something that can move underneath it. settings-general.html '
    'promises "existing links keep working if it changes" — a composed key would silently rewrite '
    'every historical reference the moment a project was rekeyed.';
COMMENT ON COLUMN issue.parent_id IS
    'ON DELETE RESTRICT, not CASCADE. Deleting an epic that holds seventeen stories is either a '
    'cascade nobody asked for or seventeen orphans; the endpoint answers 409 and names the count. '
    'The depth rule this column carries is enforced in the service against issue_type.level, not '
    'by a constraint — a CHECK cannot reach through two rows to compare levels.';
COMMENT ON COLUMN issue.acceptance_criteria IS
    'An ordered array of {id, text, done}. JSONB rather than a table because nothing joins to a '
    'criterion, nothing reports on one, and the whole list is written by one form in one request — '
    'a table would buy referential integrity for an edge that has no other end.';
COMMENT ON COLUMN issue.estimate_hours IS
    'HOURS, NOT STORY POINTS (D8, decided 2026-08-30). Hours are directly comparable to '
    'time_entry actuals, so estimate-vs-actual and budget burn work with no conversion — which is '
    'what matters on contracted client work, where the estimate and the invoice have to be the '
    'same unit. THE KNOWN COST, recorded rather than discovered later: hours read as commitments '
    'and get quoted back at you, and D6 removed sprints, so there is NO VELOCITY to recalibrate '
    'them against. The mitigation is that every page naming a percentage also names its basis.';
COMMENT ON COLUMN issue.start_date IS
    'Epics only — a body of work has a span; a story has a due date. Null on every other level, '
    'and the service refuses to set it there rather than the column allowing a value nothing '
    'renders.';
COMMENT ON COLUMN issue.status_entered_at IS
    'When this issue entered its CURRENT status. Cycle time is unanswerable from a row that holds '
    'only the current status and when it was created (009 review R12), and this is the cheap half '
    'of the answer — the full history is audit_event, and issue_fact carries this forward for M9.';
COMMENT ON COLUMN issue.done_at IS
    'When it FIRST reached a DONE-kind status, and never cleared by a reopen. A reopened issue has '
    'been done once, and a lead time computed from a cleared column would silently lengthen every '
    'time somebody reopened something.';
COMMENT ON COLUMN issue.deleted_at IS
    'Soft delete, inherited from work_item and REQUIRED rather than stylistic. Scope §3: a '
    'downstream time_entry or invoice_line holds an id into this table WITH NO FOREIGN KEY, '
    'because the module rule forbids one. A hard delete orphans them silently; this is the '
    'mitigation, together with the consistency check in the nightly suite.';
CREATE UNIQUE INDEX issue_created_idempotency_key_idx
    ON issue (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;
CREATE INDEX ix_issue_project_status ON issue (company_id, project_id, status_id)
    WHERE deleted_at IS NULL;
CREATE INDEX ix_issue_parent ON issue (company_id, parent_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_issue_assignee ON issue (company_id, assignee_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_issue_project_seq ON issue (company_id, project_id, key_seq);

CREATE TABLE issue_comment (
    id         UUID        PRIMARY KEY,
    company_id UUID        NOT NULL REFERENCES company(id),
    issue_id   UUID        NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    body       TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by UUID,
    edited_at  TIMESTAMPTZ
);
CREATE INDEX ix_issue_comment_issue ON issue_comment (company_id, issue_id, created_at);

CREATE TABLE issue_watcher (
    company_id UUID        NOT NULL REFERENCES company(id),
    issue_id   UUID        NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    user_id    UUID        NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (issue_id, user_id)
);
COMMENT ON TABLE issue_watcher IS
    'Who is following an issue (story H8). An author is added here when they comment, so a person '
    'who has said something is following it by default rather than having to also press a star.';
CREATE INDEX ix_issue_watcher_user ON issue_watcher (company_id, user_id);

CREATE TABLE issue_link (
    company_id  UUID NOT NULL REFERENCES company(id),
    issue_id    UUID NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    target_type TEXT NOT NULL,
    target_id   UUID NOT NULL,
    PRIMARY KEY (issue_id, target_type, target_id)
);
COMMENT ON TABLE issue_link IS
    'work_item_link''s successor, and the same shape for the same reason: a polymorphic reference '
    'across a module boundary, WITH NO FOREIGN KEY on target_id. Scope §3 — nearly everything in a '
    'PM system points at an issue, and tracker is not a foundation module, so the pointer is an id '
    'and the price is integrity enforced in application code. TEST_CASE is the target type story '
    'H7 needs and it costs one enum member; the rows arrive with M3.';
CREATE INDEX ix_issue_link_target ON issue_link (company_id, target_type, target_id);

CREATE TABLE issue_doc_link (
    id                  UUID    PRIMARY KEY,
    company_id          UUID    NOT NULL REFERENCES company(id),
    issue_id            UUID    NOT NULL REFERENCES issue(id) ON DELETE CASCADE,
    target_kind         TEXT    NOT NULL,
    relationship        TEXT    NOT NULL,
    page_id             UUID,
    page_key            TEXT,
    page_title          TEXT,
    block_id            UUID,
    block_excerpt       TEXT,
    stale               BOOLEAN NOT NULL,
    external_repo       TEXT,
    external_path       TEXT,
    external_line_start INTEGER,
    external_line_end   INTEGER,
    external_commit_sha TEXT,
    external_url        TEXT,
    external_provider   TEXT,
    external_issue_id   TEXT,
    external_host       TEXT,
    CONSTRAINT ck_issue_doc_link_target CHECK ((target_kind = 'DOC_BLOCK' AND page_id IS NOT NULL AND page_key IS NOT NULL AND page_title IS NOT NULL AND block_id IS NOT NULL)
        OR (target_kind = 'EXTERNAL_GIT' AND external_path IS NOT NULL AND external_commit_sha IS NOT NULL)
        OR (target_kind = 'EXTERNAL_URL' AND external_url IS NOT NULL)
        OR (target_kind = 'EXTERNAL_ISSUE' AND external_issue_id IS NOT NULL AND external_host IS NOT NULL AND external_url IS NOT NULL))
);
COMMENT ON TABLE issue_doc_link IS
    'A read model in `tracker`, mirroring doc_link from the issue end — case_doc_link field for '
    'field, INCLUDING stale. Written only by DocLinkProjection, replaced whole per company on '
    'every refresh. doc_link is the source of truth; this is what the epic screen may read without '
    'naming another module''s tables on a request path (§3 lesson 4). THE WRITE LIVES IN '
    'documentation — POST …/blocks/{blockId}/links with sourceType ISSUE — because that module '
    'owns doc_link and tracker may not import it.';
COMMENT ON COLUMN issue_doc_link.block_excerpt IS
    'The first 200 characters of the block''s text WHEN IT WAS LINKED, with a trailing ellipsis '
    'when it was cut. Not the current text — that is the entire point of `stale`: story H1 says an '
    'epic linked to a spec block should say so when the spec moves underneath it, and '
    're-confirming replaces this with the current text and clears the flag.';
COMMENT ON COLUMN issue_doc_link.stale IS
    'The linked page changed after this issue last did — newest doc_page_version against '
    'issue.updated_at. PAGE granularity, not block: doc_block carries no timestamp, so an edit '
    'elsewhere on the page raises it too. ALWAYS FALSE FOR AN EXTERNAL TARGET, for '
    'case_doc_link.stale''s two reasons — a git range is PINNED by its sha, and an external issue '
    'carries no derived meaning for a freshness marker to be about.';
CREATE INDEX ix_issue_doc_link_issue ON issue_doc_link (company_id, issue_id);
CREATE INDEX ix_issue_doc_link_block ON issue_doc_link (company_id, block_id);


-- -----------------------------------------------------------------------------
-- 3. issue_fact — THE PROJECTION EVERY DOWNSTREAM MODULE READS
-- -----------------------------------------------------------------------------
--
-- Scope §3: there is no sixth foundation module, and tracker holds the largest
-- domain concept in the product so it will never be one. Downstream modules
-- reference an issue POLYMORPHICALLY and keep their own projection — time_entry
-- carries (subject_type, subject_id), billing joins this, reporting joins this.
--
-- THE TRAP THIS TABLE EXISTS TO AVOID, stated at the schema so nobody has to find
-- it in a document: issue_fact MUST BE FED BY AN EVENT AND NEVER WRITTEN BY time
-- OR billing. The moment a downstream module writes it, tracker is no longer the
-- authority on issue state and the two disagree SILENTLY. One writer:
-- tracker.IssueFactProjection, on the tracker.issues_changed outbox event.
--
-- THE FOUR TIMESTAMPS ARE M9'S SCHEMA, LANDING HERE (009 review R12). M9 was
-- promised it would need no migration, and that promise is only keepable if the
-- columns cycle time and "scope added after the release was created" need are
-- written from the start. created_at, status_entered_at, done_at and
-- release_assigned_at are unanswerable from a projection holding only the current
-- status — and backfilling them later is impossible, because the events that
-- would have populated them have already happened.

CREATE TABLE issue_fact (
    issue_id                 UUID        PRIMARY KEY,
    company_id               UUID        NOT NULL,
    project_id               UUID        NOT NULL,
    issue_key                TEXT        NOT NULL,
    title                    TEXT        NOT NULL,
    type_name                TEXT        NOT NULL,
    type_level               TEXT        NOT NULL,
    counts_toward_completion BOOLEAN     NOT NULL,
    status_name              TEXT        NOT NULL,
    status_kind              TEXT        NOT NULL,
    epic_id                  UUID,
    parent_id                UUID,
    assignee_id              UUID,
    estimate_hours           NUMERIC(8,2),
    release_id               UUID,
    created_at               TIMESTAMPTZ NOT NULL,
    status_entered_at        TIMESTAMPTZ NOT NULL,
    done_at                  TIMESTAMPTZ,
    release_assigned_at      TIMESTAMPTZ,
    deleted_at               TIMESTAMPTZ
);
COMMENT ON TABLE issue_fact IS
    'Event-fed read projection of issue state, for reporting, time and billing to join WITHOUT '
    'importing tracker. Deliberately duplicates dimensions to avoid joins, exactly as '
    'execution_fact does. ONE WRITER — tracker.IssueFactProjection. A downstream module writing '
    'here is the failure scope §3 names by name.';
COMMENT ON COLUMN issue_fact.release_id IS
    'NULL until M3, which is when a release exists to point at. The column is here rather than in '
    'V5 because release_assigned_at beside it is the "scope added after the release was created" '
    'measure, and a timestamp cannot be backfilled for assignments that already happened.';
COMMENT ON COLUMN issue_fact.release_assigned_at IS
    'When this issue was FIRST pointed at its current release. The burn-up''s scope line is the '
    'difference between this and release.created_at (M9), and it is the one number in that chart '
    'that cannot be reconstructed after the fact.';
COMMENT ON COLUMN issue_fact.deleted_at IS
    'Mirrors issue.deleted_at rather than the row being removed, because reporting over a closed '
    'period must not change when somebody tidies the backlog afterwards.';
CREATE INDEX ix_issue_fact_project ON issue_fact (company_id, project_id, status_kind);
CREATE INDEX ix_issue_fact_epic ON issue_fact (company_id, epic_id);
CREATE INDEX ix_issue_fact_release ON issue_fact (company_id, release_id);


-- -----------------------------------------------------------------------------
-- 4. FORCED RLS ON ALL NINE
-- -----------------------------------------------------------------------------
--
-- V1's loop, restated for the nine new tables. FORCE means the policy applies to
-- the table's owner too, which is what makes the backstop real rather than a
-- property of which role happens to connect. The predicate is byte-identical to
-- V1's — SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate compares
-- them and fails on a divergent one, which is exactly the check that catches a
-- policy retyped from memory.
--
-- Enabled BEFORE sections 5 and 6 write rows, unlike V2, and that is safe rather
-- than an oversight of V2's ordering note: every loop below sets
-- app.current_company before it reads or writes anything, so the policy is
-- satisfied on both sides. V2 enabled its policy afterwards because its backfill
-- ran without a per-row company context to set.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY[
        'issue', 'issue_type', 'issue_status', 'issue_transition', 'issue_link',
        'issue_comment', 'issue_watcher', 'issue_doc_link', 'issue_fact'
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

    IF array_length(protected, 1) <> 9 THEN
        RAISE EXCEPTION 'expected 9 new company-isolated tables, found %',
            array_length(protected, 1);
    END IF;
END $$;

