-- =============================================================================
-- V14 — a retried plan or run creation must not create a second one
--
-- V11 did this for `test_case` and its header ends with the honest cost:
--
--     "a second create endpoint (a suite, a plan) adds its own column rather than
--      reusing a mechanism. That is the right trade at one caller; the generalisation
--      can wait for the second, which will then have two real shapes to generalise
--      from instead of one imagined one."
--
-- This is that second, and it arrives as two at once. The generalisation is still
-- NOT taken, and the reason has changed from "wait for evidence" to a decision:
--
--   * A general `idempotent_request` table needs its own company_id, its own FORCE
--     RLS policy, an ERD §0.1 exception review, and — the part that actually bites —
--     a RETENTION rule for rows whose subject has been deleted. A column on the row
--     retains itself: the key lives exactly as long as the thing it created.
--   * It would also have to store the RESPONSE, because a general mechanism cannot
--     re-render one it does not understand. Storing a rendered response is a second
--     copy of client content in a table with different retention, and it goes stale
--     the moment the row it describes is edited. The column form re-renders from the
--     live row through the same assembler the GET uses, so a replay and a reload
--     cannot disagree.
--
-- Three columns is the price and it is paid knowingly. The shape is identical to
-- V11's in every respect, which is what makes it cheap to read.
--
-- WHY A RUN NEEDS THIS MORE THAN A CASE DOES
--
-- A duplicated case is one extra row someone deletes. Creating a run MATERIALISES
-- an execution per case in scope, so a duplicated run is a duplicated checklist:
-- the same tester is shown the same twenty cases twice, records half their results
-- against one run and half against the other, and both runs then report a pass rate
-- computed over a denominator nobody intended. Neither run is wrong on its own,
-- which is why nothing would flag it.
--
-- WHY NOT ON test_plan ONLY, WITH THE RUN KEYED ON (plan_id, name)
--
-- Considered and rejected: a run need not belong to a plan (`test_run.plan_id` is
-- nullable, for ad-hoc runs), and two runs of the same plan with the same name are
-- a legitimate thing to want — "Regression 4.3.0" run twice after a hotfix is the
-- normal case, not a mistake to refuse. There is no natural key here to dedupe on,
-- which is exactly the condition that makes an explicit idempotency key the right
-- mechanism rather than a workaround for a missing constraint.
--
-- SCOPE, PARTIALITY AND ORDERING — all three are V11's, for V11's reasons
--
--   * (company_id, project_id, key): the lookup must name exactly the constraint's
--     columns, or a key reused across two projects passes the lookup and then
--     violates the index — a validated request that 500s on insert.
--   * company_id LEADS, per ERD §0.1 point 3 and SchemaConventionTest.
--   * Partial on IS NOT NULL because the header is optional: every plan and run that
--     predates this migration carries NULL, NULLs are not comparable so an
--     unpartitioned unique index would permit them anyway, and it would index
--     millions of them for nothing.
--
-- NOT a foreign key, not validated, never read as data. The application caps its
-- length so a hostile 10 MB header is refused at the edge rather than indexed.
-- =============================================================================

ALTER TABLE test_plan ADD COLUMN created_idempotency_key TEXT;
ALTER TABLE test_run  ADD COLUMN created_idempotency_key TEXT;

COMMENT ON COLUMN test_plan.created_idempotency_key IS
    'The Idempotency-Key the create request carried, so a retry returns THIS plan instead of '
    'creating a second one. Opaque, client-supplied, write-once, never read as data. NULL for '
    'every plan created before V14 and for any caller that sends no key.';

COMMENT ON COLUMN test_run.created_idempotency_key IS
    'As test_plan.created_idempotency_key. It matters more here: a replayed run create would '
    'materialise a SECOND set of executions, splitting one tester''s results across two runs '
    'that each report a pass rate over a denominator nobody intended.';

CREATE UNIQUE INDEX test_plan_created_idempotency_key_idx
    ON test_plan (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX test_run_created_idempotency_key_idx
    ON test_run (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;

COMMENT ON INDEX test_plan_created_idempotency_key_idx IS
    'Serves the create endpoint''s replay lookup AND enforces it: the query names exactly these '
    'three columns, so a race between two retries of one intent fails on the constraint rather '
    'than inserting twice.';

COMMENT ON INDEX test_run_created_idempotency_key_idx IS
    'As test_plan_created_idempotency_key_idx.';
