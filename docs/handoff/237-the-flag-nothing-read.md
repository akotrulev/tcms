# §237 — the flag nothing read, and the pass rate that was allowed to move (C2)

**Date:** 2026-08-16 · **Branch:** `master` · **Slice 5 of the C run**, in the §3.6 order.

`case_flake.quarantined` has been written since `V38` and read by nothing downstream. This slice
gave it two consumers: the reports, and the runner.

**No migration.** **No permission seeded, none moved.** One contract property.

The audit's C2 row is one sentence — *"exclude quarantined cases from `aggregate_execution` /
pass-rate denominators AND badge them in the runner"* — and it contains three decisions it does not
make. All three went the other way from the obvious reading, and each is recorded below with what
the obvious reading would have cost.

---

## 1. Live, not snapshotted — and the architecturally tidy answer is the inert one

The star-schema reading says this belongs on `execution_fact` as a frozen dimension: *was this case
quarantined when the result was filed*. `ExecutionSource.projectRecordedExecutions`'s header already
argues at length for freezing dimensions — a `DO UPDATE` over them "would retroactively re-file last
quarter's results under this quarter's taxonomy" — so a `quarantined` column would sit
comfortably beside `automation_status`, `suite_id` and `suite_path`, with `V63` behind it.

**It would do nothing.**

A test is quarantined *because* it went red. `CaseFlake.unscored`'s javadoc states the sequence
outright: *"A run that has just gone red is quarantined within the hour, so the common path is
quarantining a case the recompute has never produced a row for."* Every result a reader wants
discounted was therefore filed **before** the decision, and a frozen column reads `false` on exactly
those rows. The feature would be inert on the day it is used and would begin working only on results
filed later — a build that works rendering identically to one that does not, which is the shape §47,
§53, §72 and §81 each paid for.

So the flag is read at report time, and the cost is stated rather than hidden: **quarantining a test
today moves the quarantine-excluded figure for last month.** That is tolerable only because of
decision 2 — it moves no figure that already existed, and the new figure's own narrative says the
exclusion is as of now.

**Two consequences worth carrying forward.** `execution_fact` gains no column, so
`projectRecordedExecutions`' `ON CONFLICT` set-list is untouched. And `recomputeFlakeScores`'
standing warning — that a set-list of `EXCLUDED.*` there "would release every quarantined test on
the next tick — silently, in the safe-looking direction" — is still true and still about a statement
nothing in this slice edits. Both were re-read at the end to confirm it.

## 2. Both rates printed, and the headline is the one that does not move

The audit says *exclude from the denominator*. Taken literally that replaces the printed pass rate,
and it is refused twice over.

**§6e refuses it on its own terms.** A lone `95.2%` cannot be told from a `95.2%` with nothing
quarantined. `CaseQuarantineService`'s header made this exact argument when it declined to build the
consumer — *"excluding a case from a pass rate silently is how a green number stops meaning
anything … when gating reads this column it must say so on the face of the report — a separate
slice, with §6e's discipline"* — which is a specification for this slice written two milestones in
advance.

**And `AggregateExecutionCrossCheckTest` refuses it structurally.** That test pins the aggregate
report's per-status counts against `GET /plans/{planId}/statistics`, deliberately asserting the two
implementations *against each other* rather than against literals, because "a literal agreeing with
a wrong implementation is how both screens end up wrong together". `PlanStatisticsService` lives in
`execution`, a module that **may not read `case_flake` at all** — so an exclusion applied here and
not there would break that guard *by design*, leaving nothing to distinguish it from the drift it
exists to catch.

So the shape is §105's blocked-vs-failed, one milestone later: the chip merges, the panel splits.

| Line | Behaviour |
|---|---|
| `Pass rate (of contributing executions)` / `(of recorded)` | **Unchanged.** Reproducible, cross-checked, counts every recorded result. |
| `Pass rate (excluding quarantined cases)` | New. Its own numerator and denominator, both moved together. |
| `Quarantined cases in this selection` / `in this run` | `N (M executions, not counted in the rate above)`. **Printed at zero**, like the divergence count and every status row. |
| Section **Quarantined cases** | The case, the **reason somebody typed**, and when. A sentence when there are none. |

**The exclusion is one figure wide, and a test asserts that.** The status breakdown, the per-tester
table, the per-suite table, the feature grouping, the automation split and the retry section all
count the quarantined rows in full. `nothingElseInTheReportMoves` is the observer; without it the
sections would stop summing to the headline in the direction nobody checks, which is the arithmetic
§96 spent a slice on.

**The table and the metadata count are different numbers on purpose.** The section lists every
quarantine in the *project*; the metadata counts the subset that *contributed to this selection*.
Filtering the table down to contributors was the alternative and it hides the more useful fact: a
quarantined case with no result in the selection is a test **nobody is running at all**. The
narrative reconciles the two rather than leaving a reader to.

**Widened to `RunSummaryReport` as well as `aggregate_execution`**, at the user's direction: a run's
pass rate is the number a release decision is actually taken on. `PlanStatisticsService` could not
follow — the module boundary again — so the run *page*'s Result bar and the run *report*'s headline
still agree, and only the report carries the second figure. That asymmetry is deliberate and named
here so it is not read later as an oversight.

## 3. The badge is on the checklist, not only on the screen it opens

`TestExecution` is `allOf: TestExecutionSummary`, so **one property serves both screens** and the
question was never "which one" but "is the checklist worth the crossing".

It is, and the argument is one line: a badge you see only after opening the execution is a badge you
see **after you have already chosen the row**. So `quarantined` is on the summary, and it renders in
all three places a tester meets a run's cases — the Grid checklist, the Focus-mode queue rail, and
the execution header. The queue is the one that matters most and the one that had to earn its 260px:
that rail is where the next case is *picked*.

