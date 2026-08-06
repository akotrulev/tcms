# §99 — a run is scoped by suites, cases and tags

**Date:** 2026-08-06 · **Three phases, three commits** · M3 of the organization-model restatement,
and the last slice of it.

**No migration — `V33` is still the head and `V34` is still free.** No table, no column, no
permission key, no new route, policy count unchanged at **61**. `test_run.plan_id` was already
nullable and the executions were already materialised at create, so the whole slice is two fields
on one request body and one query behind them.

---

## What it does

```
TestRunInput.caseIds  : uuid[]   # existed
TestRunInput.suiteIds : uuid[]   # NEW — resolved through the suite's whole membership
TestRunInput.tags     : string[] # NEW — live cases carrying ANY of these
```

Unioned, deduped, resolved **once** at create time into `test_execution` rows.

---

## The finding: the suite arm that reads right and resolves to nothing

The tempting spelling is `c.suite_id IN (:suiteIds)`. It is one column comparison, it is what the
schema looks like it wants, and against a suite defined by a **tag rule** it returns the empty set —
because such a suite *files* nothing. The run is then either refused as empty (confusing) or, if the
emptiness check were ever relaxed, created empty: **0 of 0, 100%, complete**, a run that renders as
finished and never started.

This is §98's finding arriving one screen over. There the count and the grid answered different
questions about the same suite; here the wrong answer is not a number but an empty checklist. The
arm is `CaseFacts#memberOf` — filed ∪ added ∪ tag-matched — the same predicate the plan's scope goes
through, which is what makes "add this suite to a plan" and "run this suite" mean one thing.

**The fixture's non-vacuity is asserted first and by its own test.** `RunCreateEndpointTest.Scope`
opens with `paymentsFilesNothing`: PAYMENTS claims `caseA` through a tag rule and files nothing, and
REFUNDS files it. Without that property every assertion below it is equally true of the defective
arm — §98's lesson applied before writing the tests rather than after.

## One query, not three lookups unioned in Java

All three arms are `repository` facts, so a service-side union would cross the seam three times,
give three chances for one arm to forget `deleted_at`, and produce a set ordered by whichever
concatenation ran last. `caseIdsInRunScope` is three `OR`s over one `test_case` scan: *live in this
project* is written once and the order is `key_seq`, the project's order everywhere else.

Each arm is appended to the SQL **only when it has members**. `IN ()` is a syntax error and
Hibernate will not bind an empty collection — and the shape that survives compilation, `IN (NULL)`,
is never true, which reads as an arm that matched. The ordinary create names one arm; the other two
must cost nothing.

## §82's gate needed no edit, and that is the property worth keeping

The approval gate binds on `edit.caseIds()` — the **resolved** union, after substitution. So both
new arms are gated by construction rather than by remembering, and "run everything tagged smoke
instead" was never available as the bypass §82 named. `theNewScopeArmsAreGatedAsWell` pins both arms
in one test on purpose: they are the same defect, and splitting them would suggest each arm has a
gate of its own.

**The mutation queue records what this costs.** A gate that is covered *structurally* has no
mutation that proves it — there is no edit that represents "somebody adds a fourth arm and forgets",
because nothing would need to be remembered. Recorded as a property, not as coverage.

## The arms substitute for the plan's scope; they never add to it

`RunEdit`'s existing rule, widened from one arm to three and deliberately not changed in the second
half: a body carrying both a plan and any arm means *"this run is against that plan, and here is the
subset we are doing this pass"*. Unioning them would silently hand back the cases somebody just
narrowed away. The alternative rule — cases replace the plan, suites and tags add to it — is one
nobody could predict from the request.

The run still records its `planId`, so the narrowing reads as a run shorter than its plan rather
than as a lost association.

