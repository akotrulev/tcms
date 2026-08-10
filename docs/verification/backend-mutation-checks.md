# Deferred backend mutation checks — the queue

**Policy set 2026-08-02 (§65).** Mutation-verifying each slice inline costs a full `./gradlew build`
per mutation and was being paid *during* feature work, where it is slowest and least useful. It is
**deferred and batched**: a slice records the mutation it would have run, and this file is executed
as one pass later.

> **⚠ ZERO mutations run inline.** Every mutation a slice identifies is written here and run in the
> batch pass. None is "cheap enough" to run inline, and the fact that a run one has occasionally
> found something is not an argument for the exception — it is an argument for running this file.

**This does not lower the bar, it moves it.** The claim "this test would catch that defect" is still
required; it is just made checkable later. A slice that lands without adding its row here has
skipped the check outright. Sibling queue for the browser half: `frontend-browser-checks.md`.

## How to run this file

For each row: apply the mutation, run the named scope (never the whole suite), confirm **exactly**
the listed tests fail and nothing else, then revert.

```bash
cd backend
./gradlew test --tests '<the scope in the row>'
git checkout -- <the mutated file>          # revert by EXACT PATH, before the next row
```

**A mutation that reddens MORE than the listed tests is as much a finding as one that reddens
fewer.** Too few means the test does not cover the behaviour it claims; too many means the tests are
coupled to something other than what they name, and the extra failures are the ones worth reading.

**A row predicting zero reds is not a row to skip.** The measured number is the deliverable.

---

# RUN RECORD — 2026-08-05

**The queue was executed end to end.** 182 rows, backend only, on `4b582ec`. Baseline before and
after: `./gradlew build` green, **1756 tests, 0 failures, 0 errors**, 347 classes. Every mutation was
reverted by exact path; the backend ended byte-identical to `HEAD`.

| Outcome | Rows |
|---|---|
| MATCH — reddened exactly what the row predicted | 96 |
| MORE — reddened the predicted tests plus others | 26 |
| FEWER — reddened less than predicted | 8 |
| ZERO-AS-PREDICTED — row predicted 0, measured 0 | 13 |
| ZERO-UNEXPECTED — row predicted ≥1, measured 0 (**findings**) | 9 |
| STALE / STALE-BY-DESIGN — the code moved out from under the row | 6 |
| CONFIRMED by reading (no mutation needed) | 15 |
| BLOCKED-NO-ACCESS / NOT-SUITE-REPRODUCIBLE | 9 |

**No row reddened fewer tests than predicted for a reason that was a defect in the product.** Every
shortfall was a defect in the *test suite* or in the row's own reasoning. Those are below.

**Frontend rows in this file were not run** — they belong to `npx vitest` and are out of scope for a
backend pass. They are retained in §83, §85, §89, §90 and §96 phases 5–6 of the git history for
whoever runs them; see `f0279de` for the last version of this file carrying them in full.

---

# OPEN FINDINGS — what the pass actually discovered

Ordered by what a reader should act on first. Each is a place where a test is weaker than it reads,
not a place where the product is wrong.

## 1. `SuiteMembership#countsBySuite`'s `COUNT(DISTINCT c.id)` is vestigial, and its test says otherwise

Changing it to `COUNT(*)` reddens **nothing** across 106 tests. The three membership arms are
`EXISTS` subqueries inside one `ON` clause, so a case matching two arms still produces exactly one
join row — `COUNT(*)` is identically equal. `theCountIsTheWholeUnion`'s own comment claims *"a
`COUNT(*)` over the union answers 3 where the deduped count answers 2"*; it answers 2. **The dedup is
real but it comes from the `EXISTS` formulation, not from `DISTINCT`.** The test passes for a reason
that is not the one it states, and would keep passing if the query were later rewritten into a real
union that *does* duplicate. Fix the comment, or rewrite the test to pin the `EXISTS` shape.

## 2. The bulk path's condition attachments are asserted by nothing

Removing `|| authored` from `CaseBulkService#loadContext` is green across all 39 bulk tests. The
condition-side *absent-means-leave-alone* test only covers the single-case `PATCH`. So an authored
bulk action reconstructing a case with no attachments hashes it as though it had none, and the next
ordinary save is credited with restoring conditions nobody touched — §62's mis-attribution defect,
on a new field, with no test behind it. **Write it**: attach a condition, run `MOVE_TO_SUITE`, then
an ordinary title-only save whose version must not report the conditions as changed.

## 3. The same gap on classifications — `CaseEdit.of` with `Set.of()` reddens nothing

`CaseBulkEndpointTest` contains no occurrence of `classificationValueIds` or `case_classification`
at all, so its round-trip hash test runs over a case that carries no classification and the
reconstructed hash is identical either way. **The fixture needs a classified case**, not a new
mutation.

## 4. `PlanStatisticsEndpointTest` cannot see scope-predicate drift

Its fixture contains **no tag and no section**, so `theTwoDenominatorsAgree` is blind to the tag arm.
Drifting `caseCountsByPlan` away from the shared `scopeMatch()` string is caught (2 reds in
`PlanUpdateEndpointTest$TagArm`); drifting `casesInPlanScope` reddens **nothing anywhere**. Only the
plan-detail count is protected. Add a tag and a section to that fixture.

## 5. `FailingRepeatedlyTest`'s `LIMIT` test does not test the `LIMIT`

Deleting the inner `LIMIT CAST(:window AS bigint)` reddens **0**, though
`resultsOlderThanTheWindowAreIgnored`'s comment names itself *"the one that fails if the LIMIT is
dropped"*. `DEEP` is seeded `Passed, Failed, Failed, Failed` — with the LIMIT gone the predicate
still counts exactly 3 FAILED rows and `count = window` still holds. **The equality-with-window is
doing the LIMIT's job for this fixture.** Seed a case with failures *outside* the window.

## 6. `ReportDefinitionRepository`'s header claim is false

It states *"There is deliberately no method on this interface that can find a definition the caller
does not own."* It extends `JpaRepository<ReportDefinition, UUID>`, so **`findById` is inherited and
callable** — `definitions.findById(definitionId)` compiles with no edit. The absence is only of a
*declared* method. RLS bounds the inherited lookup to the company, not to the project or the owner.
Either the comment is wrong or the interface should not extend `JpaRepository`.

## 7. `ReportRenderingTest`'s `Header` and `Registries` cannot observe wiring

Both hand-build their subjects (`new ReportHeader(...)`, `new ReportRenderers(..., new
HtmlReportRenderer())`) rather than resolving from the context, so they are structurally incapable of
seeing a defect in `ReportHeaders.selection()` or in `@Component` wiring — two rows expecting "one
red per class" measured one, and the missing half was always this class. Only the pipeline/endpoint
tests can catch those.

## 8. M9's `WIP` status kind is unasserted

Dropping `WIP` from any of the three `case UNTESTED, RETEST, WIP ->` arms (`ExecutionViews:185`,
`SuiteHistoryService:127`, `PlanStatisticsService:400`), or dropping `&& kind != StatusKind.WIP` from
`PlanStatisticsService.isVerdict`, reddens **0 across 889 tests**. Java does not require statement
switches to be exhaustive, so a missing arm compiles clean and silently drops a count — or makes a
case somebody merely picked up count as *covered*, so coverage rises when a tester opens a screen.
The seed writes the row; nothing exercises it.

## 9. Two "mechanisms" are comments describing a path nothing walks

- **`PlanUpdateService`'s `scopeItems.flush()`** — dropping it reddens 0, and `plan_scope_item` has
  **no unique constraint on `(plan_id, suite_id)`** for an insert-before-delete ordering to collide
  on. The flush is a comment, not a mechanism.
- **`ReviewService.moved`'s idempotent open-review arm** — unreachable: an artifact already
  `IN_REVIEW` asked for `IN_REVIEW` is answered `Decision.Unchanged` one layer up. What actually
  protects `subject_version_no` is that the column is mapped `updatable = false`.

Neither is a defect. Both are places where a future reader could delete the comment's subject and see
nothing go red.

## 10. Smaller, but recorded

- **`ProjectRepository#countLiveCasesByProject`'s javadoc is wrong.** It claims `s.deleted_at IS
  NULL` "would silently keep the inner join's behaviour"; under a `LEFT JOIN` a non-match yields
  `NULL`, so that predicate is TRUE and the spelling is a **no-op**. The code is correct for a
  different reason than the comment gives.
- **Nothing asserts `project_settings.require_approved_plan_to_start` is gone.** `V32:203` drops it;
  `ddl-auto: validate` tolerates an extra column, so a `DROP COLUMN` that failed on a real database
  would go unobserved.
- **The resolution path's closed-review case has no test.** Removing `refuseIfClosed` from *both*
  comment paths reddens only the add-comment one. (The assignment side has both — §80 applied the
  repair to the new pair rather than retrofitting the old one.)
- **`CaseSummaries`' author resolution is held by one test, not two.** The second passes on fixture
  overlap: its author is also the *owner* of four other rows on the page, so the owner branch puts
  the id in `peopleIds` anyway.
- **No test authors a `=` or `!=` criterion**, so `PlanCriteria.isKnownOperator`'s delegation to
  `satisfies` can be replaced by a hard-coded four-operator set with nothing going red.
- **No test seeds a `DEPRECATED` case into a gated run**, so `<> 'APPROVED'` and
  `IN ('DRAFT','IN_REVIEW')` are indistinguishable to the suite — retired content could enter a
  release run.
- **The evidence storage key's composition is unasserted.** Appending the uploaded filename to it
  reddens nothing; traversal defence rests on the controller's sanitisation plus the adapter's
  `startsWith`, and only the first has a test.
- **Cross-query strongest-wins has no mutation behind it.** The `DISTINCT ON` mutation reddens 0
  because the two grants of the dual-grant persona land in two *different* queries; the merge is
  never given differing inputs. A mutation in `PermissionEvaluator.merge` would be the real one.
- **`ReportJobRunner`'s bytes-before-row ordering is unobservable**, as is `EvidenceService`'s
  opposite rows-before-bytes ordering. Both are argued at length in class headers and asserted
  nowhere; nothing in the suite makes a store write fail.

## 11. §98's flattening — six mutations, none run

The slice removed a column that four modules read, so most of its risk is in what silently keeps
compiling. Each row names the mutation and what it should redden; **none was run**, per §65.

| # | Mutation | What must redden |
|---|---|---|
| 11a | `CaseSpecifications.suiteScope` — delete the `scope.memberCaseIds() != null` branch, so a named suite falls through to `suite_id IN (…)` | **This is the slice's own defect, re-armed.** It reverts the grid to the filed arm while `caseCount` stays the union. `CaseListEndpointTest` must fail on a **tag-defined** suite. If it goes green, the fixture has no suite whose membership differs from its filing — which is exactly why the defect survived §96 and §97 unseen, and the row to fix first |
| 11b | `SuiteRepository.countLiveCasesFiledIn` — widen to the resolved membership | `SuiteWriteEndpointTest`'s delete-a-claiming-suite case must fail. The two readings are *deliberately* different and nothing but a test distinguishes them; if this reddens nothing, deleting a tag-defined suite could start 409ing on cases it does not hold |
| 11c | `CaseConditions.ancestry` — return `List.of()` unconditionally | A case must lose the conditions of the suite it is filed in. Suspected **weak**: the method now returns at most one suite, so the mutation is close to the whole feature, and the fixture attaches a condition to exactly one suite (`Refunds`) |
| 11d | `ExecutionSource` — change `('s' || s.key_seq)::ltree` to `NULL` | Suite-grouped report sections must move their rows into the `(not filed)` bucket. If nothing reddens, no report test covers a suite-grouped section over a **freshly projected** fact row, and the column's contents are unasserted for every row written after §98 |
| 11e | `SuiteRepository.findLiveForProject` — order by `position` instead of `keySeq` | `SuiteEndpointTest.orderingIsTotalAndStable`. Every fixture row has `position = 0`, so Postgres may return any order — meaning this mutation may pass **intermittently**, which is the honest expectation to record rather than a clean red |
| 11f | `CaseFacts.memberOf` — drop one of the three arms | `SuiteScopeAgreementTest`. It survived §98 (the plan expected it to be retired — the duplication is the module boundary's, not the subtree's), and the two predicates are now supposed to be **identical** rather than merely equivalent, so this should be its sharpest round |

**And one that cannot be run here at all:** `V33` drops two columns, and `ddl-auto: validate`
tolerates neither a missing one nor an extra one — but Testcontainers migrates a fresh container, so
a `DROP COLUMN` that failed against a real database with data would go unobserved. Same shape as
`V32`'s backfill row below, and it belongs to the Hetzner list.

## 12. §99's run scope — seven mutations, none run

The slice adds two arms to one query and changes no gate, so nearly all of its risk is in the query
itself and in what the *validator* stops refusing. **None was run**, per §65.

| # | Mutation | What must redden |
|---|---|---|
| 12a | `CaseFacts.caseIdsInRunScope` — replace the suite arm with `c.suite_id IN (:suiteIds)` | **The slice's own defect, armed.** `RunCreateEndpointTest.Scope.aSuiteArmResolvesMembership` must fail, because PAYMENTS claims `caseA` by tag and files nothing. `paymentsFilesNothing` is the guard on that property; if 12a goes green, run *that* test first — a fixture where the two readings agree makes every row below vacuous |
| 12b | Same method — drop `AND c.deleted_at IS NULL` | `aDeletedCaseIsNotReachedByItsTag`. `case_tag` rows outlive a soft delete, so this is the one arm where the omission is reachable through data rather than through a missing join |
| 12c | Same method — drop `AND s.project_id = c.project_id` from the suite arm | Suspected **weak**: `aSiblingProjectsSuiteIsRefused` is refused by `RunEdit`'s `liveSuiteIds` check one step earlier, so the query's own bound may never be reached. If nothing reddens, the second gate is untested and the honest repair is a test that bypasses the validator, not a claim of coverage |
| 12d | `RunCreateService` — pass only `requestedCases` to the resolution, dropping the other two arms | `theArmsUnionAndDedupe` and both single-arm tests. Expected to redden broadly; recorded because a narrow red would mean the arms are covered by one test each and not by the union |
| 12e | `RunEdit.validateCreate` — remove the `liveSuiteIds` loop | `aSiblingProjectsSuiteIsRefused` **and** `anAbsentSuiteIsRefused`. Both, or the check is only pinned in the direction that also fails at the query |
| 12f | `RunEdit.scopeFields` — return `List.of("caseIds")` unconditionally | `aTagMatchingNothingIsRefusedOnItsOwnField` (its `doesNotContainKey` half) and `everyArmTheCallerUsedCarriesTheRefusal`. The mutation is what the code did before §99, so a green run means the new rule is described and not asserted |
| 12g | `RunEdit.tagsOf` — drop the trim | `tagsAreTrimmed`. The test exists because the tags that are *validated* and the tags that are *queried* come from this one function, and trimming in only one of two places is the drift it prevents |

**Two things the suite structurally cannot observe here.** The gate over the new arms
(`theNewScopeArmsAreGatedAsWell`) can only fail if the gate stops reading the resolved set — there
is no mutation that covers "somebody adds a fourth arm and forgets", because the coverage is
structural. And the union's **ordering** by `key_seq` is asserted nowhere: `caseIdsOf` reads
`test_execution` with no `ORDER BY`, so a resolution that returned its arms concatenated would pass
every row above. Recorded rather than papered over — the display order is the view's, so the cost is
insertion order only.

## 13. §101's M2 rollups — eleven mutations, none run

Two `V1` tables get their first writers and two screens stop scanning. **The risk is concentrated in
three places and all three fail silently in production**, which is why 13a, 13b and 13c are the rows
that must not be skipped: each is one of the slice's own findings, armed.

**None was run**, per §65.

| # | Mutation | What must redden |
|---|---|---|
| 13a | `ExecutionSource.correctSuiteRunCounts` — join `test_execution` on the suite's **current** membership instead of on the row's `membership_snapshot` | **Finding 1, armed.** `SuiteRunSummaryRefreshTest.Freeze.aLaterTagDoesNotRewriteAPastRun`. Nothing else should move: `anAmendedVerdictIsCorrected` must stay green, and if it also reddens the mutation broke the correcting statement rather than the freeze, which is a different (and less interesting) result |
| 13b | `RollupRefreshService.refresh` — delete the `source.prunePlanSnapshot()` call | **Finding 2, armed.** `PlanSnapshotRefreshTest.Prune.prunesACaseThatLeftTheScope` **and** `prunesASoftDeletedCase`. Both, or the prune is pinned only in the direction that some other filter also covers. `theSiblingIsNotPruned` must stay green — it asserts the asymmetry, not the prune |
| 13c | `PlanStatisticsService.compute` — make `fromRollup` depend on `servedFromRollup(policy)` alone, dropping the `snapshotIsComplete` conjunct | **Finding 3, armed — the defect the oracle test caught before it shipped.** `RollupAgreementTest.anEmptyRollupDoesNotReportEveryCaseAsNotRun` and `aSnapshotMissingOneCaseFallsBackToLive`. A green run here means the repair is described and not asserted, and the behaviour it restores is a plan that has been executed reporting that nothing has been executed |
| 13d | `ExecutionSource.insertNewSuiteRunSummaries` — replace the three-arm predicate with `c.suite_id = s.id` | `SuiteRunSummaryRefreshTest.summarisesATagDefinedSuite`, `Agreement.theSnapshotAgreesWithTheRepositorysUnion`, and `RollupAgreementTest.suiteHistoryAgrees`. `aTagDefinedSuiteFilesNothing` is the guard on the property — **if 13d goes green, run that first**, because a fixture where the two readings agree makes every row here vacuous |
| 13e | Same statement — change the `LEFT JOIN case_status` to an inner join | `anUnrecordedExecutionIsNotRunRatherThanMissing`. This is the one place the rollup deliberately differs from `execution_fact`, whose header says coverage denominators can never come from it; the inner spelling makes `total` exclude the un-run cases and a run of 3 of 47 read as 100% |
| 13f | `ExecutionSource.correctSuiteRunCounts` — drop the trailing `IS DISTINCT FROM` guard | `updatedAtDoesNotMoveWhenNothingChanges`. Predicted to redden exactly one test; recorded because the consequence is not a wrong count but a `computedAt` that always says "just now", which is a freshness field that says nothing |
| 13g | `ExecutionSource.refreshPlanSnapshot` — reverse the tiebreak to `e.executed_at ASC` | `PlanSnapshotRefreshTest.keepsTheLatestExecutionPerCasePerConfiguration` and `RollupAgreementTest.planStatisticsAgreeUnderLatest`. The fixture's `CASE_TWICE` is what makes this observable — with one execution per case it reddens nothing |
| 13h | Same statement — drop `AND r.deleted_at IS NULL` from the `test_run` join | Suspected **weak**: no fixture here soft-deletes a run of a plan. If nothing reddens, that is the finding and the repair is a fixture row, not a claim of coverage. The behaviour matters — counting a withdrawn run restores a result somebody meant to retract |
| 13i | `ExecutionSource.prunePlanSnapshot` — drop the `c.deleted_at IS NULL` conjunct | `prunesASoftDeletedCase` alone. If `prunesACaseThatLeftTheScope` also reddens, the two predicates are not independent and the row's split is wrong |
| 13j | `RollupFacts.runsWithoutSummary` — return `List.of()` unconditionally | `RollupAgreementTest.aRunNewerThanTheRollupIsStillInTheHistory`. The mutation is exactly "serve the suite history from the rollup alone", and what it produces is an **absent** run rather than a stale one |
| 13k | `SuiteHistoryService.computedAt` / `PlanStatisticsService.computedAt` — take the newest `updated_at` instead of the oldest | Predicted to redden **nothing**, and that is the row rather than a shortfall: no fixture here has two rollup rows written at measurably different instants. The decision is argued in both javadocs and the honest repair is a test that writes two rows a second apart, listed under **Tests to write** below |

**One thing this slice's suite structurally cannot observe**, stated rather than implied: whether the
rollup is *faster*. Every row above is about correctness. The argument for the rollup is structural —
aggregate in Postgres over an indexed table rather than materialising N rows into JVM objects — and
`CLAUDE.md`'s *"measure bundle changes before believing them"* applies to this exactly as it does to
a bundle. **It was not measured.**

## 15. R6 phase 1's aggregate report — nine mutations, **two of them already run**

The aggregate execution report resolves the aggregation policy in SQL, a second implementation of a
rule `PlanStatisticsService.resolve` already owns. `AggregateExecutionCrossCheckTest` is the guard,
so the question this section answers is **whether that guard can actually fail** — and unusually,
two of the rows below were run during the slice rather than queued, because a cross-check nobody has
tried to break is a claim and not a test.

**Run, and recorded as run:**

| # | Mutation | Result |
|---|---|---|
| R6-1 | `WORST`'s `severityRank(...) ASC` → `DESC` (worst becomes best) | ✅ **RED, as designed** — reddens both `policy=WORST`'s agreement and `theFixtureDiscriminates`, the latter because `WORST` collapses onto `BEST`. |
| R6-2 | `LATEST`'s `executed_at DESC` → `ASC` (each case resolved to its **oldest** result — the policy inverted) | ⚠️ **GREEN on the first fixture — the oracle was vacuous.** `RECOVERED` (fail→pass) and `REGRESSED` (pass→fail) were exact mirrors, so reversing time swapped *which* case was passing and changed **no total at all**. `UNBLOCKED` (blocked→passed) was added to break the symmetry; the mutant now reddens `LATEST` **and** `LATEST_PER_CONFIG`. **The lesson generalises: a fixture built from mirrored pairs is symmetric under exactly the mutation an ordering test exists to catch.** |

**Queued, not run:**

| # | Mutation | Expected |
|---|---|---|
| R6-3 | Drop `verdictFirst()` from `WORST`'s ordering | An `UNTESTED` row outranks `PASSED`, so a case that passed in every run it was recorded in reads as owed a verdict. Should redden `policy=WORST`. **Cannot be observed on the current fixture** — every execution in it carries a verdict, so this is expected to redden **nothing** until the fixture grows a recorded non-verdict status. Recorded as a *gap*, not as coverage. |
| R6-4 | `severityRank`'s `ELSE -1` → `ELSE 99` (an unknown kind ranks best rather than worst) | Unreachable today: `case_status.kind` has a CHECK. Recorded so the choice of direction is not mistaken for arbitrary. |
| R6-5 | `LATEST_PER_CONFIG`'s partition drops `configuration_id` | Collapses to `LATEST`. **Expected to redden nothing on the current fixture**, which has one configuration — §46's "byConfiguration collapsing to a single column" is *not* yet guarded here. A second configuration is the repair. |
| R6-6 | `AggregateSelection.where()` emits `IN (:planIds)` even when the list is empty | §99's defect: `IN ()` fails to parse, `IN (NULL)` is never true and reads as an arm that matched. Should redden every test, loudly. |
| R6-7 | `idList`/`textList` treat an empty array as "select nothing" rather than "do not narrow" | `{"planIds": []}` becomes a clean report of zero executions. **No test names this today.** |
| R6-8 | `featureGroups`' `LEFT JOIN LATERAL unnest` → a plain `unnest` in the select list | The unclassified row vanishes and the section reads as a project that classified everything (§96's `JOIN suite`, second shape). **No test names this today** — the cross-check does not read the feature section. |
| R6-9 | `optionalInstant` swallows a bad date instead of refusing | A report over *every* execution ever recorded, printed under a header still claiming the range. **No test names this today.** |

**What this section is really reporting**: four of the seven queued rows are expected to redden
**nothing**, and that is the finding rather than a shortfall. The cross-check guards the *policy
resolution* well (R6-1/R6-2 prove it) and guards the *selection* and the *feature section* not at
all. ~~R6's remaining phases should close R6-5 (a second configuration in the fixture) and R6-7/
R6-8/R6-9 (a selection test that is not the cross-check), or say why not.~~ → **closed by phase 4
(`AggregateSelectionTest`), and every one of them was re-run against its mutant rather than assumed:**

| # | Re-run after phase 4 | Result |
|---|---|---|
| R6-3 | `verdictFirst()` dropped from `WORST` | ✅ **RED** — `Verdicts`. The fixture now records a `RETEST`, which is what the cross-check's does not. |
| R6-5 | `LATEST_PER_CONFIG`'s partition drops `configuration_id` | ✅ **RED** — `Configurations`. A second configuration is all it needed. |
| R6-7 | the `planIds` arm emitted unconditionally | ✅ **RED**, and loudly — 16 tests. |
| R6-8 | the `CASE … ARRAY[NULL]` removed from the lateral | ⚠️ **GREEN — and this is phase 4's finding.** `LEFT JOIN LATERAL … ON true` **already** preserves a row whose `unnest` yields nothing, so the `CASE` is not the guard the code's own comment said it was. The mutation that matters is the **join kind**: `CROSS JOIN LATERAL` reddens three tests. The comment is corrected in place, struck through with its correction, because a reviewer protecting the `CASE` would be protecting the wrong half. |
| R6-9 | `optionalInstant` swallows a bad date | ✅ **RED** — `Dates`. |

**Still not covered, and named rather than implied**: R6-4 stays unreachable (a CHECK constraint
guards `case_status.kind`), and nothing asserts that the policy the *dialog* sends is the one the
server resolves under — that crosses the wire and neither suite can see it.

---

## 16. R6 phase 3's dialog — four frontend mutations, **all four run**

Phase 3 put the aggregate report behind a form. Its four new tests **all passed on the first run**,
which this file treats as a question rather than as a result (§89's poll test passed first time in a
form that could not fail). So each was mutated once, in `GenerateReportDialog.tsx`, and reverted.

| # | Mutation | Result |
|---|---|---|
| R6-10 | `disabled={… || (!isAggregate && available.length === 0)}` → back to `available.length === 0` (the pre-R6 gate) | ✅ **RED** — *"can be asked for in a project with no runs at all"*. The row is worth keeping precisely because the assertion is `toBeEnabled()`: written the natural way round it is green against the dialog this mutation restores. |
| R6-11 | `parameters.to = dayAfter(...)` → `startOfDay(...)` (the typed date sent verbatim) | ✅ **RED** — *"sends the day AFTER the last day"*. Un-mutated this is invisible: the report renders, the header prints the range the caller typed, and the last day of every range is missing. |
| R6-12 | `if (planIds.length > 0)` dropped, so an empty array is always sent | ✅ **RED** — *"sends only what was narrowed on"*, because the body assertion is `toEqual` and not `toMatchObject`. The defect is not arithmetic — the server reads `[]` as absent — it is a **selection block on the artifact claiming a filter that was not applied**. |
| R6-13 | the `to < from` clause in `superRefine` → `false` | ✅ **RED** — *"refuses a backwards range here"*. |

**What is NOT covered, stated rather than implied**: nothing asserts that the *policy* the form
sends is the one the server resolves under — that is the cross-check test's job on the other side of
the wire, and no frontend test can see it. And the Features control's union semantics are a sentence
in a helper text, not a behaviour this suite can observe; browser row `M6-25` is where it is checked.

---

## 17. R7's three variability sections — six mutations, none run

R7 adds three sections that read the **un-collapsed** row set (`selectedCte`) where the rest of the
report reads the policy-resolved one. `AggregateVariabilityTest` (15 tests, prefix `c3`) is written
around that, and two of its tests are the ones worth breaking.

**What the RED run already established, and what it did not.** Twelve of the fifteen tests failed
before the sections existed, so their positive assertions are known non-vacuous. **Three passed on
the RED run** — `blockedThenPassedIsNotListed`, `agreementIsNotListedInEitherDirection`,
`theCaseIsNamedByItsKey` — because all three assert an **absence**, and an absent section satisfies
them. They currently carry **no evidence at all**, and R7-2 and R7-3 below are the only way to give
them any. That is the reason they are named individually rather than left inside a count.

| # | Mutation | Expected |
|---|---|---|
| R7-1 | `inconsistencySection`/`retrySection` call `resolvedCte` instead of `selectedCte` | **THE row.** Under the default `LATEST` each case is one row, so the callout empties and *"passed only after a retry"* goes to zero — a report claiming a project with no instability and no retries. Should redden `theCalloutSurvivesTheDefaultPolicy` and `theRetryOutcomesIgnoreThePolicy`. If it reddens only the `ALL` comparisons, the tests are asserting agreement between two wrong answers. |
| R7-2 | `WHERE p.passed > 0 AND p.failed > 0` → the slice plan's own shape, `count(DISTINCT counts_as_pass) > 1` | The blocked-then-passed case is listed as an inconsistency. Should redden `blockedThenPassedIsNotListed`, which **passed on the RED run** and is therefore unproven. §105's rule, one report over. |
| R7-3 | `bool_or(c.passed > 0 AND c.failed > 0)` → `true` | Every listed case reads *Within one configuration and version*, so a configuration-dependent failure is reported as a flaky test. Should redden `theRowSaysWhereTheDisagreementIs`. Then the inverse — `→ false` — which should redden it the other way and turn every genuine flake into "across configurations". |
| R7-4 | `retryOutcomes`' `ORDER BY s.executed_at ASC NULLS LAST` → `DESC` (the **last** attempt read as the first) | Expected **RED** on the three outcome counts — but note R6-2's lesson: a fixture of mirrored pairs is symmetric under exactly this mutation. Here `BLOCKED_THEN_PASSED` and `ALWAYS_FAILING` break the mirror. If it comes back GREEN, the fixture is the defect, not the code. |
| R7-5 | `attempts`' partition drops `configuration_id`, then (separately) `case_version_id` | Two runs. Each should move `CONFIG_SPLIT` / `VERSION_SPLIT` out of two groups into one, so the totals fall from 8 and *"passed only after a retry"* rises — a cross-configuration difference reported as a retry, which is §6p's false-flake signal in the third section. |
| R7-6 | `caseLabels`' `AND c.project_id = :projectId` dropped | A sibling project's case title on this project's report. **Expected to redden nothing** — no test in this class seeds a second project, and RLS scopes to the company, not the project. Recorded as a **gap**: it is `CatalogueFacts`/`PlanFacts`' stated reason for existing, arriving in a fourth file, and the row that would close it is a two-project fixture. |

**Also not covered, and named**: nothing asserts the three sections against a *second* implementation
the way `AggregateExecutionCrossCheckTest` does for the policy — there is no second implementation to
check against, because no screen in the product computes an inconsistency callout. The retry
arithmetic is therefore asserted against literals, which §46 warns about; what makes it defensible
here is that the three outcomes are disjoint and exhaustive, so the fixture's 3 + 2 + 3 = 8 is
checkable by hand against a fixture whose every execution is listed in one place.

---

## 18. R8's version divergence — seven mutations, none run, and **no RED run to lean on**

R8 adds a sixth section reading the same un-collapsed row set R7 introduced, plus the one query in
the class that reads **both** CTEs: what diverged comes from `selected`, whether the totals used it
comes from `resolved`. `AggregateVersionDivergenceTest` (13 tests, prefix `c4`) is written around it.

**Stated rather than smoothed over: this slice had no RED run.** The tests were written after the
implementation, so unlike R7 — where twelve of fifteen failed before the sections existed — nothing
here has been observed failing. What stands in for it is **structural and not evidence**:
`theFixtureHolds` asserts five executions under `LATEST` over five cases, so the resolved set holds
exactly one row per case, so `count(DISTINCT case_version_id) > 1` over it is unsatisfiable and the
section would be empty. That argument is sound and it is still an argument. **R8-1 is the only thing
that can turn it into a measurement**, and it therefore carries more weight here than R7-1 did.

| # | Mutation | Expected |
|---|---|---|
| R8-1 | `versionDivergence`'s `diverged` and `per_version` read `FROM resolved` instead of `FROM selected` | **THE row, and the one this section stands or falls on.** Under the default `LATEST` every case is one row at one version, so the section empties, the header flag goes to 0 and the report claims a project that has never edited a test between runs. Should redden `theSectionSurvivesTheDefaultPolicy`, `divergenceIsWiderThanTheInconsistencyCallout`, `theMostDividedCaseIsFirst`, `theMetadataFlagsTheCount` and every test in `Counted`. If it reddens **only** the cross-policy equalities, the tests are asserting agreement between two wrong answers. |
| R8-2 | `HAVING count(DISTINCT s.case_version_id) > 1` → the callout's predicate (`passed > 0 AND failed > 0`) | `STILL_FAILING` — failed at both versions — drops out, and the section becomes a duplicate of the inconsistency callout's *Across versions* rows. Should redden `divergenceIsWiderThanTheInconsistencyCallout`. §6e's harm is the **blending**, not the disagreement. |
| R8-3 | The `EXISTS` against `resolved` drops its `r.case_version_id = v.case_version_id` term, matching on the case alone | Every listed version reads **Counted** under every policy, and the narrative then claims the totals average across versions when under `LATEST` they average nothing. Should redden `theDefaultPolicyCountsOneVersionPerCase`, `worstCanCountTheOlderVersion` and half of `latestPerConfigBlendsAcrossConfigurations`. |
| R8-4 | `blendsVersions(...)` → `selection.policy() == AggregationPolicy.ALL` — the shortcut the method's javadoc exists to refuse | Expected to redden **exactly one assertion**: `latestPerConfigBlendsAcrossConfigurations`'s narrative check, where two configurations pinned different versions and the totals blend under a policy that is not `ALL`. A narrow blast radius is the *correct* outcome here. **If it reddens nothing at all, the fixture's second configuration is not doing its job** and the finding is in `PER_CONFIG`, not in the code. |
| R8-5 | The Java comparator: drop the `-versionsPerCase` term; then, separately, the `versionNo` term | Two runs. The first should move `THREE_VERSIONS` out of the first three rows (`theMostDividedCaseIsFirst`); the second should let a case's versions come back in id order rather than v1, v2, v3 — **and may come back GREEN by luck**, since `gen_random_uuid()` orders arbitrarily. A green here is not a passing mutation, it is an unrepeatable test, and the repair is a deterministic version id in the fixture. |
| R8-6 | `metadata` emits the divergence entry only when the count is non-zero | Should redden the second assertion of `theMetadataFlagsTheCount`. Recorded as its own row because "print it even when it is zero" is exactly the kind of line a later tidy removes as dead, and the failure mode — a report where the flag's absence and the flag's zero look identical — is this repository's most repeated shape. |
| R8-7 | `caseVersionNumbers`' `AND c.project_id = :projectId` dropped | A sibling project's version number on this project's report. **Expected to redden nothing** — R7-6 one method over, for the same reason: the class seeds one project and RLS scopes to the company, not the project. Recorded as a **gap**, and it is now the *fifth* file layering this predicate with nothing checking any of them. The row that closes both is a two-project fixture, and it should be written once for the seam rather than twice for its methods. |

**Also not covered, and named.** There is no second implementation of version divergence anywhere in
the product, so nothing plays `AggregateExecutionCrossCheckTest`'s role here — the counts are
asserted against literals, which §46 warns about. What makes it defensible is that the fixture's
eleven executions are each listed in one place in the class header and the nine printed rows are
countable by hand. What is **not** covered by anything is the interaction of divergence with the
`suitePaths` and `featureIds` facets: every test in this class narrows by policy or by date only.

---

## 14. §106's board drag — one frontend row, and the backend rows it does NOT owe

§106 replaced the board's native HTML5 drag with `@dnd-kit` and made the move optimistic. **No
backend file was touched and no contract moved**, so the Java suite owes nothing here — recorded
explicitly, because a slice that writes to `PATCH /work-items/{id}` and adds no rows to this file
otherwise reads as a slice that forgot.

What it *did* expose was on the mock side:

| # | Mutation | What must redden |
|---|---|---|
| ~~14a~~ | ~~*(frontend)* `mocks/handlers/workItems.ts` — delete the `allowedTransitions` 409 §106 added~~ | **VOID since §107 — the file is deleted.** The row said no component test drove the mock handlers, which was the finding; §107 removed the handlers entirely, so there is nothing left to mutate. **What it was really asking has not gone away and is now 14b's alone**: the board relies client-side on a rule only the server enforces. Browser row `M1-29` moved to the `L2` refusals lane for the same reason (§109) |
| 14b | *(backend, unrun)* `WorkItemService`'s move — accept a status outside `allowedTransitions` | Whichever `WorkItemEndpointTest` case pins the 409 the contract declares. **Not verified to exist.** If nothing reddens, the server's half of the rule this slice now relies on client-side is unasserted, and that is a test to write rather than a mutation that failed |

**Neither was run**, per §65 — and 14a can no longer be, which leaves 14b carrying the whole rule.

---

# CLOSED — no longer owed

- **§69's `ProjectService.writeGrantedOnTarget` reordering is closed by construction.** The
  parameter is `Function<R, RequiredPermission>` and `R` is produced only by `resolve.apply(gated)`;
  moving the resolve below the permission check leaves `permission.apply(...)` with no argument in
  scope. The javadoc's claim — a caller whose row does not exist cannot be told 403 without the
  server inventing which permission they failed — is enforced by the **type signature**, which is
  stronger than a test.
- **§93's five "write these tests" rows are all COVERED today** by `PlanDocumentEndpointTest`:
  sibling-project subject 404, re-linking writes nothing, sibling project's case 404, both
  traceability 409s, and `level`'s three arms (the `Depth` class).
- **§97 phase 3b's `ck_plan_scope_target` assertion is written and green.**
  `PlanScopeConstraintTest` asserts both halves in both directions over plain JDBC as the application
  role, deliberately bypassing `PlanScopeItem`'s factories.
- **`DocPage#linkTo`'s entity guard is a live backstop, not dead code.** It reddens nothing on its
  own because the service's check answers first — but delete that branch and this guard is what
  fires (as a 500, not the 409). Do not remove it on the strength of a coverage report.
