# §42. Backend M2, slice 2 + frontend — plan and run creation, and the write half of the pin (2026-08-01)

Run through the **`be-java`** skill (Mode B — extend) and then the **`fe-react`** skill (Mode B,
re-entering Phase 2). **Lane A of a three-lane parallel run, and the contract holder for it.**

Closes the blocker §38, §39 and §41 each name: **the live browser pass could not happen because a
run could not be created**, and creating a run is what materialises the executions to record
against. Every screen in the `execution` family existed; the one that matters most — recording a
result — was unreachable from the UI, because nothing in the product could produce a thing to
record against.

| Path | Purpose |
|---|---|
| `docs/specs/openapi.yaml` | The two POSTs' refusals, their `Idempotency-Key`, their 200-replay and their `Location`. |
| `V14__plan_and_run_creation_idempotency.sql` | Two columns, two partial unique indexes. **`V14` was free since §38.** |
| `execution/CaseFacts` | **Four new methods on the one crossing class** — the scope expansion as ids, two liveness checks, and `currentVersionIds`: *the write half of the pin*. |
| `execution/PlanEdit · PlanWriteRequest · PlanCreateService` | Validation, the wire shape, the orchestration. |
| `execution/PlanScopeItem` (+ repository) | The scope rows. Two ids into `repository` and it resolves neither. |
| `execution/RunEdit · RunWriteRequest · RunCreateService` | Same, plus the case-set resolution that is most of what validating a run means. |
| `execution/ReleaseSortKey` | `%05d.%05d.%05d`. §38 deferred it to "the slice that creates runs"; this is it. |
| `execution/TestPlan#create · TestRun#create · TestExecution#materialise` | Three factories where there were none. |
| `PlanCreateEndpointTest` · `RunCreateEndpointTest` | **New, 51 tests** over HTTP against real Postgres with RLS. |
| `features/{plans,runs}/*CreateDialog.tsx` + the two list pages | The gated affordances. |
| `api/queries/{plans,runs}.ts` | Two dead hooks get their first callers, and their `Idempotency-Key`. |
| `mocks/handlers/{plans,runs}.ts` · `mocks/db/executions.ts` · `db/store.ts` | The mock stops faking a checklist. |
| `features/runs/ExecutionPage.tsx` | `useUnsavedWork`, the residual §41 lists. |

---

### The orientation pass found one thing the plan did not anticipate, and it changed the seam

The plan said "extend the existing single crossing class rather than adding a second seam". What it
did not say is **how many crossings creating a run needs**, and the answer is four where §38 had two:

| Crossing | Why the create needs it |
|---|---|
| `caseIdsInPlanScope` | The expansion as *members*, not as a count. A run needs the set. |
| `liveSuiteIds` / `liveCaseIds` | Validation. See below — this is the one that is not obvious. |
| `currentVersionIds` | **The pin, written.** |

`liveSuiteIds` is the finding. `plan_scope_item.suite_id` and `case_id` are foreign keys, so the
database refuses an id naming nothing; RLS refuses another company's row. **Neither refuses a suite
or a case from a sibling project in the caller's own company** — and a Test Lead who holds two
projects can produce that id honestly, by copying it from the other tab. The result is a plan whose
scope silently spans two products and whose `caseCount` counts cases that do not appear on this
project's own case list. It is the §38 "second gate on a two-segment path" defect, arriving through
a request body instead of a URL, and no access check catches it because the caller genuinely holds
both projects. One test per entity, and both would pass against every cross-company assertion in the
suite while this leaked.

Putting all four in `CaseFacts` rather than in the two new services was a decision, not inertia: the
one property that makes this seam reviewable is that a reader can find every crossing by opening one
file, and the most tempting reason to end that property is that the new query is short.

---

### The pin is resolved once, at creation, and only two tests together say so

This is the whole slice. `CaseFacts.currentVersionIds` answers what version each case is on *at this
instant*, and every materialised execution stores it. From then on the case may be edited freely: the
execution keeps naming the version it was created against, and `pinnedCases` reads that version's
snapshot on every render.

