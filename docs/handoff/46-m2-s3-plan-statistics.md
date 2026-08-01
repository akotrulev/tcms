# §46. Backend M2, slice 3 + frontend — plan statistics, and the panel that hid an environment

**Date:** 2026-08-01 · **Branch:** `master` · **Sequential** — the first slice after §45 ended the
parallel experiment, and it needed nothing the lanes provided.

`GET /projects/{projectId}/plans/{planId}/statistics` is live. It is the endpoint §42's live pass
navigated *into* and got "Not found" from, the one §45 listed first under "Not done", and the last
read hole in the `execution` module. Creating a plan now lands on a page that works.

## The blocker was smaller than three sessions of notes said it was

The contract's own description declared the path unimplemented because "the shape spans three
modules — `execution` for the results, `repository` for `bySuite`, `reporting` for the trend and the
coverage denominator — and `reporting` does not exist." §45 repeated it. Only one third of that was
still true:

| Claimed blocker | Actual state |
|---|---|
| `bySuite` needs `repository` | The suite tree has been live since §24, and `CaseFacts` was already the seam. One more method on it. |
| `coveragePercent` needs a repository-wide denominator | **It never did.** §6p defines coverage as *executed over in-scope* — "97% · 305 executed · 7 not run" against a 312-case plan. The denominator is the plan's own scope, which `CaseFacts.caseCountsByPlan` has computed since §42. |
| `trend` needs a `reporting` module | It needs a pass rate per run of this plan, which is a `GROUP BY` over rows `execution` already owns. |

The `PlanSnapshot` rollup §6p specifies is still not built and this deviates from it deliberately —
computed on read, with the argument `RunService` already makes for the run list: **a figure
recomputed per request cannot go stale relative to a result recorded a second ago**, which is exactly
what a tester checks after saving one. The cost is stated in the service header, and that class is
now the definition of what the rollup must eventually produce.

**The lesson is the one §28 and §29 already paid for, arriving from a new direction.** A "not done"
note is a claim about the code that was true when written and decays silently. Three sessions
inherited this one without re-checking it, and it was a third right. The orientation pass is not
optional, and *"is this still blocked?"* belongs in it beside *"does this have a consumer?"*.

## The narrowing: `notRun` was typed at a shape that would have widened the seam

The contract typed `notRun` as `TestCaseSummary`, whose `required` array names features, tags, health
signals, automation status, owner and `updatedAt`. Serving that from `execution` means re-deriving
`repository.CaseSummaries` — `case_classification`, `case_tag`, `case_health`, `app_user` — inside
the one module forbidden to import it, through the seam `CaseFacts`' header exists to keep one class
wide. The panel renders a key, a title and a suite name.

So the contract was narrowed to a purpose-built `PlanCaseRef` (six fields), and the mock fixture was
projected down to match. **A mock richer than the contract is a mock that lets a page read a field
the real server never sends** — the mock-vs-live divergence class, caught before it could happen
rather than after.

`CaseFacts` now expands a plan's scope **three times over the same predicate** — count, ids, rows —
all adjacent in one file for the §32 reason: the plan header's `caseCount` and the statistics page's
`counts.total` are a click apart, and `PlanStatisticsEndpointTest` asserts them equal *against each
other* rather than against a literal, because a literal agreeing with a wrong implementation is how
both screens end up wrong together.

## Three defects, and only one of them was found by a gate

**1. A case in an unfinished run counted twice.** Found while writing the test, before it ran. Such a
case has an execution row with no verdict, so it contributes an `UNTESTED` result *and* looks
un-executed; adding a phantom not-run row for it too made `total` exceed the plan's scope by roughly
the size of every open run. The fix is one word — the phantom count is scope minus *touched*, not
scope minus *covered* — and the two sets are one line apart and mean different things.

**2. `WORST` let an unrecorded row outrank a recorded pass.** Found by the first run of the test. A
fresh checklist gives every case an `UNTESTED` row, which sits above `PASSED` in the severity order —
so **opening a run degraded the plan**, and a case that had passed in every run it was recorded in
read as owed a verdict. Recorded verdicts now win before severity is consulted, which is the
precedence `LATEST` already had for free from `nullsFirst` on `executedAt`. The un-started work is
already reported twice over, by `coverage` and by `notRun`.

**3. `byConfiguration` under `LATEST` hid an entire environment.** The important one. Slicing the
globally-resolved contributions by configuration is the obvious implementation, and it is wrong in a
way that looks like a working screen: `LATEST` resolves one winner per case *across* configurations,
so every winner lands in whichever run is newest and the table collapses to a single column. A plan
passing on Chrome and failing on Safari showed Chrome. **That is the precise failure §6p introduces
this panel to prevent, arrived at from the other direction** — the panel built to stop one
environment being averaged away instead made it disappear. Each row now resolves the policy *within*
its own configuration.

