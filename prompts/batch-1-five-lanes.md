# Batch 1 — five parallel lanes

**Written 2026-08-22.** Supersedes the sequential run order in `README.md` for the *first batch only*.

**One implementation lane and four planning lanes.** This is the largest batch this plan can absorb
safely, and it is not five implementation lanes — the master plan's parallelism section shows the
implementation areas top out at two concurrent lanes (`A ∥ E`). What five agents *can* do is the
work the plan already requires before any area starts: each area past A needs its own
`ep-writing-plans` pass, those passes are **read-only against code**, and each writes **one new
file**. That is a genuinely disjoint fan-out.

**Lanes 2–5 write plans, not code.** If a planning lane edits a source file, it has exceeded its
brief.

**Areas F and G are deliberately excluded.** F binds controls that A and B have not built yet, and G
sweeps a surface A–F are about to move. Planning either today would be invention, which is the thing
the master plan's Scope Check exists to prevent.

## Pre-assigned resources — do not choose your own

| Lane | Area | Fixture UUID prefix | Flyway | Contract |
|---|---|---|---|---|
| 1 | **A** Execution duration | `66` | — | **holds `openapi.yaml`** |
| 2 | **B** Bulk assignment | `88` | — | plan only — do not edit |
| 3 | **C** Scheduled runs | `e7` | **`V68`** (pre-assigned) | plan only — do not edit |
| 4 | **D** Cross-project search | `4d` | — | plan only — do not edit |
| 5 | **E** Company export | `7c` | — | none needed, ever |

Spare, if a lane needs a second namespace: `2c`. All six were checked against
`backend/src/test/java`, `frontend/src` and `packages/` on 2026-08-22 and are unused. The hand-managed
prefix namespace has collided five times (§19…§62) and has no check behind it, which is why they are
assigned here rather than chosen per lane.

**Only lane 1 touches `openapi.yaml`, and only lane 1 runs `npm run gen:api`.** Lanes 2–5 write the
YAML fragments they need *into their plan documents* as fenced blocks, to be applied by the lane that
later implements that area. Two `gen:api` runs do not merge.

**Only lane 1 writes code, runs gates, or commits source.** Lanes 2–5 commit exactly one new file
each.

**Nobody touches `docs/agent-context/*.md` or `docs/HANDOFF.md` except lane 1.** One writer per file
is a binding rule, and the close is a conflict even between lanes whose code is disjoint.

---

## Prompt

Paste everything in this block into a fresh session. Everything above and below it is context.

