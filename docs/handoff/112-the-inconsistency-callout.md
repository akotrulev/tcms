# §112 — the inconsistency callout, the split that is two columns, and the retries the default policy erases

**M2.5's R7.** Three sections added to the aggregate execution report: §6e's *inconsistency callout*
— *"cases that both passed **and** failed within the selection. This is the single most valuable
section: it's the flaky-test and environment-instability signal, and it's invisible in any single-run
report"* — plus the manual-vs-automated split and the first-attempt-vs-after-retry pass rate.

Backend only. **No migration** (`V35` still head, `V36` still free), policy count still **61**, no
new permission key, no new endpoint, no frontend code. The contract change is **prose** on the
`aggregate_execution` bullet, so `npm run gen:api` moved nineteen lines of JSDoc in `schema.d.ts` and
no type.

The report became a six-section document without anything being registered, listed or switched on:
R3's `ReportComputations` registry and F2's model-driven view render whatever a computation returns.
Same property R6 landed on — *it became requestable by existing*.

---

## 1. The finding is which row set each section reads, and it decides whether the section works at all

R6's sections read the **policy-resolved** rows: one row per case under `LATEST`, which is the
default and the policy nearly every report will be generated under. Written the obvious way — same
CTE, same shape as the section above it — **all three of R7's sections are then permanently empty**:

- a case with one result cannot disagree with itself,
- its first attempt is also its last,
- and its retries were discarded before the section saw them.

The report would print *"no case both passed and failed"* for every project, for ever. Not an error,
not a blank — **a reassuring sentence in a section that looks like it is working**, which is §47,
§53, §72, §81 and §101's cold cache in a fifth shape, and this time in the section the milestone
names as the most valuable one.

Read the other way round, it is not a near miss but a structural fact: **the aggregation policy
exists to hide exactly what these sections are about.** Collapsing many results into one is how the
rest of the report answers *"where do we stand"*; these three say what was collapsed to answer it.

So `resolvedCte` was split, and `selectedCte` — the narrowed rows with **no** policy applied — is now
a named seam with the reason in its javadoc. Three sections read each. The split is what makes the
distinction reviewable: a section reaching for the wrong one is a one-word difference in the source
and a section that is silently empty in production.

**The two tests are written in the direction that fails against the tempting implementation.**
`theCalloutSurvivesTheDefaultPolicy` asserts the case **is** listed under `LATEST` and that the
answer is identical under `LATEST`, `ALL` and `WORST`; `theRetryOutcomesIgnoreThePolicy` does the
same and then asserts *"passed only after a retry"* is **non-zero**, because otherwise the equality
is two zeroes agreeing. §66, §99 and R6's own phase-3 `toBeEnabled()` finding are the same lesson:
the assertion written the natural way round is green against the feature not existing.

## 2. A pass and a block is not an inconsistency — and the slice plan's own query shape says it is

The plan specified *"a `HAVING` over distinct `counts_as_pass` values"*. That is one predicate too
wide. `counts_as_pass` is false for `BLOCKED`, `SKIPPED`, `RETEST` and `UNTESTED`, so a case that
passed once and was blocked once would be listed as flaky. It is not: nothing failed — somebody could
not run it.

The predicate shipped is **at least one pass and at least one `FAILED`**. §105 settled the general
form (*blocked stays out of the failure count*) and this is that rule one report over; inflating the
most-quoted section of a release report is the direction §46 says never to fail in. Recorded as a
departure from the plan line rather than quietly written differently, and `BLOCKED_THEN_PASSED` is
the fixture row that discriminates.

## 3. Where the disagreement is, is a second finding, and §6p had already settled it

§6p, on the case timeline: *"pass/fail alternation **within a single version and configuration**,
which is the only scoping under which flakiness means anything. Alternation across versions is just
change, and reporting it as flake is a false signal."*

A case that fails on Chrome and passes on Firefox is a real finding and a **completely different**
one from a case that fails and passes on the same browser at the same version. Two ways to get this
wrong, both plausible: list only the within-one-cell cases (and drop a real finding), or list them
all as flakes (and report a configuration-dependent failure as an unstable test).

So every case is **listed**, and the last column names which it is — *Within one configuration and
version* / *Across configurations* / *Across versions* / *Across configurations and versions* —
resolved from a per-cell aggregate where a cell is (case, configuration, case version). Instability
wins where both hold, because that is the finding that changes what somebody does next.

The same cell is the unit of the retry section, deliberately: *"first attempt"* means nothing across
configurations (a failure on one and a pass on another is not a retry) or across versions (a failure
before an edit and a pass after it is a fix). One case therefore contributes several groups, the
counts do **not** sum to the distinct-case count, and the column is called **Case, configuration and
version** rather than "Cases" so that nobody adds it to the headline — §96's rule again: say it in
the table rather than let a reader find it by subtraction.

## 4. The split is a cross-tab because two columns both look like the answer

`execution_fact` holds two columns that each read like "manual vs automated":

- **`automation_status`** — the **case's** status as it stood when the result was recorded (a frozen
  dimension, §86): where that test sits on the road to being automated (§6c).
