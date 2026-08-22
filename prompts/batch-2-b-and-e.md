# Batch 2 — B ∥ E

Written 2026-08-22, after batch 1 landed. Supersedes the sequential run order in `README.md` for
this batch only.

Two implementation lanes. Both areas are already planned — batch 1's lanes 2 and 5 wrote
`docs/plans/2026-08-18-gap-b-bulk-execution-assignment.md` (2,214 lines) and
`docs/plans/2026-08-18-gap-e-company-data-export.md` (1,390 lines) — so neither lane runs
`ep-writing-plans`. Both run `ep-executing-plans` against a plan that already exists.

**B ∥ E is the master plan's own verdict.** E shares no contract, no migration, no frontend file and
no backend module with anything; it is the one area in the plan eligible beside any other. B is the
spine and holds the contract alone.

**This is still two concurrent subagents, so it needs the user's explicit permission before it
starts** — the standing cap is one. Batch 1's permission does not carry over.

## What changed under batch 1 that this batch inherits

**Area A is implemented and COMPLETELY UNGATED.** Four commits (`96f9764`, `505e51b`, `b7680ef`,
`34314a8`), no gate of any kind run against them beyond two pre-directive targeted test runs. B
edits `ExecutionService.java` and `ExecutionPage.tsx` on top of that unverified work. If the
post-batch pass reddens, the cause may be in A rather than in B — check A first, not last.

**`duration_ms` had no writer at all, not "ingestion since V1".** The master plan and
`docs/features.md` both said ingestion wrote it; it did not. Lane 1 gave it one. The false premise
survived because `ArchitectureRulesTest`'s writerless sweep decides "written" by *name text*
(`:1226`, `namedIn(writeText, null, field.getName())`), and `IngestionService:756`'s JSON key
`node.put("durationMs", …)` satisfied the sweep for the *column*. **That hole is unfixed and is not
this batch's job** — but B's Task 8 touches the same rule, so do not trust its verdict without
reading how it decides.

**E does not have to wait for C.** The master plan ordered E after C so the export would include
`run_schedule`. E's plan derives its table list at run time from `pg_policies` /
`information_schema` rather than listing tables in Java, so `run_schedule` exports itself the moment
`V68` lands, with no code change in E. C is still unplanned-into-code and unscheduled; that is fine.

## Pre-assigned resources — do not choose your own

| Lane | Area | Fixture UUID prefix | Flyway | Contract |
|---|---|---|---|---|
| 1 | **B** Bulk execution assignment | `88` | — | **holds `openapi.yaml`** |
| 2 | **E** Company data export | `7c` | — | none needed, ever |

