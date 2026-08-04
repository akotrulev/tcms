# §94 — Writing steps: the corpus that needs no curating, and the index spelling that decides whether it works

**Date:** 2026-08-05 · **Plan:** `docs/plans/2026-08-04-test-case-authoring-rework.md`
**Status: COMPLETE — ALL THREE PHASES, BUILT AND UNVERIFIED.** Three commits (`ada1dc0`, `fca7abf`,
and this one). Every gate is green and **not one queued check has been run**: four backend mutation
rows and eleven browser rows (51–61), zero executed, per §65. Two of the browser rows are live-only
and they are the ones that carry the slice's real risk — see §8.

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

## 6. Phase 2 — the step editor, and the defect auto-append introduced

The corpus endpoint got its consumer, and the editor changed in four ways that are one idea: **a
step row is something you write, not something you allocate.**

- The **Action field is an MUI `Autocomplete` (`freeSolo`)** completing from
  `GET /projects/{id}/step-suggestions` — debounced 250ms **per row**, `enabled`-gated on a
  non-blank fragment, `TIMEOUTS.typeahead`, `staleTime` 60s. A pick fills **all three** fields.
- **Typing in the last row appends the next one**, so the *Add step* button and its
  `CaseEditor.Steps.AddButton` testid are gone.
- **Trim, then drop.** Every value is trimmed at submit and rows that are empty once trimmed never
  reach the API.
- A pick **copies**; it creates no link and no `sharedStepId`. `SharedStepPicker` is untouched.

### The refine, which had to land in the same commit

With a permanently-present blank row, `steps.length > 0` is satisfied by a form nobody has written a
step into — **an empty case saved clean, with a success toast and a version bump.** `CaseSchema` now
counts `liveSteps(data.steps)`.

The mirror-image half is less obvious and equally compulsory: `StepSchema.action.min(1)` would have
failed on the blank row, refusing **every** save with an error on a row the author never touched. So
the field-level rule became a cross-field one — *a row carrying anything must carry an action; a row
carrying nothing is an invitation* — reported at `steps.N.action`, structured cases only.