The alternative — resolving the version when the execution is *read*, or when the result is recorded
— is the one that looks equivalent and is not. A tester who opens a run on Monday, is shown the
steps, and records a verdict on Wednesday must be recording it against Monday's text. Under late
resolution an edit on Tuesday silently changes what they agreed to.

**Neither test alone pins the behaviour, and that is worth stating because writing only one is the
natural mistake:**

| Test | What it forces |
|---|---|
| Edit the case to v2, *then* create a run → the execution must be **v2** | A create that hard-coded v1, or that pinned the version the plan was built against |
| Create the run, *then* edit to v2 → the execution must stay **v1** | A render-time lookup, which passes the first test |

A render-time implementation passes the first and fails the second. A create-time-but-wrong-version
implementation fails the first. Both are green only if the pin is resolved at creation and stored.

---

### `release_sort_key` joins the entity here, exactly as §38 said it would

§38 deliberately left the column unmapped: *"mapping a column nothing here maintains is how it comes
to disagree with the field it derives from"*, and that slice performed no write that could compute
it. This one does, so `ReleaseSortKey.of` computes it in **the one statement that also sets
`release_version`** — on the run and, denormalised, on every execution. A settable pair is a pair
that drifts, so both fields are `updatable = false`.

The failure being prevented is V1's own comment and §6q's: text collation puts `"1.10"` before
`"1.9"`, so every latest-release query answers about a release from three months ago, with no error
anywhere and a number on the screen that looks exactly like the right one. One test asserts the
ordering *and* asserts that the raw strings compare the wrong way round, so the reason the column
exists is in the test rather than only in a comment.

Two fallbacks, both stated rather than defaulted:

- **A non-semver release falls back to itself** (`nightly-2026-08-01`), which is V1's prescription.
  Refusing would make dated builds unrecordable; storing null would put every non-semver run in one
  unordered bucket that `test_run_release_idx` leads on.
- **No release means no sort key.** Not `00000.00000.00000`, which would file every ad-hoc run under
  version zero, ahead of the real ones.

---

### The permissions, derived from the roles spec rather than copied from the neighbouring endpoint

Roles spec §A4, table rows `plan:create` and `run:create`. **They are deliberately different, and
the difference is the interesting part.**

| | Instance Admin | Company Admin | Test Lead | Test Engineer | Automation Service | Viewer |
|---|---|---|---|---|---|---|
| `plan:create` | ✓ | ✓ | ✓ | ✓ | — | — |
| `run:create` | ✓ | ✓ | ✓ | ✓ | **✓** | — |

- **Viewer holds neither**, and that is the gate that matters. Every *read* in this module asks
  `project:read`, which all six roles hold — so a create gated by copy-paste from `PlanService` is
  one the client's own read-only stakeholder may perform, and it typechecks. One test per endpoint
  reads the list as a Viewer first, so the 403 is not passing against a route that does not exist.
- **Automation Service holds `run:create` and not `plan:create`.** §A6's whole justification for the
  smallest role in the product is that a leaked CI token can post results and break nothing; it opens
  the run it is about to post into, so `run:create` is the widest write gate in the product. What
  bounds it is worth writing down: a run creates nothing anybody authored. It names cases that
  already exist, pins versions that already exist, and produces rows whose only content is "not yet
  run". It cannot change a case, a plan or a verdict. The blast radius is a project with extra empty
  runs in it — noise, not damage. A *plan* is authoring: it declares what the team agreed to test.
  A gate written by analogy with `plan:create` breaks CI ingestion before it is built; one written by
  analogy with `result:submit` hands a CI token the plan catalogue.

Both gates are `ProjectService.writeGranted` — PROJECT scope, unlike §34's `project:create`, because
here there *is* a target project to ask about.

