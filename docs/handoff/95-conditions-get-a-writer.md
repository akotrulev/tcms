# §95 — Conditions get a writer: the library nothing could fill, and the count that was always zero

**Date:** 2026-08-05 · **Plan:** `docs/plans/2026-08-04-test-case-authoring-rework.md` (slice §95)
**Status: COMPLETE — ALL FOUR PHASES, BUILT AND UNVERIFIED.** Four commits (`73d3d58`, `d4b4dab`,
`7f3f8c3`, and this one). Every gate is green and **not one queued check has been run**: 12 browser
rows (62–73) and 9 mutation rows, zero executed, per §65. Two browser rows are live-only and
**three of the mutation rows are tests that do not exist yet** — one of them guards this slice's
most dangerous defect. See §8.

The number was reserved by this file before the slice finished, per §10's lesson (paid twice: §92's
number was claimed mid-slice by a concurrent strand, and §94 recorded the rule). A second agent was
working on test plans for part of this session and was stopped before phase 2; nothing of theirs
landed in this tree.

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

## 5. Phase 2 — the case can finally attach one

`CaseUpdateRequest` had refused `preconditions`/`postconditions` with a 400 since M1 — five fields
on its "no slice has built yet" list, of which these two were the ones the library existed for. They
are accepted now, validated, reconciled, and the boundary is deleted in the one place it was
written. **Only the reference form**: an inline body is refused with a field error, because nothing
can render one (every `CaseConditionRef` requires a `title`, an inline row has no column for it, and
both readers exclude such rows at the query). `CaseConditionInput` also got the `required` array it
never had.

Two rules were carried over from `CaseClassifications` rather than reinvented, and both fail
silently when wrong:

- **Absent means "leave them alone"; empty means "detach everything."** A bulk action reconstructs a
  request without touching conditions, so reading absent as "detach" would have every
  `MOVE_TO_SUITE` quietly strip the case's setup.
- **The retired bound.** A condition is retired by a soft delete because attachments survive it, and
  the reader drops an attachment whose entry no longer resolves — so a client is never shown one and
  cannot resubmit it. A naive reconcile therefore deletes it on the next save.

### The trap, and it is the one worth reading

`CaseEdit` now carries both condition lists, so they are in `CaseVersions#hash` — correct, since a
condition is authored content and `test_case_version` snapshots it. But `loadContext` only loaded
the attachments **for the two condition commands**. Left alone, every *authored* bulk action would
have reconstructed the case with no conditions, hashed it as though it had none, and the next
ordinary save would have been credited with restoring attachments a `MOVE_TO_SUITE` never touched —
exactly the trap `classificationOf` documents, on a second field, introduced by *not* touching a
line. It loads them for authored commands too.

**One-time consequence, stated rather than discovered:** every stored hash was computed without
these fields, so the first save of each existing case writes a version whose diff looks empty. That
is the price of conditions joining the pinned content, it happens once per case, and it is the
opposite of the recurring empty-diff defect the surrounding comments warn about.

## 6. Phase 3 — the library gets its first consumer

`useConditions` had **zero call sites** from the day it was written, and phase 1's two writers had
none either. The case editor now has a Conditions panel per kind — attach, detach, reorder (drag or
▲▼), create, edit — as **one component** parameterised by `kind`.

**The most dangerous line is `ownConditionIds`.** `TestCaseDetail.preconditions` carries the
*folder's* conditions too, marked `inherited`, and they render almost identically to an own row.
Seeding one into the form would attach the folder's condition **directly** to the case on the first
save: permanently, invisibly, on every case anybody edits. Inherited rows are passed separately and
carry no remove, reorder or drag.

**The edit is the only mutation in this app whose blast radius is not the thing being edited**, so
`ui-rules` §4's destructive-action rule is applied to it: the dialog names the object, states the
impact from the two derived counts, and its button reads *"Change everywhere"* in danger styling.
No fork is offered.

**`impactSummary` is in `lib/` with its own test** because the two counts must never be added — see
§2. The test uses 4 and 3 and asserts **7 never appears**.

