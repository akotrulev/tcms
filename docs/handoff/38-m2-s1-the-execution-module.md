# §38. Backend M2, slice 1 + frontend — the execution module, and the seam it could not cross (2026-07-31)

Run through the **`be-java`** skill (Mode B — extend, though the module is greenfield inside a
brownfield tree) plus `be-java-database-code` for the JPA layer, then the **`fe-react`** skill
(Mode B, re-entering Phase 2). **Lane A of a three-lane parallel run, and the contract holder for
it** — lane B held `identity`, lane C reviewed the frontend.

The seventh Modulith module. `execution` has been named in the plan, the ERD, `V1`'s 64 tables and
four separate "Next" lists since M0, and until now it was an empty word: `backend/src/main/java/com/tcms/`
had no such package, and every plan, run and execution screen in the SPA ran on MSW.

| Path | Purpose |
|---|---|
| `execution/package-info.java` | The module declaration, and the whole argument about the seam below. |
| `execution/CaseFacts.java` | **The one class that names a `repository` table.** Pinned snapshots, and the plan-scope expansion. |
| `execution/TestPlan · Configuration · TestRun · TestExecution · StepOutcome` | The entities, four of them read-only. |
| `execution/PlanService · RunService · ExecutionService` | The three reads and the one write. |
| `execution/ExecutionViews` | The one assembler both the checklist and the detail render through. |
| `execution/PlanController · RunController` | Five `GET`s and the `PATCH`. |
| `execution/ExecutionEndpointTest` | **New, 43 tests** over HTTP against real Postgres with RLS. |
| `docs/specs/openapi.yaml` | The refusals, three `required` arrays, and one contract lie corrected. |
| `api/queries/runs.ts` · `features/runs/ExecutionPage.tsx` · `mocks/handlers/runs.ts` | `If-Match`, and the amend gate. |

### The orientation pass found the seam before it found the slice, and it is the whole design

`execution` may depend only on `tenancy`, `identity` and `project`. **Not `repository`** — ArchUnit
fails the build for the edge, and `repository`'s own package comment names this module specifically
as the one that must not import it. And an execution is meaningless without a case: the response
carries the case's key, its title, its steps and its conditions.

The reconciliation is in `V1`, and it is not a loophole. `test_execution` has **two foreign keys
into `repository`** — `case_id` and `case_version_id` — and `plan_scope_item` has two more; ERD §5's
own diagram draws them (`TEST_CASE_VERSION ||--o{ TEST_EXECUTION : "pinned by"`). So the schema
deliberately models this module as *holding ids into the repository*, while §0.2 forbids the **code**
edge. Both are true and they are about different things: **a shared row is not a shared class**, and
what §0.2 exists to prevent is two modules becoming deployable only together.

Three implementations were available and two are wrong:

1. **A second JPA entity over `test_case_version` in this package.** One table owned by two modules,
   two sets of column expectations for `ddl-auto: validate`, two places a mapping drifts.
2. **An event-fed read model.** Correct eventually, and it needs events nothing publishes, a
   projection nothing populates, and a backfill for every row that already exists. It is a slice, not
   a seam.
3. **What shipped:** the entire crossing in **one class, `CaseFacts`**, issuing native SQL and
   returning records declared here. Nothing else in the package may name one of those tables.
   Concentrating it is what makes the coupling reviewable, and what makes it a single file to rewrite
   the day these facts do arrive as events.

### The narrower coupling and the correct answer turned out to be the same choice

`CaseFacts` **does not read the live case.** It reads `test_case_version.snapshot` — which
`repository.CaseVersions` writes as the fully resolved `TestCaseDetailView`, steps and inherited
conditions already walked.

That is what the execution screen is *required* to show (plan §6j): the case as it was, not as it is
now. Reading `test_case` and `test_step` would have been both a wider cross-module edge and the wrong
answer — a shared condition edited today would silently rewrite what a tester was told to do last
March, in a run that has already been reported on. **This module therefore never reads `test_case`,
`test_step`, `suite` or `condition` at all.**

The cost is real and stated rather than waved at: the snapshot's shape is `repository`'s to change,
and nothing fails to compile if it renames a field. So the fixture **authors its cases through the
real `POST /cases`** rather than hand-writing the JSON, and one test **edits the case after the
execution exists** and asserts the execution still answers v1's title and v1's steps. The coupling is
checked by the two real writers, end to end. A live read passes every other assertion in the class.

### Two permissions on one verb, and the row decides which

`result:submit` records a first verdict. `result:amend` changes one that is already there, and it is
**Lead-only** — roles spec §A4 lists it beside `suite:delete` under *creative vs destructive*: a Test
Engineer is never blocked from producing, only from unmaking someone else's work, and overwriting a
recorded result is unmaking it. Collapse them and the Automation Service — the deliberately smallest
role in the product, whose whole justification (§A6) is that a leaked CI token can post results and
break nothing — can rewrite a human's verdict.