**Permission before payload**, one test each. A 400 to a caller who may not create anything reports
whether a plan id or a suite id names a live row — a probe for the company's object graph handed to
exactly the person just refused. It is also structural: validation runs inside the callback
`writeGranted` invokes only when allowed.

---

### An empty *plan* is fine and an empty *run* is refused, which looks inconsistent and is not

A plan with no scope items is an ordinary intermediate state: somebody names the plan and the
milestone, then adds folders. `caseCount` answers 0 and the plan page says so.

A run with nothing to execute is refused with a 400. **An empty run renders as 0 of 0, 100%,
complete** — it looks finished and never started, every count on it is zero, and the person who
created it would have to notice the *absence* of rows to know. The refusal lands on `planId` when
the scope came from a plan and on `caseIds` otherwise, because a message on the wrong field is one
the form has nowhere to put.

The same reasoning drives the one refusal that should be unreachable: a case with **no version row**
cannot be materialised, because `case_version_id` is `NOT NULL` and an execution that does not name
what it ran is the one thing this module exists to prevent. The honest options were to refuse the
whole create or to materialise a shorter checklist and say nothing, and the second is how a run
silently omits cases. Every case authored since §29 has a v1 written with it, so this is unreachable
through the product; it is written down because a pre-versioning case would otherwise make run
creation fail with a foreign-key violation instead of a sentence.

---

### `caseIds` narrows the plan's scope; it is never unioned with it

A body carrying both a plan and a case list means *"this run is against that plan, and here is the
subset we are doing this pass"* — a real thing to want during a stabilisation. Unioning them would
silently add back the cases somebody explicitly narrowed away. The run still records its `planId`, so
the narrowing reads as a run shorter than its plan rather than as a lost association, and a test
asserts both halves.

---

### Idempotency: the same mechanism as §29, and the reason it matters more here

`V11`'s header ends with the honest cost of the column-not-a-table decision: *"a second create
endpoint (a suite, a plan) adds its own column rather than reusing a mechanism… the generalisation
can wait for the second, which will then have two real shapes to generalise from."*

**This is that second, and it arrives as two at once — and the generalisation is still not taken.**
The reason has changed from "wait for evidence" to a decision, recorded in `V14`'s header: a general
`idempotent_request` table needs its own `company_id`, its own FORCE RLS policy, an ERD §0.1
exception review, and a retention rule for rows whose subject has been deleted — and it would have to
store the *response*, because a general mechanism cannot re-render one it does not understand. A
stored response is a second copy of client content with different retention that goes stale the
moment the row it describes is edited. The column form re-renders from the live row through the same
assembler the `GET` uses, so a replay and a reload cannot disagree.

**Why a run needs it more than a case does.** A duplicated case is one extra row somebody deletes.
A duplicated *run* is a duplicated **checklist**: the same tester is shown the same twenty cases
twice, records half their results against one run and half against the other, and both runs then
report a pass rate over a denominator nobody intended. Neither run is wrong on its own, which is why
nothing would flag it. The test asserts the execution count, not just the run count.

**Considered and rejected: keying a run on `(plan_id, name)`.** `test_run.plan_id` is nullable (ad-hoc
runs), and two runs of the same plan with the same name are legitimate — "Regression 4.3.0" run twice
after a hotfix is normal, not a mistake to refuse. There is no natural key here, which is exactly the
condition that makes an explicit idempotency key the right mechanism rather than a workaround for a
missing constraint.

Scope, partiality and column order are all `V11`'s, for `V11`'s reasons. The lookup names exactly the
constraint's three columns; a narrower one is a validated request that 500s on insert. Both lookups
deliberately **omit `deleted_at`**: a retry of a create whose row has since been deleted must still
be recognised as that intent, or the retry resurrects something somebody removed.

---

### The contract, and the hygiene item §41 called three-for-three

Both POSTs declared **only a `201`**. Every 4xx is now stated with the reason it is that code, plus
the `200` replay, the `Idempotency-Key` parameter and the `Location` header (absent on the replay —
nothing was created by *that* request, and a 200 carrying one reads as a create to anything following
the header rather than the status).

