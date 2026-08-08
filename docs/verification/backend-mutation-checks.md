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

Three phases, three files' worth of assertions: `PlanStatisticsEndpointTest` (the policy set),
`ExecutionEndpointTest.Deletion`'s sibling nested class for `run:execute`, and
`ArtifactEndpointTest.Deletion` (10 tests, the `af…` namespace, `af009000-…` for the seeded
sibling-project row).

> **THREE MUTATIONS WERE RUN and are stated as a deviation from §65** — one per phase, each the
> substitution a reviewer would most plausibly wave through, because all three phases turn on a
> *constant* rather than on logic and a constant swap is invisible in review. The rest are queued.

| # | Mutation | Expected | Result |
|---|---|---|---|
| AG-1 | `AggregationPolicy.BEST` is restored to **one** of the two enums (either one) | `AggregateExecutionPolicyTest` pins the two name sets equal, so the copies cannot drift. This is the check that made the removal landable at all, and it matters more for a deletion than an addition: a missing value fails closed and loudly, while a copy that still *accepts* a retired policy answers a number the plan page can no longer produce. | ⬜ NOT RUN |
| AG-2 | `PlanController` falls back to `DEFAULT` for an unparseable policy instead of 400ing | A bookmark, a saved URL or a screenshot's query string carrying `BEST` answers a **different question under the same name**, silently and in the flattering direction — which is the exact harm the removal exists to prevent, reintroduced by the error path. Should redden `bestIsRefused` and `anUnknownPolicyIsRefused`. | ⬜ NOT RUN |
| AG-3 | `WORST_FIRST`'s severity order is reversed, restoring `BEST`'s behaviour under `WORST`'s name | The dropdown says "worst result per case" and computes the best. **Worse than shipping `BEST`**, which at least said what it did. Should redden `worst`, `noPolicyClearsTheBlockerList` and `thePoliciesActuallyDiffer`. | ⬜ NOT RUN |
| AG-4 | `criteriaFollowTheDisplayedCounts`' tolerance is tightened back to `within(0.01)` | *(Not a mutation — the finding.)* `actual` is rounded to one decimal for display, so three of thirteen is served as `23.1` against an unrounded `23.0769…`. **This was the phase's only red run**, and the honest fix was the tolerance and not the endpoint: a test asserting the unrounded value asserts against a number no screen shows. | ✅ **FOUND AND FIXED** — `within(0.05)`, with the rounding named in a comment |
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

Added 2026-08-08. `test_case_draft` got its first writer since `V1`, three endpoints, and a clear on
every successful save. Assertions live in `CaseDraftEndpointTest` (26 tests across four nested
classes). **No migration** — every column has been there since M0.

> **NONE RUN**, per §65. **`CD-1` is the row worth running first and it is not close.** It is the
> only mutation here whose failure is invisible to every *other* test in the class: writing and
> reading a draft works perfectly with the clear deleted, and what breaks is the next mount.

