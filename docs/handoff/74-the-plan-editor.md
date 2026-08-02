# §74. The plan editor — the gates nothing could author, and the version that is not always written

**Date:** 2026-08-02 · **Branch:** `master` · **No migration; `V22` is still free.**

## What this slice is

Three things had never been written by anything, and all three are in one aggregate:

- **`entry_criteria` and `exit_criteria`.** `TestPlan.create` sets both to `null` and `TestPlanInput`
  has no field for them, on the recorded grounds that *"criteria belong to `plan:edit`, which is a
  different permission and a different slice"*. So `PlanCriteria.evaluate` — built in §46, with a
  nullable `met`, a six-operator comparator and a whole exit-criteria panel above it — **has had
  nothing to evaluate on any real instance for its entire life**.
- **`test_plan_version`.** The last of §6j's four versioned aggregates with no writer; case, suite
  and doc page each got one. It had no JPA entity and no native query naming it — the
  remaining-scope assessment listed it under *tables with schema and no code*.
- **A plan's scope after creation.** The only way to change what a release covered was to delete the
  plan and make another, which loses its key, its runs and its history.

§73 named this slice on its way out and named it correctly. `plan:edit` had a consumer that moved a
*status* and none that changed a plan's *content*.

## The orientation finding that changed the shape before any Java

**You could not write back what you were served.** `GET /plans/{planId}` served `caseCount` — an
*expansion* of the scope, recomputed on every read — and no scope ids at all. A picker built against
it would have had to invent the scope it submitted.

This is **§68's finding one aggregate over**, and it arrived the same way: that slice found a case's
detail serving classification *names* while the writer took *ids*. The rule §68 produced is now
general enough to be a check — **a field a `PATCH` accepts must be readable from the `GET` that
precedes it** — and it was worth re-deriving rather than assuming, because the two halves are
written by different people at different times and neither one is wrong on its own.

Hence `suiteIds` and `caseIds` on the `TestPlan` schema, sorted (`plan_scope_item` has no ordinal
column, so there is no storage order to preserve) and read in one batched query for the list as well
as the detail.

## A plan version is its SCOPE, and that is what makes most saves write nothing

`V1`'s comment on `test_plan_version.snapshot` says it outright — *"A plan version captures its
SCOPE"* — and §6j's table spells the scope out: selected suites and cases, milestone, entry/exit
criteria. `PlanSnapshot` is exactly that list.

**So renaming a plan writes no version.** A name and a description are content, which is the
identical call `suite_version`'s own schema comment makes for a suite, and it is what stops the
history filling with rows whose snapshots are byte-identical. Three things are deliberately absent
from the snapshot, each for a different reason:

- `name` / `description` — content, not scope.
- `status` — `TestPlan.applyStatus` already declined to bump the counter in §73, because approving a
  plan does not change what is in it.
- `caseCount` — derived. Storing it would move the hash when somebody authors a case into a folder
  this plan happens to name, which is precisely the every-edit-bumps-every-ancestor spiral the
  suite-version comment refuses.
- `configurations` — a **project's** list, not a plan's (`configuration` has no plan column). §6j's
  table names them and that is a correction rather than an omission: a per-plan snapshot of a
  project-level list would record, against this plan, a change made to another one.

**One value serves as both snapshot and hash**, which is a deliberate divergence from
`CaseVersions` and worth stating rather than copying the shape across. That class keeps the two over
*different* shapes because a case's snapshot carries an owner's display name and health signals
derived from run results — values that move without anybody editing the case. Nothing reachable from
`PlanSnapshot` is derived, so splitting them would mean two definitions of "the scope changed"
maintained side by side, which is the defect the split exists to prevent.

**The mutation is the proof.** Disabling the unchanged-hash arm — the "bump on every save"
implementation — reddens **exactly 3** while every scope test and every criteria test stays green.
The cheaper version would have shipped with the rest of the class passing, and the damage is
invisible for months: a version history whose entries all say the same thing.

**The v1 gap is real and honest.** Every plan created before this slice has `current_version_no = 1`
and no version row, so its first scope edit writes **v2**. `CaseVersions`' argument unchanged —
writing the edit as v1 would label the edited scope as the original. Plans created from now on get
v1 with them (`PlanCreateService`), so the two populations stay distinguishable by the fact that
matters rather than by a flag somebody maintains.

## Absent, null and empty are three instructions, not two

Jackson gives an absent field and an explicit `null` the same Java value, and this codebase already
settled the answer in §68 — it just had not been written down as a rule that applies by *kind*:

