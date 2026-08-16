-- =============================================================================
-- V64 — the autosave buffer becomes one row per PERSON per case
--
-- D1 of docs/audits/2026-08-15-flow-review-and-improvements.md, and it reverses
-- a decision §128 shipped deliberately rather than by omission. That slice keyed
-- the buffer on the case alone because V1 did — `case_id UUID PRIMARY KEY` — and
-- recorded the cost in TestCaseDraft's header, on the wire as `updatedBy`, and
-- in a test (`anotherPersonsSaveClearsIt`) written so the shape could not change
-- quietly. This is that change, made loudly.
--
-- -----------------------------------------------------------------------------
-- WHAT WAS WRONG WITH ONE ROW PER CASE
-- -----------------------------------------------------------------------------
--
-- Two people editing one case shared one buffer, so:
--
--   * the last keystroke won across two editors, and the loser's only tell was
--     `updated_by` naming somebody else on a restore banner;
--   * a colleague's SAVE cleared your unsaved draft outright, because
--     CaseUpdateService cleared the row regardless of who saved;
--   * the editor was offered a restore it could not judge — "adopt this and you
--     publish their half-finished sentence under your name".
--
-- §128 answered all three by DISCLOSING them. Keying the row on the person
-- answers them instead: your buffer is yours, a colleague's save no longer
-- reaches it, and "which of the four drafts do I open" — the question that
-- header names as the reason not to do this — has one answer, which is yours.
-- The editor never had to ask it.
--
-- -----------------------------------------------------------------------------
-- THE ONE THING THIS MIGRATION DESTROYS, AND WHY IT MUST
-- -----------------------------------------------------------------------------
--
-- `updated_by` is NULLABLE in V1 and becomes half of the primary key, so a row
-- that names nobody cannot be carried across. Such a row is also unreachable by
-- construction after this change: a per-person read looks a draft up BY the
-- caller's id, and no caller has a null id. Keeping it would leave a row nothing
-- can ever serve, delete or overwrite, sitting in a table with no retention.
--
-- The DELETE below is therefore the honest form of what the re-key already does.
-- Its blast radius is small and bounded: no path in the product writes a null
-- `updated_by` — CaseDraftService has always passed the authenticated subject —
-- so this can only match rows predating §128's writer, of which there are none
-- in any deployment, plus anything hand-inserted by a fixture.
--
-- What is NOT destroyed: every attributed draft survives, keyed to the person
-- who typed it. A buffer is at most one autosave interval old by design, so even
-- a total loss here would cost two seconds of typing — which is the argument for
-- not adding ceremony around it, not an argument for being careless.
--
-- -----------------------------------------------------------------------------
-- WHY THE COLUMN IS RENAMED RATHER THAN LEFT ALONE
-- -----------------------------------------------------------------------------
--
-- `updated_by` answered "who typed this", which mattered only because the row
-- could be somebody else's. Under the new key it is "whose buffer is this", and
-- it is the identity the read is keyed on rather than an audit stamp beside the
-- content. `user_id` says that; `updated_by` would keep pointing at the fact the
-- re-key just removed, and the wire field it fed (`CaseDraft.updatedBy`) leaves
-- the contract in the same slice for the same reason — under a per-person key it
-- could only ever answer "you".
--
-- No FK to a person table, matching `updated_by` before it and every other
-- `created_by`/`updated_by` in this schema: an offboarded account's buffer stays
-- readable to nobody and blocks no deletion.
--
-- -----------------------------------------------------------------------------
-- NO company_id IN THE KEY, AND THAT IS THE CONVENTION RATHER THAN AN EXCEPTION
-- -----------------------------------------------------------------------------
--
-- ERD §0.1 point 3 wants company_id LEADING any composite index that contains
-- it. This key does not contain it, exactly as `case_tag (case_id, tag)` and
-- `case_classification (case_id, value_id)` do not: a key rooted in `case_id`
-- already determines the company, and SchemaConventionTest checks the ordering
-- of indexes that name the column rather than requiring every key to name it.
-- The column, the NOT NULL and the RLS policy on this table are all untouched.
-- =============================================================================

DELETE FROM test_case_draft WHERE updated_by IS NULL;

ALTER TABLE test_case_draft RENAME COLUMN updated_by TO user_id;
ALTER TABLE test_case_draft ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE test_case_draft DROP CONSTRAINT test_case_draft_pkey;
ALTER TABLE test_case_draft ADD CONSTRAINT test_case_draft_pkey
    PRIMARY KEY (case_id, user_id);

COMMENT ON TABLE test_case_draft IS
    'Autosave buffer, ONE ROW PER PERSON PER CASE since V64 (audit D1). NEVER versioned. An '
    'explicit save promotes the saver''s buffer into test_case + a new test_case_version and clears '
    'THAT ROW — this is what keeps history free of keystroke noise (plan §6j). A colleague''s save '
    'no longer clears your draft; it makes it stale, which CaseDraft.stale reports and the editor '
    'shows without refusing to restore.';

COMMENT ON COLUMN test_case_draft.user_id IS
    'Whose buffer this is, and half the primary key — not an audit stamp. Was updated_by, nullable, '
    'when the row was keyed on the case alone (V1 → V63) and the wire had to disclose that the '
    'draft on offer might be somebody else''s.';