- **`source`** — the **result's** provenance: a `MANUAL` run somebody executed, or an `INGESTED` one
  a CI job posted (§6e's *"both manual and CI-ingested results"*).

Picking either and labelling the table "manual vs automated" answers a question nobody asked with a
number that looks like the answer to the one they did. They disagree in both directions and each
disagreement is real: an automated case triaged by hand during a release; a case still marked
`MANUAL` whose results have been arriving from CI for a month. **The second is a data-quality signal
about the repository, not a kind of testing** — said in the narrative, because a reader who does not
know that reads it as a third category.

**Rows are ordered alphabetically, and that is a constraint rather than a preference.** The status
breakdown orders by `StatusKind`'s declaration order for a stated reason — two reports from one
project are read side by side, so row order must not move when the numbers do. The same argument
applies here and the same mechanism does not: `AutomationStatus` lives in `repository` and
`RunSource` in `execution`, and this module may import neither at any width plan §3 permits (ERD
§0.2). Alphabetical delivers what that argument actually needs — total, stable, independent of the
data — and gives up only the enums' semantic ordering, which no reader of this table relies on. A
third copy of two enums to recover it is the trade §95 and decision E both refused.

## 5. Case labels: the slice `RunSummaryReport` said it was waiting for

`RunSummaryReport`'s header has said this since §87, in as many words:

> a list of forty UUIDs is not the section §6e describes, so this report groups failures **by suite**,
> which the fact log can name, and the per-case list **waits for the slice that decides where case
> labels come from**.

R7 is that slice, because an inconsistency callout is inherently per case — §6e's *"cases that both
passed and failed"* cannot be grouped into anything else. The answer is
`ExecutionSource.caseLabels(projectId, ids)`: **in the seam, beside `findRun`, for the reason that
method's header already gives** — this is the one file in `reporting` that may name a table another
module owns, and the alternative was never a nicer import but a second file with the same problem.

Three decisions inside it, each the sibling of one already made:

- **`execution_fact` does not grow a `case_key` column.** A star schema duplicates the dimensions it
  aggregates *by*, not the labels of the rows it aggregates. And both candidate labels are mutable —
  §6t reallocates a key on a clone, titles are edited constantly — so freezing either into an
  append-only log means old rows printing names that no longer exist, in a log that would carry a
  column no aggregate groups by.
- **No `deleted_at` predicate** — `findRun`'s call, not a second opinion: deleting a case does not
  un-run it, and a report that named the case yesterday and prints a bare UUID today makes a
  signed-off release document unreproducible, which is §6e's determinism bullet by name.
- **Bounded by `project_id`** — RLS scopes to the company and no further, so without it a sibling
  project's title lands on this project's report. `CatalogueFacts` and `PlanFacts` each layer the
  same predicate for the same reason; this is the fourth file to do it. **Nothing tests it here** and
  the mutation queue says so (R7-6): the class seeds one project.

A case that no longer resolves prints its id rather than vanishing — `featureGroups`' rule, because
dropping the row would take the finding with it.

## 6. Gates

- Backend: `./gradlew build` — **BUILD SUCCESSFUL in 6m58s, 1839 tests across 374 result classes, 0
  failures, 0 skipped** (1824/370 → 1839/374: the 15 of `AggregateVariabilityTest`, whose three
  nested classes are three further result files).
- Frontend: `npm run build` clean, `npm run lint` 0 warnings, `npm run test:run` **958/958** —
  unchanged, as expected: the only frontend file touched is generated JSDoc. **One caveat recorded
  rather than smoothed over:** the first `test:run` reported `1 failed | 957 passed` and took 186s
  against a normal 121s, because it was sharing the machine with `./gradlew build`. Two subsequent
  runs were 958/958. **The failing test's name was not captured**, so this is a flake under CPU
  contention on the evidence available and not a cleared one — do not run the two stacks' suites
  concurrently.
- The RED run is recorded rather than described: **12 of 15 failed before the sections existed**, and
  the three that passed were all absence assertions — named individually in the mutation queue,
  because an absent section satisfies them and they therefore carry no evidence yet.
- Three failures in the first GREEN run were **my arithmetic, not the code**: the headline
  "Contributing executions" is the *resolved* count (6 cases, not 11 executions) under the default
  policy, and the `AUTOMATED · MANUAL` row is 1 of 2 rather than 0 of 1. The corrected 50.0% is the
  stronger assertion — the report's own headline is 83.3%, so a share computed against the wrong
  denominator cannot pass it.

## 7. Verification

**Zero rows run**, per §65. Four browser rows queued — `M6-26`…`M6-29`: the callout listing a real
retried case by key under the default policy, the two widest tables in any report checked for
clipping (§52), the "we looked and found none" wording against a blank panel, and screen-vs-export
of the three new sections (decision F's property, which no gate can see).

**Six mutation rows queued, none run**, as §17 of `backend-mutation-checks.md`. R7-1 is the one that
matters — both sections switched back to `resolvedCte` — and R7-2/R7-3 are the only evidence the
three RED-passing tests can ever get. R7-6 is recorded as a **gap** rather than as coverage.

**Built and unverified** in the browser. **R8 (version divergence) is the last reporting slice** in
the M2.5 plan, and R7's cell — (case, configuration, case version) — is already the unit it needs:
`selectedCte` projects `case_version_id`, and the inconsistency callout's *Across versions* row is a
first, coarse instance of exactly the divergence R8 has to flag.
