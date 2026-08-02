# §72. The run lifecycle — the status nothing wrote, and §67's deferral closed

**Date:** 2026-08-02 · **Branch:** `master` · **No migration; `V22` is still free.**

## What this slice is

`test_run.status` has been written **exactly once in the product's life** — to `NOT_STARTED`, by
`TestRun.create` — since the run module existed. `grep` for any other write to `status`, `started_at`
or `completed_at` on that entity returned nothing.

So on every real instance, **every run has always read "Not started"**, including after every one of
its results had been recorded. `started_at` and `completed_at` are columns nothing has ever filled.

It is not a gap anybody had to discover: three production javadoc blocks name this slice by name.

- `TestRun`: *"Still no mutator: closing a run is `run:close`, a Lead-only permission and a separate
  slice."*
- `RunDetailPage.tsx` carries a comment stating the rule it renders — *"a run starts when somebody
  records the first result, not when it is named (see `TestRun.create`)"* — and **nothing
  implemented it**. The comment describes behaviour the product did not have.
- `Evidence`: *"The invariant belongs with `run:close`, as one rule over both writes at once."*

## The fourth thing, which is the part that generalises

**This is not §52's "mock agrees with itself".** The MSW mock had the same hole: it never advanced a
run's status either. Mock mode nonetheless *looked* correct, because the fixtures ship runs that are
already `IN_PROGRESS` with a `startedAt` — so a run **created** in mock mode was as stuck as a run
created live, and nobody looked at one.

Both sides were wrong in the same direction, and the fixture hid it from both. §52's lesson was
*two implementations of one shape drift*; this one is *one shape, two implementations, both absent,
and a fixture that makes the absence invisible*. The check that would have caught it is not a
contract check — it is that **the mock's fixtures never sit in the state a fresh install produces**.

## `run:close` is the fifth permission with no consumer in Java — and the first that discriminates

After §62's `case:approve`, §64's `library:manage`, §69's `company:vocabulary` and §71's
`company:teams`. The pattern is now so reliable it is a search, and it was run as one this session:
of 55 seeded keys, 30 still have no consumer, most of them legitimately (M2.5's `report:*`, M3's
`integration:manage`, the instance tier).

**§71 recorded that its mutation could not be written**, because `V3` grants `company:teams` and
`company:members` to exactly the same roles, so no persona the suite can build tells them apart.
`run:close` is the opposite and that is worth stating: `V3` grants it to Lead and up and **withholds
it from Test Engineer**, who holds `run:execute` and `result:submit`. So the gate mutation
(`run:close` → `result:submit`) reddens **exactly 1 test**, and the discrimination §71 had to record
as a coincidence is here a property the seeded catalogue actually has.

## The decisions

**1. "Closed" is a status *pair*, not a fifth value.** `V1` says evidence is *"immutable once the run
closes"*; `V3` describes the permission as *"Close or abort a test run"* — two acts — and `RunStatus`
already has exactly two terminal values whose difference its own javadoc explains (a run stopped
halfway has results that are real and a scope never covered). So **closed ≡ `COMPLETED` ∨ `ABORTED`**,
and no `CLOSED` value is added. A fifth value would give the product two ways to say a run is over,
and every reader — the run list, plan statistics, M2.5's reporting — would have to know both.
`Evidence`'s javadoc cited the *absence* of a `CLOSED` value as evidence there was no closing act;
the permission's own description settles it the other way.

**This is why there is no migration.** `V22` is still free.

**2. Starting is automatic; finishing is not.** `NOT_STARTED → IN_PROGRESS` happens in
`ExecutionService.record`, stamped with **the result's own instant** (not a fresh clock read — two
instants microseconds apart would let a run's start sort after the first result recorded in it), and
needs no permission of its own: the caller has just passed `result:submit`, and a second gate there
would mean a tester records a result the run declines to acknowledge.

The reverse is deliberately **not** symmetric. A run does not complete itself when the last result
lands, because "we are done" is a judgement rather than an arithmetic fact — a scope may be
deliberately part-executed — and **auto-completion would let a Test Engineer close a run by finishing
it**, which is exactly the boundary `run:close` draws (roles spec §A4, creative versus destructive).

**3. Reopening exists, and the target is derived rather than chosen.** Without it a mis-click is
unrepairable through the API, which is the bound §68 recorded for a retired classification and §69
for a retired value — this project's recurring finding. A run reopens to `IN_PROGRESS` if it ever
started and to `NOT_STARTED` if it did not. **Both live spellings are accepted from the client and
normalized on the server**, so a client cannot produce the row this rule exists to forbid: an
`IN_PROGRESS` run with a null `started_at`. The SPA always sends `IN_PROGRESS` and lets the server
answer `NOT_STARTED`, rather than holding a second copy of the rule and applying it to a cached
`startedAt` that may be one save behind.

**4. Two transitions are refused, and both refusals are the feature.**

- **`NOT_STARTED → COMPLETED`** — a run with no results is not a pass over its scope, and recording
  it as one puts a 0%-executed run into every report beside runs that were actually run. `ABORTED` is
  the value that exists for this, and the message says so.
- **One terminal state → the other** — a run that slid from completed to aborted leaves no record of
  having been the first: the audit trail shows one ending and the row shows another. Reopening is one
  extra call and it makes both endings real events.

**5. Same status in, 200 out, nothing written.** `DocPageWriteService#setStatus`'s call, for its
reason — two leads clicking Complete is agreement, not a conflict. Nothing is re-stamped and **no
audit row is written**, or a repeated click buries the change that actually happened.

**6. No `If-Match`.** Every other write in this module carries one; this one does not, because the
request names an **absolute** target state rather than a delta against something the client read. A
lost update needs a value derived from a stale read and there is none here. Same call as
`PUT /doc-pages/{pageId}/status`.

