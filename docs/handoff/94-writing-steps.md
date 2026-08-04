# §94 — Writing steps: the corpus that needs no curating, and the index spelling that decides whether it works

**Date:** 2026-08-05 · **Plan:** `docs/plans/2026-08-04-test-case-authoring-rework.md`
**Status: IN PROGRESS — PHASE 1 OF 3.** One commit (`ada1dc0`). **The endpoint has no UI consumer,
so nothing about writing a test case has changed on screen yet.** Built and unverified per §65: four
rows queued to the backend mutation checks, none run.

## 0. The numbering, and why this file exists before the slice is finished

**This file is written mid-slice on purpose.** §92's header records that a concurrent strand claimed
its number mid-slice and two published commits still say §91, because **a § is not reserved until
the handoff file exists** — §10's lesson, paid twice. Phase 1 committed as §94 with nothing holding
the number. This closes that, and the file grows as phases 2 and 3 land rather than being written at
the end.

## 1. What was asked

Five things, from one request: every step searchable at project scope; a new step row appearing as
you type rather than behind a `+`; whitespace trimmed and empty steps never saved; drag-and-drop
reordering; the same flow when creating a case from a test plan. Plus: a case in more than one
suite, "either by functionality like payment, or by type like smoke, regression".

Four rounds of design conversation moved it a long way from the literal reading, and the reversals
are the valuable part. They are recorded in the plan's *Decisions* section; the two that reshaped the
schema work are summarised in §5 below.

## 2. Steps are NOT shared objects — the reversal that shrank this slice

The obvious reading of "all steps are shared steps" is that every step becomes a library row and an
edit propagates. **That was considered and rejected.** A step's text belongs to its case: the project
corpus is a *search and autocomplete source only*, picking a suggestion **copies** the text, and
editing it afterwards changes that case and nothing else.

**Conditions are where the sharing lives instead** — a case attaches a condition by id and an edit
to it propagates everywhere it is used, with no prompt. That is already how the data models it
(`case_condition.condition_id` → `condition`), so propagation costs nothing once a writer exists.
It is §95's whole slice, and it is currently impossible: `/projects/{projectId}/conditions` is
**GET-only** and has **zero frontend call sites** — a library nothing can write, and no conditions
section in the editor at all.

The consequence for this slice is that `TestStepInput.sharedStepId` and `SharedStepPicker` are now
**vestigial**. They were deliberately left in place: removing a persisted column and a shipped
contract field is its own change with its own migration, and smuggling it in here would make the
diff about two things.

## 3. The corpus is derived, not curated — and that is the correction to `shared_step`

`GET /projects/{projectId}/step-suggestions` is a `GROUP BY (action, test_data, expected_result)`
over the project's live cases, ranked by `COUNT(DISTINCT case_id)`.

**`shared_step` failed because somebody had to populate it.** §93 gave it a writer; that did not fix
the actual problem, which is that a library nobody fills ranks nothing and its picker opens empty
forever. A derived corpus is populated the moment the project has a case and never needs
maintaining.

Three decisions inside it, each with a test that can fail:

- **The group key is the whole triple**, and a pick fills all three fields. Grouping on `action`
  alone collapses two steps that share a sentence but not an expected result, and then hands the
  author one of the two arbitrarily — a wrong value silently written into a field they will not
  re-check.
- **`COUNT(DISTINCT case_id)`, not `COUNT(*)`** (§6e). The fixture writes one triple **three times in
  one case**, so a row count answers 5 where the answer is 3. Both numbers look plausible on sight,
  which is why the fixture makes them differ rather than trusting a reading of the SQL.
- **`deleted_at IS NULL`.** Cases are soft-deleted because executions reference them, so without it
  the ranking is by history and the endpoint keeps offering the step text of a deliberately retired
  case. The fixture's deleted case uses the top triple, so ignoring it answers 4.

**No id is served.** A suggestion is text to copy, not an entity to reference — and the absence is
what keeps it that way. `StepSuggestionEndpointTest` asserts the **exact field set** rather than
trusting the convention, because a consumer will use whatever is served and the copy-not-reference
decision would be reversed by nobody deciding anything.

## 4. `V30` indexes `lower(action)`, and its two siblings show why that matters

Postgres consults a functional index only for the expression it was built on. The query filters
`LOWER(action) LIKE` — case-insensitivity is not optional in a typeahead — so an index spelled
`GIN (action gin_trgm_ops)` is **never consulted**: built, maintained on every write, and the plan is
the sequential scan it always was. Nothing reports it.

