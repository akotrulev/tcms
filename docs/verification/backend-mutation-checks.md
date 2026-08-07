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
