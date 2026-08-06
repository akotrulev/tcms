# §105 — What failed: the run's failures, and the steps inside them, without opening every row

**Date:** 2026-08-06 · **Lane:** frontend only · **Migrations:** none · **Contract:** unchanged

The ask was *"on test run, we need a summary of failed step/cases"*.

A run detail page could already tell you **how much** went wrong — the pass rate, the result bar,
and a "Needs attention" chip that filters the checklist. It could not tell you **what** went wrong.
Answering that meant filtering to the failures and then opening each execution in turn to find the
step it died on. On a 40-case regression run with six failures that is six page loads to write one
sentence in a release thread.

## What changed

| File | Change |
|---|---|
| `lib/results.ts` | **new** `runFailures` (partition executions into failed / blocked, key-ordered) and `stepOutcome` (failed steps, unmarked count, total) |
| `lib/results.test.ts` | +8 tests, and the existing fixtures de-`as`-ed while nearby |
| `api/queries/runs.ts` | **new** `useExecutionSteps` (the second `useQueries` in this codebase) + `STEP_DETAIL_LIMIT` |
| `api/queries/index.ts` | the barrel lines |
| `features/runs/FailureSummary.tsx` | **new** — the panel |
| `features/runs/FailureSummary.test.tsx` | **new** — 9 tests over the state matrix and both counting rules |
| `features/runs/RunDetailPage.tsx` | mounts the panel between the rollup and the checklist |
| `mocks/db/executions.ts` | a failed execution now comes in **two** shapes — see below |

**No contract change, no `gen:api`, no migration, no backend file touched.**

## The contract question, asked before anything was built

`TestRunDetail.executions` serves `TestExecutionSummary[]`, which carries `status` and `defects`
and **no steps**. `StepResult[]` lives only on `TestExecution`, behind
`GET /runs/{runId}/executions/{executionId}`. There is no run-level step rollup anywhere in the
contract, and `/runs/{runId}` is the only run-scoped read.

So the two halves of the ask have genuinely different costs, and the slice is built around that
rather than around hiding it:

- **Failed and blocked cases — free and complete.** Already in the `TestRunDetail` the page holds.
  No new request at any run size.
- **Failed steps — one read per failed case.**

**The contract was deliberately not widened.** A `failedSteps` array on `TestExecutionSummary`, or
a `GET /runs/{id}/failures`, is the right long-term answer and it is a *server* decision: it needs
a query, an N+1 story, and a view on the Java side, and it would land the numbers in the rollup
lane §101 built — at which point this panel inherits `formatComputedAt` and has to say how old its
figures are. That is a bigger, slower slice than the question deserves, and the client answer is
honest today. It is named here as the follow-up rather than done badly now.

### Why the reads are bounded, and what the bound is on

`STEP_DETAIL_LIMIT = 25`. **The bound is on the reads, not on the answer.** Every failed case is
listed at any run size; only the step detail stops. When it does, the panel says so — *"Every failed
case is listed. Step detail was read for the first 25 — open the other 5 to see where they failed."*
A run with 300 failures is a run whose first question is not "which step", and firing 300 parallel
reads to answer it would take the page down on exactly the runs that are already going badly.

**Blocked cases are never opened.** A blocked case's steps are by definition the ones nobody reached;
a request per row to render "0 failed, N not marked" buys a sentence the row already carries by
being in the blocked list.

## The counting decision, which is the whole slice

Two distinctions, each with a sibling defect already recorded in this repo.

**A blocked case is not a failed case.** The existing "Needs attention" chip merges FAILED and
BLOCKED on purpose — both need a person — and copying that merge here would have looked perfectly
consistent with the screen above it while reporting the product as broken in places nobody managed
to test. `runFailures` partitions them; the headline counts them separately; the blocked list has
its own sentence saying it is a report about the environment. Skipped and unexecuted cases are in
neither list: a case somebody declined to run is a decision, not a finding.

**An unmarked step is not a passed step.** `StepResult.statusId` is nullable and null means nobody
recorded anything — the ordinary state of every step after the one a tester stopped on. "1 of 5
steps failed" invites the reader to infer four passes; if three were never touched, that inference
is manufactured out of nothing. So `stepOutcome` returns **three** numbers and the row prints the
third whenever it is non-zero. This is `criterionState`'s rule one field over ("5 not evaluated" is
not "0 of 5 met") and the mirror image of `suggestedVerdict`'s vacuous `[].every()`, which proposed
Passed from no evidence at all.

