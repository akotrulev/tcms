# §111 — The aggregate execution report

**Date:** 2026-08-06 · **Lane:** backend + frontend · **Migrations:** none (`V35` head, `V36` free)
**Contract:** prose only, two descriptions + `gen:api` · **Permission:** none new · **Policies:** 61

M2.5's **R6**, in four phases and four commits. The first report in this product that spans more
than one run: a selection compiled to one set of predicates over `execution_fact`, the aggregation
policy resolved **in SQL**, headline totals carrying both the execution count and the distinct-case
count, and failure groupings by suite and by feature.

| Phase | What landed |
|---|---|
| 1 | `AggregateExecutionReport` + `AggregateSelection` + a duplicated `reporting.AggregationPolicy`, and `AggregateExecutionCrossCheckTest` — the oracle that makes the duplication legal |
| 2 | The contract: the type is named, and its selection is documented with the four readings a caller would otherwise discover from a wrong total |
| 3 | `GenerateReportDialog`'s second entry, its parameter form, and the run gate that stopped being global |
| 4 | `AggregateSelectionTest` — the half the oracle cannot see, and the mutant that corrected a comment |

## 1. The report is requestable by *existing*, which is what R3 built the registry for

`ReportComputations` is a registry over every `ReportComputation` bean. Nothing in the controller,
the validation, or a list anywhere was edited to make `aggregate_execution` requestable — and
`ReportTypes`' refusal names it automatically, because the catalogue in that message is asked of the
registry rather than written beside the check.

That is also why **phase 2 exists**. A report that becomes requestable by existing becomes
*invisible* by the same mechanism: nothing in the contract said it was there, so the only clients
that could ask for it were the ones whose authors had read the Java.

## 2. Decision D, both halves — the enum is duplicated and the algorithm is not

ERD §0.2 forbids `reporting` importing `execution`'s `AggregationPolicy`, and decision E declines a
`shared` module for one enum. So the *vocabulary* is copied and the ~80-line `resolve` — carrying
three fixes §46 paid for in defects — is **not**: this resolves the policy in one window function
over `execution_fact`, a different implementation over different data answering the same question.

`AggregateExecutionCrossCheckTest` is what makes that legal, asserting the report's per-status
counts equal `GET /plans/{planId}/statistics`'s under each of the five policies, **against each
other and never against literals**. The defect it prevents is a plan page reading 94% and the
release report reading 91% on the same day with nothing to say which is lying.

**Two mutants were run in phase 1 and the second one found the oracle vacuous.** Inverting `LATEST`
to `ASC` — every case resolved to its *oldest* result, the whole meaning of the policy reversed —
passed the entire class, because the fixture's two multi-result cases were exact mirrors (fail→pass,
pass→fail) and reversing time swapped *which* case was passing while moving no total at all.
`UNBLOCKED` (blocked→passed) is the row that breaks the symmetry. **A fixture built from mirrored
pairs is symmetric under exactly the mutation an ordering test exists to catch.**

## 3. What the oracle could not see, and what phase 4 did about it

Phase 1 wrote its own gaps into the mutation queue instead of leaving them to be inferred: four of
seven queued rows were expected to redden **nothing**, because the cross-check reads one section of
a report over one plan on a fixture with one configuration. Phase 4 closed them with
`AggregateSelectionTest` (18 tests, prefix `c2`), and re-ran each mutant rather than assuming:

- **R6-5** — `LATEST_PER_CONFIG` could drop `configuration_id` from its partition and stay green.
  §46 paid for this once already: resolving latest *globally* collapses a per-configuration
  breakdown to one column, *"wrong in a way that looks like a working screen."* Now **RED**.
- **R6-7** — an empty facet array read as "select nothing" gives a clean report of nothing. §99's
  defect one layer down. Now **RED**, in 16 tests.
- **R6-9** — a swallowed bad date gives a report over *every* execution ever recorded under a header
  still naming the range. Now **RED**.
- **R6-3** — `WORST`'s verdict-first clause was unguarded because the cross-check fixture contains
  no recorded non-verdict. A recorded `RETEST` makes it observable: without the clause, a case that
  passed in every run it was recorded in reads as owed a verdict. Now **RED**.

### The finding: the comment was protecting the wrong half

