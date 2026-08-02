# §73. The plan lifecycle — the ladder nothing could climb, and the gate on the edge

**Date:** 2026-08-02 · **Branch:** `master` · **No migration; `V22` is still free.**

## What this slice is

`PlanStatus` has five values. `TestPlan.create` writes `DRAFT` and **nothing else has ever written
that column**, so four of the five were unreachable: no plan on any real instance has ever been in
review, approved, active or closed. `test_plan.closed_at` was declared in `V1` and never filled —
the entity did not even map it.

`TestPlan`'s own javadoc named the slice: *"still **not editable**: `create` is the one way a row
appears and there is no mutator, because `plan:edit` is a separate permission and a separate
slice."* `V1`'s comment inside `create` names it a second time — *"indistinguishable in the column
that a later `plan:edit` slice has to tell apart"*. §72's finding, one aggregate over, and found the
same way: `grep` for a writer.

**Three permissions get their first consumer in Java** — `plan:edit`, `plan:approve` and
`plan:delete` — after §62's `case:approve`, §64's `library:manage`, §69's `company:vocabulary`,
§71's `company:teams` and §72's `run:close`. The search is now routine; what it keeps finding is not.

**`plan:review` is deliberately still unconsumed, and that is the correct answer rather than an
omission.** §6g puts peer review *inside* `IN_REVIEW`, accumulating on the item without moving it —
so the key belongs to the reviewer apparatus (comments, LGTM, request-changes), not to any
transition. `case:review` and `suite:review` are unconsumed for exactly the same reason, and the
three of them should stay together until the review workflow's other half is built.

## The decision that is the slice: **the gate is decided by the EDGE, not by the target**

§6g says submitting for review *"needs only edit rights"* and that only Admin/Test Lead may perform
the transition into `APPROVED`. The natural implementation is the one `BulkOperations.permission`
uses one module over: read the requested status, return `plan:approve` for `APPROVED` and
`plan:edit` for everything else. It compiles, it passes an "an engineer cannot approve" test, and
**it lets that same engineer rescind a lead's approval by asking for `DRAFT`** — the
release-readiness signature §6g calls *"often the gate that matters most"*, undone by the button
beside it.

So `PlanLifecycle.permissionFor` takes **both ends of the move**: `plan:approve` is required to
*grant* the sign-off, to *end* the plan, and to *take either back*; `plan:edit` carries a plan
forward through the states nobody has signed off. That is roles spec §A4 principle 1 unchanged — a
Test Engineer is never blocked from producing, only from unmaking — and it is the same reason
`run:close`, `suite:delete` and `plan:delete` are Lead-only.

The gate therefore reads a **stored** fact, which is `ProjectService.writeGrantedOnTarget` — §69's
seam, second consumer. `writeGranted`'s rule that a caller may never nominate its own permission is
kept: what the request supplies is one end of an edge whose gate the server computes from a status
only the database holds.

**Mutation-verified, and this is the mutation worth reading.** Dropping the unmaking arm reddens
**exactly 2** tests — the rescind and the un-start — while the obvious gate test stays green. A
target-keyed implementation would have shipped with every other assertion in the class passing.

## The ladder must not require approval, because review is optional

Plan §6g opens with it: review is *"optional … enabled per project and per artifact type, off by
default, and a project that never turns it on should see no extra UI at all"* — and the per-project
setting does not exist yet. A ladder that made `ACTIVE` reachable only through `APPROVED` would
impose review on every project in the product and leave a team that never asked for it unable to
start a plan at all. Hence **`DRAFT → ACTIVE` is legal**, and review is a detour.

Two "reachable only from" rules survive that, each with a precedent already in this codebase:

- **`APPROVED` only from `IN_REVIEW`** — §6g's *"the state machine has exactly one gate"*, and
  `CaseBulkService`'s identical call for a case. Otherwise approval is reachable without ever
  passing review, and the gate guards a door standing beside an open one.
- **`CLOSED` only from `ACTIVE`** — closing says a release was executed and is over. A plan nobody
  started has nothing to say that about, and closing it would put a 0%-executed plan into every
  report beside plans that were really run: `RunLifecycle`'s argument for refusing
  `NOT_STARTED → COMPLETED`.

## Delete and close partition the space, and neither has to argue about the other's edges

`plan:delete` refuses with **409 and the count** while any live run names the plan.
`test_run.plan_id` references the row and `TestRunRepository.findPlanKeys` is **not** filtered on
`deleted_at`, so nothing breaks loudly: the runs keep rendering `TP-3` and the link under it goes
nowhere. §71's live-grants shape and §69's live-children shape, a third time.

