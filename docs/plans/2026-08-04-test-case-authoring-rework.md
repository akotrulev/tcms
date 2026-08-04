# Reworking how test cases are written

## Context

Writing a test case today is slower than it should be, and two of the reuse mechanisms the schema
was designed around are inert:

- **Steps are typed from scratch every time.** Nothing surfaces what the project already says. The
  `shared_step` library exists and `SharedStepPicker` can insert from it, but it holds only what
  someone deliberately curated — so in practice it is empty and the same twenty steps get retyped
  in twenty slightly different wordings.
- **Adding a step is a button press** (`CaseEditorPage.tsx:514`), so eight steps is eight extra
  clicks. Nothing is trimmed, and a row left blank is submitted as a step.
- **Reordering is ▲▼ buttons only** — one click per position moved.
- **Pre/post conditions cannot be authored at all.** `/projects/{projectId}/conditions` is GET-only
  (`ConditionController` has one `@GetMapping`), it has **zero frontend call sites**, and
  `CaseDetailPage` renders conditions read-only. A library nothing can write, and no conditions
  section in the editor — the same defect `shared_step` had before §93 gave it a writer.
- **A case must live in exactly one suite.** `test_case.suite_id` is `NOT NULL`, so a case that is
  both a Payments case and a smoke test has to pick one, and a case that belongs nowhere yet cannot
  be created at all.
- **Tags are half-built.** `case_tag` exists and `tags` is on `TestCaseInput`, but `caseSchema.ts`
  has **no `tags` field**, so the editor cannot author them; `useCaseFilters` cannot filter by them;
  and `CaseUpdateRequest` currently **rejects `tags` with a 400 when present** (its own comment lists
  it among the five fields "no slice has built yet"). Only `BulkActionDialog` touches tags at all.

### The decisions behind this plan

Settled in conversation. Several supersede shipped design, so they are recorded with their reasons.

1. **Steps are NOT shared objects.** A step's text belongs to its case. The project's step corpus is
   a *search and autocomplete source only* — like step suggestions in a Cucumber feature file.
   Picking a suggestion **copies the text in**; it creates no link, and editing a step afterwards
   affects that case and nothing else. → Supersedes `TestStepInput.sharedStepId`'s premise.
2. **Suggestions come from every step written in the project, ranked by frequency** — not from a
   curated table. Curation is exactly what left `shared_step` empty.
3. **Conditions ARE the shared objects.** A case links a condition by id, and **editing a condition
   changes it everywhere it is used, with no prompt.** Already how the data models it
   (`case_condition.condition_id` → `condition`), so propagation is free once a writer exists.
4. **A suite is a resolvable collection, and membership is optional.** A suite's members are the
   cases filed in it, plus cases added individually, plus **every case carrying any of its tags** —
   unioned across tags and deduped. A case may be created freely with no suite and no tags. This is
   what makes a case reachable from several suites without any hand-maintained many-to-many.
5. **`@dnd-kit` for drag-and-drop**, keeping the ▲▼ buttons as the keyboard path.

### What decision 4 costs, and what it deliberately does not

`test_case.suite_id` goes **nullable** rather than away. That keeps the change additive: the suite
tree, the case grid, plan scope, `suite_run_summary` and every suite-grouped report keep working on
the column they already read.

The one real consequence: `ExecutionSource.projectRecordedExecutions()` populates
`execution_fact.suite_id`/`suite_path` (both `NOT NULL`) via `JOIN suite s ON s.id = c.suite_id`
(`ExecutionSource.java:161`). With a nullable `suite_id` **that inner join silently drops the fact
row entirely** for any unfiled case — a short fact log with no error, which is precisely the class
of defect that file's own comments exist to prevent. So both columns become nullable and the join
becomes a `LEFT JOIN`, and suite-grouped report sections grow an explicit "not filed" bucket rather
than quietly omitting those results.

Facts already written keep their frozen values and are **not** backfilled — the freeze is correct
and rewriting history is what it exists to prevent.

**Run scoping needs no new mechanism.** "Run everything tagged smoke" is a suite whose tag rule is
`smoke`, scoped into a plan the way suites already are (`plan_scope_item.suite_id`). No tag option
on plan scope, no change to `ck_plan_scope_target`.

---

## Sequencing

Three slices, continuing from §93. Flyway head is `V29`; **`V30` is next free.**
Each phase gates and commits on its own.

| Slice | What | Size |
|---|---|---|
| **§94** | Writing steps — autocomplete, auto-append, trim, drag-reorder | medium |
| **§95** | Conditions get a writer, and edits propagate | medium |
| **§96** | Tags become real, and a suite resolves from them | large |

---

## Slice §94 — writing steps