**R6-8 came back GREEN**, and that is phase 4's result rather than a shortfall. The code's own
javadoc said the `CASE … ARRAY[NULL]::uuid[]` is *"what keeps that row"* — the unclassified
execution that a plain `unnest` would drop (§96's `JOIN suite` in a second shape). It is not.
`LEFT JOIN LATERAL … ON true` **already** preserves a left row whose `unnest` yields nothing, so
removing the `CASE` reddens nothing at all. The mutation that matters is the **join kind**: a
`CROSS JOIN LATERAL` drops every unclassified execution and reddens three tests.

The `CASE` is kept — it states the intent where the array is — and the comment is **struck through
with its correction**, because a reviewer defending the `CASE` would be defending the half that does
not carry the guarantee. The thing to protect is the word `LEFT`.

## 4. Three asymmetries with the plan page, all deliberate, all asserted

1. **The fact log has no unrecorded rows and `PlanStatisticsService` does.** `ExecutionSource`'s
   projection joins `case_status` *inner*, so a fresh run's untouched checklist has no fact row,
   while `resolve` sees `UNTESTED`. The two therefore agree on **recorded** results only — pinned in
   a nested class rather than arranged away by fixture design.
2. **`notRun` is not this report's to compute.** It is `scope − touched`, and a star schema has no
   scope. The denominator here is *contributing executions*, said in the metadata label and again in
   the results narrative.
3. **`UUID` orders differently in Java (signed halves) and Postgres (unsigned bytes)**, so the
   `execution_id` tiebreak can pick different rows. Bounded rather than fixed: it reaches a *count*
   only under `LATEST`/`LATEST_PER_CONFIG` with timestamps tied to the microsecond and differing
   statuses. Every fixture execution carries a distinct `executed_at` so the tests assert the rule
   and not the coin toss.

## 5. The contract says what a wrong total would otherwise teach

Phase 2 is two descriptions, no schema, no operation, no field. What it documents is the set of
readings a caller cannot guess:

- `featureIds` **unions** (Payments *or* Refunds). Intersection answers nothing for the ordinary
  two-feature request, which reads as a quiet project rather than a misunderstood filter.
- `suitePaths` match **descendant-or-self against frozen history** — the path as it stood when each
  result was recorded, which is why a report spanning §98's flattening matches deep paths for old
  runs and single labels for new ones.
- `from`/`to` are **half-open**, so two adjacent monthly reports neither double-count nor drop
  whatever landed on midnight; `to` before `from` is refused rather than swapped, because a swap
  prints a header describing a range the numbers did not come from. A result with **no** timestamp
  is outside every range — that is what a range means, and the alternative puts one execution in two
  months.
- An **empty array reads as absent**.

And the two properties that make this report's numbers differ from a screen's, said *before*
somebody compares them: it reads `execution_fact`, so a result recorded in the last few minutes may
not be in it (the projection runs every 15 minutes), and a case with no recorded result is not in it
at all.

## 6. The frontend finding is the gate, not the menu entry

Every affordance in `GenerateReportDialog` assumed the run picker: a project with no runs disabled
**Generate** outright. Correct for `run_summary`, whose whole scope is one run — and wrong for a
report whose every facet is optional and whose honest answer for an empty project is a report saying
nothing has been recorded. Left alone, R6's report would have been unreachable in exactly the
projects somebody reaches for it in first.

**The assertion is `toBeEnabled()`, and that direction is the finding.** Written the natural way
round — `toBeDisabled()` — the test passes against the pre-R6 dialog, so the "coverage" would have
been green while the feature was unreachable. §66's shape, and §99's, from a third door.

### The form validates two things the server also validates, and it is not the copy that drifts

`ReportJobService` deliberately leaves each report's own parameters to the **worker**, because the
alternative is a second copy of every report's rules on the request path (§58's E1). Defensible, and
it means a bad policy or a backwards range is not a 400 on the form — it is a `FAILED` job after the
dialog has closed. So the policy is a **picker** (unwrongable rather than checked) and the date order
is checked before the request. `AggregateSelection`'s javadoc claimed these were *"a 400 at request
time"*; that was wrong when written and is corrected in phase 3.

### The date range is converted, not passed through

The wire's `to` is exclusive; a person picking 31 August means the 31st. The form sends 1 September.
Passing the typed date through drops the last day of every range and produces a smaller number that
looks entirely reasonable — on the one screen whose readers compare months.

### Only what was narrowed on is sent

No empty arrays. The server reads `[]` as absent either way, so this is not about the numbers:
`parameters` is frozen onto the artifact and printed as the report's **Selection** block, where
`"planIds": []` tells a reader a filter was applied that was not. `policy` **is** always sent, at its
default, because it is the one parameter whose absence changes what the numbers *mean* rather than
which rows produced them.

**Three facets are named as not built**: `configurationIds` (no configurations endpoint exists in
this client at all), `suitePaths` (`ltree` paths the API stopped exposing when suites flattened), and
`automationStatuses`/`sources` (cheap, but this form is already the longest in the product). The API
takes all three; a caller who needs them can send them.

## 7. Gates

- Backend: `./gradlew build` **BUILD SUCCESSFUL — 1824 tests across 370 result classes, 0 failures,
  0 skipped** (1806/363 → 1824/370), counted from the XML.
- Frontend: build clean, lint 0, **958/958 across 70 files** (954 → 958).
- Phase 4's 18 tests: **nine failed on the first run and every one was the fixture, not the code** —
  a `uq_execution_run_case` violation (one execution per run per case, so a retest is a *later run*),
  and expectations written against `LATEST` while measuring narrowing, which collapses a case's
  results before anything is counted. A correct answer to a different question.

## 8. Verification

**Zero browser rows run.** Five queued — `M6-21`…`M6-25`, covering the type switch, the enabled
submit in a runless project, the half-open conversion on the wire, the refused backwards range, and
the multi-select facets.

**Mutations: nine run across phases 1, 3 and 4** — two in phase 1 (one of which found the vacuous
fixture), four in phase 3 (all four new frontend tests), and five re-runs in phase 4 (four RED, one
GREEN with the correction above). Recorded as §15 and §16 of `backend-mutation-checks.md`.

**Still uncovered and named**: R6-4 is unreachable behind a CHECK constraint, and nothing asserts
that the policy the *dialog* sends is the one the server resolves under — that crosses the wire and
neither suite can see it.

**Built and unverified** in the browser. R7 (the inconsistency callout) and R8 (version divergence)
are the remaining reporting slices.
