# Batch 3 — C ∥ F

Written 2026-08-22, after batch 2 landed. Supersedes the sequential run order in `README.md` for
this batch only.

Two implementation lanes, and **they are not symmetric.** C has a stepped plan already —
`docs/plans/2026-08-18-gap-c-scheduled-runs.md`, 2,066 lines, 23 tasks — so lane 1 runs
`ep-executing-plans` and nothing else. **F has no plan.** Nothing in batch 1 planned it, so lane 2
runs `ep-writing-plans` on area F first, saves it, and only then executes it. Plan-then-build, one
session, in that order.

**C ∥ F is the master plan's own verdict**, restated by C's own plan. F is frontend-only, holds no
contract, and touches `ExecutionPage`/`ExecutionQueue` plus two new files; C is backend-led and adds
one new page and one router entry. No shared file.

**This is two concurrent subagents, so it needs the user's explicit permission before it starts** —
the standing cap is one. Batch 2's permission does not carry over.

## What changed under batch 2 that this batch inherits

**Three areas are now implemented and COMPLETELY UNGATED — A, B and E.** Eleven commits between
`96f9764` and `7f835ee`. Not one `./gradlew build`, not one npm suite, not one lint run. A had two
targeted classes run before the directive arrived; **B and E have never executed a single test.**
Head is still `V67`, the permission catalogue is still 55. Both lanes in this batch build on that:
F binds A's duration control and B's bulk controls directly, and C's `RunScheduleFiringService`
enters the same `execution` module. **If the eventual pass reddens in `execution` or on the run
screens, check A and B before suspecting your own lane.**

**`V68` is finally live, and it is C's.** It has been pre-assigned since the master plan's revision
and no lane has spent it. It remains the only migration in the whole plan. `out-of-order` is unset —
do not renumber, and do not add a second migration.

**The section-number defect batch 2 shipped and then fixed — do not repeat it.** Area B's stepped
plan wrote `§244` throughout, having been authored before area A claimed that number. The lane
renumbered its handoff and its queue rows on close and **missed thirteen in-source citations across
five files**, so `ArchitectureRulesTest` credited the duration slice with the bulk act, the
`bulkOperationId` writer and the `WRITERLESS_BY_DECISION` deletion. Corrected in `7f835ee`. Section
numbers resolve by filename prefix in this repo, so a wrong one silently points a reader at the
wrong handoff. **Your section number is pre-assigned below. Write it into source comments from the
first commit, not at close.** C's plan is clean here — it cites `§242`/`§243` as precedent and
hardcodes no number of its own — and F's plan must not hardcode one either.

**`everyMappedColumnHasAWriter` is a live trap for C specifically.** The map holds **seven** entries
now (B deleted `AuditEvent.bulkOperationId`, which was its first writer), and the javadoc tally
inside that file has been found stale twice in three sections. C adds an entire new entity: **every
mapped column of `RunSchedule` needs a genuine writer or an exemption with a stated reason**, or the
rule reddens — and nobody will observe it, because no gate runs in this batch. Worse, the rule
decides "written" by a **name-text match** (`ArchitectureRulesTest:1226`,
`namedIn(writeText, null, field.getName())`), which has already produced one false negative here:
`IngestionService`'s JSON key `node.put("durationMs", …)` satisfied the sweep for a column nothing
had ever written. **Read how the rule decides before trusting its verdict on your new table.** That
hole is still unfixed and is still not this batch's job.

**`ExecutionPage.tsx` has no test file at all.** Master task 42 says "extend
`ExecutionPage.test.tsx`" — the file does not exist. Neither does one for `ExecutionQueue.tsx`, nor
for `executionSelection.ts`, which area B shipped last batch. `§244 ED-M8` already records that
nothing in `frontend/src` drives `useSubmitResult`. F is the natural owner of the first of these and
the planning half of lane 2 must **decide explicitly** how much of that gap it picks up, and say so
in the plan — this is scope to state, not to smuggle in and not to silently skip.