A third case falls out of the same reasoning and is handled explicitly: a step whose `statusId` the
project vocabulary does not recognise counts as **neither** failed nor unmarked. Somebody recorded
something this client cannot read, which is a different situation from the tester's unfinished work,
and folding it into either would be a guess presented as a count.

**No percentage.** `RunDetail.Summary` directly above already renders a pass rate. §65's recorded
defect is two percentages on one screen denominating differently, and this panel is the textbook
place to reintroduce it. Every figure here is a count and every count names what it counted.

**No staleness marker either, and that is also a decision.** §101's `formatComputedAt` marks screens
whose numbers come from the `plan_snapshot` / `suite_run_summary` rollups and can lag a scheduler
tick. Nothing here is a rollup: it is computed from the executions on every render. Stamping a
"computed at" on it would be a false claim in the opposite direction.

## The region wraps the step layer only

The first draft put the failed-case list *inside* the step `DataRegion`, and its own test caught it:
while 25 reads were in flight the panel showed a skeleton and no failures at all. That is
`DataRegion`'s documented first rule broken — wrap the response-dependent subtree and nothing else.

It now renders as a **strip**: the region's loaded branch is empty (`{() => null}`), because the
loaded rendering is the rows beside it, which read the same state object. Loading shows *"Reading
where each case failed…"* above a fully rendered list of failures; a failed read shows
`.Steps.Error` above the same list. The forbidden outcome — a panel silently reporting no failing
steps because the reads 500'd — is `data-fetching`'s "a failure is never an emptiness", and it has
its own test.

The combine is pessimistic in both directions, exactly as §103's `useReferencedCases`: pending while
any arm is pending, failed if any arm failed, `dataUpdatedAt` the **oldest** arm. And the keys are
`keys.execution(...)` — the same ones the execution screen reads and Grid mode writes — so opening a
case warms this panel and marking a result moves it.

## The mock change, and why it is a fixture defect being closed

`runExecutions` gave every failed execution the same checklist: failed on the **last** step, every
earlier step passed. So **"not marked" was unreachable in mock mode**, and a build that counted
unmarked steps as passes would have rendered correctly in every mock browser pass ever run.

That is the same class as §100's exit-criteria finding (the mock always supplied a boolean, so the
null branch was unreachable) and §64's usage counts. Failed executions now **alternate**: one stops
at the failure and leaves the rest of the checklist untouched — what a tester actually does — and
the next marks the whole list. Both shapes present, §72's fixture rule.

## Testing

- `results.test.ts` +8: blocked never counted as failed, skipped/unexecuted in neither list, key
  order numeric (`TC-9` before `TC-10`), the three step numbers, the unknown-status arm, the
  no-steps arm, position ordering.
- `FailureSummary.test.tsx` +9: the headline's two counts, the unmarked clause present and absent,
  the named step at its 1-based position, the no-structured-steps wording, clean-run good news,
  the unfinished-run wording, **a failed read rendering as an error and not as "nothing failed"**,
  and the cap listing every case while bounding the reads.

**Mutation-checked**: folding BLOCKED into `failed` reddens 2; forcing `unmarkedCount` to 0 reddens 3.

## Gates

`npm run build` clean · `npm run lint` 0 · `npm run test:run` **958/958 across 70 files** (from
941/69 — 17 new tests, one new file, all accounted for; no new `api.*` call site, since
`useExecutionSteps` composes the existing `executionQueryOptions`, so §70's contract-hygiene checks
generated nothing).

**Built and unverified** — 11 browser rows queued (M4-48…M4-58), **none run** (§65). M4-54 and
M4-55 are the ones that matter: they are the two states no fixture produces and the two the panel is
most likely to get wrong.

## Found and not fixed

- **No run-level step rollup in the contract.** Named above. The client bound (25) is a workaround
  for a shape the server should publish; when it does, this panel loses its `useQueries` and gains
  §101's freshness contract.
- **`STEP_DETAIL_LIMIT` fires 25 parallel requests on a bad run.** They are ordinary cached reads
  sharing the execution screen's keys, so a second visit is free — but there is no batching and no
  concurrency limit, and that is the honest ceiling of the client-side answer.
- **The panel is unconditional.** It renders on a run nobody has started, saying nothing failed —
  which is true, and the "not finished" wording covers it. If a run's first screen ever needs to be
  quieter, that is a layout decision and not a correctness one.