### Phase 1 — the step corpus endpoint (backend)

Contract (`docs/specs/openapi.yaml`, then `npm run gen:api`):

```
GET /projects/{projectId}/step-suggestions?q=
→ 200: [ { action, testData, expectedResult, useCount } ]
```

Distinct `(action, testData, expectedResult)` triples across the project's **live** cases
(`test_case.deleted_at IS NULL`); `useCount` is the number of cases using that triple; ordered
`useCount DESC, action ASC`, capped at 50. The whole triple, not just `action` — decision 1 says a
pick autocompletes the *entire* step, which is what makes it Cucumber-like rather than a text hint.

- New `StepSuggestionController` / `StepSuggestionService` in `repository`, beside
  `ConditionController`. Gate on the case-read **permission, never a role name**.
- The repository interface **must carry `@Transactional(readOnly = true)`** or RLS returns zero rows
  (`RepositoryTransactionTest` derives its list from the live context and will fail the build).
- **`V30__test_step_action_trgm.sql`** — `test_step` has only `test_step_case_idx (case_id,
  position)`; the `q` fragment match needs a GIN trigram index on `action`. Mirror
  `shared_step_title_trgm_idx`. No new table, so the RLS policy count stays **59**.

### Phase 2 — the step editor (frontend)

`CaseEditorPage.tsx` + `caseSchema.ts`:

- **Action becomes an MUI `Autocomplete` (`freeSolo`)**, options from the new endpoint — debounced,
  `enabled`-gated on a non-empty query, with a per-endpoint timeout budget, per the
  `src/api/queries/` conventions. Selecting an option fills action, testData **and** expectedResult.
- **Auto-append**: typing into the **last** row's action appends a blank row after it. Delete the
  "Add step" button and its `data-testid`.
- **Trim and drop empties at submit**: trim every value, then filter rows where action, testData and
  expectedResult are all empty. A whitespace-only step never reaches the API.
- **`CaseSchema`'s "at least one step" refine must count non-empty steps, not `steps.length`** —
  otherwise the permanently-present trailing blank row satisfies it and an empty case saves clean.
  This is the defect auto-append introduces, which is why the refine changes in the same commit.
- Keep the `stepFormat` toggle's one-blank-row seed at `:354`.

`SharedStepPicker` and `sharedStepId` **stay untouched here.** Decision 1 makes them vestigial, but
removing a persisted column and a shipped contract field is its own change with its own migration —
recorded as follow-up, not smuggled in.

### Phase 3 — drag to reorder

- `npm i @dnd-kit/core @dnd-kit/sortable @dnd-kit/utilities`.
- `DndContext` + `SortableContext` around the rows, `useSortable` per row, handle in the existing row
  toolbar; `onDragEnd` → the `steps.move(from, to)` already at `:452`.
- **Keep ▲▼** — the keyboard and screen-reader path.
- **Measure the bundle before and after** (working agreement: the DataGrid split looked obviously
  right and made total JS ~40% worse). Put the numbers in the handoff.

Reordering is already safe — `test_step.id` is stable and `step_result` FKs to it, so moving a step
never reassigns evidence. The comment at `:445` stays true.

### The plan screen

`PlanDocumentPanel`'s `AddCaseBar` creates a title-and-suite draft and nothing else. Point it at the
same editor rather than growing a second step editor beside it — **a second implementation is exactly
the "hundred slightly different pages" failure** CLAUDE.md names. Its suite field also becomes
optional here (decision 4).

---

## Slice §95 — conditions get a writer, and edits propagate

```
POST /projects/{projectId}/conditions               → 201 Condition
PUT  /projects/{projectId}/conditions/{conditionId}  → 200 Condition   (lockVersion; 409 on conflict)
```

- `library:manage` for both, as `POST /shared-steps` (§93). **`PUT` is the case §93's note was
  actually about**: the edit propagates to every referencing case, so the confirm text states *how
  many cases change*. Per decision 3 it does not offer a fork.
- Optimistic locking on `condition.lock_version`, matching §93's retitle-409 pattern.
- Propagation needs no code — `case_condition.condition_id` and `suite_condition.condition_id` both
  reference the edited row.
- **The case editor gains Preconditions/Postconditions**: attach from the library (the `/conditions`
  GET that has never had a caller), detach, reorder, create, and edit behind the impact confirm.
  `CaseDetailPage`'s `ConditionPanel` is the read counterpart and already distinguishes inherited
  from own. `CaseUpdateRequest` must stop 400-ing `preconditions`/`postconditions` — deleting that
  boundary in one place is what its comment says each slice does in turn.
- `case_condition` has `position`; reuse §94's drag primitive.

---

## Slice §96 — tags become real, and a suite resolves from them

