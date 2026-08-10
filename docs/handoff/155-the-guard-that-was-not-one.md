# §155 — the guard two entities mapped and neither maintained, and the wrong instrument for the right race

**Date:** 2026-08-09 · **Branch:** `master` · **One phase** · **`backend/` only: no migration, no
contract change, no frontend or `packages/` source.**

§154's recorded next slice, `RL-7`: *`test_run.lock_version` and `work_item.lock_version` are mapped
without `@Version` — add it.* Taken, audited, and **it is not what shipped.**

## The rule first, because the finding is that this was kept by hand for eighty slices

`ArchitectureRulesTest.everyLockVersionColumnIsAVersion` fails the build when an entity maps a
column named `lock_version` without `@Version`. Fourth in §152/§153/§154's family, and the one with
the longest paper trail: **§74 asked this question by hand.** It found three entities in that state,
annotated three, left two — and the note about the two went into a javadoc on a *third* entity,
`TestPlan.lockVersion`, where it sat from §74 to §155. Eighty slices of a written, correct,
unactionable finding. Prose is not a check.

**What the state costs is not waste, it is a guard that cannot fire.** A mapped `lock_version`
Hibernate does not maintain reads `0` on every row for ever, and that is the value an `If-Match` is
compared against. `TestPlan` is the recorded near miss: the column had been mapped since M2 slice 2
and `lockVersion` had been *required* by the contract just as long, so the edit endpoint came within
one slice of shipping a concurrency check that was structurally incapable of failing — the client
sends 0, the row holds 0, the precondition passes over any number of intervening saves, and no test
named "a stale header is refused" goes red, because there is no such thing as a stale 0.

**The rule does not say "annotate it."** It says the two states must not be confused, and unmapping
is the other way out. §154's own comment named this rule as the thing that did not exist yet and
named the two columns its text corpus structurally cannot see — a native query mentioning *another*
table's `lock_version` marks every entity's as read. This is a different question from readers, so
it is a different rule.

**Measured, and §154's tally was wrong in the direction its own slice was about.** §154 recorded
"nineteen other entities carry `@Version` on the identically-named column". It is **17 mappings, 15
annotated.** A hand-kept count being off inside the slice arguing against hand-kept counts is the
joke this family keeps telling. The population of violators is now empty, so the rule asserts empty
— no by-decision map, unlike §153's and §154's, because pinning an empty population pins nothing.

## The audit: the two columns are not the same shape, and neither wanted `@Version`

`ApiToken`'s "No `@Version`" header is the rubric already in this repository: **is a lost update here
a discarded edit, or a duplicated intent?**

