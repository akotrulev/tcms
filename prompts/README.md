# Slice prompts

One file per area of `docs/plans/2026-08-18-unplanned-gaps.md`, which closes the gaps recorded in
`docs/features.md` § *Gaps Not Currently Planned*.

**Each file is one Claude session.** Paste its `## Prompt` block into a fresh session; everything
outside that block is context for the reader, not part of the paste.

## Start here

**[`batch-4-d-alone.md`](batch-4-d-alone.md)** — the current entry point. **Two stages, and the
first blocks the second**: green the tree on `shanks` (five areas are ungated and one was never
compiled), then implement D **gated**, the way slices ran before batch 1. One lane, so the standing
one-subagent cap applies again — batch 3's permission was for batch 3.

[`batch-3-c-and-f.md`](batch-3-c-and-f.md) — **done 2026-08-22.** Two lanes, C ∥ F, asymmetric: C
executed its stepped plan, F wrote its own first. Landed both areas in twenty-three commits
(`f5bc206`..`356ec13a`); see the state table.

[`batch-2-b-and-e.md`](batch-2-b-and-e.md) — **done 2026-08-22.** Two implementation lanes, B ∥ E.
Landed both areas in ten commits, plus one correction commit (`7f835ee`); see the state table.

[`batch-1-five-lanes.md`](batch-1-five-lanes.md) — **done 2026-08-22.** One implementation lane
(Area A) plus four planning lanes (B, C, D, E). Landed Area A in four commits and four stepped
plans; see the state table below.

The per-area files below are still accurate for scope and traps, but their **numbering and Flyway
assignments are superseded** by the master plan's 2026-08-22 revision, and for B, C, D and E the
**stepped plan supersedes the prompt file entirely**.

## The no-gates directive ended with batch 3

Batches 1–3 ran **no tests and no gates**, by direction: ship while requirements move, verify in one
concentrated pass. That pass never happened, and it accumulated — **five areas are implemented and
completely ungated (A, B, E, C, F), and C was never even compiled.** So batch 4 opens by running the
gates on `shanks` before it adds anything, and **D is gated as it goes.**

Two different things have been called "the pass" and they are not the same:

- **The gates** — `./gradlew build`, the npm suites, `lint`, via `~/bin/tcms-remote`. Mechanical,
  agent-runnable, and **batch 4 stage 1 does them**.
- **The §65 queues** — 56 backend mutation rows and 36 browser rows, driven by hand against a live
  stack. Still batched, still deferred, still the user's own sitting. **They do not block D.**

A lane from batches 1–3 that reports "landed" means *implemented and completely ungated*. Keep
saying it that way about those five until stage 1 says otherwise.

## State

| # | Prompt | Area | Lane | Migration | State |
|---|---|---|---|---|---|
| 1 | [`01-audit-log-reader.md`](01-audit-log-reader.md) | — | backend + frontend | — | ✅ **shipped** — `docs/handoff/242` |
| 2 | [`02-review-notifications.md`](02-review-notifications.md) | — | backend | — | ✅ **shipped** — `docs/handoff/243` |
| 3 | [`03-execution-duration.md`](03-execution-duration.md) | **A** | backend + frontend | **none** | 🟡 **landed ungated** — `34314a8`, `docs/handoff/244` |
| 4 | [`04-bulk-execution-assignment.md`](04-bulk-execution-assignment.md) | **B** | backend + frontend | — | 🟡 **landed ungated** — `3f68100`, `docs/handoff/245` |
| 5 | [`05-scheduled-runs.md`](05-scheduled-runs.md) | **C** | backend + frontend | **`V68`** | 🟡 **landed ungated** — `356ec13a`, `docs/handoff/247`. **Never compiled.** |
| 6 | [`06-cross-project-case-search.md`](06-cross-project-case-search.md) | **D** | backend | — | **planned** → [gap-d](../docs/plans/2026-08-18-gap-d-cross-project-case-search.md) · **batch 4**, alone, **gated** |
| 7 | [`07-company-data-export.md`](07-company-data-export.md) | **E** | operator CLI | — | 🟡 **landed ungated** — `61cad11`, `docs/handoff/246` |
| 8 | [`08-keyboard-driven-execution.md`](08-keyboard-driven-execution.md) | **F** | frontend | — | 🟡 **landed ungated** — `7a19b263`, `docs/handoff/248`. Planned itself in batch 3. |
| 9 | [`09-i18n-groundwork.md`](09-i18n-groundwork.md) | **G** | frontend | — | unplanned · batch 5, alone, last |

## Corrections these prompt files predate

**`03-execution-duration.md` claims it needs `V68` to add a duration column. It does not.**
`test_execution.duration_ms BIGINT` has existed since `V1__initial_schema.sql:845`, is mapped at
`TestExecution.java:96`, served at `openapi.yaml:10848` and summed at `RunSummaryReport.java:396`.
The real gap is one field wide: `ExecutionResultRequest` carries no duration, so only ingestion can
write the column. **Area A adds no DDL.**

**That frees the number, so `V68` belongs to scheduled runs** — not `V69` as `05-scheduled-runs.md`
says. `V68` is now the **only** migration in the whole plan. `out-of-order` is unset; leave gaps
rather than renumbering.

**A third, found while implementing A: `duration_ms` had no writer at all.** Not "ingestion since
V1", as the master plan and `docs/features.md` both said — the parsers recovered a duration and
`IngestionService` stashed it in the ingestion *job's* payload JSON, never in the column. Five
readers answered null for a year. Area A gave it a writer on both paths. The reason the gate missed
it is worth knowing before you trust that gate: `ArchitectureRulesTest:1226` decides "written" by
matching the field *name* as text, and a JSON key of the same name satisfied it for the column.

**Every number is now spoken for, so pick nothing yourself.** Sections: `§242`–`§248` are spent and
**`§249` is D's**. Fixture prefixes: `0a`, `66`, `88`, `7c`, `3d`, `2c` are spent and **`4d` is D's**;
**`5e` is already in use in the tree and is not a spare.** `V68` was C's and was the only migration
in the plan — **head is `V68` now**, and D deliberately takes no version, recording its index as
owed instead.

**`docs/plans/2026-08-18-gap-d-cross-project-case-search.md` is stale in three places** and batch 4's
prompt says so: it names `§243` as the cache header (it is `§247`) and as the section head (it is
`§248`), and reasons about `V68` as unspent. Its decisions are unaffected; its numbers are not.

**A section number written into source comments is load-bearing.** Area B's plan was authored as
`§244`, area A took that number first, and the lane renumbered its handoff but left thirteen
in-source citations across five files crediting the wrong slice — fixed in `7f835ee`. References
resolve by filename prefix here, so write the assigned number as you go rather than at close.

**`E` no longer waits for `C`.** `07-company-data-export.md` and the master plan both order E after
C so the export covers `run_schedule`. E's stepped plan derives its table list at run time from
`pg_policies` / `information_schema`, so `run_schedule` exports itself the moment `V68` lands, with
no code change in E.

## Order

The order is a dependency order, not a preference — the reasoning is in the master plan's order
table and its parallelism matrix. **Areas F and G may not be planned early**: F binds controls A and
B have not built, and G sweeps a surface A–F are about to move.
