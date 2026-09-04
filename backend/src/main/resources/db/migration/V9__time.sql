-- =============================================================================
-- V9 — time (plan 009 module M6, plan 016)
--
-- THE VERSION IS V9, AND THE DIRECTORY IS WHAT SAYS SO. 009's table reserved V8,
-- then V10, then V11 for this module; all three were arithmetic over an assumed
-- landing order. Re-read on 2026-09-04: V1..V8, V8 being M5's. Mc and M7 have
-- NOT started, so the numbers reserved for them are unspent and this is V9.
-- `out-of-order` is unset in application.yml, so a reused number is SILENTLY not
-- applied, and Testcontainers builds every test database from empty and cannot
-- show the conflict.
--
-- Three tables and one column. NO rate, NO cost, NO invoice: that is M8, and the
-- boundary is the sharpest one in the expansion (scope §7).
-- =============================================================================

-- 1. TIMESHEET_PERIOD — one per person per half-month (016 D-a).
--
-- The cadence is 1-15 and 16-end-of-month, and it is NOT configurable: no mockup
-- has a control that sets one, and a column no screen writes is a migration for
-- generality nobody asked for. time.Periods derives the bounds arithmetically;
-- a row here is created lazily by the first write into the period.
--
-- The state machine is OPEN -> SUBMITTED -> APPROVED | REJECTED, and REJECTED
-- returns to editable WITHOUT returning to OPEN, so the note that sent it back
-- survives on the row and is shown at the top of the unlocked period. A
-- rejection is never silent and never a deletion (timesheet-approvals.html).
CREATE TABLE timesheet_period (
    id            UUID        PRIMARY KEY,
    company_id    UUID        NOT NULL REFERENCES company(id),
    person_id     UUID        NOT NULL REFERENCES app_user(id),
    starts_on     DATE        NOT NULL,
    ends_on       DATE        NOT NULL,
    state         TEXT        NOT NULL DEFAULT 'OPEN',
    submitted_at  TIMESTAMPTZ,
    decided_at    TIMESTAMPTZ,
    decided_by    UUID        REFERENCES app_user(id),
    decision_note TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    lock_version  BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uq_timesheet_period UNIQUE (company_id, person_id, starts_on),
    CONSTRAINT ck_timesheet_period_state
        CHECK (state IN ('OPEN','SUBMITTED','APPROVED','REJECTED')),
    CONSTRAINT ck_timesheet_period_bounds CHECK (ends_on >= starts_on),
    CONSTRAINT ck_timesheet_period_note
        CHECK (state <> 'REJECTED' OR decision_note IS NOT NULL)
);
COMMENT ON TABLE timesheet_period IS
    'One person''s half-month of time (016 D-a). SPANS PROJECTS deliberately — a period is a '
    'person''s fortnight, not a project''s, which is why 016 D-i has to say who may approve one.';
COMMENT ON COLUMN timesheet_period.decision_note IS
    'The rejector''s note. NOT cleared when the period is re-submitted: the engineer needs to see '
    'what they were asked to fix while they fix it, and the history of why a fortnight moved twice '
    'is worth more than a tidy row. ck_timesheet_period_note makes a silent rejection impossible.';
CREATE INDEX ix_timesheet_period_person
    ON timesheet_period (company_id, person_id, starts_on DESC);
CREATE INDEX ix_timesheet_period_queue
    ON timesheet_period (company_id, starts_on, state);

-- 2. TIME_ENTRY — the polymorphic one (scope §3).
--
-- subject_type + subject_id, and DELIBERATELY NO FOREIGN KEY on subject_id.
-- ArchUnit permits a cross-module dependency only toward the five foundation
-- modules, and `tracker` is not one and will never be one: it holds the largest
-- domain concept in the product and fails both clauses of the bar. So a module
-- downstream of an issue references it polymorphically and keeps its own
-- projection. attachment.owner_id, doc_link.source_id, review.subject_id and
-- issue_link.target_id already pay exactly this price — the last of them being
-- work_item_link's successor, since V4 dropped that table.
--
-- WHAT IT COSTS, STATED PLAINLY: referential integrity between an entry and its
-- issue is enforced in application code, not here. A deleted issue can orphan an
-- entry. The mitigation is soft delete (issue_fact.deleted_at mirrors
-- issue.deleted_at) plus the nightly consistency check.
--
-- project_id, by contrast, IS a real FK: `project` is a foundation module, the
-- approvals queue groups by it (016 D-i) and the work table totals by it.
CREATE TABLE time_entry (
    id           UUID          PRIMARY KEY,
    company_id   UUID          NOT NULL REFERENCES company(id),
    period_id    UUID          NOT NULL REFERENCES timesheet_period(id) ON DELETE CASCADE,
    person_id    UUID          NOT NULL REFERENCES app_user(id),
    subject_type TEXT          NOT NULL,
    subject_id   UUID,
    project_id   UUID          REFERENCES project(id),
    spent_on     DATE          NOT NULL,
    hours        NUMERIC(5,2)  NOT NULL,
    note         TEXT,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    created_by   UUID,
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by   UUID,
    lock_version BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ck_time_entry_subject_type
        CHECK (subject_type IN ('ISSUE','MEETING','LEAVE','TRAINING','INTERNAL')),
    CONSTRAINT ck_time_entry_subject_shape CHECK (
        (subject_type =  'ISSUE' AND subject_id IS NOT NULL AND project_id IS NOT NULL) OR
        (subject_type <> 'ISSUE' AND subject_id IS     NULL AND project_id IS     NULL)),
    CONSTRAINT ck_time_entry_hours CHECK (hours > 0 AND hours <= 24)
);
COMMENT ON TABLE time_entry IS
    'Hours, never points (009 D8) — the same unit a budget and an invoice use, which is the whole '
    'reason an estimate is expressible here. NO RATE COLUMN AND NO COST COLUMN, in this table or '
    'any other this module owns: what an hour is worth is M8''s, gated on billing:* and '
    'ratecard:read, and a Test Engineer with time:log must be unable to reach it.';