> Read `CLAUDE.md`, then `docs/agent-context/be-java.md` and `docs/agent-context/fe-react.md`, then
> `docs/plans/2026-08-18-unplanned-gaps.md` — including its parallelism section — before doing
> anything else.
>
> You are running **batch 1** of that plan as **five parallel agents**. This is the explicit
> permission `docs/specs/parallel-work-rules.md` requires for more than one concurrent subagent. The
> batch is safe because four of the five lanes are read-only against code and each writes exactly one
> new file; only lane 1 writes source, edits `openapi.yaml`, runs gates or commits code.
>
> Launch these five lanes concurrently. Give each one only its own brief plus the shared constraints.
>
> **Lane 1 — implement Area A (Execution duration), tasks 1–6.**
> Area A is the one area already fully stepped in the master plan; do **not** run `ep-writing-plans`
> on it. Follow `ep-executing-plans` task by task. Its premise is a correction you must not undo:
> `test_execution.duration_ms` **already exists** (`V1__initial_schema.sql:845`, mapped at
> `TestExecution.java:96`, served at `openapi.yaml:10848`, summed at `RunSummaryReport.java:396`).
> **There is no migration in this area.** The gap is that `ExecutionResultRequest` has no duration
> field, so only ingestion can write the column. Use fixture prefix `66`. You are the only lane that
> may edit `openapi.yaml`, run `npm run gen:api`, run gates, or write `docs/agent-context/*.md` and
> `docs/HANDOFF.md`. Run every gate on `shanks` via `~/bin/tcms-remote` — never locally. Task 6
> requires correcting the false claim at `docs/features.md:392`.
>
> **Lane 2 — plan Area B (Bulk execution assignment), tasks 7–14.**
> Run `ep-writing-plans` on Area B alone and save
> `docs/plans/2026-08-18-gap-b-bulk-execution-assignment.md`. Write no code. Mirror the depth of
> `docs/plans/2026-08-18-gap-2-review-notifications.md`, which is the proven shape for this. Plan
> against the **post-lane-1** contract: assume `ExecutionResultInput` has gained an optional
> nullable `durationMs`. Two inherited debts from the shipped audit reader are in your scope and are
> recorded in `docs/handoff/242-the-audit-log-reader.md`: the `ArchitectureRulesTest` **writerless
> exemption** that your area's first `bulk_operation_id` writer makes false, and the `AuditLogPage`
> grouping that stops a 300-case assignment flooding the trail. Use fixture prefix `88`. Put any
> YAML in fenced blocks inside your plan — do not edit `openapi.yaml`.
>
> **Lane 3 — plan Area C (Scheduled runs), tasks 15–25.**
> Run `ep-writing-plans` on Area C alone and save `docs/plans/2026-08-18-gap-c-scheduled-runs.md`.
> Write no code and **create no migration file** — plan it. **`V68` is pre-assigned to you and is the
> only migration in the whole plan**; do not renumber it and do not add a second. The per-company
> scheduler must mirror `CaseHealthScheduler.recomputeAllCompanies()`
> (`backend/src/main/java/com/tcms/repository/CaseHealthScheduler.java:73-87`) — iterate companies,
> `CompanyContext.open(company.getId())`, catch per company, log the id, **continue** — not
> `OutboxScheduler`. Reuse the scope shape `V61` already stores on `test_run`; do not invent a second
> scope encoding. Your area is also what first makes verification row `§242 LG-5` (an audit act with
> `actorUserId` null) drivable — say so in the plan. Use fixture prefix `e7`.
>
> **Lane 4 — plan Area D (Cross-project case search), tasks 26–32.**
> Run `ep-writing-plans` on Area D alone and save
> `docs/plans/2026-08-18-gap-d-cross-project-case-search.md`. Write no code. This is the riskiest
> read in the plan and the plan must show why it is safe: **RLS scopes to the company, not the
> project**, so two projects in one company are both RLS-visible and the project-grant narrowing is
> entirely the application's job. The narrowing must happen **inside the query**, never in Java after
> an unrestricted read, or the count and the paging are computed over rows the caller may not see.
> Require a test asserting on the **total count**, not just the page. Reuse the visible-project
> narrowing already in `identity/OrgChartService.java` and `identity/AdminReach.java` rather than
> writing a second one, and read `/work-items` (`openapi.yaml:7071`) as the shipped cross-project
> precedent. Use fixture prefix `4d`.
>
> **Lane 5 — plan Area E (Company data export), tasks 33–40.**
> Run `ep-writing-plans` on Area E alone and save
> `docs/plans/2026-08-18-gap-e-company-data-export.md`. Write no code. This is an **operator CLI
> command** (`export-company --slug=… --out=…`) beside `provision-company`, **not** an HTTP endpoint —
> so your area needs no contract change at all, which is what makes it the one area eligible to run
> beside any other. Mirror the registration of the three commands already in
> `backend/src/main/java/com/tcms/provisioning/`. Three things must be settled in the plan, not during
> implementation: the export runs inside **one `CompanyContext` and one read-only transaction** (a
> table read outside context returns zero rows and the archive is silently short, which is worse than
> failing); **blobs live on the filesystem, not in Postgres**, so a table-only walk produces a manifest
> referencing bytes it did not include; and the **redaction list** — password hashes and token secrets
> must not be in the archive — decided up front and written down. Note that the export gains
> `run_schedule` only once Area C lands. Use fixture prefix `7c`.
>
> **Shared constraints, give these to every lane:**
> - Work on the current branch. Do not create a branch or a worktree.
> - Stage only the files your lane touched — `git add <paths>`, **never `git add -A`**. Concurrent
>   writers in this tree have silently lost committed work three times.
> - Lanes 2–5: exactly one new file each, no source edits, no `openapi.yaml`, no `gen:api`, no
>   `docs/agent-context/*.md`, no `docs/HANDOFF.md`, no gates.
> - Save plans under `docs/plans/` in this repo — never to a global directory.
> - Tests run on `shanks` via `~/bin/tcms-remote`, never on the Mac. Only lane 1 runs any.
>
> When all five report, review each plan against the master plan's task list for that area before
> anything from batch 2 starts.

---

## After the batch

Lane 1 lands Area A. Lanes 2–5 produce four stepped plans and no code. **Batch 2 is then a choice
between two eligible pairs**, per the master plan's matrix — `B` alone (it is the spine and shares
`ExecutionService.java` with nothing else running), or `B ∥ E` if a second lane is authorised again,
since E shares no contract, no migration and no frontend file with anything.

Update `README.md`'s run order once batch 1 lands.
