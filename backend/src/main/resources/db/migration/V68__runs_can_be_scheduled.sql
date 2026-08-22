-- =============================================================================
-- V68 — a run can be asked for on a cadence, not only by a person
--
-- Area C of docs/plans/2026-08-18-unplanned-gaps.md, stepped into
-- docs/plans/2026-08-18-gap-c-scheduled-runs.md. Handoff §247.
--
-- WHAT IS MISSING TODAY
--
-- A run is created by somebody clicking Create, or by CI posting a report.
-- A weekly regression cycle is therefore a recurring calendar entry in somebody
-- else's tool, and the run that should have happened on a Monday when that
-- person was on leave simply did not.
--
-- WHAT THIS TABLE IS
--
-- A STANDING REQUEST. It stores what a run should be ASKED for and when to ask,
-- and a poller re-asks it. It stores no results, no executions and no state
-- about a run in flight — the run it creates is an ordinary test_run and
-- everything downstream of creation is unchanged.
--
-- scope_request IS V61'S DOCUMENT, NOT A SECOND ENCODING
--
-- The same {planId, caseIds, suiteIds, tags} JSONB that V61 added to test_run,
-- written by the same code (RunScopeDocument.of, extracted out of
-- RunCreateService in §247 so both writers are one) and read by the same code
-- (RunScopeRequestView.parse). V61's header carries the argument for JSONB over
-- four columns or a join table and it applies unchanged: the request is read as
-- a whole and never joined against.
--
-- Three differences from V61's column, each deliberate:
--   * NOT NULL here. V61's is nullable because an INGESTED run had no request at
--     all. A schedule with no request could only ever create empty runs, which
--     RunEdit refuses anyway — refusing it once, here, beats refusing it nightly.
--   * It is EDITED. A run's request is written once; a standing request is
--     maintained. Hence updated_at and lock_version.
--   * It is RE-RESOLVED on every fire. "Everything tagged smoke" means today's
--     smoke cases. Materialisation-once is unharmed: it is a property of each
--     run, which is the level V61 states it at.
--
-- WHY next_fire_at IS STORED
--
-- The poller's query is one index range scan per company. The alternative reads
-- every schedule in the company on every tick and evaluates its cron in Java, to
-- discard nearly all of it — and makes one project's cost a function of how many
-- schedules every other project has. Stored state must be repaired, so exactly
-- one method writes it (RunScheduleService#recomputeNextFire) and create,
-- update, delete-and-replace and fire all go through it.
--
-- NO CATCH-UP, and the column shape says so: next_fire_at is recomputed FROM THE
-- MOMENT OF FIRING. A deployment down for a day does not produce a day of
-- backdated regression runs on restart — seven identical runs appearing at once,
-- each materialised against today's case versions, none of them meaning what its
-- timestamp says. The honest record of the gap is last_fired_at.
--
-- NO deleted_at. A schedule is configuration, not a record of work: deleting one
-- destroys nothing that happened, because the runs it created are ordinary runs
-- that outlive it. The audit trail keeps the delete.
--
-- created_by IS NOT NULL AND IT IS LOAD-BEARING
--
-- It is the authority the fire runs under. RunScheduleFiringService re-evaluates
-- that person's run:create grant on EVERY fire through the ordinary
-- ProjectService.writeGranted gate, so a schedule outlives neither the grant nor
-- the account. A nullable owner would be a standing, unrevokable right to create
-- runs in a project.
--
-- It is NOT the actor on what gets written. The run row and the audit row a fire
-- produces both carry a null actor, because at 03:00 nobody performed the act —
-- see AuditEvent.record's own @param ("null only for an unattended job") and the
-- actor_token_id header's refusal to say a human did what a pipeline did. This
-- is the product's FIRST such writer, which is why verification row §242 LG-5
-- was recorded as NOT DRIVABLE until this migration.
--
-- test_run.source GAINS A THIRD MEMBER
--
-- The CHECK below is replaced rather than left alone. TestRun#create's header
-- reads source as THE DOOR the request came through; a poller is a third door.
-- Leaving it at MANUAL is not neutral: RunListPage renders anything that is not
-- INGESTED as "manual", so every scheduled run would be labelled as a person's
-- act beside a created_by of nobody.
--
-- The constraint's name was not guessed. It is inline in V1__initial_schema.sql,
-- so Postgres named it <table>_<column>_check, and five migrations in this
-- directory have already dropped a constraint minted that way by that exact
-- name — V22 (review_state_check), V27 (report_artifact_format_check),
-- V32 (test_plan_status_check, case_status_kind_check) and
-- V39 (attachment_owner_type_check). A DROP CONSTRAINT naming something that
-- does not exist fails this migration on first boot in every environment at
-- once, so the five precedents are the evidence rather than a reading of V1.
--
-- company_id IS FIRST IN BOTH INDEXES, per ERD §0.1 point 3 and
-- SchemaConventionTest.companyIdLeadsCompositeIndexes. run_schedule is
-- company-owned and is NOT on §0.1's RLS exception list, so it takes
-- FORCE ROW LEVEL SECURITY and V4's nullif() predicate, exactly as written —
-- SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate compares the
-- text, and its policy count moves from 70 to 71.
--
-- NO CREATE INDEX CONCURRENTLY. V17, V42, V59 and V62 all refuse it on record,
-- Flyway runs a migration in one transaction, and this table is created empty in
-- this same migration — so the lock the concurrent form buys its way out of is
-- held over zero rows.
-- =============================================================================

CREATE TABLE run_schedule (
    id               UUID PRIMARY KEY,
    company_id       UUID NOT NULL REFERENCES company(id),
    project_id       UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name             TEXT NOT NULL,
    cron_expression  TEXT NOT NULL,
    zone_id          TEXT NOT NULL,
    scope_request    JSONB NOT NULL,
    configuration_id UUID REFERENCES configuration(id),
    assignee_id      UUID,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    next_fire_at     TIMESTAMPTZ,
    last_fired_at    TIMESTAMPTZ,
    last_run_id      UUID REFERENCES test_run(id),
    last_fire_error  TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       UUID NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       UUID,
    lock_version     BIGINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE run_schedule IS
    'A STANDING REQUEST for a run — what to ask for and when. The poller re-asks it and creates an '
    'ordinary test_run; nothing about execution changes. Deleting one destroys nothing that '
    'happened, because the runs it made outlive it.';

COMMENT ON COLUMN run_schedule.scope_request IS
    'V61''s document, unchanged — {planId, caseIds, suiteIds, tags}, normalised. NOT NULL here '
    'because a schedule that asked for nothing could only ever make empty runs. RE-RESOLVED on '
    'every fire: "everything tagged smoke" means today''s smoke cases. Materialisation-once is '
    'untouched — it is a property of each run.';

COMMENT ON COLUMN run_schedule.cron_expression IS
    'A six-field Spring cron expression, parsed by CronSchedule and by nothing else. Validated at '
    'write time, so an expression this server cannot read is a 400 rather than a stored schedule '
    'that silently never fires.';

COMMENT ON COLUMN run_schedule.zone_id IS
    'An IANA zone, validated with ZoneId.of at write time. Stored rather than defaulted in code '
    'because "every weekday at 07:00" is not a time without one, and a DST shift with nothing on '
    'the screen to explain it is the failure this column prevents.';

COMMENT ON COLUMN run_schedule.next_fire_at IS
    'When the poller will next ask. Recomputed FROM THE MOMENT OF FIRING, never from a missed slot '
    '— there is no catch-up, by decision. NULL means the expression yields nothing further, which '
    'CronExpression can legitimately answer, and the screen says "will not fire again".';

COMMENT ON COLUMN run_schedule.created_by IS
    'The AUTHORITY, re-evaluated on every fire: the poller runs the create through this person''s '
    'run:create grant, so a schedule outlives neither the grant nor the account. It is NOT the '
    'actor on what gets written — a fire''s run row and audit row both carry a null actor, because '
    'nobody performed the act.';

COMMENT ON COLUMN run_schedule.last_fire_error IS
    'Why the last attempt produced no run — the owner lost the grant, the project was archived, the '
    'resolved scope was empty, the approval gate refused. NULL after a fire that made a run. Read '
    'by the settings screen: a schedule that silently stops is the failure mode of this whole '
    'feature.';

CREATE INDEX run_schedule_project_idx ON run_schedule (company_id, project_id);

-- The poller's whole query. Partial on `enabled` because a disabled schedule is
-- never due, and it is the one predicate that never varies.
CREATE INDEX run_schedule_due_idx ON run_schedule (company_id, next_fire_at) WHERE enabled;

-- -----------------------------------------------------------------------------
-- RLS
-- -----------------------------------------------------------------------------

ALTER TABLE run_schedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_schedule FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON run_schedule USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);

-- -----------------------------------------------------------------------------
-- test_run.source gains SCHEDULED — see the header
-- -----------------------------------------------------------------------------

ALTER TABLE test_run DROP CONSTRAINT test_run_source_check;
ALTER TABLE test_run ADD CONSTRAINT test_run_source_check
    CHECK (source IN ('MANUAL', 'INGESTED', 'SCHEDULED'));

COMMENT ON COLUMN test_run.source IS
    'The DOOR the request came through, never the caller''s role — MANUAL (a person, including an '
    'Automation Service token through the public endpoint), INGESTED (a posted report), SCHEDULED '
    '(a run_schedule fired, V68). A SCHEDULED run has a null created_by and a null actor on its '
    'audit row.';
