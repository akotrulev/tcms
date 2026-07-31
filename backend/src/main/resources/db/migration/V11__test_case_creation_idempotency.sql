-- =============================================================================
-- V11 — a retried case creation must not create a second case
--
-- The SPA has sent `Idempotency-Key` on POST /projects/{id}/cases since frontend
-- Phase 1, one key per mounted editor rather than per attempt, and the comment on
-- `useCreateCase` explains why: pressing Save again after a timeout must be
-- recognised as the same intent. The MSW mock honours it. Nothing on the server
-- ever read it.
--
-- Accepting a header and ignoring it is the same failure as accepting a field and
-- dropping it, which slice 7 refused for the six unsupported inputs on
-- TestCaseInput — and it is worse here, because the mock DOES dedupe: mock mode was
-- proving a property live mode does not have. Same class as the If-Match-in-the-body
-- divergence that slice found, one endpoint later.
--
-- WHY A COLUMN ON test_case AND NOT A GENERAL REQUEST TABLE
--
-- The key identifies the creation of exactly one row, so it lives on that row. A
-- general `idempotent_request` table would need its own company_id, its own FORCE
-- RLS policy, an entry in the ERD §0.1 exception review, and a retention decision
-- for rows whose subject may have been deleted. Here retention is automatic — the
-- key lives exactly as long as the case it created — and there is no new policy to
-- get wrong.
--
-- The honest cost, recorded rather than hidden: a second create endpoint (a suite, a
-- plan) adds its own column rather than reusing a mechanism. That is the right trade
-- at one caller; the generalisation can wait for the second, which will then have two
-- real shapes to generalise from instead of one imagined one.
--
-- WHY THE UNIQUE SCOPE IS (company_id, project_id, key) AND NOT (company_id, key)
--
-- The lookup must match the constraint exactly. A company-wide constraint with a
-- per-project lookup means a key reused across two projects passes the lookup and
-- then violates the index — a validated request that 500s on insert, which is the
-- exact failure test_case_automation_key_idx's own header warns about. The endpoint
-- is project-scoped, so the key is too.
--
-- company_id LEADS, per ERD §0.1 point 3 and SchemaConventionTest.
--
-- Partial on `created_idempotency_key IS NOT NULL` because the header is optional:
-- every case created before this migration, and every one created by a caller that
-- sends no key, carries NULL — and NULLs are not comparable, so an unpartitioned
-- unique index would permit them anyway while indexing millions of them for nothing.
--
-- NOT a foreign key, not validated, never read as data. It is an opaque client
-- string compared for equality; the application caps its length rather than the
-- column, so a hostile 10 MB header is rejected at the edge instead of stored.
-- =============================================================================

ALTER TABLE test_case ADD COLUMN created_idempotency_key TEXT;

COMMENT ON COLUMN test_case.created_idempotency_key IS
    'The Idempotency-Key the create request carried, so a retry returns THIS case instead of '
    'creating a second one. Opaque, client-supplied, write-once, and never read as data. NULL for '
    'every case created before V11 and for any caller that sends no key.';

CREATE UNIQUE INDEX test_case_created_idempotency_key_idx
    ON test_case (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;

COMMENT ON INDEX test_case_created_idempotency_key_idx IS
    'Serves the create endpoint''s replay lookup AND enforces it: the query is keyed on exactly '
    'these three columns, so a race between two retries of one intent fails on the constraint '
    'rather than inserting twice. Scoped to the project because the endpoint is — a company-wide '
    'constraint with a project-scoped lookup is a validated request that 500s on insert.';
