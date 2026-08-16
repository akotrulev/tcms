-- =============================================================================
-- V66 — POST /reports/generate stops queueing the same report twice
--
-- E2 of docs/audits/2026-08-15-flow-review-and-improvements.md, and the last of
-- the eleven creates in this API to take the header: "the one create of eleven
-- that takes none (found by the §205 conformance sweep as a header-declaration
-- defect); a retried 202 queues the report twice."
--
-- The §205 finding is worth restating because it is the reason this was invisible
-- rather than merely open: packages/mcp's `tcms_report.generate` DECLARED an
-- Idempotency-Key and sent one, against an endpoint that read none. The header
-- was discarded, the tool looked correct, and every retry the MCP transport made
-- produced a second job. §205 repaired the declaration — it stopped sending the
-- header, which was the honest fix while the server ignored it — and this
-- migration is the other half arriving.
--
-- -----------------------------------------------------------------------------
-- WHY A REPORT NEEDED THIS LEAST, AND WHY IT STILL NEEDS IT
-- -----------------------------------------------------------------------------
--
-- B4 (§232) closed the same gap on artifacts and deliberately left this one,
-- with a reason recorded in the audit's ledger: "a duplicated report is two
-- files, where a duplicated register row is a document nobody can tell from a
-- deliberate second one and a duplicated upload is a second copy of the bytes."
-- That ordering was right. What makes this one worth doing anyway:
--
--   * A report job is not free. It refreshes execution_fact for the whole
--     company inside its own transaction, then computes, then renders and writes
--     TWO artifacts to disk. A duplicate is a second full pass, not a second row.
--   * The caller most likely to retry is the one that cannot see the duplicate:
--     an MCP client or a CI job, neither of which has a job list on screen.
--   * A3 (§227) gave jobs a listing, so a duplicate is now visible in a place a
--     person reads — which turns an invisible waste into a confusing one.
--
-- -----------------------------------------------------------------------------
-- THE COLUMN IS THE SAME SHAPE AS THE OTHER NINE
-- -----------------------------------------------------------------------------
--
-- TEXT, nullable, write-once, never read as data — V14's design, repeated by
-- V42 (doc pages) and V59 (artifacts). The partial unique index is what enforces
-- it, and its three columns must be exactly the three the lookup predicates on,
-- or a query narrower than the constraint finds nothing and then violates the
-- index on insert: a validated request that 500s. V14's header carries the full
-- account of that defect.
--
-- company_id LEADS the index, per ERD §0.1 point 3.
--
-- Nothing is backfilled and nothing needs to be: a NULL key means "this request
-- carried no header", which is every job written before this migration and every
-- ad-hoc generate from a client that does not send one. The header stays
-- OPTIONAL — a caller who does not send it gets the old behaviour, which is a
-- new job per request.
-- =============================================================================

ALTER TABLE report_job ADD COLUMN created_idempotency_key TEXT;

COMMENT ON COLUMN report_job.created_idempotency_key IS
    'The Idempotency-Key of the request that queued this job, or NULL when none was sent (V66, '
    'audit E2). Opaque, write-once, never read as data — as on test_case, test_plan, test_run, '
    'suite, doc_page, work_item, ai_proposal and artifact. A replayed key answers 200 with THIS '
    'job rather than 202 with a second one, which matters more here than on a row: a report job '
    'refreshes execution_fact, computes, renders and writes two artifacts, so a duplicate is a '
    'second full pass rather than a second row.';

CREATE UNIQUE INDEX report_job_created_idempotency_key_idx
    ON report_job (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;

COMMENT ON INDEX report_job_created_idempotency_key_idx IS
    'Enforces the replay guarantee, and its three columns ARE the lookup''s three predicates — a '
    'lookup narrower than the constraint finds nothing and then violates the index on insert, '
    'which is a validated request answering 500. See V14 for the full account.';