Which permission applies is a property of the **row**, which no other write in this product has to
deal with. So the execution is resolved through the *read* gate first and only then is the write gate
asked. That leaks nothing: a caller who gets that far has already passed the read gate, so learning
the execution exists tells them nothing `GET` would not. It costs one extra permission evaluation and
buys the property that matters — the verdict is still computed in exactly one place,
`ProjectService.writeGranted`, rather than by this service deciding for itself once it has seen the row.

**The two 403s are worded differently**, §32's rule: *"A result has already been recorded here, and
changing one needs a test lead"* versus *"You do not have permission to record results in this
project."* The second sentence is false for a Test Engineer and would send them to ask an
administrator for a permission they already hold, about a screen they are looking at.

### What this slice REFUSES, and the refusal is the honest answer

**`GET …/plans/{planId}/statistics` is not implemented.** It was in scope *if and only if* it could
be done without inventing a cross-module read, and it cannot:

- `bySuite` needs suite ids, keys and names — `repository`'s live tree, not a pinned snapshot, and
  not something a plan's scope pins either.
- `coveragePercent` needs a denominator spanning the repository.
- `trend` and `stale` are time series over runs; `exitCriteria.met` needs the aggregate to evaluate
  against.

The contract itself tags that path `[execution, reporting]`, and **`reporting` does not exist**.
Serving a partial one would put a second, quieter definition of "pass rate" in the product beside the
one the real endpoint will compute — on the same screen. It stays on MSW, the contract now says so in
its own description, and `passRate` / `coverage` on a plan are served as **null** rather than as an
invented zero: the contract marks both nullable precisely so "not measured" has a spelling.

### The contract changes, and all of them state a refusal

Six paths carried their `200` and **not one of their 404s**. That is §37 finding 2 for the *third*
time — a new path gets its success response written and its refusals forgotten — so this is now a
property of how paths get written here, not an oversight, and it is worth a checklist item rather
than another lesson.

| Change | Why |
|---|---|
| 404 on all six paths; 400/403/409 on the `PATCH` | Declared, with the reason each is a 404 rather than a 403. |
| `TestExecutionSummary.assignee` / `.executedBy` → `allOf` + `nullable: true` | **A contract lie.** Bare `$ref`s over two nullable columns, so the generated type claimed every execution has an assignee and an executor. Both null is the ordinary state of a run nobody has started; `executed_by` is also null for every ingested result. Same defect and same fix as §37's `EntityVersion.createdBy`. |
| `required` on `TestPlan`, `TestRunDetail`, `TestExecution`'s `allOf` members, plus `StepResult` | `required` does not descend through `allOf`, so every added property was optional and no consumer could use one. |
| `required` widened on `TestPlanSummary`, `TestRun`, `TestExecutionSummary` | All nullable, all always served. |
| `ExecutionResultInput` gains `required: [statusId]` and a description saying it has **no** `lockVersion` | See below. |
| `/plans` list item widened from `TestPlanSummary` to `TestPlan` | The SPA's `usePlans` has typed its result as `TestPlan[]` since Phase 1. Every `TestPlan` is a valid `TestPlanSummary`, so this removes a divergence rather than adding one, and it keeps the list and the detail on one assembler. |

**Tightening `required` immediately caught a defect in the mock**: `mocks/db/executions.ts` produced
`releaseVersion: undefined` where the contract says `string | null`. Three type errors on the first
`npm run build` after regeneration, on a fixture that had been green for two sessions.

### The `If-Match`-in-the-body divergence, found exactly where §30 predicted it

§30 corrected the suite rename and named the class. **This call site had it worse**: the SPA has sent
`lockVersion` in the PATCH *body* since Phase 1, and `mocks/handlers/runs.ts` read it from the body —
so both sides agreed, and both were wrong about where the contract puts it. Nothing could catch it,
because no server existed to read the header.

The server built here reads only the header. `ExecutionResultInput` no longer has a component for the
other spelling, so the divergence cannot be reintroduced by anything that still compiles.

### The frontend half was three changes, because the screens were already written

`ExecutionPage`, `RunDetailPage`, `RunListPage` and `PlanListPage` were built in Phase 2 against the
contract, so pointing them at a real server needed no rework — which is the Phase 1 discipline paying
out, and worth recording as the first time it has.

1. **`If-Match`** instead of the body field, above.
2. **The amend gate.** All four verdict buttons were gated on `result:submit` regardless of the row,
   so a Test Engineer looking at an already-recorded execution saw four enabled buttons that every
   one of them would 403. The gate now asks the same question the server does —
   `execution.status ? 'result:amend' : 'result:submit'`. **`useCan.ts` and `PermissionGate.tsx` are
   untouched**: §37's near-miss was a lane changing those two files while another asserted on them,
   and nothing here needed to.
3. **The mock refuses from the same `permissionsOn` the gate reads**, so the disabled button and the
   refused write cannot disagree — and a step that is not in the pinned version is a 400 rather than
   a silently dropped mark, matching the server.

### The defect the module's own first test run found

`ExecutionPeople.resolve` returns `Map.of()` when it is given no user ids — and an **immutable map
throws on `get(null)`** rather than answering null. Every execution in a run nobody has started has a
null assignee and a null executor, so *the ordinary case* was a 500. It was invisible until a fixture
contained an unexecuted execution, which is the only interesting kind on this endpoint.

