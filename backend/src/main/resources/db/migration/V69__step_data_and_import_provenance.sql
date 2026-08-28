-- =============================================================================
-- V69 — a step's block arguments become writable, and a case remembers the
--        file it was imported from
--
-- Initiative 001 (docs/plans/001-unified-step-model/), task T1. Handoff §255.
--
-- WHAT CHANGES, AND WHAT DOES NOT
--
-- Nothing here adds a table and nothing here drops a column. test_step.docstring
-- and test_step.datatable have existed since V1 and have never held a value:
-- neither had a field on the contract, so nothing could set one. §255 gives them
-- both, which is what makes a .feature round trip lossless and what makes a
-- Gherkin case EXECUTABLE PER STEP rather than a blob somebody reads.
--
-- The one thing V1 did not say is that the three data spellings are
-- alternatives, so this adds the CHECK that says it.
--
-- test_case.gherkin_source IS DELIBERATELY LEFT ALONE. It stops being written by
-- any path in this initiative and is read by three things — the SPA's
-- not-yet-migrated fallback, the migrate-gherkin-source backfill (T5), and V2's
-- search_vector generated column. Dropping it needs the backfill run on every
-- instance first, and needs search_vector re-created without it; both belong to
-- initiative 002-, which is where the column and test_step.shared_step_id and
-- case_condition.inline_body go together.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- test_step — one step, at most one data spelling
--
-- ADDED WITH VALIDATION, not NOT VALID. The premise is that the constraint is
-- trivially true of every stored row: docstring and datatable have never been
-- written anywhere, so num_nonnulls() is at most 1 for test_data alone. The scan
-- is what PROVES that premise rather than assuming it, and the tables are small
-- enough per instance that proving it costs nothing. num_nonnulls exists from
-- PostgreSQL 9.6; this instance is 17.
-- ---------------------------------------------------------------------------
ALTER TABLE test_step
    ADD CONSTRAINT ck_test_step_one_data
    CHECK (num_nonnulls(test_data, docstring, datatable) <= 1);

COMMENT ON CONSTRAINT ck_test_step_one_data ON test_step IS
    'One step carries structured test data, OR a Gherkin doc string, OR a Gherkin table — never '
    'two. A step holding two leaves the execution screen to choose which one the tester follows, '
    'and whichever it picks is wrong for somebody. THE BACKSTOP, NOT THE ANSWER: CaseEdit rejects '
    'the same payload with a field error on steps naming the step number, because a constraint '
    'violation reaching a client is a 500 where a form error belongs.';

COMMENT ON COLUMN test_step.docstring IS
    'Gherkin''s """ block argument — a multi-line string hanging off ONE step. Declared in V1 and '
    'first written in §255, which is also when it first had a contract field (TestStep.docString) '
    'to be served on. Before that it was a column nothing could fill: a .feature file with a doc '
    'string could not round-trip through this product.';

COMMENT ON COLUMN test_step.datatable IS
    'Gherkin''s |-table argument as a JSON array of arrays of strings — [["name","qty"],'
    '["widget","2"]]. Rows MAY BE RAGGED: nothing here requires a rectangle, the parser does not '
    'produce a non-rectangular table, and a reader that indexes blindly across rows is the thing '
    'that breaks. Not normalised into rows and cells because it is read as a whole, written as a '
    'whole, and never joined against — the same argument V61 makes for test_run.scope_request.';

-- ---------------------------------------------------------------------------
-- test_case — where an imported case came from
--
-- All three are nullable and all three are written together by the import (T6)
-- or by nothing at all. NO CHECK ENFORCES THE TRIPLE, deliberately: the backfill
-- (T5) sets none of them, and a future partial import must not be refused by DDL
-- for a provenance field it happens not to know.
-- ---------------------------------------------------------------------------
ALTER TABLE test_case
    ADD COLUMN imported_source TEXT,
    ADD COLUMN imported_path   TEXT,
    ADD COLUMN imported_line   INT;

COMMENT ON COLUMN test_case.imported_source IS
    'The scenario''s text at last import, verbatim from its Scenario: line to its last step. NOT '
    'provenance decoration — it is READ: a re-import compares the file''s scenario against it and '
    'answers SKIP "unchanged since import", so an unchanged folder re-imported mints no version '
    'and writes nothing. Without it every re-import rewrites every case and the version history '
    'fills with entries whose diff is empty.';

COMMENT ON COLUMN test_case.imported_path IS
    'The path the caller supplied for the file this case came from. PROVENANCE, NOT A LOCATOR: the '
    'file lives in somebody''s automation repository and this instance never held it, so nothing '
    'resolves this to content. Bounded at 1024 chars in Java rather than here — a longer path is a '
    'caller mistake worth a field error, not a truncation nobody sees.';

COMMENT ON COLUMN test_case.imported_line IS
    '1-based line of the Scenario: keyword in imported_path, so a reader can answer "why does this '
    'case say that" by opening the file at the right place. Stale the moment the file is edited, '
    'which is why it is recorded beside imported_source rather than trusted on its own.';

-- ---------------------------------------------------------------------------
-- condition — the import's key for a Background step
--
-- The column arrives here and its ENTITY FIELD arrives with its writer (T6),
-- which is this repository's standing rule: a field mapped before its writer is
-- a field nobody notices has gone stale.
-- ---------------------------------------------------------------------------
ALTER TABLE condition
    ADD COLUMN imported_key TEXT;

COMMENT ON COLUMN condition.imported_key IS
    '"bg:" plus the first 16 hex of SHA-256 over one normalised Background step (keyword + text + '
    'block lines, whitespace collapsed, \n line endings). A TEXT HASH, NOT A POSITIONAL KEY, and '
    'that is the whole design: a reordered or partly rewritten Background re-mints nothing, and '
    'the same step text in two feature files is ONE library entry for the project. Non-null also '
    'means "the import owns this entry" — a re-import may replace the PRE attachments whose '
    'condition has one, and may never touch a human-attached PRE or any POST.';

-- Project-scoped, because a Background belongs to one automation repository.
-- PARTIAL ON deleted_at IS NULL so a retired condition FREES its key: the next
-- import re-creates it rather than colliding with a row no read returns.
CREATE UNIQUE INDEX condition_imported_key_idx
    ON condition (company_id, project_id, imported_key)
    WHERE imported_key IS NOT NULL AND deleted_at IS NULL;

COMMENT ON INDEX condition_imported_key_idx IS
    'One point lookup per distinct Background step per import request: "is this step already in '
    'the library". Also the uniqueness that makes a concurrent APPLY lose loudly (409 '
    'duplicate-imported-key, whole request rolled back) instead of quietly minting a second copy '
    'of one condition.';
