# Batch 4 — the gate pass, then D alone

Written 2026-08-22, after batch 3 landed. Supersedes the "one pass after every batch" ordering for
this batch only.

**This batch is two stages in one session, and the first one blocks the second.** Five areas —
A, B, E, C and F — are implemented and completely ungated, and one of them was never even compiled.
Stage 1 runs the gates on `shanks` until `master` is green. Stage 2 implements D against its
existing stepped plan, **gated**, the way slices ran before batch 1.

D is alone. `C ∥ D` was never eligible — both hold the contract and both edit `router.tsx` — and now
that C has landed, D holds `openapi.yaml` and `router.tsx` by itself. **One subagent, so no special
permission is needed.** The standing cap is the default again.

## Why the gates come first, and what "the pass" does and does not mean

Two different things have been called "the verification pass" and conflating them would stall this
batch for days.

| | What it is | Who runs it | Blocks D? |
|---|---|---|---|
| **The gates** | `./gradlew build`, the three npm suites, `lint` — on `shanks` via `~/bin/tcms-remote` | the session, mechanically | **Yes.** Stage 1. |
| **The §65 queues** | 56 backend mutation rows + 36 browser rows, driven by hand against a live stack | the user, in a sitting of its own | **No.** Still batched, still deferred. |

Only the first blocks D. The queues stay exactly where §65 put them.

**The reason the gates cannot wait for D is attribution.** `./gradlew build` compiles and tests the
whole backend, so there is no such thing as gating D alone — D's own gate would necessarily be the
first execution of A, B, C and E as well. Run it that way and every red arrives unattributed, in the
session least able to judge whose it is. D is also the one area in the plan that can expose a
**tenancy** defect, which is precisely the finding you do not want landing in a pile of five other
authors' failures. So: green the tree, then add the risky read to it.

D's own plan assumes this anyway. Its Task 14 is *"Go green, and gate"* and its Task 15 measures a
query plan on a seeded `shanks` container. It was written for a tree that compiles.

## What batch 3 left

Two areas landed, both ungated, in twenty-three commits between `f5bc206` and `356ec13a`, pushed to
`origin/master`.

- **§247 Scheduled runs** — `V68`, `run_schedule`, a per-company poller, the settings screen.
  **61 arms, and `compileJava` was never run.** Head is now `V68`; the catalogue is still 55.
- **§248 Keyboard-driven execution** — the key map, the focus guard, the overlay, and
  `ExecutionPage.test.tsx`, which did not exist before. **32 arms, never executed.**

Both closes are in, both caches carry `§247`/`§248` headers, and the two lanes shared no source file.

Three things batch 3 discovered that stage 1 should expect:

1. **`SchemaConventionTest`'s policy count is a literal.** C changed `70` → `71` in the migration's
   own commit — something its own stepped plan had missed. Under a no-gates directive nobody would
   have seen that red, and it is the clearest evidence available for why stage 1 exists.
2. **`everyMappedColumnHasAWriter` needed no exemption for `RunSchedule`.** C read
   `ArchitectureRulesTest:1238` before trusting it and leaned only on the genuine-Java-setter
   disjunct, never the name-text match that produced §244's false negative.
   `WRITERLESS_BY_DECISION` still holds seven.
3. **`§242 LG-5` is drivable now**, superseded in place with its id unchanged, because the poller is
   the product's first actorless writer.

And one gap nobody has recorded as a row yet: **`run-schedule` appears in `packages/` only inside the
regenerated `client/src/schema.ts`.** C added five contract operations and gave `@tcms/cli` and
`@tcms/mcp` no verbs for any of them. `writeConformance.test.ts` checks the *command registry*, so a
missing command is invisible to it — this is a coverage gap, **not** a predicted red. Stage 1 should
record it; closing it is not this batch's job.

## Stage 1 — what will probably redden, and whose it is

Naming these up front so the session does not misattribute a five-author failure to whichever file
it happened to open first. **Nothing below is a prediction of a defect** — it is a list of the checks
most exposed to what the last three batches changed without executing.

| Check | Exposed by | Whose |
|---|---|---|
| `SchemaConventionTest` | `V68`, and the `70` → `71` literal | C |
| `everyMappedColumnHasAWriter` + its javadoc tally | a whole new entity; the tally has been stale twice in three sections | C |
| `RepositoryTransactionTest` | the new repository interface | C |
| `ContractConventionTest` pairings | five new operations | C |
| `schemaFreshness.test.ts` | the two generated copies; C regenerated and diffed them, F touched neither | C |
| `citedFiles.test.ts` | every area added comments citing paths — **a stale citation in `frontend/` or `backend/` reddens the *packages* job** | all five |
| `contractPairing.test.ts` | the byte-identical marked region | C |
| `renderFailures.test.tsx` | F added the first `ExecutionPage.test.tsx` and 32 arms | F |
| `tierClaims.test.ts` / `matrixCopies.test.ts` | catalogue still 55 and no permission moved, so these should be quiet — if either reddens, that is the interesting one | — |

