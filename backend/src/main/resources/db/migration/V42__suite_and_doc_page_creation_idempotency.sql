-- =============================================================================
-- V42 — a retried suite or page creation must not create a second one
--
-- The fourth instance of a shape V11 (test_case) introduced and V14 (test_plan,
-- test_run) and V17 (work_item) repeated. Six columns across six tables now.
--
-- THE GENERALISATION IS STILL NOT TAKEN, and V17's header holds unchanged — the
-- part of it that actually decides the question has not weakened with the count:
-- a general `idempotent_request` table would have to store the RENDERED
-- RESPONSE, because a general mechanism cannot re-render one it does not
-- understand, and a stored response is a second copy of client content that goes
-- stale the moment the row it describes is edited. The column form re-renders
-- from the live row through the same assembler the GET uses, so a replay and a
-- reload cannot disagree. Nothing about that argument is a function of how many
-- tables carry the column; it is a function of what the alternative must store.
--
-- WHAT IS DIFFERENT HERE: A RECORDED REFUSAL IS BEING REVERSED
--
-- Unlike the other three, these two endpoints did not merely lack the header —
-- one of them REFUSED it, in writing. SuiteWriteService#create has said since
-- §29:
--
--     "No Idempotency-Key, unlike case creation, and the difference is not an
--      omission. A duplicate case is expensive to spot ... A duplicate folder is
--      visible in the tree the moment the create returns, next to its twin."
--
-- That argument was correct and it is not being called wrong. What changed is
-- its premise. It reasons from A PERSON LOOKING AT A SCREEN — "visible", "next
-- to its twin" — and the caller this migration exists for has no screen. §135's
-- tool surface makes `tcms_write_suite` and `tcms_write_doc_page` two of the
-- three MCP tools that are NOT buildable against today's contract, and both are
-- blocked on exactly this header. An agent that times out and retries has no way
-- to see the twin, and the correction loop the sentence assumes ("the user
-- notices and deletes one") does not exist on that path.
--
-- The sentence is doubly stale besides: §98 deleted the tree it names. A
-- duplicate in a flat list of two hundred named collections is not "visible the
-- moment the create returns" to anyone.
--
-- So the reason is superseded rather than deleted, struck through at its own
-- site per the working agreement, and the record of why is here.
--
-- WHAT A DUPLICATE COSTS ON THESE TWO, WHICH IS NOT THE SAME COST
--
-- V14 and V17 ranked the earlier ones. A duplicated SUITE is the mildest of the
-- six: nothing is filed in it yet, and the delete this same module ships removes
-- it. A duplicated DOC PAGE is the most expensive of the six and it is worth
-- being explicit about why. A page is a LINK TARGET — doc_link points at
-- (page_id, block_id), and ERD resolved question #18 makes a doc_block the
-- product's entire model of a requirement (there is deliberately no
-- `requirement` table). So two copies of one specification are two sets of block
-- ids, and traceability then splits across them: half the cases verify the
-- statement on one copy, half on the other, and the coverage query over either
-- one is silently short. Deleting the loser does not merge the links back — it
-- destroys them. That is the one duplicate in this family that a person cannot
-- clean up by pressing delete.
--
-- Both also burn a display key from the company counter (TS-n, DOC-n), which is
-- never recycled, so every duplicate leaves a permanent hole in the series.
--
-- THE PLAN-DOCUMENT PATH DELIBERATELY GETS NO KEY
--
-- DocPageWriteService#writeSubjectPage creates a page ON BEHALF OF a plan and
-- passes NULL here. It is not an endpoint, it has no header to carry, and it is
-- already deduplicated by a stronger constraint than this one: uq_doc_page_subject
-- permits exactly one page per subject, so a replayed plan-document create fails
-- on that index rather than making a twin. A NULL here is the honest encoding of
-- "this row was not created by a keyed request", which is also what every row
-- predating this migration means.
--
-- SCOPE, PARTIALITY AND ORDERING — V11's, V14's and V17's, for their reasons
--
--   * (company_id, project_id, key): the lookup must name exactly the
--     constraint's columns, or a key reused across two projects passes the
--     lookup and then violates the index — a validated request that 500s.
--   * company_id LEADS, per ERD §0.1 point 3 and SchemaConventionTest.
--   * Partial on IS NOT NULL because the header is optional: every suite and
--     page created before this migration carries NULL, NULLs are not comparable
--     so an unpartitioned unique index would permit them anyway, and it would
--     index every unkeyed row for nothing.
--
-- NOT CREATE INDEX CONCURRENTLY, for V17's reason: Flyway runs a migration in
-- one transaction and CONCURRENTLY cannot run inside one. Both tables are small
-- by construction — a project has tens of suites and tens of pages, against
-- thousands of cases — so the brief ACCESS EXCLUSIVE is measured in
-- milliseconds.
--
-- NO NEW TABLE AND NO NEW POLICY. Both tables are already company-owned and
-- already under FORCE ROW LEVEL SECURITY; the count stays at 62.
--
-- NOT a foreign key, not validated, never read as data. The application caps its
-- length so a hostile 10 MB header is refused at the edge rather than indexed.
-- =============================================================================

ALTER TABLE suite    ADD COLUMN created_idempotency_key TEXT;
ALTER TABLE doc_page ADD COLUMN created_idempotency_key TEXT;

COMMENT ON COLUMN suite.created_idempotency_key IS
    'The Idempotency-Key the create request carried, so a retry returns THIS suite instead of '
    'creating a second one. Opaque, client-supplied, write-once, and never read as data. NULL for '
    'every suite created before V42 and for any caller that sends no key.';

COMMENT ON COLUMN doc_page.created_idempotency_key IS
    'As suite.created_idempotency_key. It matters more here: a page is a link target, so two '
    'copies of one specification split traceability across two sets of block ids and deleting the '
    'loser destroys its links rather than merging them. NULL for every page created before V42, '
    'for any caller that sends no key, and for a page created on a plan''s behalf — that path is '
    'deduplicated by uq_doc_page_subject instead.';

CREATE UNIQUE INDEX suite_created_idempotency_key_idx
    ON suite (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;

CREATE UNIQUE INDEX doc_page_created_idempotency_key_idx
    ON doc_page (company_id, project_id, created_idempotency_key)
    WHERE created_idempotency_key IS NOT NULL;

COMMENT ON INDEX suite_created_idempotency_key_idx IS
    'Serves the create endpoint''s replay lookup AND enforces it: the query is keyed on exactly '
    'these three columns, so a race between two retries of one intent fails on the constraint '
    'rather than inserting twice. Scoped to the project because the endpoint is.';

COMMENT ON INDEX doc_page_created_idempotency_key_idx IS
    'As suite_created_idempotency_key_idx. Sits alongside uq_doc_page_subject, which dedupes the '
    'plan-document path this index cannot see because that path carries no key.';
