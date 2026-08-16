-- =============================================================================
-- V61 — what a run was ASKED for, beside what it materialised
--
-- B7 of docs/audits/2026-08-15-flow-review-and-improvements.md.
--
-- WHAT IS MISSING TODAY
--
-- `POST /runs` takes four arms — a plan, case ids, suite ids, tags — resolves
-- them into a case set, and writes one `test_execution` per case. `test_run`
-- keeps `plan_id` and NOTHING ELSE about the request. So:
--
--   * "why is this case in this run" has no answer. The checklist is a list of
--     executions; whether TC-41 is there because somebody named it, because it
--     was filed in a suite that was named, or because it carried `smoke` on the
--     day the run was made is unrecoverable.
--   * a clone re-runs the MATERIALISED set, which is correct (see below) and
--     silently omits every case added to those suites since. The clone dialog
--     cannot even offer the alternative, because the arms are gone.
--
-- THIS IS PROVENANCE, NOT A SECOND SOURCE OF TRUTH
--
-- Materialisation-once stays the law: a run's contents are its `test_execution`
-- rows, pinned to the case version they were created against, and nothing
-- re-resolves them. That is what makes a run a record of a checklist rather than
-- a query whose answer moves under the tester. This column changes none of it —
-- it records what was asked so a reader can see the difference, and so a NEW run
-- can be asked for on the same terms.
--
-- The distinction matters for reading the column too: it is the request as
-- normalised (de-duplicated, trimmed), NOT the case set. A suite named here may
-- since have been deleted; a tag may now match nothing. That is the point.
--
-- JSONB AND NOT FOUR COLUMNS / A JOIN TABLE
--
-- `plan_scope_item` is the other shape and it is the right one for a PLAN, whose
-- scope is edited, resolved and counted continuously. A run's request is written
-- once, never edited, read as a whole, and never joined against — it is a
-- document, and the two candidates for storing a document are a JSONB column and
-- three more tables with an insert order. `report_job.parameters` (`V27`) and
-- `test_plan.entry_criteria` are the same call in this schema already.
--
-- NULLABLE, and null means "not recorded" rather than "no scope": every run
-- created before this migration carries null, and so does one created by
-- ingestion, which materialises from a report rather than from a request. A
-- client must render the absence as unknown and not as an empty scope.
--
-- NO CHECK CONSTRAINT on the document's shape. The keys are the wire's
-- (`planId`, `caseIds`, `suiteIds`, `tags`) and the writer is one service; a
-- CHECK would be a second, weaker copy of `RunEdit`'s validation, and Postgres
-- cannot express "these ids exist" anyway.
-- =============================================================================

ALTER TABLE test_run ADD COLUMN scope_request JSONB;

COMMENT ON COLUMN test_run.scope_request IS
    'What this run was ASKED for — {planId, caseIds, suiteIds, tags}, normalised, as sent. '
    'Provenance only: the run''s contents are its test_execution rows and nothing re-resolves '
    'them. NULL for every run created before V61 and for every run opened by ingestion, where '
    'there was no request. A reader must show NULL as "not recorded", never as "no scope".';