Two findings, both from running it:

- **A created condition rendered as "Unknown condition."** The library lists are cached per search
  term, so invalidating them does not make a new entry resolvable synchronously — and the row in
  between reported the author's own condition as missing, on the screen they created it from.
- **The panel pushed `CaseEditorPage.test.tsx` over the suite's load cliff**, and the cause was
  mine: each panel mounted *two* library reads, so every render in that file paid four extra queries
  and a `userEvent.type` test blew past 30s to 640s. The resolver read is now gated on there being a
  row to resolve — which is also the right product behaviour, and is §94's argument about the step
  corpus one endpoint over.

Bundle measured per the working agreement: **+8.9 kB raw / +2.1 kB gz (+0.54%)**, entirely in the
lazy `CaseEditorPage` chunk (79.92 → 88.19 kB); entry unchanged at 336.84 kB.

### A third finding, in git rather than in the code — and it shipped a red commit

The schema's test file is tracked as **`CaseSchema.test.ts`** (capital S) while its subject is
`caseSchema.ts`. macOS's filesystem is case-insensitive, so `git add
frontend/src/features/cases/caseSchema.test.ts` **wrote to the right file and staged nothing** — the
path did not match the tracked one. Phase 3's first commit therefore contained the schema's two new
required fields and *not* the test fixture that supplies them, which means that commit's test suite
was red while every gate had just been run green in the working tree.

Caught by reading `git status --porcelain` after staging rather than trusting the `git add`, and
fixed by amending phase 3 (`7f3f8c3` → `1b9378c`) so each commit remains a state whose gates
actually pass — which is what `CLAUDE.md`'s per-phase commit rule exists to guarantee.

**The general lesson, and it is not about this file:** the repo's staging discipline is
`git add <explicit paths>` precisely so a concurrent lane's work is not swept in — and on a
case-insensitive filesystem that discipline will silently no-op on any path whose case is
misremembered. `git status` after staging is the only thing that shows it.

## 7. Gates

| | Before | After |
|---|---|---|
| Backend | 1704 | **1715** (build clean, `-Werror`) |
| Frontend tests | 805 | **820** across 55 files |
| Frontend build / lint | clean / 0 | clean / 0 |

No migration, no table, no column, no new permission key, across all four phases. Policy count
stays **59** and `V30` is still head with `V31` free.

## 8. What is NOT done — and the queues nobody ran

**This slice is built and unverified.** 12 browser rows (62–73) and 9 mutation rows are queued and
**zero have been run**, per §65. Read that as the status, not as a formality:

- **Three of the mutation rows are tests that do not exist yet**, and they are named as such. The
  `ownConditionIds` one guards the inherited-row defect above — the editor's tests build cases with
  no inherited conditions, so nothing currently fails if that filter is deleted. The bulk-hash row
  and the retired-bound row are the other two.
- **Browser rows 68 and 69 are live-only** and carry the real risk: mock mode has one project, so a
  sibling project's condition and a suite attachment's true reach are structurally unreachable in it.

Still not built:

- **No delete.** Retiring a condition is a soft delete the schema supports and no endpoint offers.
  Out of scope deliberately — a retire is only meaningful once something can attach, which is what
  this slice just built.
- **Inline conditions.** Refused with a field error rather than implemented, because nothing can
  render one. When a reader exists, `CaseConditionInput` is where the second arm lands.
- **Suite-level condition authoring.** `suite_condition` still has no writer, so the inherited rows
  the editor renders can only be created by a fixture. That is the natural next slice and it is what
  makes `suiteUsageCount` mean something in practice.
- **`CaseUpdateRequest` still refuses three fields** — `ownerId`, `tags`, `automationTags`. Tags are
  §96's.
- **`condition_title_trgm_idx` is still spelled on the bare column** while `findLibrary` filters
  `LOWER(title)`, so it is never consulted — §94 §4 found it, declined to fix it for a reason that
  still holds, and this slice does not change that.
