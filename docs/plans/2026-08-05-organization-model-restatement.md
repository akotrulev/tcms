# The organization model, restated — and the two slices it adds (§98, §99)

**Date:** 2026-08-05
**Source:** the user's restatement of the product's organizational model, 2026-08-05, and the four
decisions taken from it.
**Status:** planned, not started. **§97 does not depend on either slice** — see §5.

---

## 1. The model, in the user's words

> A test case is made up of test steps. Test step is the smallest piece. A test case can have tags,
> those point out which features or functional areas the test case validates. A test suite is a
> collection of test cases, where those cases can either be assigned to a suite by calling its id
> directly OR as part of a set of test cases with the same tag. A test case can have many tags and
> be used in many suites, it's just a way to organize them. A test execution is derived either from
> a test suite, where the cases get an execution status (only in the scope of the execution) AND/OR
> by adding test cases by id/name.

## 2. Most of this is already shipped

| Statement | Where it lives | State |
|---|---|---|
| A case is made of steps; the step is the smallest piece | `test_case_step`; §94's editor | ✅ |
| A case has tags naming features / functional areas | `case_tag`; §96 made them authorable | ✅ |
| A suite collects cases **by id** | `suite_case` (§96) | ✅ |
| A suite collects cases **by tag** | `suite_tag_rule` (§96) | ✅ |
| A case has many tags and is used in many suites | §96's whole subject | ✅ |
| Execution status is scoped to the execution **only** | `test_execution.status_id`, `uq_execution_run_case` | ✅ |

**The status point is worth stating because it is the one that is easy to get wrong and is already
right**: there is one `test_execution` row per (run, case), the status hangs off *that* row, and
`test_case` has no status column at all. The same case is `PASSED` in one run and `FAILED` in
another with no contradiction anywhere, and no history is rewritten when it is run again.

## 3. Decisions taken with the user (2026-08-05)

- **M1 — the filing arm stays.** `test_case.suite_id` remains as the third membership arm beside
  `suite_case` and `suite_tag_rule`. §96 as shipped stands. The column is nullable, so the model
  above is **already fully expressible** — a case with no `suite_id`, claimed by suites through ids
  and tags — and filing is an optional convenience rather than a requirement. Nothing to build.
- **M2 — suites become FLAT.** `suite.parent_id` and `suite.path LTREE` go. A suite is a bare named
  collection, not a folder in a tree. **§98.**
- **M3 — a run is scoped by suites, cases AND tags, with no plan required.** `TestRunInput` gains
  `suiteIds` and `tags` beside `caseIds`; `test_run.plan_id` is already nullable, so a plan-less run
  needs no migration. **§99.**
- ~~**M4 — the plan's sub-feature sections stay labels** (§97's D7, reaffirmed). They are not suites.~~
  → **superseded 2026-08-05 by M5**, later the same day, when the model was validated against TestNG.
- **M5 — the plan's sub-feature sections are LIVE selectors, and are still not suites.** §97's D7′.
  TestNG's `<test>` block is exactly this level — a named subdivision of a suite carrying its own
  `<classes>` list and its own `<groups>` filter, re-resolved on every run. Sections stay
  **plan-local** (they are not promoted to suites), and they stop snapshotting.
- **M6 — an EXCLUDE arm is a known gap, recorded and not built.** TestNG's
  `<exclude name="flaky"/>` has no equivalent anywhere in this product: `suite_tag_rule` is
  union-only (V31 settles OR-vs-AND and never raises negation), a section's tag arm inherits that,
  and run scoping under M3 will too. *"Everything tagged smoke except the flaky ones"* is common and
  cannot be expressed. Named here so the next reader finds a decision rather than an oversight;
  the shape when it is built is a flag on `suite_tag_rule` and a subtraction applied **after** all
  arms resolve.

### Taken after the Zephyr clarification (2026-08-05, same day)

The user restated the plan/execution split against Zephyr: *"A test plan is just an outline of what
should be tested for an epic to be considered complete, it has draft and approved states. Running a
test and writing down the result is done in a test execution … within a test cycle."*

