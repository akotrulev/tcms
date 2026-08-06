# §101 — the M2 rollups: the membership that freezes, the prune its sibling must not have, and the cold cache that read as an un-started plan

**Date:** 2026-08-06
**Plan:** [`docs/plans/2026-08-06-m2-rollups.md`](../plans/2026-08-06-m2-rollups.md)
**Commits:** `5434214` (phase 1) · `3d086e9` (phase 2) · `486568f` (phase 3) · `54a8e8b` (phase 4) ·
`7f43c28` (phase 5) · this one (phase 6)

**Migrations:** `V34__rollup_read_indexes.sql`, `V35__rollup_freshness_columns.sql`. **`V36` is the
next free number.** Policy count **unchanged at 61** — both tables have been in `V1`'s
`company_owned` array since M0, so they were protected for eight months before there was a row to
protect. Contract: **one field on two schemas**, both `required` and nullable.

---

## 0. Why this slice, and what "backwards" meant

Round 3 of the remaining-scope assessment moved **M2 backwards, 90% → 85%**, because its rollup
deliverable turned out to have no code at all. Nothing was un-built; the earlier figure was wrong,
and it was wrong because round 2's check was a plain `grep` — both tables are *discussed* in javadoc,
so a mention counted as a writer. That is the same failure mode round 2 itself identified for
`attachment`/`evidence` at round 1 and for `review_comment` at round 2, made a third time by the
check meant to prevent it. **A table named in a comment is not a table with a writer.**

`suite_run_summary` and `plan_snapshot` were the last two tables §86's package comment listed as
having no code. Both now have one, and both screens §6p designed them for read them.

**This slice is not a performance emergency, and the code said so before it was written.**
`PlanStatisticsService`'s header was right about its own deviation — a figure recomputed per request
cannot be stale — and it named the migration path in one sentence, which became this plan's
specification:

> *"the migration path is to feed the rollup and read it here, with this class as the definition of
> what the rollup must produce."*

That sentence is why `RollupAgreementTest` asserts **equality between two computations** rather than
a fresh set of expected numbers. Fresh numbers would only re-state whatever the new code happens to
do, which is the failure mode of every test written after its implementation.

---

## 1. The three findings

### 1a. `suite_run_summary` must FREEZE its membership, and `membership_snapshot` is why that column exists

A run's executions are fixed at create time (§42), so *which cases ran* cannot move. **Which suites
claim those cases moves every time somebody adds a tag.**

So a recompute that re-derives membership on every tick rewrites last March's pass rate: a case
tagged `checkout` today joins the March run's Checkout summary, whose total goes 43 → 44 and whose
percentage moves. **Both the old number and the new one look equally correct, and nobody can name
when it changed.**

§6p settled this before the table had a writer — open question **#32**: *"the run shows the cases the
suite held when it ran; a later suite edit cannot retroactively change a past run's coverage."*
`membership_snapshot JSONB` is the `V1` column that exists to hold it, and this is the slice that
fills it. It is also **§86's frozen-dimension argument reaching a second table**: a frozen dimension
that should have moved is one statement to change, while a moving dimension that should have been
frozen has already destroyed the history it rewrote.

**The freeze is structural, not a set-list.** §86 holds fifteen frozen columns by an `ON CONFLICT`
naming the other seven — correct, and held only by a reviewer noticing which columns are absent.
Here it is two statements: an `INSERT … ON CONFLICT DO NOTHING` that captures membership, and an
`UPDATE` that tallies **over the row's own stored snapshot**, so the frozen columns are unreachable
from the correcting statement rather than merely omitted from it.

The pair is also what makes each half non-vacuous: `anAmendedVerdictIsCorrected` proves the `UPDATE`
fires at all, which is what turns `aLaterTagDoesNotRewriteAPastRun` into a statement about freezing
rather than about a statement that never ran.

### 1b. `plan_snapshot` must be PRUNED and its sibling must not be

The asymmetry between two tables that look like siblings, were created in one migration, and are
discussed in one paragraph of §6p.

| | `suite_run_summary` | `plan_snapshot` |
|---|---|---|
| What a row is | **history** — how one suite did in one run | **current state** — `V1`: *"the CURRENT latest result per case per configuration"* |
| Membership | frozen | re-derived every tick |
| Rows that stop qualifying | **kept** — a suite that stops claiming a case does not un-run it | **deleted** — or the plan argues readiness from a case it no longer covers |
| `ON CONFLICT` set-list | two columns move, two are frozen | **every column moves** — all of them are part of *which result is current* |

**A recompute written the same way for both is wrong for one of them, and silently:** the plan's
totals simply stay too high for ever, with no row anywhere that looks wrong.
`PlanStatisticsService.rowsInScope` has done that filter in Java since §46 and its header calls the
mirror case *"easier to miss"*; moving the computation into a table moves the filter into a `DELETE`,
and losing it in the move was phase 2's whole risk. The `DELETE` is written as the insert's own scope
predicate **negated**, so the pair is total by construction rather than by care.

