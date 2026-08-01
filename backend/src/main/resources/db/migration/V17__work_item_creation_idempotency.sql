-- =============================================================================
-- V17 — a retried task creation must not create a second card
--
-- The third instance of a shape V11 (test_case) introduced and V14 (test_plan,
-- test_run) repeated. V14's header set the rule for exactly this moment:
--
--     "a second create endpoint adds its own column rather than reusing a
--      mechanism ... the generalisation can wait for the second, which will then
--      have two real shapes to generalise from instead of one imagined one."
--
-- Four columns now. The generalisation is STILL not taken, and the reasons V14
-- recorded have not changed with the count: a general `idempotent_request` table
-- needs its own company_id, its own FORCE RLS policy, an ERD §0.1 exception
-- review, a retention rule for rows whose subject was deleted, and — the part
-- that actually decides it — it would have to store the RENDERED RESPONSE, since
-- a general mechanism cannot re-render one it does not understand. A stored
-- response is a second copy of client content that goes stale the moment the row
-- it describes is edited. The column form re-renders from the live row through
-- the same assembler the GET uses, so a replay and a reload cannot disagree.
--
-- WHY THIS ONE WAS LEFT OUT OF §53, AND WHY IT ARRIVES WITH THE CONTRACT CHANGE
--
-- §53 shipped POST /work-items WITHOUT declaring Idempotency-Key, deliberately,
-- and wrote the reason into openapi.yaml: declaring the header with no column
-- behind it is §50's defect in its worse direction. There, the parameter sits in
-- the reviewed spec, is generated into schema.d.ts, and is offered by
-- autocomplete — while the server ignores it. Every artifact agrees with the
-- caller and the behaviour does not. A client would believe its retries were
-- deduplicated when every one of them created another card, which is precisely
-- the failure the header exists to prevent, arrived at by trusting the contract.
--
-- So the column and the declaration land together. That ordering is the point.
--
-- WHAT A DUPLICATE COSTS HERE, COMPARED WITH A CASE AND WITH A RUN
--
-- V14 ranked these: a duplicated case is one extra row somebody deletes; a
-- duplicated RUN materialises a second checklist and splits one tester's results
-- across two runs whose pass rates are each computed over a denominator nobody
-- intended. A duplicated TASK sits between them, and closer to the case — except
-- that the board is the one screen where duplicates are actively harmful rather
-- than untidy: two identical cards in To do are two people picking up the same
-- work, and the second one finds it already done. A board is a coordination
-- surface, and a coordination surface that lies about how much work there is
-- gets abandoned (plan §6l's whole thesis about trust).
--
-- SCOPE, PARTIALITY AND ORDERING — V11's and V14's, for their reasons
--
--   * (company_id, project_id, key): the lookup must name exactly the
--     constraint's columns, or a key reused across two projects passes the
--     lookup and then violates the index — a validated request that 500s.
--   * company_id LEADS, per ERD §0.1 point 3 and SchemaConventionTest.
--   * Partial on IS NOT NULL because the header is optional: every task created
--     before this migration carries NULL, NULLs are not comparable so an
--     unpartitioned unique index would permit them anyway, and it would index
--     every unkeyed row for nothing.
--
-- The project_id in the index comes from the REQUEST BODY on this endpoint
-- rather than from a path segment — POST /work-items is instance-scoped, for
-- §6l's eventual cross-project board. That changes where the handler reads it
-- and nothing about the constraint.
--
-- NOT CREATE INDEX CONCURRENTLY, and that is a choice rather than an oversight:
-- Flyway runs a migration in one transaction and CONCURRENTLY cannot run inside
-- one, so taking it would mean splitting this file and disabling transactional
-- migration for it. `work_item` holds hand-typed tasks — the smallest table in
-- the schema by construction — so the brief ACCESS EXCLUSIVE is measured in
-- milliseconds. V11 and V14 made the same call on much larger tables; the day
-- one of them is genuinely hot, that is a decision for all four at once.
--
-- NOT a foreign key, not validated, never read as data. The application caps its
-- length so a hostile 10 MB header is refused at the edge rather than indexed.
-- =============================================================================

ALTER TABLE work_item ADD COLUMN created_idempotency_key TEXT;

COMMENT ON COLUMN work_item.created_idempotency_key IS
    'The Idempotency-Key the create request carried, so a retry returns THIS task instead of '
    'creating a second card. Opaque, client-supplied, write-once, never read as data. NULL for '
    'every task created before V17 and for any caller that sends no key.';

CREATE UNIQUE INDEX work_item_created_idempotency_key_idx
    ON work_item (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;

COMMENT ON INDEX work_item_created_idempotency_key_idx IS
    'Serves the create endpoint''s replay lookup AND enforces it: the query names exactly these '
    'three columns, so a race between two retries of one intent fails on the constraint rather '
    'than inserting twice.';