**The frontend test count in `CLAUDE.md` (1582) is stale by at least 43.** Re-measure it from the run
rather than adding up the claims in the handoffs.

## Pre-assigned resources — do not choose your own

| | D — Cross-project case search |
|---|---|
| Fixture UUID prefix | **`4d`** — its stepped plan already self-assigns this, and it is unused across `backend/src`, `frontend/src` and `packages/*/src` |
| Section | **§249** |
| Queue ids | **`CS-1..7`** / **`CS-M1..M9`** — both unused in either queue; the plan drafts the rows already, with `§NNN` placeholders to substitute |
| Flyway | **none.** Head is `V68`. Task 15 *measures* and records an index as **owed** rather than adding DDL |
| Contract | **holds `openapi.yaml` alone**, and is the only lane that runs `npm run gen:api` |
| Router | **holds `router.tsx` alone** |

Taken so far: `0a` (§242), `66` (§244), `88` (§245), `7c` (§246), `3d` (§247), `2c` (§248). **`5e` is
in use in the tree and is not a spare.**

**D's plan carries three stale references.** It says `be-java.md`'s header names `§243` (now `§247`),
that `§243` is the section head (now `§248`), and it reasons about `V68` as unspent (C spent it). The
plan's *decisions* are unaffected — it takes no Flyway version either way — but do not copy its
numbers.

---

## Prompt

Paste everything in this block into a fresh session. Everything above and below it is context.

> Read `CLAUDE.md`, then `docs/agent-context/be-java.md` and `docs/agent-context/fe-react.md`, then
> `docs/plans/2026-08-18-unplanned-gaps.md` — including its parallelism section — before doing
> anything else.
>
> You are running batch 4. It is **two stages, and stage 1 blocks stage 2.** One lane, one subagent
> at most — the standing cap applies again, since batch 3's two-lane permission was for that batch.
>
> **Stage 1 — green the tree.** Five areas are implemented and completely ungated: A (§244), B
> (§245), E (§246), C (§247) and F (§248). C was never compiled at all. Run the gates on `shanks` via
> `~/bin/tcms-remote`, per `CLAUDE.md` — **never locally**; the batch-3 no-gates directive is over and
> the standing "tests run on shanks" directive is back in force.
>
> `~/bin/tcms-remote backend ./gradlew build` · `~/bin/tcms-remote packages npm run build` ·
> `npm run lint --workspaces` · `npm run test:run --workspaces` · `~/bin/tcms-remote frontend npm run test:run` ·
> `npm run build` · `npm run lint`. Backend first: it is the only stage that touched DDL, and a red
> `SchemaConventionTest` invalidates everything downstream of it.
>
> **Fix what reddens, and attribute every fix to the area that caused it** — the areas are §244,
> §245, §246, §247, §248 and the handoff for each says what it did. Use `ep-systematic-debugging`:
> root cause before fix, and a fix in the *cause* rather than in the assertion. **A gate that is
> wrong is a legitimate finding** — `SchemaConventionTest`'s policy count is a literal and C already
> moved it `70` → `71` — but "the test is wrong" is a conclusion you earn by reading, never an
> opening hypothesis. Commit fixes per area, not in one sweep, so `git log` keeps the attribution.
>
> Do **not** drive the §65 queues in stage 1. The 56 backend mutation rows and 36 browser rows stay
> deferred and batched; they are the user's own sitting. Stage 1 is the automated gates and nothing
> else.
>
> When the tree is green, **say so with the actual commands and their output**, re-measure the
> frontend test count (`CLAUDE.md`'s 1582 is stale by at least 43), and record what you fixed and
> whose it was. If something reddens that you cannot fix without a decision, stop and ask rather than
> guessing — a wrong fix here is attributed to five authors at once.
>
> **Stage 2 — implement Area D (Cross-project case search), master tasks 26–32.** Your plan is
> `docs/plans/2026-08-18-gap-d-cross-project-case-search.md` — 22 tasks, 1,543 lines. Read it in full
> before writing anything. Run `ep-executing-plans` and nothing else; **do not run
> `ep-writing-plans`**, the plan exists.
>
> **This one is gated.** Run each phase's gate before its commit, the way slices ran before batch 1.
> A phase commit means its gate passed, and you may say so.
>
> You hold `openapi.yaml` and `router.tsx` alone and you are the only lane running `npm run gen:api`
> — regenerate both copies once (`frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts`)
> and `diff` them; they must be byte-identical or `schemaFreshness.test.ts` fails.
>
> Four things that will bite:
>
> (1) **RLS does not save you here.** RLS scopes to the **company**. Two projects in one company are
> both RLS-visible, so "which projects may this caller see" is entirely the application's job. This
> is the only cross-project *case* read in the product and it is the riskiest read in the plan.
>
> (2) **Narrow inside the SQL, never in Java after the read** — and **assert the total, not just the
> page**. A count computed over rows the caller may not see leaks the existence of those rows even
> when the page hides them. That is the defect the whole area exists to prevent, and `CS-M1` claims
> two arms catch it: verify by doing it, and if only one does, say which.
>
> (3) **Reuse the existing visible-project narrowing.** `/work-items` (`openapi.yaml:7071`),
> `OrgChartService` and `AdminReach` already carry it. A second implementation of "which projects may
> this caller see" is the defect class this area exists to avoid.
>
> (4) **`CS-M3` is the point of the slice.** Deleting the project predicate from `CaseSpecifications`
> must redden `CaseSearchTest` and — check — stay **green** on `CaseListEndpointTest`. That predicate
> has been a documented survivor mutation on the project-scoped read since it was written; this slice
> is what makes it killable. Record which suites reddened.
>
> Your plan records **`CS-M6` and `CS-M7` as expected-green gaps on purpose**, and `CS-M7` names the
> single arm that would close it. Keep them as gaps if you still agree after reading the code, but
> **say which you chose and why** — an expected-green row means no observer exists, not that the
> behaviour works. `CS-M9` may come back green if the fixture is too small to page, in which case
> **the fixture is the finding**.
>
> **Take no Flyway version.** Head is `V68`. Task 15 measures the query plan and records an
> expression index as **owed** rather than adding DDL — `V67` already fixed one seq scan on the
> authorization hot path, so if you find another, write it down with the measurement behind it. If
> the measurement genuinely demands the index in this slice, that is a decision to raise, not a file
> to write.
>
> Use fixture prefix **`4d`**, section **§249**, queue ids **`CS-`/`CS-M`**, and **write §249 into
> your source comments from the first commit, not at close** — a lane did the renumber-at-close and
> left thirteen in-source citations crediting the wrong slice (`7f835ee`). Your plan drafts its queue
> rows with `§NNN` placeholders; substitute `§249`. **Your plan's own numbers are stale in three
> places** — it says `be-java.md`'s header names §243 (it is §247), that §243 is the section head (it
> is §248), and it reasons about `V68` as unspent (C spent it). Its decisions are unaffected; its
> numbers are not.
>
> Standing rules: work on the current branch, do not create a branch or a worktree; stage only the
> files you touched (`git add <paths>`, never `-A`); never `git checkout`, `git stash` or
> `git reset`; browser and mutation rows are written into `docs/verification/*.md` and **run
> nothing** (§65); save generated `.md` under `docs/`; close by updating both orientation caches and
> adding `docs/handoff/249-<slug>.md` plus one index row in `docs/HANDOFF.md`; push after the lane.
>
> Report stage 1 and stage 2 separately. For stage 1: what you ran, what reddened, whose it was, what
> you fixed, and the real test counts. For stage 2: the tasks against the plan, the `CS-M6`/`CS-M7`
> decision, the query-plan measurement and whether an index is owed, and — because this one is gated
> — which gates you actually ran and that they passed.