### 1c. **A cold rollup read as a plan nobody had started** — and the oracle test caught it before it shipped

The first cut of phase 4 chose its source from the aggregation policy alone. That is a claim about
what the rollup **can** serve, not about what it **has** been written for.

A plan whose first results landed since the last scheduler tick — and **every plan on a deployment
where the job has never run** — has an empty `plan_snapshot`. Read from it, the page reported
**every case as not-run**.

That is not the staleness this design accepts and declares. It is a plan that has been executed
reporting that nothing has been executed, **rendering exactly like a plan nobody has started**: the
shape this repository has paid for in §47's empty panel, §53's silent card, §72's every-run-not-
started and §81's mock, reached this time through a cache that is merely cold.

The rollup is now used only when it covers every in-scope case with a recorded result; otherwise the
live path, with `computedAt` null. **Falling back is the safe direction and is not a degradation** —
the live path is the specification and was M2's entire behaviour; the rollup is an optimisation over
it. The failure it replaces is a *wrong* number; what it costs is a *slow correct* one.

`RollupAgreementTest` was written before the implementation, per the plan, and this is what that
bought. Two tests now pin it — fully cold, and **partially** cold, which is the ordinary case: the
tick ran, then somebody recorded a result, and the tester looking at the verdict they just saved is
the single most likely person to open the page.

---

## 2. Two things the plan under-specified, found in the building

Recorded because the plan was wrong about them, not because they were hard.

- **`suite_run_summary` had no `last_executed_at`**, which is the entry's **required** sort key. §6p's
  row is *"87% — Nightly Regression #412 — 2026-07-28 — 41/47 passed"*, and that date is the run's
  last result rather than its start: a run left open for a week belongs on the timeline where the
  work finished. Without the column the rollup could not serve this screen at all. `V35` adds it —
  **nullable**, because a run can open and record nothing, and **not frozen with the membership**,
  because it describes the results rather than the composition.

- **Serving the suite history from the rollup alone would DROP a run recorded since the last tick.**
  Not a number fifteen minutes old — a row that is not there, which the live path never produced and
  which the person who has just finished a run meets first. Hence **rollup ∪ tail**, bounded by
  `NOT EXISTS` against the rollup rather than by a timestamp: a run created long ago can receive a
  result today, and that changes an *already-summarised* row, which is the correcting statement's job
  and correctly shows as staleness rather than as an absence.

`V35` also gave `plan_snapshot` the `updated_at` its sibling has had since `V1`, which is what
`computedAt` serves.

---

## 3. The seam, and the edge that runs both ways

`execution.RollupFacts` is **the module's second seam**, beside `CaseFacts`. Two classes rather than
one — a departure from `work.BoardFacts`' precedent of a single seam naming two foreign modules —
because `CaseFacts` is nine hundred lines whose header is an argument about the *repository*
boundary specifically. **The rule was never "one seam per module" but one seam per FOREIGN module.**

**At the table level the `reporting` edge now runs both ways**: that module builds `execution_fact`,
`suite_run_summary` and `plan_snapshot` by reading `execution`'s tables, and `execution` reads those
three back. At the Java level there is no dependency in either direction — no import, no bean, no
call — which is the only level ArchUnit and Modulith check, and the only level at which a compile can
break. The rejected alternative was moving two working endpoints into `reporting`, which would have
made a screen's module depend on which storage strategy it happened to use.

**The cost is stated in both package comments: nothing checks that the two sides still agree about a
column.** `RollupAgreementTest` closes it, the same way `ExecutionEndpointTest` closes the
`repository` seam — machine-checked end to end rather than asserted in a comment.

`ExecutionSource` now holds a **third copy** of `CaseFacts#memberOf`'s three-arm union. Forced by ERD
§0.2, and held to `SuiteMembership` **through the `membership_snapshot` column the shipping statement
writes**, rather than through a resolution method added for the test — which would have been
production code with no production caller. `SuiteScopeAgreementTest` holds the first two; its header
now names where the third lives.

---

## 4. What the rollup cannot do, stated rather than worked around

- **`plan_snapshot` serves two of the five aggregation policies.** `LATEST_PER_CONFIG` is literally
  its grain and `LATEST` is one further collapse of it. `WORST`, `BEST` and `ALL` each need *every*
  execution — the worst result a case ever had is not derivable from its most recent one — so they
  keep the live path. Asserted in **both** directions, because serving `WORST` from a latest-only
  table would be wrong in the one direction nobody checks: it would make a plan look **better** than
  it is.

- **Neither rollup may answer a denominator.** Coverage, not-run and the scope size still come from
  `CaseFacts.casesInPlanScope` (§46, §6p) — the scope is the plan's own, and a rollup that could be
  asked for it would be a second, disagreeing definition.