COMMENT ON COLUMN time_entry.subject_type IS
    'Five members (016 D-b). TEST_RUN is deliberately not one: timesheet-approvals.html displays a '
    'row logged against a test run, but timesheets.html — the only screen that CREATES an entry — '
    'cannot produce one, and a kind no person can make is a kind that does not exist. The two '
    'mockups disagree and the form wins. Adding it later is one member of this CHECK.';
COMMENT ON COLUMN time_entry.subject_id IS
    'NO FOREIGN KEY, and that is the module rule rather than an omission — see the table''s block '
    'comment above. NULL for every non-ISSUE kind, enforced by ck_time_entry_subject_shape.';
CREATE INDEX ix_time_entry_period ON time_entry (company_id, period_id, spent_on);
CREATE INDEX ix_time_entry_subject ON time_entry (company_id, subject_type, subject_id);
CREATE INDEX ix_time_entry_project ON time_entry (company_id, project_id, spent_on);

-- 3. ISSUE_TIME_ROLLUP — this module's own projection, at the ISSUE grain.
--
-- ONE WRITER, AND IT IS time.RollupService. Note which table this is NOT:
-- issue_fact has exactly one writer, tracker.IssueFactProjection, and a
-- downstream module writing THERE is the failure scope §3 names by name (009
-- review R10). This module READS issue_fact — through one class,
-- time.IssueFacts — and writes only here.
--
-- The story, epic and project figures are aggregated at READ time by joining
-- issue_fact for epic_id / parent_id / project_id. Three stored grains would be
-- three tables that can disagree about the same hours.
--
-- Approved and pending are separate columns (016 D-d) because epic.html says
-- "approved hours only", story.html says "14h (approved)" and timesheets.html's
-- own KPI counts hours nobody has approved. One number could not say all three.
CREATE TABLE issue_time_rollup (
    issue_id       UUID          PRIMARY KEY,
    company_id     UUID          NOT NULL REFERENCES company(id),
    project_id     UUID          NOT NULL REFERENCES project(id),
    approved_hours NUMERIC(10,2) NOT NULL DEFAULT 0,
    pending_hours  NUMERIC(10,2) NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_issue_time_rollup_nonneg
        CHECK (approved_hours >= 0 AND pending_hours >= 0)
);
COMMENT ON TABLE issue_time_rollup IS
    'time''s own projection of hours per issue. ONE WRITER — time.RollupService. Maintained '
    'SYNCHRONOUSLY inside the log/approve transaction rather than through the outbox (016 D-h): '
    'this module owns both sides, so there is no boundary to cross, and the outbox has a delivery '
    'floor around a minute while the story panel must be right the moment Approve is clicked.';
CREATE INDEX ix_issue_time_rollup_project ON issue_time_rollup (company_id, project_id);

-- 4. PROJECT_SETTINGS — the time-tracking toggle (016 D-g).
--
-- DEFAULT TRUE, not false. work-table.html says "Logged hours are here because
-- this project has time tracking on", and a product whose timesheet is empty on
-- every project until somebody finds a switch is a feature nobody discovers.
-- Turning it off hides the Logged column, the story and epic time panels and the
-- project's issues in the log form's picker; it deletes nothing already recorded.
ALTER TABLE project_settings
    ADD COLUMN track_time BOOLEAN NOT NULL DEFAULT true;
COMMENT ON COLUMN project_settings.track_time IS
    '016 D-g — "Track time on this project" (settings-general.html). ON by default. Off hides the '
    'surfaces; it never deletes an entry, because hours somebody worked are not a display setting.';

-- 5. ROW LEVEL SECURITY — three more forced policies, predicate byte-identical
--    to V1 (SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate
--    compares it, and fails equally on an explicit WITH CHECK — a USING-only
--    policy reuses USING as the insert check, so the read boundary IS the write
--    boundary).
--
-- FORCE, not merely ENABLE: the app connects as tcms_app, which owns nothing,
-- but FORCE is what makes the policy apply to a table's OWNER too, and
-- tcms_owner is what Flyway connects as. Without it a migration or an owner-run
-- query reads every company's rows (docs/specs/tenancy-invariants.md).
--
-- project_settings already carries its V1 policy; a column added to a protected
-- table is protected by it.
DO $$
DECLARE
    t         TEXT;
    protected TEXT[] := ARRAY['timesheet_period','time_entry','issue_time_rollup'];
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

    IF array_length(protected, 1) <> 3 THEN
        RAISE EXCEPTION 'expected 3 new company-isolated tables, found %', array_length(protected, 1);
    END IF;
END $$;

-- 6. tcms_seed_project_defaults IS DELIBERATELY NOT TOUCHED.
--
-- Nothing here is a per-project default row. track_time is a DEFAULT on a
-- column of a table the project already gets, and a project with no time
-- entries and no period is the correct empty state. The function is not
-- restated "to be safe" — reproducing it is the only way this file could break
-- it, since CREATE OR REPLACE FUNCTION replaces the whole body (V3's §5, V8's
-- §5), and a block omitted from a reproduction silently stops being seeded.
--
-- The app role (tcms_app) needs no explicit GRANT: the two-role split grants it
-- SELECT/INSERT/UPDATE/DELETE on every new table in schema public through
-- ALTER DEFAULT PRIVILEGES FOR ROLE tcms_owner, set up before Flyway runs
-- (deploy/postgres/initdb/10-tcms-roles.sql).
