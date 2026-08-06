# §109 — The browser queue gets a lane to run in

**Date:** 2026-08-06 · **Lane:** verification · **Migrations:** none · **Contract:** unchanged
**Code touched:** none. Two files in `docs/verification/`.

§107 deleted `src/mocks/` and, in the same commit, wrote a box at the top of
`docs/verification/frontend-browser-checks.md` saying the mock pass had no lane to run in and that
folding its rows into the live pass was *"a real re-write and it is not done here"*. This is that
re-write. It is not a slice that ships behaviour — it is the slice that makes ~40 slices' worth of
deferred verification **executable** instead of merely queued.

## What the file was, and why it could not be run

Two passes: a **mock pass** (`M1 → M7`, 260 rows) that needed no backend, and a **live pass**
(`L0 → L4`, 102 rows) against a provisioned company. The mock pass was the cheap one and it carried
most of the screen checks. With `src/mocks/` deleted, every one of those 260 rows named a fixture
store that no longer exists — the seeded suites, the "sign in with any password of 4+ chars", the
handler behaviours some rows asserted directly.

**The naive fold — delete the mock rows — would have thrown away most of the queue.** Most of them
check a *screen*, not a mock. What they needed was a data source, and the only one left is the
product itself.

## The three findings, none of which is a re-homing

### 1. `L0` was written against an instance somebody else had filled

`L0` is the read-only lane: *"eight facts that are true of every real installation today and false
for ever after the first write."* Five of the eight were not facts about a fresh instance at all:

| Row | What it says | What a freshly provisioned company has |
|---|---|---|
| `L0-2` | "Any run list, then open a run — every run Not started" | no runs |
| `L0-3` | "Plan list — every plan Draft" | no plans |
| `L0-4` | "Any project's suite rail — every folder unbadged" | **no suites** — `SuiteWriteService`'s own header says so |
| `L0-5` | "Any plan's exit-criteria panel — empty" | no plans |
| `L0-6` | "A case or plan already In review" | no cases |

`provision-company` creates a company, its vocabularies, its counters, one Company Admin and
optionally one empty project. Nothing else. **The lane was executable only against a demo dataset
nobody in this repo has ever created**, and it had been sitting there since §100 looking runnable.

The five rows now sit in the lane that *creates* the thing they are about, and are checked at the
moment of creation. That is strictly stronger: *"the plan you just made is Draft"* is an assertion
about the write path, where *"every plan is Draft"* over somebody else's data is an assertion about
a fixture. `L0` is three rows now and honest about it.

### 2. The board lane had to move from first to sixth

`M1` — rails, boards, drag — ran **first** in the mock pass, because the fixture store already held
cards. Live, **every card on every board is derived**: from a review request (`A4`), an execution
(`A3`), or a `case_health` signal whose scheduler tick has not even fired yet. A board lane run
first would have checked five card behaviours against three empty columns **and passed every one of
them**. The same argument applies in miniature to `M2-46` (a suite's execution history, empty until
runs exist) and `M3-15` (a plan delete that must be *refused* because runs exist), both of which are
now held back to the lane that gives them something to be wrong about.

This is §66's shape — an assertion that passes because the thing it examines is absent — arriving
through lane ordering rather than through a permission.

### 3. Three rows in the file were already wrong, and nobody had executed them to find out

- **The console snippet in `M6` would have thrown on its first line.** It read
  `JSON.parse(localStorage.getItem('tcms.session')).token`. The key is `tcms.token`
  (`AuthContext.tsx:8`) and it holds the raw token, not JSON. Eight rows depended on it.
- **Four rows assume a plan can be Started and Closed** — `M3-13`, `L2-5`, `L2-6`, `L3-6`, with
  `M4-27` resting on them — while `M3-1`, three rows above, asserts **three states with no Start,
  Close or Reopen anywhere** (§97 phase 3a). The file contradicts itself and has since §97. They are
  marked ⚠ rather than deleted, and `A2` opens by driving `M3-1` to settle which side is right: this
  is a documentation defect *or* a product one, and only driving it decides which.
- **`M7-7` still asks for "a suite rail with ~10 folders at three depths"**, which §98 made
  impossible. Suites are flat.

**A queue nobody executes accumulates exactly this.** Every one of the three was invisible to
reading and would have cost the person running the pass a stall each.

## What replaced the fixtures: a charter, not a prologue

The old live pass had a **prologue** — a block of SQL plus four numbered UI steps. It could not
survive the fold, because the mock rows it now has to support need a repository, not four steps.

So the file carries a **fixture charter**: the named objects the pass authors, in the order that
makes their display keys deterministic. Two projects (`OLB`, `MOB`), six accounts, three teams, ten
flat suites, 26 cases, the tags, the conditions, five plans, the runs.