- **`SeedDataTest.everyReviewerIsAlsoACommenter` is not vacuous.** Its outer set is non-empty in the
  seeded catalogue, so a role granted a review key without `case:comment` would be selected.

---

# STILL OWED

## Needs the Hetzner box — migration-in-place checks

Testcontainers migrates a fresh container every time and **structurally cannot make these checks**.
None was attempted; all remain owed.

| Migration | What to show |
|---|---|
| `V20` | v19 → v20 in place, `installed_by = tcms_owner`, index present with `indpred` non-null and `company_id` leading |
| `V21` | v20 → v21; `uq_team_name` present as a unique **index** over `(company_id, lower(name))`, no longer a table constraint. Drops a constraint and creates an index in its place — the first to do so |
| `V22` | drops and re-adds `review_state_check`, adds two indexes; table has never held a row |
| `V23` | **the first migration since V1 to CREATE A TABLE.** Confirm the policy carries `V4`'s `nullif(...)` predicate and not `V1`'s, that `tcms_app` default privileges covered it without an explicit `GRANT`, and that the backfill left exactly one row per project |
| `V24`–`V28` | uneventful by expectation — which is the thing to confirm rather than assume |
| `V33` | **destructive, and the only one on this list that discards information.** Drops `suite.parent_id`, `suite.path` and `suite_path_idx` against a database that has them **with data in them**. Confirm: the GIST index is gone, the `ltree` extension **survives** (three other columns use it), `execution_fact.suite_path` is **byte-for-byte unchanged** including its dotted paths, and `ddl-auto: validate` boots afterwards. The parent-child relationships are gone for good — unlike `V32`'s lifecycle reduction, no audit event records them |
| `V32` | **the one that matters most.** Its status backfill runs inside a per-company `set_config` loop because `test_plan` is under `FORCE ROW LEVEL SECURITY`. Verified here that removing the loop leaves the migration **green on a fresh container** — a fresh database has no `ACTIVE`/`CLOSED` row to backfill, so the bare `UPDATE` touches zero rows and reports success. **That is V7's shape exactly.** Only a restored dump can check it |

Also owed on a real box: `EXPLAIN (ANALYZE)` on the step-suggestion query, to confirm
`test_step_action_trgm_idx` is actually chosen. Confirmed by reading that it is spelled on the
**expression** `lower(action)` and not the bare column — the two V1 siblings
(`shared_step_title_trgm_idx`, `condition_title_trgm_idx`) still have the wrong spelling, deliberately.

## Not reproducible through the suite

- **`V22`'s partial unique index under a race.** Two concurrent `PUT …/status` submissions of one
  artifact: one must succeed and one must fail on `uq_review_open_per_subject`. Needs two interleaved
  transactions; MockMvc cannot express it.
- **`review_assignment`'s cascade on a real database** — deleting a `team` row must remove its
  `review_assignment` rows and nothing else. The in-suite half
  (`deletingATeamCascadesOntoItsRequests`) is green.

## Tests to write


- **§101 — two rollup rows written a second apart**, so `computedAt`'s oldest-not-newest
  decision becomes assertable. Row 13k predicts zero reds today: both services pick the minimum
  `updated_at` across the rows they used, and no fixture produces two distinct instants, so the
  choice is argued in javadoc and pinned by nothing. It matters because the page mixes rolled-up rows
  with a live tail, and the newest is a claim true of one row and false of the others — false in the
  reassuring direction.

Each is a gap this pass measured rather than a guess. Numbered findings above give the reasoning.