The cost is one query for a whole run. `ExecutionViews`' header said **"Four bulk lookups, never per
row"**; it says five now, in the same commit, and `CaseFacts` gained a twelfth crossing —
`quarantinedCaseIds`, keyed on **case id and not execution id**, because a run holding one case
twice must badge both rows.

**It never means skip it.** A quarantined case stays on the checklist, stays enabled, takes a verdict
with no confirmation, and still counts towards the run's own pass rate. The chip is outlined amber
beside the *title* rather than filled in the *Result* column, and the tooltip says in words that the
case is still to be run — because "Quarantined" alone reads as "don't bother", which is the one thing
it must not mean. `theRowIsNeverHiddenOrRefused` and `neverHidesOrDisablesTheRowItBadges` hold the
two halves.

The chip cannot say **why**: the reason lives on `case_flake` and the execution wire carries only the
flag. That is a deliberate stop — the reason is a sentence and the checklist is a table — and both
the case's own `FlakePanel` and the board card already render it in full. The chip's job is to make a
reader ask.

## 4. Where the crossings went, and why not straight into the report

`reporting/package-info.java` permits exactly one class to name another module's tables:
*"Everything else crosses through `ExecutionSource` … A second class here naming `test_execution`
ends that property, and it ends it for the most tempting reason there is — that the new query is
short."* So `AggregateExecutionReport` does not join `case_flake`; `ExecutionSource.quarantinedCases`
reads it and the report filters on the returned ids. `ExecutionSource`'s header now says **eight**
tables rather than seven.

The predicate is composed as text, not bound as an array: an empty quarantine set is the ordinary
state and `IN ()` is not valid SQL, so the empty case is the literal `false`. Every `FILTER` then
counts nothing, both rates agree, and the metadata still prints a zero.

**The `FILTER`s sit on `resolved`, not on `selected`** — the exclusion follows the selection's
aggregation policy. Counting over the un-collapsed set would report more exclusions than the
denominator ever held; `theExclusionIsCountedOverTheRESOLVEDSet` is the observer, asserting 2 under
`ALL` and 1 under the default for one quarantine.

**One difference from `work.BoardFacts.quarantinedCases` is deliberate**: that query drops
soft-deleted cases and this one does not. A board card is work somebody has to do and there is none
in a case nobody runs; a report figure is history to account for, and the fact log projects a deleted
case's results anyway — so dropping it here would leave its executions inside the *excluded*
denominator while the team's last word on them was "do not trust this". `ExecutionSource.caseLabels`
already made this call for the same reason, in this file.

## 5. One stale claim corrected that this slice did not cause

`RunSummaryReport`'s header said the per-case failure list *"waits for the slice that decides where
case labels come from"*. `ExecutionSource.caseLabels` landed in **R7**, and its own javadoc names
that header as the thing it was answering — *"the slice `RunSummaryReport`'s header said it was
waiting for"*. The two files have contradicted each other for eight rounds.

Struck through in place. The per-case list is now a decision nobody has taken rather than a
dependency nobody has, and the quarantine section is the first per-case table in that report — built
on exactly the seam the header said it was waiting for.

**Fifth slice in a row to hit the §152 shape** (§233 tier claims, §234 `ArtifactKind` prose, §235
three `search_vector` comments, §236 the `findDefectLinks` javadoc). The pattern is stable enough to
state plainly: **a comment describing an absence is never revisited by the slice that ends the
absence**, because that slice is working in a different file.

## 6. What `npm run build` caught that `vitest` would not have

Five test files build a `TestExecutionSummary` by hand instead of calling the fixture factory, so a
required property broke `tsc -b` in five places while every one of those tests would have run. §236's
lesson, second outing: **the gate is `npm run build`.** It also caught a MUI v7 `Stack` refusing a
top-level `alignItems`, which no test would ever have rendered differently for.

Recorded rather than fixed: those five hand-built literals are a small standing cost on every
contract change to this schema, and consolidating them onto `fixtures.ts` is a sweep, not this
slice's.

## 7. Not done, and deliberately

- **`PlanStatisticsService` and the coverage matrix carry no second figure.** The first is barred by
  the module boundary and is the class the cross-check pins; the second was not in C2's scope.
- **The MCP surface gained nothing and needed nothing.** Its run reads return the server's response
  whole, so `quarantined` reaches an agent already. No tool description enumerates fields, so none
  became false.
- **No new permission.** Reading a quarantine costs whatever reading the report or the run costs.
  The catalogue stays at 55.

---

## Gates (shanks)

| Stack | Result |
|---|---|
| backend | `./gradlew build` **BUILD SUCCESSFUL**, +16 tests |
| frontend | `npm run build` + `npm run lint` (0 warnings) + suite |
| packages | `npm run build` + lint + **336** (78 / 130 / 128), unchanged |

Arrival baseline was run before anything was changed: frontend **1547 / 115**, matching §236.
Both `schema` copies regenerated.

## Queue rows — none run (§65)

- Browser: `§237 QB-1…QB-6` (the badge) and `§237 QR-1…QR-7` (the two reports), all lane `A3`.
  **`QR-1` must be read before anything is quarantined** — the finding is the difference between two
  readings of one report, and a single reading cannot tell a working exclusion from a rate that was
  always going to say that. `QR-5` names the fixture it needs and its fallback.
- Mutation: `§237 QR-1…QR-7` and `§237 QB-1…QB-3`. **`QR-7` is expected green and recorded as a
  gap**, not a decision the tests made: nothing quarantines a soft-deleted case.

**Nine of eleven C items done.** Remaining, in order: **C1** (suite version advancement), **C4**
(retire the shared-step library).