`repository.CaseOwners` has the identical shape and is unbroken only because its caller never asks
about a null owner id. Recorded rather than fixed across the boundary — it is `repository`'s file and
this lane did not touch that module.

### What the counts mean, decided rather than assumed

`ResultCounts` has six buckets and Postgres has six `StatusKind`s, which do not line up: `UNTESTED`
and `RETEST` are both non-final. Both go to **`notRun`** — "still owed a verdict" — because a case
marked for retest has no outcome anybody reached, and because it keeps the identity the SPA's
`lib/results.ts` depends on true: `passed + failed + blocked + skipped + notRun = total`. A test
asserts the identity rather than trusting the arithmetic.

Every tally reads `StatusKind`, never a status name, so a client who renames "Blocked" to
"Environment down" does not vanish from their own run's counts.

### A plan's `caseCount` is the scope EXPANDED, and the fixture makes both wrong answers visible

A scope item names a case or a suite, and a **suite item means the folder and everything beneath it**
— which only the `ltree` path can answer. The fixture's plan has three scope items covering two
distinct cases (a folder whose subtree holds one, a second case directly, and the first case *again*
directly), so a `COUNT(*)` over `plan_scope_item` says 3, a count that forgets the subtree says 1,
and neither can be mistaken for the right answer. §32's "Payments read 40 in a project with 27
cases", pre-empted one entity over.

### Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL in 1m 25s
           673 tests, 0 failures, 0 skipped   (630 + 43)
frontend:  npm run build   → ✓ built, entry chunk 253.30 kB / 79.80 kB gzip (unchanged)
           npm run lint    → oxlint, exit 0, no output
           npm run test:run→ Test Files 10 passed (10) · Tests 203 passed (203)
           find "* 2.*"    → nothing outside node_modules
```

The 43 cover: all five reads for a Lead and a Viewer; **persona 3** (a company member with no grant)
getting 404 on every one; the other client refused both ways; a run in a **sibling project** and an
execution belonging to **another run** both 404 through a path the caller does hold; the pin, twice,
including after a real edit; the scope expansion and a soft-deleted case leaving it; criteria parsed
and deliberately unevaluated; the counts and their identity; both filters, including that an **empty**
`releaseVersion=` is no filter at all; both 403s and their differing sentences; permission-before-
payload; a foreign status and a foreign step refused with nothing written; stale, absent and malformed
`If-Match`; the lost update between two testers; step results round-tripping, updating in place rather
than violating `uq_step_result`, and an unmarked step still rendering; and the audit rows carrying the
case key and both verdicts.

### Deliberately not done

- **No writes for plans or runs.** `POST /plans` and `POST /runs` are declared in the contract and
  unimplemented, so a run still has to be seeded with SQL. That is the honest next slice and it is
  bigger than it looks: creating a run means **materializing an execution per case in scope, each
  pinned to that case's current version**, which is this module's second crossing of the seam and the
  first one that *writes* a `case_version_id`.
- **No live browser pass.** Same gap §34 and §35 recorded. The endpoints are covered against real
  Postgres, but the SPA was not pointed at `docker compose` — and it cannot usefully be until runs can
  be created, per the item above.
- **`release_sort_key` is not mapped.** The column is computed on write from `release_version`, and
  this slice performs no write that could compute it. Mapping a column nothing here maintains is how
  it comes to disagree with the field it derives from — silently, because "1.10" then sorts before
  "1.9" and every latest-release query is quietly wrong (§6q). It joins the entity in the slice that
  creates runs.
- **No evidence, no defect creation.** `evidence:attach` and `defect:create` have no endpoint;
  `evidence` is served as `[]`, which is exactly true because nothing can upload one. `defect_link`
  **is** read, through the one native query in `TestExecutionRepository`, because an ingested run
  could carry links this module did not make and answering `[]` unconditionally would be a claim
  rather than a lookup.
- **`UserRefView` is now declared twice** — here and in `repository` — because neither module may
  import the other and the shape belongs to the contract. That makes **three** copies of a wire shape
  in the tree (with `ApiProblem` / `ProblemBody`) and the count is recorded so the eventual `shared`
  module knows what it reconciles. §34's reasoning applies unchanged: the module is a decision, not
  an import.
- **No frontend tests for the two changes above.** Lane C held the frontend test surface this run,
  and `useSubmitResult`'s header and the amend gate are both good targets — the gate especially,
  since it is a pure function of one field.
- **`V14` was pre-assigned to this lane and is not used.** Every table this slice needs exists in
  `V1`; nothing here changes the schema. It is free for whoever needs it next.

### For the integrator

- **Contract changes are additive except three `required` widenings**, which only ever remove a
  consumer's need to null-check. `schema.d.ts` was regenerated once, by this lane.
- **Lane B's `POST`/`DELETE /role-assignments` (`a8e4b50`) was not touched.**
- `ModuleStructureTest`'s pinned module list now names seven modules. A lane adding an eighth edits
  the same three lines.
