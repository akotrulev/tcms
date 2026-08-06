-- =============================================================================
-- V33 — suites become flat
--
-- M2 of docs/plans/2026-08-05-organization-model-restatement.md (§98). A suite
-- is a named collection, not a folder in a tree: `suite.parent_id`, `suite.path`
-- and the GIST index over that path go.
--
-- No new table and no table dropped, so the RLS policy count stays at 61.
--
-- -----------------------------------------------------------------------------
-- THIS IS DESTRUCTIVE AND THE LOSS IS NOT RECOVERABLE FROM THIS DATABASE
-- -----------------------------------------------------------------------------
--
-- `parent_id` is the tree. Dropping it discards which suite each suite was
-- filed under, and nothing else records that — there is no audit event for a
-- suite's placement, unlike the plan-lifecycle reduction V32 performed, where
-- `plan.started` / `plan.closed` kept the history where history belongs.
--
-- This product has a real deployment (handoff §58), so that is a live concern
-- and not a theoretical one. It is accepted rather than mitigated: the model the
-- product now states has no place to put the information back, and a "former
-- parent" column preserved for sentiment would be a second organizing axis that
-- nothing reads and every future reader has to rule out.
--
-- What survives the change on purpose is in the next block.
--
-- -----------------------------------------------------------------------------
-- `execution_fact.suite_path` IS FROZEN HISTORY AND IS NOT TOUCHED HERE
-- -----------------------------------------------------------------------------
--
-- The fact log carries `suite_id` and `suite_path` per row, and §86 deliberately
-- FROZE both on conflict — the recompute may not re-file last quarter's results
-- under this quarter's taxonomy. §96 kept them frozen when the column went
-- nullable.
--
-- After this migration those stored paths describe a hierarchy that no longer
-- exists. **That is correct.** They say where the case was filed WHEN IT RAN,
-- which is the only thing a fact row is ever allowed to say. A suite-grouped
-- report of an old run must keep printing them.
--
-- So: this migration does not update, null, or reformat a single `execution_fact`
-- row, and the column keeps its `LTREE NOT NULL` type. The first person to
-- notice a dotted path in a report of a flat-suite product will want to "fix"
-- it; this comment is here to be found by that person.
--
-- -----------------------------------------------------------------------------
-- THE `ltree` EXTENSION STAYS — CHECKED, NOT ASSUMED
-- -----------------------------------------------------------------------------
--
-- V1 creates the extension and four columns use it. Three are untouched by §98:
--
--   doc_page.path                — the documentation space is a real tree
--   classification_value.path    — features and their sub-features are a real tree
--   execution_fact.suite_path    — frozen history, above
--
-- Only `suite.path` leaves. `DROP EXTENSION` would take the other three with it.
--
-- -----------------------------------------------------------------------------
-- WHAT IS DELIBERATELY LEFT IN PLACE
-- -----------------------------------------------------------------------------
--
--   suite.position  — sibling order within a parent, which now means order
--                     within the project. Nothing writes it and nothing reads
--                     it (the rail sorts by name), so it is neither made
--                     load-bearing nor dropped in a migration whose subject is
--                     the hierarchy. Dropping an unused column is its own change.
--
--   suite.key_seq   — still the key allocator's, and still what the old path
--                     label was derived from. Unaffected.
--
--   suite_case, suite_tag_rule (V31) — the other two membership arms, which are
--                     what a suite is made of now.
-- =============================================================================

-- The index first: it exists only for `path`, and dropping the column would take
-- it anyway. Naming it is what makes the removal reviewable.
DROP INDEX IF EXISTS suite_path_idx;

ALTER TABLE suite DROP COLUMN IF EXISTS path;

-- Last, because it is the self-reference: dropping it removes the FK to suite(id)
-- along with the column.
ALTER TABLE suite DROP COLUMN IF EXISTS parent_id;

COMMENT ON TABLE suite IS
    'A named collection of test cases. FLAT since V33 (§98): membership is three '
    'arms — filed (test_case.suite_id), added (suite_case), tagged '
    '(suite_tag_rule) — unioned and deduped. There is no parent and no path; '
    'execution_fact.suite_path is frozen history and describes the tree that '
    'existed when a case ran.';