Audited the rest of the `execution` family rather than assuming: **every other path already declares
its refusals**, because §38 added them. The `$ref`-over-a-nullable-column check is likewise clean in
this family — §38's `TestExecutionSummary.assignee`/`.executedBy` fix stands and nothing new was
introduced. So this slice is the first time the habit did *not* have to be corrected, which is the
first data point against §41's three-for-three rather than a fourth for it. **The two computed checks
§41 proposes are still not built** — this was a reading, and §8 says a catalogue-wide invariant wants
a script.

`TestPlanInput` and `TestRunInput` were already in the contract and are implemented as declared, with
one refusal recorded rather than papered over: `TestRunInput.assigneeId` is honoured (it lands on
every materialised execution) and `TestPlanInput.caseIds` is honoured by the server but **not offered
by the dialog** — see below.

---

### The frontend half — two dead hooks get their first callers

`useCreatePlan` and `useCreateRun` have existed since Phase 1 with **zero call sites**, which is the
dead-hook trap §28 and §29 each caught one slice apart. Both now have one, and both gained the
`Idempotency-Key` the server reads: one key per *mounted dialog*, held in a `useRef`, because a key
generated inside `mutationFn` is a fresh one per attempt and makes the header decorative.

**The plan dialog offers folders, not cases, and that is a product decision.** A scope item may name
either and the server accepts both. A suite item means *the folder and everything beneath it,
whenever the question is asked* — so a plan scoped to folders stays correct as cases are authored
into it, which is what somebody building a release plan means. Picking two hundred cases individually
produces a plan that is wrong by the next case written and that nobody notices is wrong. Case-level
scope belongs with an "add selected cases to a plan" affordance on the case grid, where the selection
already exists; reproducing it here would be a second, worse case picker.

**The run dialog requires a plan, and the contract does not.** Same argument from the other end: an
ad-hoc `caseIds` run belongs to the case grid's selection. A run created by hand-reconstructing a
plan's scope is one nobody can later ask "against which plan?" of.

Both write affordances are gated **disable-don't-hide with a reason** (§31), at PROJECT scope through
the existing `PermissionGate`. **`useCan.ts` and `PermissionGate.tsx` are untouched** — §37's
near-miss was a lane changing those while another asserted on them, and neither needed widening here:
`plan:create` and `run:create` were already in `lib/permissions.ts`'s catalogue with labels.

#### The mock stopped faking a checklist, and that was a real divergence

`POST /runs` in MSW used to invent "the first twelve cases in the project" regardless of what was
asked for, and **never wrote the execution rows at all** — the count said twelve and `runExecutions`
reverse-engineered a plausible twelve, complete with verdicts, on first read. That is precisely the
mock-proves-a-property-live-mode-lacks divergence §29 (`Idempotency-Key`) and §38 (`If-Match` in the
body) each found, and it would have hidden the one behaviour this slice exists to deliver.

Now: the scope resolves the same way the server's does (explicit `caseIds`, else the plan's expanded
scope, never the union), a run that would materialise nothing is refused, the created run is
`NOT_STARTED` with a null `startedAt`, and the executions are written with **no verdicts, no
assignee and no executor** — the state that made §38's person-lookup a 500. `db.planScope` remembers
the suite ids because the wire shape carries `caseCount` and not the scope, and without it a
mock-created plan has an empty scope and a run started from it is an empty checklist.

Both handlers refuse from the same `permissionsOn` the `PermissionGate` reads, so the disabled button
and the refused write cannot disagree, and both honour `Idempotency-Key`.

#### `useUnsavedWork` on the execution screen — the residual §41 lists

This form has no `formState.isDirty`; it is four `useState` buffers. So "dirty" is **derived** — a
step verdict differing from the row's, a typed comment, an unticked precondition, or a submit in
flight — rather than a flag set in each handler, which is what keeps a fifth buffer from being added
later and silently not counting.