**`liveSteps` has one definition and two consumers** (the refine and the submit's filter) because a
validator that counts rows the submit drops, and a submit that keeps rows the validator ignored, are
each invisible on their own.

### Two defects found, both pre-existing

- **The step list's error alert had never rendered.** A refinement whose `path` is a *field array*
  lands on `errors.steps.root`, not `errors.steps.message`. Read only as the latter since Phase 4,
  "Add at least one step" was computed, blocked the submit, and displayed **nothing** — the form
  simply did not respond to the button. Found by the first test ever written for this screen.
- **Removing the trailing blank row was a dead end** once the Add button went. `ensureTrailingBlank`
  holds the invariant after a remove, and the shared-step insert lands *before* the blank row rather
  than after it.

`PlanDocumentPanel`'s AddCaseBar now offers **"Write the steps"** — a link to the case editor for
the case it just made — rather than growing a second step editor beside it.

## 7. Phase 3 — drag to reorder, and what it cost

`@dnd-kit/core` + `sortable` + `utilities` + `modifiers`. `DndContext` + `SortableContext` around
the rows, `useSortable` per row through a `SortableStep` render-prop wrapper (the row's JSX stays
where it was, so the diff reads as a reordering change rather than a rewrite), a handle in the
existing row toolbar, and `onDragEnd` → the `steps.move` the ▲▼ buttons already called.

**The ▲▼ buttons stay.** They are the keyboard and screen-reader path, the only way to move a step
by exactly one place deliberately, and the ones that work on a touch screen. A `KeyboardSensor` is
wired to the handle as well, because a focusable handle that did nothing on Space would be worse
than no handle.

`resolveStepMove` is its own module for one reason: **the three ways a drag ends in nothing** —
dropped outside the list, dropped where it started, dropped on an id the list no longer holds — are
the only part of drag-and-drop that can be *wrong* rather than merely unwired, and they are
unreachable from a component test. `move(i, i)` reorders nothing and marks the form **dirty**, which
on an edit form offers to write a new version of an unchanged case.

### The bundle, measured rather than assumed

The working agreement exists because the DataGrid split looked obviously right and made total JS
~40% worse. Measured before and after phase 3, same build:

| | Total JS (raw) | Total JS (gzip) | `CaseEditorPage` chunk | Entry chunk |
|---|---|---|---|---|
| Before | 1,617,790 B | 512,058 B | 33.68 kB / 10.75 kB gz | 336.78 kB |
| After | 1,663,987 B | 526,881 B | 79.92 kB / 25.70 kB gz | 336.81 kB |
| Δ | **+46.2 kB (+2.9%)** | **+14.8 kB (+2.9%)** | +46.2 kB | **+0.03 kB (noise)** |

**All of it lands in the lazily-loaded route chunk** and the entry bundle is unchanged, so nobody
who never opens the case editor pays for it. That is the number the decision turns on, and it is why
no manual code-splitting was attempted on top.

## 8. What is NOT done, and must not be read as done

- **Every queued check.** Four backend mutation rows (phase 1) and **browser rows 51–61**, none run
  — not the cheap one, not the high-signal one. **This slice is built and unverified.** Rows 59 and
  61 are live-only and cover what mock mode structurally cannot show: the corpus's ranking, its
  soft-delete exclusion and its project scoping (every fixture case carries the *same four steps*,
  and the mock's `DELETE` removes the row rather than flagging it), and evidence staying with its
  step across a reorder.
- **`sharedStepId` and `SharedStepPicker` are vestigial and still shipped.** Deliberate: removing a
  persisted column and a shipped contract field is its own slice with its own migration.
- **`caseSchema.ts` still has no `tags` field**, so the editor cannot author tags — §96.
- **The two trigram indexes spelled on a bare column** (`shared_step_title_trgm_idx`,
  `condition_title_trgm_idx`) — §4 above, deliberately left for a slice that can measure it.
- **§95 and §96** in full.
- **A process error worth not repeating:** a second `./gradlew build` was started while the first was
  in flight. Both wrote `backend/build/`, and the resulting `Could not write XML test results`
  errors read as test failures. `CLAUDE.md` states the one-build-one-daemon rule; it was walked into
  anyway. A pipe to `tail` also masked Gradle's exit code as `0` while the build had failed — the
  green reported here captures Gradle's own status directly.

## 9. Files

**Backend, new:** `StepSuggestionController` · `StepSuggestionService` · `StepSuggestionQuery` ·
`StepSuggestionView` · `V30__test_step_action_trgm.sql` (**head**, `V31` next free, **policy count
still 59** — an index, no table) · `StepSuggestionEndpointTest` (13 tests, fixture prefix **`c4`**).

**Backend, changed:** `TestStepRepository` — gains `findSuggestions`.

**Contract:** `openapi.yaml` — `/projects/{projectId}/step-suggestions` + the `StepSuggestion`
schema; `frontend/src/api/schema.d.ts` regenerated.

**Frontend, new:** `features/cases/CaseEditorPage.test.tsx` (**the screen's first test file**, 12
tests) · `features/cases/stepReorder.ts` + `stepReorder.test.ts` (5) · `useStepSuggestions` +
`keys.stepSuggestions` in `api/queries/{library,keys,index}.ts` · `StepSuggestion` in `api/types.ts`
· the mock's `stepCorpus` + `GET /step-suggestions` in `mocks/handlers/cases.ts`.

**Frontend, changed:** `features/cases/CaseEditorPage.tsx` (`StepActionField`, `SortableStep`,
auto-append, trim-and-drop, `ensureTrailingBlank`, the drag context; **`Steps.AddButton` deleted**,
`Steps.DragHandle` and `Steps.Suggestion` added) · `features/cases/caseSchema.ts` (`isBlankStep`,
`liveSteps`, the two rewritten rules) · `CaseSchema.test.ts` (+5 net) ·
`features/plans/PlanDocumentPanel.tsx` (the **Write the steps** link).

**Dependencies:** `@dnd-kit/core` 6.3 · `@dnd-kit/sortable` 10.0 · `@dnd-kit/utilities` 3.2 ·
`@dnd-kit/modifiers` — the first runtime dependencies added to the frontend since the scaffold. Cost
measured in §7.

**Docs:** this file · the plan · `docs/verification/backend-mutation-checks.md` (+4 rows) ·
`docs/verification/frontend-browser-checks.md` (**+11 rows, 51–61**) · `docs/agent-context/be-java.md`
and `fe-react.md` (seam + session log).

**Gate:** `./gradlew build` green, 1649 → **1681** (+13). `npm run build` + `npm run lint` green;
Vitest **783 → 805** (+22 — 12 editor, 5 reorder, 5 net schema).