- **Scalars** — `null` means *clear it* (`CaseUpdateRequest`'s convention; the SPA submits every
  field it renders).
- **Lists** — `null` means *leave it alone* and `[]` means *clear it*, because a list has an empty
  spelling of its own and does not need `null` to carry two meanings.
  `CaseEdit.normalizeClassification` already did exactly this.

That split is what keeps `TestPlan.create`'s stated intent alive: a plan whose criteria nobody has
ever touched keeps its `NULL` — *nobody has decided* — while an author who removes them all writes
`[]` — *there are deliberately none*. Both render as `[]` on the wire, which is correct: the
difference is authoring history, not something a plan page can act on.

**The mutation reddens 4, and two of them are the interesting ones.** Reading an absent list as
*clear it* fails the two tests that name the rule — and also *renaming a plan writes NO version* and
*the audit row says whether the scope moved*, because under that defect a rename **empties the
scope**, which is a real change, which writes a version. The version tests are an independent
detector of the same defect, which is the kind of redundancy worth noticing rather than removing.

## The criteria vocabulary is closed, and the reason is that a typo is invisible

`PlanCriteria.evaluate` answers `met` for two of the five metrics plans are written with; the other
three (`open_critical`, `cases_approved`, `build_available`) need machinery the product does not
have, and render as **not evaluated**.

**Which is exactly why the input is validated against the list.** Unchecked, a plan gated on
`pass_rte` renders *identically* to one gated on `open_critical` — both show a criterion with no
verdict. One means "we cannot check this yet" and the other is a typo that will never be checked at
all, and **a release meeting cannot tell them apart by looking**. A field error at authoring time is
the only moment the difference is visible.

`isKnownOperator` is implemented by *calling* `satisfies` rather than by listing the six operators a
second time — §58's E1 and §70's verb list are both about a hand-maintained copy of a neighbour,
correct on the day it was written.

## A closed plan does not change, and that gives `isClosed` its first consumer

`CLOSED` says a release was executed and is over, and §6n pins a plan's numbers into the evidence
bundle when it closes. Editing the exit criteria afterwards rewrites the gate a sign-off was
measured against, *after* the sign-off — the record would show a release passing criteria nobody
applied to it. Refused with **409**.

This is §72's frozen-run rule at the aggregate above, and **`PlanLifecycle.isClosed` had zero
consumers**: §73 wrote the method and `TestPlan.applyStatus` compares the enum inline, so it was
dead code waiting for exactly this. The way out is §72's asymmetry — reopening exists, and it needs
`plan:approve`, so unfreezing a signed-off release is a lead's act and editing it afterwards is not.

**The placement carries the reasoning**, checked against §72's three questions rather than copied:
**after** the permission (a Viewer editing a closed plan gets 403, not 409 — the refusal names a
state, and a caller who may not edit plans has not earned that), **before** the `If-Match` (a stale
header is fixable and this is not), and **before** validation (no point landing field errors on a
form whose submit is refused whatever it contains).

## The defect the slice found: `lock_version` was mapped without `@Version`

The `If-Match` test failed on the first run for the right reason — a stale `lockVersion` still
saved. **`TestPlan` mapped `lock_version` and never annotated it `@Version`**, so Hibernate never
incremented it: the column has been `0` on every plan since M2 slice 2, while `lockVersion` has been
a *required* property of the contract's `TestPlan` for just as long. An `If-Match` check against it
**could not fail** — the client sends 0, the row holds 0, the precondition passes over any number of
intervening saves. The endpoint would have shipped with a concurrency guard structurally incapable
of firing, and no test asserting "a stale header is refused" would have gone red, because there is
no such thing as a stale 0.

**§73's own lesson applied rather than restated** — *a fix applied to one instance of a class needs
the grep that finds the others*. The sweep over every entity mapping `lock_version` found **three**
without `@Version`: `TestPlan`, `TestRun` and `work.WorkItem`. Ten others have it.

**Only `TestPlan` is fixed, and the other two are found-not-fixed with a reason.** Neither serves
`lockVersion` on the wire and nothing sends an `If-Match` for either, so both are latent rather than
live — and adding it to `TestRun` is specifically not a free repair: `TestRun.begin` touches the run
on **every result recorded**, so two testers working through the same run concurrently would start
colliding on the product's most-used write. That is a behaviour change on a hot path and not this
slice's call.

