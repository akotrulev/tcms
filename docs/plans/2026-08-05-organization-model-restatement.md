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
- **M4 — the plan's sub-feature sections stay labels** (§97's D7, reaffirmed). They are not suites.

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
