# §95 — Conditions get a writer: the library nothing could fill, and the count that was always zero

**Date:** 2026-08-05 · **Plan:** `docs/plans/2026-08-04-test-case-authoring-rework.md` (slice §95)
**Status: PHASE 1 OF 4 COMPLETE — BUILT AND UNVERIFIED.** The number is reserved by this file
before the slice finishes, per §10's lesson (paid twice: §92's number was claimed mid-slice by a
concurrent strand, and §94 recorded the rule).

## 0. Why this slice exists

§94 established what a step is: text somebody wrote, copied from a corpus, belonging to its case.
It did that by *rejecting* the shared-object reading of the original ask. The reversal left a
promise behind, recorded in §94 §2:

> **Conditions are where the sharing lives instead** — a case attaches a condition by id and an edit
> to it propagates everywhere it is used, with no prompt. That is already how the data models it
> (`case_condition.condition_id` → `condition`), so propagation costs nothing once a writer exists.
> It is §95's whole slice, and it is currently impossible.

"Currently impossible" was exact. `/projects/{projectId}/conditions` was **GET-only**, had **zero
frontend call sites**, and `CaseDetailPage` rendered conditions read-only. The product's one
genuinely shared authoring object could be read, ranked and attached — and never created.

## 1. Phase 1 — the writers

```
POST /projects/{projectId}/conditions                 → 201 Condition
PUT  /projects/{projectId}/conditions/{conditionId}   → 200 Condition   (If-Match; 409 on conflict)
```

Both gated on **`library:manage`**, the same key `POST /shared-steps` (§64) uses and for the same
reason on the create: creating an entry propagates nowhere, so the permission is the whole control.

**The `PUT` is the operation the roles spec's "gated by an impact modal, not by permission" note was
actually about.** An edit lands on every case and suite attaching the entry, with no prompt and no
fork. Two decisions follow, both refusals:

- **No fork on edit.** "Change everywhere / copy for this case" is what turned `shared_step` into a
  library of near-duplicates nobody could rank. An author wanting different text writes a different
  condition.
- **No confirmation round-trip on the endpoint.** By the time the `PUT` runs the decision has been
  made. What the server owes the client is *honest numbers* to state the impact from — which turned
  out to be the whole of §2.

**Propagation needed no code.** `case_condition.condition_id` is a reference, so `Condition.applyEdit`
*is* the fan-out. `ConditionWriteEndpointTest.theEditReachesEveryAttachedCase` reads the change back
through **a case**, not through the library, because a library read proves only that the row was
written — it cannot tell a reference from a copy, which is the one distinction that matters here.

## 2. THE FINDING — `usageCount` was always zero, and it was about to become an impact warning

`Condition.usageCount` was mapped, served, and read its `DEFAULT 0` on every row in every
deployment. That was defensible while nothing consumed it — the entity's own comment argued the
stored value was the honest answer until a writer maintained it, on `case_health`'s precedent.

**This slice is what makes it indefensible.** The plan's line for §95 is *"the confirm text states
how many cases change"*. A confirm text computed from a permanent zero is not a cautious answer; it
is a confident wrong one, telling an author that editing an entry three hundred cases attach will
affect nothing. So the column is now **unmapped** and both counts are **derived**, exactly as
`SharedStepService.rank` does it — one shape in this module, not two.

### The count is TWO numbers, and that is the resolution of a question the contract had already posed

`/shared-steps`' own description named this, one slice before anything needed it:

> This deliberately differs from `Condition.usageCount`, which is still the stored 0 — a condition's
> usage spans `case_condition` **and** `suite_condition`, and the suite side propagates by
> inheritance, so that count is a design question rather than a join.

The answer: **`usageCount` (distinct live cases attaching directly) and `suiteUsageCount` (live
suites attaching), served separately and never added.**