**`V31__suite_membership.sql`:**

- `suite_case (company_id, suite_id, case_id)` — individually added members.
- `suite_tag_rule (company_id, suite_id, tag)` — the suite's tags, unioned.
- `test_case.suite_id` → **nullable**.
- `execution_fact.suite_id` / `suite_path` → **nullable**.

Both new tables need `company_id NOT NULL` leading the PK and every composite index — the ERD §0.1
rule, and join tables are exactly where it gets forgotten. `SchemaConventionTest` fails the build
until they comply, and the **RLS policy count goes 59 → 61**.

**Backend:**

- Membership resolution — filed ∪ `suite_case` ∪ cases matching any `suite_tag_rule`, deduped — as
  one query beside `CaseFacts.caseIdsInSuiteSubtree` (`CaseFacts.java:596`), which already resolves a
  suite subtree for run scoping and is where the tag arm belongs.
- `ExecutionSource.projectRecordedExecutions()` — `JOIN suite` → `LEFT JOIN suite`. **This is the
  line that silently shortens the fact log if it is missed.**
- `CaseUpdateRequest` stops 400-ing `tags`; `CaseEdit` validates and persists them, reconciling
  `case_tag` the way `CaseClassifications` reconciles classifications.

**Frontend:**

- `caseSchema.ts` gains `tags` — today the editor cannot author them at all.
- `useCaseFilters` gains a tag filter, **held in the URL** like every other filter.
- Suite create/edit gains its tag rules and its added-case list.
- `SuiteTree` shows resolved membership; **case counts stop summing to the project total** (a case in
  three suites is counted three times), and the UI must say so rather than let a reader read it as a
  bug.
- `TestCaseSummary.suiteId`/`suiteName` become nullable — the case grid needs an "unfiled" rendering.

---

## Critical files

| File | Change |
|---|---|
| `docs/specs/openapi.yaml` | `/step-suggestions`; condition `POST`/`PUT`; suite membership; nullable `suiteId`. **Every new schema needs its `required` array, including inline nested objects.** |
| `backend/.../repository/StepSuggestionController.java` *(new)* | The corpus endpoint |
| `backend/.../repository/ConditionController.java` | Gains `POST` / `PUT` |
| `backend/.../repository/CaseUpdateRequest.java`, `CaseEdit.java` | Stop refusing `tags`, `preconditions`, `postconditions` |
| `backend/.../reporting/ExecutionSource.java` | `LEFT JOIN suite` — the fact-log correctness line |
| `backend/.../execution/CaseFacts.java` | Membership resolution beside `caseIdsInSuiteSubtree` |
| `backend/src/main/resources/db/migration/V30__…`, `V31__…` | Trigram index; suite membership |
| `frontend/src/features/cases/CaseEditorPage.tsx` | Autocomplete, auto-append, trim, DnD, conditions, tags |
| `frontend/src/features/cases/caseSchema.ts` | Non-empty-step refine; `tags` |
| `frontend/src/api/queries/cases.ts` | `useStepSuggestions`, condition write hooks |
| `frontend/src/mocks/{db,handlers}/cases.ts` | Stateful mocks for the new endpoints |
| `frontend/src/features/plans/PlanDocumentPanel.tsx` | Link to the editor; suite optional |
| `docs/agent-context/{be-java,fe-react}.md` | **Updated in the slice's own commit** |
| `docs/handoff/9N-*.md` + one index row in `docs/HANDOFF.md` | Never append to `HANDOFF.md` |

## Verification

Per-phase gates: `./gradlew build` from `backend/` — the only thing that runs the 1000+ integration
tests, since CI does not — and `npm run build && npm run lint && npm run test:run` from `frontend/`.

**New backend tests**, in the existing style: corpus ranking and project scoping (a sibling
project's steps must not appear — the isolation failure this query invites); RLS zero-rows on a
context-less read; condition create/edit permission and 409, with propagation asserted from a
*second* case's read; membership resolution deduping a case that both is filed in a suite and
matches its tag; and **a fact-log test that a case with no suite is still projected** — the defect
the `LEFT JOIN` prevents, invisible otherwise.

**Frontend (Vitest + RTL):** auto-append, trimming, empty-row drop, the non-empty-step refine, and a
suggestion pick filling all three fields. DnD is asserted through the ▲▼ path plus `onDragEnd`
directly — driving a real drag in jsdom is not worth the cost.

**Browser and mutation checks are deferred and batched (§65).** Each slice appends its rows to
`docs/verification/frontend-browser-checks.md` and `docs/verification/backend-mutation-checks.md`
and runs **none** of them — not the cheap one, not the high-signal one. A slice in an unrun queue is
**built and unverified**, and the handoff says exactly that.