**Zephyr's split is already what is built** — see §3b — so the clarification produced no structural
change. It produced three corrections.

- **M7 — the plan lifecycle drops `ACTIVE` and `CLOSED`.** `DRAFT → IN_REVIEW → APPROVED` is the
  whole ladder.
  **The argument is the user's own, applied precisely.** `PlanStatusService` writes `plan.started`,
  `plan.reopened` and `plan.closed` and fills `closed_at` — those describe *a plan being run*, which
  is the execution's business, and `test_run` **already has** `NOT_STARTED → IN_PROGRESS →
  COMPLETED → ABORTED`. Plan-`ACTIVE` duplicates run-`IN_PROGRESS` one level up.
  **`IN_REVIEW` stays, and it is not a third state the author manages** — it is the transit between
  the two the user named. Cutting it would remove plan review from the product: `review.subject_type`
  admits `PLAN`, and §77's `reviewEnabledPlan`, §79's comments, §80's assignment and §81's reviewer
  inbox all operate on it. It would also reopen what `PlanLifecycle` closed on §6g's authority —
  *"the state machine has exactly one gate"* — by making approval reachable with no gate before it.
- **M8 — the epic is the linked business-requirements page.** §97's D4′ already links a `DocPage`;
  that page *is* the epic's specification and the plan needs no separate identifier. **Nothing to
  build**, and the named cost: a plan whose epic lives in a tracker and not in the docs space has
  nowhere to say so. An external issue reference on `test_plan` is the shape if that changes.
- **M9 — `WIP` joins the default execution statuses.** A seventh seeded `case_status`
  (`is_final=false`, `counts_as_pass=false`, like `Retest`), matching Zephyr. The six seeded today —
  Passed, Failed, Blocked, Skipped, Retest, Untested — have no value meaning *"somebody is running
  this right now"*, which is what a lead wants halfway through a long manual cycle. `case_status` was
  always per-project configurable, so this changes the **default seed** and not the mechanism;
  existing companies top up by re-running `provision-company`, which is idempotent by design and is
  documented as the supported way to take on a release's new vocabulary.

## 3b. Validation against Zephyr (2026-08-05)

| Zephyr | TCMS | |
|---|---|---|
| Test cycle | `test_run` | ✅ |
| Test execution — a case run *within* a cycle | `test_execution` (`run_id` + `case_id`, `uq_execution_run_case`) | ✅ |
| Pass / Fail / Blocked | seeded `case_status`, plus Skipped / Retest / Untested | ✅ |
| WIP | — | → **M9** |
| Results never touch the case definition | `test_case` has no status column | ✅ |

**The clarification confirms §97 rather than changing it**: the plan stays an outline, and its
Progress tab is *derived* from runs — nothing writes a result to a plan. "Is this plan done?" is
answered by the runs, which is where the user places it.

## 3c. M7 is LOSSY, and the loss is bounded by the audit log

Reducing the CHECK constraint means every existing `ACTIVE` and `CLOSED` plan must become something,
and both map to `APPROVED` — a plan that was started or finished was, by construction, past drafting.
`closed_at` is dropped with the state that filled it.

**The down-migration cannot restore which plans were closed**, and this product has a real
deployment (§58), so that is a live concern rather than a theoretical one. What bounds it: the audit
log already carries `plan.started`, `plan.reopened` and `plan.closed` events, so *the history
survives where history belongs* even though the current-state column stops carrying it. The
migration must say so in its header, because the next reader will otherwise assume the information
was thrown away.

**§82's `requireApprovedPlanToStart` goes with them**, and not as collateral: `PlanStatusService:182`
records that the gate binds **only** on `DRAFT → ACTIVE`, and after M7 there is no such edge. §82's
finding — *"the plan rule is an EDGE, not a target"*, which cost a whole slice to discover and whose
naive reading made every closed plan permanently un-reopenable — becomes **moot rather than wrong**.
It is written down here because a finding that expensive should not vanish silently when the states
it was about are deleted.

**`PlanDeleteService`'s partition goes too** — *"delete a plan nobody executed, close one somebody
did"*. With no `CLOSED`, a plan somebody executed against has no terminal state, and delete stays
refused while a run names it. That leaves such a plan permanently `APPROVED`, which is correct under
M7 (its execution state lives on its runs) and is the sentence the handoff has to carry.

## 3a. Validation against TestNG (2026-08-05)

The user validated the model against TestNG, which is the right instrument: it is the same
vocabulary with a working implementation behind it.

| TestNG | TCMS | |
|---|---|---|
| `@Test` method | test step | ✅ `test_case_step` |
| class | test case | ✅ |
| `@Test(groups={"auth"})` | tag naming a feature area | ✅ `case_tag` |
| `<class name="…"/>` | case by id | ✅ `suite_case` |
| `<groups><include name="smoke"/>` | case by tag | ✅ `suite_tag_rule` |
| `<packages>` | the filing arm | ✅ `test_case.suite_id` (M1) |
| running the suite | the run | ✅ `test_run` + `test_execution` |

**Two structural confirmations.** TestNG never writes pass/fail back into `testng.xml` — the
definition and the results are separate artifacts, which is exactly `test_case` having no status
column while `test_execution.status_id` carries it per (run, case). And a `<suite>` is a *named
selection document, not a folder in a folder tree*, which is independent support for M2.

**One vocabulary correction.** The user wrote *"test run = test execution"*. This product splits
them and the split is load-bearing: `test_run` is the **event** (status, configuration, build
number, commit sha), `test_execution` is **one case's result inside it** (`uq_execution_run_case`).
§86's fact log and every report are built on the per-case row.

**Two disagreements, and they became M5 and M6.** TestNG's `<test>` re-resolves every run, where
§97's D7 snapshotted; and TestNG can `<exclude>`, where nothing here can.

## 4. M2 and M3 each overturn a recorded decision, and both reversals are written down

Per the working agreement — *"write superseded decisions and their reversal into the docs, so the
next reader doesn't think the old reasoning was never considered."*

### M3 overturns `V31`'s explicit refusal of tag-scoped runs

`V31__suite_membership.sql` says, in its header:

> ~~Run scoping needs no new mechanism: "run everything tagged smoke" is a suite whose tag rule is
> `smoke`, scoped into a plan the way suites already are (`plan_scope_item.suite_id`). **No tag
> option on plan scope**, and `ck_plan_scope_target` is untouched.~~

→ **superseded 2026-08-05 (M3).** The user wants a run derivable from tags directly. The cost that
argument was avoiding is real and is accepted rather than denied: **there will be two ways to say
"run everything tagged smoke"** — a suite whose tag rule is `smoke`, and a run scoped on the tag —
and the two can drift, because the suite's rule can be edited while the run's tag cannot. The
mitigation is that run scope is **resolved once at create time** into `test_execution` rows (it
already is — §1950 of the contract: *"Creating a run materialises its executions"*), so a run never
re-resolves and the drift is invisible after creation by construction.

### M2 overturns the suite's shape, which nothing wrote down as a decision because it was V1

The tree has been load-bearing since V1: `path LTREE NOT NULL`, a GIST index, `suite_path_idx`, and
the contract's own definition of plan scope — *"A suite item means the folder **and everything
beneath it**"*. M2 deletes the phrase "and everything beneath it" from the product.

## 5. Sequencing — §97 does not depend on either, and that is because of §96's seam

The instinct is that §98 must land before §97, because §97's Test cases tab adds *a whole suite* to
a section and a flat suite expands differently from a tree. **It does not**, and the reason is
structural: §97 calls `SuiteMembership` to resolve a suite's members, and whether that resolution
walks a subtree is the seam's business, not the caller's. Flattening changes the seam's body and not
its signature.

So the order is a scheduling call. **Recommended: §97 → §98 → §99.** §97 is the thing that has been
asked for twice and it is the only one of the three a user can see. §98 is a destructive migration
that touches history and wants its own undivided slice.

---

## §98 — suites become flat

### What goes

- `suite.parent_id`, `suite.path`, `suite_path_idx` (GIST), and the `ltree` extension if nothing
  else uses it — **check, do not assume**.
- `CaseFacts#inSubtreeOf` — §96's seam for plan scope and suite history. With no subtree it becomes
  membership, which `SuiteMembership` already answers, so **one of §96's two duplicated resolution
  predicates disappears** and `SuiteScopeAgreementTest` loses its reason to exist. That is the one
  genuine simplification in this slice and it should be taken deliberately rather than left behind.