**Two batch-2 decisions are flagged for review and neither lane touches them:** area E widened
`provisioning`'s `allowedDependencies` to include `storage`, and added a fourth, deliberately narrow
storage port (`BlobInventory`) because `AttachmentStore`'s header refuses a listing method.

**The close nearly collided again.** Lane E closed while B was still running; nothing was lost only
because the section numbers had been pre-assigned and E left B's free. Separately, lane B's commit
swept up lane E's appended queue rows — content intact, attribution moved. Both are handled below.

## Pre-assigned resources — do not choose your own

| Lane | Area | Fixture UUID prefix | Flyway | Contract | Section | Queue ids |
|---|---|---|---|---|---|---|
| 1 | **C** Scheduled runs | `3d` | **holds `V68`** | **holds `openapi.yaml`** | **§247** | `SK-…` / `SK-M…` |
| 2 | **F** Keyboard-driven execution | `2c` | — | none needed, ever | **§248** | `KB-…` |

Both prefixes were checked against `backend/src`, `frontend/src` and `packages/*/src` and appear
zero times. **`5e` is already in use (ten hits) — do not reach for it.** Taken so far: `0a` (§242),
`66` (§244), `88` (§245), `7c` (§246).

`SK` and `KB` are both unused across every row id in both verification queues.

**Only lane 1 touches `openapi.yaml` and only lane 1 runs `npm run gen:api`.** C's plan writes its
contract changes as fenced `yaml` fragments in its Phase 3 precisely because it was authored while
another lane held the file — **apply the fragments to `openapi.yaml` first, then regenerate both
copies once, then `diff` them.** F needs no contract change, ever.

**Only lane 1 creates a migration**, and exactly one: `V68__runs_can_be_scheduled.sql`. If lane 2
finds it needs DDL, that is a finding to report, not a file to write.

**The queue files are shared.** Both lanes append to `docs/verification/*.md`. Append your rows and
commit that file promptly in a commit of its own; before staging, run `git status` and if the diff
you are about to stage carries the other lane's rows too, **say so in the commit message** rather
than pretend the commit is yours alone. That is what happened last batch and it cost nothing,
because it was recorded.

**Nobody edits `docs/agent-context/*.md` or `docs/HANDOFF.md` while the other lane is still
running** — the close is a conflict even between lanes whose code is disjoint. Stagger it properly
this time: whichever lane finishes first closes first and announces it; the second **waits** for
that announcement, then re-reads all three files immediately before editing them.

## Prompt

Paste everything in this block into a fresh session. Everything above and below it is context.