All three are mutation-verified: reverting each kills 8, 2 and 1 tests respectively.

## The frontend half — two defects live data made reachable

Neither is new code. Both are §45's second finding exactly: *no test written before a state was
reachable can fail on it.*

**`met: null` rendered as a failed gate.** `criteria.filter((c) => !c.met)` reads "not evaluated" as
"unmet", so a plan showed red crosses and an "N unmet" count against gates nobody had checked. It
survived every gate and three browser passes for one reason: **the mock always supplied a boolean**,
so the null branch was unreachable until this endpoint went live. The server now returns null for the
three metrics it cannot answer — `open_critical` needs the M4 issue tracker, `cases_approved` the
review workflow, `build_available` nothing that exists — and the page renders three states. The
classification moved to `lib/results.ts` per the shared-layer rule and is unit-tested there, because
a second reading of `met` elsewhere is how the two would come to disagree.

**The staleness caption said the opposite of what `stale` means.** It read *"the read model is
catching up, so counts may lag by a few seconds"* — reassurance. The figures are recomputed per
request and never lag; `stale` means **nothing has been executed in fourteen days**, and §6p calls a
page showing old numbers as current the most dangerous screen in the product. Reassuring the reader
was the exact wrong response. Same root cause: the mock never set the flag, so the sentence had never
been on screen.

## Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL in 2m 20s
           843 tests, 0 failures, 0 skipped        (810 + 33)
frontend:  npm run build   → ✓ built
           npm run lint    → oxlint, exit 0, no output
           npm run test:run→ 15 files, 255 tests passed   (252 + 3)
```

**Driven live in the browser**, `VITE_API_MODE=live` against `docker compose`, on a plan with four
cases in scope and one recorded result: the page renders where §42 got "Not found"; `counts.total`
(4) matches the plan header's `caseCount`; coverage reads 25% of 4 in scope beside 100% of 1
executed; `notRun` names TC-2, TC-3 and TC-4 with their suite; `bySuite` and `byConfiguration` render;
the policy round-trips through `?aggregation=`; the console is clean. Then, deliberately: exit
criteria written onto the plan render **"2 unmet" and "2 not evaluated"** as separate chips — four
red crosses before the fix — and the executions aged 40 days render the amber staleness warning,
**a state no screen in this product had ever displayed.**

## Deliberate limits, stated

- **`bySuite` is flat, not the rolled-up tree §6p describes.** The SPA renders a flat table; computing
  a rollup nothing displays would be a second definition of a suite's numbers waiting to disagree
  with the first. A suite with no cases of its own does not appear.
- **Blockers are ordered by case key, not by priority as §6p asks.** Priority is a `repository` column
  that would widen the seam, and `TestExecutionSummary` has no field to display it in — sorting by an
  invisible key produces an order the reader cannot account for.
- **The exit criteria move with the aggregation policy**, because they are evaluated against the
  response's own counts. A criterion pinned to one policy while the counts above it followed another
  would be the contradiction; the policy is stated at the top of the page and carried in the URL.
- **`STALE_AFTER` is a constant, not a project setting.** Nobody has asked to tune it, and a setting
  nobody changes is a column to migrate, default, seed and explain.

## Not done

- **The contract-hygiene computed checks are still not built** — "every path declares at least one
  4xx" and "every `$ref` at a nullable column is an `allOf`". This slice added a `400` to its own path
  and stayed inside the existing nullable conventions, which is what the last three sessions also did.
  **Fourth session running.** Now with three more pieces of evidence that reading misses absent
  things, all three from this slice.
- **`documentation`, `reporting` and `work` writes still run on MSW.** With this endpoint live, the
  plan/run/execution surface is complete for reads and writes; `/dashboard`, `/doc-pages`,
  `/artifacts`, `/reports` and `/work-items` are the remaining MSW-only endpoints.
- **Teams and memberships** remain the next write surface with no endpoints (§39, §45).
- **`data-testid` on MUI `TextField` lands on the root `<div>`** (§44) — untouched.
- **`cases/bulk`, `cases/{id}/history` and `suites/{id}/history`** are contract paths with SPA
  consumers and no server. `useCaseHistory` and the suite history hook both have call sites, so they
  pass the §28 "does it have a consumer" test — unlike the endpoint this slice replaced in that list.