- The MUI X **TreeView** on the suite screen becomes a list. `TreeItem` is a 91 kB chunk; measure
  the bundle after, per the working agreement, rather than assuming it drops.
- The contract's *"and everything beneath it"* on plan scope, and `Suite.path`.

### The trap that outranks the rest

**`execution_fact.suite_path` is frozen history and must not be rewritten.** §86 froze
`suite_id`/`suite_path` on conflict precisely so last quarter's results are not re-filed under this
quarter's taxonomy, and §96 kept them frozen when the column went nullable. After M2 those stored
paths describe a hierarchy that no longer exists — and that is **correct**: they say where the case
was filed *when it ran*. The migration must not touch them, every suite-grouped report must keep
rendering them, and the handoff has to say so, or the first person to notice a path in a report of a
flat-suite product will "fix" it.

### Phases (sketch)

| # | Phase | Gate |
|---|---|---|
| 1 | Contract — drop `path`, restate plan scope, `gen:api` | `npm run build` |
| 2 | `V32` — drop the columns and the index; **leave `execution_fact` untouched** | `./gradlew build` |
| 3 | Backend — retire `inSubtreeOf`, collapse to `SuiteMembership`, delete the agreement test with its rationale recorded | `./gradlew build` |
| 4 | Frontend — TreeView becomes a list; measure the bundle | both |
| 5 | The record | both |