> Read `CLAUDE.md`, then `docs/agent-context/be-java.md` and `docs/agent-context/fe-react.md`, then
> `docs/plans/2026-08-18-unplanned-gaps.md` — including its parallelism section — before doing
> anything else.
>
> You are running batch 3 of that plan as two parallel lanes. This is the explicit permission
> `docs/specs/parallel-work-rules.md` requires for more than one concurrent subagent. The batch is
> safe because F is frontend-only, holds no contract and touches `ExecutionPage`/`ExecutionQueue`,
> which C goes nowhere near; C is backend-led and adds one new page plus one router entry.
>
> **The two lanes are not symmetric.** C is already planned and runs `ep-executing-plans` only. F
> has no plan at all and must run `ep-writing-plans` first, then execute what it wrote.
>
> Launch these two lanes concurrently. Give each one only its own brief plus the shared constraints.
>
> **Lane 1 — implement Area C (Scheduled runs), master tasks 15–25.** Your plan is
> `docs/plans/2026-08-18-gap-c-scheduled-runs.md` — 23 tasks, read it in full before writing
> anything. Do not run `ep-writing-plans`; the plan exists. You hold `openapi.yaml`, you hold
> **`V68`**, and you are the only lane that may run `npm run gen:api`. Your plan deliberately carries
> its contract changes as fenced `yaml` fragments because another lane held the file when it was
> written — **apply them to `openapi.yaml` first, then regenerate both copies once
> (`frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts`), then `diff` them; they must
> be byte-identical.** Four things that will bite: (1) `run_schedule` is **company-owned**, not an
> ERD §0.1 exception — `company_id UUID NOT NULL`, first in every index, plus a
> `FORCE ROW LEVEL SECURITY` policy, or `SchemaConventionTest` reddens; (2) **`@Transactional(readOnly = true)`
> on the repository interface**, without which every derived query returns zero rows under RLS;
> (3) **every mapped column of `RunSchedule` needs a real writer or a reasoned exemption** —
> `everyMappedColumnHasAWriter` holds seven entries and decides "written" by a *name-text* match
> (`ArchitectureRulesTest:1226`) that has already produced one false negative here, so read how it
> decides rather than trusting its verdict, and no gate in this batch will observe a red; (4) **run
> creation is not reimplemented** — the poller enters `RunCreateService` through a new
> package-private door so the pin, the display-key allocation, the approval gate and the audit row
> stay in the one place that has ever written a run. Mirror `CaseHealthScheduler.recomputeAllCompanies()`
> for the company loop, including the per-company `catch` and `continue` — without it one bad
> company silently costs every company after it. Decide and record the archived-project behaviour
> (a schedule whose project was archived must not fire); an undecided answer is a job that quietly
> resurrects archived work. **Your Task 22 unblocks `§242 LG-5`** — the browser row currently marked
> *NOT DRIVABLE* because no write path in the product lacks a caller; a poller firing a run is the
> first actorless writer, so supersede that row in place rather than adding a new one, and say so in
> your handoff. **Your Task 17 is "the full backend gate": do not run it.** Complete the task's
> edits, mark it deferred, and never describe it as passed. Use fixture prefix `3d`, section
> **§247**, queue ids `SK-…`/`SK-M…`, and write §247 into your source comments from the first
> commit rather than at close.
>
> **Lane 2 — plan and then implement Area F (Keyboard-driven execution), master tasks 41–46.**
> **There is no stepped plan for F.** Your first act is `ep-writing-plans` on area F alone, saved as
> `docs/plans/2026-08-18-gap-f-keyboard-driven-execution.md`, exactly as the master plan's Scope
> Check requires. Then run `ep-executing-plans` against what you wrote. Frontend only — no contract
> change, no backend file, no migration; if you find you need any of those, that is a finding to
> report, not a file to write. The defect this feature ships broken everywhere: **shortcuts must not
> fire while a text field has focus** — type "p" in the notes box and the row is marked Passed —
> so write that arm before the handler exists. Put the key map in **one** module both the overlay
> and the handler read, or a documented shortcut does nothing. Bind at the screen, not globally, so
> the bindings die with it. **Accessibility is part of the feature, not a follow-up**: every
> shortcut needs a visible, focusable equivalent, and the status change is announced through a live
> region or screen-reader users get no feedback that the act happened. **Know what you are building
> on: `ExecutionPage.tsx` has no test file at all** — master task 42 says "extend
> `ExecutionPage.test.tsx`" and that file does not exist; neither does one for `ExecutionQueue.tsx`
> or for `executionSelection.ts`, which area B shipped last batch, and `§244 ED-M8` already records
> that nothing in `frontend/src` drives `useSubmitResult`. **Decide in your plan how much of that
> gap you take, and state the decision** — creating the first `ExecutionPage.test.tsx` is defensible
> scope, silently skipping it is not, and neither is quietly absorbing all three. You bind controls
> that areas A and B shipped **completely ungated**, so if something looks wrong on those screens,
> report it rather than assume your own edit caused it. Never build your own `QueryClient` — only
> `createTestQueryClient` has the caches. Use fixture prefix `2c`, section **§248**, queue ids
> `KB-…`, and write §248 into your source comments from the first commit rather than at close.
>
> **Shared constraints, give these to every lane:**
>
> - **Run no tests and no gates. None.** Not `./gradlew build`, not `./gradlew test`, not a single
>   targeted test class, not the npm suites, not `npm run lint`, not `npm run build`, not locally
>   and not on shanks via `~/bin/tcms-remote`. Verification is **one pass the user runs on shanks
>   after this batch**, and it will cover areas A, B, E, C and F together. This overrides
>   `CLAUDE.md`'s "run the gates on shanks" instruction for the duration of the batch — the gates
>   still run on shanks, just not by you and not yet. **Where your plan contains a gate task, do the
>   task's edits, mark the gate deferred, and never report it as passed.**
> - **Still write every test your plan calls for**, fleshed out into real runnable assertions rather
>   than sketches. They simply do not get executed by you.
> - **`npm run gen:api` is still allowed** for the contract holder — codegen is not a test, and the
>   two generated copies must stay byte-identical or `schemaFreshness.test.ts` fails later.
> - **A phase commit does not mean "its gate passed."** Commit per phase when the phase's edits are
>   complete and self-consistent, and say so — do not imply a gate ran. Report your lane as
>   **implemented and completely ungated**; never as done, green, verified or passing.
> - Work on the current branch. Do not create a branch or a worktree.
> - Stage only the files your lane touched — `git add <paths>`, **never `git add -A`**. Concurrent
>   writers in this tree have silently lost committed work three times. On an `index.lock` conflict,
>   wait a few seconds and retry; never delete the lock. Never run `git checkout`, `git stash` or
>   `git reset`.
> - Browser passes and backend mutation checks are **deferred and batched** (§65): write the rows
>   into `docs/verification/frontend-browser-checks.md` and
>   `docs/verification/backend-mutation-checks.md` with the stable ids assigned to your lane, and
>   **run nothing**. A row whose lane creates no fixture must say what is missing and what the
>   options are. Both lanes append to these two files: commit them promptly in a commit of their
>   own, and if the diff you stage carries the other lane's rows too, say so in the commit message.
> - **Your section number is pre-assigned. Write it into source comments as you go**, not at close —
>   last batch a lane renumbered its handoff and left thirteen in-source citations pointing at
>   another slice.
> - Save generated `.md` artifacts under `docs/` in this repo — never a global directory.
> - Close by updating `docs/agent-context/*.md` and adding a `docs/handoff/NN-slug.md` plus **one**
>   index row in `docs/HANDOFF.md`. **Do not close while the other lane is still running** — one
>   writer per file. Announce your close; the second lane **waits** for that announcement, then
>   re-reads all three files immediately before editing them.
>
> When both lanes report, review each against its plan and against the master plan's task list for
> that area, then hand the user the **full accumulated verification backlog** — this batch's rows
> plus the 34 backend mutation rows and 15 browser rows already unrun from areas A, B and E — as one
> shanks pass.