The way out is not force — it is `CLOSED`, which exists for exactly this plan. **Delete a plan
nobody executed; close one somebody did.** Stating it that way is what let both rules stay simple: a
soft-deleted run does not block the delete (the refusal protects runs somebody can still open), and
the close does not have to reason about runs at all.

## Two pre-existing defects found on the way, both in the same file

**`PlanListPage`'s status colour map is the sibling of the defect §72 fixed on the run list, and
§72's sweep did not reach it.** Typed `Record<string, string>`, it carried an **`ARCHIVED`** that is
not a `PlanStatus` and was **missing `IN_REVIEW` and `APPROVED`** — invisible while `DRAFT` was the
only value any plan could hold, and a grey unlabelled chip the moment this slice made the other four
reachable. Both plan screens also spelled the status `status.toLowerCase()`, which renders
`IN_REVIEW` as "in_review". `planStatusLabels` / `planStatusColors` now live in `lib/labels.ts` with
`satisfies Record<PlanStatus, string>`. **The lesson is not "fix the map" — it is that a fix applied
to one instance of a class needs the grep that finds the others**, which §72 had the evidence for
and did not run.

**The exit-criteria column counted every criterion as unmet.** `!c.met` — and `met: null` means *not
evaluated*, which is not *unmet* (`Criterion.met`'s own contract), while the plan list never
evaluates anything because doing so would be the statistics endpoint run once per row. So a plan
whose gates were all green would have read "3 unmet". It could not fire, because `exit_criteria` has
no writer and the array is empty on every real instance — **and the slice that gives it one is the
next one**, which is why this is fixed now rather than recorded.

## What shipped

**Backend** — `PlanLifecycle` (pure: the transition table, `permissionFor`, `isClosed`),
`PlanStatusRequest`, `PlanStatusService`, `PlanDeleteService`,
`PUT /projects/{projectId}/plans/{planId}/status`, `DELETE /projects/{projectId}/plans/{planId}`,
`TestPlan.applyStatus`/`softDelete` with `closed_at` and `updated_by` mapped for the first time,
`TestRunRepository.countLiveByPlan`, audit actions `plan.submitted`, `plan.approved`,
`plan.started`, `plan.closed`, `plan.reopened`, `plan.withdrawn`, `plan.deleted`.

**Contract** — the two operations, `PlanStatusInput`, and the reasoning for both refusals.

**Frontend** — `useSetPlanStatus`, `useDeletePlan`, `PlanLifecycleActions` and `PlanDeleteDialog` on
the plan screen, `planStatusLabels`/`planStatusColors`/`isPlanClosed` in the shared layer, the two
list defects above, and the mock's own ladder — **mirroring the server's order**, permission before
transition, so a mock cannot teach a 409 where live gives a 403.

**The mock's fixtures gained a `DRAFT` and an `IN_REVIEW` plan.** §72's lesson, applied rather than
restated: every fixture plan was `ACTIVE` or `CLOSED` while every real plan was `DRAFT`, so the
state a fresh install produces was the one state mock mode never rendered.

## Gates

`./gradlew build` — **1348 tests, 0 failures, 0 skipped** (1325 → 1348). `npm run build` clean,
`npm run lint` 0 warnings, `npm run test:run` **572/572** (565 → 572: 7 written, and the +4 from 561
before them were the two data-driven contract checks firing on the two new `api.*` call sites —
2 × 2, **checked rather than noted**, §70's lesson on its third opportunity).

**Six mutations run, four queued.** Four backend (exactly 2 / 3 / 1 / 1 red) and two frontend
(exactly 1 each). The frontend pair was not optional: all 7 tests passed on the first run, which
§66 established is the moment to distrust them.

## Not browser-verified

The eighth slice in the deferred queue. Rows are in
`docs/verification/frontend-browser-checks.md`, live-only first — the state this slice makes
reachable is one **no instance has ever been in**.

## What this leaves for the next slice, named rather than implied

**The plan editor.** `plan:edit` now has a consumer that moves a status and still no way to change a
plan's *content*: `entry_criteria` and `exit_criteria` have **never been written by anything**, so
`PlanStatisticsPage`'s exit-criteria panel — built in §46, with an evaluator behind it in
`PlanCriteria` — has rendered nothing on every real instance for its whole life. That is the same
class as this slice and §72, and it is the largest one left in this module.

It also carries the piece this slice deliberately did not take: **`test_plan_version` is the only
one of §6j's four versioned aggregates whose table has no writer** (case, suite and doc page all
have one), and §6j defines a plan version as its *scope* — suites, cases, milestone, criteria.
`TestPlan.applyStatus` does **not** bump `currentVersionNo` and says why: a status change alters
none of those, and a version row whose snapshot is identical to the last is what `SuiteVersions`
refuses for a rename. The editor is where that changes.