Both prefixes are the ones their plans were written against — keep them. Spare, if a lane genuinely
needs a second namespace: `2c` (still untaken; batch 1's lane 1 did not use it).

**`V68` belongs to Area C.** Neither lane in this batch may create a migration. If either finds it
needs DDL, that is a finding to report, not a file to write.

Only lane 1 touches `openapi.yaml` and only lane 1 runs `npm run gen:api`. E needs no contract
change, ever.

Nobody edits `docs/agent-context/*.md` or `docs/HANDOFF.md` while the other lane is still running —
**the close is a conflict even between lanes whose code is disjoint.** Stagger it: whichever lane
finishes first closes first and says so; the second re-reads both files before editing them.

## Prompt

Paste everything in this block into a fresh session. Everything above and below it is context.

> Read `CLAUDE.md`, then `docs/agent-context/be-java.md` and `docs/agent-context/fe-react.md`, then
> `docs/plans/2026-08-18-unplanned-gaps.md` — including its parallelism section — before doing
> anything else.
>
> You are running batch 2 of that plan as two parallel implementation lanes. This is the explicit
> permission `docs/specs/parallel-work-rules.md` requires for more than one concurrent subagent. The
> batch is safe because E shares no contract, no migration, no frontend file and no backend module
> with B — it is the only area in the plan for which that is true.
>
> Both areas are already planned. Neither lane runs `ep-writing-plans`. Both run
> `ep-executing-plans` against the existing plan, task by task.
>
> Launch these two lanes concurrently. Give each one only its own brief plus the shared constraints.
>
> **Lane 1 — implement Area B (Bulk execution assignment), master tasks 7–14.** Your plan is
> `docs/plans/2026-08-18-gap-b-bulk-execution-assignment.md` — read it in full before writing
> anything; it corrects the master plan in six places and those corrections bind. The three that
> will bite if you miss them: **there is no `Idempotency-Key` on this endpoint** (cases-bulk
> declares none, and this repo's idempotency is a unique column on a *created* row, which a bulk
> assign never produces — determinism is the property instead); **there is no new
> `ExecutionBulkController`** (`RunController` already maps the prefix and owns the five problem
> builders you reuse verbatim); and **one audit row per target sharing one `bulkOperationId`**, a
> deliberate departure from every other bulk service here, because one `changes` document cannot
> truthfully state twelve rows going to five people. Distribute mode is in scope, not a follow-up.
> You hold `openapi.yaml` and you are the only lane that may run `npm run gen:api` — regenerate both
> copies and `diff` them. Use fixture prefix `88`. Your tasks 7 and 8 are a pair: T7 leaves
> `everyMappedColumnHasAWriter` red and T8's deletion of the `WRITERLESS_BY_DECISION` entry is what
> greens it. **Land them adjacently in one batch** — under the no-tests rule nobody will observe
> that red, so a T7 that ships without T8 ships a red gate nobody sees. Before trusting that rule at
> all, read how it decides a column is written (`ArchitectureRulesTest:1226`) — it is a name-text
> match, and it has already produced one false negative.
>
> **Lane 2 — implement Area E (Company data export), master tasks 33–40.** Your plan is
> `docs/plans/2026-08-18-gap-e-company-data-export.md` — read it in full before writing anything.
> This is an operator CLI command (`export-company --slug=… --out=…`) beside `provision-company`,
> not an HTTP endpoint, so you need no contract change and must not make one. The correctness rule
> the whole area rests on: **`CompanyContext.open(id)` wraps the `@Transactional` call from
> OUTSIDE**, because `CompanyAwareTransactionManager` reads the context in `doBegin` — a context
> opened inside runs after the GUC decision, every RLS read returns zero rows, and the archive comes
> out silently short, which is worse than failing. The plan's six checks (S1–S6), the `.partial` +
> `ATOMIC_MOVE` write, and the seeded-vocabulary sentinel exist to make that failure loud; implement
> all of them. The redaction list is settled in the plan — six dropped columns, ~11 excluded tables,
> and a reasoned KEEP list that must survive (the four `content_hash` columns are content addresses
> of the client's own snapshots, and a naive `%hash%` rule would destroy them). Two open questions
> the plan deliberately refused to guess: whether `company.logo_storage_key` has a writer at all
> (grep it, Task 8 step 4) and which storage root it belongs to. Use fixture prefix `7c`.
>
> **Shared constraints, give these to every lane:**
>
> - **Run no tests and no gates. None.** Not `./gradlew build`, not `./gradlew test`, not a single
>   targeted test class, not the npm suites, not `npm run lint`, not locally and not on shanks via
>   `~/bin/tcms-remote`. Verification is **one pass the user runs on shanks after every lane in this
>   batch has finished.** This overrides `CLAUDE.md`'s "run the gates on shanks" instruction for the
>   duration of the batch — the gates still run on shanks, just not by you and not yet.
> - **Still write every test your plan calls for**, fleshed out into real runnable assertions rather
>   than sketches. They simply do not get executed by you.
> - **`npm run gen:api` is still allowed** for the contract holder — codegen is not a test, and the
>   two generated copies must stay byte-identical or `schemaFreshness.test.ts` fails later.
> - **A phase commit no longer means "its gate passed."** Commit per phase when the phase's edits
>   are complete and self-consistent, and say so — do not imply a gate ran. Report your lane as
>   **implemented and completely ungated**; never as done, green, verified or passing.
> - Work on the current branch. Do not create a branch or a worktree.
> - Stage only the files your lane touched — `git add <paths>`, **never `git add -A`**. Concurrent
>   writers in this tree have silently lost committed work three times. On an `index.lock` conflict,
>   wait a few seconds and retry; never delete the lock. Never run `git checkout`, `git stash` or
>   `git reset`.
> - Browser passes and backend mutation checks are **deferred and batched** (§65): write the rows
>   into `docs/verification/frontend-browser-checks.md` and `docs/verification/backend-mutation-checks.md`
>   with stable ids in your area's `§NN` series, and **run nothing**. A row whose lane creates no
>   fixture must say what is missing and what the options are.
> - Save generated `.md` artifacts under `docs/` in this repo — never a global directory.
> - Close by updating `docs/agent-context/*.md` and adding a `docs/handoff/NN-slug.md` plus **one**
>   index row in `docs/HANDOFF.md`. **Do not close while the other lane is still running** — one
>   writer per file. Announce your close; if the other lane has already closed, re-read both files
>   first.
>
> When both lanes report, review each against its plan and against the master plan's task list for
> that area, then hand the user the full verification backlog — this batch's rows **plus Area A's,
> which is still completely ungated** — as one shanks pass.

## After the batch

Areas A, B and E are then implemented and none of them gated. The verification pass covers all
three at once.

Batch 3 is **C alone or C ∥ F**, per the master plan's matrix — C takes `V68` and holds the
contract; F is frontend-only, binds the controls A and B will by then have built, and touches
`ExecutionPage`/`ExecutionQueue`, which C goes nowhere near. D is also eligible beside F. **G stays
last, always.**