## The backlog this batch adds to

Nothing below has ever been executed.

| Area | Section | Browser queue | Backend mutation queue |
|---|---|---|---|
| **A** Execution duration | §244 | `ED-1…6` | `ED-M1…M9` |
| **B** Bulk assignment | §245 | `BA-1…9` | `BA-M1…M12` |
| **E** Company export | §246 | none — a CLI verb has no browser surface | `CE-M1…M13` |
| **C** Scheduled runs | §247 | `SK-…` | `SK-M…` |
| **F** Keyboard execution | §248 | `KB-…` | — |

Eight of the existing mutation rows are **expected green and recorded as gaps**, not as passes —
`BA-M7`, `BA-M10`, `BA-M12`, `CE-M2`, `CE-M9`, `CE-M10`, `CE-M12`, `CE-M13`. Each names the fixture
that would close it. A green there means *no observer exists*.

## After the batch

Areas A, B, C, E and F are then implemented and none of them gated. Two areas remain.

**Batch 4 is D alone.** `C ∥ D` was never eligible — both hold the contract and both edit
`router.tsx` — and once C has landed, D holds the contract by itself. Its plan already exists:
`docs/plans/2026-08-18-gap-d-cross-project-case-search.md`, 1,543 lines. It is the one area that can
expose a tenancy defect, which is why the master plan wanted it in a quiet tree.

**Batch 5 is G alone, last, always.** It sweeps all 132 components, so it conflicts with every
frontend area by construction; the only area it could ever have run beside was E, which is done. It
still needs `ep-writing-plans` run on it first. Ship the mechanism and the English catalogue only —
**do not translate anything.**