---

## §99 — a run is scoped by suites, cases and tags

> **SHIPPED 2026-08-06** — three phases, three commits (`d9b25c6`, `9f4ef89`, `38df750`), no
> migration. See `docs/handoff/99-a-run-is-scoped-by-suites-cases-and-tags.md`. **With it this plan
> is complete**: §97, §98 and §99 have all landed.
>
> **Two corrections to the sketch below, both worth reading before trusting a phase table again.**
> The four phases became **three** — the frontend and its mock are one commit, because the mock is
> what the dialog's tests run against and a commit that changed one without the other could not pass
> its own gate. And the sketch's silence about the suite arm is where the slice's finding lived:
> "resolved through `SuiteMembership`" is one line here and is the whole difficulty, because the
> spelling that reads right (`c.suite_id IN (…)`) resolves a tag-defined suite to nothing.

### The change

```
TestRunInput.suiteIds : uuid[]   # NEW — resolved through SuiteMembership
TestRunInput.tags     : string[] # NEW — live cases carrying ANY of these
TestRunInput.caseIds  : uuid[]   # exists
```

Union, deduped, resolved **once** at create time into `test_execution` rows. `planId` stays optional
and the column is already nullable, so **no migration**.

### The three things that must not be got wrong

1. **The approval gate binds on the RESOLVED set** — §82's decision, and it arrives here through a
   new door. `requireApprovedCaseForRun` refuses the **whole** run naming the offending keys, never
   a trimmed one, and it must see the union of all three arms. A tag arm that skipped the gate would
   be exactly §82's bypass — *"create the run from the plan instead"* — with a new spelling.
2. **A run that would materialise nothing is a 400 on the field**, as today. Three arms make this
   more reachable, not less: a tag nobody has used resolves to zero and reads as a typo, and an
   empty run looks finished.
3. **Two ways to say "everything tagged smoke"** (§4). Accepted; mitigated by create-time
   resolution.

### Phases (sketch)

| # | Phase | Gate |
|---|---|---|
| 1 | Contract — `suiteIds` + `tags` on `TestRunInput`, the resolution and gate prose, `gen:api` | `npm run build` |
| 2 | Backend — union resolution, the gate over it, the empty-scope 400, tests | `./gradlew build` |
| 3 | Frontend — the run-create dialog offers all three | both |
| 4 | The record | both |