**`WorkItem` — duplicated intent, and annotating it would have been a 500.** The only mutator is
`moveTo`, an *absolute destination* chosen by somebody dragging a card. Two people dropping the same
card in two columns both get what they asked for and the later drop is the answer, which is the
argument `openapi.yaml` already makes on three status endpoints for taking no `If-Match`. Nothing on
that row is ever derived from a stale read, so there is nothing to detect — and
`WorkItemController` has **no `OptimisticLockingFailureException` handler**, so `@Version` would have
turned a board race that resolves correctly today into a 500. Unmapped, with the argument at the
class and the condition that would change it (the field edit the contract calls "a separate slice
with no affordance yet") named there too.

**`TestRun` — a real discarded edit, and `@Version` is still the wrong instrument.** This is the one
aggregate in the product with two concurrent writers that are **not the same act**, which ERD §5 says
is the ordinary case rather than the race:

| Writer | Sets | Who |
|---|---|---|
| the first result | `status`, `started_at` | any tester, on every result recorded |
| `PUT /runs/{runId}/status` | `status`, `completed_at` | a lead, deliberately |

They overlap on `status` **alone**, and a full-row `UPDATE` from either side discards the other's
column. Each order loses something different, and both were live:

- **Start after close.** The recording transaction read `NOT_STARTED`, a lead committed
  `COMPLETED`, and `TestRun.begin`'s Java guard — asked against that stale snapshot — said yes. The
  write then set the run back to `IN_PROGRESS` with `completed_at` cleared. **A run closure erased,
  two 200s, and nothing anywhere to read afterwards.**
- **Close after start.** The mirror. The lead's entity was loaded before the first result landed, so
  its `started_at` is null, and the full-row write puts that null back over a run that demonstrably
  ran — `COMPLETED` with no start, and every duration this product computes reads that column.

`@Version` detects both, at a price `TestPlan`'s header had already named: it makes **the most-used
write in the product** a contention point. Two testers' first results in flight at once would collide
on a row neither of them was editing, and one legitimate verdict would roll back with *"Someone else
recorded a result first — reload the execution to see their result"*, pointing at an execution that
is not the one they were looking at.

**So the guards are the two the races actually want**, and neither is a counter:

1. `TestRunRepository.startIfNotStarted` — a conditional `UPDATE … WHERE status = NOT_STARTED`.
   Postgres re-evaluates that predicate against the row as it is at write time, under the lock the
   statement takes, so a run somebody closed in the meantime matches nothing. The same property
   makes it idempotent, so the second of two simultaneous first results waits, re-reads, and reports
   **0 rows** — a no-op rather than a 409. `TestRun.begin` is deleted; the rule that was in its `if`
   is in the `WHERE`. **The audit trail gets exact as a side effect**: `run.started` is written on a
   return of 1, so two racing first results produce one row between them rather than one each.
2. `@DynamicUpdate` on `TestRun` — a mutator that does not touch a column no longer names it in the
   statement, so `applyStatus` stops writing `started_at` back and the two writers stop colliding on
   the columns they do not share.

**The window that is left is named rather than closed.** `ExecutionService` refuses a result on a
closed run from the same read that could go stale, so a verdict recorded in the millisecond after a
lead's close still lands. Closing that needs a row lock on the run for every result — the cost the
conditional update exists to avoid — and the tester had finished the work before the close. It is
recorded here and at neither site pretended away.

## What `RunController.onWriteRace` actually is

§154 said it "exists and has never been reachable". That is half right and the half matters: the
handler is on the controller that also serves `PATCH …/executions/{executionId}`, and
`TestExecution` **does** carry `@Version` — so the handler is reachable, by the interleave its own
javadoc describes, on the execution rather than on the run. Nothing about this slice changes it, and
the run never gains a path to it, which is the point.

## Two documents that were false the moment this landed, corrected in the same commit

§145's lesson, sixth instance. `TestPlan.lockVersion` and `project.Review.lockVersion` each named
`TestRun` and `WorkItem` as still-latent instances of §74's finding. Both are struck through and
corrected at the field rather than deleted, so the next reader sees the argument that was made and
what replaced it.

## The gate found something that was not about lock columns at all

Eleven tests in `ExecutionEndpointTest` failed on `AccessDeniedException` writing to
`/tmp/tcms-evidence-test/…`, on a tree this change does not touch. The cause: the suite's storage
roots are a fixed path under the JVM temp directory, the fixture ids above the random key are
**hand-assigned prefixes and therefore identical on every machine and in every checkout**, and this
box runs the nightly Linux lane as `ghrunner` and a developer as themselves. Whoever runs
`ExecutionEndpointTest` first creates `e2000000-…/e2007000-…` mode 0755; the second user gets eleven
failures with nothing to do with what they changed. The roots carry `user.name` now, and the reason
is at the registration.

## Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **SUCCESSFUL — 2412 tests across 501 result classes, 0 failures.** §155 adds **+4 tests, no new class**: one rule in `ArchitectureRulesTest`, three in `ExecutionEndpointTest`. |
| `frontend/`, `packages/` | unchanged — not run |

Rows `LV-1`…`LV-6` in `docs/verification/backend-mutation-checks.md`; **`LV-1` and `LV-2` run**, both
red for the right reason and reverted — `LV-2` is a **MORE** result and the two extra reds are the
right ones. **No browser row**: no request, response or screen differs. The two columns were never on
the wire, and a race the SPA could not provoke through one browser tab is not a browser check.

## What is next

The two entity headers name their own conditions. Otherwise unchanged from §154: the browser queue,
`TeamRef`, another re-derivation, or the unrun mutation rows.