**Authoring the charter *is* the first three lanes.** That is the part worth keeping: the old pass
could paper over a missing control with a fixture `INSERT`, and this one cannot. If a suite cannot be
given a condition through the UI, `M2-22` has no fixture — and that is a finding, not a setup
problem. Two places still need SQL and both are called out where they are used: `TC-3`'s
`created_by = NULL` (`L1-6`'s third arm, which exists only in code) and `L3-3`'s
assigned-then-retired classification value. **The author split `L1-6` needs is no longer an
`UPDATE`** — `TC-1` is authored as the engineer and `TC-2` as the lead, because the pass now has
both people signed in anyway.

The charter is also where the non-vacuity lives, restated deliberately in three places: `Smoke`
**files nothing** and claims two cases by tag (`M2-38`, `M2-43`, `L4-44`), `Payments` **files
nothing** and claims cases filed elsewhere (`M2-39`, `M3-54`, `M4-32`), and the two teams'
membership is **disjoint** (`M1-11`). Each is a fixture property without which the row below it is
equally true of the defective implementation — §96's and §98's lesson, written into the setup rather
than rediscovered per row.

## Row ids are permanent

`M2-38`, `M4-41`, `M2-48` and twenty-eight others are quoted from handoff §98 and §102–§106.
Renumbering would break every one of them for a cosmetic gain, so **`M` is now the name a row was
born with and not the pass it runs in**. `361 of the old 362 ids survive`, verified by diffing the
id sets rather than by reading — the one dropped is `M6-2` and the drop is written down. A "where a
row went" table closes the file.

Four rows are **retargeted rather than dropped**, and three of the four get stronger:

| Row | Was | Is |
|---|---|---|
| `M6-2` | poll three times, one state per poll | **dropped** — that was the mock's clock; the real worker is `PT15M` and `L4-30` is about exactly that difference |
| `M6-13` | *edit `GenerateReportDialog`* to force a `FAILED` job | generate with an unresolvable `runId` — **the worker's own refusal**, which the mock had to be taught to imitate |
| `M6-20` | *edit `mockModel`* to serve half a document | `tcmsFaults.drift()` scoped to the artifact route — `client.ts:371` is a second fault seam and this is its first consumer. **No file is edited.** |
| `M1-29` | the mock handler's `allowedTransitions` 409 | a **server** refusal, moved to `L2`, reachable only from devtools because the board disables the illegal columns |
| `M4-40` | *"the mock excludes `DEPRECATED` and the server does not"* | **a question, not an assertion**: half the comparison is deleted, the server's behaviour is now the only behaviour, and nothing has ever recorded what it is. §99 inherited the divergence and this is where it gets answered. |

## The ✅ marks are gone, and that is not a loss of coverage

The only rows in the file marked run were `M4-13`…`M4-18` and `M1-13` — §100's, driven **in mock
mode at the user's explicit direction**, overriding §65 for that slice. `CLAUDE.md` says live is the
one that counts and the thing they ran against is deleted. **They are ordinary unrun rows again.**
This does not remove coverage; it stops the file claiming coverage it never had.

`§100 row 111` — the one row §100 recorded as *undriven* because `resize_window` had no effect on
the viewport — was a bullet at the bottom of the file. It is a **row** now (`F-1`), with the
hand-resize workaround in it, because a bullet is a thing nobody executes.

## Also written down: what this pass cannot reach

Five row groups need something one freshly provisioned company does not have — scale (200+ cases,
25+ failures), a second company, a database that migrated in place across `V33`, an execution older
than 14 days, a real deploy. Rather than let the runner discover these mid-lane, they are a table at
the end with the options for each: bulk-create, provision a throwaway, use the Hetzner box,
back-date in `psql`, or **record as not driven and why**. The last is a legitimate outcome and
saying so is what stops it being a silent skip.

## The other file

`backend-mutation-checks.md` row **14a** told a future reader to mutate
`mocks/handlers/workItems.ts`. That file is deleted, so the row is **void** and struck through —
with the note that what it was really asking (the board relies client-side on a rule only the server
enforces) has not gone away and now rests entirely on 14b, which is itself recorded as *"not
verified to exist"*.

## What is still true, and what this does not do

**Nothing here has been run.** This slice makes the queue executable; it does not execute it. Every
slice from §65 onward remains **built and unverified**, and the honest statement of the state is now
*"a runnable 361-row pass exists and nobody has run it"* rather than *"a queue exists"*.

The queue is one sitting, one agent, thirteen stages, and two scheduler waits. Whoever runs it should
expect the charter-authoring lanes (`A1`–`A3`) to be most of the wall-clock time and most of the
findings — they are the create paths, driven by hand, for the first time.