| # | Mutation | Expected | Result |
|---|---|---|---|
| CD-1 | `CaseUpdateService.apply` drops the `drafts.deleteForCase(...)` call | **The mutation of this slice.** V1's own comment has promised this since M0 and nothing enforced it. The editor's next mount offers to restore content the user already saved — "your save did not land", about a save that did. Should redden `Promotion.savingTheCaseClearsTheBuffer` **and nothing else**, which is the point: every other test in the class passes. | ⬜ NOT RUN |
| CD-2 | The `deleteForCase` call is moved to the **top** of `apply`, before validation | The clear that destroys the work it was protecting: a save refused for a blank title also throws away the buffered draft the author was about to lose. Should redden `Promotion.aFailedSaveKeepsTheDraft` while `savingTheCaseClearsTheBuffer` stays green — the pair is why the placement is asserted separately. | ⬜ NOT RUN |
| CD-3 | `CaseDraftService.findOne` uses `readVisible` / `project:read` instead of `writeGranted` / `case:edit` | The gate most likely to be "simplified", on the argument that a draft discloses nothing the case does not. It discloses somebody's unfinished sentence, shown nowhere else in the product, to the one role invited to read coverage rather than watch it being written. Should redden `Gates.aViewerCannotReadTheDraftEither` and leave `theViewerReachesTheCase` green. | ⬜ NOT RUN |
| CD-4 | `save`/`discard` swap `writeGranted` for `readVisible` | A Viewer can overwrite and destroy other people's unsaved work. Should redden `Gates.aViewerCannotBuffer` and `aViewerCannotDiscard` — and note both assert the row's *state* as well as the status, so a 403 that wrote anyway is caught too. | ⬜ NOT RUN |
| CD-5 | `buffer` drops the `cases.findLiveInProject` check and trusts the project gate alone | `/projects/{granted}/cases/{ungranted}` buffers against a case the caller was never given, and the `GET` then reads it back. Should redden `Gates.theSecondPathSegmentIsGatedToo`. | ⬜ NOT RUN |
| CD-6 | The `lockVersion == null` check is replaced with a default of `0` | The field exists precisely because guessing it wrong loses a committed edit: a draft that claims version 0 is stale against every case, or — worse, if the default were the case's current version — is silently *never* stale. Should redden `Writing.theLockVersionIsRequired`. | ⬜ NOT RUN |
| CD-7 | The `lockVersion` check is moved **below** the `findLiveInProject` lookup | A malformed body answers 404, sending the caller to check a case id that is fine. Should redden `Writing.theBodyIsCheckedBeforeThe404` only — the 400 itself still happens for a reachable case, which is why the ordering has its own test. | ⬜ NOT RUN |
| CD-8 | `render` computes `stale` as `false` unconditionally | The restore-over-a-colleague's-edit path goes unreported, and it is the one the existing `If-Match` machinery **structurally cannot catch** — the client's precondition comes from a fresh read and is correct. Should redden `Staleness.aDraftWrittenAgainstAnOlderVersionSaysSo` and leave `aCurrentDraftIsNotStale` green. | ⬜ NOT RUN |
| CD-9 | `render` makes a stale draft return empty `content` instead of the buffered text | The refusal dressed as safety: the product deciding somebody else's edit invalidates your sentence. Should redden the second assertion of `aDraftWrittenAgainstAnOlderVersionSaysSo`, which exists separately from the `stale` flag for exactly this. | ⬜ NOT RUN |
| CD-10 | `read` returns `Outcome.NotFound` instead of `Outcome.Empty` when no draft exists | Collapses the 204 into the 404 and tells an editor their case is gone every time they open one they had not left work in — which is almost every case, almost always. Should redden `Reading.noDraftIsA204AndNotA404` and leave `theCaseGateStillAnswers404` green; the pair is the whole distinction. | ⬜ NOT RUN |
| CD-11 | `discard` answers `NotFound` when `deleteForCase` returns 0 | A second click, a retry after a timeout, and a concurrent save that already cleared the row all become errors. Should redden `Promotion.discardingNothingIsNotA404` on its **second** call — the first still finds a row, which is why the test discards twice. | ⬜ NOT RUN |
| CD-12 | `buffer` validates the content through `CaseEdit.validate` before storing | The tempting "surely we should check it" change, and it breaks the one thing a keystroke buffer may never do: autosave starts failing while somebody is mid-sentence, so the buffer protects nothing during exactly the interval it exists for. Should redden `Writing.aHalfWrittenDraftIsBuffered`. | ⬜ NOT RUN |
| CD-13 | `MAX_CONTENT_BYTES` is measured in `String.length()` rather than UTF-8 bytes | A different ceiling per language — a case written in Cyrillic or Japanese gets up to four times the storage of one written in English, on a column that stores bytes. Should redden nothing in the current suite, which is the finding: the assertion uses ASCII. **Recorded as a known gap rather than a mutation with an expected red.** | ⬜ NOT RUN |
| CD-14 | `TestCaseDraft.overwrite` merges the incoming content instead of replacing it | A merge has to decide what an absent field means, and `CaseUpdateRequest`'s header records that absent and null are the same Java value here — so clearing a field would never survive an autosave. Should redden `Writing.theBufferIsReplacedWholesale`. | ⬜ NOT RUN |
| CD-15 | `TestCaseDraft` is given a `@Version` column and optimistic locking | An autosave that answers 409 is one the user cannot resolve — they never asked for it and there is no dialog. Would not redden anything in this suite, because reaching it needs two interleaved transactions. **Recorded as a decision to defend, not a mutation with an expected red** — the same honesty `CaseUpdateService` applies to its own second concurrency check. | ⬜ NOT RUN |
| CD-16 | `TestCaseDraftRepository` loses its `@Transactional` (or gains `readOnly = true`) | The first is the repository rule this project has a build-failing test for; the second is subtler — Hibernate skips the flush, so the write silently does nothing while every assertion about the *read* still passes. `RepositoryTransactionTest` catches the first from the live context. Should redden most of `Writing` for the second. | ⬜ NOT RUN |

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