1. A classified case in `CaseBulkEndpointTest`'s fixture (finding 3).
2. A condition attached across a bulk action (finding 2).
3. A tag and a section in `PlanStatisticsEndpointTest`'s fixture (finding 4).
4. A `DEEP` case with failures outside the window (finding 5).
5. A `WIP` execution asserted through `notRun` and through coverage (finding 8).
6. A `DEPRECATED` case seeded into a gated run.
7. A criterion authored with `=` / `!=`.
8. A fault-injecting `EvidenceStore` and `ReportStore`, to make both storage orderings observable
   (finding 9's third cousin) — each needs its own slice.
9. Step corpus: a ~5k-step project answering a 3-character `q` in typeahead time; and the corpus
   after a case is edited.
10. Conditions: the edit under real concurrency (two readers at version *n*, one 200 and one 409);
    and an edit to a condition attached to a **suite**, reaching cases added after the edit.
11. Plan scope widening end to end: a plan scoped on a tag-defined suite, a run created from it, and
    the executions materialised.
12. A retired condition attachment surviving a save that cannot mention it
    (`CaseConditions.reconcile`'s `!live.contains(...)` guard — currently uncovered).

---

# CORRECTIONS TO THIS FILE'S OWN FACTS

The pass found the queue's stated invariants had drifted. Superseded, with the reason:

- ~~"`V22` / `V26` / `V27` / `V28` / `V29` / `V30` / `V31` is the next free number"~~ → **head is
  `V32__plan_sections_scope_prose_and_lifecycle.sql`; `V33` is next free.**
- ~~"`SchemaConventionTest` asserts 56 / 57 / 58 / 59 policies"~~ → **61**
  (`SchemaConventionTest:263`).
- ~~§73's *"CLOSED is reachable only from ACTIVE"* row~~ and ~~§82's whole plan-side approval gate~~
  → **STALE-BY-DESIGN.** §97 phase 3a reduced the plan ladder to `DRAFT → IN_REVIEW → APPROVED` and
  deleted `PlanLifecycle.startsWithoutApproval`, `PlanLifecycle.isClosed`, `test_plan.closed_at`,
  `project_settings.require_approved_plan_to_start` and `PlanStatusInput.overrideApprovalGate`. §6g's
  **case** gate (`requireApprovedCaseForRun`) survives and still binds. §73's gate row consequently
  reddens 2 where it predicted 3 — the missing third named the `ACTIVE` state.
- ~~§93's row scoping `PlanDocumentEndpointTest$Lifecycle`~~ → the nested class is **`$Adoption`**.
- ~~§97 phase 3b's row naming `PlanStatisticsTest`~~ → the class is **`PlanStatisticsEndpointTest`**.
- ~~§97 phase 3a's *"four statement switches consume `StatusKind`"*~~ → there are **three**; the
  fourth reference is `isVerdict`.
- ~~§95's rows scoping the usage-count tests to `ConditionWriteEndpointTest`~~ → they live in
  **`ConditionLibraryEndpointTest`**; the blanket scope would have under-measured them.
- ~~§68's *"a 500 from V2's `23505`"*~~ → the raise is the trigger's own `RAISE` with its explanatory
  message, not a bare unique-violation SQLSTATE. The layer and failure class are as predicted.

---

# THE MUTATION THAT CANNOT BE WRITTEN, AND WHY IT IS RECORDED

Swapping the team gate's key from `company:teams` to `company:members` reddens **nothing**. `V3`
grants both to exactly the same roles, so every persona the suite can build holds both or neither:
the distinction is real in the code and unobservable in the seeded data.

That is not repaired by inventing a fixture role — it is recorded by
`SeedDataTest.teamManagementAndMemberManagementAreStillHeldTogether`, which fails the day a role
holds one without the other. **When it fails, that is the good case**: the discrimination has become
observable and the repair is to add that persona to `TeamEndpointTest.Gate`, not to re-align the
seed. The frontend carries the only test that can tell the two keys apart today
(`TeamsPage.test.tsx`), because a test can mock a permission set the seed cannot produce.

The counter-examples are worth naming beside it, because they are what a *discriminating* gate looks
like: `run:close` vs `result:submit`, `plan:approve` vs `plan:edit`, `suite:approve` vs
`suite:manage`, `case:comment` vs `plan:review`, and — only because §87 inserted the suite's first
two custom PROJECT-scope roles — `report:view` vs `report:export`. Each has a persona the suite can
build that holds one and not the other, and each swap reddens exactly the tests that name it.

---

## 19. §115's coverage matrix — five mutations **RUN**, four not, and a deviation stated rather than buried

§115 adds `coverage_matrix`: a report whose spine is a `doc_page`, over a new `DocumentationSource`
seam (`doc_page` · `doc_block` · `doc_link`) and a `DISTINCT ON` over `execution_fact`.
`CoverageMatrixReportTest` (18 tests, prefix `c5`) is written around it.

> **⚠ DEVIATION FROM §65, AND IT IS DELIBERATE.** The standing policy is *write the row, run
> nothing*, and §72–§76 were reversed for running a handful inline. Five of the nine rows below were
> nonetheless **run during the slice**, for one reason: this class went **18/18 green first time**,
> which is exactly the state §114 was criticised for (*"THIS SLICE HAS NO RED RUN"*), and §18 of this
> file records that the substitute — a structural argument — *"is sound and it is still an
> argument"*. Running the five turns the argument into a measurement. **This is not licence to run
> the cheap ones during a slice**; it is the specific remedy the previous section asked for. The four
> unrun rows below are the ordinary batched ones and stay unrun.

| # | Mutation | Expected | Result |
|---|---|---|---|
| CM-1 | `latestResults`' `ORDER BY … executed_at DESC NULLS LAST, execution_id DESC` → `ASC … ASC` | Each case resolves to its **oldest** result, so a case that failed and was fixed reads as failing. Should redden `theResultIsTheLatestOne`. | ✅ **RUN — reddened `theResultIsTheLatestOne` and `oneFailingCaseMakesTheStatementFailing`.** The second is collateral and correct: `FIXED` reverting to its failure moves the summary ladder too. The fixture's two results are deliberately **not** mirrored, which is what stops this passing by symmetry — R6's vacuous-fixture trap in the form it takes here. |
| CM-2 | `verifyingCasesByBlock` drops `AND l.relationship = 'VERIFIES'` | Every relationship counts as coverage, so a `REFERENCES` link — *"context, not coverage"* — proves a requirement. Should redden `onlyVerifiesCounts`. | ✅ **RUN — reddened `onlyVerifiesCounts` and `oneFailingCaseMakesTheStatementFailing`.** This is the one-word change `LinkRelationship`'s header exists to warn about, and it inflates every coverage number in the product while looking correct in any fixture that has only `VERIFIES` rows. |
| CM-3 | `stateOf` returns `VERIFIED` on the first passing case instead of `FAILING` on the first non-passing one | The optimistic roll-up: four green and one red reads as verified. Should redden `oneFailingCaseMakesTheStatementFailing` **and nothing else**, because it is the only statement in the fixture with two cases of differing verdicts. | ✅ **RUN — reddened exactly that one test.** The narrow blast radius is the correct outcome; a wider one would have meant the ladder was being asserted by accident elsewhere. |
| CM-4 | `isStatement` → `return true` (every block is a coverage candidate) | A specification's prose becomes uncovered requirements. Should redden `listItemsAreStatementsAndProseIsNot` and `aProsePageSaysItHasNoStatements`. | ✅ **RUN — reddened those two plus `statementsBeforeAnyHeadingArePreamble` and `oneFailingCaseMakesTheStatementFailing`.** Four is right: the preamble gains the intro paragraph and the ladder gains three uncovered rows. This is `DocBlockView`'s zeroed-marker objection arriving in a document sent to an auditor. |
| CM-5 | `verifyingCasesByBlock` drops its `DISTINCT` | The same claim recorded twice becomes two evidence rows while the statement counts stay put, so the table and the summary silently disagree. Should redden `duplicateLinksCountOnce`. | ✅ **RUN — reddened exactly that one test.** `doc_link` has **no unique index**; `BlockLinkService`'s 200-not-409 is a promise with nothing behind it at the database, which is why this is belt and braces rather than paranoia. |
| CM-6 | `findPage` drops `AND p.project_id = :projectId` | A sibling project's document, in the same company, becomes reportable — RLS scopes to the company and no further. **Expected to redden nothing**: the class seeds one project per company. | ⬜ **NOT RUN — and it is a known gap, not a prediction.** This is now the **sixth** file layering a project predicate over RLS with nothing checking any of them (R7-6, R8-7, `PlanFacts`, `CatalogueFacts`, `ExecutionSource`×2). The row that closes all six is one two-project fixture written **once for the seam**, and it should stop being re-recorded per method. |
| CM-7 | `findPage` drops `AND p.deleted_at IS NULL` | A report whose spine is a document somebody removed. Should redden `aDeletedPageIsRefused`. | ⬜ NOT RUN |
| CM-8 | `blocks`' `ORDER BY b.position, b.id` → `ORDER BY b.id` | The specification comes out in a different order from the page it is about. Should redden `theSpineIsTheDocumentsOwnOrder` and `statementsBeforeAnyHeadingArePreamble`. **`doc_block` has no unique constraint on `(page_id, position)`**, so dropping the `b.id` tiebreak alone is expected to redden **nothing** and is the more interesting half — a non-total order produces two documents from one model only when two blocks tie. | ⬜ NOT RUN — run **both halves**; the second is the determinism claim and the first is only its symptom. |
| CM-9 | `caseLabel` returns `""` instead of the raw id for a case that no longer resolves | A `doc_link` outliving its case — reachable, because `source_id` is opaque with **no foreign key** — renders as a blank cell rather than as evidence a claim was recorded. Expected to redden **nothing**: the fixture has no dangling link. | ⬜ NOT RUN — recorded as a **gap in the fixture**, not in the code. Closing it is one `doc_link` row naming a `gen_random_uuid()`. |

---

## 20. §116's API tokens — the credential M3 runs on, and the one mutation the suite already ran for itself

§116 adds `api_token`'s first writer and the authentication path behind it: `V36`'s instance-level
`api_token_lookup`, `ApiTokenService` (issue · list · revoke), `ApiTokenAuthenticationFilter`, and
the `PermissionEvaluator` intersection that caps a token by its role **and** its owner.
`ApiTokenEndpointTest` (26 tests, prefix `9d`) and `ApiTokenAuthenticationTest` (13 tests, prefix
`9e`) are written around it.

> **This slice HAD a red run, and it was not a mutation.** The first draft of
> `ApiTokenAuthenticationService` was one `@Transactional` method that opened the company between
> its two reads, and **eight tests went red at once**: `CompanyAwareTransactionManager` sets the GUC
> in `doBegin`, so a transaction that begins with no context runs to completion without one and the
> policed `api_token` read returned zero rows. Every valid credential got a 401. That is the defect
> §114's header asks for a substitute for, arriving on its own — and it was observable **only**
> because the assertions are made over HTTP. A harness that opened a context before calling in would
> have been green. The rows below are the ordinary batched ones and stay **unrun**.

| # | Mutation | Expected | Result |
|---|---|---|---|
| AT-1 | `PermissionEvaluator.cappedToToken` returns `held` unchanged (the cap removed) | A CI token holds everything its owner does. Should redden `theRoleCapsTheOwner`, `theEffectiveSetIsTheIntersection`, `theOwnerCapsTheRole` and `theCompanyWideAnswerAdministersNothing`. **This is the slice's whole security claim in one method**, so a narrow blast radius here would mean the cap is being asserted by accident. | ⬜ NOT RUN |
| AT-2 | `cappedToToken`'s intersection becomes a **union** (`held` ∪ role grant) | The direction that looks like a typo and is an escalation: a Test Engineer's token carrying Company Admin becomes a Company Admin. Should redden `theOwnerCapsTheRole` **alone**, which is the only test whose owner is weaker than its role — and that is why that fixture is seeded directly rather than through the endpoint. | ⬜ NOT RUN |
| AT-3 | `entry.getValue().narrowedBy(bound)` → `entry.getValue()` (key intersected, qualifier not) | A token whose role qualifies a key `OWN` reaches rows its owner's `ALL` covers. Expected to redden **nothing** against the seeded catalogue, because Automation Service's keys are unqualified. **Recorded as a fixture gap, not a code gap**: closing it is one token carrying Test Engineer, whose `case:delete` is `OWN`. | ⬜ NOT RUN — the gap is the interesting half. |
| AT-4 | The project bound in `evaluate` is removed (a token that names a project reaches every one) | Should redden `theProjectBoundIsHard`. The bound is checked **before** `findScopedById` deliberately, so a mutation that merely moves it after the intersection is a second, subtler variant worth running. | ⬜ NOT RUN |
| AT-5 | `ApiToken.isUsable` drops the `revokedAt == null` arm | A revoked credential keeps working while the list shows it revoked. Should redden `revocationIsImmediate`. Note that test asserts the **lookup row still exists** first, so it cannot pass by the row having been deleted. | ⬜ NOT RUN |
| AT-6 | `isUsable` drops the `expiresAt` arm | Should redden `expiryIsEnforced`, whose fixture is seeded past-dated because the endpoint correctly refuses a past expiry — the state is reachable only by time passing. | ⬜ NOT RUN |
| AT-7 | `ApiToken.revoke` drops its `if (revokedAt == null)` guard | Re-revoking moves the timestamp, so the trail reports when somebody clicked twice rather than when the credential died. Should redden `revocationIsIdempotentAndTheFirstOneCounts` and **nothing else** — a status-only suite passes this mutant. | ⬜ NOT RUN |
| AT-8 | `ApiTokenService.issue` writes the plaintext into the audit `changes` instead of the prefix | Should redden `theAuditTrailNeverCarriesIt`. `AuditEvent.record`'s own contract forbids it and nothing enforces that but this test. | ⬜ NOT RUN |
| AT-9 | `V36`'s `api_token_name_idx` loses its `WHERE revoked_at IS NULL` | Should redden `aRevokedNameIsReusable` — as a **500**, not a 400, because the service's `existsByNameAndRevokedAtIsNull` check would still pass and the index would refuse the insert. Worth running for the shape of the failure as much as for the fact of it. | ⬜ NOT RUN |
| AT-10 | `SecurityConfiguration.bearerTokenResolver` returns `resolved` unconditionally | The resource server tries to parse `tcms_…` as a JWT and 401s before the token filter is reached — a valid credential failing in a way that looks exactly like an invalid one. Should redden every test in `ApiTokenAuthenticationTest.Resolving` except `anUnknownSecretIs401`, which passes for the wrong reason. | ⬜ NOT RUN |
| AT-11 | `PermissionVersionFilter`'s token branch drops the `!user.get().isActive()` check | Deactivating a person leaves every credential they issued working — the instance-wide off switch with a hole in it. Should redden `deactivatingTheOwnerKillsTheToken`. | ⬜ NOT RUN |
| AT-12 | `ApiTokenLookupRepository.findByTokenHash` is moved onto `api_token` (i.e. `V36` reverted) | **The defect this slice exists to prevent.** Expected to redden all of `Resolving` — and it is recorded because reverting it is a one-line temptation the day somebody asks why there are two tables. | ⬜ NOT RUN |

---

## §117 — CI result ingestion, and `automation_key_mapping`'s writer

`IngestionEndpointTest` (fixture prefix `d7`, 21 tests) and `ReportParserTest` (17 tests, no
database). Both were **green first time**, which §114's header names as the weakness — so this
slice ran mutations rather than queuing them, and the rows below record what was measured.

> **EIGHT MUTATIONS WERE RUN — a stated §65 deviation, for §115's reason.** The deferral is about
> *browser passes and manual mutation checks*, not about whether a class that went green on its
> first run has been shown capable of going red. Every mutation below reddened its intended
> assertion and only that one, except where noted.

| # | Mutation | Expected | Result |
|---|---|---|---|
| IN-1 | `ReportXml.parse` sets `disallow-doctype-decl` to `false` | An XXE payload reads a file off the application server as `tcms_app` — the production signing key, the database password, the SMTP credentials — through a feature working as designed. | ✅ **RUN — and the result corrected the code's own comment.** It reddened only `entityExpansionIsRefused`, **not** `externalEntitiesAreRefused`: the `external-*-entities` features and `ACCESS_EXTERNAL_DTD` refuse the file read on their own. So the DOCTYPE ban is what stands between this endpoint and a billion-laughs expansion, and the entity pair is what stands between it and disclosure. Neither is redundant; `ReportXml`'s header now says which is which. |
| IN-2 | `CucumberReportParser`'s severity ladder ranks `SKIPPED` above `FAILED` | A scenario that fails mid-way reads as skipped, because Cucumber marks every later step skipped. The all-green direction on the format most used for acceptance tests. | ✅ RUN — reddened `theWorstStepWins` alone. |
| IN-3 | `AutomationMatcher` drops ladder steps 2 and 3 (rung 1 only) | A ladder that silently collapsed to its first rung. | ✅ RUN — reddened `embeddedDisplayKey`, `learnedMapping` and `reconfirmingMoves`. **This is why the three rungs are exercised ALONE**: one report using all three would have kept its count and passed. |
| IN-4 | `TestRun.create` hard-codes `RunSource.MANUAL` again | Every ingested run reads as a person's. `test_run.source` has had one value written to it since M0 and nothing but this test reads the other. | ✅ RUN — reddened `theRunIsIngestedAndStarted` alone. |
| IN-5 | `IngestionService`'s idempotency lookup always returns empty | **Every** CI retry ingests a second time — a second run over which one build's results are split, each reporting a pass rate over a denominator nobody chose. Also the shape a lost transaction would produce, since the lookup is RLS-protected. | ✅ RUN — reddened `aRetryReplays` alone. |
| IN-6 | `worstPerCase` keeps the last match rather than the most severe | A parameterised test whose second run failed reports the case as passed. | ✅ RUN — reddened `severalTestsCollapseToTheWorst` alone. |
| IN-7 | The run is opened even when nothing matched | A run of zero cases reports 0 of 0, 100%, complete — §99's defect one screen over, on the *first* report a team posts. | ✅ RUN — reddened `nothingMatchedMeansNoRun` and `unmatchedIsNamedAndCreatesNothing`. |
| IN-8 | `AutomationMappingService.EDIT_CASE` becomes `result:submit` | **The security property of the whole feature.** A leaked CI token could bind every failing test's name to a throwaway case and launder a red suite into a green one, with an audit log recording nothing unusual. | ✅ RUN — reddened `theTokenCannotRepointResults` alone. |

**Still queued, not run** — these need state the automated fixture does not build:

| # | Mutation | Expected | Result |
|---|---|---|---|
| IN-9 | `IngestionEdit.MAX_REPORT_BYTES` removed | A 400 MB payload becomes an out-of-memory fault taking the instance down for every other company on it, rather than a refusal naming the cap. Needs a large body, so it is a manual check. | ⬜ NOT RUN |
| IN-10 | `AutomationMatcher.soleKey` returns the first key instead of refusing a name that yields two | A test named after two cases posts its result to whichever the regex found first. Needs a two-key fixture. | ⬜ NOT RUN |
| IN-11 | `CaseFacts.caseIdsByAutomationKey` drops its `project_id` predicate | A sibling project's case is materialised into this project's run — a `test_execution` and its `test_case` in two different projects, which every read in the module assumes cannot happen. **The sixth file layering a project predicate over RLS with nothing checking it** (`PlanFacts`, `CatalogueFacts`, `ExecutionSource`×3, `DocumentationSource`); the fix is still one two-project fixture written once for the seam, not a row per method. | ⬜ NOT RUN — same gap §115's CM-6 records. |
| IN-12 | `IngestionService.write` records a result on a case whose execution is absent from a named run | A CI job silently grows a checklist a person is working through, and its denominator. Needs a `runId` whose scope excludes a matched case. | ⬜ NOT RUN |

---

## §119 — the ingestion history reads

`IngestionEndpointTest.History` (7 tests, the same `d7` fixture, now with a **second project**).
**Nothing below was run** — §65 as restated: deferred means all of them, and §117's eight-mutation
deviation was that slice's, not a new rule. Two of these rows are the reason the slice's own
fixture grew a sibling project.

| # | Mutation | Expected | Result |
|---|---|---|---|
| IH-1 | `IngestionJobRepository.findOneInProject` drops its `projectId` predicate | A sibling project's job — its unmatched test names, its build's shape — is readable by somebody holding no grant on the project that owns it. RLS and the Hibernate filter both pass the row, so nothing else refuses it. Should redden `aSiblingProjectsJobIsUnreachable` alone. **This is IN-11's defect class with a fixture finally behind it**, on one of the seven files. | ⬜ NOT RUN |
| IH-2 | `IngestionReadService.list`/`findOne` gate on `writeGranted(…, "result:submit")` instead of `readVisible` | A Viewer can no longer read why a test produced no result — the history becomes an administrator's screen for no reason, and the binder's read gets a stricter gate than its write needs. Should redden `aViewerMayRead` alone. | ⬜ NOT RUN |
| IH-3 | `UnmatchedTests.landed` drops the `recorded` arm (matched ⇒ landed) | A test whose case is real but is **not in the run the caller named** disappears from the detail read while still counting in `unmatchedCount` — the two populations §117 deliberately unions come apart, and the screen shows fewer names than the number beside them. Should redden `theDetailAgreesWithThePost` on a fixture that names a `runId`; the current fixture does **not** build that state, so this is a manual check and IN-12's sibling. | ⬜ NOT RUN — needs IN-12's fixture |
| IH-4 | `IngestionService`'s replay serves `List.of()` again | `unmatchedCount: 12` beside `unmatched: []` on the response to a CI **retry** — the caller that most needs the names, on the second time one build failed to bind the same tests. Should redden `aReplayCarriesTheNames` alone. | ⬜ NOT RUN |
| IH-5 | `findSummariesInProject` selects the entity rather than the constructor projection | No assertion changes and no test goes red — the defect is a `JSONB` blob per row pulled across to render a list that shows none of it. **Recorded because it is invisible to the suite**: the check is the query log on a project with a 5,000-test report in its history, which is the shape of the thing this projection exists for. | ⬜ NOT RUN — not assertable, measure instead |
| IH-6 | The list's `ORDER BY` loses its `j.id DESC` tiebreak | Two jobs posted inside one microsecond — a matrix build does it every time — swap places between reloads, moving the row somebody is reading out from under the cursor. Not reliably reddenable: the fixture's two posts differ in `createdAt`, so this needs two rows written with an identical timestamp. | ⬜ NOT RUN |
| IH-7 | `TestRunRepository.findRunKeys` loses `deletedAt IS NULL` | The history offers a link to a deleted run, which 404s — and `IngestionJobSummary` loses the only thing that tells "matched nothing" apart from "its run was deleted". Needs a soft-deleted run in the fixture. | ⬜ NOT RUN |
| IH-8 | `UnmatchedTests.outcomeOf` falls back to `IngestedOutcome.values()[0]` on an unknown name | Every historical row of a removed enum constant re-reads as **`PASSED`** — the all-green direction §117 spent its parsers on, arriving through the reader instead. Needs a payload written by a future build, so it is a manual check. | ⬜ NOT RUN |

---

## §120 — automation drift

`CaseHealthRecomputeTest.AutomationDrift` (8 tests, the `ce…` fixture, now with two `INGESTED` runs
and seven cases crossing automation status against result age) plus `CaseHealthAttributesTest` (2,
**no database**).

> **THREE MUTATIONS WERE RUN — a stated §65 deviation, and the reason is §114's own.** Both new test
> classes went green first time, which is the state §114 recorded as its weakness and named a
> mutation row as the only route out of. Two backend mutations plus one frontend, run inline, listed
> below with their measured results. The rest are queued. **This is a deviation, not a new rule** —
> §117 said the same thing about its eight and §115 about its five, and the queue's opening
> paragraph still means what it says.

| # | Mutation | Expected | Result |
|---|---|---|---|
| DR-1 | `ExecutionFacts.recomputeAutomationDrift` drops `AND r.source = 'INGESTED'` from the lateral | Every result counts as a CI result, so a case CI abandoned but a person still runs by hand reads as healthy, and every manually-executed case in the project becomes `UNDECLARED_AUTOMATION`. The single likeliest wrong implementation, because the statement directly above it in the same file reads exactly this way. | ✅ **RUN — reddens 5**, including `aPersonRunningItByHandProvesNothingAboutTheAutomation`, `automationThatNeverStartedHasNotStopped`, `recentlyExecuted` and `theNewestResultWins`. More than the one test written for it, and the extra four are the right extra four: they are the fixture's manual cases becoming drift cases. |
| DR-2 | The stale arm becomes §6c read literally — `(last_ci.at IS NULL OR last_ci.at < :cutoff)` | A case marked `AUTOMATED` before its automation exists raises an **ERROR-severity** signal immediately, on every case in a project, the day somebody starts declaring intent. §6c makes `automationKey` optional precisely to allow that state. | ✅ **RUN — reddens exactly one**, `automationThatNeverStartedHasNotStopped`, alone. **The first attempt at this mutation was the wrong one and it is worth not repeating**: deleting `last_ci.at IS NOT NULL` outright reddens *all 19*, because a NULL boolean into a `NOT NULL` column aborts the recompute — a crash, which says nothing about the semantics. Mutate to the competing implementation, not to nothing. |
| DR-3 | The mirror arm narrows to `c.automation_status = 'MANUAL'` — §6c's own wording | A `NOT_AUTOMATABLE` case taking CI results goes unflagged, which is the loudest arm: coverage is denominated `AUTOMATED / (total − NOT_AUTOMATABLE)`, so this one is wrong in the **denominator** of the number the whole signal family exists to keep honest. Should redden `undeclaredIsWiderThanManual` alone. | ⬜ NOT RUN |
| DR-4 | The mirror arm drops `AND last_ci.at >= :cutoff` (unbounded, as the first draft of the design had it) | The signal can never go false. A case automated, discovered, and then legitimately returned to manual when the automation was deleted stays flagged for ever with nothing anybody can do — `HealthSignal`'s "no button for lying" rule arriving from the other side, as a claim with no route back. Should redden `undeclaredLapses` alone. | ⬜ NOT RUN |
| DR-5 | The `ON CONFLICT` names every signal column rather than three | This job becomes the author of claims it has no evidence for, and — new in this slice — it now blanks a **live** sibling's work rather than a hypothetical future one's: `reporting.ExecutionSource` writes `failing_repeatedly` into these same rows on its own schedule. Should redden `otherSignalsAreNotClobbered`. **Worth reading the failure rather than just the colour**: that test seeds `stale_approval`, which no job writes, so it does not exercise the two-live-writers race it now describes. | ⬜ NOT RUN |
| DR-6 | `HealthSignal.UNDECLARED_AUTOMATION` is declared **before** `ORPHANED` instead of appended | `CaseSearch.tallyHealth` selects one aggregate per constant and reads the results back **by position**, so every facet count after the insertion point lands on the wrong tile — the dashboard reports the right numbers against the wrong names. **This row may redden nothing, and that would be the finding**: the declaration-order rule is stated in a comment and, as far as this slice could tell, held by no test. | ⬜ NOT RUN |
| DR-7 | `HealthSignal.UNDECLARED_AUTOMATION`'s attribute is misspelled `"undeclaredAutomatio"` | Every Criteria filter on the signal throws `Unable to locate Attribute` at runtime while the chip, the tile and `CaseHealth.signals()` all keep rendering — one broken filter among ten working ones. Should redden `CaseHealthAttributesTest.everyAttributeResolves` alone, which is the class §47 said existed and did not until this slice. | ⬜ NOT RUN |
| DR-8 | `CaseHealthRecomputeService` runs the two statements in **two** transactions | Half a tick's signals stamped with this tick's `computed_at` if the second fails — a row claiming to have been derived just now, half of which was not. **Not assertable by a test** without a fault injected into the second statement; recorded because the single transaction looks like an accident of style and is not. | ⬜ NOT RUN — not assertable |
| DR-9 | `CaseHealthProperties`' positive-duration check for `staleAutomation` removed, with `P0D` configured | The cutoff lands at `now`, so every case with any ingested result becomes stale and every case without one reads clean — **the pair inverts** rather than merely over-firing, and the dashboard shows a project whose automation all died at once while the unautomated cases look healthy. **No test exists for either half of this check**, `longUnexecuted`'s included; the row exists to say so. | ⬜ NOT RUN — no test to redden, which is the finding |
| DR-10 | *(frontend)* `UNDECLARED_AUTOMATION` is added to `SEVERE_SIGNALS` in `StatusChip.tsx` | The pair renders identically and the only distinction a reader acts on is gone. This is the *tidy* change — the two signals are mirrors and look like they should match — which is precisely why it needs a test rather than a comment. | ✅ **RUN — reddens exactly one**, `renders undeclared automation as a warning, NOT as severe`. |

---

## §121 — flake detection and quarantine (M3 slice 6, phases 1–2)

`FlakeScoreTest` (15, four nested classes, the `fc…` fixture with **ten runs, two configurations
and two case versions** — the smallest shape that can tell §6l's three false signals apart) and
`CaseQuarantineEndpointTest` (18, the `aa…` fixture, five personas including Automation Service).

> **THREE MUTATIONS WERE RUN — a stated §65 deviation, and the same one §115, §117 and §120 each
> recorded.** Both classes went green first time. Two of the three are the decisions the slice plan
> named as load-bearing (D3, D4) and the third is the security property the whole gate choice rests
> on. **This is a deviation, not a new rule.**

| # | Mutation | Expected | Result |
|---|---|---|---|
| FQ-1 | `ExecutionSource.recomputeFlakeScores` drops `AND ef.status_kind IN ('PASSED','FAILED')`, keeping every row in the sequence | The implementation anybody writes first. A blocked run between two passes becomes **two** alternations, so `INTERRUPTED` — a test that has passed every time it was actually run — scores `0.8889`. R7's *"a pass and a BLOCK is not an inconsistency"* rule applied to a sequence rather than a set. | ✅ **RUN — reddens 3**, `aBlockedRunBetweenTwoPassesIsNotAnAlternation` plus the two that count its executions. |
| FQ-2 | The `PARTITION BY` drops `ef.case_version_id` | §6l names this failure in its own sentence: *"alternation across versions is just change, and reporting it as flake is a false signal."* `EDITED` passed five times, was edited, and has failed five times since — a test that caught a regression — and would be reported as the flakiest thing in the project, sending somebody to quarantine the test that found the bug. | ✅ **RUN — reddens exactly one**, `alternationAcrossVersionsIsJustChange`, alone. |
| FQ-3 | `CaseQuarantineService.EDIT_CASE` becomes `"result:submit"` | **The security row.** A leaked CI token could then post results *and* quarantine the tests those results fail — turning a red suite green with nobody involved, which is the exact breach Automation Service is shaped to prevent. It is a one-word change and it is the key an endpoint about CI results looks like it should use. | ✅ **RUN — reddens exactly one**, `automationServiceCannotQuarantineItsOwnRedTest`, alone. Every other test in the class survives it. |
| FQ-4 | The `PARTITION BY` drops `ef.configuration_id` | The other half of §6l's sentence, with a different harm: *"fails only on Safari"* is a real, reproducible, fixable defect and quarantining the test is precisely the wrong response. Should redden `alternationAcrossConfigurationsIsAPlatformBug` alone. | ⬜ NOT RUN |
| FQ-5 | The pass predicate becomes `ef.counts_as_pass` (and the filter `counts_as_pass OR status_kind = 'FAILED'`) — **the implementation this slice originally shipped and corrected** | `kind` and `counts_as_pass` are independent columns, so this agrees with `execution.CaseHistoryService.flakiness` on all six seeded defaults and diverges the moment a project defines "Passed with known issues" as `RETEST`-kind counting toward the pass rate. The case page and the worklist would then quote different numbers for the same test. Should redden `thePredicateIsTheKindAndNotThePassRateColumn` alone — the only test in the suite that seeds such a status. | ⬜ NOT RUN |
| FQ-6 | `flake_score` becomes `0` rather than null below the floor | The confident zero, in the place it costs most: `0.0` reads as *evidently stable* on a test whose only three results went pass, fail, pass. It is the number a human then uses to decide whether to quarantine. Should redden `belowTheFloorThereIsNoScore`. | ⬜ NOT RUN |
| FQ-7 | The denominator becomes `executions` rather than `transitions` | A case with six results across six cells scores `0.0` on evidence that says nothing about stability at all — and looks exactly like the cases that really are stable. Should redden `alternationAcrossConfigurationsIsAPlatformBug`'s counts and `alternationIsTheScore`. | ⬜ NOT RUN |
| FQ-8 | The recompute's `ON CONFLICT` set-list becomes `EXCLUDED.*` | **Every quarantined test is released on the next tick** — silently, in the reassuring direction, presenting as a board whose quarantine column empties itself overnight with no action recorded by anyone. The first time the own-your-own-columns rule binds two writers of one *table* rather than of one row across modules. Should redden `theAuthoredHalfIsUntouchable`. | ⬜ NOT RUN |
| FQ-9 | `ef.executed_at >= :cutoff` removed (unbounded window) | The score can never go false: a test that flapped badly last spring and has been solid since stays flagged for ever. §120's rule for `UNDECLARED_AUTOMATION`, arriving from the other direction. Should redden `theWindowIsBounded`. | ⬜ NOT RUN |
| FQ-10 | `ef.execution_id` removed from the `ORDER BY` inside the window | Two results recorded in the same microsecond — what a CI run posting a batch produces — resolve differently per tick, so the score flickers between two values with no input moving. **This row may redden nothing**: the fixture spaces results a day apart, so `isIdempotent` cannot observe it without a same-instant pair. That would be the finding. | ⬜ NOT RUN — may redden nothing |
| FQ-11 | `CaseQuarantineRequest.quarantined` becomes a primitive `boolean` | An absent field arrives as `false`, so a client sending only a `reason` **releases a quarantine it never mentioned** and gets a 200. The one shape of this request that destroys a decision by omission. Should redden `theStateFieldDoesNotDefault`. | ⬜ NOT RUN |
| FQ-12 | `CaseFlake.release()` clears the flag and leaves `quarantineReason` | A row saying "not quarantined" beside "quarantined by L. Lead — flaky on Firefox", read in whichever order the screen puts them. The reason field is exactly the one a card renders without checking the flag first. Should redden `aReleaseLeavesNoResidue`. | ⬜ NOT RUN |
| FQ-13 | `case_flake_quarantine_complete` dropped from `V38` | The reason becomes optional at the storage layer, so any future caller — a bulk action, a board drag — can quarantine without one. **Not assertable by the current suite**, whose only writer already sends all four; recorded because the constraint is what stops the one-argument toggle from being addable, and because it is the check that caught this slice's own fixture writing a null `quarantined_by`. | ⬜ NOT RUN — not assertable without a second writer |
| FQ-14 | `CaseQuarantineService` looks the flake row up with `findById` rather than `findScopedById` | The Hibernate company filter does not apply to the primary-key path (`TestCaseRepository.findLiveInProject`'s javadoc). RLS still covers it, so **this may redden nothing** — which is the point of the row: it removes one of two layers, and a suite that cannot see the difference is exactly why the rule is written down rather than tested. | ⬜ NOT RUN — may redden nothing, which is the finding |

### §121 phase 3 — the board's fourth generator

`BoardEndpointTest.Quarantine` (5, inside the existing `b0…` fixture, which grew two cases and five
`case_flake` rows).

> **NO MUTATIONS WERE RUN.** The class went green on the second run — the first failed on a
> **fixture-id collision**, not on the implementation, and that is recorded as `FQ-19` below because
> it is the only finding of the phase that a mutation could not have produced.

| # | Mutation | Expected | Result |
|---|---|---|---|
| FQ-15 | `BoardFacts.quarantinedCases` selects on `f.flake_score >= 0.5` rather than on `f.quarantined` | The version §121 argued against, arriving as a query rather than as a schema: the card then **vanishes the moment the test stops flaking**, taking the debt off the board while the test is still excluded from the gate. Should redden `onlyLiveQuarantinesAreCards` on `TC-13` alone — the one fixture row with a high score and no decision. | ⬜ NOT RUN |
| FQ-16 | `PREFIX_QUARANTINE` becomes `PREFIX_CASE` — the natural "it is a case card" simplification | A case that is both flagged and quarantined produces **two cards with one id**. The API returns both, the board renders both, and the SPA's list key collides silently. Nothing throws on either side. Should redden `twoSourcesAreTwoCards`. | ⬜ NOT RUN |
| FQ-17 | The card's assignee becomes `flake.quarantinedBy()` instead of `flake.ownerId()` | Quarantining somebody else's flaky test moves **their** debt onto **your** personal board, and off the board of the person who can fix it. Both boards still render perfectly. Should redden `theDebtBelongsToTheOwner` in both directions. | ⬜ NOT RUN |
| FQ-18 | The `test_case` join drops `AND c.deleted_at IS NULL` | A soft-deleted case keeps its flake row (only a hard delete cascades, `V38`), so the board grows a permanent card for a test nobody is running and nobody can release from a screen. `assignedExecutions` and `pendingReviews` both carry this predicate; this is the third place it matters. Should redden `onlyLiveQuarantinesAreCards` on `TC-8`. | ⬜ NOT RUN |
| FQ-19 | *(not a mutation — the finding itself)* The fixture's two new case ids were `…000c`/`…000d`, and `…000c` was already `TEAM_ELSEWHERE`, declared 80 lines away | **Sixth instance of the family the handoff has tracked since §19.** Every case helper is `ON CONFLICT (id) DO NOTHING`, so the collision silently dropped the *later* insert and `TEAM_ELSEWHERE`'s execution pointed at this slice's case — wrong project, wrong owner. The only red test was §84's team-board bound, which is nothing to do with either row: **a collision fails somewhere else.** | ✅ **FOUND AND FIXED** — ids moved to `…000e`/`…000f` |
| FQ-20 | `cardFor(QuarantinedCase…)` returns a non-empty `allowedTransitions` | A quarantined card becomes draggable and the drop is refused by the id prefix instead — 409 after the gesture rather than no gesture at all (`ui-rules`, §106-6). Releasing a test needs `case:edit` **and a reason**, which a drag has nowhere to collect. Should redden `derivedCardsCannotBeMoved`, which iterates every derived card rather than naming kinds. | ⬜ NOT RUN |

### §121 phase 4 — the read path, and the actor field that was an id

`CaseDetailEndpointTest.Flake` (6, in the existing `d0…` fixture, which grew three `case_flake`
rows and deliberately leaves a fourth case without one) plus one changed assertion in
`CaseQuarantineEndpointTest.leadCanQuarantine`.

> **NO MUTATIONS WERE RUN**, and unlike phases 1–3 the class did **not** go green first time. Two
> real defects fell out of writing it and both are recorded below as findings rather than as
> mutations (`FQ-25`, `FQ-26`) — one of them a 500 on the ordinary path.

| # | Mutation | Expected | Result |
|---|---|---|---|
| FQ-21 | `CaseRenderer.flakesFor` returns `Map.of()` for a case with no row, and `TestCaseDetailView.of` receives `CaseFlakeView.of(CaseFlake.unscored(...))` instead of null | The **never-scored** state collapses into the **scored-zero** state at the seam furthest from the screen. Every panel then renders "Stable, 0 switches across 0 repeat runs" for a case nothing has ever run — the most reassuring possible sentence about the least evidence. Should redden `noRowIsAnExplicitNull`, and nothing else: every other assertion is about rows that exist. | ⬜ NOT RUN |
| FQ-22 | `CaseFlakeView.of` passes `flake.getQuarantinedBy()` straight through as the id, with `quarantinedBy` back to `UUID` | The state this field shipped in for three phases. The response still validates, the panel still renders a line, and the line says `by aa003000-0000-4000-8000-000000000001`. Should redden `theDeciderIsNamed` and `leadCanQuarantine`'s `displayName` assertion — **two classes**, which is the point: the endpoint and the detail read resolve it through different call paths and both had to be closed. | ⬜ NOT RUN |
| FQ-23 | `CaseQuarantineService.render` is deleted and the controller resolves the decider from `Outcome.Done(CaseFlake)` after the transaction closes | `CaseOwners` reads `app_user` through the tenancy filter, so a resolution outside the transaction has no `app.current_company` and **fail-closes to nobody**. Every successful quarantine then answers `quarantinedBy: null`, which the schema permits and the panel renders as "somebody no longer in this company". Should redden `leadCanQuarantine`. The failure direction is the dangerous one: fail-closed here means *silently wrong*, not *loud*. | ⬜ NOT RUN |
| FQ-24 | `CaseBulkService.loadContext` passes `Map.of()` for `flakes` regardless of `authored` | A bulk edit's **version snapshot** records `flake: null` for a case that has one, so the recorded history of a case says a measurement did not exist at a moment it did. Invisible to every client — the bulk response is `BulkResultView`, not a case — so **no test in the suite should redden**, and that is the finding: the snapshot has no assertion behind it. A row that may redden nothing, like `FQ-10` and `FQ-14`. | ⬜ NOT RUN |
| FQ-25 | *(not a mutation — the finding itself)* `flakesFor` short-circuited to `Map.of()` when no row in the batch named a decider, then did `named.get(flake.getQuarantinedBy())` on a **nullable** column | **`Map.of().get(null)` throws NPE by contract** — §38's trap, and `CaseOwners` carries a comment about it that this code copied the *words* of and not the behaviour. Every case detail 500'd whenever the batch contained no quarantined case, which is the overwhelmingly common path; the batch that *did* contain one worked, so it reads as correct. Five tests went red, only two of them the new ones — the other three were `Conditions`, `Automation` and `Steps`, which have nothing to do with flake. **A short-circuit on an empty map is not an optimisation when the lookup key is nullable.** | ✅ **FOUND AND FIXED** — `new HashMap<>()` on both arms |
| FQ-26 | *(not a mutation — the finding itself)* The `case_flake` fixture insert had no `ON CONFLICT`, and `seed()` is `@BeforeEach` | 44 of 63 tests failed on `duplicate key value violates unique constraint "case_flake_pkey"`. Loud, immediate and in the fixture rather than the product — recorded because the **shape** matches the handoff's standing trap: a catastrophic failure count is a signal to read the harness before reading an assertion, and this is the second consecutive phase of this slice where the first red run was a fixture and not the code. | ✅ **FOUND AND FIXED** — `ON CONFLICT (case_id) DO NOTHING`, matching every sibling helper |

---

## §122 — grant-instance-admin (M3 slice 7)

`GrantInstanceAdminServiceTest` (7, four nested classes, the `gia-` slug namespace). The whole
suite is a **service-level** suite: this command has no HTTP surface by design, so there is no
endpoint test and no persona matrix — the only caller is a shell on the box.

> **NO MUTATIONS WERE RUN.** §65 is the standing policy and the deviations §115, §117, §120 and
> §121 each recorded were exceptions, not the rule. The suite went green first time.

| # | Mutation | Expected | Result |
|---|---|---|---|
| GA-1 | `grant-instance-admin` is removed from `OperatorCommands`' verb set, leaving the `ApplicationRunner` in place | **The documented "hangs after succeeding" defect.** `TcmsApplication.main` reads the verb set *before the context exists* to pick `WebApplicationType.NONE`; an unregistered verb boots a web server, so the command does its work, prints its success, and then never exits — an operator's terminal blocks and CI hangs on a step that already succeeded. Should redden whatever pins the verb set. **This row may redden nothing**, and that would be the finding: the registration is one line in a second file and the failure is invisible to a service-level test. | ⬜ NOT RUN — may redden nothing, which is the finding |
| GA-2 | `USER_DEACTIVATED` is dropped and a deactivated account is granted like any other | The grant is inert today — `PermissionEvaluator.systemTermIfAccountActive` resolves SYSTEM permissions only for an `ACTIVE` account — and becomes the widest power in the product the moment anybody reactivates the user, with no second look and no record that the two acts were related. An operator who typos a departed colleague's address arms it. Should redden `refusesADeactivatedAccount` alone. | ⬜ NOT RUN |
| GA-3 | `INVITED` is refused alongside `DEACTIVATED` — the tidy "only ACTIVE accounts" reading | **The command becomes unable to create the first operator.** `provision-company` leaves its first administrator `INVITED`, so the only sequence that bootstraps a fresh instance is provision → grant → redeem. Refusing the middle step leaves a hand-written `INSERT` as the only route to the tier, which is exactly what this slice exists to remove. Should redden `grantsToAnInvitedAccount`. | ⬜ NOT RUN |
| GA-4 | `ALREADY_HELD` yields `EXIT_FAILED` — `issue-invitation`'s exit code for its own no-op | A re-run reports failure for a state that is correct. An operator repairing an instance re-runs the provisioning sequence and has to read exit codes to learn which steps were legitimate no-ops, which is the property `provision-company`'s idempotence exists to give them. The divergence is deliberate: a grant is a **state**, an invitation is an **artifact**. Should redden `isIdempotent`'s exit assertion. | ⬜ NOT RUN |
| GA-5 | The `alreadyHeld` check is removed, relying on `uq_instance_role_assignment (user_id, role_id)` | The constraint still stops the second row, so nothing is corrupted — the operator gets a `DataIntegrityViolationException` stack trace instead of a sentence. **The check does not exist for correctness**; it exists to turn a constraint into English, which is why deleting it looks harmless in review. Should redden `isIdempotent`. | ⬜ NOT RUN |
| GA-6 | `grant` runs without the surrounding `TransactionTemplate` — read and write in separate transactions | Two operators running the command concurrently both read "not held" and both insert; one gets the constraint violation as an unhandled exception rather than `ALREADY_HELD`. **Not assertable by the current suite**, which is single-threaded; recorded because the transaction looks like ceremony around two repository calls and is not. | ⬜ NOT RUN — not assertable without a concurrent caller |
| GA-7 | The service opens a `CompanyContext` around the grant — e.g. the granting operator's own company | The grant is written correctly and reads as **belonging to one client**. `instance_role_assignment` carries no `company_id` (ERD §0.1 exception list) so nothing fails, and the harm is entirely conceptual until somebody reasons from the surrounding code that an instance-wide power is company-scoped. The tier the model does not have. Should redden `reachesAnotherCompanyEntirely`. | ⬜ NOT RUN |
| GA-8 | `actorUserId` becomes required, rejecting `null` | There is genuinely nobody to attribute the **first** Instance Admin to — which is why `instance_role_assignment.granted_by` is nullable in `V1`. A required actor makes the bootstrap case unrepresentable and invites an operator to pass the grantee's own id, producing a row that says a person promoted themselves. Should redden `grantsToAProvisionedAdmin`, which passes `null`. | ⬜ NOT RUN |
| GA-9 | The role written is any id other than `Role.INSTANCE_ADMIN_ID` | The command reports success and grants a different tier — or, with an unseeded id, an FK violation. The loudest possible defect and the cheapest to write, since the id is a constant reference in a four-argument factory call. Should redden `grantsToAProvisionedAdmin` and `reachesAnotherCompanyEntirely`. | ⬜ NOT RUN |
| GA-10 | The `log.info` on a successful grant is removed or dropped to `DEBUG` | An instance's log can no longer answer *"when did this account become an operator"* without a database read — for the widest grant the product has, one the roles spec calls a disclosed and audited capability. **No test asserts on logging**; the row exists to say so. | ⬜ NOT RUN — no test to redden, which is the finding |

---

## §124 — the BEST removal and the two unwired gates (M3 slice 8)

> **`AG-*` IS A COLLIDING PREFIX — quote these as `§124 AG-n`, never bare.** The other owner is §133 — the `Agent` role; see the ID COLLISION banner above for the rule (qualify, never renumber — decided 2026-08-11).

Three phases, three files' worth of assertions: `PlanStatisticsEndpointTest` (the policy set),
`ExecutionEndpointTest.Deletion`'s sibling nested class for `run:execute`, and
`ArtifactEndpointTest.Deletion` (10 tests, the `af…` namespace, `af009000-…` for the seeded
sibling-project row).

> **THREE MUTATIONS WERE RUN and are stated as a deviation from §65** — one per phase, each the
> substitution a reviewer would most plausibly wave through, because all three phases turn on a
> *constant* rather than on logic and a constant swap is invisible in review. The rest are queued.

| # | Mutation | Expected | Result |
|---|---|---|---|
| §124 AG-1 | `AggregationPolicy.BEST` is restored to **one** of the two enums (either one) | `AggregateExecutionPolicyTest` pins the two name sets equal, so the copies cannot drift. This is the check that made the removal landable at all, and it matters more for a deletion than an addition: a missing value fails closed and loudly, while a copy that still *accepts* a retired policy answers a number the plan page can no longer produce. | ⬜ NOT RUN |
| §124 AG-2 | `PlanController` falls back to `DEFAULT` for an unparseable policy instead of 400ing | A bookmark, a saved URL or a screenshot's query string carrying `BEST` answers a **different question under the same name**, silently and in the flattering direction — which is the exact harm the removal exists to prevent, reintroduced by the error path. Should redden `bestIsRefused` and `anUnknownPolicyIsRefused`. | ⬜ NOT RUN |
| §124 AG-3 | `WORST_FIRST`'s severity order is reversed, restoring `BEST`'s behaviour under `WORST`'s name | The dropdown says "worst result per case" and computes the best. **Worse than shipping `BEST`**, which at least said what it did. Should redden `worst`, `noPolicyClearsTheBlockerList` and `thePoliciesActuallyDiffer`. | ⬜ NOT RUN |
| §124 AG-4 | `criteriaFollowTheDisplayedCounts`' tolerance is tightened back to `within(0.01)` | *(Not a mutation — the finding.)* `actual` is rounded to one decimal for display, so three of thirteen is served as `23.1` against an unrounded `23.0769…`. **This was the phase's only red run**, and the honest fix was the tolerance and not the endpoint: a test asserting the unrounded value asserts against a number no screen shows. | ✅ **FOUND AND FIXED** — `within(0.05)`, with the rounding named in a comment |
| RX-1 | `projects.holds(userId, projectId, EXECUTE_RUN)` is deleted from `ExecutionService.record` | **RUN.** A leaked CI token can hand-record verdicts through the endpoint a tester uses — with an `If-Match`, with evidence, indistinguishable in the audit trail from a person. | ✅ **RUN — reddened exactly the three new tests** (`theRobotCannotExecuteByHand`, `theTwoRefusalsGiveOppositeAdvice`, `theRefusalsNameNoKey`) and nothing else |
| RX-2 | The `run:execute` check moves **in front of** the verdict gate | The draft this slice started from. A Viewer, who holds neither key, is told to post to the ingestion endpoint they may not use either — advice that is confidently wrong, which is worse than a flat refusal. Should redden `theTwoRefusalsGiveOppositeAdvice` and `viewerIsRefused`. | ⬜ NOT RUN |
| RX-3 | `IngestionService`'s gate is changed from `result:submit` to `run:execute` | The mirror image, and the one that breaks the product rather than opening it: Automation Service is the one role without `run:execute`, so **every CI credential 401s on the endpoint built for it** and ingestion has no caller at all. Should redden most of `IngestionEndpointTest`. | ⬜ NOT RUN |
| RX-4 | `Outcome.NotExecuting` is mapped to 404 rather than 403 | A CI job that is posting to the wrong endpoint is told the run does not exist, and goes looking for a run that is right there. The 404-not-403 rule is about concealing existence from callers who cannot *read*; this caller has already passed the read gate. Should redden the status assertion in `theRobotCannotExecuteByHand`. | ⬜ NOT RUN |
| AD-1 | `ArtifactWriteService.delete` gates on `PERMISSION` (`artifact:edit`) instead of `DELETE_PERMISSION` | **RUN.** Every Test Engineer can remove a lead's sign-off record from the register — in the one module where the artefact being destroyed *is* the audit trail. Compiles, reads naturally, and is one word. | ✅ **RUN — reddened `theEngineerCannotDeleteWhatTheyMayCreate` alone**, which is the discrimination the key exists for |
| AD-2 | `artifacts.findInProject(...)` becomes `artifacts.findById(...)` | Everything else lines up for the delete to succeed: the row is in the caller's own company so RLS admits it, the caller holds `artifact:delete`, and the project named in the path is one they are fully granted on. **Only the predicate stops it.** Should redden `anotherProjectsArtifactIsNotReachable`. | ⬜ NOT RUN |
| AD-3 | The `write_once` refusal is deleted | A sign-off record is deletable, and the column's `V1` comment becomes a comment about nothing. Nothing in the product sets the column yet, so **this cannot be reached by any user today** — the guard is written ahead of its writer on purpose, for `Company.suspend()`'s reason. Should redden `aSignOffRecordCannotBeDeleted`, which reaches it by SQL. | ⬜ NOT RUN |
| AD-4 | The `isSupersedingSomething` refusal is deleted | A foreign-key violation surfaces as a **500** on a request the caller cannot fix. This is the edge `Artifact`'s javadoc said did not exist. Should redden `theReplacementCannotBeDeleted` — and note its second half must keep passing: the *other* end of the same edge is still deletable, so a mutation that refuses both directions is also wrong. | ⬜ NOT RUN |
| AD-5 | `auditRemoval` is moved to *after* `artifacts.delete(artifact)` | The audit row is written from a detached entity, or from fields read after the delete — and in the rollback case the trail records a removal that did not happen. Should redden `theRemovalIsAudited` if it throws; **may redden nothing** if Hibernate's flush order happens to save it, which is the finding. | ⬜ NOT RUN — may redden nothing |
| AD-6 | `auditRemoval` carries only the artifact id, dropping title/kind/url | The one case where an audit event **must** carry content rather than a pointer: the row it names is about to stop existing, so *"who took the compliance link out of the register"* becomes unanswerable. Should redden `theRemovalIsAudited`. | ⬜ NOT RUN |
| AD-7 | The delete soft-deletes (sets `ARCHIVED`) instead of removing the row | Reads as more careful and quietly changes what the endpoint means: `ARCHIVED` is §6n's *"no longer relevant, kept for the record"*, so a mis-registered link stays in the register asserting something untrue, and the register grows a second lifecycle nobody asked for. There is no `deleted_at` on `artifact`, which is the schema's own answer. Should redden `theLeadDeletes`' `countArtifacts` assertion. | ⬜ NOT RUN |

---

## §125 — `storage` becomes a module (M3 slice 9)

Two phases, no migration and no contract change. The assertions live in
`ArchitectureRulesTest`, `ModuleStructureTest` and the new `FilesystemBlobStoreTest`, plus the
existing `ExecutionEndpointTest.EvidenceAttachment` and `ReportJobPipelineTest`, which exercise the
moved types end to end without naming them.

> **NONE RUN**, per §65. Rows ST-1…ST-6 are the ones worth running first: they are the structural
> rules, and a structural rule that cannot fail is indistinguishable from one that passes.

| # | Mutation | Expected | Result |
|---|---|---|---|
| ST-1 | `storage/package-info.java` declares `allowedDependencies = {"tenancy", "identity"}` and a class in `storage` imports something from `identity` | **The whole reason the new ArchUnit rule exists.** Modulith is satisfied — the declaration is honoured and `identity` is itself a foundation module, so `declarationsNameOnlyTheFoundationModules` passes too. Should redden `storageDependsOnTenancyAlone` **alone**, and if it reddens nothing the fourth foundation module is unguarded. | ⬜ NOT RUN |
| ST-2 | `storage` is added to `FOUNDATION_MODULES` but the ArchUnit rule is deleted | The state the repo would have been in if the move had been taken at face value. Nothing fails, and the property that makes a non-domain foundation module safe becomes a paragraph. Should redden nothing — **that is the finding**, and the row exists to record that ST-1 is the only observer. | ⬜ NOT RUN — expected to redden nothing |
| ST-3 | `ModuleStructureTest`'s expected list keeps ten entries, dropping `"storage"` | Confirms the list is a *deliberate edit* rather than decoration — a new module must fail the build until somebody writes it down and reads the `allowedDependencies` rule they are now subject to. Should redden `detectedModulesMatchTheSourceTree`. | ⬜ NOT RUN |
| ST-4 | `execution/package-info.java` drops `"storage"` from `allowedDependencies` | Should redden `modulesVerify` with the offending edge named. The check that the declaration is a *fact about code that exists*, not a forecast. | ⬜ NOT RUN |
| ST-5 | `com.tcms.storage` gets a class importing `com.tcms.execution.TestExecution` | The cycle that would make the isolation-adjacent module jointly deployable with a feature module. Should redden `storageDependsOnTenancyAlone` and `modulesVerify`. | ⬜ NOT RUN |
| ST-6 | `Attachment.forStepResult` writes `OWNER_EXECUTION` | Every step-level screenshot is filed against the execution instead of the step result, so a future sweep by owner finds it in the wrong place and the step panel shows nothing. **The one thing in the moved file that is behaviour rather than structure**, and the reason the owner vocabulary being one file matters when #49 adds three more. Should redden `ExecutionEndpointTest.EvidenceAttachment`'s step-scoped assertions. | ⬜ NOT RUN |
| ST-7 | `FilesystemReportStore` is constructed with `"${tcms.storage.evidence.root}"` | **The failure that keeps the two ports two types**, arriving through the other door. Reports are written into the evidence root: nothing throws, nothing 404s while the box is untouched, and the two directories' different backup and retention treatment silently stops meaning anything. Should redden `Adapters.reportAdapterRoundTripsAndRefusesTraversal`'s message assertion — and note it would NOT redden a round-trip-only test, which is why that test asserts the kind. | ⬜ NOT RUN |
| ST-8 | `FilesystemBlobStore.resolve`'s `startsWith(root)` check is deleted | A key climbing out of the root writes anywhere the process can write. Unreachable through the API today — keys are server-generated UUIDs — which is exactly why the guard is tested directly. Should redden `refusesTraversal` **and both adapter tests**, which is the property phase 2 bought: before it, this mutation applied to the report copy reddened nothing at all. | ⬜ NOT RUN |
| ST-9 | The `kind` label is dropped and every message reads `"Could not store bytes under …"` | An operator cannot tell which root is full or gone from the log line, and the two are configured separately precisely because they have different loss consequences. Should redden `theMessageNamesTheKind`. | ⬜ NOT RUN |
| ST-10 | `FilesystemBlobStore.delete` uses `Files.delete` instead of `deleteIfExists` | Evidence deletion runs after the rows are gone, so a retry of a half-completed delete answers 500 for bytes that are absent *because the first attempt succeeded*. Should redden `missingKeyIsAbsent`. | ⬜ NOT RUN |
| ST-11 | `FilesystemBlobStore.open` reads the size **after** opening the stream | A `Content-Length` that can disagree with the body if the file changes between the two calls, which a browser reports as a corrupt download rather than as a server error. **Not assertable by the current suite** — there is no interleaving to arrange — and recorded because the ordering reads as arbitrary. | ⬜ NOT RUN — not assertable single-threaded |
| ST-12 | `EvidenceStore` and `ReportStore` are collapsed into one `BlobStore` with two qualified beans | The design phase 2 explicitly refused. Everything compiles and every test passes **until an injection point omits the qualifier**, at which point Spring fails loudly at startup — or, worse, a `@Primary` is added to settle it and one caller silently gets the other's root. Should redden nothing, which is the argument: the cost is not observable by tests, only by the failure it eventually produces. | ⬜ NOT RUN — expected to redden nothing |
| ST-13 | `Attachment`'s getters go back to package-private | Compile failure in `execution.EvidenceViews` and `EvidenceService`. Recorded not as a defect but as the **measurement of the move's true cost**: five getters and two factories widened, and nothing else in 2028 tests needed to change. | ⬜ NOT RUN — compile-time |

---

## §126 phase 1 — attachment owners: `CASE` gets a writer (M3 slice 10)

`V39` narrows `attachment.owner_type` to five values (#50), `storage` gains `AttachmentOwner`,
`BlobUpload` and three factories, and `repository` gains the case-attachment family (#49, first of
three owners). Assertions live in the new `CaseAttachmentEndpointTest` plus the existing
`CaseDetailEndpointTest` and `ExecutionEndpointTest.EvidenceAttachment`, which exercise the
refactored upload vocabulary end to end without naming it.

> **NONE RUN**, per §65. `CA-1` is the row worth running first: it is the one whose mutation reads
> as a security improvement, which is the kind that survives a review and ships.

| # | Mutation | Expected | Result |
|---|---|---|---|
| CA-1 | `CaseAttachmentService.delete` uses `writeGrantedByAuthorship` with `evidence:delete`'s `OWN` arm | **The mutation this slice is most likely to receive as a "fix".** It reads as obviously safer and is incoherent: `case:edit` already lets a Test Engineer rewrite every step of a lead's case, so refusing them the screenshot makes the file stricter than the step referencing it. Should redden `Gates.anEngineerRemovesSomebodyElsesFile`, and **only** that test — if it reddens nothing, the decision is a comment. | ⬜ NOT RUN |
| CA-2 | `attach` is gated on `case:read` instead of `case:edit` | Any Viewer — often the client themselves — can put files on a case definition. Should redden `Gates.viewerCannotAttach` **and** `automationServiceCannotAttach`; if only the first reddens, the CI-credential property is untested. | ⬜ NOT RUN |
| CA-3 | The download is gated on `case:edit` | The invented-permission failure, arriving as over-caution: a Viewer sees a paperclip they can never open, and no administrator can grant `attachment:read` because it does not exist. Should redden `Gates.viewerCanDownload`. | ⬜ NOT RUN |
| CA-4 | `AttachmentRepository.findOwnedById` drops the `ownerId` half of its predicate | `/cases/{a}/attachments/{belongs-to-b}` serves and deletes another case's file with a valid grant on both. Should redden `Scoping.attachmentIsScopedToItsOwner` — the row that exists because the id is real, the case is real, and the pair is still wrong. | ⬜ NOT RUN |
| CA-5 | `write` skips `cases.findLiveInProject` and trusts the project gate | `/projects/{granted}/cases/{ungranted}` attaches to a case the caller was never given. Should redden `Scoping.caseMustBeInThatProject`. | ⬜ NOT RUN |
| CA-6 | `remove` deletes the bytes **before** flushing the row delete | The inverted ordering. A failure between them leaves a row whose download 404s — recoverable — replaced by a thumbnail that renders and never opens. **Should redden nothing**, because a single-threaded test cannot arrange the failure; recorded so the ordering is not read as arbitrary and quietly swapped. | ⬜ NOT RUN — expected to redden nothing |
| CA-7 | `remove` soft-deletes (keeps the row, leaves the blob) | A deletion that did not delete, on the one thing here a customer may need removed for privacy rather than tidiness. Should redden `Writes.deleteRemovesTheBytes` on both the row count and `store.exists`. | ⬜ NOT RUN |
| CA-8 | `BlobUpload.key` uses the sanitized filename as its last segment instead of a fresh UUID | Two files of the same name on one case overwrite each other's bytes silently — the second upload's row points at the first's content, and both download identically. Should redden `Writes.theKeyIsAllServerUuids`. | ⬜ NOT RUN |
| CA-9 | `BlobUpload.sanitizeFilename` returns its argument unchanged | The traversal name reaches the column and the UI. Harmless to the filesystem — the key never contains it — and alarming on screen, which is the second reason the reduction exists. Should redden `Writes.theKeyIsAllServerUuids`' filename assertion and **not** its key assertion. | ⬜ NOT RUN |
| CA-10 | `CaseAttachmentController` sets `contentType` to `null` when the part declares none | A `NOT NULL` violation at flush, i.e. a 500 for an upload from any client that omits the header — `curl -F` among them. Should redden `Writes.missingContentTypeDefaults`. | ⬜ NOT RUN |
| CA-11 | The uploader is resolved in the **controller**, after the service's transaction closes | §121's exact defect, one slice on: `CaseOwners` reads through the tenancy filter, so with no `app.current_company` it names nobody. Fails **closed** — `uploadedBy` is simply null on every successful upload and nothing throws. Should redden `Gates.engineerAttaches`' `uploadedBy` assertions. | ⬜ NOT RUN |
| CA-12 | `CaseRenderer.attachmentsFor` uses `Map.of()` on the empty-uploaders arm | §38's trap, third sighting. `created_by` is nullable, so `Map.of().get(null)` throws by contract — every case detail 500s in exactly the batches where no uploader resolves, and works in the batches where one does. Should redden `Detail.filesRenderInUploadOrder`. | ⬜ NOT RUN |
| CA-13 | `attachmentsFor` keys the returned map on the attachment id instead of `owner_id` | Every case renders an empty list while rows exist — the grouping bug that is invisible on a one-case fixture. Should redden `Detail.filesRenderInUploadOrder` and **not** `Detail.listIsScopedToItsCase`, which passes for the wrong reason. | ⬜ NOT RUN |
| CA-14 | `TestCaseDetailView.attachments` is serialized as `null` when empty | Two spellings of nothing for the SPA to tell apart — the defect §121 phase 4 found on the neighbouring field, reintroduced on a field that has no third state to justify it. Should redden `Detail.emptyIsAList`. | ⬜ NOT RUN |
| CA-15 | `attachmentsFor` is called per case inside `CaseBulkService`'s loop rather than once per batch | 500 cases, 1000 queries, one bulk operation. Should redden nothing — **that is the finding**: no test in this repository counts queries, so every N+1 the renderer's two-step form prevents is prevented by a comment. | ⬜ NOT RUN — expected to redden nothing |
| CA-16 | `V39` re-adds `'STEP'` to the `CHECK` | #50 undone. Should redden `OwnerTypes.stepIsNotInTheConstraint` **and** `theEnumAndTheConstraintAgree`, which is the pair: one names the value, the other derives the whole set from `AttachmentOwner`, so neither alone would survive somebody "helpfully" adding the enum constant back too. | ⬜ NOT RUN |
| CA-17 | `AttachmentOwner` gains a `STEP` constant with no migration | The mirror of CA-16, and the direction a reviewer is likelier to take: the enum grows and the database does not, so the first row written raises a constraint violation in production. Should redden `OwnerTypes.theEnumAndTheConstraintAgree`. | ⬜ NOT RUN |
| CA-18 | The empty-part guard in the controller is removed, leaving only the service's `sizeBytes <= 0` check | Should redden nothing, and that is the point: the two checks are deliberately not one, because the service's is what a future non-controller caller meets. Recorded so the apparent duplication is not collapsed. | ⬜ NOT RUN — expected to redden nothing |
| CA-19 | `AuditEvent.record` for the removal drops the filename from `changes` | Once the row and the bytes are gone, that line is the **only** surviving statement the file existed — so *"who removed the signed-off spec"* becomes unanswerable. Should redden `Writes.auditKeepsWhatTheDeleteDestroys`. | ⬜ NOT RUN |
| CA-20 | `FilesystemAttachmentStore` is constructed with `"${tcms.storage.reports.root}"` | The rename's own failure mode: case attachments and evidence land in the regenerable root, which has different retention and is not backup-critical. Nothing throws and nothing 404s until a restore. Should redden nothing — the tests use one temp root — which is why the property key was **not** renamed alongside the type. | ⬜ NOT RUN — expected to redden nothing |

## §126 phase 2 — the `DOC_PAGE` attachment owner (`DA-1` … `DA-14`)

Added 2026-08-07. #49's second owner: `documentation` gains the page-attachment family and declares
`storage`, `DocPage` grows an `attachments` array, and the wire's `CaseAttachment` schema becomes the
shared `Attachment`. Assertions live in the new `DocPageAttachmentEndpointTest` (22) plus the
existing `DocPageEndpointTest` and `PlanDocumentEndpointTest`, which render the widened view without
naming the new field.

> **NONE RUN**, per §65. `DA-1` is the row worth running first: it is the one whose mutation is a
> *plausible reading of the roles spec* rather than a slip, so it would survive review.

| # | Mutation | Expected | Result |
|---|---|---|---|
| DA-1 | `DocPageAttachmentService.EDIT_DOC` becomes `doc:publish` | **The mutation this phase is most likely to receive**, and it is a plausible misreading rather than a typo: attaching a signed-off PDF *sounds* like a publication act. It leaves a Test Engineer able to author a specification they cannot illustrate, and every other doc write they make keeps working, so nothing else in the suite notices. Should redden `Gates.anEngineerWithoutPublishCanAttach` and **only** that test. | ⬜ NOT RUN |
| DA-2 | `delete` uses an `OWN`-qualified gate, copying `evidence:delete` | `CA-1` one module over, and it reads as safer here too. Incoherent: `doc:edit` already deletes the lead's paragraph beside the image. Should redden `Gates.anEngineerRemovesSomebodyElsesFile`. | ⬜ NOT RUN |
| DA-3 | `attach` is gated on the project read gate instead of `doc:edit` | Any Viewer can put files on somebody's specification. Should redden `Gates.viewerCannotAttach`. | ⬜ NOT RUN |
| DA-4 | The download asks `doc:edit` | The invented-permission failure arriving as over-caution: a Viewer sees a paperclip they can never open, and there is no `attachment:read` to grant. Should redden `Gates.viewerCanDownload`. | ⬜ NOT RUN |
| DA-5 | `write` refuses when `page.getStatus() == ARCHIVED` | The status arm that was deliberately **not** written. It reads as correct — archived means finished — and makes an archived page's files unremovable by anybody while the page's own `PATCH` still edits freely. Should redden `Gates.archivedPagesStillAcceptFiles`. | ⬜ NOT RUN |
| DA-6 | `write` skips `pages.findLiveInProject` and trusts the project gate | `/projects/{granted}/doc-pages/{ungranted}` attaches to a page the caller was never given. Should redden `Scoping.pageMustBeInThatProject`. | ⬜ NOT RUN |
| DA-7 | `resolve`/`remove` call `attachments.findById` instead of `findOwnedById` | `/doc-pages/{a}/attachments/{belongs-to-b}` serves and deletes another page's file with a valid grant on both. Should redden `Scoping.attachmentIsScopedToItsOwner`. | ⬜ NOT RUN |
| DA-8 | `DocPageWriteService.edit` passes `List.of()` for the attachments instead of re-reading them | **The subtle one.** `PATCH` answers with the whole page and the SPA renders that answer, so a save appears to delete every file on the page until the next `GET` puts them back — no error, no log line, and it looks like the upload was the thing that failed. Should redden `Detail.anEditDoesNotEmptyTheList`. | ⬜ NOT RUN |
| DA-9 | `DocPageAttachmentView` drops `uploadedBy` (or `CaseAttachmentView` gains a field this one lacks) | The cost of one wire schema served by two records: the response omits a required field on one owner only. Should redden `Detail.filesRenderInUploadOrder`; the *divergence itself* is caught by nothing — recorded because the two records are the standing hazard of this design and only the contract test sees them together. | ⬜ NOT RUN |
| DA-10 | `PageAttachments.forPage` uses `Map.of()` for the uploader lookup | §38's trap, fourth sighting. `created_by` is nullable, so `Map.of().get(null)` throws by contract — every page detail 500s whenever no uploader resolves. Should redden `Detail.filesRenderInUploadOrder`. | ⬜ NOT RUN |
| DA-11 | `remove` soft-deletes, or deletes the bytes before the row | `CA-6`/`CA-7` on this owner. The soft-delete half should redden `Writes.deleteRemovesTheBytes`; the reordering half should redden **nothing**, because a single-threaded test cannot arrange the failure — recorded so the ordering is not read as arbitrary. | ⬜ NOT RUN |
| DA-12 | The removal's audit row drops the filename | Once the row and the bytes are gone that line is the only surviving statement the file existed. Should redden `Writes.auditKeepsWhatTheDeleteDestroys`. | ⬜ NOT RUN |
| DA-13 | The audit target becomes `attachment` with the attachment's own id | The row survives the hard delete keyed on an id that no longer names anything, so "what happened to DOC-14" cannot be answered from the page's trail. Should redden `Writes.auditNamesThePage`. | ⬜ NOT RUN |
| DA-14 | `documentation`'s `package-info` declares `storage` **and** widens to add another module | §125's warning, exercised from the other side: Modulith enforces a declaration and has nothing to say about that declaration being widened. Should redden `ArchitectureRulesTest` only if a rule covers this module — **it does not**; `storageDependsOnTenancyAlone` guards `storage`'s own declaration, not its consumers'. Recorded as a **gap**, not a check. | ⬜ NOT RUN — expected to redden nothing |

## §126 phase 3 — the `ARTIFACT` attachment owner (`FA-1` … `FA-15`)

Added 2026-08-07. #49's third and last owner, and the one that is not a list: `ArtifactKind.FILE`
becomes registrable, `artifact.attachment_id` stops being one of `V1`'s deliberately-unmapped
columns, and `POST /projects/{projectId}/artifacts/files` writes **two rows that reference each
other** plus the bytes. Assertions live in the new `ArtifactFileEndpointTest` (16) plus the existing
`ArtifactEndpointTest`, whose `FILE`-is-refused test changed reason without changing verdict.

> **NONE RUN**, per §65. `FA-1` is the row worth running first — the qualifier is applied at a
> *second* call site now, and dropping it there passes every other test in the class.

| # | Mutation | Expected | Result |
|---|---|---|---|
| FA-1 | `createFile` uses `projects.writeGranted` instead of `writeGrantedByRowScope` | **The mutation this phase is most likely to receive.** The file create is the second call site of the row-scope gate, and the plain form compiles, reads as simpler, and hands a Test Engineer the project's test strategy — a document every plan inherits read-only. Should redden `Gates.anEngineerCannotUploadAtProjectScope` and only that test. | ⬜ NOT RUN |
| FA-2 | `createFile`'s `mayActOn` argument is inverted (`!= ArtifactScope.PLAN`) | The qualifier over-applied — the mirror failure, and the one that reads as caution: the register becomes Lead-only for uploads, which the roles spec's ✓ explicitly is not. Should redden `Gates.anEngineerUploadsAtPlanScope`. | ⬜ NOT RUN |
| FA-3 | The artifact row is saved before the attachment row in `writeFile` | The foreign key `artifact.attachment_id` is checked at statement time, so this is a constraint violation the caller reads as a 500 on every upload. Should redden every test in `Registration` — a **loud** failure, recorded because the ordering looks like style until you know which direction the FK runs. | ⬜ NOT RUN |
| FA-4 | `writeFile` writes the bytes before flushing the rows | The half that does not roll back going first: a failed transaction leaves a blob no row names — unreachable, uncollectable, invisible. Should redden **nothing**, because a single-threaded green-path test cannot arrange the failure. Recorded so the ordering is not read as arbitrary. | ⬜ NOT RUN — expected to redden nothing |
| FA-5 | `Artifact.register` ignores its `attachmentId` argument (leaves the column null) | The register renders a `FILE` with no filename, no size and no URL — a row the reader can see and cannot open — while the blob and its owner row are both perfectly fine. Should redden `Registration.bothRowsAndTheBytes` and `Register.theRegisterRendersNameSizeAndDownload`. | ⬜ NOT RUN |
| FA-6 | `Attachment.forArtifact` is swapped for `forDocPage` in `writeFile` | The owner vocabulary used wrongly at the one place it is chosen. The upload succeeds and the download 404s for ever, because `findOwnedById` asks for `ARTIFACT`. Should redden `Registration.bothRowsAndTheBytes`. | ⬜ NOT RUN |
| FA-7 | `ArtifactFiles.forArtifact` drops the `AttachmentOwner.ARTIFACT`/`ownerId` predicate and calls `findById` | The two-segment gate removed: an `attachment_id` guessed onto another artifact's row would serve bytes through a path that does not own them. Should redden **nothing today** — the column is written only by `writeFile`, so no test can produce the divergence — and it is recorded as the guard that must survive the day anything else writes it. | ⬜ NOT RUN — expected to redden nothing |
| FA-8 | `removeFile` returns early for every kind (the whole body deleted) | The bytes and the blob row outlive the register row: a deletion that did not delete, invisible from every screen, on the one thing a customer may need removed for privacy rather than tidiness. Should redden `Removal.unregisteringAFileDestroysTheBytes`. | ⬜ NOT RUN |
| FA-9 | `removeFile` keys on `getKind() == FILE` instead of on `attachment_id` | Equivalent today and it is the version that rots: the enum and the column are independent, and the day they disagree this leaves a blob behind. Should redden **nothing**. Recorded because it is the change a reviewer would suggest as clearer. | ⬜ NOT RUN — expected to redden nothing |
| FA-10 | `delete` calls `removeFile` **before** `artifacts.delete` | The mirror of `FA-3`: the attachment row is still referenced by `artifact.attachment_id`, so the delete is a constraint violation on every `FILE`. Should redden `Removal.unregisteringAFileDestroysTheBytes` loudly. | ⬜ NOT RUN |
| FA-11 | `delete` uses `PERMISSION` (`artifact:edit`) instead of `DELETE_PERMISSION` | §124's key undone, and it now costs more than it did: a Test Engineer who may register a cycle's documents can destroy the **last copy** of a lead's sign-off file. Should redden `Removal.anEngineerCannotUnregister`. | ⬜ NOT RUN |
| FA-12 | `validateKind`'s `FILE` arm refuses regardless of `hasUpload` | The "not registrable yet" refusal left in place under the new endpoint: every upload is a 400 naming `kind`, and the advice tells the caller to use the endpoint they are already using. Should redden every test in `Gates` and `Registration`. | ⬜ NOT RUN |
| FA-13 | `createFile` skips the `upload.sizeBytes() <= 0` check (the controller's `isEmpty` too) | The state the single-request design exists to make unreachable: a register row saying a document is held, backed by a zero-byte blob that downloads as a corrupt file. Should redden `Registration.anEmptyPartIsRefusedAndWritesNothing`. | ⬜ NOT RUN |
| FA-14 | `ArtifactView.of` returns `artifact.getExternalUrl()` unconditionally (never the content path) | A `FILE` renders `url: null`, so the SPA either shows an unopenable row or composes the route itself — the second place the route is spelled, which is what `url` being server-built exists to prevent. Should redden `Registration.bothRowsAndTheBytes`. | ⬜ NOT RUN |
| FA-15 | `ArtifactFileController.body` sends `inline` instead of `attachment` | The one deliberate difference from every other attachment response in the product. A 40 MB archive is handed to the browser to render, and the uploaded filename stops reaching the user. Should redden `Registration.theDownloadIsNotInline`. | ⬜ NOT RUN |

## §127 — the table and image block payloads (M3 #48, `DB-1` … `DB-14`)

Added 2026-08-08. `BlockContent` grew `rows` and `attachmentId`, `PageContent` grew three arms, and
`DELETE /doc-pages/{id}/attachments/{id}` grew a 409 with no foreign key behind it. Assertions live
in `DocPageEndpointTest` (`TableBlocks`, `ImageBlocks` — 13 tests), `DocPageAttachmentEndpointTest`
(`Writes` — 2) and `SearchVectorTest` (1).

> **NONE RUN**, per §65. **`DB-1` is the row worth running first and it is not close.** It is the
> only mutation here whose failure is *invisible in every response*: the page saves, reads back
> correct, and silently stops recording history. Every other test about tables passes against it.

| # | Mutation | Expected | Result |
|---|---|---|---|
| DB-1 | `PageVersions.SnapshotBlock` drops its `rows` component (and `snapshotOf` stops passing it) | **The mutation of this slice.** The hash decides whether a version row is written, so a content field the snapshot omits is one somebody can rewrite for ever while the history says nothing happened — with the page saving, reading back correct, and no error anywhere. Should redden `TableBlocks.aCellEditIsAuthorship` **and nothing else**, which is the point: every other table assertion passes. | ⬜ NOT RUN |
| DB-2 | The `@JsonInclude(NON_NULL)` annotations are removed from `SnapshotBlock`'s two new components | Every stored `content_hash` in every deployment changes on deploy, so the first save of each untouched page writes an empty-diff version — history noise delivered once per page as a migration artefact. Should redden `TableBlocks.theSnapshotStaysByteIdenticalForBlocksWithNoPayload`, which is the **only** observer: every hash inside one test run is computed by one build, so nothing else in the suite can see it. | ⬜ NOT RUN |
| DB-3 | `BlockContent.of` is inlined back into `DocPageWriteService.applyCreate` as `new BlockContent(text, level, null, null)` | The two-writers-of-one-mapping shape this factory removed: a table renders on save and comes back empty the first time the page is *created*, with the edit path perfectly correct. Should redden `TableBlocks.aTableKeepsItsCells` — specifically its re-read through the `GET`, not the create's own echo. | ⬜ NOT RUN |
| DB-4 | `validRows`'s ragged check compares against the **widest** row instead of the header | The stray extra cell silently widens the table and leaves the header a column short. Should redden `TableBlocks.aWideRowIsRaggedToo` and leave `aShortRowIsRefusedRatherThanFilledIn` green — the asymmetry is the finding. | ⬜ NOT RUN |
| DB-5 | `validRows` pads short rows with `""` instead of refusing them | The failure that renders cleanly: an empty cell the author typed and a cell the server invented are indistinguishable, and in a specification they mean opposite things. Should redden `aShortRowIsRefusedRatherThanFilledIn`. | ⬜ NOT RUN |
| DB-6 | `validRows` accepts an empty `rows` array on a `table` | A block that renders as nothing while occupying a position and holding an id something may link to. Should redden `theRowsRuleGoesBothWays`' second half. | ⬜ NOT RUN |
| DB-7 | `validRows` drops the "rows on a non-table" refusal (ignores the field instead) | The `level` rule undone one field over: the client believes it is sending a table, the server stores a paragraph, and nothing reports it. Should redden `theRowsRuleGoesBothWays`' first half. | ⬜ NOT RUN |
| DB-8 | `MAX_CELL` is raised to `MAX_TEXT` | A cell becomes a place to write a paragraph — and a paragraph in a table is the one thing on the page a case **cannot** link to, because `doc_link` targets a block. Should redden `aCellIsNotAParagraph`. | ⬜ NOT RUN |
| DB-9 | `validImage` skips the `pageAttachmentIds.contains` check | `parentId`'s defect one field over: a block references another page's file, every read fails to resolve it, and the block is saved successfully and immediately broken. Should redden `ImageBlocks.aForeignAttachmentIsRefused`. | ⬜ NOT RUN |
| DB-10 | `validImage`'s `onCreate` arm is deleted (falls through to the membership check) | The refusal becomes true-but-useless: on a create no id can pass, so a perfectly valid attachment id is reported as unknown and the author hunts a mistake they did not make. Should redden `aNewPageHasNoFilesToReference` on its **message** assertion while the 400 itself stays — the status alone passes. | ⬜ NOT RUN |
| DB-11 | The blank-alt-text arm is removed from `validBlocks` | An image block with no description, in the one product surface whose purpose is stating what a system must do. Should redden `anImageMustBeDescribed`' first half and **must leave its second half green** — an empty paragraph is a spacer somebody typed and stays legal. | ⬜ NOT RUN |
| DB-12 | `DocPageAttachmentService.remove` drops the `countBlocksReferencing` guard | An `image` block keeps an `attachmentId` pointing at nothing and renders as a hole in a specification. There is no foreign key to catch it — the reference is inside a JSONB document. Should redden `Writes.aReferencedFileIsRefused`. | ⬜ NOT RUN |
| DB-13 | The guard is moved **below** `store.delete(row.getStorageKey())` | The 409 still answers and the bytes are already gone: a refusal that destroyed the thing it refused to destroy. Should redden `aReferencedFileIsRefused`'s `store.exists` assertion only — the status assertion above it passes, which is why the placement is asserted separately. | ⬜ NOT RUN |
| DB-14 | `countBlocksReferencing` binds `:attachmentId` as `uuid` instead of `String` | `content ->> 'attachmentId'` yields **text**, and Postgres has no `text = uuid` operator, so this is a 500 on every attachment delete rather than a wrong answer. Should redden both rows in `Writes` loudly. Recorded because the cast is the tempting "fix", and it also makes a malformed stored value throw instead of simply not matching. | ⬜ NOT RUN |

**Not a mutation, recorded as an observation:** narrowing `V2`'s `tcms_doc_page_search_vector` to
`content ->> 'text'` reads as a tidy-up and would make every table's cells silently unfindable.
`jsonb_to_tsvector(…, '["string"]')` walks the whole document, which is why the cells became
searchable with no migration. `SearchVectorTest.tableCellsAreSearchable` is the guard.

---

## §128 — the case draft buffer (M3 #57, decision round 3 step 8)

> **`CD-*` IS A COLLIDING PREFIX — quote these as `§128 CD-n`, never bare.** The other owner is §158 — condition drift; see the ID COLLISION banner above for the rule (qualify, never renumber — decided 2026-08-11).

Added 2026-08-08. `test_case_draft` got its first writer since `V1`, three endpoints, and a clear on
every successful save. Assertions live in `CaseDraftEndpointTest` (26 tests across four nested
classes). **No migration** — every column has been there since M0.

> **NONE RUN**, per §65. **`§128 CD-1` is the row worth running first and it is not close.** It is the
> only mutation here whose failure is invisible to every *other* test in the class: writing and
> reading a draft works perfectly with the clear deleted, and what breaks is the next mount.

| # | Mutation | Expected | Result |
|---|---|---|---|
| §128 CD-1 | `CaseUpdateService.apply` drops the `drafts.deleteForCase(...)` call | **The mutation of this slice.** V1's own comment has promised this since M0 and nothing enforced it. The editor's next mount offers to restore content the user already saved — "your save did not land", about a save that did. Should redden `Promotion.savingTheCaseClearsTheBuffer` **and nothing else**, which is the point: every other test in the class passes. | ⬜ NOT RUN |
| §128 CD-2 | The `deleteForCase` call is moved to the **top** of `apply`, before validation | The clear that destroys the work it was protecting: a save refused for a blank title also throws away the buffered draft the author was about to lose. Should redden `Promotion.aFailedSaveKeepsTheDraft` while `savingTheCaseClearsTheBuffer` stays green — the pair is why the placement is asserted separately. | ⬜ NOT RUN |
| §128 CD-3 | `CaseDraftService.findOne` uses `readVisible` / `project:read` instead of `writeGranted` / `case:edit` | The gate most likely to be "simplified", on the argument that a draft discloses nothing the case does not. It discloses somebody's unfinished sentence, shown nowhere else in the product, to the one role invited to read coverage rather than watch it being written. Should redden `Gates.aViewerCannotReadTheDraftEither` and leave `theViewerReachesTheCase` green. | ⬜ NOT RUN |
| §128 CD-4 | `save`/`discard` swap `writeGranted` for `readVisible` | A Viewer can overwrite and destroy other people's unsaved work. Should redden `Gates.aViewerCannotBuffer` and `aViewerCannotDiscard` — and note both assert the row's *state* as well as the status, so a 403 that wrote anyway is caught too. | ⬜ NOT RUN |
| §128 CD-5 | `buffer` drops the `cases.findLiveInProject` check and trusts the project gate alone | `/projects/{granted}/cases/{ungranted}` buffers against a case the caller was never given, and the `GET` then reads it back. Should redden `Gates.theSecondPathSegmentIsGatedToo`. | ⬜ NOT RUN |
| §128 CD-6 | The `lockVersion == null` check is replaced with a default of `0` | The field exists precisely because guessing it wrong loses a committed edit: a draft that claims version 0 is stale against every case, or — worse, if the default were the case's current version — is silently *never* stale. Should redden `Writing.theLockVersionIsRequired`. | ⬜ NOT RUN |
| §128 CD-7 | The `lockVersion` check is moved **below** the `findLiveInProject` lookup | A malformed body answers 404, sending the caller to check a case id that is fine. Should redden `Writing.theBodyIsCheckedBeforeThe404` only — the 400 itself still happens for a reachable case, which is why the ordering has its own test. | ⬜ NOT RUN |
| §128 CD-8 | `render` computes `stale` as `false` unconditionally | The restore-over-a-colleague's-edit path goes unreported, and it is the one the existing `If-Match` machinery **structurally cannot catch** — the client's precondition comes from a fresh read and is correct. Should redden `Staleness.aDraftWrittenAgainstAnOlderVersionSaysSo` and leave `aCurrentDraftIsNotStale` green. | ⬜ NOT RUN |
| §128 CD-9 | `render` makes a stale draft return empty `content` instead of the buffered text | The refusal dressed as safety: the product deciding somebody else's edit invalidates your sentence. Should redden the second assertion of `aDraftWrittenAgainstAnOlderVersionSaysSo`, which exists separately from the `stale` flag for exactly this. | ⬜ NOT RUN |
| §128 CD-10 | `read` returns `Outcome.NotFound` instead of `Outcome.Empty` when no draft exists | Collapses the 204 into the 404 and tells an editor their case is gone every time they open one they had not left work in — which is almost every case, almost always. Should redden `Reading.noDraftIsA204AndNotA404` and leave `theCaseGateStillAnswers404` green; the pair is the whole distinction. | ⬜ NOT RUN |
| §128 CD-11 | `discard` answers `NotFound` when `deleteForCase` returns 0 | A second click, a retry after a timeout, and a concurrent save that already cleared the row all become errors. Should redden `Promotion.discardingNothingIsNotA404` on its **second** call — the first still finds a row, which is why the test discards twice. | ⬜ NOT RUN |
| §128 CD-12 | `buffer` validates the content through `CaseEdit.validate` before storing | The tempting "surely we should check it" change, and it breaks the one thing a keystroke buffer may never do: autosave starts failing while somebody is mid-sentence, so the buffer protects nothing during exactly the interval it exists for. Should redden `Writing.aHalfWrittenDraftIsBuffered`. | ⬜ NOT RUN |
| §128 CD-13 | `MAX_CONTENT_BYTES` is measured in `String.length()` rather than UTF-8 bytes | A different ceiling per language — a case written in Cyrillic or Japanese gets up to four times the storage of one written in English, on a column that stores bytes. Should redden nothing in the current suite, which is the finding: the assertion uses ASCII. **Recorded as a known gap rather than a mutation with an expected red.** | ⬜ NOT RUN |
| §128 CD-14 | `TestCaseDraft.overwrite` merges the incoming content instead of replacing it | A merge has to decide what an absent field means, and `CaseUpdateRequest`'s header records that absent and null are the same Java value here — so clearing a field would never survive an autosave. Should redden `Writing.theBufferIsReplacedWholesale`. | ⬜ NOT RUN |
| §128 CD-15 | `TestCaseDraft` is given a `@Version` column and optimistic locking | An autosave that answers 409 is one the user cannot resolve — they never asked for it and there is no dialog. Would not redden anything in this suite, because reaching it needs two interleaved transactions. **Recorded as a decision to defend, not a mutation with an expected red** — the same honesty `CaseUpdateService` applies to its own second concurrency check. | ⬜ NOT RUN |
| §128 CD-16 | `TestCaseDraftRepository` loses its `@Transactional` (or gains `readOnly = true`) | The first is the repository rule this project has a build-failing test for; the second is subtler — Hibernate skips the flush, so the write silently does nothing while every assertion about the *read* still passes. `RepositoryTransactionTest` catches the first from the live context. Should redden most of `Writing` for the second. | ⬜ NOT RUN |

**Not a mutation, recorded as an observation:** `findScopedById` is a `@Query` and deliberately not
`findById`. A primary-key lookup resolves through the persistence context and Hibernate filters do
not apply to that path — RLS would still cover it, and using one layer where both are available is
how a layered defence quietly becomes a single point. Nothing in the suite can see the difference,
which is exactly why it is written down.

---

## §129 phase 1 — cloning a case (M3 #59, decision round 3 step 8's second half)

Added 2026-08-08. `POST /projects/{p}/cases/{c}/clone`, `AttachmentStore.copy` as a fifth port
method, and `Attachment.copyForCase`. Assertions live in `CaseCloneEndpointTest` (23 tests across
five nested classes) and `FilesystemBlobStoreTest` (3 new). **No migration.**

> **NONE RUN**, per §65. **`CL-1` and `CL-6` are the two worth running first**, and for opposite
> reasons: `CL-1` is invisible to every other test in the class (a reference copy passes all of
> them, and fails the first time somebody deletes), while `CL-6` is the rule the code found by
> refusing to compile the plan.

| # | Mutation | Expected | Result |
|---|---|---|---|
| CL-1 | `copyAttachments` reuses the source's `storageKey` instead of allocating a fresh one — the literal reading of §6v's "by reference, not byte copy" | **The mutation of this slice.** Two rows over one key; `AttachmentStore.delete` is keyed and holds no reference count, so removing either file destroys the other's bytes and the survivor renders as an attachment that 404s with nothing having failed. Should redden `Files.deletingTheClonesFileLeavesTheSourcesReadable` **and nothing else** — `theFilesAreCopied` still sees a row, a filename and a checksum. | ⬜ NOT RUN |
| CL-2 | `copyAttachments` drops the `store.exists` guard and copies unconditionally | A source row whose bytes are already gone is cloned into a second row that also 404s — one operator incident becomes two, and the second is untraceable to the first. Should redden `Files.anOrphanedFileIsNotCloned`. | ⬜ NOT RUN |
| CL-3 | `Attachment.copyForCase` recomputes the checksum from the copied bytes instead of carrying the source's | Silent corruption laundered: a source blob that has rotted disagrees with its recorded checksum, and the clone would carry a *fresh* attestation that the rotten bytes are correct. Reddens the checksum assertion in `Files.theFilesAreCopied` only when the bytes differ, so **on today's fixture it reddens nothing** — recorded as a decision to defend rather than a mutation with an expected red, because nothing in the suite corrupts a blob. | ⬜ NOT RUN |
| CL-4 | `derive` passes `step.getId()` through instead of `null` | Every `step_result` recorded against the source's steps follows them onto a case nobody has run. Should redden `Copying.theStepsAreNewRows` — and note the request would *also* be refused by the create path's empty adoptable-id set, so the interesting variant is stripping the ids **and** widening that set. | ⬜ NOT RUN |
| CL-5 | `derive` copies `source.getAutomationKey()` | Either a constraint violation on `test_case_automation_key_unique`, or — with uniqueness relaxed — an ingested result matching two cases (§117). Should redden `Stripping.theAutomationKeyIsNotCopiedAndTheStatusIs` on its first assertion. | ⬜ NOT RUN |
| CL-6 | `clonedAutomationStatus` returns its argument unchanged | **The rule the plan did not have.** `CaseEdit.validate` refuses `AUTOMATED` with no key, so every clone of an automated case answers 400 — the two documented rules ("copy the status", "never copy the key") are jointly unsatisfiable and neither document says so. Should redden `Stripping.theAutomationKeyIsNotCopiedAndTheStatusIs` **and** every test whose fixture case is automated, which here is most of the class. | ⬜ NOT RUN |
| CL-7 | `clonedAutomationStatus` maps *every* status to `TO_BE_AUTOMATED` | The over-correction, and it says something false: a `NOT_AUTOMATABLE` case carries a written reason it cannot be automated, and the clone would claim it is merely pending. Should redden `Stripping.theOtherStatusesSurvive`. | ⬜ NOT RUN |
| CL-8 | `cloneCase` writes the rows itself instead of delegating to `CaseCreateService` | The design, inverted — a third definition of a legal case beside `CaseEdit` and the bulk reconstruction. Cannot be expressed as a one-line mutation, so it is recorded as **the shape to defend in review**: the observable symptoms are a clone that skips validation, one that is created `APPROVED`, and one with no version row. `Stripping.theCloneIsADraftEvenWhenTheSourceIsApproved` and `theCloneIsANewAggregate` are what would catch it. | ⬜ NOT RUN |
| CL-9 | `cloneCase` gates on `case:edit` instead of leaving the gate to the create path | A Test Engineer who may edit but not create can manufacture cases; more importantly the refusal message would name the wrong key. Should redden `Gates.aViewerCannotClone` only if Viewer holds neither — it does not, so the honest variant is gating on `project:read`, which reddens it. | ⬜ NOT RUN |
| CL-10 | `cloneCase` resolves the source with `cases.findById` instead of `readVisible` + `findLiveInProject` | `/projects/{granted}/cases/{ungranted}` clones a case from a project the caller was never given, through a project id they were — and the clone lands in the project they *can* read, so the content is exfiltrated in full. Should redden `Gates.theSecondPathSegmentIsGatedToo` and `anotherCompanysCaseIsInvisible`. | ⬜ NOT RUN |
| CL-11 | The `Replayed` arm falls through to `copyAttachments` and `recordProvenance` | A retry attaches a second identical copy of every file to a case that already has them, and writes a second provenance row, under a 200. The create is idempotent; the file copy is not part of it. Should redden the attachment-count assertion in `Replay.aRetriedIntentClonesOnce`. | ⬜ NOT RUN |
| CL-12 | `recordProvenance` is deleted, on the argument that `case.created` already records the creation | §6v's `clonedFrom` disappears and "where did this case come from" becomes unanswerable — the created row is identical to an ordinary create's. Should redden `Replay.theTrailSaysWhereItCameFrom`. | ⬜ NOT RUN |
| CL-13 | `recordProvenance` records the source's **id** and not its **key** | The id is what the audit row's target column already carries; the key is what a reader recognises a case by and the one thing the id does not tell them. Should redden the first assertion of `theTrailSaysWhereItCameFrom`. | ⬜ NOT RUN |
| CL-14 | `derive` passes `request.suiteId()` straight through, without the null fallback | A clone with no explicit destination lands in no suite (or fails validation), rather than beside the case it was copied from. Should redden `Copying.theSuiteDefaultsToTheSources`. | ⬜ NOT RUN |
| CL-15 | `derive` sets `changeComment` to `"Cloned from " + key` | It is hashed, so the next ordinary save — which sends no comment — differs by the comment alone and writes a version whose diff is empty. Reddens nothing in this class; `CaseVersionEndpointTest` is where a second empty version would show. **Recorded as a decision to defend.** | ⬜ NOT RUN |
| CL-16 | `AttachmentStore.copy` resolves only the source key and writes the destination unresolved | The traversal guard's *writing* end — the dangerous one. Should redden `FilesystemBlobStoreTest.copyGuardsBothEnds` on its second assertion. | ⬜ NOT RUN |
| CL-17 | `FilesystemBlobStore.copy` throws instead of returning `false` for an absent source | Turns the orphan the write ordering deliberately produces into a 500 on the clone, so one unreadable file makes the whole case uncopyable. Should redden `copyingAnAbsentKeyIsNotAnError` and `Files.anOrphanedFileIsNotCloned`. | ⬜ NOT RUN |
| CL-18 | `copy` is implemented in `CaseCloneService` as `open()` then `put()` rather than on the port | Correct today and wrong for ever after: every byte of every file through the JVM heap, and the one operation an S3 adapter makes free (`CopyObject`) is foreclosed. Reddens nothing — it is a **shape**, not a behaviour, which is why it is written down. | ⬜ NOT RUN |

**Not a mutation, recorded as an observation:** `CaseConditionOptout` has no writer anywhere in
`src/main`, so a clone has no opt-outs to copy and cannot be tested for copying them. When that
table gets a writer, the rule is that a clone landing in the **source's** suite carries the opt-outs
whose `suite_condition` belongs to that suite and one landing elsewhere carries none — one rule
covering both cases, and it needs a row here the day the writer lands.

---

## §129 phase 2 — cloning a plan and a run (M3 #59)

Added 2026-08-08. `POST …/plans/{planId}/clone` and `POST …/runs/{runId}/clone`, plus two
extractions the clone forced: `PlanScopeItem.rowsOf` (the scope→rows mapping, which existed twice)
and `PlanCreateService.write` (the create's orchestration, so the clone does not become a second
one). Assertions live in `PlanCloneEndpointTest` (12) and `RunCloneEndpointTest` (14). **No
migration.**

> **NONE RUN**, per §65. **`CL-19` and `CL-25` are the two to run first.** The first is the whole
> reason `PlanCloneService` exists rather than being four lines of delegation; the second is the
> one defect here that would be indistinguishable from a feature.

| # | Mutation | Expected | Result |
|---|---|---|---|
| CL-19 | `PlanCloneService` is replaced by a delegation to `PlanCreateService.create` with a derived `PlanWriteRequest` | **The mutation of this phase.** `TestPlanInput` carries six fields where a plan has twelve, so the clone drops the tag arm, every §97 section, both prose fields and both criteria lists — and answers 201 with a plan that reads as a faithful copy. Should redden `Copying.theWholePlanComesAcross` and nothing else, which is the point: the plan is created, named and scoped, and every other assertion passes. | ⬜ NOT RUN |
| CL-20 | The clone does create-then-edit instead of `PlanCreateService.write` | The cheap way to keep the sections. Every cloned plan is born at **version 2**, with a v1 that described a plan with no sections and no criteria — a history saying somebody rewrote it a second after it was made. Should redden `Copying.theCloneHasExactlyOneVersion`. | ⬜ NOT RUN |
| CL-21 | `PlanScopeItem.rowsOf` drops the tag loops (unsectioned and per-section) | The extraction's reason, inverted: this is what `PlanCreateService` did before §129, and it was *correct there* only because the create request had no tag arm. Should redden `Copying.theWholePlanComesAcross` on the tags assertion and leave `PlanUpdateEndpointTest`'s tag tests red too — the point being that one mapping now fails in both places rather than one. | ⬜ NOT RUN |
| CL-22 | `rowsOf` drops the `section.isEmpty()` placeholder row | An empty section is a heading somebody typed and it silently disappears on clone (and on every edit). Should redden the "Not started" assertion in `theWholePlanComesAcross`. | ⬜ NOT RUN |
| CL-23 | `PlanCloneService.derive` puts the source's criteria in the `PlanUpdateRequest` instead of passing the JSON to `write` | The plausible tidy-up. The clone then goes through `validateCriteria`, so a plan whose metric was retired since it was written becomes **permanently uncloneable** with a 400 about a field the caller never sent. Reddens nothing today — every seeded metric is still valid — so it is recorded as **a decision to defend**, and `Copying.theCriteriaAreCopiedVerbatim` is the assertion that documents it. | ⬜ NOT RUN |
| CL-24 | `PlanCloneService` gates on `project:read` instead of `plan:create` | A Viewer manufactures plans. Should redden `Gate.aViewerCannotClone`. The related check is `Gate.everyPlanCreatorAlsoHoldsPlanEdit`, which is **derived from the seeded catalogue** rather than restating `V3` — delete a role's `plan:edit` and it goes red without anybody editing this file. | ⬜ NOT RUN |
| CL-25 | `RunCloneService.derive` reads `overrideApprovalGate` from the source instead of the request (needs a column or an audit read to express — that is the finding) | **A documented 2am exception becomes a standing exemption**, invisible on every screen and unexplained in every audit row. Today it is *unexpressible*: nothing stores the flag. Should redden `Gating.theSourcesOverrideIsNotInherited`, which exists to keep it unexpressible. | ⬜ NOT RUN |
| CL-26 | `RunCloneService.derive` passes the source's suite/tag arms as well as the resolved case ids | The clone silently widens to cases authored since the source ran — a template's behaviour under a clone's name (§6v). Should redden `Copying.theScopeIsTheResolvedSetAndNotTheArms`. | ⬜ NOT RUN |
| CL-27 | `derive` picks an assignee from the source's first execution when the request omits one | Inventing a fact: a checklist can name two hundred different people and a run has no assignee column. Should redden `Copying.anOmittedAssigneeMeansUnassigned`. | ⬜ NOT RUN |
| CL-28 | `RunCloneService` gates on `run:execute` (§124's key) instead of `run:create` | Looks stricter and is wrong: Automation Service may open a run, and a clone of a run is a run — a CI job could no longer repeat its own nightly. Should redden `Gate.theRobotMayClone`. | ⬜ NOT RUN |
| CL-29 | The `Replayed` arm of either clone falls through to `recordProvenance` | A second provenance row claiming a clone that did not happen. Reddens nothing in the current suite (both tests assert the row exists, not that there is one of it) — **recorded as a known gap**, and the assertion to add is a count. | ⬜ NOT RUN |
| CL-30 | `PlanCloneService` resolves the source with `plans.findById` rather than `findLiveInProject` | `/projects/{granted}/plans/{other project's}` copies a plan out of a project the caller may not read, into one they can. Should redden `Gate.theSecondPathSegmentIsGatedToo`. | ⬜ NOT RUN |

**Not a mutation, recorded as an observation:** the run clone re-evaluates §6g's approval gate purely
because it is a create — no line in `RunCloneService` mentions gating. `Gating.theGateIsReEvaluated`
pins the consequence (a run legal last month is refused once its project turns gating on), and it is
the clearest evidence that "a clone is a create" buys correctness rather than merely brevity.

---

## §131 — the outbox (round 3 #52, `OB-1` … `OB-14`)

Added 2026-08-08. The transactional outbox: `V1`'s table gets its first code in M0+8 months, plus
the first `CompanyScopedEvent` implementation in production and the first cross-module event
delivery. Assertions live in `OutboxDispatchTest` (13) and `OutboxPropertiesTest` (6). **No
migration** — every column, `CHECK` and index this uses has been there since `V1`.

> **NONE RUN**, per §65. **`OB-1`, `OB-4` and `OB-9` are the three to run first.** The first is the
> pattern itself — remove it and every test still passes for a while. The second is the one that
> silently stops the retry ladder recording anything. The third turns an undelivered event into a
> delivered-looking one, which is this repository's oldest failure shape.

| # | Mutation | Expected | Result |
|---|---|---|---|
| OB-1 | `Outbox.record` / `recordCoalesced` drop the `isActualTransactionActive` guard | **The mutation of this slice.** The row still saves, delivery still works, and the atomicity the whole pattern exists for is gone with no symptom — a producer that rolls back leaves a notice for work that never happened. Should redden `recordOutsideATransactionIsRefused` and nothing else, which is the finding: only the refusal observes it. | ⬜ NOT RUN |
| OB-2 | `OutboxEntry.pending` leaves `nextAttemptAt` null (as `V1` allows) | `findDueIds` has no `IS NULL` arm, so a fresh event is **never due** and nothing is ever delivered. Should redden most of `OutboxDispatchTest`. The interesting half is why the column is filled rather than the query widened: `outbox_pending_idx` is a partial index on exactly this predicate. | ⬜ NOT RUN |
| OB-3 | `claim` bumps `nextAttemptAt` but not `attempts` | A row that kills the process is retried for ever — the ceiling is never reached because the counter only moves on a path the crash never reaches. Should redden `theCeilingStopsAtFailed`. | ⬜ NOT RUN |
| OB-4 | `OutboxLedger`'s methods are moved onto `OutboxDispatcher` (self-invoked, one transaction) | Two defects in one, and both are invisible on the happy path: a self-invoked `@Transactional` is not intercepted at all (§116's defect), and a handler's failure marks the surrounding transaction rollback-only, so the attempt count and error message are discarded and the row retries for ever having recorded nothing. Should redden `aFailingHandlerSchedulesARetry`. | ⬜ NOT RUN |
| OB-5 | `retryAt` sets `FAILED` instead of leaving `PENDING` | The status looks more honest and makes the row invisible to `outbox_pending_idx`, which is `WHERE status = 'PENDING'` — so retries fall off the only index the schema has for them. Should redden `aFailingHandlerSchedulesARetry`. | ⬜ NOT RUN |
| OB-6 | `failed` uses `>` instead of `>=` against `maxAttempts` | One extra attempt, for ever, off by one. Should redden `theCeilingStopsAtFailed` on the status assertion. | ⬜ NOT RUN |
| OB-7 | `stopped` leaves `nextAttemptAt` set | A terminal row stays on the due index and is re-read every tick for the life of the deployment, matching nothing. Reddens the `getNextAttemptAt()).isNull()` assertions in two tests. | ⬜ NOT RUN |
| OB-8 | `claim` drops the `nextAttemptAt.isAfter(now)` filter | The lease stops meaning anything: a claimed row is re-claimed by the next tick regardless, so a slow handler is delivered once per tick until it finishes. Should redden `aClaimedRowIsLeased`. | ⬜ NOT RUN |
| OB-9 | An unclaimed event type is marked `SENT` instead of `DEAD` | **A claim with no evidence behind it, rendering identically to a delivered one** — the shape §47, §53, §72 and §81 each paid for. A deployment with the consuming module disabled would report every event delivered. Should redden `anUnclaimedEventTypeIsDead`. | ⬜ NOT RUN |
| OB-10 | `dispatchDue` runs only the first handler claiming a type | One module is told and the other is not, for ever, with nothing failing — the fan-out is what two modules that may not call each other have instead of a call. Should redden `allClaimingHandlersReceiveTheEvent`. | ⬜ NOT RUN |
| OB-11 | `hasUnclaimed` drops the `attempts = 0` predicate | Coalescing folds a new notice into one a handler has **already read the database for**, so the newest results are not projected until the next scheduled tick — the staleness the event exists to remove, restored silently. Should redden `coalescedEventsDoNotFoldIntoAClaimedRow`. | ⬜ NOT RUN |
| OB-12 | `recordCoalesced` gains an `aggregateId` / payload parameter and the producer passes the current run | The tempting convenience. A coalesced event stands for every change since the last delivery, so the payload is a fact about one of them presented as the reason for all of them — and a handler that reads it scopes its recompute to one run. Reddens the `getAggregateId()).isNull()` assertion only; the rest is a design that has to be defended in review. | ⬜ NOT RUN |
| OB-13 | `dispatchDue` drops the company check on the claimed message | Unreachable through RLS today, which is exactly why it is asserted: it is the assumption every handler's writes rest on. Reddens nothing in the current suite — **recorded as a known gap**, since constructing a crossed row requires bypassing RLS as the superuser. | ⬜ NOT RUN |
| OB-14 | `OutboxProperties` drops the lease-vs-interval check | Ships a configuration in which every delivery slower than one tick is delivered twice, discovered under load and only under load. Should redden `leaseShorterThanIntervalIsRefused`. | ⬜ NOT RUN |

### §131 phase 2 — the producer and the two consumers (`OB-15` … `OB-20`)

The first cross-module event delivery in the product. `execution` records
`execution.results_changed` (from both the by-hand verdict and the CI ingest); `reporting` and
`repository` each claim it. Assertions live in `ExecutionEndpointTest.ResultsChangedEvent` (1) plus
phase 1's thirteen.

> **NONE RUN.** **`OB-15` first** — it is the one whose failure is a number on a screen being
> quietly out of date, which no test in this repository would notice.

| # | Mutation | Expected | Result |
|---|---|---|---|
| OB-15 | `ExecutionService.apply` drops the `outbox.recordCoalesced` call | The plan page's headline and the board go back to being up to fifteen and sixty minutes stale after a result is recorded — **correct eventually, wrong exactly when somebody is looking**. Should redden `ResultsChangedEvent.recordingRaisesOneCoalescedEvent`. | ⬜ NOT RUN |
| OB-16 | `IngestionService` records the event unconditionally instead of on `!recorded.isEmpty()` | A report whose every test was unmatched changes no execution row, so two modules re-derive an unchanged world on every CI push against a project whose mappings are not set up. Reddens nothing today — **recorded as a known gap**, and the assertion to add is an ingest with no matches. | ⬜ NOT RUN |
| OB-17 | `reporting.FactRefreshOnResultsChanged` calls `rollups.refresh()` before `refresh.refresh()` | §101's ordering, inverted: the plan headline reads `plan_snapshot` and the trend reads `execution_fact`, so the page disagrees with itself by one interval with nothing on screen to say so. Reddens nothing in the current suite — **known gap**, and the assertion belongs beside §101's own. | ⬜ NOT RUN |
| OB-18 | The two handlers' effects are merged into one bean in either module | Cannot compile — that is the check. `reporting` and `repository` may not import each other (plan §3), so the fan-out is not a stylistic choice. Recorded because the merge is the tidier-looking shape and the reason it is impossible is one file away. | ⬜ NOT RUN |
| OB-19 | Either handler's `@ConditionalOnProperty` is removed | `tcms.reporting.enabled=false` stops meaning "the fact log does not move" and starts meaning "it does not move *on a timer*" — the module is off and its tables keep changing. Reddens nothing; **known gap**, and the assertion is a context with the property off and an event that goes DEAD. | ⬜ NOT RUN |
| OB-21 | Either handler is renamed back so both are `ResultsChangedHandler` | **The defect this slice shipped and caught.** Two `@Component`s with one simple name share a default bean name; the context fails to start with `ConflictingBeanDefinitionException` — but **not in this suite**, because both carry `@ConditionalOnProperty` and `IntegrationTestBase` disables both, so neither bean is ever registered. Should redden `ArchitectureRulesTest.springStereotypesHaveDistinctSimpleNames` **and nothing else** — which is the entire point of that check reading bytecode rather than a context. | ⬜ NOT RUN |
| OB-20 | `OutboxEvents.RESULTS_CHANGED` is replaced by string literals at the three call sites | The reason the constant is in a foundation module at all. One typo and the producer writes an event nobody claims — a DEAD row in production, found by an operator rather than by the build. Reddens nothing (the literals would agree on the day of the mutation), so it is **a decision to defend in review** rather than a check. | ⬜ NOT RUN |

### §132 phase 1 — `audit_event.actor_token_id` (`TA-1` … `TA-6`)

The first of three dead token columns. `AuditEvent.record` reads `TokenContext` ambiently; the type
moved from `identity` to `tenancy` to make that legal, and `CompanyContextTaskDecorator` clears it.
Assertions live in `IngestionEndpointTest.Attribution` (2) and `AsyncCompanyContextTest` (2).

> **NONE RUN.** **`TA-2` first** — it is the only one whose failure writes a *false* record rather
> than an absent one, and nothing downstream could tell the difference.

| # | Mutation | Expected | Result |
|---|---|---|---|
| TA-1 | `AuditEvent.record` stops reading `TokenContext` (back to leaving `actorTokenId` null) | The pre-slice state: every CI job's audit row says a person did it. Should redden `Attribution.bothActorColumnsAreWritten` and nothing else — which is the measure of how little of the suite drives a token-authenticated write. | ⬜ NOT RUN |
| TA-2 | `AuditEvent.record` sets `actorUserId = null` when a token is present | **The tempting tidy-up**, and the one that loses accountability: the owner is who can be asked about a leaked credential, and a row naming only the token names nobody. Should redden the `auditActorUser()` assertion in both `Attribution` tests. | ⬜ NOT RUN |
| TA-3 | `CompanyContextTaskDecorator` clears the company but not the token (its state before this slice) | A pooled worker inherits a credential from a leaked task and every audit row it writes names that credential. Invisible on virtual threads, which are never reused — so it is one YAML line away from being live. Should redden `pooledThreadCarriesNoTokenResidue`. | ⬜ NOT RUN |
| TA-4 | `TokenContext.clear()` drops the restore and just removes | The inline path — `CallerRunsPolicy`, a `SyncTaskExecutor` — strips a live request's token mid-flight, and the request continues **uncapped**: the one direction in which losing this context grants rather than narrows. Should redden `inlineExecutionRestoresTheCallersToken`. | ⬜ NOT RUN |
| TA-5 | `AuditEvent.record` gains an `actorTokenId` parameter and one of the 42 call sites passes null | The design this slice refused, mutated at exactly the place it fails: the row reads correct and says a person did what a pipeline did. Reddens nothing unless the chosen call site is the ingestion one — **recorded as a known gap**, and the reason the parameter does not exist. | ⬜ NOT RUN |
| TA-6 | `ApiTokenAuthenticationFilter` opens the token scope without try-with-resources | A servlet thread keeps the credential for the next request on it, which then writes audit rows under somebody else's token and is capped by it. Reddens nothing in the current suite — **known gap**; the assertion needs two sequential requests pinned to one thread. | ⬜ NOT RUN |

### §132 phase 2 — `test_execution.executed_by_token` (`TA-7` … `TA-11`)

The column was **not mapped at all** — the field did not exist on `TestExecution`, while three
javadocs and the ingestion path's own comment named it as the home of a fact nothing recorded.
`record` fills it from the ambient token; `ExecutionTokens` resolves the id to the token's name on
the way out. Assertions live in `IngestionEndpointTest.Attribution` (2 more, 4 total).

> **NONE RUN.** **`TA-9` first** — the only one whose failure puts a *person's name* on a machine's
> claim, which is the thing this column exists to prevent.

| # | Mutation | Expected | Result |
|---|---|---|---|
| TA-7 | `TestExecution.record` stops setting `executedByToken` | The pre-slice state, restored: an ingested verdict is attributable to nothing at all, since `executedBy` is deliberately null too. Should redden `theExecutionRecordsTheCredential` and `theHistoryNamesTheCredential`. | ⬜ NOT RUN |
| TA-8 | `IngestionService.write` passes the token's **owner** as `executedBy` | The tempting fill for an empty column, and the one the surrounding comment already refuses: it attributes a machine's claim to a person who did not run the test. Should redden the `executedBy(...)).isNull()` assertions. | ⬜ NOT RUN |
| TA-9 | `CaseHistoryPage.authorLine` goes back to branching on `entry.source` | **The defect this phase found.** The source is a property of the *run* and the actor a property of the *verdict*; Automation Service holds `result:submit`, so a credential posting into a MANUAL run renders as "by \<the owner's name\>". No backend test can see it — it is a frontend branch over two fields that are both correct. Reddens nothing; **a browser row**, `TA-B1`. | ⬜ NOT RUN |
| TA-10 | `record` merges rather than overwrites — it leaves an existing `executedByToken` standing on an amendment | A person's amendment reads as a verdict a person and a credential made together, and the credential named is the one whose result was replaced. Reddens nothing today — **known gap**; the assertion is a token ingest followed by a human amend on the same execution. | ⬜ NOT RUN |
| TA-11 | `ApiTokenRepository.findAllByIdIn` becomes `findAllById` | `EntityManager.find` does not apply the Hibernate `@Filter`, so the company predicate rests on RLS alone — one layer instead of two, on a name rendered into another company's screen if RLS is ever bypassed. Reddens nothing (RLS still holds); recorded because it is the exact trap `findScopedById`'s javadoc documents, one method away. | ⬜ NOT RUN |

### §132 phase 3 — `api_token.last_used_at` (`TA-12` … `TA-15`)

The third dead column, and the only one of the three with a screen. Written in
`ApiTokenAuthenticationService`'s **second** transaction — the one already open inside the company
on the policed row — and only when the recorded value is stale by more than
`tcms.api-token.last-used-resolution` (`PT1H`). Assertions live in
`ApiTokenAuthenticationTest.LastUsed` (3) and `ApiTokensPage.test.tsx` (1).

> **NONE RUN.** **`TA-13` first** — it is the one whose failure is a production write-amplification
> nobody sees in a test, on the hottest row in the table.

| # | Mutation | Expected | Result |
|---|---|---|---|
| TA-12 | `markUsed` always writes (drop the staleness comparison) | The naive writer: one `UPDATE api_token` per authenticated request, every request, all contending on a busy CI token's single row — to keep a value whose only reader is an administrator who cannot tell an hour from a second. Should redden `theSecondUseIsFree`. | ⬜ NOT RUN |
| TA-13 | The bump moves to `ApiTokenAuthenticationFilter`, in its own transaction | Two transactions per authenticated request instead of one, and the second begins **outside** the company scope — so it writes under RLS with no GUC and updates zero rows, silently. The column stays empty and every test still passes. **This is the shape §116's red run had**; reddens nothing here, so it is a **known gap** and a design to defend in review. | ⬜ NOT RUN |
| TA-14 | `accept` is called before the `isUsable` filter | "Last used" becomes "last presented": a revoked credential somebody is still trying gets a live date, and the administrator deciding what to revoke reads a date on the one they already did. Should redden `arefusedRequestRecordsNothing`. | ⬜ NOT RUN |
| TA-15 | `ApiToken` gains `@Version` for this write | Two concurrent requests on one CI token — the ordinary case, not the race — start 409ing an unattended pipeline over a lost timestamp bump. Reddens nothing (the suite is sequential); recorded because "add optimistic locking" is the reflex and the class header already argues the opposite for `revoke`. | ⬜ NOT RUN |

### §133 — the `Agent` role, `role.assignable` and the agent-token expiry (`AG-1` … `AG-9`)

> **`AG-*` IS A COLLIDING PREFIX — quote these as `§133 AG-n`, never bare.** The other owner is §124 — the `BEST` removal; see the ID COLLISION banner above for the rule (qualify, never renumber — decided 2026-08-11).

`V40` seeds an eighth `role` row and a flag that replaces two of five hardcoded
`COMPANY_MEMBER_ID` comparisons. **The failure mode this whole slice guards is a tidy-up**: three
of the five sites must stay baseline-specific, and every one of them looks like it wants the flag.
Assertions live in `SeedDataTest` (3 new), `RoleGrantEndpointTest` (1 new), `ApiTokenEndpointTest`
(2 new) and three frontend tests.

> **NONE RUN.** **`§133 AG-1` first** — it is the one whose failure makes the role unobtainable through
> the product while every other screen keeps working.

| # | Mutation | Expected | Result |
|---|---|---|---|
| §133 AG-1 | `ApiTokenService.validate`'s `COMPANY_MEMBER_ID` check becomes `!role.isAssignable()` | The one-line "cleanup" the code comments refuse in three places. An `Agent` token becomes unissuable — the single thing the role exists for — and nothing else changes: every other role still issues, every grant still refuses. Should redden `theAgentCeilingIsIssuable`. | ⬜ NOT RUN |
| §133 AG-2 | `AdminDirectoryService.roleCatalogue`'s filter becomes `WHERE assignable` | Same outcome by the other route: `Agent` vanishes from `GET /roles`, so the token form cannot offer it. Should redden `theRoleCatalogueIsWholeAndHasNoWildcard` and the SPA's `offers Agent, which every grant picker hides`. | ⬜ NOT RUN |
| §133 AG-3 | `RoleGrantService.grant` goes back to comparing `COMPANY_MEMBER_ID` | The state before the flag: an administrator can grant `Agent` to a person, conferring a capability set no evaluation path reads. Should redden `theAgentCeilingIsNotGrantableEvenThoughItIsListed`. | ⬜ NOT RUN |
| §133 AG-4 | `RoleGrantService.revoke`'s `orElse(false)` becomes `orElse(true)` | The fail-open arm of the same check. Unreachable today (`role_id` is a foreign key), so it reddens nothing — recorded because "an unknown role is revocable" is the arm that only has to be wrong once, and a future soft-deleted role row is what would reach it. **Known gap.** | ⬜ NOT RUN |
| §133 AG-5 | `V40` grants `Agent` `library:manage` | The permission roles spec §A3 gates by an *impact modal*, handed to a caller that never sees one: editing a shared condition propagates to every referencing case with no confirmation anywhere. Should redden `agentIsTestEngineerMinusThree`. | ⬜ NOT RUN |
| §133 AG-6 | `V40` grants `Agent` `case:delete` (`OWN`, copying Test Engineer) | `OWN` resolves against `created_by` while the token's owner is `api_token.owner_id`, so the agent deletes cases **the human authored** — the reason the key is withheld rather than qualified. Should redden `agentIsTestEngineerMinusThree`. | ⬜ NOT RUN |
| §133 AG-7 | `V40` spells `Agent`'s `artifact:edit` qualifier as `ALL` | An agent rewrites the project-scope strategy document every plan inherits read-only (§6n). Should redden `testEngineerHoldsArtifactEditOnlyAtPlanScope`; a *different* misspelling (a new qualifier value) should instead redden `noPermissionCarriesTwoDifferentRestrictiveQualifiers`. Run both variants. | ⬜ NOT RUN |
| §133 AG-8 | `ApiTokenService.validate` drops the `Agent` expiry rule | Every agent token issued from `curl` is open-ended, and the form's pre-fill is the only thing standing — which is exactly the boundary §2.5 says a client cannot hold. Should redden `anAgentTokenMustExpire`. | ⬜ NOT RUN |
| §133 AG-9 | The intersection is asserted end-to-end: issue an `Agent` token owned by a **Viewer**, then `POST` a case with it | **Not a mutation — a missing test.** The whole design rests on `evaluate(ownerId) ∩ permissionsOf(Agent)`, and nothing drives it for an `Agent` token; §132 already recorded that `IngestionEndpointTest.Attribution` is the only place in the suite a write is authenticated by a real `api_token` at all. Expect a 403. **Known gap, and the highest-value row here.** | ⬜ NOT RUN |

### §134 — Automation Service becomes non-assignable, and revoke leaves the flag (`AS-1` … `AS-5`)

`V41` flips one seeded row. The slice's real content is the **split**: `RoleGrantService.grant`
reads `assignable`, `revoke` does not, and every mutation below is a version of putting them back
together. Assertions live in `SeedDataTest` (2, one new), `RoleGrantEndpointTest` (3 new) and the
SPA's `IssueTokenDialog.test.tsx`.

> **NONE RUN.** **`AS-2` first** — it is the one whose failure leaves data stranded on a live
> instance rather than merely refusing something.

| # | Mutation | Expected | Result |
|---|---|---|---|
| AS-1 | `V41`'s `UPDATE` is reverted (`assignable` stays true for `…0005`) | The state before this slice: `POST /role-assignments` accepts a CI capability set for a person, and every grant picker offers it. Should redden `automationServiceIsNotGrantableToAPerson` and `nonAssignableRolesAreExactlyTheThree`. | ⬜ NOT RUN |
| AS-2 | `RoleGrantService.revoke` goes back to `Role::isAssignable` | **The row this slice exists for.** Every person-held `Automation Service` grant made before `V41` becomes permanently unrevokable while `GET /role-assignments` keeps listing it — a Revoke button that 403s forever, with no message naming a role. Should redden `anAutomationServiceGrantMadeBeforeTheRuleIsStillRevocable`. | ⬜ NOT RUN |
| AS-3 | `AdminDirectoryService.listRoleAssignments` filters non-assignable roles' grants | The other half of the same defect, arrived at from the "tidy it up" direction: the stranded grant becomes invisible instead of unrevokable, so nobody can even see what to clean up, and the permissions keep resolving. Should redden `anAutomationServiceGrantIsStillListed`. | ⬜ NOT RUN |
| AS-4 | `AdminDirectoryService.roleCatalogue` becomes `WHERE assignable` | `§133 AG-2` with a second victim: `Automation Service` leaves `GET /roles` as well as `Agent`, so **no CI credential can be issued through the product**. Should redden `theRoleCatalogueIsWholeAndHasNoWildcard` and the SPA's `offers BOTH roles every grant picker hides`. | ⬜ NOT RUN |
| AS-5 | `V41` also `DELETE`s existing person-held `Automation Service` grants | The option refused on 2026-08-08: a migration removing access on a live instance, with no actor and therefore no audit row `RoleGrantService` would have written. Reddens nothing — the fixtures create their grants after migration — which is the finding. **Known gap: the suite cannot observe a migration that deletes production data.** | ⬜ NOT RUN |

### §136 — `Idempotency-Key` on suite and doc-page creates (`IK-1` … `IK-7`)

`V42` adds two columns and two partial unique indexes; the services gain the replay lookup the
other four creates have had since `V11`/`V14`/`V17`. Assertions live in
`SuiteWriteEndpointTest.Idempotency` (9 new), `DocPageEndpointTest.Idempotency` (9 new) and
`IdempotencyKeyCapTest` (3 new, no Spring context).

> **NONE RUN.** **`IK-3` first** — it is the one whose failure is silent on both sides of the wire:
> the endpoint keeps answering 201 and the client keeps believing it deduplicated.

| # | Mutation | Expected | Result |
|---|---|---|---|
| IK-1 | `V42`'s unique indexes are made non-unique | The lookup still finds the first row, so every single-threaded test stays green — the constraint exists for the **race** between two retries of one intent, which the suite does not run. Reddens nothing. **Known gap: no test in this repo drives two concurrent creates carrying one key**, on any of the six tables. The same gap has existed since `V11`. | ⬜ NOT RUN |
| IK-2 | The replay lookup moves **after** `SuiteEdit.validate` / `PageContent.validateCreate` | A retry whose payload drifted gets a 400 naming fields on a resource that already exists, sending the client to fix a suite it cannot see. Should redden `aRetryIgnoresADriftedPayload` in both classes. | ⬜ NOT RUN |
| IK-3 | `trimToNull` is dropped from either controller (header passed through raw) | `Idempotency-Key: ` (empty) is stored as `''` and becomes one key every misconfigured client in the project replays against — the project accepts exactly one suite, or one page, from all of them, forever. Should redden `aBlankKeyIsAbsent` in both classes. | ⬜ NOT RUN |
| IK-4 | `findByIdempotencyKey` drops its `projectId` predicate | A key reused across two projects of one company passes the lookup and then violates the index: a validated request that 500s on insert. Should redden `keysAreScopedToTheProject` (suite). **The doc-page class has no second granted project**, so its own scope test only crosses *companies* — a narrower observation, recorded rather than papered over. | ⬜ NOT RUN |
| IK-5 | The controller answers `201` + `Location` on a replay | A client following the `Location` reads the replay as having created something, which is what the 200 exists to tell it apart from. Should redden `aReplayCarriesNoLocation` and the status assertion in `aRetryReplaysTheOriginal`, both classes. | ⬜ NOT RUN |
| IK-6 | `MAX_IDEMPOTENCY_KEY` is changed to 500 in **one** of the seven services | The drift the copies invite: one key format works against part of the API and 400s against the rest. Should redden `IdempotencyKeyCapTest.everyCapAgrees` and `theContractAgreesWithTheCode`. **This is the row that justifies not extracting a shared constant** — if it does not redden, the argument in `SuiteWriteService`'s field comment is wrong and the symbol should be extracted. | ⬜ NOT RUN |
| IK-7 | `DocPageWriteService.writeSubjectPage` passes a key instead of null | A plan-document create starts consuming keys from a header it never receives — harmless today (the parameter is null at the only call site) but it would make `uq_doc_page_subject` and this index two answers to one question. Reddens nothing. **Known gap**, recorded because the null is a decision and not an omission. | ⬜ NOT RUN |

### §137 — the TypeScript client and the CLI (`TS-1` … `TS-9`)

**A new stack, and the queue's first rows that are not `./gradlew`.** These mutate
`packages/client` and `packages/cli` and are verified with `cd packages && npm run test:run
--workspaces` — the scope column names the file, since the suite is ~0.5s whole and there is no
container to boot. Assertions live in `http.test.ts` (21), `client.test.ts` (10),
`schemaFreshness.test.ts` (4), `core/core.test.ts` (33) and `cli/src/main.test.ts` (14).

> **NONE RUN.** **`TS-4` first** — it is the only row whose failure is a security finding rather
> than a defect, and `TS-2` second, because its failure mode is a *silent* data loss on somebody
> else's edit.

| # | Mutation | Expected | Result |
|---|---|---|---|
| TS-1 | `http.ts` sends the outgoing request as `new Request(request, { headers, signal: deadline })` instead of a URL and an init | The shape the first draft had. The deadline fires and the copy's signal never does, so every request hangs until the peer gives up — the failure the budget exists to prevent, arriving through the budget. Should redden `becomes a TcmsTimeoutError that says the write may have landed`. **The row that pins the finding**; if it does not redden, the workaround is unnecessary and the comment in `http.ts` is wrong. | ⬜ NOT RUN |
| TS-2 | `concurrency.ts`'s `versionOf` returns `{ none: 'state-preconditioned' }` instead of throwing when the resource carries no `lockVersion` | Every edit whose read lacked a version silently becomes last-write-wins, overwriting a colleague's concurrent change with no error anywhere — the single worst thing this surface can do. Should redden `refuses to write when the read carried no version`. | ⬜ NOT RUN |
| TS-3 | `http.ts` drops the `if (perRequest.ifMatch !== undefined)` guard for a truthiness check | `lockVersion: 0` — a resource nobody has edited yet — stops sending `If-Match` while every other version keeps working, so the defect is invisible on any fixture that has been saved once. Should redden `sends If-Match for lockVersion 0, which a truthiness check would drop`. | ⬜ NOT RUN |
| TS-4 | `refusal.ts`'s 404 branch returns the 403 branch's message | The enumeration oracle §6t exists to prevent: a caller in another company learns that a row exists from a message naming the permission they lack. Should redden `says nothing about permissions or roles on a 404` and the CLI's `says nothing about permissions on a 404`. **Failure here is a security finding, not a defect.** | ⬜ NOT RUN |
| TS-5 | `identifiers.ts` adds `SU` to `KEY_PATTERN`, as the surface spec's §4.4 example implies | `TS-7` and `SU-7` both parse; the second resolves nothing and reports "not found" for a key the user believed in. Should redden `parses the five seeded prefixes and nothing else`. | ⬜ NOT RUN |
| TS-6 | `identifiers.ts`'s `classify` drops the `expected` mismatch check | `tcms case show TS-7` looks up a suite key in the case index and reports "no such case" — a typo with a plausible-looking answer. Should redden `refuses a key of the wrong kind rather than looking it up` and the CLI's `refuses a key of the wrong kind without making a request`. | ⬜ NOT RUN |
| TS-7 | `paging.ts`'s `eachItem` drops the `result.items.length === 0` exit | A server answering an empty page while claiming a larger total spins forever against an instance the client cannot fix. Should redden `stops on an empty page even when total claims more`. | ⬜ NOT RUN |
| TS-8 | `main.ts` returns `EXIT.failed` for a `UsageError` | A mistyped flag and a refused request become indistinguishable to a script, so a pipeline retries the one that will never succeed. Should redden `exits 2 when a show command is given no argument` and `exits 2 for a bad --timeout, not 1`. | ⬜ NOT RUN |
| TS-9 | Two concurrent invocations of a write carrying one `Idempotency-Key` | **Not a mutation — a missing test**, and the same gap `IK-1` records on the backend side, now with a client that can actually produce the race. Nothing in either stack drives two concurrent creates under one key. **Known gap.** | ⬜ NOT RUN |

**Not in this queue, deliberately:** the CLI's read commands against a live instance. That is
neither a mutation nor a browser lane, and the WIP plan's §9 puts rows here for *the agent-token
path and every write arm* — which is phase 7, not this slice. What it means in the meantime is
stated plainly in the handoff: **no command in `@tcms/cli` has been run against a real TCMS.**
Every test above drives a stubbed `fetch`, so the contract is enforced by the types and the
behaviour by the stubs, and the one thing neither can observe is the server disagreeing with its
own `openapi.yaml`.

### §138 — `ai_proposal`, the review boundary (`AP-1` … `AP-8`)

**The queue's first rows whose subject is a boundary rather than a field.** Scope is
`./gradlew test --tests 'com.tcms.repository.AiProposalEndpointTest'` unless a row says otherwise;
assertions live in that class's five nested groups.

> **NONE RUN.** **`AP-1` first** — it is the only row whose failure is a product-integrity finding
> rather than a defect: it is what stops any caller minting verified coverage.

| # | Mutation | Expected | Result |
|---|---|---|---|
| AP-1 | `AiProposalInput` gains a `verdictSource` field and `AiProposal.file` uses it | Any MCP client can send `verdictSource: TCMS_VERIFIED` and have TCMS report its own critic checked a case nothing checked — §6w.1a's whole argument, defeated by one field added "for symmetry". Should redden `a claimed source is ignored and derived instead`. **If it does not redden, the second barrier (`V43`'s CHECK) is carrying the rule alone and the test is not testing what it says.** | ⬜ NOT RUN |
| AP-2 | `V43`'s `verdict_source` CHECK gains `'TCMS_VERIFIED'` | Reddens nothing on its own — the second barrier is invisible while the first holds, which is why both exist. **Known gap made visible**: run it together with `AP-1` and the pair should still redden, because the derivation is what the test observes. | ⬜ NOT RUN |
| AP-3 | The file endpoint is gated on `case:read` instead of `case:create` | The client's read-only stakeholder can fill the reviewer's queue, and every read in this module would pass the same gate. Should redden `a Viewer is refused — proposing is creating, not reading`. | ⬜ NOT RUN |
| AP-4 | `suite_id` gets its foreign key back (`REFERENCES suite(id)`) | A proposal naming a suite id that does not exist becomes a **500 at file time** instead of a stored proposal a reviewer can reject — so a generation agent that hallucinated one id loses the batch. Should redden `A SUITE THAT NO LONGER EXISTS IS STILL FILED`. **This row is the record of a constraint that was written, failed this test, and was removed deliberately.** | ⬜ NOT RUN |
| AP-5 | `AiProposalService.store` validates the payload through `CaseEdit.validate` before storing | Two answers to "is this a legal case", given a week apart, and the file-time one refuses proposals the accept-time one would have taken. Reddens `A SUITE THAT NO LONGER EXISTS IS STILL FILED` and probably nothing else — **the rest of the cost is invisible to the suite**, which is why the decision is written in the service header rather than only here. | ⬜ NOT RUN |
| AP-6 | The replay lookup moves **after** `validate` | A retry whose payload drifted gets a 400 about fields on a proposal that already exists, sending a program to fix something it cannot see. Should redden `a retry whose payload drifted still replays rather than 400ing`. | ⬜ NOT RUN |
| AP-7 | `findByIdempotencyKey` drops its `projectId` predicate | A key reused across two projects of one company passes the lookup and then violates the index — a validated request that 500s. **No test covers this yet on this table**; the shape is `IK-4`'s and the same gap. **Known gap.** | ⬜ NOT RUN |
| AP-8 | `filedByAgent` is computed as `createdBy != null` rather than `createdTokenId != null` | Every proposal reads as agent-filed, including the ones a person typed — so the flag that changes how a reviewer reads a proposal says the same thing about all of them. Should redden `a proposal filed in a browser session is not filed by an agent`. | ⬜ NOT RUN |
| AP-9 | `AiProposalDecisionService.decide` drops the `TokenContext.currentOrNull() != null` branch | **The whole boundary.** An agent token files a proposal and accepts it in two calls, so the review surface is defeated by the thing it exists to review — and nothing in the permission model notices, because `Agent` holds `case:create` on purpose. Should redden `AN AGENT TOKEN CANNOT ACCEPT ITS OWN PROPOSAL` and the reject half of `a Viewer can decide nothing` stays green throughout, which is the point: **no permission test can see this.** | ⬜ NOT RUN |
| AP-10 | The credential branch moves **after** the proposal lookup | An agent learns which proposal ids exist from the 404-versus-403 difference — §6t's enumeration oracle arriving through the one endpoint that refuses it for an unrelated reason. **Reddens nothing**; the suite has no assertion that an agent gets the same answer for a real id and an invented one. **Known gap**, and the reason the ordering is written in the method's javadoc. | ⬜ NOT RUN |
| AP-11 | `acceptOne` inserts the case itself instead of calling `CaseCreateService` | A second definition of a legal case, a display key allocated outside `DisplayKeys`, no v1 version row, and a status a payload field could set — every §6w.6 guarantee that currently holds *by construction*. Should redden `a person accepts, and the case arrives through the ordinary create path` on the key, the status, or the owner. | ⬜ NOT RUN |
| AP-12 | The synthetic `Idempotency-Key` passed to the create path is replaced with `null` | Two reviewers accepting at the same moment create **two cases from one proposal**. Reddens nothing single-threaded — `accepting twice answers with the case` passes on the status check alone — so this is the same race gap as `IK-1`, one level up. **Known gap.** | ⬜ NOT RUN |
| AP-13 | `rejectOne` overwrites the note when the proposal is already `REJECTED` | A second click erases the reason the decision was actually made for, which is the one thing keeping the row was for. Should redden `rejecting twice keeps the FIRST note`. | ⬜ NOT RUN |
| AP-14 | The accept path stops writing `ai_proposal.accepted`, relying on `case.created` alone | The audit trail records that a case was created and never that it came from a model — so "which cases did an agent propose" is unanswerable a month later, and `verdict_source` is nowhere in the log. Should redden `both decisions are audited, and the accept records the provenance`. | ⬜ NOT RUN |

### §139 — the CLI's write arms (`CW-1` … `CW-12`)

**The second batch of rows that are not `./gradlew`**, on `TS-*`'s terms: mutate `packages/`, run
`cd packages && npm run test:run --workspaces`, and name the file in the scope column since the
whole suite is under a second. Assertions live in `cli/src/writes.test.ts` (48 across five groups)
and `client/src/core/core.test.ts`'s `mutation intents` (5).

> **NONE RUN.** **`CW-1` first** — it is the row that pins this slice's whole finding, and its
> failure mode is a *silent* loss of somebody else's work. `CW-5` second, for the same reason one
> noun over.

| # | Mutation | Expected | Result |
|---|---|---|---|
| CW-1 | `writes.ts`'s `versionFor` drops the `fromFile` branch and always reads a fresh version | The finding, defeated. A `--file` edit built from a read at version 3 sends `If-Match: 5`, the server accepts, and whoever saved at 4 loses their words — last-write-wins arriving **through** the header that prevents it, with a green build and a 200. Should redden `refuses a --file edit that names no --lock-version` and `refuses a whole-body edit that names no version, and says why`. **If it does not redden, the rule is a comment and not a behaviour.** | ⬜ NOT RUN |
| CW-2 | `context.ts`'s `strictNumber` returns `undefined` for an unparseable value instead of throwing | `--lock-version latest` falls through to a *fresh read* — the typo silently selects the unsafe path, which is `CW-1` reached by accident rather than by design. Should redden `refuses a --lock-version that is not a number, rather than falling back to a fresh read`. | ⬜ NOT RUN |
| CW-3 | `main.ts` restores `strict: false` and stops merging `command.options` | §137's live defect, restored. Every string flag on every command parses as `true` and its argument becomes a positional, so `case list --q login` answers the unfiltered list and `case edit --title x` sends `{}`. Should redden `sends the filter values, rather than parsing them as booleans`, `exits 2 rather than sending an empty body`, and most of `writes.test.ts`. **The row that pins a defect that shipped and was invisible for two slices.** | ⬜ NOT RUN |
| CW-4 | `context.ts`'s `requireSomethingToDo` returns the body instead of throwing | A `PATCH` of `{}` is legal by the contract, so a command whose flags were all wrong answers 200 and reports a save. Should redden `exits 2 rather than sending an empty body that reports success`. | ⬜ NOT RUN |
| CW-5 | `docEdit` sends `blocks: page.blocks` alongside the title, "so the page round-trips" | The contract's rule is that `blocks` is the whole page and an **omitted block is deleted** — the helpful round-trip is one stale read away from replacing somebody's specification with an older copy of it. Should redden `retitles without sending blocks, which would empty the page`. **The one mutation in this queue that destroys authored content rather than a field.** | ⬜ NOT RUN |
| CW-6 | `present()` keeps `undefined` values instead of dropping them | Every unnamed field is sent as `null`, so `case edit --title x` clears the description, the objective, the owner and the automation key. `PATCH`'s absent-versus-null split is the whole protection and this removes it. Should redden most of `case edit` and `suite writes`. | ⬜ NOT RUN |
| CW-7 | `nullableText` maps `''` to `undefined` rather than `null` | The clearing form stops clearing: `--description ''` becomes "leave it alone", silently, and a user who cannot empty a field has no other spelling to reach for. Should redden `leaves unnamed fields alone, clears an empty one, and files by suite key`. | ⬜ NOT RUN |
| CW-8 | `caseProposal` defaults `verdict` to `UNVERIFIED` when none was given | Absent and `UNVERIFIED` are different claims — nobody asserted anything, against an agent asserting it had not checked — and they differ in `verdictSource`, which §6w.1a is built on. Every silent proposal becomes a claim. Should redden `sends no verdict when none was given`. | ⬜ NOT RUN |
| CW-9 | `suiteStatus` filters the target state against a client-side ladder | A second copy of §6g's ladder, in a client versioned independently of the instance it points at — so a Test Lead's token is refused a move the server would have allowed, by the tool rather than by the rule. Should redden `does not filter the ladder client-side`. | ⬜ NOT RUN |
| CW-10 | `planEdit` stops threading the plan's current name through a name-less edit | `TestPlanUpdateInput` requires `name`, so `--description`-only becomes a 400 about a field the user never mentioned. Should redden `carries the plan's current name through an edit that does not mention it`. | ⬜ NOT RUN |
| CW-11 | `resultRecord` takes the `lockVersion` off the run detail's execution summary instead of the execution's own detail | `TestExecutionSummary` carries no `lockVersion` at all, so this is `versionOf` throwing — a loud failure, which is the right one. Should redden `finds the execution by case key and the status by name`. **Predicted to redden; the row exists because the summary/detail split is easy to miss and the cheap read is the wrong one.** | ⬜ NOT RUN |
| CW-12 | `reviewComment` picks `reviews[0]` instead of the first `IN_REVIEW` one | A comment aimed at a closed review earns a 409 the user cannot act on — and re-submitting opens a *new* record, so the row it aimed at will never accept one. Should redden `refuses a closed review here, rather than earning a 409 from the server`. | ⬜ NOT RUN |

**The gap §137 recorded is now wider and is still the most important thing in this section:**
**no `@tcms/cli` command has been run against a real TCMS**, and there are sixteen write commands
in that state. Every row above mutates code observed only by a stubbed `fetch`. The live pass this
needs is one setup — `docker compose up`, `provision-company`, issue an `Agent` token, `TCMS_URL`
and `TCMS_TOKEN` — after which every read command from §137 and every write command here is one
invocation. It is not a browser lane and not a mutation, so neither queue owns it; it is named in
the handoff as its own outstanding item.

### §140 — the MCP server (`MS-1` … `MS-10`)

**The third batch that is not `./gradlew`**, on `TS-*`'s and `CW-*`'s terms: mutate `packages/`, run
`cd packages && npm run test:run --workspaces`, and name the file in the scope column since the
whole suite is under a second. Assertions live in `mcp/src/session.test.ts` (15),
`mcp/src/reads.test.ts` (11), `mcp/src/writes.test.ts` (27) and `mcp/src/server.test.ts` +
`mcp/src/http.test.ts` (20).

> **NONE RUN.** **`MS-1` first** — it is the row that pins this slice's whole finding, and its
> failure mode is one company's session shape disclosed to another through a field that reads like
> documentation. **`MS-4` second**, because its failure is §6t's enumeration oracle.

| # | Mutation | Expected | Result |
|---|---|---|---|
| MS-1 | `http.ts` hoists `buildServer(session)` out of the request handler into a module-level singleton built from the first session | The finding, defeated. `instructions` is a constructor argument sent once during `initialize`, so every later caller receives the **first** caller's capability summary — their name, email, project key and reach — and a filtered tool list computed from somebody else's permissions. Should redden `gives each caller their own server, with their own capability summary` in `http.test.ts`. **Nothing else would fail: every tool still works and the protocol is still correct.** | ⬜ NOT RUN |
| MS-2 | `session.ts`'s `probe` sends `projectId` to `GET /me/permissions` even when no project resolved | A token that can see three projects reports the PROJECT terms of whichever one it happened to pick, so `tools/list` offers write tools for a project the session is not bound to and every call 404s. The contract is explicit that the two answers are different sets rather than one filtered. Should redden `does not choose between several projects, and says how to bind one`. | ⬜ NOT RUN |
| MS-3 | `session.ts` drops `qualifiers` and keeps only the keys | §65's SPA defect, one client over: a tool held at `OWN` or `PLAN_SCOPE` lists with a description claiming the whole scope, and the model offers an operation the server refuses per row — the refuse-and-retry loop the filtering exists to prevent. Should redden `keeps the qualifier, not only the key` and `lists a narrowly-held tool and says what it is narrowed to`. | ⬜ NOT RUN |
| MS-4 | `server.ts`'s `CallTool` handler answers an unlisted tool with the tool's gate permission instead of "no tool named X" | A hidden tool confirms its own existence, and the permission it names tells the caller what the instance's model looks like — the enumeration §6w.3's hiding removes, restored through the refusal. Should redden `refuses a hidden tool without confirming that it exists`. | ⬜ NOT RUN |
| MS-5 | `result.ts`'s `failed` returns the raw `error.detail` for every status instead of routing through `describeRefusal` | The 404 branch stops being the silent one. The server's own 404 detail can name the thing that was not found, which is the difference between "no such case, or it is not visible" and confirming the row exists to a caller in another company. Should redden `names nothing on the 404 branch` — **the one row here whose failure is a security finding rather than a defect.** | ⬜ NOT RUN |
| MS-6 | `server.ts`'s dispatcher passes `tool.gate.permissions[0]` instead of the resolved arm's permission | A refused edit on `tcms_write_case` reports `case:create`, so the user asks an administrator for a permission they already hold and the grant that would fix it is never requested. Should redden `names the permission of the ARM that ran, not the tool's first gate key`. | ⬜ NOT RUN |
| MS-7 | `writes.ts` replaces `requireExplicitVersion` on the `blocks` arm with the ordinary `versionFor` | §139's finding at the one place it bites here. A `blocks` array is the whole page and its content came from an earlier read, so a fresh version asserts a state that body was never based on: the server accepts, and whoever saved in between loses their paragraphs. Should redden `demands the caller's own lockVersion when replacing blocks`. **The only mutation in this section that destroys authored content.** | ⬜ NOT RUN |
| MS-8 | `writes.ts`'s `recordResult` adds `evidence:attach` to its gate "for symmetry with the spec" | A caller holding `run:execute` and `result:submit` without `evidence:attach` loses the tool entirely — hidden by a permission it never exercises, which is §2's failure arriving through the declaration rather than the merge. Should redden `does not declare evidence:attach, because it cannot attach evidence`. | ⬜ NOT RUN |
| MS-9 | `writes.ts`'s `openReview` picks `reviews[0]` instead of the first `IN_REVIEW` one | `CW-12` one surface over: a verdict aimed at a closed review earns a 409 the caller cannot act on, and re-submitting opens a *new* record, so the row it aimed at will never accept one. Should redden `acts on the OPEN review, not the newest one` and `explains that a closed review cannot be joined`. | ⬜ NOT RUN |
| MS-10 | `instructions.ts`'s `fit` truncates the joined string at `INSTRUCTIONS_BUDGET` instead of dropping whole sections | The summary ends mid-permission-key, and a half-written key is what a model hands the user as the thing to ask an administrator for. Reddens **nothing** — no assertion reads the tail of a truncated summary, only its byte length. **Known gap**, and the reason the section-dropping order is written out in the function's own comment. | ⬜ NOT RUN |

**The gap is three packages wide now.** `MS-*`, `CW-*` and `TS-*` all mutate code observed only by a
stubbed `fetch`. One live setup closes all three — `docker compose up`, `provision-company`, an
`Agent` token, `TCMS_URL`/`TCMS_TOKEN`, then the CLI's commands and an MCP host pointed at
`tcms-mcp --http`. It is neither a browser lane nor a mutation, so neither queue owns it; it is
named in the handoff as its own outstanding item.

### §141 — rate limiting (`RL-1` … `RL-8`)

> **`RL-*` IS A COLLIDING PREFIX — quote these as `§141 RL-n`, never bare.** The other owner is §154 — the readerless rule; see the ID COLLISION banner above for the rule (qualify, never renumber — decided 2026-08-11).

**A mixed batch, and the first one that is.** `§141 RL-1`…`RL-6` are backend rows and run the ordinary
way (`cd backend && ./gradlew test --tests '<scope>'`); `§141 RL-7`…`RL-8` mutate `packages/` and run as
`cd packages && npm run test:run --workspaces`, on `TS-*`'s terms. Assertions live in
`backend/src/test/java/com/tcms/auth/TokenRateLimiterTest.java` (13 across four groups),
`RateLimitTest.java` (4), `ArchitectureRulesTest.java` (the new clock rule) and
`packages/client/src/core/core.test.ts`'s `refusal copy` (2 new).

> **NONE RUN.** **`§141 RL-1` first** — it is the row that pins the slice's finding, and its failure mode
> is a refused caller that obeys the header exactly and is refused again, which is the loop the whole
> slice exists to interrupt. **`§141 RL-3` second**, because its failure is a limit that silently does
> not apply.

| # | Mutation | Expected | Result |
|---|---|---|---|
| §141 RL-1 | `TokenRateLimiter.Bucket` goes back to a fractional `available` accumulator (`available += elapsed × rate`, clamped at `capacity`) | The floating-point defect this slice found by writing the test first: ten increments of `0.1` sum to `0.9999999999999999`, so a caller that waited exactly one second is refused again. Should redden `fractions of a permit accumulate rather than being lost per request`. **The row that pins why the state is a timestamp and not a count** — and note that a *counting* implementation is what almost everybody writes. | ⬜ NOT RUN |
| §141 RL-2 | `Decision.refusedFor` drops the floor and returns `Duration.ofSeconds(wait.toSeconds())` (truncating instead of rounding up) | Two failures in one line: a sub-second wait becomes `Retry-After: 0`, which is an instruction to retry immediately, and a 1.5 s wait becomes 1 s, so a compliant client is refused again through no fault of its own. Should redden `the wait is rounded UP, so a client that obeys it is not refused again` and `and is never zero, whatever the arithmetic says`. | ⬜ NOT RUN |
| §141 RL-3 | `RateLimitFilter` keys on the authenticated JWT subject (`jwt.getSubject()`) instead of `TokenContext.currentOrNull()` | Two changes at once, and both are wrong quietly: a person in the SPA starts being counted (a 429 the SPA has no copy for and no test anywhere expects), and every token issued by one owner shares one bucket, so a person's second CI job is refused by their first. Should redden `a session is never counted, however fast the person clicks` and `a second token is untouched — the bucket is per credential, not per company`. | ⬜ NOT RUN |
| §141 RL-4 | `SecurityConfiguration` moves `rateLimitFilter` to `.addFilterAfter(…, CompanyContextFilter.class)` | The refusal now happens *after* the company is open, so every refused request has established tenancy context for a request that will not be served. Reddens **nothing** — the 429 still has the right status, header and body. **Known gap, and the honest answer is that this ordering is an efficiency and a hygiene property rather than a behavioural one**; what would make it observable is an assertion about `CompanyContext` during a refusal, which is a test about a `ThreadLocal` from outside a filter. Recorded rather than faked. | ⬜ NOT RUN |
| §141 RL-5 | `RateLimitProperties`' compact constructor drops the `burst > requestsPerMinute` check | The configuration that reads as generous and is not: the bucket empties in one batch that takes longer than a minute to earn back, so the first caller of the minute meets a different API from the second. Should redden `a burst larger than one minute of refill is refused at bind time`. | ⬜ NOT RUN |
| §141 RL-6 | `TokenRateLimiter.nowMillis()` goes back to `clock.millis()` | **The slice's second finding, restored.** `TcmsApplication.clock()` is `Clock.tick(…, ofNanos(1000))` and `TickClock.millis()` divides by `tickNanos / 1_000_000` — zero — so this is an `ArithmeticException` on the request path, not an imprecise reading. Should redden all four of `RateLimitTest` **and** `nothing calls Clock.millis() — on this application's clock bean it throws`. **The ArchUnit rule is what makes the next caller find it at build time instead of in production.** | ⬜ NOT RUN |
| §141 RL-7 | `packages/client`'s `describeRefusal` loses its `429` branch, falling back to `default:` | The client half of the finding: the model receives `Could not …: <server prose>` with no structural signal, and an unexplained failure is the one it retries — the loop, produced by the client one refusal later. Should redden `turns a 429 into a wait, taken from Retry-After rather than from the prose` and `does not invent a wait the server did not state`. | ⬜ NOT RUN |
| §141 RL-8 | The `429` branch's fallback becomes `Wait 60 seconds` when `retryAfterMs` is null | An invented number is worse than none precisely because it *will* be obeyed — a caller sleeps a minute the server never asked for, or (with a smaller literal) hammers a limit it was told nothing about. Should redden `does not invent a wait the server did not state`. | ⬜ NOT RUN |

**Still three packages wide, and now four surfaces.** `§141 RL-7`/`RL-8` join `MS-*`, `CW-*` and `TS-*`
in being observed only by a stubbed `fetch` — and `§141 RL-1`…`RL-6` are the first rows in this queue
whose behaviour a live pass would exercise *by accident*, since any looping client meets them. The
same one setup closes all of it.

### §142 — deploying the MCP server (`DP-1` … `DP-8`)

**A different kind of batch, and it needs saying plainly: this slice added NO tests, because
nothing it changed is code a test runner can load.** A Dockerfile, a compose service, a Caddy block
and two workflows are verified by building, running and asking — which is what was done (§142's
section file lists every command and its answer) — but a *repeat* of that verification is a person
at a terminal, not `./gradlew test`.

So each row below names its **observer**: the thing that would catch the mutation without anybody
looking. Where the answer is *none*, the row says so and gives the command that substitutes for it.
That is the honest shape; a row claiming a test that does not exist would be worse than no row.

Run these from a checkout with Docker available. Several need the image built first:

```bash
cd packages && docker build -f mcp/Dockerfile -t tcms-mcp:dev .
```

> **NONE RUN.** **`DP-1` first** — it is the slice's finding, its failure mode is a container that
> starts, reports itself up and answers nothing, and it is the one mutation a reviewer is most
> likely to make on purpose (the Dockerfile's `0.0.0.0` reads like a widening).

| # | Mutation | Expected | Observer | Result |
|---|---|---|---|---|
| DP-1 | `packages/mcp/Dockerfile`'s `CMD` drops `--host 0.0.0.0`, falling back to `BIND_DEFAULT` | The slice's finding. The container starts, logs `listening on 127.0.0.1:8081/mcp`, and every request through the published port is refused — because docker-proxy dials the container's *network* address. `docker run -p 127.0.0.1:18081:8081` then `curl` → connection reset, and the container goes `unhealthy` after ~40 s. | **The HEALTHCHECK** — and therefore the deploy job's mcp wait, which fails the release. This is the row that pays for the probe existing. | ⬜ NOT RUN |
| DP-2 | The `HEALTHCHECK` asserts `r.statusCode===200` instead of `405` | The probe can never pass: nothing on this surface answers 200 without a credential. Container reports `unhealthy` forever with a server that is working. **The inverse mutation is the interesting one** — see `DP-3`. | The healthcheck itself, immediately. | ⬜ NOT RUN |
| DP-3 | `CMD`'s `--path` becomes `/mcp2` while the Caddy block stays `/mcp` | The mismatch the 405 assertion exists for. The container is `unhealthy` (its probe still asks for `/mcp`), and from outside, `/mcp` falls through to the SPA catch-all and answers **200 with `text/html`** — so a host reports a parse error against a server that is fine. | **The HEALTHCHECK catches the container half.** Nothing catches the Caddy half: the box's `/etc/caddy/Caddyfile` is not synced by any pipeline (shanks plan §14). Manual: `curl -sI https://…/mcp` and read the content-type. | ⬜ NOT RUN |
| DP-4 | `packages/mcp/Dockerfile` drops the stray-`node_modules` check, and `mcp/package.json` pins an `openapi-fetch` that conflicts with the client's | npm stops hoisting, puts a private copy under `mcp/node_modules`, and the runtime stage — which copies the root tree alone — ships an image that fails at `import`. | **The check itself**, at build time, with the paths to add to the COPY list. Without it the failure is `ERR_MODULE_NOT_FOUND` at container start. | ⬜ NOT RUN |
| DP-5 | The runtime stage's `ENTRYPOINT` drops tini (`CMD` alone, node as PID 1) | Measured at 10.2 s versus 0.3 s: `docker stop` waits out the full stop timeout and SIGKILLs a process holding live SSE sessions, on every release. Presents as a slow deploy. | **None.** Nothing fails; the deploy is just ten seconds longer each time. Manual: `time docker stop <container>`. | ⬜ NOT RUN |
| DP-6 | `deploy/compose.prod.yaml`'s `TCMS_URL` becomes `${TCMS_BASE_URL}` (the public origin) | Every tool call leaves the box, crosses Funnel — the public internet — and comes back. It *works*, which is the problem: correct answers, a TLS handshake and a public round trip per call, and a second dependency on Funnel for a request that never needed to leave the host. | **None.** Nothing is red and nothing is slow enough to notice at one call. Manual: `docker compose exec mcp node -e "console.log(process.env.TCMS_URL)"`. | ⬜ NOT RUN |
| DP-7 | `deploy/compose.prod.yaml` drops `profiles: [mcp]` | The CX23 — 4 GB, running Postgres, Caddy and the JVM — starts a second runtime it has no Caddy route for, on the next deploy, with nobody deciding to. | **None, and it would look like success**: the container starts and is healthy; only `free -h` on the box shows it. `docker compose --env-file <no COMPOSE_PROFILES> config --services` printing `mcp` is the cheap check. | ⬜ NOT RUN |
| DP-8 | `.github/workflows/ci.yml`'s `lab` job drops `packages` from `needs` (the pre-§142 state) | A red `packages` job — a type error in `@tcms/mcp`, say — no longer stops the deploy, so the box gets an image built from code whose gate is failing. This is the *reason* §142 changed that edge, restored. | **None from a lint.** `actionlint` is happy either way; the edge is a judgement about what is deployed. Manual: break a type in `packages/mcp/src/tools.ts`, push, and confirm `lab` is skipped rather than green. | ⬜ NOT RUN |

**Four of the eight have no observer at all** (`DP-5`…`DP-8`), which is a fair summary of what a
deployment slice is: the parts with an observer are the parts that run *inside* something — the
image's own probe and its own build. Everything expressed as a choice between two working
configurations (which URL, which profile, which `needs` edge) is verified by reading, and stays
verified only for as long as somebody keeps reading. The runbook section is where that reading is
written down.

---

### §144 — the contract's required set, and the check that compares it to the server (`CR-1` … `CR-3`)

**One test, and it is the first thing in this repo that compares `openapi.yaml` to the server's
behaviour rather than to itself.** `TestCaseInput` said `required: [title]` from M0 while
`CaseEdit.validate` refused four, and nothing could see it: `ContractConventionTest` reads the
contract against itself, every endpoint test sends a complete payload, and both generated copies of
`schema.d.ts` faithfully typed three mandatory fields as optional. §143 found it by sending a real
request. `CaseUpdateEndpointTest.contractRequiredMatchesWhatTheServerEnforces` is what would have.

> **NONE RUN.** `CR-1` is the row that matters: it is the original defect, restored.

```bash
cd backend
./gradlew test --tests 'com.tcms.repository.CaseUpdateEndpointTest'
```

| # | Mutation | Expected | Result |
|---|---|---|---|
| CR-1 | `docs/specs/openapi.yaml` — `TestCaseInput.required` back to `[title]` | **The original defect, restored.** `contractRequiredMatchesWhatTheServerEnforces` fails naming `stepFormat`, `priority` and `automationStatus` as enforced-but-undeclared. Nothing else in the class moves — every other test sends a complete payload, which is precisely why none of them ever noticed. | ⬜ NOT RUN |
| CR-2 | `TestCaseInput.required` gains `severity` (a plausible-looking addition — the SPA always sends one) | The other direction: a contract claim the server does not make. The same test fails, this time with `severity` declared and not enforced. A client would be made to send a value nobody asked for, and `TestCase.severity` is nullable precisely because not every team has an opinion. | ⬜ NOT RUN |
| CR-3 | `CaseEdit.validate` drops the `priority == null` check | The server stops enforcing what the contract declares, so the same test fails from the *code* side. This is the row that proves the check is not merely reading the YAML twice. **Watch for over-reddening**: `Validation` has no priority-absent test of its own, so a single failure here means the new test is the only observer of that rule. | ⬜ NOT RUN |

**No row for `core/rewrite.ts`.** It is TypeScript and this queue runs `./gradlew`; its computed
check lives in `packages/client/src/core/core.test.ts`, which derives the projected field list from
the contract so that a field added to `TestCaseInput` fails the build until somebody decides whether
the round-trip carries it.

---

### §145 — the operation nobody served, and the skip that hid it (`SD-1` … `SD-4`)

**The check existed and declined to look.** `ContractParameterBindingTest` has walked the live
`RequestMappingHandlerMapping` against `openapi.yaml` since §49 — and treated "no handler at all" as
*not yet built*, because MSW served the SPA ahead of the API. §107 deleted the mock layer on
2026-08-06 and the skip stayed, covering `GET /projects/{projectId}/suites/{suiteId}`: declared since
M0, generated into both copies of `schema.d.ts`, called by three clients, answered by nothing.
`everyDeclaredOperationHasAHandler` is the check that says so, and it has **no ignore list** on
purpose.

> **`SD-1` IS RUN** — it is how the new check was accepted, and a check whose failure nobody has seen
> is a check nobody has tested. The other three are not run.

```bash
cd backend
./gradlew test --tests 'com.tcms.contract.ContractParameterBindingTest' \
               --tests 'com.tcms.repository.SuiteEndpointTest'
```

| # | Mutation | Expected | Result |
|---|---|---|---|
| SD-1 | Remove `@GetMapping("/{suiteId}")` from `SuiteController.detail` (leaving the method) | **The original defect, restored.** `everyDeclaredOperationHasAHandler` fails naming `GET /projects/{projectId}/suites/{suiteId}`; `everyDeclaredQueryParameterIsBound` beside it stays **green**, which is the point — that test skips exactly this case and is why the defect shipped. | ✅ **RUN 2026-08-09** — failed as stated, sibling green |
| SD-2 | Add a plausible unimplemented operation to `openapi.yaml` (e.g. `delete` on `/projects/{projectId}/suites/{suiteId}/status`) | The check fails naming it. This is the row that proves it guards the contract *generally* rather than having been fitted to one known gap — and it is the shape a contract-first slice produces on purpose, so the failure message must be the one that tells an author to implement or delete rather than to add an exemption. | ⬜ NOT RUN |
| SD-3 | `SuiteService.findOne` drops the `projectId` gate and looks the suite up by id alone | `Detail.aSiblingProjectsSuiteIsNotReachableThroughThisProject` fails. **Nothing else does** — the row is in the caller's own company and satisfies the FK, so every cross-company assertion in the class still passes. Same shape as the tree's own persona-3 row, one endpoint over. | ⬜ NOT RUN |
| SD-4 | `SuiteService.findOne` assembles its own `SuiteView` instead of calling `render` | `Detail.detailAgreesWithTheList` fails on whichever field the copy gets wrong — and if the copy happens to be complete today it passes, which is the *reason* the assertion compares the two documents rather than listing fields: the second definition is only wrong later. | ⬜ NOT RUN |

---

### §146 — one error envelope, and the module that had to exist first (`PR-1` … `PR-3`)

**The container's own failures now answer `Problem`.** Five of them never reach a handler — an
unreadable body, malformed JSON, a wrong `Content-Type`, a wrong method, an unmapped path — and
until this slice they answered Boot's `{timestamp,status,error,path}`, a shape the contract declares
nowhere, on the paths a machine caller hits most. §143 fixed the status; §145's live pass found the
shape; this fixes the shape.

> **`PR-1` IS RUN** — it is how the new assertions were accepted. The other two are not.

```bash
cd backend
./gradlew test --tests 'com.tcms.auth.ErrorDispatchStatusTest' \
               --tests 'com.tcms.architecture.ArchitectureRulesTest'
```

| # | Mutation | Expected | Result |
|---|---|---|---|
| PR-1 | Remove `@Component` from `ProblemErrorAttributes` | **The original defect, restored.** Exactly the three body tests fail (`theBodyIsAProblem`, `theDetailIsCuratedRatherThanForwarded`, `theOtherContainerFailuresAreShapedAlike`); the five status tests and `aControllersOwn404IsStillBodiless` stay **green** — which is the shape of the gap, since those five are what §143 added and they never looked at the body. | ✅ **RUN 2026-08-09** — failed as stated, six green |
| PR-2 | `ProblemErrorAttributes.detailOf` returns `getError(webRequest).getMessage()` instead of the curated sentence | `theDetailIsCuratedRatherThanForwarded` fails on `com.tcms` or `com.fasterxml` appearing in `detail`. The row that matters most for a machine caller: the forwarded message is *longer and more specific*, which is exactly why it looks like an improvement in review. | ⬜ NOT RUN |
| PR-3 | `api/package-info.java` gains `allowedDependencies = {"identity"}` and `Problem` imports anything from it | `ArchitectureRulesTest.apiDependsOnNothing` fails. **Nothing else does** — the edge is legal under the foundation rule (`identity` is itself a foundation module), the Modulith declaration permits it, and the module graph still verifies. That is the whole reason the rule reads the *code* rather than the declaration. | ⬜ NOT RUN |

---

## §149 — an `errors` value is a sentence, not a datum

The 409 that refuses to retire a classification value sent `errors.children = ["3"]` while the team
delete, under the same key and read by the same client code, sent a full sentence. The SPA composed
words around the number in JSX and looked right; `@tcms/cli` printed `children: 3` and `@tcms/mcp`
handed that to a model. Both tests pinned the wrong side — the backend one asserted
`containsExactly("2")` and the SPA one supplied `{ children: ['2'] }` before asserting it back.

```bash
cd backend
./gradlew test --tests 'com.tcms.contract.ErrorBodyPolicyTest' \
               --tests 'com.tcms.project.ClassificationValueWriteEndpointTest' --rerun-tasks
```

> **`--rerun-tasks` is not optional here.** `openapi.yaml` is outside Gradle's input set (§148), so
> a mutation to the contract alone leaves `:test` UP-TO-DATE and the run reports success having
> executed nothing.

| # | Mutation | Expected | Result |
|---|---|---|---|
| EV-1 | `ProjectController.childrenProblem` back to `List.of(Long.toString(blocked.children()))` | **The original defect, restored.** `everyErrorsValueIsASentence` fails naming `project/ProjectController.java`; `aParentIsNotRetiredOutFromUnderItsChildren` fails on the sentence. The other seven `ErrorBodyPolicyTest` tests stay green — the body is present, the status is right, the key is pinned; only the *value* is wrong, which is why §148's three did not see it. | ✅ **RUN 2026-08-09** — the first half, as stated: datum check red, other seven green |
| EV-2 | Any one `ResponseEntity.status(HttpStatus.CONFLICT).body(…)` → `.build()` (e.g. `SuiteController`'s `NotEmpty`) | `noHandlerOmitsABodyOnABodiedStatus` fails naming that file and line. Nothing else does: the contract still declares the body, and no test drives that arm's payload. | ✅ **RUN 2026-08-09** — failed as stated |
| EV-3 | Rewrite one 409 as `ResponseEntity.status(409).body(…)` | `noBackDoorSpellingOfABodiedStatus` fails. **`noHandlerOmitsABodyOnABodiedStatus` stays green while its `sites` count silently drops by one** — which is the entire reason the ban exists rather than being left as the scan's hole. | ⬜ NOT RUN |
| EV-4 | Delete `content` from `components/responses/TooManyRequests` | `bodiedStatusesDeclareContent` fails. **Nothing else does, on either stack** — no operation references that component, so the operation walk never reaches it and both generated clients keep their own hand-written 429 types. | ⬜ NOT RUN |

---

## §151 — the release sort key's precedence flag, and the fix that measures as a no-op

`ReleaseSortKey` appended a pre-release suffix straight after the padded triple, so a release's key
was a strict **prefix** of its own release candidate's and the candidate sorted as the newer of the
two — §150's finding 4a. `V44` backfills the flag onto three tables under `FORCE ROW LEVEL
SECURITY`, and `ReleaseSortKeyBackfillTest` executes **the shipped migration file itself** rather
than a copy of its `UPDATE`.

```bash
cd backend
./gradlew test --tests 'com.tcms.execution.ReleaseSortKeyBackfillTest' \
               --tests 'com.tcms.execution.ReleaseSortKeyTest'
```

| # | Mutation | Expected | Result |
|---|---|---|---|
| RK-1 | `V44`'s flag becomes a `~` sentinel: `THEN '' ELSE '~'` — i.e. **the standard fix for this exact problem**, applied faithfully | `theCandidateSortsBeforeItsRelease` fails, on a database, with the RC still ordering last; `everyRowMatchesTheApplication` and `theLoopReachesEveryCompany` fail with it. **The point of the row is that this mutation is not a mistake anybody would catch in review** — it is what Debian does, it passes under `C` and under ICU, and it is a no-op only under the libc `en_US` collation this deployment actually runs. | ✅ **RUN 2026-08-09** — three red exactly as stated |
| RK-2 | Unanchor `V44`'s predicate: drop the `([-+].*)?$` guard, leaving a bare padded-triple prefix match | `theTextFallbackIsUntouched` fails on `12345.12345.12345 hotfix` — a value that was **correct before the migration ran** and is corrupted by it. `everyRowMatchesTheApplication` and `runningItTwiceChangesNothing` fail with it, the latter because an unanchored predicate also matches its own output. | ✅ **RUN 2026-08-09** — three red exactly as stated |
| RK-3 | Delete the per-company `set_config` loop from `V44`, leaving the three `UPDATE`s at the top level | Every `Rewrite` and `Collation` test fails, and **the migration itself still reports success** — `FORCE ROW LEVEL SECURITY` covers the owner Flyway connects as, so the naive spelling is a clean run that changes nothing on every table for every company. `withoutContextTheUpdateIsASilentNoOp` is the assertion that names the mechanism. | ⬜ NOT RUN |
| RK-4 | Add a fourth table with a `release_sort_key` column in a later migration and do not name it in `V44` | `everyTableWithTheColumnIsCovered` fails naming it. Nothing else does — the new table simply carries keys in the old format for ever, ordering its releases backwards, which is the shape this computed check exists to refuse. | ⬜ NOT RUN — needs a table that does not exist yet |

> **No browser-queue row.** Nothing renders and no request differs: the column is written on three
> tables, carries three indexes, and **is ordered by nothing in the application** — `releaseVersion`
> is an exact-match filter in every operation that takes it. See §151 for why that is the finding
> rather than a footnote.

---

## §152 — the seams nobody calls

`ArchitectureRulesTest.everyDeclaredRepositoryMethodHasACaller` computes the tally §121, §116,
§117, §132 and §151 each kept by hand: a repository method with no caller anywhere is an unverified
claim, and its javadoc is evidence nothing has ever checked. Ten were found and deleted.

```bash
cd backend
./gradlew test --tests 'com.tcms.architecture.ArchitectureRulesTest'
```

| # | Mutation | Expected | Result |
|---|---|---|---|
| SC-1 | Restore any one of the ten deleted methods — e.g. `List<Team> findByParentTeamId(UUID)` on `TeamRepository` — with no caller | `everyDeclaredRepositoryMethodHasACaller` fails naming exactly that method. **Nothing else does, on any stack**: the interface compiles, Spring Data implements it, the context boots, and every one of the other 2401 tests stays green. That gap is the whole reason the rule exists rather than being a review habit. | ✅ **RUN 2026-08-09** — the pre-fix tree is this mutation ten times over; the rule went red naming all ten and no other test moved |
| SC-2 | Narrow the rule to `getAccessesToSelf()` alone, dropping `getReferencesToSelf()` | The rule fails on `TestCaseRepository.existsByAutomationKey` and the two `RoleAssignmentRepository` projection accessors — **live code, called by method reference**, which a bytecode scan that reads only invocations cannot see. Following its advice would delete a working call path. | ✅ **RUN 2026-08-09** — the opening hand-grep was this mutation, and it flagged `existsByAutomationKey` exactly as predicted |
| SC-3 | Point the rule at `PRODUCTION` instead of `PRODUCTION_AND_TESTS` | Three methods whose only reader is a test fail — `AuditEventRepository.findByActionOrderByOccurredAtDesc`, `TeamMembershipRepository.findByTeamId`, `InstanceAuditEventRepository.findByCompanyIdOrderByOccurredAtDesc`. The audit trail has no screen, so deleting the first would remove the only reader that exists and blind three endpoint tests to what they actually wrote. | ⬜ NOT RUN |
| SC-4 | Rename a repository interface so it no longer ends in `Repository` | The rule silently stops examining it and **stays green** — the `declared > 100` precondition is what keeps the whole rule from passing vacuously, not what protects one interface. Recorded rather than closed: the naming convention is the scope, and a repository not called `*Repository` is a different finding. | ⬜ NOT RUN |

> **No browser-queue row.** Ten methods were deleted and none had a caller, so no request, response
> or screen can differ — that is the premise the deletions rest on, and `./gradlew build` is where
> it is checked.

---

## §153 — the seams only a test exercises

`ArchitectureRulesTest.everyPublicBeanMethodIsExercisedInProduction` asks §152's question from the
other side: not *has this seam a caller*, but *is every caller a test*. It pins an exact population
(`UNEXERCISED_BY_DECISION`) rather than asserting emptiness, so it fails in **both** directions — a
new unexercised seam, and the disappearance of one whose argument is written down.

```bash
cd backend
./gradlew test --tests 'com.tcms.architecture.ArchitectureRulesTest' \
               --tests 'com.tcms.provisioning.OperatorCommandsTest'
```

| # | Mutation | Expected | Result |
|---|---|---|---|
| UX-1 | Restore `ProvisionCompanyCommand.isRequestedBy` with its old javadoc, calling it from nothing | `everyPublicBeanMethodIsExercisedInProduction` fails naming it as an unexpected member. **Nothing else does** — it compiles, the CLI works, and the method's own test (had it been restored too) passes, which is exactly how the seam survived being superseded. | ✅ **RUN 2026-08-09** — the pre-fix tree is this mutation; the rule went red naming `ProvisionCompanyCommand.isRequestedBy` and nothing else moved |
| UX-2 | Delete `Outbox.record` and leave `UNEXERCISED_BY_DECISION` naming it | The rule fails on the *missing* element rather than an extra one. This is the direction an ignore list cannot express, and it is what stops the argument at `Outbox#record` from outliving the code it argues about — §152's `countLiveCasesBySuite` in reverse. | ⬜ NOT RUN |
| UX-3 | Make one `@Scheduled` method public — e.g. `OutboxScheduler.dispatchAllCompanies` | The rule fails naming it, because the container is its only real caller and bytecode cannot see that. **This is a false positive by construction and the row exists to bound the rule's claim**: the `public` narrowing works here only because all four `@Scheduled` methods in this codebase are package-private, which is a property of this codebase and not of Java. If that ever stops being true, the rule needs an annotation-based exclusion rather than another argued entry. | ⬜ NOT RUN |
| UX-4 | Drop a verb from `OperatorCommands.ALL` — e.g. `GRANT_INSTANCE_ADMIN` | `OperatorCommandsTest.everyVerb` fails naming that verb. Nothing else does: the command bean still runs, does its work correctly, and then **never exits**, because the jar was never switched to non-web. The failure is a hang an operator meets at 2am, and before this slice no test observed the guard at all. | ⬜ NOT RUN |

> **No browser-queue row.** One method was deleted (uncalled), one test moved to the method it was
> always about, and four javadocs were corrected. No request, response or screen differs.

---

> ### ⚠ ID COLLISION — THREE PREFIXES ARE MINTED TWICE. **RESOLVED BY QUALIFICATION, 2026-08-11.**
>
> **THE RULE: AN ID IN A COLLIDING RANGE IS WRITTEN `§NNN XX-n` AND NEVER BARE.** Decided
> 2026-08-11 at the user's direction, closing §161's item 2 of the §154 redo. **No id was
> renumbered** — the working agreement says an id a handoff section has quoted is never renumbered,
> and both owners of every range below have quoted theirs. The `id` column of all six affected
> tables now carries its section, so a mark resolves by reading. **This is the form to use when
> quoting one from anywhere in the repo**, and the qualifier is *only* required for the three
> ranges below; every other prefix in this file is minted once and stays bare.
>
> **A new block must not take a prefix this file already uses.** Grep before minting one. The
> collision was free to happen because two lanes each picked the obvious initials for their slice —
> `AG` for *aggregation* and again for *agent*, `CD` for *case draft* and again for *condition
> drift* — which is the fixture-UUID-prefix failure (§19 … §62) in another namespace.
>
> Recorded 2026-08-10 (§161). **Three id ranges are minted twice in this file**, so an id quoted
> from a handoff section did not resolve to one row:
>
> | id range | first owner | second owner |
> |---|---|---|
> | `AG-1`…`AG-4` | §124 — the `BEST` removal | §133 — the `Agent` role |
> | `CD-1`…`CD-7` | §128 — the case-draft buffer | §158 — condition drift |
> | `RL-1`…`RL-7` | §141 — rate limiting | §154 — the readerless rule |
>
> **This is not cosmetic.** Sections quote these ids as their record of what was verified — §158
> says *"`CD-1`, `CD-2`, `CD-3` **run**"* and §154 says *"`RL-1` and `RL-2` run"* — so a reader
> checking whether a row ran has two candidates and no way to choose from the text. §156's own
> block below says *"`RL-1`…`RL-6` above"*, and there are two blocks above it.
>
> ~~**Not renumbered here on purpose.** … a decision, not a cleanup, and it belongs to the §154
> redo.~~ → **DECIDED 2026-08-11: qualify, do not renumber.** The alternative was renaming one side
> of each range and back-patching every section that quotes it; qualification was chosen because it
> changes no id's meaning, touches no handoff text that has already been read, and leaves each
> range's two owners equal rather than making the later one wrong for arriving second. The six
> tables carry the qualifier; the sections that quote bare ids are **not** back-patched, and this
> banner is what makes them resolvable.

## §154 — the columns nothing reads

> **`RL-*` IS A COLLIDING PREFIX — quote these as `§154 RL-n`, never bare.** The other owner is §141 — rate limiting; see the ID COLLISION banner above for the rule (qualify, never renumber — decided 2026-08-11).

`ArchitectureRulesTest.everyMappedColumnHasAReader` asks the third question in §152's and §153's
family, at the column: *which mapped column does nothing read back*. It is the only rule in that
file that reads **source text** rather than bytecode, because a column's reader is usually a query
and a query is a string — over bytecode alone the population is 212 of 635 fields, which was
measured before the rule was written. It pins an exact population (`READERLESS_BY_DECISION`) like
§153's, so it fails in **both** directions.

> **THE POPULATION IS TWELVE, NOT THE THIRTEEN §154 SHIPPED** (checked 2026-08-11).
> `IngestionJob.rawStorageKey` left the map when §156 found it writerless as well and §160 built it
> a store — so **`§154 RL-1` no longer works as written**: deleting *that* entry deletes nothing.
> Use any surviving member; `AppUser.locale` is the closest equivalent. The row is left as it was
> because it has already RUN and a run row is a record, not an instruction.
>
> **`§154 RL-4` is the row to run first** and it is not close: it is the only property of this rule
> that nothing has verified, and it is the finding the slice is named for. Not run inline on
> 2026-08-11 when this block was last edited — that is §65, and §72–§76 are what taking the
> exception looks like.

```bash
cd backend
./gradlew test --tests 'com.tcms.architecture.ArchitectureRulesTest'
```

| # | Mutation | Expected | Result |
|---|---|---|---|
| §154 RL-1 | Delete one entry from `READERLESS_BY_DECISION` — e.g. `IngestionJob.rawStorageKey` — leaving the column as it is | The rule fails naming it as an **unexpected** member. The column is still readerless; only the argument for keeping it went away. | ✅ **RUN 2026-08-09** — red naming `IngestionJob.rawStorageKey`, nothing else moved |
| §154 RL-2 | Give a pinned column a reader without removing its entry — add `List<SuiteCase> findByAddedBy(UUID)` to `SuiteCaseRepository` | The rule fails on the **missing** element `SuiteCase.addedBy`. This is the direction an ignore list cannot express: an argument for a readerless column cannot outlive the column being read. | ✅ **RUN 2026-08-09** — red on the missing element; reverted after |
| §154 RL-3 | Restore the corpus to `src/main` only, dropping `src/test` | The rule reports columns whose only reader is a test's `SELECT` — `AuditEvent.actorTokenId` is the instance, which §132 spent a whole slice giving a writer and which `IngestionEndpointTest` selects to assert token attribution. **This bounds §152's widening**: a test that reads a column to check what a write left behind is that column's reader. | ⬜ NOT RUN |
| §154 RL-4 | Stop excluding `ArchitectureRulesTest.java` from its own corpus (`OWN_FILE`) | **Five members vanish and the rule goes green while the defect stands.** Every key is the string `"Entity.field"`, so each argument for keeping a column is itself a literal naming that column. This is the rule's own most dangerous failure mode and the reason the measured population went 7 → 13. | ⬜ NOT RUN |
| §154 RL-5 | Stop stripping comments from the corpus | Nineteen columns read as live because some javadoc mentions them, and the population collapses to 7. In a repository whose comments carry the reasoning, prose standing in for code is §152's exact finding — the rule must not accept it as a reader. | ⬜ NOT RUN |
| §154 RL-6 | Stop dropping `INSERT` statements from the corpus | Seven columns read as live off the fixture rows that *create* them. An insert's column list is a write; a fixture inserting `added_by` is not a reader of `added_by`. | ⬜ NOT RUN |
| §154 RL-7 | Add `@Version` to `TestRun.lockVersion` (the recorded next-slice candidate, **not done here**) | Two concurrent updates to one run answer 409 through `RunController.onWriteRace`, which already exists and has never been reachable. Today the column is `NOT NULL DEFAULT 0`, mapped as a plain column, never incremented and never read — while `RunController` itself cites ERD §5 that "a run is worked by several testers at once by design". `work_item.lock_version` is the same shape. ~~**This row is the check for a slice that has not been written.**~~ → **SUPERSEDED 2026-08-09 by §155's `LV-1`…`LV-6` below.** The slice was written and the answer was no: `@Version` here makes the product's most-used write a contention point (`TestPlan.lockVersion`'s header said so before §154 proposed it), and the run's real lost update is closed by a conditional `UPDATE` and `@DynamicUpdate`. Both columns are unmapped now, so this mutation no longer describes a reachable state. | ⛔ SUPERSEDED |

> **No browser-queue row.** No production behaviour changed: one test rule was added and thirteen
> javadocs were corrected at the fields they describe. No request, response or screen differs.

---

### §155 — the guard two entities mapped and neither maintained (`LV-1` … `LV-6`)

**`§154 RL-7` above is superseded by this block and is marked so there.** It proposed adding `@Version`
to `TestRun`; the audit said no on both entities and closed the run's actual race with a conditional
`UPDATE` and `@DynamicUpdate` instead. These rows check what shipped.

```bash
cd backend
./gradlew test --tests 'com.tcms.architecture.ArchitectureRulesTest'   # LV-3, LV-4
./gradlew test --tests 'com.tcms.execution.ExecutionEndpointTest'      # LV-1, LV-2, LV-5
./gradlew test --tests 'com.tcms.execution.IngestionEndpointTest'      # LV-6
```

| # | Mutation | Expected | Result |
|---|---|---|---|
| LV-1 | Remove `@DynamicUpdate` from `TestRun` | The mirror lost update returns: a close loaded before the first result lands writes its own null `started_at` back over the row. Should redden `ending a run does not write started_at — the mirror, and what @DynamicUpdate buys` **and nothing else** — the annotation changes no other observable behaviour, which is the whole reason it is affordable. | ✅ **RUN 2026-08-09** — red on exactly that one test, 83 run / 1 failed; reverted |
| LV-2 | Drop `AND r.status = …NOT_STARTED` from `startIfNotStarted`'s `WHERE` clause | The statement becomes unconditional, so every result re-stamps the start and a closed run is reopened by a late one. Predicted: the two §155 tests **plus** the two §72 idempotence tests, because those assert the same property through the endpoint. | ✅ **RUN 2026-08-09** — **MORE, and the right more**: 4 red — `a run closed while a result was in flight is NOT put back to IN_PROGRESS`, `the start answers 1 once and 0 for ever after`, `a second result does not re-start it, and writes no second run.started`, `recording in an already-running run leaves its start alone`; reverted |
| LV-3 | Map `lock_version` again on `WorkItem` (field + `@Column`, no `@Version`) | `everyLockVersionColumnIsAVersion` fails naming `WorkItem.lockVersion`. This is the state §74 left two entities in for eighty slices, and the rule exists so it cannot be re-entered by accident. | ⬜ NOT RUN |
| LV-4 | Delete `@Version` from `TestPlan.lockVersion`, leaving the mapping | The rule fails naming `TestPlan.lockVersion` — **the expensive direction.** That column is `required` in the contract and `PATCH /plans/{planId}` compares an `If-Match` against it, so this mutation ships a precondition that cannot fail. `PlanUpdateEndpointTest`'s stale-header test should redden too; **if it does not, that test is asserting something other than what it names.** | ⬜ NOT RUN |
| LV-5 | `ExecutionService` writes the `run.started` audit row unconditionally instead of on a return of 1 | Every result claims to have started the run. Should redden `a second result does not re-start it, and writes no second run.started` and `recording in an already-running run leaves its start alone` — the two that count audit rows. **The row that pins why the statement returns an int at all.** | ⬜ NOT RUN |
| LV-6 | `IngestionService` passes the token owner's id instead of `null` as `actorId` to `startIfNotStarted` | A machine's start is attributed to a person, which is the attribution `TestExecution#record` refuses on the verdict itself. Predicted **zero** reds — nothing asserts `updated_by` on an ingested run's start, and the honest deliverable of this row is that measurement. A red here would mean the ingestion suite is stricter than it reads. | ⬜ NOT RUN |

> **No browser-queue row.** Neither column was ever on the wire, no contract changed, and the race
> the fixes close cannot be provoked from one browser tab — it needs two requests overlapping inside
> one row's write window, which the browser queue's lanes cannot express.

---

### §156 — the columns nothing writes (`WL-1` … `WL-7`)

`everyMappedColumnHasAWriter` is `everyMappedColumnHasAReader`'s mirror, so `§154 RL-1`…`RL-6` above are
the shape these follow. What is different is the **direction the corpus fails in**: an over-matching
reader corpus costs a finding it will catch next time, an over-matching *writer* corpus hides one
permanently. `WL-3` is the row for the instance that actually happened.

```bash
cd backend
./gradlew test --tests 'com.tcms.architecture.ArchitectureRulesTest'   # every row here
```

| # | Mutation | Expected | Result |
|---|---|---|---|
| `WL-1` | Delete one entry from `WRITERLESS_BY_DECISION` — e.g. `Project.archivedAt` — leaving the field as it is | The rule fails naming it as an **unexpected** member. Nothing about the column changed; only the argument for keeping it went away. | ⬜ NOT RUN |
| `WL-2` | Give a pinned column a writer without removing its entry — call `Project.archive(now)` from `ProjectService` | The rule fails on the **missing** element `Project.archivedAt`, which is the direction an ignore list cannot express. Note what else this mutation shows: nothing else in the suite reddens, because no test asserts a project can be archived. | ⬜ NOT RUN |
| `WL-3` | Restore the first draft's corpus — `writesIn(literalsOf(source))` in place of `writesInLiterals(source)` | **Two members disappear and the rule goes green.** `CaseHealth.unlinked` and `IngestionJob.error` read as written because `DocPageEndpointTest`'s `"UPDATE key_sequence SET next_value = 1"` has no terminator, so `[^;]*` swallows every later literal in that file and an `@DisplayName` containing the word "unlinked" stands in for a writer. **The most important row in this block**: it is the rule's own failure mode, and it fails silently in the direction that hides findings. | ⬜ NOT RUN |
| `WL-4` | Drop the SQL arm — keep `isWrittenInJava` and delete both `namedIn` calls | Every column written only by native SQL is reported: the five `case_health` signals that *do* have a writer (`never_executed`, `long_unexecuted`, `stale_automation`, `undeclared_automation`, `failing_repeatedly`), `computed_at`, and the `test_run` columns `startIfNotStarted` sets in JPQL. Bounds the claim that bytecode alone is the wrong observer here — the mirror of §154's measured "212 of 635". | ⬜ NOT RUN |
| `WL-5` | Drop the field-name arm alone (`namedIn(writeText, null, field.getName())`), keeping the column-name one | The JPQL writers go: `startIfNotStarted` names `r.startedAt`, `V1` names `started_at`, and only the field-name arm connects the two. This is the row that says why there are two spellings rather than one. | ⬜ NOT RUN |
| `WL-6` | Drop `SQL_ROOTS` — the `.sql` corpus — keeping the Java one | A column written only by a migration is reported. `V44`'s backfill is the worked example, and the general point is that a migration that *backfills* is a writer while one that only creates the column is not, because `CREATE TABLE` is neither verb. | ⬜ NOT RUN |
| `WL-7` | Count a self-write in an uncalled method as a writer — drop the `origin.getAccessesToSelf().isEmpty()` check in `isWrittenInJava` | `Project.archivedAt` disappears, because `archive(now)` assigns it and nothing calls `archive`. That one line is the whole difference between "this column has a mutator" and "this column has ever held a value", and it is the reason the most expensive member of the population is in it. | ⬜ NOT RUN |

> **One browser-queue row, and only one.** The rule changed no production behaviour and the
> thirteen javadocs it corrected change nothing a request can see. What *does* differ on screen is
> the dashboard's health panel sentence, which is `HP-1` in the browser queue — and the reason it
> is a browser row rather than a mutation row is that the defect was in what the sentence *claims*,
> which no assertion about the response body can observe.

---

### §157 — the stale-approval derivation (`SA-1` … `SA-6`)

The first of §156's five writerless signals to get a computation, so these rows are unlike the
`RL-*`/`WL-*` blocks above them: those mutate a **rule** and predict which entries a tally loses,
these mutate a **statement** and predict which cases change their answer. The population under test
is one `INSERT … ON CONFLICT` in `repository/ApprovalFacts.java`.

```bash
cd backend
./gradlew test --tests 'com.tcms.repository.CaseHealthRecomputeTest'   # every row here
```

| # | Mutation | Expected | Result |
|---|---|---|---|
| `SA-1` | Drop `AND r.state = 'APPROVED'` from the lateral | A refused review reads as an aged sign-off. Should redden `a REJECTED review at an old version is not a stale approval` **and nothing else.** | ✅ **RUN 2026-08-09** — red on exactly that one test; reverted |
| `SA-2` | `max(r.subject_version_no)` → `min(...)`, or an `ORDER BY r.approved_at DESC LIMIT 1` | A case approved at v1, edited, and approved again at v3 reports stale while sitting on v3. Should redden `the HIGHEST approved version decides, not the latest sign-off`. The `ORDER BY` spelling is the one worth measuring, because it is the reading somebody will propose as more natural. | ⬜ NOT RUN |
| `SA-3` | Move `c.lifecycle_status = 'APPROVED'` out of the `SELECT` expression and into the `WHERE` | The statement becomes a filter, so a row already flagged stale and then demoted keeps the flag for ever. Should redden `a stale flag left behind by an earlier tick is cleared, not merely not set` and `a case sent back to DRAFT is not stale, even with the old approval on record`. **The expensive direction**: it is a signal with no route back, which is `HealthSignal`'s no-button-for-lying rule failing from the other side. | ⬜ NOT RUN |
| `SA-4` | Drop `c.lifecycle_status = 'APPROVED'` entirely | Every case whose review history is behind its current version reports stale regardless of whether it claims to be approved. Should redden `a case sent back to DRAFT is not stale, even with the old approval on record`. | ⬜ NOT RUN |
| `SA-5` | Replace the version comparison with the timestamp one — `c.updated_at > approved.at` | Predicted **zero** reds, and that is the deliverable: this fixture cannot tell the two apart, because the case that separates them is a save whose content hash is unchanged (no new version, `updated_at` moved). Writing the row is what records that the argument at the method is unasserted. A red here would mean the fixture is stricter than it reads. | ⬜ NOT RUN |
| `SA-6` | Add `stale_approval` to `ExecutionFacts.recomputeAutomationDrift`'s `ON CONFLICT` update list | The last statement in the tick blanks the third one's work. Should redden `APPROVED at a version the case has since moved past is STALE_APPROVAL` — and it is the concrete instance of the hazard `signals this job does not compute survive a recompute` has guarded against hypothetically since §53. | ⬜ NOT RUN |

> **One browser-queue row block, `SA-B1`…`SA-B3`**, in lane 4 beside `HP-1`…`HP-3`. Unlike §156's,
> these need a fixture the pass must author — a project with §6g review switched **on** for cases,
> a case taken to `APPROVED`, and an edit after it — because the signal cannot exist without a
> review row and `ReviewService.moved` opens none for a project with the default settings.

---

### §158 — the condition-drift derivation (`CD-1` … `CD-7`)

> **`CD-*` IS A COLLIDING PREFIX — quote these as `§158 CD-n`, never bare.** The other owner is §128 — the case-draft buffer; see the ID COLLISION banner above for the rule (qualify, never renumber — decided 2026-08-11).

The second of §156's five to get a computation, and the same kind of block as `SA-*`: these mutate a
**statement** and predict which cases change their answer. The population under test is one
`INSERT … ON CONFLICT` in `repository/ConditionDrift.java`.

```bash
cd backend
./gradlew test --tests 'com.tcms.repository.CaseHealthRecomputeTest'   # every row here
```

> **Three of these were run during the slice, which the policy at the top of this file says should
> be none.** Recorded as run rather than quietly re-listed as pending: §157 did the same for one row
> and the honest count across the two slices is four. They were run because each measures a
> *decision the statement had to take* — join type, ordering, and which fields are content — and a
> statement whose competing spelling is never measured is a paragraph of prose, which is §152's
> lesson. The other four are queued.

| # | Mutation | Expected | Result |
|---|---|---|---|
| §158 `CD-1` | `LEFT JOIN condition lib` → `JOIN condition lib` | A soft-deleted entry stops being comparable, so the case that lost a step outright reports clean. Should redden `a soft-deleted entry is drift, and it is the case an inner join would miss` **and nothing else.** | ✅ **RUN 2026-08-09** — red on exactly that one test; reverted |
| §158 `CD-2` | `ORDER BY v.version_no DESC` → `ASC` | The oldest snapshot decides, so a case brought up to date still reports drift. Should redden `the NEWEST snapshot decides, not any snapshot` **and nothing else.** | ✅ **RUN 2026-08-09** — red on exactly that one test; reverted |
| §158 `CD-3` | Drop the `lib.title IS DISTINCT FROM …` term | A rename with unchanged steps reads clean. Should redden `a rename with the same steps is drift too` **and nothing else.** | ✅ **RUN 2026-08-09** — red on exactly that one test; reverted |
| §158 `CD-4` | Drop the `postconditions` half of the `||` | Only the PRE arm is compared. Should redden `the postcondition arm counts, not only the preconditions`. Worth measuring rather than assuming, because the two arms are concatenated in one expression and an edit that "simplifies" it drops the second silently. | ⬜ NOT RUN |
| §158 `CD-5` | `coalesce(drift.drifted, false)` → `drift.drifted` | `bool_or` over zero rows is NULL and the column is `NOT NULL`, so this is not a wrong answer but an **aborted transaction**, taking the other three statements' work with it. Should redden `a snapshot with no conditions raises nothing rather than failing`, `a case with no version at all has nothing to have drifted from`, and — because the whole tick rolls back — most of the class. **The row exists to measure how far the blast radius reaches**, since the failure mode is a job that stops rather than one that lies. | ⬜ NOT RUN |
| §158 `CD-6` | Replace the content comparison with the timestamp one — `lib.updated_at > newest.created_at` | Predicted **some** reds (`an entry still saying what the snapshot recorded raises nothing`, since every fixture condition is written after its case's version row) but **not** the one that matters: nothing here expresses "edited and changed straight back", which is the state where the timestamp spelling can never heal. **The deliverable is the gap**, and closing it is a fixture that writes a version, bumps `condition.updated_at`, and restores the body. | ⬜ NOT RUN |
| §158 `CD-7` | Add `condition_drift` to `ApprovalFacts.recomputeStaleApproval`'s `ON CONFLICT` update list | The third statement in the tick blanks the fourth's — or rather, being third, is blanked *by* it, so the mutation that shows the hazard is the one on the **later** statement. Should redden `an entry edited since the snapshot drifts the case that references it`. `SA-6`'s row for the other pair; the column-ownership rule now binds four statements and the number of ways to break it grows with it. | ⬜ NOT RUN |

> **One browser-queue row block, `CD-B1`…`CD-B3`**, in lane 4 beside `HP-1`…`HP-3` and
> `SA-B1`…`SA-B3`. Its fixture is the **cheapest of the three blocks** and worth saying so: no
> review settings, no second account, no approval — attach a library condition to a case, save the
> case so a version is written, then edit the condition from the library screen. Everything this
> signal needs is one author with `library:manage`.

## §159 — the project archive operation

Run with:

```bash
./gradlew test --tests 'com.tcms.project.ProjectEndpointTest'      # every row here
./gradlew test --tests 'com.tcms.architecture.ArchitectureRulesTest'   # AR-M5 only
```

**None of these were run during the slice.** The policy at the top of this file says none should be,
and §157 and §158 between them ran four; this block restores the queue's own rule.

| # | Mutation | Expected | Result |
|---|---|---|---|
| `AR-M1` | In `ProjectArchiveService.apply`, delete the `project.isArchived() == target` early return | Every request writes: the timestamp is re-stamped on an already-archived project and a second audit row is written. Should redden `setting the state a project already has writes nothing at all` **and nothing else** — which is the row that says the idempotence is in the service and not an accident of the gate. | ⬜ NOT RUN |
| `AR-M2` | `PROJECT_SETTINGS` → `"project:read"` | Every visible caller may retire a project. Should redden `a Test Engineer is refused with 403, and nothing moves` **and nothing else.** The narrow blast radius is the point: it demonstrates that the 404-over-403 rows are answering the *visibility* gate rather than passing because the permission happened to be missing too. | ⬜ NOT RUN |
| `AR-M3` | In the controller's switch, answer `Unreachable` with 403 instead of 404 | Should redden `a project the caller cannot see is 404, never 403` — **both arms**, the no-grant one and the other company's. Worth measuring rather than assuming, because the two reach the same arm by different routes and a mutation that reddens only one would mean the second assertion is decorative. | ⬜ NOT RUN |
| `AR-M4` | In `ProjectArchiveService.set`, treat a null `archived` as `false` instead of a field error | A malformed body silently un-archives. Should redden `an absent archived field is a 400 on the field, not a silent un-archive`. **This is the mutation a reviewer would call a simplification** — `Boolean.TRUE.equals(request.archived())` reads as tidier than the null check and is exactly the defect `ProjectSettingsRequest` documents for `requiredPeerReviews`. | ⬜ NOT RUN |
| `AR-M5` | Re-add `Project.archivedAt` to `ArchitectureRulesTest.WRITERLESS_BY_DECISION` | The population is eleven and the map says twelve. Should redden `every mapped column has a writer — the writerless tally, computed`. It measures the rule from the direction that matters now: the column has a writer, so the by-decision entry is the thing that would be wrong, and the rule must say so rather than tolerating a stale entry. | ⬜ NOT RUN |
| `AR-M6` | Have `ProjectArchiveService` return `projects.viewOfCreated(project, userId)` instead of `viewOfChanged` | `caseCount` is 0 on the response and correct on the next `GET`. Should redden `the response is the same shape the list and the detail serve` **and nothing else**. The two assemblers differ in exactly one thing and it is the field a client renders straight onto the card it just acted on. | ⬜ NOT RUN |

> **Six browser rows, `AR-1`…`AR-6`**, in lane **2 (`A0`)** rather than lane 4: this block needs no
> scheduler tick, no review row and no second account except for `AR-5`/`AR-6`. `AR-3` — an archived
> project still accepts writes — is the one that observes a decision rather than a rendering, and
> `AR-4` is the only place the no-op's *silence* (no audit row, no re-dated timestamp) is visible
> outside a test.

## §160 — the verbatim CI upload

*Six mutations. `RR-M1` is the one worth running first: it is the defect the whole slice exists to
prevent, it produces a perfectly valid file, and the only assertion that catches it is a byte
comparison against what was posted.*

| # | Mutation | Expected | Result |
|---|---|---|---|
| `RR-M1` | In `IngestionService.completed`, store `payload(matches, recorded)` instead of `request.report()` | The download serves the server's *reading* of the report — valid JSON, plausible content, and not what CI sent. Should redden `the bytes come back EXACTLY as posted, not the parsed payload` **and nothing else.** This is the mutation that proves the test asserts byte identity rather than "a file came back", and it is the defect a reviewer is least likely to spot because both columns are called `raw`. | ⬜ NOT RUN |
| `RR-M2` | In `IngestionReadService.locate`, delete the `.filter(job -> job.getRawStorageKey() != null)` | A job with no stored upload reaches `rawReports.open(null)`. Should redden `a job whose key is null — every row posted before §160 — is the same 404` — and the **manner** matters more than the redness: a `NullPointerException` surfacing as a 500 is a different failure from a 404, and it is what every historical row in an upgraded instance would meet. Record which it is. | ⬜ NOT RUN |
| `RR-M3` | In `FilesystemRawReportStore`, construct `FilesystemBlobStore` with `"${tcms.storage.reports.root}"` | `CA-20`'s shape at the new root: CI uploads land in the regenerable report root, which has different retention and is the one an operator is told is safe to lose. **Should redden nothing in the suite** — `IntegrationTestBase` points all three at temp directories and the tests never compare roots — which is exactly why it is here and why `RR-7` exists in the browser lane. | ⬜ NOT RUN |
| `RR-M4` | In `FilesystemRawReportStore`, pass the label `"report"` instead of `"raw report"` | An operator reading a storage failure is told the regenerable root failed when the irreproducible one did. Should redden `raw reports — the third root, §160`. Worth measuring because the assertion is a substring: `"report"` **is** contained in `"raw report"`, so the sibling's test would survive this swap in the other direction and this one must not. | ⬜ NOT RUN |
| `RR-M5` | In `IngestionService.apply`, move `rawReports.put(...)` to before `jobs.saveAndFlush(job)` | Bytes before row — `ReportJobRunner`'s ordering imported into the caller that must not have it. **Should redden nothing**, and that is the finding: the ordering only matters when the transaction rolls back, which no test drives. The consequence is an orphan blob no row names, unreachable and uncollectable — `EvidenceService`'s stated reason for the opposite order, unobserved by this suite. | ⬜ NOT RUN |
| `RR-M6` | In `IngestionJobView.reportUrl`, return the URL unconditionally instead of null for a null key | Every job advertises a download; the historical ones 404. Should redden `renders no download for a job posted before uploads were kept` in the **frontend** suite and nothing in the backend one — the field is required-and-nullable and the backend never renders it, so this is one of the few mutations whose only observer is on the other stack. | ⬜ NOT RUN |

> **Seven browser rows, `RR-1`…`RR-7`**, in lane **`A3`** — the CI ingestion block, which already
> builds every fixture they need. `RR-M3` and `RR-M5` are the two that redden nothing here and are
> the reason both queues have a row about roots and orderings.

## §161 — the contract-versus-server enum rule

*Three mutations, and **two of them were already run** — before the slice's prose was written,
because a rule whose failure mode is untested is the shape §161 is about. `CE-M3` is the one left,
and it is the interesting one: it asks whether the rule that pins the correspondence can itself be
silently emptied.*

| # | Mutation | Expected | Result |
|---|---|---|---|
| `CE-M1` | Add a constant (`MARKDOWN`) to `execution.StepFormat` and not to `repository.StepFormat` or the contract | Should redden `a contract enum and its server type hold the same members` as **SERVER-ONLY**, naming the copy that moved. This is the defect the rule exists for — a constant Jackson will write that no generated union contains — and the two-copy case is the one a name-matching rule would have missed. | ✅ **RUN 2026-08-10 — red, on the predicted rule and message** |
| `CE-M2` | Delete `- INCOMPLETE` from `openapi.yaml`'s `HealthSignal` enum | Should redden the **healed** arm: `HealthSignal.INCOMPLETE` is in `DIVERGENCE_BY_DECISION`, and with the member gone from both sides the divergence no longer exists, so the argument for it has outlived its gap. Proves the map fails in *both* directions — the property §157–§160 each relied on in the writerless twin. | ✅ **RUN 2026-08-10 — red, `Expecting empty but was: ["HealthSignal.INCOMPLETE"]`** |
| `CE-M3` | In `constantsOf`, revert `JavaModifier.ENUM` to `JavaModifier.STATIC` | Restores the bug the rule shipped with for one run: `execution.AggregationPolicy.DEFAULT` — a `public static final` alias for `LATEST`, never serialized — is counted as a constant and reported as SERVER-ONLY. **Should redden the same rule with a false finding**, which is the failure mode worth measuring: a green suite proves the rule finds things, and nothing here proves it does not manufacture them. If it stays green, the alias has gone and the mutation needs a new target. | ⬜ NOT RUN |

> **`CE-M1` and `CE-M2` were run inline and that is a deliberate exception to §65, not a drift back
> to §72–§76's.** They mutate a *check* rather than the product: the thing under test is the
> assertion, there is no screen and no request, and a rule shipped without them is a rule nobody has
> seen fail. Everything §65 defers — the browser pass and the product's own behaviour — is deferred.

## §162 — one media type for every refusal, and the exemptions that excused nothing

*Four mutations. **All four were run** before the slice's prose was written, for `CE-M1`/`CE-M2`'s
reason: each mutates a **check** rather than the product — no screen, no request, no user-visible
behaviour — and a rule shipped without seeing it fail is the rule this slice exists because of.
They are recorded here rather than omitted, because "run" is a result and the queue is the record
of what ran.*

| # | Mutation | Expected | Result |
|---|---|---|---|
| `§162 MT-1` | In `openapi.yaml`, revert `components/responses/Conflict` to `application/json` | Should redden `every declared refusal body is application/problem+json`, naming the component. Proves the rule sees a shared response — 30 of the document's error bodies reach their media type through a `$ref`, so a rule that walked only inline `content` blocks would have reported clean over almost the whole document. | ✅ **RUN 2026-08-11 — red, on the named rule** |
| `§162 MT-2` | In `ProblemMediaType.beforeBodyWrite`, make the condition `if (false)` | Should redden **two** things in different harnesses: `both envelopes go out as application/problem+json` (standalone MockMvc, the record's arm) and `the body is a Problem, not the container's whitelabel shape` (real HTTP, the `/error` dispatch). The pair is the point — the advice serves two paths and one test each, so a single red would mean one of the two paths is unobserved. | ✅ **RUN 2026-08-11 — both red, as predicted** |
| `§162 MT-3` | In `ProblemWriter.write`, revert to `MediaType.APPLICATION_JSON_VALUE` | Should redden `a token is refused at its ceiling, and told how long to wait` and **nothing else** — a filter's response reaches no `ResponseBodyAdvice`, so this is the one writer the advice cannot cover and the only assertion standing over it. | ✅ **RUN 2026-08-11 — red, only that test** |
| `§162 MT-4` | Re-add `".components.schemas.CaseConditionInput"` to `ContractConventionTest.NO_ALWAYS_PRESENT_PROPERTY` | Should redden `every exemption is still exempting something`. This is the slice's own finding replayed: before the rule existed, the entry sat there for sixty-seven sections excusing a schema that had stopped needing it, and **every run was green**. | ✅ **RUN 2026-08-11 — red, `NO_ALWAYS_PRESENT_PROPERTY … it declares required now`** |

> **Not mutation-tested and worth knowing:** nothing pins that the *record* and `ProblemDetail`
> carry the same field names. `ContractConventionTest.RESPONSE_RECORDS` does that job for four
> response schemas and `Problem` is not one of them — it is written by two Java types, only one of
> which this project owns, so the record-component comparison has nothing to compare. §162 declared
> `instance` rather than closing that gap.
