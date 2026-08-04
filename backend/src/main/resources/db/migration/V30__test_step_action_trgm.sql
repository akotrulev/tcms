-- V30 — the step corpus gets the one index it needs.
--
-- `GET /projects/{projectId}/step-suggestions` ranks every step ever written in a project by how
-- many live cases use it, narrowed by a fragment of the action. Steps are no longer authored from
-- scratch: the editor's action field autocompletes from what the project already says, the way a
-- Cucumber feature file completes from the step definitions already defined. That makes this query
-- run on a KEYSTROKE, which is the whole reason the index exists.
--
-- `test_step` has carried exactly one index since V1 — `test_step_case_idx (case_id, position)` —
-- and it serves the detail endpoint's seek on one case. Nothing serves a fragment match across a
-- project, so without this the suggestion query is a sequential scan over every step row in the
-- COMPANY, per keystroke, behind an RLS predicate that cannot narrow it either.
--
-- THE INDEX IS ON THE EXPRESSION `lower(action)`, NOT ON `action`, AND THAT IS THE WHOLE POINT
--
-- Postgres consults a functional index only for the expression it was built on. The query filters
-- `LOWER(action) LIKE '%…%'` — case-insensitivity is not optional in a typeahead, a search that
-- refuses "log" for "Log in" is one nobody uses — so an index spelled `GIN (action gin_trgm_ops)`
-- is **never consulted by this query at all**. It would be built, it would be maintained on every
-- step write, EXPLAIN would show the same sequential scan as before, and nothing would report it.
-- That is the failure mode this comment exists to prevent, because the wrong spelling is the
-- obvious one and it is silent.
--
-- Its two siblings have exactly that gap today. `shared_step_title_trgm_idx` and
-- `condition_title_trgm_idx` (both V1) are spelled on the bare column while
-- `SharedStepRepository.findLibrary` and `ConditionRepository.findLibrary` both filter on
-- `LOWER(title)` — so both libraries are sequentially scanned and both look indexed. This migration
-- deliberately does NOT fix them: they are bounded to one project's library (hundreds of rows, as
-- their own comments argue), the fix is a rebuild on two tables, and a correctness-neutral
-- performance change belongs in a slice that can measure it. Recorded here so the next reader finds
-- the reason rather than the omission.
CREATE INDEX test_step_action_trgm_idx
    ON test_step USING GIN (lower(action) gin_trgm_ops);

COMMENT ON INDEX test_step_action_trgm_idx IS
    'Serves the step-suggestion typeahead''s LOWER(action) LIKE ''%…%'' fragment match. Built on '
    'the EXPRESSION lower(action), not on the column: a trigram index on `action` is never '
    'consulted by a predicate over LOWER(action), so the plain spelling indexes nothing this '
    'query asks for. See V30''s header.';
