-- =============================================================================
-- V45 — the newest recorded result of a case, so the grid stops saying
--       "Never run" about cases that have run
--
-- Source: handoff §165, which measured the gap and argued it at the right
--         obstacle for the first time. See ExecutionFacts.recomputeExecutionSignals
--         for the statement these columns are written by.
--
-- `TestCaseSummary.lastResult` has been declared in openapi.yaml since M0 and
-- served by nothing. CaseGrid's "Last result" column renders a missing value as
-- "Never run" and CaseDetailPage renders it the same way, so EVERY CASE IN THE
-- PRODUCT reads "Never run" — including ones with recorded executions, and
-- including ones a person recorded a result on ten seconds earlier. That is not
-- an empty column: it is a confident falsehood, and it has been one since
-- `execution` shipped in §117.
--
-- The reason it stayed unserved was argued in four places as "the `execution`
-- module does not exist". It is 119 files. The LIVE obstacle is the module
-- boundary — `repository` may not import `execution` (plan §3, its own
-- package-info) — so this arrives the way suite_run_summary does: a read model
-- fed by events (§3 lesson 4), never a join on the request path.
--
-- -----------------------------------------------------------------------------
-- 1. WHY THESE COLUMNS GO ON case_health AND NOT INTO A case_last_result TABLE
-- -----------------------------------------------------------------------------
--
-- A separate table would agree with this one on everything that identifies a
-- row — same primary key (case_id), same population (every live case), same
-- writer (ExecutionFacts), same refresh trigger (the RESULTS_CHANGED outbox
-- event plus the hourly schedule), same company. What it would NOT agree with
-- is the answer, and that is the whole argument:
--
--     never_executed  = the newest recorded execution does not exist
--     long_unexecuted = the newest recorded execution is older than the cutoff
--     last_executed_at = the newest recorded execution
--
-- All three are ONE FACT. They are on the same grid row — the health chips and
-- the Last result cell sit inches apart in CaseGrid — so two writers computing
-- that fact separately is two answers to "has this case ever run" rendered side
-- by side, each plausible, with nothing on the screen saying which is right.
-- That is the failure this repository has paid for repeatedly (§52's
-- entries/runs, §81's review cards, §98's suite arms), and here it is avoidable
-- for free: ONE statement computes the fact, stores it, and derives both
-- booleans from it. Disagreement is not merely unlikely, it is unrepresentable.
--
-- The cost is that a table named case_health now carries three columns that are
-- not health signals. It already carried two (computed_at, incomplete), the
-- entity's own header says what it is — the per-case derived read model this
-- module owns — and a name that under-describes its contents is a smaller
-- problem than two tables that can contradict each other. The alternative that
-- WOULD have been right is renaming the table; that is a bigger change than the
-- gap it would tidy, and it is not this slice.
--
-- Second, smaller consequence, and it is the read path's: CaseSummaries already
-- loads these rows for the health chips, in one bulk query for the whole page.
-- The last result costs it no query at all. A separate table would have made it
-- a fifth.
--
-- -----------------------------------------------------------------------------
-- 2. THE KIND, NOT THE STATUS ID — AND NOT THE STATUS NAME
-- -----------------------------------------------------------------------------
--
-- case_status is PROJECT-scoped and renameable (plan §6d): a client may call
-- their blocked status "Environment down". StatusKind is the fixed aggregation
-- class behind those names and is what the contract's `lastResult.statusKind`
-- declares, so it is what gets stored.
--
-- Storing status_id instead would be one more join in CaseSummaries on every
-- page, to recover a value the read model could have carried. What that costs
-- in exchange is a staleness window that the id would not have: re-KINDING a
-- status (Passed -> Blocked, which the vocabulary editor permits) leaves every
-- stored kind wrong until the next recompute. That is the ordinary property of
-- a read model, it self-heals within the hour, and renaming — the thing clients
-- actually do — moves nothing here at all.
--
-- TEXT rather than an enum type, matching case_status.kind itself, which is TEXT
-- with a CHECK. No CHECK is added here: this column is not authored, it is
-- copied from a column that already has one, and a second constraint would be a
-- second place to remember WIP when V32 added it.
--
-- -----------------------------------------------------------------------------
-- 3. release_version IS DENORMALIZED ALREADY, SO IT IS COPIED AND NOT JOINED
-- -----------------------------------------------------------------------------
--
-- test_execution.release_version has carried the run's release since V1, for the
-- reason V1 states: "Nearly every interesting query filters executions by
-- release; joining back to the run on each would be the join in every
-- slow-query report (plan §6q)." The statement that fills these columns reads
-- the winning execution row and takes the value off it — no test_run join, which
-- is what keeps the cheap statement cheap (see ExecutionFacts' note on why the
-- two statements in that file are split).
--
-- Nullable, and it is the one member of the contract's lastResult object that is
-- nullable when the object is present: a run outside a release has none. The
-- object as a whole is nullable and its members are required, so "half a last
-- result" is not a state this can produce — which is exactly why all three
-- columns are written by one statement from one row.
--
-- -----------------------------------------------------------------------------
-- 4. NO BACKFILL, AND NO CHECK CONSTRAINT ON THE INVARIANT
-- -----------------------------------------------------------------------------
--
-- The columns arrive NULL on every existing row and stay that way until the
-- recompute next runs — hourly by default, and immediately on the next
-- RESULTS_CHANGED event. Until then a case reads "Never run", which is what it
-- read before this migration: the deployment is no worse off for a window it
-- was permanently inside.
--
-- A backfill here would not work anyway, and the reason is worth recording
-- because it will tempt the next author of a data migration. Flyway runs as
-- tcms_owner, which is NOT a superuser and does NOT hold BYPASSRLS, and
-- case_health is under FORCE ROW LEVEL SECURITY — so a statement here runs with
-- app.current_company unset, V4's nullif() turns that into a predicate matching
-- nothing, and the UPDATE would report a confident zero rows. The job owns the
-- backfill because the job is the only thing that opens a company.
--
-- For the same reason there is no CHECK asserting the invariant this migration
-- is built on (never_executed <=> last_executed_at IS NULL): every pre-existing
-- row violates it the instant these columns are added, and no statement here can
-- see those rows to fix them. It is asserted where it can be — in
-- CaseHealthRecomputeTest, over rows a recompute has actually written.
--
-- -----------------------------------------------------------------------------
-- 5. NO NEW TABLE, SO NO NEW POLICY. AND NO INDEX.
-- -----------------------------------------------------------------------------
--
-- case_health has been in V1's company_owned array since M0 and already carries
-- FORCE ROW LEVEL SECURITY; a column added to a policed table inherits the
-- policy. The policy count does not move. V37 made the same note when it added
-- undeclared_automation.
--
-- V37 added indexes because its columns are filtered and faceted on. These are
-- not: nothing filters by last result and nothing sorts by it — CaseGrid marks
-- the column `sortable: false` and CaseSort says why (it would need a column
-- this module's ORDER BY cannot reach, which is no longer true and is still not
-- a feature anybody asked for). They are fetched by case_id, which is the
-- primary key. An index for a predicate nobody writes is the mirror of V37's
-- point, not an application of it.
-- =============================================================================

ALTER TABLE case_health
    ADD COLUMN last_status_kind     TEXT,
    ADD COLUMN last_executed_at     TIMESTAMPTZ,
    ADD COLUMN last_release_version TEXT;

COMMENT ON COLUMN case_health.last_status_kind IS
    'The StatusKind of the case''s newest RECORDED execution — case_status.kind, not the '
    'project''s own name for it, because the name is renameable and the kind is the fixed '
    'aggregation class the contract declares. NULL exactly when the case has never been executed, '
    'which is the same fact never_executed asserts: both are derived from one row by one statement '
    'so they cannot disagree.';

COMMENT ON COLUMN case_health.last_executed_at IS
    'When that execution was recorded. Recorded means status_id IS NOT NULL — creating a run '
    'writes one empty execution per case in scope (§42), so a case in a freshly planned run has an '
    'execution row and has never been executed. This is the same fact long_unexecuted is derived '
    'from, one cutoff later.';

COMMENT ON COLUMN case_health.last_release_version IS
    'The release that execution belonged to, copied off test_execution.release_version rather than '
    'joined back through test_run (V1: that join is the one in every slow-query report). NULL for a '
    'run outside a release, which is a real state and the only member of the contract''s lastResult '
    'object that may be null while the object is present.';