## §67's deferral, closed — and the mutation that proves both halves

§67 declined to implement `V1`'s *"immutable once the run closes"* and wrote down precisely what
would have to be true first: that closing a run be a real act, and that `ExecutionService.record`
refuse to amend a result on a run that has ended. Both are true now, so the rule lands on **all three
writes at once** — the verdict, the attachment and the removal — rather than making the screenshot
stricter than the result it documents.

Three placement decisions inside that:

- **After the permission, not before.** A Viewer on a closed run still gets **403**, not 409. The
  refusal is about the target's state, and telling a caller who may not write at all about the run's
  state instead of about their own permission is the wrong answer. Structurally enforced: the check
  sits inside the gate's callback, where `ExecutionService`'s header already puts validation.
- **Before the `If-Match` check.** A stale header is something the caller can fix and resubmit; this
  is not, and reporting the fixable problem first sends them round a loop that cannot close.
- **Before the authorship check on the delete.** A closed run refuses **everybody, the author
  included**. Answering *"this is not yours"* first would tell an author that being the author was
  what they were missing, and send them to ask a lead who also cannot do it.

That last one is what the fourth mutation tests: dropping the freeze from the delete path *only* —
the realistic half-applied fix, and the exact shape §67 argued against — reddens exactly 1 test.

## The duplicate the slice had to delete before it could start

`ExecutionService#resolve` and `EvidenceService#resolve` were **byte-identical private copies**, and
`EvidenceService`'s own javadoc said so: *"the same three predicates `ExecutionService#resolve`
applies, and for the same reasons."* A hand-maintained copy under an instruction to keep it in step —
§58's E1, §70's verb list, §62's `CaseRenderer`, and the fix is the same every time: **delete the
copy**.

It became a defect the moment both needed the same change, which is this slice: the freeze has to see
the **run**, and the old signature threw it away. Hence `ExecutionTargets`, returning
`(run, execution)`. Deliberately **not** `@Transactional`, unchanged from the copies — `EvidenceService.open`
calls it from a method that must not be in a transaction, because it returns an open stream the
controller writes to the response afterwards.

## Two pre-existing defects found on the way

**`RunListPage`'s status colour map was typed `Record<string, string>`, and had drifted exactly the
way `lib/labels.ts`'s header predicts.** It carried a **`PLANNED`** that is not a run status at all,
and was **missing `NOT_STARTED`** — so on a real instance, where every run has always been
`NOT_STARTED`, *every run in the list rendered with the grey fall-through*. Both screens also spelled
the status with `status.replace(/_/g, ' ').toLowerCase()` rather than a label map. `runStatusLabels`
and `runStatusColors` now live in `lib/labels.ts` with `satisfies Record<RunStatus, string>`, which
makes the invented value a compile error and the missing one mandatory. This is CLAUDE.md's
shared-layer rule catching a case that had slipped past it for the life of both screens.

**`ExecutionService` writes its audit `target_type` as `"TEST_EXECUTION"` while every other service
in the module writes the table name in snake_case** (`test_run`, `test_plan`, `test_case`). Nothing
reads `target_type` yet — `audit:view` and `project:audit` both have zero consumers — so this is
latent rather than live, and it is **found and not fixed**: changing it would split existing rows
across two spellings, and the right repair is a sweep when the audit screen is built. §72's own new
rows use `"test_run"`, with a comment at the point of the decision. Same family as §45's absent row:
invisible to every reading, and it will present as an audit filter that silently matches nothing.

## What shipped

**Backend** — `ExecutionTargets` (the extracted resolve), `RunLifecycle` (the pure transition table
and the one copy of the frozen-run sentence), `RunStatusRequest`, `RunStatusService`,
`PUT /projects/{projectId}/runs/{runId}/status`, `TestRun.begin`/`applyStatus`/`isClosed`,
`updated_by` mapped for the first time, `Outcome.RunClosed` on two sealed interfaces (which was a
compile error at three switch arms — the property working), audit actions `run.started`,
`run.closed`, `run.aborted`, `run.reopened`.

**Contract** — the operation, `RunStatusInput` with its `required`, and `409` added to the evidence
attach and delete operations. The `PATCH` execution op already declared 409 for the stale `If-Match`;
its description now names the second cause.

**Frontend** — `useSetRunStatus`, `RunLifecycleActions` on the run detail (Complete / Abort swapping
to Reopen), the frozen state on the execution screen and both evidence controls with the reason on
the tooltip, `runStatusLabels`/`runStatusColors`/`isRunClosed` in the shared layer, and the mock's
own transition handler **plus its missing start-on-first-result**.

**The mock mirrors the server's ORDER, not just its rules** — the freeze after the permission on
attach, before authorship on delete. A mock that gets the order backwards teaches a 409 where live
gives a 403, which is the divergence class this project keeps finding.

## Gates

`./gradlew build` — **1325 tests, 0 failures, 0 skipped** (1302 → 1325). `npm run build` clean,
`npm run lint` 0 warnings, `npm run test:run` **561/561** (554 → 561: 7 written, and the +2 from 552
before them were the two data-driven contract checks firing on the one new `api.*` call site —
**the generated arithmetic was checked rather than noted**, §70's lesson on its second opportunity).

**Seven mutations run, three queued.** Four backend (exactly 1 / 2 / 2 / 1 red) and three frontend
(exactly 1 each). The frontend ones were not optional: **all 7 tests passed on the first run**, which
§66 established is the moment to distrust them.

## Not browser-verified

The seventh slice in the deferred queue. Rows are in `docs/verification/frontend-browser-checks.md`,
and **the live-only ones lead** for the inverse of §71's reason: the state this slice fixes is one
**live mode has always been in and mock mode has essentially never been in**.