---

## The backlog this batch does not touch

Unchanged by stage 1, which runs gates and not queue rows. Nothing below has ever been executed.

| Area | Section | Browser queue | Backend mutation queue |
|---|---|---|---|
| A Execution duration | §244 | `ED-1…6` | `ED-M1…M9` |
| B Bulk assignment | §245 | `BA-1…9` | `BA-M1…M12` |
| E Company export | §246 | none — a CLI verb has no browser surface | `CE-M1…M13` |
| C Scheduled runs | §247 | `SK-1…11` | `SK-M1…M22` |
| F Keyboard execution | §248 | `KB-1…10` | none — its one write is covered by §244's rows |
| D Cross-project search | §249 | `CS-1…7` | `CS-M1…M9` |

**56 backend mutation rows and 36 browser rows** before D adds its own, plus §242's ten browser rows
— including `LG-5`, which §247 made drivable and which is no longer marked NOT DRIVABLE.

**Twelve rows are recorded as gaps, not as passes**: `BA-M7`, `BA-M10`, `BA-M12`, `CE-M2`, `CE-M9`,
`CE-M10`, `CE-M12`, `CE-M13`, `SK-M5` (its run-count half), `SK-M20`, `SK-M22`, and **`SK-M6` — nothing
drives the poller at all, so its per-company `continue` is unobserved.** A green there means no
observer exists. D adds `CS-M6` and `CS-M7` to that list.

**Four rows cannot be settled by a suite**: `SK-3` needs a real fire (it names both honest ways and
requires the pass to say which it used), `KB-9` and `SK-11` ask for a judgement rather than a verdict,
and `KB-10` needs a real screen reader — without which §248's accessibility half is a claim rather
than an observation.

## After the batch

**Batch 5 is G alone, last, always.** It sweeps all 132 components, so it conflicts with every
frontend area by construction; the only area it could ever have run beside was E, which is done. It
still needs `ep-writing-plans` run on it first. **Ship the mechanism and the English catalogue only —
do not translate anything.**

That leaves the §65 queues as the only thing outstanding after G, and by then they will hold seven
areas.
