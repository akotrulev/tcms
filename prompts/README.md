# Slice prompts

One file per area of `docs/plans/2026-08-18-unplanned-gaps.md`, which closes the gaps recorded in
`docs/features.md` § *Gaps Not Currently Planned*.

**Each file is one Claude session.** Paste its `## Prompt` block into a fresh session; everything
outside that block is context for the reader, not part of the paste.

## Start here

**[`batch-3-c-and-f.md`](batch-3-c-and-f.md)** — the current entry point. Two lanes, C ∥ F, and
they are **not symmetric**: C has a stepped plan and only executes it, while F has no plan at all
and must run `ep-writing-plans` first. Needs the user's explicit permission: two concurrent
subagents, and batch 2's permission does not carry over.

[`batch-2-b-and-e.md`](batch-2-b-and-e.md) — **done 2026-08-22.** Two implementation lanes, B ∥ E.
Landed both areas in ten commits, plus one correction commit (`7f835ee`); see the state table.

[`batch-1-five-lanes.md`](batch-1-five-lanes.md) — **done 2026-08-22.** One implementation lane
(Area A) plus four planning lanes (B, C, D, E). Landed Area A in four commits and four stepped
plans; see the state table below.

The per-area files below are still accurate for scope and traps, but their **numbering and Flyway
assignments are superseded** by the master plan's 2026-08-22 revision, and for B, C, D and E the
**stepped plan supersedes the prompt file entirely**.

## Nothing here is gated

Since batch 1, **subagents run no tests and no gates.** Verification is one pass on `shanks` that
runs after every lane in a batch has finished, driven by the user. So a lane that reports "landed"
means *implemented and completely ungated* — on top of whatever sits unrun in the §65 queues.
**Areas A, B and E are all in that state now** — and B and E have never executed a single test,
where A at least had two targeted classes run before the directive arrived. Say it that way in every
prompt you write from here on.

## State

| # | Prompt | Area | Lane | Migration | State |
|---|---|---|---|---|---|
| 1 | [`01-audit-log-reader.md`](01-audit-log-reader.md) | — | backend + frontend | — | ✅ **shipped** — `docs/handoff/242` |
| 2 | [`02-review-notifications.md`](02-review-notifications.md) | — | backend | — | ✅ **shipped** — `docs/handoff/243` |
| 3 | [`03-execution-duration.md`](03-execution-duration.md) | **A** | backend + frontend | **none** | 🟡 **landed ungated** — `34314a8`, `docs/handoff/244` |
| 4 | [`04-bulk-execution-assignment.md`](04-bulk-execution-assignment.md) | **B** | backend + frontend | — | 🟡 **landed ungated** — `3f68100`, `docs/handoff/245` |
| 5 | [`05-scheduled-runs.md`](05-scheduled-runs.md) | **C** | backend + frontend | **`V68`** | **planned** → [gap-c](../docs/plans/2026-08-18-gap-c-scheduled-runs.md) · **batch 3** |
| 6 | [`06-cross-project-case-search.md`](06-cross-project-case-search.md) | **D** | backend | — | **planned** → [gap-d](../docs/plans/2026-08-18-gap-d-cross-project-case-search.md) · batch 4, alone |
| 7 | [`07-company-data-export.md`](07-company-data-export.md) | **E** | operator CLI | — | 🟡 **landed ungated** — `61cad11`, `docs/handoff/246` |
| 8 | [`08-keyboard-driven-execution.md`](08-keyboard-driven-execution.md) | **F** | frontend | — | **unplanned** — plans itself in **batch 3** |
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

**Every number is now spoken for, so pick nothing yourself.** Sections: `§242`–`§246` are spent,
`§247` is C's and `§248` is F's. Fixture prefixes: `0a`, `66`, `88`, `7c` are spent, `3d` is C's and
`2c` is F's; **`5e` is already in use in the tree and is not a spare.** `V68` is C's and is the only
migration in the plan.

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
