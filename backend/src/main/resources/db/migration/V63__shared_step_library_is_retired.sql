-- =============================================================================
-- V63 — the shared-step library is retired, and this migration destroys nothing
--
-- C4 of docs/audits/2026-08-15-flow-review-and-improvements.md, and the one C
-- item that deletes a feature. §94 declared the library vestigial beside the
-- step-suggestion corpus; §239 finishes the argument and removes it from the
-- contract, the API, the SPA and the packages surface.
--
-- THIS FILE IS COMMENTS. It drops nothing, updates nothing and adds no column.
-- That is the whole design of the slice, and the reasoning is below.
--
-- -----------------------------------------------------------------------------
-- WHY THERE IS NO BACKFILL, WHEN THE DECISION SAID "MIGRATE REFS TO COPIED TEXT"
-- -----------------------------------------------------------------------------
--
-- The audit's decision reads: "migrate `sharedStepId` refs to copied text".
-- Reading the code before writing the UPDATE turned that into a no-op, and the
-- reason is worth stating in full, because the obvious backfill would have made
-- the product WORSE.
--
-- shared_step.body was resolved by exactly ONE reader in the entire backend:
-- SharedStepView, the library's own list endpoint. Grep it before disbelieving
-- this. The case read path never resolved it — TestStepView served the raw id
-- and said so in its own javadoc — so no case detail, no execution screen, no
-- report, no export and no test_case_version.snapshot has ever rendered a
-- shared step's body. The SPA's picker wrote the entry's TITLE into
-- test_step.action at insert time and set shared_step_id beside it as a marker.
--
-- So a reference resolved to NOTHING, and the step's visible text is, and always
-- was, test_step.action. There is no content behind the pointer to rescue:
--
--   * Copying the TITLE is what already happened, at authoring time. An UPDATE
--     doing it again would rewrite `action` with a value it already holds — or,
--     worse, overwrite an action the author has since edited by hand with the
--     library title they edited it away from.
--
--   * Copying the BODY would insert into live test cases a block of text that
--     has never once been displayed to anybody. A migration that adds steps to
--     authored test cases is a content change nobody asked for, made by a
--     release note.
--
-- The correct backfill of a pointer that resolved to nothing is no backfill.
-- Retiring the feature therefore costs no case any text at all; what it costs is
-- a "Shared" chip whose tooltip ("Editing the library entry changes every case
-- using it") was false on the day it was written.
--
-- -----------------------------------------------------------------------------
-- WHAT IS OWED TO A LATER SLICE — the CONTRACT half of expand–contract
-- -----------------------------------------------------------------------------
--
-- data-migrations is explicit that no column is dropped in the release that
-- stops writing to it. §239 is the release that stops writing. So this migration
-- deliberately leaves BOTH of these in place, fully populated:
--
--   * test_step.shared_step_id     -- every reference any case ever made
--   * shared_step                  -- every entry anybody ever authored
--
-- A later slice owes:
--
--   ALTER TABLE test_step DROP COLUMN shared_step_id;
--   DROP TABLE shared_step;
--
-- and it must be its own decision rather than a tidy-up appended to this one,
-- because THAT statement is the destructive one: shared_step.body holds authored
-- content that this slice has just made unreachable but has not deleted. Nobody
-- asked to lose it. Whoever writes that migration should say whether the entries
-- are exported first, and should note that the drop also retires
-- shared_step.created_idempotency_key (V59, §232 B4) — the eighth of the eight
-- creates that carry the key, and the one that is now dead. Java's cap
-- declaration went with SharedStepService in this slice, so IdempotencyKeyCapTest
-- reads NINE, not ten.
--
-- Nothing in this database is now reachable from Java: hibernate.ddl-auto is
-- `validate`, which requires every MAPPED thing to exist and says nothing about
-- a column no entity mentions. The FK from test_step to shared_step keeps both
-- rows consistent until the drop, at no cost, because nothing writes either.
--
-- -----------------------------------------------------------------------------
-- WHAT ABOUT test_case_version.snapshot, WHICH IS HISTORY?
-- -----------------------------------------------------------------------------
--
-- Checked before planning any of this, because if snapshots embedded the ID
-- rather than resolved text, retiring the table would break the reads that
-- render old versions and would have forced the opposite decision.
--
-- A snapshot is a serialized TestCaseDetailView, so it embeds BOTH the step's
-- resolved `action` text AND its raw sharedStepId. The text is complete: a
-- snapshot rendered today reads exactly as it did before this slice. The id is
-- decoration, and old snapshots keep carrying it forever, which is correct —
-- test_case_version is append-only and a past claim does not get rewritten
-- because the product changed.
--
-- The one thing a future reader must know: TestStepView no longer declares
-- `sharedStepId`, so a diff endpoint that deserializes an OLD snapshot back into
-- that record meets an undeclared property. Nothing does this today —
-- TestCaseVersion.getSnapshot() has no reader in src/main at all, which is why
-- this could not break anything — but the endpoint that reads it must be built
-- tolerant of unknown properties rather than strict.
-- =============================================================================

COMMENT ON TABLE shared_step IS
    'RETIRED §239 (audit C4). No writer, no reader, and unreachable from Java — the entity, the '
    'repository, the service and the controller were deleted, and GET/POST /shared-steps left the '
    'contract. Kept rather than dropped because it holds authored content: the rows are the entries '
    'somebody wrote. The DROP is owed to a later slice, deliberately not appended here. Why the '
    'feature went: nothing ever resolved shared_step.body outside this table''s own list endpoint, '
    'so a case referencing an entry showed the entry TITLE that the picker had copied into '
    'test_step.action, and editing the entry propagated to nothing while the UI said it propagated '
    'to every case. Step reuse is GET /step-suggestions, derived from the project''s own cases.';

COMMENT ON COLUMN test_step.shared_step_id IS
    'RETIRED §239 (audit C4). No longer mapped by TestStep and no longer on the contract''s TestStep '
    'or TestStepInput. Left populated and NOT nulled: the reference resolved to nothing on every '
    'read path, so the step''s text is action and always was, and there is no content to migrate '
    'out of here. DROP COLUMN is owed to a later slice, per expand-contract. A case saved after '
    '§239 writes NULL here, because the field left the payload — so this column drains naturally '
    'rather than by an UPDATE.';