**`shared_step_title_trgm_idx` and `condition_title_trgm_idx` (both `V1`) have exactly that gap
today.** Both are spelled on the bare column while `SharedStepRepository.findLibrary` and
`ConditionRepository.findLibrary` both filter on `LOWER(title)`. Neither index is used by the query
it exists for, and both libraries look indexed.

**Deliberately not fixed here.** They are bounded to one project's curated library — hundreds of
rows, as their own comments argue — where this corpus is bounded only by how much the project has
written. A correctness-neutral performance change belongs in a slice that can measure it. Recorded in
`V30`'s header, the orientation cache, and the mutation queue, so the next reader finds the reason
rather than the omission.

**The mutation queue's first row is the one that matters**: an `EXPLAIN` showing a sequential scan on
a populated table means the index is spelled the wrong way. The test suite structurally cannot catch
this — the query is correct either way, and Testcontainers' tables are too small for the planner to
prefer an index regardless.

## 5. Suites, and the two reversals that are NOT in this slice

The original ask was "a case can be assigned to multiple suites". Two rounds changed what that means:

1. **Suites are a selection mechanism, not an accounting unit** — "failure is counted against test
   executions, which are either filled in manually or derived from a test suite. The suite is just a
   collection of cases, nothing more."
2. Then, on the observation that this makes a suite tag-shaped: **a suite is a resolvable
   collection** — its own cases, plus cases added individually, plus every case carrying any of its
   tags, unioned and deduped. **Membership is optional**: a case created freely belongs to no suite
   until tagged or added.

So `test_case.suite_id` goes **nullable**, not away — which keeps §96 additive. The one real
consequence, and it is a silent one:
`ExecutionSource.projectRecordedExecutions()` populates `execution_fact.suite_id`/`suite_path`
(both `NOT NULL`) through `JOIN suite s ON s.id = c.suite_id`. **With a nullable `suite_id` that
inner join drops the fact row entirely** for an unfiled case — a short fact log, no error, and
exactly the class of defect that file's own comments exist to prevent. Both columns go nullable and
the join becomes a `LEFT JOIN`.

**Run scoping needs no new mechanism.** "Run everything tagged smoke" is a suite whose tag rule is
`smoke`, scoped into a plan the way suites already are. No tag arm on `plan_scope_item`, no change to
`ck_plan_scope_target`.

Also found while scoping §96, and not yet fixed: **`caseSchema.ts` has no `tags` field**, so the case
editor cannot author tags at all, and `CaseUpdateRequest` **rejects `tags` with a 400 when present**
— it is on that file's own list of five fields "no slice has built yet". Only `BulkActionDialog`
touches tags today.

## 6. What is NOT done, and must not be read as done

- **Phases 2 and 3 of this slice.** The autocomplete, the auto-appending row, the trimming, the
  empty-step drop, and drag-to-reorder. **All four things originally asked for live here.** The
  endpoint has no consumer; on screen, nothing has changed.
- **The `CaseSchema` refine that phase 2 must change with it.** "At least one step" currently counts
  `steps.length`; with a permanently-present trailing blank row it must count **non-empty** steps, or
  an empty case saves clean. That is the defect auto-append introduces and it must land in the same
  commit.
- **§95 and §96** in full.
- **Every queued check.** Four backend rows, none run (§65).
- **A process error worth not repeating:** a second `./gradlew build` was started while the first was
  in flight. Both wrote `backend/build/`, and the resulting `Could not write XML test results`
  errors read as test failures. `CLAUDE.md` states the one-build-one-daemon rule; it was walked into
  anyway. A pipe to `tail` also masked Gradle's exit code as `0` while the build had failed — the
  green reported here captures Gradle's own status directly.

## 7. Files

**Backend, new:** `StepSuggestionController` · `StepSuggestionService` · `StepSuggestionQuery` ·
`StepSuggestionView` · `V30__test_step_action_trgm.sql` (**head**, `V31` next free, **policy count
still 59** — an index, no table) · `StepSuggestionEndpointTest` (13 tests, fixture prefix **`c4`**).

**Backend, changed:** `TestStepRepository` — gains `findSuggestions`.

**Contract:** `openapi.yaml` — `/projects/{projectId}/step-suggestions` + the `StepSuggestion`
schema; `frontend/src/api/schema.d.ts` regenerated.

**Docs:** this file · the plan · `docs/verification/backend-mutation-checks.md` (+4 rows) ·
`docs/agent-context/be-java.md` (seam + session log).

**Gate:** `./gradlew build` green, 1649 → **1681** (+13). `npm run build` + `npm run lint` green.