It matters more here than on the case editor: an execution is filled in *while running the test*. The
tester has been through fifteen steps in another window and the marks on this screen are the only
record of what happened; losing them to a deploy-skew reload means running the test again, not
retyping a title. §41 records that lane C checked the *board* for the same treatment and found it
correctly does not need one — its writes are immediate single-field mutations with no unsaved buffer.
This screen is the opposite shape.

---

### Two defects the first full test run found, and only one of them is this lane's

**1. A membership check that was not one — fixed here.** `RunEdit` validates `assigneeId`, and the
first implementation asked `ExecutionPeople.resolve`, which is the class this module already uses to
turn user ids into names. It answered *yes* for a user belonging to **another client entirely**.

The reason is worth keeping: `app_user` is an **instance-level** table with no `company_id` — it is
on ERD §0.1's exception list precisely because one person may work with several clients. So `resolve`
finding any account on the instance is *correct for its own job*: §38's header says it must still
name somebody since offboarded from this company, or a past verdict loses its author. Used as a
membership check it is wrong, and the two calls are one word apart.

`ExecutionPeople.isActiveMember` is the question asked correctly —
`existsByCompanyIdAndUserIdAndStatus(…, ACTIVE)`, so an **`INVITED`** membership conveys nothing
either, which is that method's own header's rule: an unaccepted invitation must not become a way to
be handed a client's work. Had it shipped, a run could have assigned a client's testing to a stranger
and put their name on this company's screens — and `test_execution.assignee_id`'s foreign key points
at `app_user`, so nothing below would have refused it.

**2. `run:create` is unreachable for the role whose whole purpose it is — NOT fixed here.**
Roles spec §A6 gives Automation Service exactly five permissions and `V3` line 252 seeds precisely
those. **`project:read` is not among them.** But `ProjectService.writeGranted` runs the read gate
first and `ProjectAccess.canView` *is* "holds `project:read`" — so every project-scoped path answers
this role **404 before its permission is asked**, including `run:create` here and `result:submit`
back in §38. §A6's own justification — *"a leaked CI token can post results and break nothing"* —
describes capabilities the role cannot currently reach at all.

**This is §39's `company:read` finding in a second place**: a spec-vs-code disagreement rather than a
bug, with two candidate repairs and no obviously right one —

- add `project:read` to §A6's grant (widens the deliberately smallest role in the product, and §A6's
  "everything else denied" line was written to resist exactly that), or
- let the write gate resolve visibility from the write permission itself (changes `writeGranted` for
  every module, and re-opens the 403-vs-404 ordering argument its own comment settles).

Choosing is a decision, and **the repair lives in `V3__seed_permissions_and_roles.sql`, which lane B
held this run.** So it is written up rather than fixed — what lane A did correctly last run. It is
pinned by a test that asserts the **404** and whose failure message sends the next reader to its
javadoc: `theRobotCannotReachRunCreate`. A test asserting the wrong-but-current behaviour is only
defensible when it says so loudly, which is why that one is the longest javadoc in the class.

### The build stalled for twenty minutes and it was the sync daemon again

`./gradlew build` sat at `> Task :test` twice, with no test worker forked and no Testcontainers
container — 12 minutes, then 20. `jstack` put the execution worker in `DefaultStreamHasher` reading
a file, and `lsof` named it: `backend/build/test-results/test/TEST-…​ 2.xml`. **105 sync-conflict
copies** had accumulated in the test-results directory, and Gradle was hashing them as task inputs on
a cold daemon.

CLAUDE.md's warning is about `* 2.*` files being *hard compile errors*. This is a second failure mode
for the same cause and it looks nothing like the first: **no error, no output, just a build that
never finishes**, which reads as a hung Docker or a Gradle deadlock. `find build -name "* 2.*"
-delete` and the suite ran in 2m19s. Worth adding to the check the handoff already prescribes —
`find . -name "* 2.*"` before trusting a build failure *or a build that is not failing fast enough*.