A suite attachment inherits down the **whole ancestor chain** — the walk `CaseConditions` does over
`suite.path` — so it reaches every case beneath the suite. That figure is not computable here:
the walk is `ltree`, HQL cannot express it, and a native query would drop the Hibernate company
filter, which is the constraint every library read in this module records. So blending the two would
be **a guess presented as a measurement**, and it would *understate* — the direction that matters,
since understating is precisely the failure an impact warning exists to prevent.

The client is expected to render the second as *"attached to N suites — every case beneath inherits
it"* rather than inventing a case count it has no basis to compute.

## 3. `lock_version` mapped WITH `@Version`, which is §74's trap in its preventable form

`condition.lock_version` has existed since `V1` and was **never mapped**. §74 found the sibling
spelling — mapped *without* `@Version` — on `TestPlan`, `TestRun` and `work.WorkItem`, where the
column reads `0` for ever and an `If-Match` precondition **cannot fail**, there being no such thing
as a stale `0`. An endpoint shipping that has a concurrency check that is decorative, and every
happy-path test passes.

Mapping it correctly was free here: this entity has no hot write path, which is the reason the run's
copy was deliberately left alone.

**The two tests are a pair and neither works alone.** `theVersionMovesOnEveryEdit` is what makes
`aStaleIfMatchIsRefused` able to fail — a version that never moves makes every `If-Match` match, so
the 409 test would pass against the defect.

## 4. Two things found while writing the tests

- **The fixture had to reset the row, not skip it.** These tests *write*, nothing rolls back a
  MockMvc request, and `ON CONFLICT DO NOTHING` therefore left `lock_version` climbing across the
  class: the first edit left the row at version 1 and every later `If-Match: 0` was a legitimate
  409. Six tests failed for a reason unrelated to what they asserted. The seed is now
  `DO UPDATE SET … lock_version = 0`.
- **The library test's stored counts are now deliberately WRONG, and kept.** `ConditionLibraryEndpointTest`
  seeds `usage_count` by hand (Log out 412, Log in 203). Those values are no longer served — but
  they stay, and they rank the two "log" rows in the **opposite** order to the real attachments
  (Log in 2 live cases, Log out 1). Anything that goes back to reading the column **reverses the
  list** rather than merely making it look alphabetical, which is the difference between a failing
  assertion and a silent regression.

The same fixture makes three separate wrong readings land on three different numbers, so one
assertion separates all of them: `203` = the column is being served again; `4` = neither `DISTINCT`
nor the deleted filter; `3` = `COUNT(*)` **or** counting the soft-deleted case; `2` = correct.

## 5. What is NOT done

- **Phase 2 — the case-conditions write path.** `CaseUpdateRequest` still **rejects
  `preconditions`/`postconditions` with a 400**; it is on that record's own list of five fields "no
  slice has built yet". `CaseEdit` must validate and persist them and `CaseConditions` must
  reconcile with `position`, the way `CaseClassifications` reconciles classifications.
- **Phase 3 — the frontend.** No call site exists for any of this yet: the `GET` has never had one,
  and now the `POST` and `PUT` have none either. The case editor gains Preconditions/Postconditions
  (attach, detach, reorder with §94's drag primitive, create, and edit behind the impact confirm).
- **Phase 4 — the record and the queues.**
- **No delete.** Retiring a condition is a soft delete the schema supports and no endpoint offers.
  Deliberately out of scope: a retire is only meaningful once something can attach, which is phase 2.
- **`condition_title_trgm_idx` is still spelled on the bare column** while `findLibrary` filters
  `LOWER(title)`, so it is never consulted — §94 §4 found it, declined to fix it for a reason that
  still holds (bounded to hundreds of curated rows, and a correctness-neutral performance change
  belongs in a slice that can measure it), and this slice does not change that.

## 6. Gates

Phase 1: `./gradlew build` — see the session log entry for the result. **Zero mutation checks run**
and zero browser rows executed, per §65; the queue rows are owed at phase 4 and the slice is
**built and unverified** until they are run.