**And this is where "scope" stops meaning one thing.** A run's scope is resolved once, here, because
its executions are written now; a plan's scope is a set of references re-resolved on every read
(§97's D7′). The two are described in the same words and behave differently, which is why the
contract says so in prose and `theScopeIsResolvedAtCreation` asserts it: a case tagged *after* the
run does not join it.

## Three arms, three different validations, and each difference is a decision

- An unknown **case id** or **suite id** is a field error. An id is a reference to something the
  caller has already seen; one that resolves to nothing means the two of you disagree about what
  exists. (And a suite id from a *sibling project in this company* passes the foreign key, the
  company column and RLS alike — `liveSuiteIds` is the only thing that refuses it.)
- An unknown **tag** is accepted, for `SuiteEdit`'s reason: there is no vocabulary to check it
  against, and refusing a run because one of its three tags is currently unused would be a validator
  inventing a workflow order.
- What catches the typo is the **emptiness check**, which is the honest place for it — the request
  is refused when it would produce nothing, not when one arm of it happens to be quiet.

## The refusal now lands on every arm the caller used

It used to be `planId` when the scope came from a plan and `caseIds` otherwise. With three arms
"the wrong field" stops being a guess between two: a run scoped only by a mistyped tag, answered on
`caseIds`, sends somebody to a control they never touched. When more than one arm was used the
message goes on **all of them**, because at least one has to change and nothing in the validator can
know which. `caseIds` remains the fallback for a body that named no scope at all.

## `tags` unions where `?tag=` intersects, and both are right

The grid filter ANDs — a list is being narrowed. A run's tags OR — a collection is being assembled,
the same reading `suite_tag_rule` takes. §96 wrote this down for suites and the frontend's helper
text is where a user meets it: *"Any of these — a case carrying one of them is in the run."*

## Two ways to say "run everything tagged smoke", accepted rather than denied

A suite whose tag rule is `smoke`, and a run scoped on the tag. `V31`'s header explicitly refused
tag-scoped runs to avoid exactly this, and the plan overturned it on 2026-08-05 with the cost stated
rather than argued away. The mitigation is create-time resolution: a run never re-resolves, so after
creation the two are indistinguishable.

---

## The frontend, and the decision this dialog's own header had to overturn

`RunCreateDialog` said: *"offering `caseIds` here would mean building a second case picker whose
only use is to reconstruct, by hand, the set a plan already names"*. That held while a case list was
the only other way to say what a run covers. It stops holding the moment suites and tags are arms —
"everything tagged `smoke`" is not a plan written out longhand, it is a scope no plan need express,
and it is the run people ask for. Struck through in the file, not deleted.

So the plan is now **one of four ways** to scope a run, and a project with no plans is no longer a
dead end — which is the change a brand-new project feels first.

**THE ASSERTION THAT HAD TO BE INVERTED IS THE FINDING OF THE FRONTEND HALF.** The old test asserted
the submit is **disabled** in a project with no plans. `toBeDisabled()` passes against a dialog that
never grew the arms at all — so it would have stayed green while the feature was unreachable in
exactly the project that needs it first. It is now `toBeEnabled()`, which is the barrier. §66's
fail-closed lesson, arriving from the other side.

`caseIds` also moved **out** of the stray-error branch by gaining a control, so the error pair is
now one sentence that must land *on* a control and one that must not — which is what makes the
server's "every arm the caller used" rule visible instead of collapsing into one banner line.

Both new pickers spread `...params.slotProps` first. §96 paid a whole slice for the alternative:
replacing the object drops `input.startAdornment`, so every chip is held, sent, and **invisible**,
with build and lint green.

## The hand-written type failed in exactly the way its own comment predicted

`TestRunInput` in `api/queries/runs.ts` was hand-written, with a note saying nothing mechanically
checked it against the contract and a property added to the spec would be invisible here. Two
properties were added to the spec, the generated type grew them, the copy did not, and the dialog
sending `suiteIds` was a **type error against a stale local shape**. It is now `S['TestRunInput']`
— the recorded risk closed by the risk happening, the first time the shape changed after the note
was written.

**One property is corrected rather than taken as generated, and that is worth knowing before the
next one.** `openapi-typescript` renders a property carrying `default:` as **required** — true of a
*response*, where the server has applied the default, and false of a *request body*, which is the
only thing this type is ever used as. Taken literally it forces every caller to send
`overrideApprovalGate: false`, and §82's whole point is that an ordinary create stays byte-identical
to what callers sent before the override existed. Narrowed once, in one place, with the reason. The
alternative — dropping `default:` from the contract — deletes a true statement about the server to
satisfy a generator.

## The mock resolves through the plan's own union

`runScopeCaseIds` is `resolveArms`, the function a plan's scope and a section's count already go
through. One function, because a suite that means one set on the plan screen and another in the run
dialog is precisely the drift a shared resolver exists to prevent (§81's and §87's finding).

**Found and not fixed:** `resolveArms` excludes `DEPRECATED` cases and **the server does not**. It
predates this slice — it is the plan scope's behaviour, inherited here by reusing the resolver — and
it is now a one-row disagreement between mock and live on any tag-scoped run whose cases include a
deprecated one. Queued as browser row M4-40 rather than changed in a slice that does not own the
plan's scope: which of the two is right is a product question, and the server's reading (a
deprecated case is still runnable, and the *gating* rule is what refuses it) is the more likely one.

---

## Gates

- Backend: **BUILD SUCCESSFUL — 1761 tests across 348 classes, 0 failures, 0 skipped**
  (1746 → 1761), counted from the XML. **All 14 new tests passed on the first run**, stated plainly
  rather than taken as evidence they can fail.
- Frontend: build clean, lint 0, **913/913 across 65 files** (909 → 913). Entry bundle **unchanged**
  at 341.87 kB — `Autocomplete` was already a shared chunk, so the three new pickers add no
  dependency.
- **One test failed on the first run and it was the assertion, not the code**: the banner test
  still expected the old `caseIds` sentence after that field had been given a control.

## Verification

**Zero verification run**, per §65. **11 browser rows (M4-30…M4-40)** and **7 mutation rows (§12)**
queued. Two of the queued rows record checks weaker than they look and say so: the sibling-project
suite bound is probably refused by the validator before the query's own predicate is reached, and
the union's `key_seq` ordering is asserted by nothing because `caseIdsOf` reads `test_execution`
with no `ORDER BY`.

**Built and unverified.**

## What this closes, and what is left

The organization-model restatement (`docs/plans/2026-08-05-organization-model-restatement.md`) is
**complete**: §97 the plan detail, §98 flat suites, §99 the run's scope. Nothing in that plan is
outstanding.

Named as not built, here rather than elsewhere:

- **A "run selected cases" affordance on the case grid.** The endpoint has supported it since M2 and
  the dialog now has the picker, but the grid's own selection does not open this dialog pre-filled.
- **A resolved count before the click.** The dialog shows the plan's `caseCount` and each tag's
  usage, and deliberately computes **no total** across the three arms: they overlap, so a
  client-side sum is wrong in the direction that makes a run look longer than it is. The honest
  number is the server's, after resolution, and there is no endpoint that answers it.
