# ENT-003 — Test Run Lifecycle

**Created:** 2026-08-03 · **Owned by:** `ba-entity-state-grid`
**Derived from:** code by `be-documenter` @ `93444b2` on 2026-08-03
**Area:** AREA-004 (Test Execution)

`RunLifecycle.java:1-129`. Two live columns matter — the requested status, and whether the run
has ever started (`started_at` set) — because `NOT_STARTED` and `IN_PROGRESS` are both "open"
states told apart only by that timestamp.

## States

`NOT_STARTED` · `IN_PROGRESS` · `COMPLETED` · `ABORTED` — the last two are jointly "closed"; the
product deliberately has no separate fifth "CLOSED" value. `RunLifecycle.java:10-18` `#observed`.

## State × event grid

| Current \ Requested "reopen" (NOT_STARTED/IN_PROGRESS, either spelling) | Requested COMPLETED | Requested ABORTED |
|---|---|---|
| **NOT_STARTED** | Unchanged | Refused ("nothing recorded yet, abort instead") | Move (`run:close`) |
| **IN_PROGRESS** | Unchanged | Move (`run:close`) | Move (`run:close`) |
| **COMPLETED** (closed) | Move (`run:close`, reopen — target derived from `started_at`) | Refused (already closed, no direct swap) | Refused (already closed, no direct swap) |
| **ABORTED** (closed) | Move (`run:close`, reopen) | Refused | Refused |

`#observed`, `RunLifecycle.java:100-128`.

## Rules

- **R1 — Starting is automatic; finishing never is.** `NOT_STARTED → IN_PROGRESS` happens the
  moment the first result is recorded, with no separate permission — the tester already passed
  `result:submit`. The run never completes itself just because every case in scope got a
  result; "we are done" is a judgement call reserved to `run:close`.
  `RunLifecycle.java:19-30` `#observed`.
- **R2 — A run with zero recorded results cannot be marked COMPLETED** — it must be ABORTED
  instead, so a report never shows a 0%-executed run next to genuinely completed ones.
  `RunLifecycle.java:119-126` `#observed`.
- **R3 — COMPLETED and ABORTED cannot be swapped directly into each other.** Sliding straight
  from one terminal state to the other would erase the record that the run was ever in the
  first one; reopening first makes both endings real, separately audited events.
  `RunLifecycle.java:111-118` `#observed`.
- **R4 — Reopening a closed run is not a free choice of target** — it's derived: back to
  `IN_PROGRESS` if it ever started, back to `NOT_STARTED` if it never did. Both client
  spellings of "reopen" are accepted and normalized to the correct one, so an aborted-before-
  anyone-touched-it run can never come back claiming to have run.
  `RunLifecycle.java:32-41` `#observed`.
- **R5 — Ending or reopening a run is lead-only (`run:close`); recording results is not.**
  `RunController.java:158-165` `#observed`.
- **R6 — Once closed, results and evidence are frozen** until the run is reopened — one rule,
  enforced identically at three write points (record result, attach evidence, delete evidence).
  `RunLifecycle.java:47-58` `#observed`.

## Business intent

`#inferred`, stated directly in the code: closing a run is treated as a judgement about
release readiness, deliberately withheld from the tier that produces results, mirroring the
same creative-vs-destructive split used everywhere else in the permission model.