Adding `@Version` also made the true race reachable, so `PlanController#onWriteRace` maps
`OptimisticLockingFailureException` to the same 409 — a handler rather than a `catch`, for
`CaseController`'s reason (the transaction is rollback-only by the time the exception exists).

## What shipped

**Backend** — `PlanUpdateService`, `PlanUpdateRequest`, `CriterionInput`, `PlanScope` (the one
normalizer of "the same scope"), `PlanSnapshot`, `PlanVersions`, `TestPlanVersion` +
`TestPlanVersionRepository`, `PlanEdit.validateUpdate` (sharing every field rule with the create),
`PlanCriteria.METRICS`/`isKnownOperator`/`serialize`, `TestPlan.applyEdit`/`stampVersion`/`@Version`,
`PlanScopeItemRepository.findForPlans`/`deleteForPlan`, `PATCH /projects/{projectId}/plans/{planId}`,
audit action `plan.updated`.

**Contract** — the operation, `TestPlanUpdateInput`, `CriterionInput`, and `suiteIds`/`caseIds` on
`TestPlan`.

**Frontend** — `useUpdatePlan`, `PlanEditDialog` with a criteria editor, the Edit affordance on the
plan header (disabled and explained on a closed plan), `criterionMetricLabels`/`criterionOperators`
in the shared layer, `CriterionMetric` in `api/types.ts`, and the mock's `PATCH` handler **mirroring
the server's order** — permission, then freeze, then `If-Match`, then validation — so a mock cannot
teach a 409 where live gives a 403.

**The mock's fixtures gained real `suiteIds`.** The editor seeds its folder checkboxes from that
field, so fixtures carrying `[]` would have rendered every plan as unscoped and made the one screen
this slice adds look broken in the only mode it can be driven in without a backend.

**The MUI checkbox trap fired a third time.** `data-testid` on a `Checkbox` lands on the wrapper
span where `toBeChecked` cannot see it — §62 found it, §68 hit it again, §71 extracted
`lib/muiTestIds.ts` for it, and this test file still hit it on the first run. The helper existed;
the dialog was written from `PlanCreateDialog`, which predates it. **That create dialog still has
the bare spelling** — harmless today because no test asserts its checkboxes, and worth knowing.

## Gates

`./gradlew build` — **1378 tests, 0 failures, 0 skipped** (1348 → 1378: 30 written). `npm run build`
clean, `npm run lint` 0 warnings, `npm run test:run` **584/584** (572 → 584: **10 written + 2
generated**, the 2 being 1 new `api.*` call site × the 2 per-call-site contract checks — §70's
arithmetic checked rather than noted, on its fourth opportunity).

**Six mutations run, four queued.** Four backend (**exactly 3 / 2 / 4 / 1** red) and two frontend
(exactly 1 each). The frontend pair was not optional: 8 of the 10 tests passed on the first run,
which §66 established is the moment to distrust them.

## Not browser-verified

The ninth slice in the deferred queue. Rows are in
`docs/verification/frontend-browser-checks.md`, **live-only first and for a new reason**: the mock's
fixtures have carried hand-written exit criteria on `TP-1` since Phase 1, so **mock mode has been
showing a feature the product did not have**. That is §52's "mock agrees with itself" from the
opposite direction — the mock was right and the server had no writer — and it is why the panel has
to be seen against a real row before this is called done. **§73's queued row 9 becomes runnable
here**, since it needed a plan with real exit criteria and there was no way to make one.

## What this leaves for the next slice, named rather than implied

**The review workflow's reviewer half** is now the largest coherent gap in this module's family, and
it is the one four unenforced permission keys point at: `case:review`, `suite:review`,
`plan:review` and `case:comment` have no consumer in Java, and §73 recorded that this is *correct*
rather than an omission — §6g puts peer review **inside** `IN_REVIEW`, accumulating on the item
without moving it, so those keys belong to comments, LGTM and request-changes, which do not exist.
`review_comment` and `review_participation` are two of the eleven tables with schema and no code.
The per-project opt-in setting §6g requires does not exist either, and `PlanLifecycle`'s header
already leans on its absence to justify `DRAFT → ACTIVE`.

Two smaller things this slice deliberately did not take:

- **`test_plan_version` has a writer and no reader.** Nothing serves a plan's scope history, so the
  rows accumulate unread — the same state `suite_version` was in for a long time. A plan timeline
  is the natural consumer and it is §6p's, not this slice's.
- **`@Version` on `TestRun` and `work.WorkItem`**, above, with the reason each is latent and the
  reason the run one is not free.