One side effect to declare: clearing the contention needed `./gradlew --stop`, which stops **every**
daemon on the machine including the other lanes'. Gradle restarts them automatically on the next
invocation, so nothing is lost, but a concurrent lane will have seen one slow build.

### Mutation evidence

**Frontend — three mutations, three killed.**

| Mutation | Result |
|---|---|
| `applyServerError` drops field errors with no matching input (`caseIds`) | **Killed**, 1 test |
| "Start run" enabled in a project with no plans | **Killed**, 1 test |
| The dialog resolves the scope itself and sends `caseIds` | **Killed**, 1 test |

**Backend — eleven mutations, eleven killed.** Each run against
`PlanCreateEndpointTest` + `RunCreateEndpointTest` only (63 tests), so the count is the number of
tests in *these two classes* that can see the defect.

| Mutation | Killed by |
|---|---|
| `plan:create` gate asks `project:read` (a Viewer may create) | **2** |
| Key allocated **before** validation, so a typo burns a number | **7** |
| The pin resolved from `version_no = 1` instead of `current_version_no` | **1** |
| Run idempotency lookup skipped entirely | **2** |
| `release_sort_key` left null while the release is set | **3** |
| A created plan lands `ACTIVE` instead of `DRAFT` | **1** |
| `caseIds` **unioned** with the plan's scope instead of narrowing it | **4** |
| A run with an empty scope accepted | **2** |
| `liveSuiteIds` / `liveCaseIds` drop the project predicate | **2** |
| Assignee checked with `resolve()` — *the defect as first shipped* | **1** |
| Plan scope stops expanding the suite subtree | **6** |

Two are worth a note. The **pin** mutation is killed by exactly one test, which is why the pair of
pin tests had to be written deliberately rather than one of them: the render-time variant kills
neither, and only the second test catches it. The **assignee** mutation restores the code this slice
actually shipped first and was caught by a real test run — it is in the table so the fix is
demonstrably load-bearing rather than defensive.

---

### Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL in 1m 49s
           796 tests, 0 failures, 0 skipped   (733 + 63)
frontend:  npm run build   → ✓ built, entry chunk 249.61 kB / 78.83 kB gzip
           npm run lint    → oxlint, exit 0, no output
           npm run test:run→ Test Files 13 passed (13) · Tests 234 passed (234)
           find "* 2.*"    → nothing outside node_modules / build
```

### The live browser pass — it happened, and it found two things

**Three consecutive runs ended without one** (§34, §35, §38, §41), each for the same reason: a run
could not be created. This one closes that, and it is recorded in full because §40's finding stands
— its four majors were **all** found by driving the app and **none** by reading it.

`docker compose up --build` against the **existing volume**, which made it a better test than a
fresh one: Flyway applied `V12`, `V13` and **`V14` in order onto an already-migrated database**
(*"Successfully applied 3 migrations… now at version v14"*), health `UP`. Then
`provision-company --slug=lanea`, and the SPA in **live** mode (`VITE_API_MODE=live`, vite killed
and `node_modules/.vite` cleared first, per CLAUDE.md).

| Step | Result |
|---|---|
| New plan → the dialog | The **real suite tree**: `TS-1 Payments · 4 cases`, `TS-3 Refunds · 0 cases` |
| Create with `TS-1` ticked | **TP-1**, `draft`, milestone `GA`, description round-tripped |
| The plans list | TP-1 with **Cases 4** — the suite subtree expanded, live |
| New run → the plan picker | `TP-1 · Release 4.3 regression · 4 cases` |
| Start run | **TR-1**, `not started`, `Default`, `Release 4.3.0 build 4471`, linked to TP-1 |
| The run's checklist | **4 executions**, `0.0% executed, of 4 in scope`, Untested 4 |
| The pin, on screen | `TC-1 … v2`, `TC-2 v1`, `TC-3 v1`, `TC-4 v2` — **each case's own current version**, mixed, which is the whole point |
| Open TC-1 | The pinned **v2** step text, not the live case's |
| Record Passed | *"Recorded Passed for TC-1"*, counts → `Passed 1 / Untested 3`, `100.0% passed, of 1 executed`, `25.0% executed, of 4 in scope` |
| Console | **No errors** |

And the rows read back through the `tcms_app` role, past the API — the first query with no company
set returned **zero rows**, which is RLS failing closed exactly as designed:

```
TR-1 | NOT_STARTED | rel=4.3.0 | sortkey=00004.00003.00000 | build=4471 | started_at_null=true | idem=true | source=MANUAL
TP-1 | DRAFT | v1 | scope_items=1 | entry_null=true | exit_null=true | idem=true
TC-1 pinned v2 (case is at v2) | status=PASSED | rel=4.3.0 | sortkey=00004.00003.00000
TC-2 pinned v1 (case is at v1) | status=(none)  | …   TC-3 v1 | TC-4 v2
audit: plan.created {"key":"TP-1","scopeItems":1}
       run.created  {"key":"TR-1","planId":"2112aca5…","executions":4}
       execution.recorded {"to":"Passed","case":"TC-1","from":null}
