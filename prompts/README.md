# Slice prompts

One file per area of `docs/plans/2026-08-18-unplanned-gaps.md`, which closes the gaps recorded in
`docs/features.md` § *Gaps Not Currently Planned*.

**Each file is one Claude session.** Paste its `## Prompt` block into a fresh session; everything
outside that block is context for the reader, not part of the paste.

## Start here

**[`batch-2-b-and-e.md`](batch-2-b-and-e.md)** — the current entry point. Two implementation lanes,
B ∥ E, both against plans that already exist. Needs the user's explicit permission first: two
concurrent subagents, and batch 1's permission does not carry over.

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
**Area A is in that state now.** Say it that way in every prompt you write from here on.

## State

| # | Prompt | Area | Lane | Migration | State |
|---|---|---|---|---|---|
| 1 | [`01-audit-log-reader.md`](01-audit-log-reader.md) | — | backend + frontend | — | ✅ **shipped** — `docs/handoff/242` |
| 2 | [`02-review-notifications.md`](02-review-notifications.md) | — | backend | — | ✅ **shipped** — `docs/handoff/243` |
| 3 | [`03-execution-duration.md`](03-execution-duration.md) | **A** | backend + frontend | **none** | 🟡 **landed ungated** — `34314a8`, `docs/handoff/244` |
| 4 | [`04-bulk-execution-assignment.md`](04-bulk-execution-assignment.md) | **B** | backend + frontend | — | **planned** → [gap-b](../docs/plans/2026-08-18-gap-b-bulk-execution-assignment.md) · batch 2 |
| 5 | [`05-scheduled-runs.md`](05-scheduled-runs.md) | **C** | backend + frontend | **`V68`** | **planned** → [gap-c](../docs/plans/2026-08-18-gap-c-scheduled-runs.md) · batch 3 |
| 6 | [`06-cross-project-case-search.md`](06-cross-project-case-search.md) | **D** | backend | — | **planned** → [gap-d](../docs/plans/2026-08-18-gap-d-cross-project-case-search.md) |
| 7 | [`07-company-data-export.md`](07-company-data-export.md) | **E** | operator CLI | — | **planned** → [gap-e](../docs/plans/2026-08-18-gap-e-company-data-export.md) · batch 2 |
| 8 | [`08-keyboard-driven-execution.md`](08-keyboard-driven-execution.md) | **F** | frontend | — | after A and B |
| 9 | [`09-i18n-groundwork.md`](09-i18n-groundwork.md) | **G** | frontend | — | last, always |

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

**`E` no longer waits for `C`.** `07-company-data-export.md` and the master plan both order E after
C so the export covers `run_schedule`. E's stepped plan derives its table list at run time from
`pg_policies` / `information_schema`, so `run_schedule` exports itself the moment `V68` lands, with
no code change in E.

## Order

The order is a dependency order, not a preference — the reasoning is in the master plan's order
table and its parallelism matrix. **Areas F and G may not be planned early**: F binds controls A and
B have not built, and G sweeps a surface A–F are about to move.