- **The unrecorded half stays live, and the split is between what grows and what does not.** The
  recorded half grows without limit — every execution of every case across every run, for as long as
  the plan is used — and is what §6p wrote the rollup for. The checklist §42 writes at run creation
  is bounded by (cases in scope × configurations) and does not grow with time. Dropping it would be
  wrong in exactly one place and quietly: `byConfiguration` lists only configurations with at least
  one execution, so a configuration whose run had been opened and not recorded would **vanish from
  the matrix** — §6p's Chrome-versus-Safari failure from a third direction.

- **The trend is genuinely written twice.** `plan_snapshot` keeps one row per case and a trend is one
  point per *run*, so the rollup path reads `execution_fact`. Computing it live beside a rolled-up
  headline would keep the scan the rollup exists to remove *and* put the sparkline on a different
  clock from the number beside it. Held to the other implementation by the agreement test.

---

## 5. Freshness is on the wire, because §6p names this screen

> §6p: *"a plan page showing 94% from three weeks ago, presented as current, is the most dangerous
> screen in the product."*

`computedAt` is **required and nullable** on both responses. Null means *computed live* — a positive
statement, not a missing value, and required rather than optional precisely because an absent field
would be indistinguishable from a live answer to a client that had not been updated.

**It is the OLDEST `updated_at`, not the newest.** Both responses mix rolled-up rows with a live
tail, so the newest would be a claim true of one row and false of the others — false in the
reassuring direction, which is the one that matters here.

**Two ages, two lines.** `stale` is about the **executions**; `computedAt` is about the
**arithmetic**. A plan executed an hour ago and rolled up ten minutes ago is fresh by one measure and
behind by the other. The contract's `stale` description carried the sentence *"not a freshness
warning about the figures themselves, which are computed per request"* — true when written, false
after phase 4 — and it is **struck through with its reversal** rather than deleted, because the
distinction it drew is now the reason there are two fields.

---

## 6. Gates

| | Before | After |
|---|---|---|
| Backend | 1761 across 348 | **1795 across 360, 0 failures, 0 skipped** |
| Frontend | 913 across 65 | **919 across 67**, lint 0, build clean |

Counted from the XML rather than the console (§86, §88). The frontend `+6` is exactly the six tests
written: **no `api.*` call site was added**, so the contract-hygiene checks generate nothing — the
§70 arithmetic checked rather than noted.

**Phase 1's twelve tests all passed first run, stated plainly** rather than taken as evidence they
can fail; two pairs there are mutually non-vacuous by construction. **Phase 2's thirteen: seven were
observed red first.** **Phase 4's nine: seven observed red, two of them for the cold-rollup defect
above.** Phase 5's `PlanFreshness` test failed three times before the cast was the suspect — and the
fixture it was copied from had already recorded the reason, which is that `as unknown as TestPlan`
hides the fields §97 phase 2 made required and `PlanForm` crashes on mount reading them.

---

## 7. Verification

**Zero verification run**, per §65. **9 browser rows** and **11 mutation rows** queued.

**Every browser row is live-only, and structurally so** — not as a preference. The mock computes
from fixtures per request, so it truthfully answers `computedAt: null` on both endpoints and **mock
mode can only ever render the "Computed now" branch**. The rollup branch, the two ages side by side,
and the stale-arithmetic wording cannot be produced there at all. That is §67's and §68's live-only
argument arriving through a *field* rather than through a fixture gap.

Two mutation rows must not be skipped, because both defects are silent in production and both are
this slice's findings:

- delete `prunePlanSnapshot`'s call and assert `PlanSnapshotRefreshTest.prunesACaseThatLeftTheScope`
  reddens;
- change `correctSuiteRunCounts` to tally over current membership rather than the stored snapshot and
  assert `SuiteRunSummaryRefreshTest.aLaterTagDoesNotRewriteAPastRun` reddens.

A third is worth as much and is newer: make `servedFromRollup` the only condition again and assert
`RollupAgreementTest.anEmptyRollupDoesNotReportEveryCaseAsNotRun` reddens.

**Built and unverified.**

---

## 8. Found and not fixed

- **`suite_run_summary.total` is stored and never read.** The reader sums the five counts instead,
  which is equal by construction and avoids a second source of truth. The column stays because the
  table is `V1`'s and dropping it is a migration for no gain — but it is now a column with a writer
  and no reader, which is the shape §28's orientation question exists to find.
- **The suite history's tail costs a second query even when it is empty.** `runsWithoutSummary`
  always runs. Bounded and indexed, but it could be skipped when the rollup already covers every run.
- **Nothing measures whether the rollup is actually faster.** *"Measure bundle changes before
  believing them"* (`CLAUDE.md`) applies here too and this slice did not: the argument for the rollup
  is structural (aggregate in Postgres over an indexed table rather than materialising N rows into
  JVM objects) and it is an argument, not a measurement.
- **§86/§87's three open rows are untouched** — the unobservable bytes-first ordering, the two
  unasserted scheduler loops, and `case_health`'s missing index on `failing_repeatedly`.