```

`release_sort_key` computed on write and denormalised onto **every** execution; `entry_criteria`
and `exit_criteria` **NULL** rather than `[]`; the idempotency key stored on both rows.

#### The two defects the pass found, and neither gate could have

**1. A successful create that told the user it had failed.** `onCreated` navigated to
`/plans/:planId` — which is the plan **statistics** route, and that endpoint is deliberately
unimplemented (§38). One second after TP-1 was created, the screen showed a red *"Something didn't
load"* toast over a *"Not found"* panel. The create had worked. Every gate was green, because the
route is real, the types are right and the failing fetch is a different endpoint's. Now it stays on
the list, where the new plan is the first row with its expanded case count — with a comment saying
to point it back the day statistics is served.

**2. `Started — · Still running` on a run nobody has opened.** `RunDetailPage` had two states where
there are three and had never rendered the un-started one: every fixture was `IN_PROGRESS` with a
`startedAt`, *because until this slice no run could be created*. So the first live run read two
contradictory false claims. Now: **"Not started yet"**, and "Still running" only once a run actually
has. This is the shape worth remembering — **a slice that makes a new state reachable inherits every
screen that never rendered it**, and no test written before the state existed can fail.

Both fixed in their own commit, so the diff shows what driving the app bought.

---

### Deliberately not done

- **No plan or run EDIT, and no delete.** `plan:edit`, `plan:delete` and `run:close` are separate
  permissions held by fewer roles, and `TestPlan`/`TestRun` still have no mutators — deliberately, so
  a "just add a setter" edit cannot land without the gate that belongs to it. Closing a run in
  particular needs a decision about what a closed run's executions accept.
- **No `entry_criteria` / `exit_criteria` on create.** `TestPlanInput` does not carry them and the
  columns are written **NULL**, not `[]` — the two are different, and a `plan:edit` slice has to tell
  "nobody has decided" from "there are deliberately none" apart. `PlanCriteria` renders null as `[]`
  for the wire either way, so the SPA sees no difference.
- **No `test_plan_version` row.** A plan version captures its *scope* (V1's comment), and there is no
  plan-history endpoint and no consumer — writing one now would be §29's dead-hook trap in the
  schema. It belongs with `plan:edit`, which is the first thing that can produce a v2.
- **`GET …/plans/{planId}/statistics` stays on MSW**, unchanged from §38: `bySuite` needs the live
  suite tree, `coveragePercent` a repository-wide denominator, and `trend` a `reporting` module that
  does not exist.
- **The Automation Service's "quarantine folder only" qualifier is still unenforced** (§29). It does
  not bite here — `run:create` carries no folder restriction in the spec — but it is the same open
  item and it is owed to the same ingestion slice.
- **`created_by` is mapped on plan and run and unread.** Unlike §28's and §34's gaps this one is
  written rather than ignored, because the factory had to set *something* and a column that exists
  and is always null is worse than one that is populated and unread. Nothing serves it; the actor is
  on the `audit_event` row.
- **No `V16`.** It was pre-assigned as a second migration for this lane and is not used. **`V15` was
  not touched** — it is lane B's.
- **The plan detail screen is still the statistics screen.** `/plans/:planId` renders
  `PlanStatisticsPage`, whose endpoint is unimplemented, so a plan's *own* page is a header over an
  error panel. This slice routed around it rather than fixing it — a real plan detail screen
  (scope, criteria, its runs) is a slice, not a redirect, and it wants the statistics endpoint or a
  decision to render without it.
- **No idempotency replay driven through the UI.** Same limitation §29 recorded: TanStack Query
  resolves the mutation before a second submit is possible, so the duplicate-intent case cannot be
  staged from outside. It is covered by backend tests, including that a replayed run create
  materialises **no second checklist**.

### For the integrator

- **Contract changes are additive, and confined to exactly two paths.** The 4xx declarations added
  are on **`POST /projects/{projectId}/plans`** and **`POST /projects/{projectId}/runs`** — and
  nowhere else. Each gained `400`, `403`, `404`, a `200` replay, the `Idempotency-Key` parameter and
  a `Location` header on its `201`. No existing response or schema changed shape. `schema.d.ts` was
  regenerated once, by this lane.
- **The nullable-`$ref` audit found nothing to fix.** Every `$ref` in the `execution` family that
  points at a nullable column already uses `allOf` + `nullable: true` — §38 corrected
  `TestExecutionSummary.assignee` / `.executedBy` and §41 corrected `RoleAssignment.user`. Nothing
  new was introduced. **This was a reading, not a computed check**, so it is worth exactly what §8
  says a reading is worth; the two mechanical checks §41 proposes are still unbuilt.
- **The red-when-repaired test for the Automation Service gap is
  `com.tcms.execution.RunCreateEndpointTest$Gate.theRobotCannotReachRunCreate`** (nested class
  `Gate`). It asserts **404** and its failure message names the repair. When `V15` gives the role
  `project:read`, this goes red with *"If this is now 201, the project:read gap has been
  repaired"* — change the expectation to `201` and delete the finding from this file. The same gap
  makes `result:submit` unreachable for that role, which §38's tests could not have shown because
  they used only LEAD and ENGINEER.
- **Lane B's seventh built-in role (`Company Member`) is compatible with everything here.** Nothing
  this slice adds enumerates the `role` table or assumes six rows: the two new test classes name
  `Role.TEST_LEAD_ID` / `TEST_ENGINEER_ID` / `VIEWER_ID` / `AUTOMATION_SERVICE_ID` individually,
  and `mocks/handlers/shared.ts#permissionsOn` looks a role up by name rather than counting. The
  contract's `Role.name` enum is untouched. **One thing to check at integration**: both new test
  classes build their fixtures by calling `CompanyMembership.invite(...).accept(...)` directly
  rather than going through the invitation service — so if `V15` seeds a COMPANY-scope
  `Company Member` grant *on acceptance*, these fixtures will not get one and the `NO_GRANT`
  persona stays as intended. If it is seeded by a migration over existing memberships instead,
  re-check that `NO_GRANT` still holds nothing.
- **`V14` is used** (it was free since §38). `V15` is lane B's and untouched; `V16` unused.
- **Nothing under `identity/`, `provisioning/` or `V3` was touched.** No defect was found in another
  lane's files this run.
- **`useCan.ts`, `PermissionGate.tsx`, `app/queryClient.ts`, `app/Providers.tsx`, `auth/**` and
  `api/queries/cases.ts` are untouched.** `api/queries/index.ts` needed no edit (both hooks were
  already exported); `mocks/db/index.ts` and `mocks/db/store.ts` each gained appended lines.
- **`ExecutionPage.tsx` gained one import and one derived-state block** — lane C reviewed this file
  last run and may have an opinion about the file, not this block.
