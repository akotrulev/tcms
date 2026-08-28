-- =============================================================================
-- V72 — the last result says WHICH ENVIRONMENT it was, so "Failed on Chrome,
--        passed on Firefox" is a sentence the grid can write
--
-- Source: plan 005 S1 (docs/plans/005-renders-backend-support.md), built in
--         batch B3 per 006-build-batches.md.
--
-- 005 S1 recorded this row as MIGRATION: NONE, and the contract's own wording is
-- where that came from — `TestCaseSummary.lastResult.configurationName` is
-- described as "already in the join that produces this object". It is: the join
-- is ExecutionFacts.recomputeExecutionSignals, and that join produces this
-- object INTO case_health. So serving the name needs a column to produce it into.
-- The plan was written without reading the statement; 006's batch B3 read it.
-- This is the one migration that batch claims.
--
-- -----------------------------------------------------------------------------
-- 1. WHY A COLUMN AND NOT A JOIN ON THE REQUEST PATH
-- -----------------------------------------------------------------------------
--
-- `repository` may not import `execution` (plan §3), which is the whole reason
-- V45 put the last result here in the first place. A second statement resolving
-- "the configuration of the newest recorded execution" alongside the read model
-- would be a SECOND answer to the question the read model already answers, and
-- the two can disagree: case_health lags by up to an hour by design, so a live
-- join could name a configuration for a case the read model reports as never
-- run, or vice versa — and the contract declares this field NEVER NULL when
-- lastResult is present. "Half a last result" is exactly the state V45's header
-- says one statement exists to make unrepresentable, and adding a fourth column
-- to that statement is what keeps it that way.
--
-- -----------------------------------------------------------------------------
-- 2. THE NAME, NOT THE ID — THE OPPOSITE CALL TO V45 §2, AND FOR ITS REASON
-- -----------------------------------------------------------------------------
--
-- V45 stores the status KIND rather than the status id, because case_status is
-- project-scoped and renameable and the kind is the fixed class the contract
-- declares. A configuration has no such class: the contract declares
-- `configurationName` as a string, and `PlanStatisticsView.ConfigurationCountsView`
-- already keys its rows by name for the same stated reason (a configuration is
-- unique by name within a project, so the name does not collapse two rows).
--
-- The cost is the one V45 named and accepted: renaming a configuration leaves
-- every stored name stale until the next recompute, which self-heals within the
-- hour. Storing the id instead would have meant a join in CaseSummaries on every
-- page of the biggest screen in the product, to recover a value the read model
-- could have carried.
--
-- -----------------------------------------------------------------------------
-- 3. NO BACKFILL, AND THE INVARIANT IS ASSERTED IN A TEST, NOT IN A CHECK
-- -----------------------------------------------------------------------------
--
-- V45 §4's argument, unchanged and still binding: Flyway runs as tcms_owner,
-- which is not a superuser and holds no BYPASSRLS, and case_health is under
-- FORCE ROW LEVEL SECURITY — so a statement here runs with app.current_company
-- unset, V4's nullif() turns that into a predicate matching nothing, and the
-- UPDATE would report a confident zero rows. The column arrives NULL on every
-- existing row and is filled by the next recompute, hourly by default and
-- immediately on the next RESULTS_CHANGED event.
--
-- Until then a row written before this migration has a last result with no
-- configuration name, which is the one window in which the contract's "never
-- null" is not true of the database. The read path resolves it rather than
-- serving a null into a required field — see CaseHealth#lastResult.
--
-- No CHECK for the same reason a backfill is impossible: every pre-existing row
-- would violate it the instant the column is added, and no statement here can
-- see those rows to fix them.
--
-- -----------------------------------------------------------------------------
-- 4. NO NEW TABLE, SO NO NEW POLICY. AND NO INDEX.
-- -----------------------------------------------------------------------------
--
-- case_health has been in V1's company_owned array since M0 and already carries
-- FORCE ROW LEVEL SECURITY; a column added to a policed table inherits it. The
-- policy count does not move — V37's note, and V45's.
--
-- No index: nothing filters or sorts by the configuration of a last result. It
-- is fetched by case_id, which is the primary key.
-- =============================================================================

ALTER TABLE case_health
    ADD COLUMN last_configuration_name TEXT;

COMMENT ON COLUMN case_health.last_configuration_name IS
    'The name of the configuration the case''s newest RECORDED execution ran under — joined off '
    'test_run through the execution''s run by the same statement that writes last_status_kind, so '
    'the four columns of a last result cannot disagree (V45). The NAME and not the id, because the '
    'contract declares a string and a configuration is unique by name within a project; a rename '
    'leaves this stale until the next recompute, which is the ordinary property of a read model. '
    'NULL on a case that has never been executed, and on a row written before V72 until the next '
    'recompute reaches it.';
