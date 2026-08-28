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

> # ⛔ THE PASS IS CLOSED — 2026-08-23, at the user's direction.
>
> **492 rows are `CLOSED:NOT-RUN` in the manifest, and that status means CLOSED WITHOUT
> MEASUREMENT and nothing else.** It is not a zero, not a green, and not evidence that the test a
> row names observes anything. **A closed row answers no question about coverage** — the honest
> reading is "nobody looked", which is where the row stood before the pass began.
>
> What WAS measured stands: 244 adjudicated rows across waves 1, S, 2 and wave 3's batch A, in
> `mutation-results.tsv`. Those are real and are the only thing in this file that may be cited as
> evidence.
>
> **Re-opening costs a status edit.** Every closed row keeps its id, its text and its derived
> scope, and `mutation-manifest.tsv`'s `wave` column still carries the wave-3 selection. The
> harness (`~/bin/tcms-mutate`) and the isolated remote tree are intact.

**Waves 1, S and 2 are run.** `RUN RECORD — 2026-08-22, wave 1` below covers 90 rows, eight findings
and the five registered gaps that turned out not to exist; `RUN RECORD — 2026-08-23, wave S` covers
the 74 rows of the four scripted sweeps, eight findings, and seven registered gaps confirmed by
measurement; `RUN RECORD — 2026-08-23, wave 2` covers the 30 rows of the three cross-cutting fixture
families, **three fixtures added to the suite**, eight findings and five more registered gaps
withdrawn. **Wave 3 ran batch A only — its 41 gap rows** (`RUN RECORD — 2026-08-23, wave 3 batch A` below):
36 gaps confirmed, four withdrawn, one already-measured row caught as stale. **The remaining 72
rows of the slimmed set, and the 414 rows never selected into it, are `CLOSED:NOT-RUN`.**
Wave 2 was the committed stopping point (run plan §9 decision 4), and wave 3 was
re-decided on what waves 1, S and 2 actually found: **`docs/plans/2026-08-23-wave-3-slimmed.md`**
selects **113 of the 509** rows wave 3 would have held, admitted on each row's own text — the rows
predicting GREEN (the class waves 1+2 withdrew ten registered gaps from), the rows that say they do
not know, the cross-company and silent-data-loss consequences, and two probes into each section that
has already produced a finding. The other 396 keep their ids and their `queued` status with no wave
tag — a recorded deferral, not a truncation. **Every wave set is derived, not hand-listed** — the
criteria are in `mutation-manifest.tsv`'s header and the `wave` column is authoritative. **Two sections govern what is owed before you
start.** `DOCUMENTED GAPS` at the foot of this file
holds the 44 rows retired from the run on 2026-08-22 (each keeping its id and naming what would
close it), and the run plan is
`docs/plans/2026-08-22-backend-mutation-backlog-run-plan.md`. Measured results land in
`mutation-results.tsv`; the per-row manifest is `mutation-manifest.tsv`.

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

# RUN RECORD — 2026-08-23, wave 3 batch A (the gap rows) — AND THE CLOSE OF THE PASS

**41 rows, the whole `gap` class of the slimmed wave 3** (`docs/plans/2026-08-23-wave-3-slimmed.md`
§4, batch A). Run on the isolated tree with the isolated Gradle home; the lane reverted clean after
every row and ended byte-identical to HEAD.

| Outcome | Rows |
|---|---|
| ZERO-AS-PREDICTED — the gap is real | **36** |
| MORE — **a registered gap WITHDRAWN**, by a red the row did not expect | **4** |
| STALE — already measured; the derivation picked up a superseded entry | 1 |

**The withdrawal rate is 4 of 40, and that matters more than the four.** The slimmed set was built
on the claim that a green prediction is close to a coin flip — waves 1 and 2 withdrew ten registered
gaps against seven confirmed. Batch A came in at **10%**, not ~59%. The register is materially more
honest than the earlier waves implied, and the argument that ranked this class first is weaker than
when it was written. **Recorded here rather than left standing in the plan**, which still carries the
older estimate.

## The four withdrawn

| id | What the row believed | What the measurement said |
|---|---|---|---|
| `§246 CE-M11` | the belt-and-braces `company_id` predicate might have no observer on the policied tables | **Two** observers — `companyBIsAbsentFromEveryLine` and the direct-count arm |
| `§246 CE-M9` | a filesystem blob sweep stays green on the happy path | Green there, but `aRowNamingBytesThatAreGoneIsReportedRatherThanSkipped` falls: a sweep sees only what exists, so it can never report bytes that are gone. The row's *orphan* half still stands |
| `§227 PM-7` | folding the reaped count into the tick budget needs 25 stranded jobs to see | Visible at a reap count of **one** — `run = reaped` costs the loop an iteration the fixture depends on |
| `§236 SE-1` | nothing asserts the step-evidence refusal, only the success path | Both arms the refusal deliberately conflates assert it — a step of another case, and a step nobody has marked |

## The confirmed gaps worth acting on

Ordered by what a reader should act on first. Each is a place where the product's behaviour is held
by a comment, a convention, or review — and by no test.

1. **`DR-6` — `HealthSignal`'s declaration order is enforced by a comment.** `CaseSearch.tallyHealth`
   selects one aggregate per constant and reads the results back **by position**. Moving
   `UNDECLARED_AUTOMATION` ahead of `ORPHANED` reddens nothing, and puts every facet count after the
   insertion point on the wrong dashboard tile: right numbers, wrong names, no error anywhere.
2. **`§225 PM-2` — the credential is absent from the memo key's observers.** Dropping the `token`
   component from `PermissionEvaluator.MemoKey` is green. Two resolutions under different
   credentials sharing an entry is a **cap bypass**, and it is held by review alone. Closing it needs
   `TokenContext` swapped mid-transaction, which no production path does.
3. **`§227 PM-11` — §6g's review policy does not bind on the single-case status endpoint.** Deleting
   the `reviews.refuses(...)` arm reddens nothing. Author-cannot-approve-own-work and
   minimum-peer-LGTM bind on the bulk path, on suites and on plans — and not on the endpoint a person
   actually uses to approve one case. **`§227 PM-12` is its sibling**: `reviews.moved(...)` deleted is
   also green, so a submit would move the status and open no review record.
4. **`GA-1` — the "hangs after succeeding" defect has no observer.** `TcmsApplication.main` reads the
   verb set *before the context exists* to pick `WebApplicationType.NONE`. Removing a verb is green:
   the command does its work, prints success, boots a web server and never exits. An operator's
   terminal blocks and CI hangs on a step that passed.
5. **`§249 CS-M7` — a search hit's project NAME is not asserted to come from the gated set.** Resolving
   it from a fresh `findById` is green. A name is a disclosure where an id is not.
6. **`§241 OC-3` — the tenancy second layer is unobserved.** Both UNION arms lose
   `AND ra.company_id = :companyId` and nothing moves: RLS alone holds the line. It becomes
   load-bearing the moment a deployment takes `V1` up on its offer to disable RLS, which no test
   covers.

## Queue hygiene the run exposed

**Six rows cite something that is not there**, each caught by the runner's scope guard before a wrong
scope could be recorded as a zero — the guard `AT-5` bought:

- `ReviewGateTest` and `ReviewPolicyTest` (`§227 PM-11`), `PlanEndpointTest` and
  `PlanDetailEndpointTest` (`§229 PM-13`): **no such class on disk.**
- `theSupersededOneNamesItsReplacement` (`§209 AM-5`) and `theSuiteDefaultsToTheSources` (`FQ-24`):
  renamed, or living in a different class than the row says.

**`R6-5` was already measured.** The queued entry is superseded by the same id's re-run after R6
phase 4 — RED on `Configurations`, once the fixture grew a second configuration. The wave-3
derivation read the stale first entry. This is the D9 shape the run plan records for `R6-8`, and it
means **a derived wave set can carry an already-answered row**; a future derivation should exclude
ids that appear twice in this file.

## And then the pass was closed

**At the user's direction, 2026-08-23: no further mutation time is spent on this file.** The
remaining 72 rows of the slimmed wave 3, and the 414 never selected into it, are `CLOSED:NOT-RUN`.
See the banner at the top of this file for what that status does and does not mean — the short
version is that it is **not a measurement**, and no closed row may be cited as coverage.

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
|---|---|---|---|
| 11a | `CaseSpecifications.suiteScope` — delete the `scope.memberCaseIds() != null` branch, so a named suite falls through to `suite_id IN (…)` | **This is the slice's own defect, re-armed.** It reverts the grid to the filed arm while `caseCount` stays the union. `CaseListEndpointTest` must fail on a **tag-defined** suite. If it goes green, the fixture has no suite whose membership differs from its filing — which is exactly why the defect survived §96 and §97 unseen, and the row to fix first ✅ **RUN 2026-08-22 — RED, 5.** MORE than the 1 predicted, and the row's own worry is answered: *"suiteId selects the suite's RESOLVED MEMBERSHIP, not just what is filed in it"* fell, so `CaseListEndpointTest`'s fixture **does** hold a suite whose membership differs from its filing. The other four — facets over the filtered set, filters AND-ing, `unfiled` beside a `suiteId`, the by-ids intersection — all read the same predicate, so the width is coherence, not coupling. |
| 11b | `SuiteRepository.countLiveCasesFiledIn` — widen to the resolved membership | `SuiteWriteEndpointTest`'s delete-a-claiming-suite case must fail. The two readings are *deliberately* different and nothing but a test distinguishes them; if this reddens nothing, deleting a tag-defined suite could start 409ing on cases it does not hold |
| 11c | `CaseConditions.ancestry` — return `List.of()` unconditionally | A case must lose the conditions of the suite it is filed in. Suspected **weak**: the method now returns at most one suite, so the mutation is close to the whole feature, and the fixture attaches a condition to exactly one suite (`Refunds`) |
| 11d | `ExecutionSource` — change `('s' || s.key_seq)::ltree` to `NULL` | Suite-grouped report sections must move their rows into the `(not filed)` bucket. If nothing reddens, no report test covers a suite-grouped section over a **freshly projected** fact row, and the column's contents are unasserted for every row written after §98 |
| 11e | `SuiteRepository.findLiveForProject` — order by `position` instead of `keySeq` | `SuiteEndpointTest.orderingIsTotalAndStable`. Every fixture row has `position = 0`, so Postgres may return any order — meaning this mutation may pass **intermittently**, which is the honest expectation to record rather than a clean red ✅ **RUN 2026-08-23 (wave 2) — RED, exactly 1: `the order is total and stable, so the rail cannot reshuffle between loads`.** MATCH. The row's intermittency worry did not materialise: every fixture row has `position = 0`, and ordering by it reddened cleanly. |
| 11f | `CaseFacts.memberOf` — drop one of the three arms | `SuiteScopeAgreementTest`. It survived §98 (the plan expected it to be retired — the duplication is the module boundary's, not the subtree's), and the two predicates are now supposed to be **identical** rather than merely equivalent, so this should be its sharpest round |

**And one that cannot be run here at all:** `V33` drops two columns, and `ddl-auto: validate`
tolerates neither a missing one nor an extra one — but Testcontainers migrates a fresh container, so
a `DROP COLUMN` that failed against a real database with data would go unobserved. Same shape as
`V32`'s backfill row below, and it belongs to the Hetzner list.

## 12. §99's run scope — seven mutations, none run

The slice adds two arms to one query and changes no gate, so nearly all of its risk is in the query
itself and in what the *validator* stops refusing. **None was run**, per §65.

| # | Mutation | What must redden |
|---|---|---|---|
| 12a | `CaseFacts.caseIdsInRunScope` — replace the suite arm with `c.suite_id IN (:suiteIds)` | **The slice's own defect, armed.** `RunCreateEndpointTest.Scope.aSuiteArmResolvesMembership` must fail, because PAYMENTS claims `caseA` by tag and files nothing. `paymentsFilesNothing` is the guard on that property; if 12a goes green, run *that* test first — a fixture where the two readings agree makes every row below vacuous ✅ **RUN 2026-08-22 — RED, 2.** `aSuiteArmResolvesMembership` fell as predicted, and with it *"a run scoped by a TAG or a SUITE is gated too — §99's arms are not doors"*. The extra is the right extra: §6g's gate reads the **resolved** set, so a broken resolution breaks the gate's own fixture. `paymentsFilesNothing` holds — the fixture discriminates, so the rows below it are not vacuous. |
| 12b | Same method — drop `AND c.deleted_at IS NULL` | `aDeletedCaseIsNotReachedByItsTag`. `case_tag` rows outlive a soft delete, so this is the one arm where the omission is reachable through data rather than through a missing join |
| 12c | Same method — drop `AND s.project_id = c.project_id` from the suite arm | Suspected **weak**: `aSiblingProjectsSuiteIsRefused` is refused by `RunEdit`'s `liveSuiteIds` check one step earlier, so the query's own bound may never be reached. If nothing reddens, the second gate is untested and the honest repair is a test that bypasses the validator, not a claim of coverage ❗ **RUN 2026-08-23 (wave 2) — ZERO, and the row's own suspicion is confirmed.** `RunEdit.liveSuiteIds` refuses one step earlier, so the query's bound is never reached. The second gate is untested, and the honest repair is the one the row already names: a test that bypasses the validator. |
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
|---|---|---|---|
| 13a | `ExecutionSource.correctSuiteRunCounts` — join `test_execution` on the suite's **current** membership instead of on the row's `membership_snapshot` | **Finding 1, armed.** `SuiteRunSummaryRefreshTest.Freeze.aLaterTagDoesNotRewriteAPastRun`. Nothing else should move: `anAmendedVerdictIsCorrected` must stay green, and if it also reddens the mutation broke the correcting statement rather than the freeze, which is a different (and less interesting) result ✅ **RUN 2026-08-22 — RED, exactly 1: `aLaterTagDoesNotRewriteAPastRun`.** MATCH. `anAmendedVerdictIsCorrected` stayed green, which is the half that distinguishes a broken freeze from a broken correcting statement — the row asked for exactly that and got it. |
| 13b | `RollupRefreshService.refresh` — delete the `source.prunePlanSnapshot()` call | **Finding 2, armed.** `PlanSnapshotRefreshTest.Prune.prunesACaseThatLeftTheScope` **and** `prunesASoftDeletedCase`. Both, or the prune is pinned only in the direction that some other filter also covers. `theSiblingIsNotPruned` must stay green — it asserts the asymmetry, not the prune ✅ **RUN 2026-08-22 — RED, 2: `prunesACaseThatLeftTheScope` AND `prunesASoftDeletedCase`.** MATCH. `theSiblingIsNotPruned` stayed green. |
| 13c | `PlanStatisticsService.compute` — make `fromRollup` depend on `servedFromRollup(policy)` alone, dropping the `snapshotIsComplete` conjunct | **Finding 3, armed — the defect the oracle test caught before it shipped.** `RollupAgreementTest.anEmptyRollupDoesNotReportEveryCaseAsNotRun` and `aSnapshotMissingOneCaseFallsBackToLive`. A green run here means the repair is described and not asserted, and the behaviour it restores is a plan that has been executed reporting that nothing has been executed ✅ **RUN 2026-08-22 — RED, 5.** MORE than the 2 predicted. Both named tests fell (*"a cold rollup falls back to live rather than reporting the plan as un-executed"*, *"a PARTIALLY cold rollup falls back too"*) plus the three agreement assertions — LATEST, per-configuration, and the `execution_fact` trend. Coherent rather than coupled: the `snapshotIsComplete` conjunct is what makes the two paths agree at all, so breaking it breaks agreement. **The repair is asserted, not merely described.** |
| 13d | `ExecutionSource.insertNewSuiteRunSummaries` — replace the three-arm predicate with `c.suite_id = s.id` | `SuiteRunSummaryRefreshTest.summarisesATagDefinedSuite`, `Agreement.theSnapshotAgreesWithTheRepositorysUnion`, and `RollupAgreementTest.suiteHistoryAgrees`. `aTagDefinedSuiteFilesNothing` is the guard on the property — **if 13d goes green, run that first**, because a fixture where the two readings agree makes every row here vacuous |
| 13e | Same statement — change the `LEFT JOIN case_status` to an inner join | `anUnrecordedExecutionIsNotRunRatherThanMissing`. This is the one place the rollup deliberately differs from `execution_fact`, whose header says coverage denominators can never come from it; the inner spelling makes `total` exclude the un-run cases and a run of 3 of 47 read as 100% |
| 13f | `ExecutionSource.correctSuiteRunCounts` — drop the trailing `IS DISTINCT FROM` guard | `updatedAtDoesNotMoveWhenNothingChanges`. Predicted to redden exactly one test; recorded because the consequence is not a wrong count but a `computedAt` that always says "just now", which is a freshness field that says nothing |
| 13g | `ExecutionSource.refreshPlanSnapshot` — reverse the tiebreak to `e.executed_at ASC` | `PlanSnapshotRefreshTest.keepsTheLatestExecutionPerCasePerConfiguration` and `RollupAgreementTest.planStatisticsAgreeUnderLatest`. The fixture's `CASE_TWICE` is what makes this observable — with one execution per case it reddens nothing ✅ **RUN 2026-08-23 (wave 2) — RED, 4.** MORE than the 2 predicted; the extras are `RollupAgreementTest`'s two plan-statistics agreements, which is the right extra — a wrong snapshot is a wrong rollup. **Two citation corrections:** the test is `keepsTheLatestPerCasePerConfiguration` (no *Execution*), and `PlanSnapshotRefreshTest` is `com.tcms.reporting`, not `com.tcms.execution`. |
| 13h | Same statement — drop `AND r.deleted_at IS NULL` from the `test_run` join | Suspected **weak**: no fixture here soft-deletes a run of a plan. If nothing reddens, that is the finding and the repair is a fixture row, not a claim of coverage. The behaviour matters — counting a withdrawn run restores a result somebody meant to retract |
| 13i | `ExecutionSource.prunePlanSnapshot` — drop the `c.deleted_at IS NULL` conjunct | `prunesASoftDeletedCase` alone. If `prunesACaseThatLeftTheScope` also reddens, the two predicates are not independent and the row's split is wrong ✅ **RUN 2026-08-22 — RED, exactly 1: `prunesASoftDeletedCase`.** MATCH, and it answers the row's own question: `prunesACaseThatLeftTheScope` did **not** redden, so the two predicates are independent and the split between 13b and 13i is right. |
| 13j | `RollupFacts.runsWithoutSummary` — return `List.of()` unconditionally | `RollupAgreementTest.aRunNewerThanTheRollupIsStillInTheHistory`. The mutation is exactly "serve the suite history from the rollup alone", and what it produces is an **absent** run rather than a stale one |
| 13k | `SuiteHistoryService.computedAt` / `PlanStatisticsService.computedAt` — take the newest `updated_at` instead of the oldest | Predicted to redden **nothing**, and that is the row rather than a shortfall: no fixture here has two rollup rows written at measurably different instants. The decision is argued in both javadocs and the honest repair is a test that writes two rows a second apart, listed under **Tests to write** below ✅ **RUN 2026-08-22 — ZERO, as predicted.** Both `computedAt` methods mutated together (`isBefore` → `isAfter`), scope `RollupAgreementTest` + `SuiteRunSummaryRefreshTest` + `PlanStatisticsEndpointTest`: nothing moved. The row derived its own zero and the measurement agrees, so this is **BLIND, not proof** — held for wave 2's two-instant fixture, which is the only thing that can tell the decision from its opposite. ✅ **RE-RUN 2026-08-23 (wave 2) — RED, 2: both halves.** `RollupAgreementTest.TwoInstants` stamps the rollup rows with two instants an hour apart and asserts the OLDER, and `isBefore` → `isAfter` now reddens the suite history and the plan statistics alike. The 2026-08-05 pass listed this under *Tests to write*; it is written. **WITHDRAWN from `DOCUMENTED GAPS`.** |

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
|---|---|---|---|
| R6-1 | `WORST`'s `severityRank(...) ASC` → `DESC` (worst becomes best) | ✅ **RED, as designed** — reddens both `policy=WORST`'s agreement and `theFixtureDiscriminates`, the latter because `WORST` collapses onto `BEST`. |
| R6-2 | `LATEST`'s `executed_at DESC` → `ASC` (each case resolved to its **oldest** result — the policy inverted) | ⚠️ **GREEN on the first fixture — the oracle was vacuous.** `RECOVERED` (fail→pass) and `REGRESSED` (pass→fail) were exact mirrors, so reversing time swapped *which* case was passing and changed **no total at all**. `UNBLOCKED` (blocked→passed) was added to break the symmetry; the mutant now reddens `LATEST` **and** `LATEST_PER_CONFIG`. **The lesson generalises: a fixture built from mirrored pairs is symmetric under exactly the mutation an ordering test exists to catch.** |

**Queued, not run:**

| # | Mutation | Expected |
|---|---|---|---|
| R6-3 | Drop `verdictFirst()` from `WORST`'s ordering | An `UNTESTED` row outranks `PASSED`, so a case that passed in every run it was recorded in reads as owed a verdict. Should redden `policy=WORST`. **Cannot be observed on the current fixture** — every execution in it carries a verdict, so this is expected to redden **nothing** until the fixture grows a recorded non-verdict status. Recorded as a *gap*, not as coverage. |
| R6-4 | `severityRank`'s `ELSE -1` → `ELSE 99` (an unknown kind ranks best rather than worst) | Unreachable today: `case_status.kind` has a CHECK. Recorded so the choice of direction is not mistaken for arbitrary.  ✅ **RUN 2026-08-23 (wave S) — GREEN.** ZERO-AS-PREDICTED, and the derivation in the cell beside this one is confirmed: the `ELSE` arm of `severityRank` is reached only by a `StatusKind` outside `WORST_FIRST`, and `theLadderNamesEveryKind` asserts the ladder is exactly `StatusKind.values()`. **The arm is unreachable by construction**, so `-1` and `99` cannot differ. Nothing closes this; `theLadderNamesEveryKind` is what keeps it unreachable. |
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
|---|---|---|---|
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
|---|---|---|---|
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
|---|---|---|---|
| R7-1 | `inconsistencySection`/`retrySection` call `resolvedCte` instead of `selectedCte` | **THE row.** Under the default `LATEST` each case is one row, so the callout empties and *"passed only after a retry"* goes to zero — a report claiming a project with no instability and no retries. Should redden `theCalloutSurvivesTheDefaultPolicy` and `theRetryOutcomesIgnoreThePolicy`. If it reddens only the `ALL` comparisons, the tests are asserting agreement between two wrong answers. ✅ **RUN 2026-08-22 — RED, 5.** MORE than the 2 predicted, and both named tests fell: *"the callout survives the DEFAULT policy, which collapses the very rows it reads"* and *"the outcomes ignore the policy, because the policy discards the retries"*. It did **not** redden only the `ALL` comparisons, which the row named as the bad outcome — so the three tests that passed on the RED run are no longer carrying no evidence. **The first patch of this row was a NO-OP and measured ZERO**: swapping `selectedCte` for `resolvedCte` leaves the query body reading `FROM selected`, and an unused CTE is legal SQL. A no-op reporting a clean zero is the false reassurance this file exists to catch; the corrected patch moves the CTE **and** the three row-set references. |
| R7-2 | `WHERE p.passed > 0 AND p.failed > 0` → the slice plan's own shape, `count(DISTINCT counts_as_pass) > 1` | The blocked-then-passed case is listed as an inconsistency. Should redden `blockedThenPassedIsNotListed`, which **passed on the RED run** and is therefore unproven. §105's rule, one report over. |
| R7-3 | `bool_or(c.passed > 0 AND c.failed > 0)` → `true` | Every listed case reads *Within one configuration and version*, so a configuration-dependent failure is reported as a flaky test. Should redden `theRowSaysWhereTheDisagreementIs`. Then the inverse — `→ false` — which should redden it the other way and turn every genuine flake into "across configurations". |
| R7-4 | `retryOutcomes`' `ORDER BY s.executed_at ASC NULLS LAST` → `DESC` (the **last** attempt read as the first) | Expected **RED** on the three outcome counts — but note R6-2's lesson: a fixture of mirrored pairs is symmetric under exactly this mutation. Here `BLOCKED_THEN_PASSED` and `ALWAYS_FAILING` break the mirror. If it comes back GREEN, the fixture is the defect, not the code. |
| R7-5 | `attempts`' partition drops `configuration_id`, then (separately) `case_version_id` | Two runs. Each should move `CONFIG_SPLIT` / `VERSION_SPLIT` out of two groups into one, so the totals fall from 8 and *"passed only after a retry"* rises — a cross-configuration difference reported as a retry, which is §6p's false-flake signal in the third section. |
| R7-6 | `caseLabels`' `AND c.project_id = :projectId` dropped | A sibling project's case title on this project's report. **Expected to redden nothing** — no test in this class seeds a second project, and RLS scopes to the company, not the project. Recorded as a **gap**: it is `CatalogueFacts`/`PlanFacts`' stated reason for existing, arriving in a fourth file, and the row that would close it is a two-project fixture. ✅ **RUN 2026-08-23 (wave 2) — RED, exactly 1**, against `reporting.SiblingProjectReadsTest`. **The zero-prediction was right about its own class and wrong about the code**: `AggregateVariabilityTest` stayed green, and the two-project fixture caught it. **WITHDRAWN from `DOCUMENTED GAPS`.** |

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
|---|---|---|---|
| R8-1 | `versionDivergence`'s `diverged` and `per_version` read `FROM resolved` instead of `FROM selected` | **THE row, and the one this section stands or falls on.** Under the default `LATEST` every case is one row at one version, so the section empties, the header flag goes to 0 and the report claims a project that has never edited a test between runs. Should redden `theSectionSurvivesTheDefaultPolicy`, `divergenceIsWiderThanTheInconsistencyCallout`, `theMostDividedCaseIsFirst`, `theMetadataFlagsTheCount` and every test in `Counted`. If it reddens **only** the cross-policy equalities, the tests are asserting agreement between two wrong answers. ✅ **RUN 2026-08-22 — RED, 7.** **§18's structural argument is now a measurement.** Every test the row named fell — `theSectionSurvivesTheDefaultPolicy`, `divergenceIsWiderThanTheInconsistencyCallout`, `theMostDividedCaseIsFirst`, `theMetadataFlagsTheCount` — plus all three of `Counted`. It did **not** redden only the cross-policy equalities, so the section is asserted rather than agreeing with itself. |
| R8-2 | `HAVING count(DISTINCT s.case_version_id) > 1` → the callout's predicate (`passed > 0 AND failed > 0`) | `STILL_FAILING` — failed at both versions — drops out, and the section becomes a duplicate of the inconsistency callout's *Across versions* rows. Should redden `divergenceIsWiderThanTheInconsistencyCallout`. §6e's harm is the **blending**, not the disagreement. |
| R8-3 | The `EXISTS` against `resolved` drops its `r.case_version_id = v.case_version_id` term, matching on the case alone | Every listed version reads **Counted** under every policy, and the narrative then claims the totals average across versions when under `LATEST` they average nothing. Should redden `theDefaultPolicyCountsOneVersionPerCase`, `worstCanCountTheOlderVersion` and half of `latestPerConfigBlendsAcrossConfigurations`. |
| R8-4 | `blendsVersions(...)` → `selection.policy() == AggregationPolicy.ALL` — the shortcut the method's javadoc exists to refuse | Expected to redden **exactly one assertion**: `latestPerConfigBlendsAcrossConfigurations`'s narrative check, where two configurations pinned different versions and the totals blend under a policy that is not `ALL`. A narrow blast radius is the *correct* outcome here. **If it reddens nothing at all, the fixture's second configuration is not doing its job** and the finding is in `PER_CONFIG`, not in the code. |
| R8-5 | The Java comparator: drop the `-versionsPerCase` term; then, separately, the `versionNo` term | Two runs. The first should move `THREE_VERSIONS` out of the first three rows (`theMostDividedCaseIsFirst`); the second should let a case's versions come back in id order rather than v1, v2, v3 — **and may come back GREEN by luck**, since `gen_random_uuid()` orders arbitrarily. A green here is not a passing mutation, it is an unrepeatable test, and the repair is a deterministic version id in the fixture. ✅ **RUN 2026-08-23 (wave 2) — both halves RED**: (a) 1, (b) 2. ⚠️ **And (b)'s red is exactly as unrepeatable as the green the row feared.** `AggregateVersionDivergenceTest` writes `test_case_version.id` with `gen_random_uuid()`, so what the sort fell back on this run was random. The repair the row named — a deterministic version id in the fixture — is still owed, and a red does not discharge it. |
| R8-6 | `metadata` emits the divergence entry only when the count is non-zero | Should redden the second assertion of `theMetadataFlagsTheCount`. Recorded as its own row because "print it even when it is zero" is exactly the kind of line a later tidy removes as dead, and the failure mode — a report where the flag's absence and the flag's zero look identical — is this repository's most repeated shape. |
| R8-7 | `caseVersionNumbers`' `AND c.project_id = :projectId` dropped | A sibling project's version number on this project's report. **Expected to redden nothing** — R7-6 one method over, for the same reason: the class seeds one project and RLS scopes to the company, not the project. Recorded as a **gap**, and it is now the *fifth* file layering this predicate with nothing checking any of them. The row that closes both is a two-project fixture, and it should be written once for the seam rather than twice for its methods. ✅ **RUN 2026-08-23 (wave 2) — RED, exactly 1.** `AggregateVersionDivergenceTest` green, the fixture red — `R7-6`'s answer one method over. **WITHDRAWN from `DOCUMENTED GAPS`.** |

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
|---|---|---|---|
| ~~14a~~ | ~~*(frontend)* `mocks/handlers/workItems.ts` — delete the `allowedTransitions` 409 §106 added~~ | **VOID since §107 — the file is deleted.** The row said no component test drove the mock handlers, which was the finding; §107 removed the handlers entirely, so there is nothing left to mutate. **What it was really asking has not gone away and is now 14b's alone**: the board relies client-side on a rule only the server enforces. Browser row `M1-29` moved to the `L2` refusals lane for the same reason (§109). ✅ **CLOSED 2026-08-22 as `STALE-VOID`, not run** — there is no file to mutate and it was a frontend row besides. Dropped from the wave-1 count (run plan §5, D10) with its id kept. |
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


- ~~**§101 — two rollup rows written a second apart**, so `computedAt`'s oldest-not-newest
  decision becomes assertable. Row 13k predicts zero reds today: both services pick the minimum
  `updated_at` across the rows they used, and no fixture produces two distinct instants, so the
  choice is argued in javadoc and pinned by nothing. It matters because the page mixes rolled-up rows
  with a live tail, and the newest is a claim true of one row and false of the others — false in the
  reassuring direction.~~
  **→ WRITTEN, wave 2, 2026-08-23.** `RollupAgreementTest.TwoInstants` stamps the rollup rows with
  two instants an hour apart by SQL and asserts the OLDER comes back, in both services. It carries
  its own control — the row count, asserted `>= 2`, because over one row the minimum and the maximum
  are the same number and the test could not fail. `13k` is measured against it below.

**The three fixtures wave 2 shipped**, listed here because this is the section that owes them:

- ~~**One two-project fixture, written once for the seam** — the row six sections have been asking
  for (`R7-6`, `R8-7`, `CM-6`, `IN-11`, `§167 DL-3`, `§168 CV-3`, and every other `D1` member).~~
  **→ WRITTEN, wave 2** as `reporting.SiblingProjectReadsTest`: one company, two projects, and every
  absence paired with the same query bounded to the sibling's own project so an empty result is the
  predicate's doing and not the fixture's. It closes `R7-6`, `R8-7` and `CM-6`; the `D1` members in
  `documentation` and `execution` need the same fixture in their own packages and are still blind.
- ~~**A same-instant pair**, so an `ORDER BY` that loses its id tiebreak is observable (`IH-6` and
  the rest of `D8`).~~ **→ WRITTEN, wave 2** as `IngestionEndpointTest.SameInstant`: six jobs
  stamped with one literal `created_at` by SQL — never by racing the clock — inserted in ascending
  id order so the answer under test is the exact reverse of the order a sort left alone returns.
- ~~**Two live writers on `case_health`**, both authoring from evidence rather than one of them
  hand-set (`DR-5` and the rest of `D3`).~~ **→ WRITTEN, wave 2** as
  `FailingRepeatedlyTest.LiveWriters`: `reporting`'s tick and `repository`'s tick, in both orders,
  over a case whose old failures raise a column of each. Its control asserts each writer alone
  raises something the other cannot.

**And one wave 2 opened rather than closed:** `AggregateVersionDivergenceTest` writes
`test_case_version.id` with `gen_random_uuid()`. `R8-5` (b) reddened against it, and the red is as
unrepeatable as the green the row feared — the sort fell back on random ids. A deterministic version
id in that fixture is a one-line repair and is the difference between a test and a coin flip.
The same is owed in five places for the same-instant pair: `CM-8` (b), `FQ-10`, `§242 LG-M2`,
`§245 BA-M12` and `§249 CS-M9`, each needing what `IngestionEndpointTest.SameInstant` demonstrates.

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
| CM-6 | `findPage` drops `AND p.project_id = :projectId` | A sibling project's document, in the same company, becomes reportable — RLS scopes to the company and no further. **Expected to redden nothing**: the class seeds one project per company. | ⬜ **NOT RUN — and it is a known gap, not a prediction.** This is now the **sixth** file layering a project predicate over RLS with nothing checking any of them (R7-6, R8-7, `PlanFacts`, `CatalogueFacts`, `ExecutionSource`×2). The row that closes all six is one two-project fixture written **once for the seam**, and it should stop being re-recorded per method. ✅ **RUN 2026-08-23 (wave 2) — RED, exactly 1**, and it is the first measurement any of the six files has ever had. `CoverageMatrixReportTest` stayed green, so the row's *expected to redden nothing* was a statement about the fixture. **WITHDRAWN from `DOCUMENTED GAPS`** — closed by `reporting.SiblingProjectReadsTest`. |
| CM-7 | `findPage` drops `AND p.deleted_at IS NULL` | A report whose spine is a document somebody removed. Should redden `aDeletedPageIsRefused`. | ⬜ NOT RUN |
| CM-8 | `blocks`' `ORDER BY b.position, b.id` → `ORDER BY b.id` | The specification comes out in a different order from the page it is about. Should redden `theSpineIsTheDocumentsOwnOrder` and `statementsBeforeAnyHeadingArePreamble`. **`doc_block` has no unique constraint on `(page_id, position)`**, so dropping the `b.id` tiebreak alone is expected to redden **nothing** and is the more interesting half — a non-total order produces two documents from one model only when two blocks tie. | ⬜ NOT RUN — run **both halves**; the second is the determinism claim and the first is only its symptom. ✅❗ **RUN 2026-08-23 (wave 2) — both halves.** (a) dropping `position` reddens **5**, more than the 2 predicted: the spine decides the coverage arithmetic as well as the order. (b) dropping only `b.id` reddens **ZERO**, exactly as predicted and for its reason — nothing ties a position. **The interesting half is the gap**, and it is the third sighting in this wave of one missing fixture shape. |
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
| AT-5 | `ApiToken.isUsable` drops the `revokedAt == null` arm | A revoked credential keeps working while the list shows it revoked. Should redden `revocationIsImmediate`. Note that test asserts the **lookup row still exists** first, so it cannot pass by the row having been deleted. | ✅ **RUN 2026-08-22 — RED, exactly 1: `revocationIsImmediate`.** MATCH. **The first run measured ZERO and was mis-scoped** — that test lives in `com.tcms.auth.ApiTokenAuthenticationTest`, not in `ApiTokenEndpointTest`. A wrong scope answers a green build, which is why the runner reports the scope it ran beside every result. |
| AT-6 | `isUsable` drops the `expiresAt` arm | Should redden `expiryIsEnforced`, whose fixture is seeded past-dated because the endpoint correctly refuses a past expiry — the state is reachable only by time passing. | ⬜ NOT RUN |
| AT-7 | `ApiToken.revoke` drops its `if (revokedAt == null)` guard | Re-revoking moves the timestamp, so the trail reports when somebody clicked twice rather than when the credential died. Should redden `revocationIsIdempotentAndTheFirstOneCounts` and **nothing else** — a status-only suite passes this mutant. | ⬜ NOT RUN |
| AT-8 | `ApiTokenService.issue` writes the plaintext into the audit `changes` instead of the prefix | Should redden `theAuditTrailNeverCarriesIt`. `AuditEvent.record`'s own contract forbids it and nothing enforces that but this test. | ⬜ NOT RUN |
| AT-9 | `V36`'s `api_token_name_idx` loses its `WHERE revoked_at IS NULL` | Should redden `aRevokedNameIsReusable` — as a **500**, not a 400, because the service's `existsByNameAndRevokedAtIsNull` check would still pass and the index would refuse the insert. Worth running for the shape of the failure as much as for the fact of it. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `aRevokedNameIsReusable`.** MATCH, **and the shape is what the row asked for**: the log shows `ServletException` ← `DataIntegrityViolationException` ← `ConstraintViolationException` ← `PSQLException`, i.e. a **500**, not a 400. The service's `existsByNameAndRevokedAtIsNull` check passes and the index refuses the insert underneath it, exactly as predicted. |
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
| IN-11 | `CaseFacts.caseIdsByAutomationKey` drops its `project_id` predicate | A sibling project's case is materialised into this project's run — a `test_execution` and its `test_case` in two different projects, which every read in the module assumes cannot happen. **The sixth file layering a project predicate over RLS with nothing checking it** (`PlanFacts`, `CatalogueFacts`, `ExecutionSource`×3, `DocumentationSource`); the fix is still one two-project fixture written once for the seam, not a row per method. | ⬜ NOT RUN — same gap §115's CM-6 records. ❗ **RUN 2026-08-23 (wave 2) — ZERO, and the fixture is HALF there.** `IngestionEndpointTest` holds a second project (`SAVINGS`) — its header says so — but no **case** in it, so `keyedLookup` has no sibling row to reach. One case in `SAVINGS` closes this row; that is a smaller repair than the section assumed. |
| IN-12 | `IngestionService.write` records a result on a case whose execution is absent from a named run | A CI job silently grows a checklist a person is working through, and its denominator. Needs a `runId` whose scope excludes a matched case. | ⬜ NOT RUN |

---

## §119 — the ingestion history reads

`IngestionEndpointTest.History` (7 tests, the same `d7` fixture, now with a **second project**).
**Nothing below was run** — §65 as restated: deferred means all of them, and §117's eight-mutation
deviation was that slice's, not a new rule. Two of these rows are the reason the slice's own
fixture grew a sibling project.

| # | Mutation | Expected | Result |
|---|---|---|---|
| IH-1 | `IngestionJobRepository.findOneInProject` drops its `projectId` predicate | A sibling project's job — its unmatched test names, its build's shape — is readable by somebody holding no grant on the project that owns it. RLS and the Hibernate filter both pass the row, so nothing else refuses it. Should redden `aSiblingProjectsJobIsUnreachable` alone. **This is IN-11's defect class with a fixture finally behind it**, on one of the seven files. | ✅ **RUN 2026-08-22 — RED, exactly 1: `aSiblingProjectsJobIsUnreachable`.** MATCH. **This is the D1 control the run plan asked for**: the §119 two-project fixture really can see a dropped `project_id` predicate, so the other eleven rows of that family are blind for want of a fixture — not green because the predicate is redundant. |
| IH-2 | `IngestionReadService.list`/`findOne` gate on `writeGranted(…, "result:submit")` instead of `readVisible` | A Viewer can no longer read why a test produced no result — the history becomes an administrator's screen for no reason, and the binder's read gets a stricter gate than its write needs. Should redden `aViewerMayRead` alone. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `aViewerMayRead`.** MATCH, and *alone* as the row asked. §A3 discriminates: a Viewer holds `project:read` and not `result:submit`. |
| IH-3 | `UnmatchedTests.landed` drops the `recorded` arm (matched ⇒ landed) | A test whose case is real but is **not in the run the caller named** disappears from the detail read while still counting in `unmatchedCount` — the two populations §117 deliberately unions come apart, and the screen shows fewer names than the number beside them. Should redden `theDetailAgreesWithThePost` on a fixture that names a `runId`; the current fixture does **not** build that state, so this is a manual check and IN-12's sibling. | ⬜ NOT RUN — needs IN-12's fixture |
| IH-4 | `IngestionService`'s replay serves `List.of()` again | `unmatchedCount: 12` beside `unmatched: []` on the response to a CI **retry** — the caller that most needs the names, on the second time one build failed to bind the same tests. Should redden `aReplayCarriesTheNames` alone. | ⬜ NOT RUN |
| IH-5 | `findSummariesInProject` selects the entity rather than the constructor projection | No assertion changes and no test goes red — the defect is a `JSONB` blob per row pulled across to render a list that shows none of it. **Recorded because it is invisible to the suite**: the check is the query log on a project with a 5,000-test report in its history, which is the shape of the thing this projection exists for. | ⬜ NOT RUN — not assertable, measure instead |
| IH-6 | The list's `ORDER BY` loses its `j.id DESC` tiebreak | Two jobs posted inside one microsecond — a matrix build does it every time — swap places between reloads, moving the row somebody is reading out from under the cursor. Not reliably reddenable: the fixture's two posts differ in `createdAt`, so this needs two rows written with an identical timestamp. | ✅ **RUN 2026-08-22 — ZERO**, as the row predicted, and for the reason it gave. **BLIND — re-run after wave 2's same-instant fixture** (D8); recorded as a held row, not as coverage. ✅ **RUN 2026-08-23 (wave 2) — RED, exactly 1**, against the new `SameInstant` group. `theListIsSummaries` stayed green, which is the fixture's own claim measured: two posts milliseconds apart never consult the tiebreak. ⚠️ `theOrderIsRepeatable` stayed green too — Postgres returned the same **wrong** order twice, so repeatability is not correctness. |
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
| DR-5 | The `ON CONFLICT` names every signal column rather than three | This job becomes the author of claims it has no evidence for, and — new in this slice — it now blanks a **live** sibling's work rather than a hypothetical future one's: `reporting.ExecutionSource` writes `failing_repeatedly` into these same rows on its own schedule. Should redden `otherSignalsAreNotClobbered`. **Worth reading the failure rather than just the colour**: that test seeds `stale_approval`, which no job writes, so it does not exercise the two-live-writers race it now describes. | ⬜ NOT RUN ✅ **RUN 2026-08-23 (wave 2) — RED, 57**, with eight sibling columns added to the set-list. 57 is the blast radius rather than a measurement of the row, and the reason it is still worth having: the two directional tests of wave 2's `LiveWriters` group are among the reds, so a fixture where both values are **derived** discriminates here exactly as the hand-set boundary test does. |
| DR-6 | `HealthSignal.UNDECLARED_AUTOMATION` is declared **before** `ORPHANED` instead of appended | `CaseSearch.tallyHealth` selects one aggregate per constant and reads the results back **by position**, so every facet count after the insertion point lands on the wrong tile — the dashboard reports the right numbers against the wrong names. **This row may redden nothing, and that would be the finding**: the declaration-order rule is stated in a comment and, as far as this slice could tell, held by no test. | ⬜ NOT RUN |
| DR-7 | `HealthSignal.UNDECLARED_AUTOMATION`'s attribute is misspelled `"undeclaredAutomatio"` | Every Criteria filter on the signal throws `Unable to locate Attribute` at runtime while the chip, the tile and `CaseHealth.signals()` all keep rendering — one broken filter among ten working ones. Should redden `CaseHealthAttributesTest.everyAttributeResolves` alone, which is the class §47 said existed and did not until this slice. | ⬜ NOT RUN |
| DR-8 | `CaseHealthRecomputeService` runs the two statements in **two** transactions | Half a tick's signals stamped with this tick's `computed_at` if the second fails — a row claiming to have been derived just now, half of which was not. **Not assertable by a test** without a fault injected into the second statement; recorded because the single transaction looks like an accident of style and is not. | ⬜ NOT RUN — not assertable |
| DR-9 | `CaseHealthProperties`' positive-duration check for `staleAutomation` removed, with `P0D` configured | The cutoff lands at `now`, so every case with any ingested result becomes stale and every case without one reads clean — **the pair inverts** rather than merely over-firing, and the dashboard shows a project whose automation all died at once while the unautomated cases look healthy. **No test exists for either half of this check**, `longUnexecuted`'s included; the row exists to say so. | ✅ **RUN 2026-08-22 — RED, 3, and the row is half wrong.** Check removed **and** `stale-automation` set to `P0D`; scope widened to `CaseHealthRecomputeTest` + `CaseHealthAttributesTest` + `ConfigConventionTest`. The **inversion is observed**: `AUTOMATED, and CI reported inside the window, raises nothing`, `a MANUAL case taking CI results is UNDECLARED_AUTOMATION` and `NOT_AUTOMATABLE taking CI results is undeclared too` all fall. What has no test is the **guard itself** — nothing asserts that a non-positive window refuses to *start*, and `ConfigConventionTest` (the lane that reads `application.yml` as text) stayed green. **The finding is narrower than the row claimed and still real**: the consequence is covered, the fail-fast is not. |
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
| FQ-8 | The recompute's `ON CONFLICT` set-list becomes `EXCLUDED.*` | **Every quarantined test is released on the next tick** — silently, in the reassuring direction, presenting as a board whose quarantine column empties itself overnight with no action recorded by anyone. The first time the own-your-own-columns rule binds two writers of one *table* rather than of one row across modules. Should redden `theAuthoredHalfIsUntouchable`. | ⬜ NOT RUN ✅ **RUN 2026-08-23 (wave 2) — RED, exactly 1.** MATCH. The one `D3` row that already had a two-**live**-writers fixture: `theAuthoredHalfIsUntouchable` calls the real quarantine writer rather than an `UPDATE`. |
| FQ-9 | `ef.executed_at >= :cutoff` removed (unbounded window) | The score can never go false: a test that flapped badly last spring and has been solid since stays flagged for ever. §120's rule for `UNDECLARED_AUTOMATION`, arriving from the other direction. Should redden `theWindowIsBounded`. | ⬜ NOT RUN |
| FQ-10 | `ef.execution_id` removed from the `ORDER BY` inside the window | Two results recorded in the same microsecond — what a CI run posting a batch produces — resolve differently per tick, so the score flickers between two values with no input moving. **This row may redden nothing**: the fixture spaces results a day apart, so `isIdempotent` cannot observe it without a same-instant pair. That would be the finding. | ⬜ NOT RUN — may redden nothing ❗ **RUN 2026-08-23 (wave 2) — ZERO.** One of decision 1's six undetermined rows, settled: **it is a gap.** The fixture spaces results a day apart, so the `lag()` window's `execution_id` tiebreak never decides anything. The fourth sighting in this wave of one missing fixture. |
| FQ-11 | `CaseQuarantineRequest.quarantined` becomes a primitive `boolean` | An absent field arrives as `false`, so a client sending only a `reason` **releases a quarantine it never mentioned** and gets a 200. The one shape of this request that destroys a decision by omission. Should redden `theStateFieldDoesNotDefault`. | ⬜ NOT RUN |
| FQ-12 | `CaseFlake.release()` clears the flag and leaves `quarantineReason` | A row saying "not quarantined" beside "quarantined by L. Lead — flaky on Firefox", read in whichever order the screen puts them. The reason field is exactly the one a card renders without checking the flag first. Should redden `aReleaseLeavesNoResidue`. | ⬜ NOT RUN |
| FQ-13 | `case_flake_quarantine_complete` dropped from `V38` | The reason becomes optional at the storage layer, so any future caller — a bulk action, a board drag — can quarantine without one. **Not assertable by the current suite**, whose only writer already sends all four; recorded because the constraint is what stops the one-argument toggle from being addable, and because it is the check that caught this slice's own fixture writing a null `quarantined_by`. | ⬜ NOT RUN — not assertable without a second writer |
| FQ-14 | `CaseQuarantineService` looks the flake row up with `findById` rather than `findScopedById` | The Hibernate company filter does not apply to the primary-key path (`TestCaseRepository.findLiveInProject`'s javadoc). RLS still covers it, so **this may redden nothing** — which is the point of the row: it removes one of two layers, and a suite that cannot see the difference is exactly why the rule is written down rather than tested. | ✅ **RUN 2026-08-22 — ZERO, as the row said it might, and that is the finding.** RLS still bounds the primary-key path, so removing the Hibernate company filter is invisible to the suite. One of the two layers is gone and nothing goes red — which is exactly why the rule is written down rather than tested. |
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
| GA-10 | The `log.info` on a successful grant is removed or dropped to `DEBUG` | An instance's log can no longer answer *"when did this account become an operator"* without a database read — for the widest grant the product has, one the roles spec calls a disclosed and audited capability. **No test asserts on logging**; the row exists to say so. | ✅ **RUN 2026-08-22 — ZERO, confirming the row.** `log.info` → `log.debug`; `GrantInstanceAdminServiceTest` is entirely green. Nothing in this suite can observe a log line, for the widest grant the product has. **Closes with** a logback `ListAppender` (or `OutputCaptureExtension`) arm on `grantsToAProvisionedAdmin`. |

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
| §124 AG-1 | `AggregationPolicy.BEST` is restored to **one** of the two enums (either one) | `AggregateExecutionPolicyTest` pins the two name sets equal, so the copies cannot drift. This is the check that made the removal landable at all, and it matters more for a deletion than an addition: a missing value fails closed and loudly, while a copy that still *accepts* a retired policy answers a number the plan page can no longer produce. | ⚠️ **RUN 2026-08-23 (wave S) — BOTH COPIES TRIED, AND NEITHER REACHES A TEST: the build does not compile.** Adding `BEST` to `execution.AggregationPolicy` fails at `PlanStatisticsService.java:490` (*the switch expression does not cover all possible input values*); adding it to `reporting.AggregationPolicy` fails at `AggregateExecutionReport.java:846`. **An exhaustive switch on each copy is the guard, and it fires before any test runs** — a stronger and cheaper protection than the row assumed. The class the row names, `AggregateExecutionPolicyTest`, does not exist. `ContractEnumTest.contractEnumsAndTheirServerTypesAgree` remains the observer for the *other* direction, a constant leaving one copy only, which `§161 CE-M1` already ran RED. |
| §124 AG-2 | `PlanController` falls back to `DEFAULT` for an unparseable policy instead of 400ing | A bookmark, a saved URL or a screenshot's query string carrying `BEST` answers a **different question under the same name**, silently and in the flattering direction — which is the exact harm the removal exists to prevent, reintroduced by the error path. Should redden `bestIsRefused` and `anUnknownPolicyIsRefused`. | ⬜ NOT RUN |
| §124 AG-3 | `WORST_FIRST`'s severity order is reversed, restoring `BEST`'s behaviour under `WORST`'s name | The dropdown says "worst result per case" and computes the best. **Worse than shipping `BEST`**, which at least said what it did. Should redden `worst`, `noPolicyClearsTheBlockerList` and `thePoliciesActuallyDiffer`. | ⚠️ **RUN 2026-08-23 (wave S) — RED, 3 — two of the three predicted, plus one.** `worst` and `noPolicyClearsTheBlockerList` fell; **`thePoliciesActuallyDiffer` stayed green**, and `worstIgnoresUnrecordedRows` fell in its place. The policies still differ from each other under the inverted comparator — they are simply all wrong — which is why a difference test cannot catch a reversal and a *value* test can. |
| §124 AG-4 | `criteriaFollowTheDisplayedCounts`' tolerance is tightened back to `within(0.01)` | *(Not a mutation — the finding.)* `actual` is rounded to one decimal for display, so three of thirteen is served as `23.1` against an unrounded `23.0769…`. **This was the phase's only red run**, and the honest fix was the tolerance and not the endpoint: a test asserting the unrounded value asserts against a number no screen shows. | ✅ **FOUND AND FIXED** — `within(0.05)`, with the rounding named in a comment |
| RX-1 | `projects.holds(userId, projectId, EXECUTE_RUN)` is deleted from `ExecutionService.record` | **RUN.** A leaked CI token can hand-record verdicts through the endpoint a tester uses — with an `If-Match`, with evidence, indistinguishable in the audit trail from a person. | ✅ **RUN — reddened exactly the three new tests** (`theRobotCannotExecuteByHand`, `theTwoRefusalsGiveOppositeAdvice`, `theRefusalsNameNoKey`) and nothing else |
| RX-2 | The `run:execute` check moves **in front of** the verdict gate | The draft this slice started from. A Viewer, who holds neither key, is told to post to the ingestion endpoint they may not use either — advice that is confidently wrong, which is worse than a flat refusal. Should redden `theTwoRefusalsGiveOppositeAdvice` and `viewerIsRefused`. | ⚠️ **RUN 2026-08-23 (wave S) — RED, 3.** MORE than the 2 predicted, and the extra is coherent: `viewerIsRefused` and `theTwoRefusalsGiveOppositeAdvice` both fall as the row says, and `the amend refusal is still its own sentence` falls beside them — the amend path runs through the same gate, so hoisting the `run:execute` check turns its 403 into the ingestion advice too. **Order really is the point**, and three tests say so rather than two. |
| RX-3 | `IngestionService`'s gate is changed from `result:submit` to `run:execute` | The mirror image, and the one that breaks the product rather than opening it: Automation Service is the one role without `run:execute`, so **every CI credential 401s on the endpoint built for it** and ingestion has no caller at all. Should redden most of `IngestionEndpointTest`. | ✅ **RUN 2026-08-23 (wave S) — RED, 35.** MATCH against the row's own qualitative prediction (*"most of `IngestionEndpointTest`"*): the four-step ladder, attribution, idempotency, the history and the verbatim upload all fall. §A3 discriminates hard — Automation Service is the one role holding `result:submit` without `run:execute`, and the whole class runs on a CI credential, so the endpoint built for CI has no caller at all. |
| RX-4 | `Outcome.NotExecuting` is mapped to 404 rather than 403 | A CI job that is posting to the wrong endpoint is told the run does not exist, and goes looking for a run that is right there. The 404-not-403 rule is about concealing existence from callers who cannot *read*; this caller has already passed the read gate. Should redden the status assertion in `theRobotCannotExecuteByHand`. | ⬜ NOT RUN |
| AD-1 | `ArtifactWriteService.delete` gates on `PERMISSION` (`artifact:edit`) instead of `DELETE_PERMISSION` | **RUN.** Every Test Engineer can remove a lead's sign-off record from the register — in the one module where the artefact being destroyed *is* the audit trail. Compiles, reads naturally, and is one word. | ✅ **RUN — reddened `theEngineerCannotDeleteWhatTheyMayCreate` alone**, which is the discrimination the key exists for |
| AD-2 | `artifacts.findInProject(...)` becomes `artifacts.findById(...)` | Everything else lines up for the delete to succeed: the row is in the caller's own company so RLS admits it, the caller holds `artifact:delete`, and the project named in the path is one they are fully granted on. **Only the predicate stops it.** Should redden `anotherProjectsArtifactIsNotReachable`. | ✅ **RUN 2026-08-22 — RED, exactly 1: `anotherProjectsArtifactIsNotReachable`** (the `Deletion` copy — the name occurs three times in the class). MATCH. ⚠️ **Scope correction**: the manifest carried `PlanStatisticsEndpointTest`, this section's first-named class; the `AD-*` rows are `ArtifactEndpointTest.Deletion`. That is trap two, and the runner's scope guard is what caught it. |
| AD-3 | The `write_once` refusal is deleted | A sign-off record is deletable, and the column's `V1` comment becomes a comment about nothing. Nothing in the product sets the column yet, so **this cannot be reached by any user today** — the guard is written ahead of its writer on purpose, for `Company.suspend()`'s reason. Should redden `aSignOffRecordCannotBeDeleted`, which reaches it by SQL. | ⬜ NOT RUN |
| AD-4 | The `isSupersedingSomething` refusal is deleted | A foreign-key violation surfaces as a **500** on a request the caller cannot fix. This is the edge `Artifact`'s javadoc said did not exist. Should redden `theReplacementCannotBeDeleted` — and note its second half must keep passing: the *other* end of the same edge is still deletable, so a mutation that refuses both directions is also wrong. | ⬜ NOT RUN |
| AD-5 | `auditRemoval` is moved to *after* `artifacts.delete(artifact)` | The audit row is written from a detached entity, or from fields read after the delete — and in the rollback case the trail records a removal that did not happen. Should redden `theRemovalIsAudited` if it throws; **may redden nothing** if Hibernate's flush order happens to save it, which is the finding. | ⬜ NOT RUN — may redden nothing |
| AD-6 | `auditRemoval` carries only the artifact id, dropping title/kind/url | The one case where an audit event **must** carry content rather than a pointer: the row it names is about to stop existing, so *"who took the compliance link out of the register"* becomes unanswerable. Should redden `theRemovalIsAudited`. | ✅ **RUN 2026-08-22 — RED, exactly 1: `theRemovalIsAudited`.** MATCH. The payload is asserted, not merely written. |
| AD-7 | The delete soft-deletes (sets `ARCHIVED`) instead of removing the row | Reads as more careful and quietly changes what the endpoint means: `ARCHIVED` is §6n's *"no longer relevant, kept for the record"*, so a mis-registered link stays in the register asserting something untrue, and the register grows a second lifecycle nobody asked for. There is no `deleted_at` on `artifact`, which is the schema's own answer. Should redden `theLeadDeletes`' `countArtifacts` assertion. | ✅ **RUN 2026-08-22 — RED, 2.** MORE than the 1 predicted, and the extra is the right extra: `theLeadDeletes` **and** `a Test Engineer deletes what they may create since V52`, which counts rows the same way. Soft-deleting is caught from both personas, not only the lead's. |

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
| ST-3 | `ModuleStructureTest`'s expected list keeps ten entries, dropping `"storage"` | Confirms the list is a *deliberate edit* rather than decoration — a new module must fail the build until somebody writes it down and reads the `allowedDependencies` rule they are now subject to. Should redden `detectedModulesMatchTheSourceTree`. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `detectedModulesMatchTheSourceTree`.** MATCH. The list is a deliberate edit, not decoration. |
| ST-4 | `execution/package-info.java` drops `"storage"` from `allowedDependencies` | Should redden `modulesVerify` with the offending edge named. The check that the declaration is a *fact about code that exists*, not a forecast. | ⬜ NOT RUN |
| ST-5 | `com.tcms.storage` gets a class importing `com.tcms.execution.TestExecution` | The cycle that would make the isolation-adjacent module jointly deployable with a feature module. Should redden `storageDependsOnTenancyAlone` and `modulesVerify`. | ⬜ NOT RUN |
| ST-6 | `Attachment.forStepResult` writes `OWNER_EXECUTION` | Every step-level screenshot is filed against the execution instead of the step result, so a future sweep by owner finds it in the wrong place and the step panel shows nothing. **The one thing in the moved file that is behaviour rather than structure**, and the reason the owner vocabulary being one file matters when #49 adds three more. Should redden `ExecutionEndpointTest.EvidenceAttachment`'s step-scoped assertions. | ⬜ NOT RUN |
| ST-7 | `FilesystemReportStore` is constructed with `"${tcms.storage.evidence.root}"` | **The failure that keeps the two ports two types**, arriving through the other door. Reports are written into the evidence root: nothing throws, nothing 404s while the box is untouched, and the two directories' different backup and retention treatment silently stops meaning anything. Should redden `Adapters.reportAdapterRoundTripsAndRefusesTraversal`'s message assertion — and note it would NOT redden a round-trip-only test, which is why that test asserts the kind. | ⬜ NOT RUN |
| ST-8 | `FilesystemBlobStore.resolve`'s `startsWith(root)` check is deleted | A key climbing out of the root writes anywhere the process can write. Unreachable through the API today — keys are server-generated UUIDs — which is exactly why the guard is tested directly. Should redden `refusesTraversal` **and both adapter tests**, which is the property phase 2 bought: before it, this mutation applied to the report copy reddened nothing at all. | ⬜ NOT RUN |
| ST-9 | The `kind` label is dropped and every message reads `"Could not store bytes under …"` | An operator cannot tell which root is full or gone from the log line, and the two are configured separately precisely because they have different loss consequences. Should redden `theMessageNamesTheKind`. | ⬜ NOT RUN |
| ST-10 | `FilesystemBlobStore.delete` uses `Files.delete` instead of `deleteIfExists` | Evidence deletion runs after the rows are gone, so a retry of a half-completed delete answers 500 for bytes that are absent *because the first attempt succeeded*. Should redden `missingKeyIsAbsent`. | ✅ **RUN 2026-08-22 — RED, exactly 1: `missingKeyIsAbsent`.** MATCH. 5 s, because this class needs no container. ⚠️ **Scope correction**: the manifest carried `ArchitectureRulesTest`, this section's first-named class; the row's own observer is `FilesystemBlobStoreTest`. |
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
| CA-1 | `CaseAttachmentService.delete` uses `writeGrantedByAuthorship` with `evidence:delete`'s `OWN` arm | **The mutation this slice is most likely to receive as a "fix".** It reads as obviously safer and is incoherent: `case:edit` already lets a Test Engineer rewrite every step of a lead's case, so refusing them the screenshot makes the file stricter than the step referencing it. Should redden `Gates.anEngineerRemovesSomebodyElsesFile`, and **only** that test — if it reddens nothing, the decision is a comment. | ❗ **RUN 2026-08-22 — ZERO. The row's own condition is met: the decision is a comment.** The gate really was swapped to `writeGrantedByAuthorship(evidence:delete)` with a live `mayActOn(row.getCreatedBy())` arm, and nothing moved — for **two independent reasons**, either of which alone would do it. (1) **`V51` (§207) removed `OWN` from every grant in the catalogue** — its own header says so in capitals — so `mayActOn` answers true for everybody and the authorship half is inert *by construction*. The mutation the row was written to fear is no longer expressible. (2) The **key** half cannot discriminate either: roles spec §A3 grants `case:edit` (line 145) and `evidence:delete` (line 193) to the **same four roles**. `DA-1`'s family, with a new aggravation — here it is not one migration invalidating one test but a *pair of keys that no seeded persona can tell apart*. **What would close it:** not a persona (none exists), but a **catalogue-derived convention test** that fails when a gate's key is interchangeable with the constant a plausible mutation would substitute — i.e. a check that a permission swap is observable at all. `CA-7` proves the test itself is alive: it reddens when the delete stops deleting. |
| CA-2 | `attach` is gated on `case:read` instead of `case:edit` | Any Viewer — often the client themselves — can put files on a case definition. Should redden `Gates.viewerCannotAttach` **and** `automationServiceCannotAttach`; if only the first reddens, the CI-credential property is untested. | ✅ **RUN 2026-08-23 (wave S) — RED, 2.** MATCH, and the row's conditional is answered: **both** arms fell, so the CI-credential property is tested and not only the Viewer's. §A3 discriminates cleanly — `case:read` is held by every role including Viewer and Automation Service, `case:edit` by IA/CA/TL/TE only. |
| CA-3 | The download is gated on `case:edit` | The invented-permission failure, arriving as over-caution: a Viewer sees a paperclip they can never open, and no administrator can grant `attachment:read` because it does not exist. Should redden `Gates.viewerCanDownload`. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `viewerCanDownload`.** MATCH. The gate was added in front of `resolve`, which is the download's only gate; §A3 discriminates via the Viewer, who holds `case:read` and not `case:edit`. |
| CA-4 | `AttachmentRepository.findOwnedById` drops the `ownerId` half of its predicate | `/cases/{a}/attachments/{belongs-to-b}` serves and deletes another case's file with a valid grant on both. Should redden `Scoping.attachmentIsScopedToItsOwner` — the row that exists because the id is real, the case is real, and the pair is still wrong. | ✅ **RUN 2026-08-22 — RED, exactly 1: `attachmentIsScopedToItsOwner`.** MATCH. `findOwnedById` is the **shared** two-segment gate, so per the run plan's D7 this one measurement answers for `DA-7` and `FA-7` as well. |
| CA-5 | `write` skips `cases.findLiveInProject` and trusts the project gate | `/projects/{granted}/cases/{ungranted}` attaches to a case the caller was never given. Should redden `Scoping.caseMustBeInThatProject`. | ⬜ NOT RUN |
| CA-6 | `remove` deletes the bytes **before** flushing the row delete | The inverted ordering. A failure between them leaves a row whose download 404s — recoverable — replaced by a thumbnail that renders and never opens. **Should redden nothing**, because a single-threaded test cannot arrange the failure; recorded so the ordering is not read as arbitrary and quietly swapped. | ⬜ NOT RUN — expected to redden nothing |
| CA-7 | `remove` soft-deletes (keeps the row, leaves the blob) | A deletion that did not delete, on the one thing here a customer may need removed for privacy rather than tidiness. Should redden `Writes.deleteRemovesTheBytes` on both the row count and `store.exists`. | ✅ **RUN 2026-08-22 — RED, 2.** MORE than the 1 predicted, and the extra one **refines `CA-1`**: `anEngineerRemovesSomebodyElsesFile` also falls, so that test is *alive* and does observe the delete's effect. What is dead is only its ability to see the **gate**. |
| CA-8 | `BlobUpload.key` uses the sanitized filename as its last segment instead of a fresh UUID | Two files of the same name on one case overwrite each other's bytes silently — the second upload's row points at the first's content, and both download identically. Should redden `Writes.theKeyIsAllServerUuids`. | ⬜ NOT RUN |
| CA-9 | `BlobUpload.sanitizeFilename` returns its argument unchanged | The traversal name reaches the column and the UI. Harmless to the filesystem — the key never contains it — and alarming on screen, which is the second reason the reduction exists. Should redden `Writes.theKeyIsAllServerUuids`' filename assertion and **not** its key assertion. | ⬜ NOT RUN |
| CA-10 | `CaseAttachmentController` sets `contentType` to `null` when the part declares none | A `NOT NULL` violation at flush, i.e. a 500 for an upload from any client that omits the header — `curl -F` among them. Should redden `Writes.missingContentTypeDefaults`. | ⬜ NOT RUN |
| CA-11 | The uploader is resolved in the **controller**, after the service's transaction closes | §121's exact defect, one slice on: `CaseOwners` reads through the tenancy filter, so with no `app.current_company` it names nobody. Fails **closed** — `uploadedBy` is simply null on every successful upload and nothing throws. Should redden `Gates.engineerAttaches`' `uploadedBy` assertions. | ⬜ NOT RUN |
| CA-12 | `CaseRenderer.attachmentsFor` uses `Map.of()` on the empty-uploaders arm | §38's trap, third sighting. `created_by` is nullable, so `Map.of().get(null)` throws by contract — every case detail 500s in exactly the batches where no uploader resolves, and works in the batches where one does. Should redden `Detail.filesRenderInUploadOrder`. | ⬜ NOT RUN |
| CA-13 | `attachmentsFor` keys the returned map on the attachment id instead of `owner_id` | Every case renders an empty list while rows exist — the grouping bug that is invisible on a one-case fixture. Should redden `Detail.filesRenderInUploadOrder` and **not** `Detail.listIsScopedToItsCase`, which passes for the wrong reason. | ⬜ NOT RUN |
| CA-14 | `TestCaseDetailView.attachments` is serialized as `null` when empty | Two spellings of nothing for the SPA to tell apart — the defect §121 phase 4 found on the neighbouring field, reintroduced on a field that has no third state to justify it. Should redden `Detail.emptyIsAList`. | ⬜ NOT RUN |
| CA-15 | `attachmentsFor` is called per case inside `CaseBulkService`'s loop rather than once per batch | 500 cases, 1000 queries, one bulk operation. Should redden nothing — **that is the finding**: no test in this repository counts queries, so every N+1 the renderer's two-step form prevents is prevented by a comment. | ⬜ NOT RUN — expected to redden nothing |
| CA-16 | `V39` re-adds `'STEP'` to the `CHECK` | #50 undone. Should redden `OwnerTypes.stepIsNotInTheConstraint` **and** `theEnumAndTheConstraintAgree`, which is the pair: one names the value, the other derives the whole set from `AttachmentOwner`, so neither alone would survive somebody "helpfully" adding the enum constant back too. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 2.** MATCH, and **the pair is the point**: `stepIsNotInTheConstraint` (which names the value) and `theEnumAndTheConstraintAgree` (which derives the whole set from `AttachmentOwner`) both fall, so adding the enum constant back too would not have bought the mutation a green build. |
| CA-17 | `AttachmentOwner` gains a `STEP` constant with no migration | The mirror of CA-16, and the direction a reviewer is likelier to take: the enum grows and the database does not, so the first row written raises a constraint violation in production. Should redden `OwnerTypes.theEnumAndTheConstraintAgree`. | ⬜ NOT RUN |
| CA-18 | The empty-part guard in the controller is removed, leaving only the service's `sizeBytes <= 0` check | Should redden nothing, and that is the point: the two checks are deliberately not one, because the service's is what a future non-controller caller meets. Recorded so the apparent duplication is not collapsed. | ⬜ NOT RUN — expected to redden nothing |
| CA-19 | `AuditEvent.record` for the removal drops the filename from `changes` | Once the row and the bytes are gone, that line is the **only** surviving statement the file existed — so *"who removed the signed-off spec"* becomes unanswerable. Should redden `Writes.auditKeepsWhatTheDeleteDestroys`. | ✅ **RUN 2026-08-22 — RED, exactly 1: `auditKeepsWhatTheDeleteDestroys`.** MATCH. |
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
| DA-1 | `DocPageAttachmentService.EDIT_DOC` becomes `doc:publish` | **The mutation this phase is most likely to receive**, and it is a plausible misreading rather than a typo: attaching a signed-off PDF *sounds* like a publication act. It leaves a Test Engineer able to author a specification they cannot illustrate, and every other doc write they make keeps working, so nothing else in the suite notices. Should redden `Gates.anEngineerWithoutPublishCanAttach` and **only** that test. | ❗ **RUN 2026-08-22 — ZERO. A FINDING, and not the one the row expected.** `anEngineerWithoutPublishCanAttach` did not move, because **`V52` (§210, 2026-08-15) granted Test Engineer `doc:publish`**. The persona now holds `doc:edit` **and** `doc:publish`, so swapping the gate between them is unobservable — the test passes under either constant and has done since eight days after §126 phase 2 wrote it. Its own `@DisplayName` still reads *"A TEST ENGINEER WITHOUT doc:publish CAN ATTACH"*, which is now false. This is the `company:teams` / `company:members` family from the foot of this file, with two aggravations: the claim is **stated in the test's name**, and it was true when written and was invalidated by a later migration with nothing to catch it. `tierClaims.test.ts` catches exactly this shape in `packages/**` comments and does not read Java. **Repair:** re-persona the test on a role that holds `doc:edit` without `doc:publish`, or delete the claim and keep the 201. |
| DA-2 | `delete` uses an `OWN`-qualified gate, copying `evidence:delete` | `CA-1` one module over, and it reads as safer here too. Incoherent: `doc:edit` already deletes the lead's paragraph beside the image. Should redden `Gates.anEngineerRemovesSomebodyElsesFile`. | ⚠️ **RUN 2026-08-23 (wave S) — GREEN. ZERO-UNEXPECTED, and it is `CA-1`'s finding one module over.** `delete` was switched to `writeGrantedByAuthorship("evidence:delete")` with the callback asking `mayActOn(row.getCreatedBy())`, and nothing moved — for **two independent reasons**, both derived from the catalogue before the run: `V51` (§207) removed `OWN` from every grant, so `canDoOnAuthored` returns the unqualified answer and the authorship arm is inert; and roles spec §A3 gives `doc:edit` and `evidence:delete` to the **same four roles**, so the key swap cannot discriminate either. The test is alive — `DA-3` and `DA-4` redden in the same class. What is dead is its sight of the gate, and no seeded persona can close it. |
| DA-3 | `attach` is gated on the project read gate instead of `doc:edit` | Any Viewer can put files on somebody's specification. Should redden `Gates.viewerCannotAttach`. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `viewerCannotAttach`.** MATCH. §A3 discriminates: a Viewer holds `project:read` and not `doc:edit`. |
| DA-4 | The download asks `doc:edit` | The invented-permission failure arriving as over-caution: a Viewer sees a paperclip they can never open, and there is no `attachment:read` to grant. Should redden `Gates.viewerCanDownload`. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `viewerCanDownload`.** MATCH, by the Viewer, as `CA-3` one module over. |
| DA-5 | `write` refuses when `page.getStatus() == ARCHIVED` | The status arm that was deliberately **not** written. It reads as correct — archived means finished — and makes an archived page's files unremovable by anybody while the page's own `PATCH` still edits freely. Should redden `Gates.archivedPagesStillAcceptFiles`. | ⬜ NOT RUN |
| DA-6 | `write` skips `pages.findLiveInProject` and trusts the project gate | `/projects/{granted}/doc-pages/{ungranted}` attaches to a page the caller was never given. Should redden `Scoping.pageMustBeInThatProject`. | ⬜ NOT RUN |
| DA-7 | `resolve`/`remove` call `attachments.findById` instead of `findOwnedById` | `/doc-pages/{a}/attachments/{belongs-to-b}` serves and deletes another page's file with a valid grant on both. Should redden `Scoping.attachmentIsScopedToItsOwner`. | ⬜ NOT RUN |
| DA-8 | `DocPageWriteService.edit` passes `List.of()` for the attachments instead of re-reading them | **The subtle one.** `PATCH` answers with the whole page and the SPA renders that answer, so a save appears to delete every file on the page until the next `GET` puts them back — no error, no log line, and it looks like the upload was the thing that failed. Should redden `Detail.anEditDoesNotEmptyTheList`. | ⬜ NOT RUN |
| DA-9 | `DocPageAttachmentView` drops `uploadedBy` (or `CaseAttachmentView` gains a field this one lacks) | The cost of one wire schema served by two records: the response omits a required field on one owner only. Should redden `Detail.filesRenderInUploadOrder`; the *divergence itself* is caught by nothing — recorded because the two records are the standing hazard of this design and only the contract test sees them together. | ⬜ NOT RUN |
| DA-10 | `PageAttachments.forPage` uses `Map.of()` for the uploader lookup | §38's trap, fourth sighting. `created_by` is nullable, so `Map.of().get(null)` throws by contract — every page detail 500s whenever no uploader resolves. Should redden `Detail.filesRenderInUploadOrder`. | ⬜ NOT RUN |
| DA-11 | `remove` soft-deletes, or deletes the bytes before the row | `CA-6`/`CA-7` on this owner. The soft-delete half should redden `Writes.deleteRemovesTheBytes`; the reordering half should redden **nothing**, because a single-threaded test cannot arrange the failure — recorded so the ordering is not read as arbitrary. | ⬜ NOT RUN |
| DA-12 | The removal's audit row drops the filename | Once the row and the bytes are gone that line is the only surviving statement the file existed. Should redden `Writes.auditKeepsWhatTheDeleteDestroys`. | ✅ **RUN 2026-08-22 — RED, exactly 1: `auditKeepsWhatTheDeleteDestroys`.** MATCH — `CA-19`'s shape on the second owner, and it holds here too. |
| DA-13 | The audit target becomes `attachment` with the attachment's own id | The row survives the hard delete keyed on an id that no longer names anything, so "what happened to DOC-14" cannot be answered from the page's trail. Should redden `Writes.auditNamesThePage`. | ❗ **RUN 2026-08-22 — ZERO. A FINDING: the test named as the observer observes a different event.** The removal's audit really was retargeted to `target_type = "attachment"` keyed on the attachment's own id, and `auditNamesThePage` did not move — because it asserts on `auditChanges(ACME, "doc_page.attachment.add")`, the **add**, while its own comment argues about *"a row keyed on an id that no longer exists after the hard delete"*. It also asserts only the `changes` JSON and never `target_type` or the target ids, so it could not see this mutation even on the right event. **Repair:** assert on `doc_page.attachment.remove`, and on the target rather than the payload. |
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
| FA-1 | `createFile` uses `projects.writeGranted` instead of `writeGrantedByRowScope` | **The mutation this phase is most likely to receive.** The file create is the second call site of the row-scope gate, and the plain form compiles, reads as simpler, and hands a Test Engineer the project's test strategy — a document every plan inherits read-only. Should redden `Gates.anEngineerCannotUploadAtProjectScope` and only that test. | ❗ **RUN 2026-08-22 — ZERO, and the row is STALE rather than the suite weak.** `V52` (§210) left the **`PLAN_SCOPE` qualifier carried by no seeded row**, so the file-create `Gates` arms cannot discriminate the two scopes at all — the second `V52` casualty in this file after `DA-1`. ⚠️ **Corrected after `§229 PM-15` measured the same gate**: this was first written as *inert by construction*, and it is not. The seam is still wired, and `PM-15` reddens because `ArtifactEndpointTest.Correcting` **grants the qualifier by hand** — its own header says that is *"the only way this suite can see the seam the roles spec §A5 says must stay wired"*. **The repair is to copy that hand-granted persona into `ArtifactFileEndpointTest.Gates`.** **`anEngineerCannotUploadAtProjectScope` no longer exists**: it was replaced by `anEngineerUploadsAtProjectScope` — *"a Test Engineer uploads at PROJECT scope since V52 — the qualifier is gone"* — and survives only as a `{@link Gates#anEngineerCannotUploadAtProjectScope()}` in the class javadoc, which `javac` does not resolve, so it compiles. The class header above it still argues the qualifier is load-bearing. **Repair:** rewrite the header and delete the dead `{@link}`. The runner's scope guard was *satisfied by that comment* and has since been hardened to reject a comment-only match — see the run record. |
| FA-2 | `createFile`'s `mayActOn` argument is inverted (`!= ArtifactScope.PLAN`) | The qualifier over-applied — the mirror failure, and the one that reads as caution: the register becomes Lead-only for uploads, which the roles spec's ✓ explicitly is not. Should redden `Gates.anEngineerUploadsAtPlanScope`. | ⬜ NOT RUN |
| FA-3 | The artifact row is saved before the attachment row in `writeFile` | The foreign key `artifact.attachment_id` is checked at statement time, so this is a constraint violation the caller reads as a 500 on every upload. Should redden every test in `Registration` — a **loud** failure, recorded because the ordering looks like style until you know which direction the FK runs. | ⬜ NOT RUN |
| FA-4 | `writeFile` writes the bytes before flushing the rows | The half that does not roll back going first: a failed transaction leaves a blob no row names — unreachable, uncollectable, invisible. Should redden **nothing**, because a single-threaded green-path test cannot arrange the failure. Recorded so the ordering is not read as arbitrary. | ⬜ NOT RUN — expected to redden nothing |
| FA-5 | `Artifact.register` ignores its `attachmentId` argument (leaves the column null) | The register renders a `FILE` with no filename, no size and no URL — a row the reader can see and cannot open — while the blob and its owner row are both perfectly fine. Should redden `Registration.bothRowsAndTheBytes` and `Register.theRegisterRendersNameSizeAndDownload`. | ⬜ NOT RUN |
| FA-6 | `Attachment.forArtifact` is swapped for `forDocPage` in `writeFile` | The owner vocabulary used wrongly at the one place it is chosen. The upload succeeds and the download 404s for ever, because `findOwnedById` asks for `ARTIFACT`. Should redden `Registration.bothRowsAndTheBytes`. | ⬜ NOT RUN |
| FA-7 | `ArtifactFiles.forArtifact` drops the `AttachmentOwner.ARTIFACT`/`ownerId` predicate and calls `findById` | The two-segment gate removed: an `attachment_id` guessed onto another artifact's row would serve bytes through a path that does not own them. Should redden **nothing today** — the column is written only by `writeFile`, so no test can produce the divergence — and it is recorded as the guard that must survive the day anything else writes it. | ⬜ NOT RUN — expected to redden nothing |
| FA-8 | `removeFile` returns early for every kind (the whole body deleted) | The bytes and the blob row outlive the register row: a deletion that did not delete, invisible from every screen, on the one thing a customer may need removed for privacy rather than tidiness. Should redden `Removal.unregisteringAFileDestroysTheBytes`. | ⬜ NOT RUN |
| FA-9 | `removeFile` keys on `getKind() == FILE` instead of on `attachment_id` | Equivalent today and it is the version that rots: the enum and the column are independent, and the day they disagree this leaves a blob behind. Should redden **nothing**. Recorded because it is the change a reviewer would suggest as clearer. | ⬜ NOT RUN — expected to redden nothing |
| FA-10 | `delete` calls `removeFile` **before** `artifacts.delete` | The mirror of `FA-3`: the attachment row is still referenced by `artifact.attachment_id`, so the delete is a constraint violation on every `FILE`. Should redden `Removal.unregisteringAFileDestroysTheBytes` loudly. | ⬜ NOT RUN |
| FA-11 | `delete` uses `PERMISSION` (`artifact:edit`) instead of `DELETE_PERMISSION` | §124's key undone, and it now costs more than it did: a Test Engineer who may register a cycle's documents can destroy the **last copy** of a lead's sign-off file. Should redden `Removal.anEngineerCannotUnregister`. | ⚠️ **RUN 2026-08-23 (wave S) — GREEN. ZERO-UNEXPECTED, and STALE in the same breath.** Two independent reasons: the test this row names, `Removal.anEngineerCannotUnregister`, **no longer exists** — `V52` (§210) granted `artifact:delete` to Test Engineer and it was rewritten as `aViewerCannotUnregister` with a different persona; and §A3 now gives `artifact:edit` and `artifact:delete` to the same four roles, so **no seeded persona can tell the pair apart**. This is `§210 DQ-5`'s family arriving on a row that predates it. The scope was widened to `ArtifactEndpointTest` (`AD-1`'s class, which drives the same method) and that stayed green too. |
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
| §128 CD-3 | `CaseDraftService.findOne` uses `readVisible` / `project:read` instead of `writeGranted` / `case:edit` | The gate most likely to be "simplified", on the argument that a draft discloses nothing the case does not. It discloses somebody's unfinished sentence, shown nowhere else in the product, to the one role invited to read coverage rather than watch it being written. Should redden `Gates.aViewerCannotReadTheDraftEither` and leave `theViewerReachesTheCase` green. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `aViewerCannotReadTheDraftEither`.** MATCH, **and the second half held**: `theViewerReachesTheCase` stayed green, so the class really does distinguish reading the case from reading the draft. |
| §128 CD-4 | `save`/`discard` swap `writeGranted` for `readVisible` | A Viewer can overwrite and destroy other people's unsaved work. Should redden `Gates.aViewerCannotBuffer` and `aViewerCannotDiscard` — and note both assert the row's *state* as well as the status, so a 403 that wrote anyway is caught too. | ✅ **RUN 2026-08-22 — RED, 3.** MORE than the 2 predicted, and the extra is coherent rather than coupled: with the gate replaced by `readVisible` the 403 never happens at all, so `a 403 body names no permission key` falls beside the two arms asserting the refusal. |
| §128 CD-5 | `buffer` drops the `cases.findLiveInProject` check and trusts the project gate alone | `/projects/{granted}/cases/{ungranted}` buffers against a case the caller was never given, and the `GET` then reads it back. Should redden `Gates.theSecondPathSegmentIsGatedToo`. | ⬜ NOT RUN |
| §128 CD-6 | The `lockVersion == null` check is replaced with a default of `0` | The field exists precisely because guessing it wrong loses a committed edit: a draft that claims version 0 is stale against every case, or — worse, if the default were the case's current version — is silently *never* stale. Should redden `Writing.theLockVersionIsRequired`. | ⬜ NOT RUN |
| §128 CD-7 | The `lockVersion` check is moved **below** the `findLiveInProject` lookup | A malformed body answers 404, sending the caller to check a case id that is fine. Should redden `Writing.theBodyIsCheckedBeforeThe404` only — the 400 itself still happens for a reachable case, which is why the ordering has its own test. | ⬜ NOT RUN |
| §128 CD-8 | `render` computes `stale` as `false` unconditionally | The restore-over-a-colleague's-edit path goes unreported, and it is the one the existing `If-Match` machinery **structurally cannot catch** — the client's precondition comes from a fresh read and is correct. Should redden `Staleness.aDraftWrittenAgainstAnOlderVersionSaysSo` and leave `aCurrentDraftIsNotStale` green. | ⬜ NOT RUN |
| §128 CD-9 | `render` makes a stale draft return empty `content` instead of the buffered text | The refusal dressed as safety: the product deciding somebody else's edit invalidates your sentence. Should redden the second assertion of `aDraftWrittenAgainstAnOlderVersionSaysSo`, which exists separately from the `stale` flag for exactly this. | ⬜ NOT RUN |
| §128 CD-10 | `read` returns `Outcome.NotFound` instead of `Outcome.Empty` when no draft exists | Collapses the 204 into the 404 and tells an editor their case is gone every time they open one they had not left work in — which is almost every case, almost always. Should redden `Reading.noDraftIsA204AndNotA404` and leave `theCaseGateStillAnswers404` green; the pair is the whole distinction. | ⬜ NOT RUN |
| §128 CD-11 | `discard` answers `NotFound` when `deleteForCase` returns 0 | A second click, a retry after a timeout, and a concurrent save that already cleared the row all become errors. Should redden `Promotion.discardingNothingIsNotA404` on its **second** call — the first still finds a row, which is why the test discards twice. | ✅ **RUN 2026-08-22 — RED, exactly 1: `discarding a draft that is not there succeeds — it is idempotent`.** MATCH, and it failed on the second discard exactly as the row said. |
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
| CL-8 | `cloneCase` writes the rows itself instead of delegating to `CaseCreateService` | The design, inverted — a third definition of a legal case beside `CaseEdit` and the bulk reconstruction. Cannot be expressed as a one-line mutation, so it is recorded as **the shape to defend in review**: the observable symptoms are a clone that skips validation, one that is created `APPROVED`, and one with no version row. `Stripping.theCloneIsADraftEvenWhenTheSourceIsApproved` and `theCloneIsANewAggregate` are what would catch it. | ⛔ **RETIRED 2026-08-22 as `NOT-A-MEASUREMENT`, on the row's own words** — and confirmed by trying: no one-line proxy is faithful, because `TestCase#create` is what decides `DRAFT`, so reaching the `APPROVED` symptom means writing the rows directly, which *is* the mutation rather than a stand-in for it. **What would close it is not a test but a rule**: an ArchUnit declaration that nothing in `repository` other than `CaseCreateService` saves a `TestCase`. Belongs in `DOCUMENTED GAPS` bucket A. |
| CL-9 | `cloneCase` gates on `case:edit` instead of leaving the gate to the create path | A Test Engineer who may edit but not create can manufacture cases; more importantly the refusal message would name the wrong key. Should redden `Gates.aViewerCannotClone` only if Viewer holds neither — it does not, so the honest variant is gating on `project:read`, which reddens it. | ⚠️ **RUN 2026-08-23 (wave S) — RED, 2 — and NOT the predicted one.** `aViewerCannotClone` stayed **green**; `a member with no grant gets 404, never 403` and `another company's case is a 404, not a 403` fell instead. **The row's premise does not survive the run.** `cloneCase` has no gate of its own — the create path's `case:create` is the gate — so a gate *added* in front can only **narrow**, never loosen, and neither variant the row names (`case:edit`, or the "honest" `project:read`) can change a Viewer's status. What the added check did move is the 404-before-403 ordering, because a `holds()` refusal answers 403 before visibility is resolved. So the clone route's **key** is unobservable — no seeded role holds `case:edit` without `case:create` — while its **order** is observed by two tests. What would close the key half: a create seam that does not re-gate, or a test asserting the refusal's permission key in the 403 body, which `§207 DQ-3` already found nothing reads. |
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
| CL-24 | `PlanCloneService` gates on `project:read` instead of `plan:create` | A Viewer manufactures plans. Should redden `Gate.aViewerCannotClone`. The related check is `Gate.everyPlanCreatorAlsoHoldsPlanEdit`, which is **derived from the seeded catalogue** rather than restating `V3` — delete a role's `plan:edit` and it goes red without anybody editing this file. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `aViewerCannotClone`.** MATCH. Worth reading beside `CL-9`: `PlanCloneService` gates **itself**, so replacing its constant loosens; `CaseCloneService` delegates, so adding one only narrows. |
| CL-25 | `RunCloneService.derive` reads `overrideApprovalGate` from the source instead of the request (needs a column or an audit read to express — that is the finding) | **A documented 2am exception becomes a standing exemption**, invisible on every screen and unexplained in every audit row. Today it is *unexpressible*: nothing stores the flag. Should redden `Gating.theSourcesOverrideIsNotInherited`, which exists to keep it unexpressible. | ⬜ NOT RUN |
| CL-26 | `RunCloneService.derive` passes the source's suite/tag arms as well as the resolved case ids | The clone silently widens to cases authored since the source ran — a template's behaviour under a clone's name (§6v). Should redden `Copying.theScopeIsTheResolvedSetAndNotTheArms`. | ⬜ NOT RUN |
| CL-27 | `derive` picks an assignee from the source's first execution when the request omits one | Inventing a fact: a checklist can name two hundred different people and a run has no assignee column. Should redden `Copying.anOmittedAssigneeMeansUnassigned`. | ⬜ NOT RUN |
| CL-28 | `RunCloneService` gates on `run:execute` (§124's key) instead of `run:create` | Looks stricter and is wrong: Automation Service may open a run, and a clone of a run is a run — a CI job could no longer repeat its own nightly. Should redden `Gate.theRobotMayClone`. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `theRobotMayClone`.** MATCH, and by the one persona that can tell this pair apart — Automation Service holds `run:create` and not `run:execute`, and the class has it. |
| CL-29 | The `Replayed` arm of either clone falls through to `recordProvenance` | A second provenance row claiming a clone that did not happen. Reddens nothing in the current suite (both tests assert the row exists, not that there is one of it) — **recorded as a known gap**, and the assertion to add is a count. | ❗ **RUN 2026-08-22 — RED, 1: `a retried intent clones once`. THE ROW IS WRONG and the gap it registers does not exist.** `PlanCloneService`'s `Replayed` arm was made to fall through to `recordProvenance`, and `PlanCloneEndpointTest` caught it — the assertion the row asks for is already there. The gap entry should be withdrawn rather than carried, and this settles `D9`'s `CL-11 ≡ CL-29` pairing in the direction that needs no new work. |
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
| OB-1 | `Outbox.record` / `recordCoalesced` drop the `isActualTransactionActive` guard | **The mutation of this slice.** The row still saves, delivery still works, and the atomicity the whole pattern exists for is gone with no symptom — a producer that rolls back leaves a notice for work that never happened. Should redden `recordOutsideATransactionIsRefused` and nothing else, which is the finding: only the refusal observes it. | ✅ **RUN 2026-08-22 — RED, exactly 1: `recording outside a transaction is refused`.** MATCH, and the row's own point is confirmed: **only the refusal observes the pattern.** Both guards were removed (`record` and `recordCoalesced`) and every other test in the class stayed green. |
| OB-2 | `OutboxEntry.pending` leaves `nextAttemptAt` null (as `V1` allows) | `findDueIds` has no `IS NULL` arm, so a fresh event is **never due** and nothing is ever delivered. Should redden most of `OutboxDispatchTest`. The interesting half is why the column is filled rather than the query widened: `outbox_pending_idx` is a partial index on exactly this predicate. | ⬜ NOT RUN |
| OB-3 | `claim` bumps `nextAttemptAt` but not `attempts` | A row that kills the process is retried for ever — the ceiling is never reached because the counter only moves on a path the crash never reaches. Should redden `theCeilingStopsAtFailed`. | ⬜ NOT RUN |
| OB-4 | `OutboxLedger`'s methods are moved onto `OutboxDispatcher` (self-invoked, one transaction) | Two defects in one, and both are invisible on the happy path: a self-invoked `@Transactional` is not intercepted at all (§116's defect), and a handler's failure marks the surrounding transaction rollback-only, so the attempt count and error message are discarded and the row retries for ever having recorded nothing. Should redden `aFailingHandlerSchedulesARetry`. | ❗ **RUN 2026-08-22 as a stated PROXY — RED, 1, but NOT the predicted one.** The self-invocation half cannot be written in one line, so only the *one-transaction* half was applied: `@Transactional` on `dispatchDue`, which necessarily also removes the guard that refuses exactly that. What fell was `dispatching inside a transaction is refused`; **`aFailingHandlerSchedulesARetry` stayed green with the three transactions collapsed into one.** So this is `OB-1`'s finding a second time — only the structural refusal observes the pattern, and the behavioural test does not. ⚠️ **The first proxy was a no-op of an unusually apt kind**: `@Transactional` on the self-invoked `private deliver()` is never intercepted at all, which is *the very defect this row describes*, arriving as trap one. Caught by re-reading the mutant, and the reason the runner's substantive-diff guard is not sufficient on its own. |
| OB-5 | `retryAt` sets `FAILED` instead of leaving `PENDING` | The status looks more honest and makes the row invisible to `outbox_pending_idx`, which is `WHERE status = 'PENDING'` — so retries fall off the only index the schema has for them. Should redden `aFailingHandlerSchedulesARetry`. | ✅ **RUN 2026-08-22 — RED, 2.** MORE than the 1 predicted, and the extra is the right extra: `theCeilingStopsAtFailed` also falls, because a ladder whose every retry is already `FAILED` can never climb to the ceiling. |
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
| TA-5 | `AuditEvent.record` gains an `actorTokenId` parameter and one of the 42 call sites passes null | The design this slice refused, mutated at exactly the place it fails: the row reads correct and says a person did what a pipeline did. Reddens nothing unless the chosen call site is the ingestion one — **recorded as a known gap**, and the reason the parameter does not exist. | ⛔ **RETIRED 2026-08-22 as `NOT-A-MEASUREMENT`**, on the row's own words: adding a parameter to `AuditEvent.record` is a 42-call-site refactor whose outcome the row has already derived. It is the *reason the parameter does not exist* — an argument, and the deliverable. `DOCUMENTED GAPS` bucket A. |
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
| §133 AG-5 | `V40` grants `Agent` `library:manage` | The permission roles spec §A3 gates by an *impact modal*, handed to a caller that never sees one: editing a shared condition propagates to every referencing case with no confirmation anywhere. Should redden `agentIsTestEngineerMinusThree`. | ⚠️ **RUN 2026-08-23 (wave S) — GREEN. STALE, not a hole.** `V52` (§210) statement 3 derives Agent's grants **from Test Engineer's**, and Test Engineer holds `library:manage`, so Agent already holds it and adding it to `V40` changes no row. The test the row names no longer exists either: `agentIsTestEngineerMinusThree` became `agentIsTestEngineerMinusNothingItCouldUse`. **The exclusion this row defends was deleted by decision** (§210, over a recorded objection), so the mutation is no longer a defect. |
| §133 AG-6 | `V40` grants `Agent` `case:delete` (`OWN`, copying Test Engineer) | `OWN` resolves against `created_by` while the token's owner is `api_token.owner_id`, so the agent deletes cases **the human authored** — the reason the key is withheld rather than qualified. Should redden `agentIsTestEngineerMinusThree`. | ⚠️ **RUN 2026-08-23 (wave S) — RED, 2 — and NOT the predicted one.** The delta test stayed **green**, because `V51` gave Test Engineer `case:delete` and `V52` derives Agent from it, so the *key set* is unchanged. What the added `OWN` qualifier does move is `ownIsCarriedByNoGrant` and `everyQualifierIsAll`, both of which fall — `V52`'s statement 3 inserts with `ON CONFLICT DO NOTHING`, so `V40`'s `OWN` row **survives** the de-qualification. **This is caught, and caught better than the row expected**: the delta check compares key sets and cannot see a qualifier, and two catalogue-derived rules cover the half it cannot. |
| §133 AG-7 | `V40` spells `Agent`'s `artifact:edit` qualifier as `ALL` | An agent rewrites the project-scope strategy document every plan inherits read-only (§6n). Should redden `testEngineerHoldsArtifactEditOnlyAtPlanScope`; a *different* misspelling (a new qualifier value) should instead redden `noPermissionCarriesTwoDifferentRestrictiveQualifiers`. Run both variants. | ⚠️ **RUN 2026-08-23 (wave S) — BOTH VARIANTS RUN, and they are very different.** *Variant 1* (`PLAN_SCOPE`→`ALL`): **GREEN**, and stale — `V52` statement 2 sets that exact row to `ALL` anyway, so the mutation is erased two migrations later. *Variant 2* (a near-miss value, `PLAN`): **RED, 24 — the whole of `SeedDataTest`**, because the migration itself aborts: `FlywayMigrateException` ← `FlywaySqlScriptException` ← `PSQLException` at context startup. The near-miss is refused by the **database**, not by the derived test the row names, so the failure is loud and arrives before any assertion — §169 `XL-6`'s distinction, on a different row. |
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
| AS-1 | `V41`'s `UPDATE` is reverted (`assignable` stays true for `…0005`) | The state before this slice: `POST /role-assignments` accepts a CI capability set for a person, and every grant picker offers it. Should redden `automationServiceIsNotGrantableToAPerson` and `nonAssignableRolesAreExactlyTheThree`. | ⚠️ **RUN 2026-08-23 (wave S) — RED, 3.** MORE than the 2 predicted, and the extra is coherent rather than coupled: `automationServiceIsNotGrantableToAPerson` and `nonAssignableRolesAreExactlyTheThree` fall as the row says, and `the five a person may hold are the five, and Instance Admin is among them` falls beside them — reverting the flag widens the assignable set to six, which that test counts. |
| AS-2 | `RoleGrantService.revoke` goes back to `Role::isAssignable` | **The row this slice exists for.** Every person-held `Automation Service` grant made before `V41` becomes permanently unrevokable while `GET /role-assignments` keeps listing it — a Revoke button that 403s forever, with no message naming a role. Should redden `anAutomationServiceGrantMadeBeforeTheRuleIsStillRevocable`. | ✅ **RUN 2026-08-22 — RED, exactly 1: `but a grant made BEFORE V41 is still revocable — the axes are not the same`.** MATCH. The `DO NOT restore this to Role::isAssignable` comment above the line is asserted, not merely written. ⚠️ **Scope correction**: the manifest carried `SeedDataTest`; the observer is `RoleGrantEndpointTest`. |
| AS-3 | `AdminDirectoryService.listRoleAssignments` filters non-assignable roles' grants | The other half of the same defect, arrived at from the "tidy it up" direction: the stranded grant becomes invisible instead of unrevokable, so nobody can even see what to clean up, and the permissions keep resolving. Should redden `anAutomationServiceGrantIsStillListed`. | ⬜ NOT RUN |
| AS-4 | `AdminDirectoryService.roleCatalogue` becomes `WHERE assignable` | `§133 AG-2` with a second victim: `Automation Service` leaves `GET /roles` as well as `Agent`, so **no CI credential can be issued through the product**. Should redden `theRoleCatalogueIsWholeAndHasNoWildcard` and the SPA's `offers BOTH roles every grant picker hides`. | ⬜ NOT RUN |
| AS-5 | `V41` also `DELETE`s existing person-held `Automation Service` grants | The option refused on 2026-08-08: a migration removing access on a live instance, with no actor and therefore no audit row `RoleGrantService` would have written. Reddens nothing — the fixtures create their grants after migration — which is the finding. **Known gap: the suite cannot observe a migration that deletes production data.** | ✅ **RUN 2026-08-23 (wave S) — GREEN.** ZERO-AS-PREDICTED, and **the known gap is confirmed rather than assumed**: a `DELETE FROM role_assignment` appended to `V41` reddens nothing, because every fixture creates its grants *after* the migration has run. The suite cannot observe a migration that deletes production data, which is the row's own finding and the reason the option was refused on 2026-08-08. |

### §136 — `Idempotency-Key` on suite and doc-page creates (`IK-1` … `IK-7`)

`V42` adds two columns and two partial unique indexes; the services gain the replay lookup the
other four creates have had since `V11`/`V14`/`V17`. Assertions live in
`SuiteWriteEndpointTest.Idempotency` (9 new), `DocPageEndpointTest.Idempotency` (9 new) and
`IdempotencyKeyCapTest` (3 new, no Spring context).

> **NONE RUN.** **`IK-3` first** — it is the one whose failure is silent on both sides of the wire:
> the endpoint keeps answering 201 and the client keeps believing it deduplicated.

| # | Mutation | Expected | Result |
|---|---|---|---|
| IK-1 | `V42`'s unique indexes are made non-unique | The lookup still finds the first row, so every single-threaded test stays green — the constraint exists for the **race** between two retries of one intent, which the suite does not run. Reddens nothing. **Known gap: no test in this repo drives two concurrent creates carrying one key**, on any of the six tables. The same gap has existed since `V11`. | ✅ **RUN 2026-08-23 (wave S) — GREEN.** ZERO-AS-PREDICTED across both `SuiteWriteEndpointTest` and `DocPageEndpointTest`: with both indexes made non-unique the lookup still finds the first row and every single-threaded test passes. **The known gap is real** — nothing in this repository drives two concurrent creates carrying one key, on any of the six tables, and has not since `V11`. |
| IK-2 | The replay lookup moves **after** `SuiteEdit.validate` / `PageContent.validateCreate` | A retry whose payload drifted gets a 400 naming fields on a resource that already exists, sending the client to fix a suite it cannot see. Should redden `aRetryIgnoresADriftedPayload` in both classes. | ⬜ NOT RUN |
| IK-3 | `trimToNull` is dropped from either controller (header passed through raw) | `Idempotency-Key: ` (empty) is stored as `''` and becomes one key every misconfigured client in the project replays against — the project accepts exactly one suite, or one page, from all of them, forever. Should redden `aBlankKeyIsAbsent` in both classes. | ⬜ NOT RUN |
| IK-4 | `findByIdempotencyKey` drops its `projectId` predicate | A key reused across two projects of one company passes the lookup and then violates the index: a validated request that 500s on insert. Should redden `keysAreScopedToTheProject` (suite). **The doc-page class has no second granted project**, so its own scope test only crosses *companies* — a narrower observation, recorded rather than papered over. | ⬜ NOT RUN ✅ **RUN 2026-08-23 (wave 2) — RED, exactly 1: `the same key in a DIFFERENT project creates a suite — the scope is the index's`.** **Not blind.** The row's caveat is about the doc-page class; the suite class has a second granted project and sees this. A `D1` member that was never a gap. |
| IK-5 | The controller answers `201` + `Location` on a replay | A client following the `Location` reads the replay as having created something, which is what the 200 exists to tell it apart from. Should redden `aReplayCarriesNoLocation` and the status assertion in `aRetryReplaysTheOriginal`, both classes. | ⬜ NOT RUN |
| IK-6 | `MAX_IDEMPOTENCY_KEY` is changed to 500 in **one** of the seven services | The drift the copies invite: one key format works against part of the API and 400s against the rest. Should redden `IdempotencyKeyCapTest.everyCapAgrees` and `theContractAgreesWithTheCode`. **This is the row that justifies not extracting a shared constant** — if it does not redden, the argument in `SuiteWriteService`'s field comment is wrong and the symbol should be extracted. | ✅ **RUN 2026-08-22 — RED, 1 of the 2 predicted. FEWER, and the shortfall is correct.** `everyCapAgrees` falls, so **the argument in `SuiteWriteService`'s comment stands and the constant should not be extracted** — which is what this row exists to decide. `theContractAgreesWithTheCode` **cannot** redden on this mutation and is right not to: it compares `openapi.yaml`'s `maxLength` against the test's own `EXPECTED_CAP`, a different axis entirely. The row's prediction of two was a misreading of the second test. ⚠️ The class is `com.tcms.config.IdempotencyKeyCapTest`, not `com.tcms.shared` — the first attempt matched no tests, which the hardened runner reports as `ERROR-NO-TESTS` rather than as a zero. |
| IK-7 | `DocPageWriteService.writeSubjectPage` passes a key instead of null | A plan-document create starts consuming keys from a header it never receives — harmless today (the parameter is null at the only call site) but it would make `uq_doc_page_subject` and this index two answers to one question. Reddens nothing. **Known gap**, recorded because the null is a decision and not an omission. | ⬜ NOT RUN |

### §137 · §139 · §140 — the TypeScript client, the CLI and the MCP server

**MOVED 2026-08-22 to `packages-and-frontend-mutation-checks.md`.** `TS-1`…`TS-9`, `CW-1`…`CW-12`
and `MS-1`…`MS-10` mutate `packages/` and run under `npm run test:run`, not `./gradlew test`.
Ids are unchanged.

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
| §141 RL-1 | `TokenRateLimiter.Bucket` goes back to a fractional `available` accumulator (`available += elapsed × rate`, clamped at `capacity`) | The floating-point defect this slice found by writing the test first: ten increments of `0.1` sum to `0.9999999999999999`, so a caller that waited exactly one second is refused again. Should redden `fractions of a permit accumulate rather than being lost per request`. **The row that pins why the state is a timestamp and not a count** — and note that a *counting* implementation is what almost everybody writes. | ✅ **RUN 2026-08-22 — RED, exactly 1: `fractions of a permit accumulate rather than being lost per request`.** MATCH. `Bucket` really was rewritten to the fractional accumulator — `available += elapsed × rate`, clamped at `capacity`, with the refusal computed from the shortfall — and at 60/min ten increments of `0.1` sum to `0.9999999999999999`, so the tenth 100 ms step is refused. Nothing else moved; `theWaitIsRoundedUp` stayed green. **The counting implementation everybody writes is caught by exactly one test, and it is this one.** |
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
| DP-1 | `packages/mcp/Dockerfile`'s `CMD` drops `--host 0.0.0.0`, falling back to `BIND_DEFAULT` | The slice's finding. The container starts, logs `listening on 127.0.0.1:8081/mcp`, and every request through the published port is refused — because docker-proxy dials the container's *network* address. `docker run -p 127.0.0.1:18081:8081` then `curl` → connection reset, and the container goes `unhealthy` after ~40 s. | **The HEALTHCHECK** — and therefore the deploy job's mcp wait, which fails the release. This is the row that pays for the probe existing. | ⏭ **NOT RUN HERE — infrastructure lane.** `packages/mcp/Dockerfile` needs `docker build` and a compose bring-up, not `./gradlew test`. Deferred to wave 5 (run plan §3), where the other eight infrastructure rows live. |
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
| CR-3 | `CaseEdit.validate` drops the `priority == null` check | The server stops enforcing what the contract declares, so the same test fails from the *code* side. This is the row that proves the check is not merely reading the YAML twice. **Watch for over-reddening**: `Validation` has no priority-absent test of its own, so a single failure here means the new test is the only observer of that rule. | ✅ **RUN 2026-08-22 — RED, exactly 1: `the contract's required set is exactly what the server refuses to work without`.** MATCH, and it answers the row's own warning: **there was no over-reddening, so the new test is the only observer of the priority-required rule.** ⚠️ **Scope correction**: the manifest carried `ContractConventionTest`; the test lives in `CaseUpdateEndpointTest`, and the runner's scope guard is what said so. |

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
| SD-2 | Add a plausible unimplemented operation to `openapi.yaml` (e.g. `delete` on `/projects/{projectId}/suites/{suiteId}/status`) | The check fails naming it. This is the row that proves it guards the contract *generally* rather than having been fitted to one known gap — and it is the shape a contract-first slice produces on purpose, so the failure message must be the one that tells an author to implement or delete rather than to add an exemption. | ✅ **RUN 2026-08-22 — RED, exactly 1: `everyDeclaredOperationHasAHandler`.** MATCH, on a `delete` added to `/projects/{projectId}/suites/{suiteId}/status`, so the check does guard the contract **generally**. **The message is the right one**: *implement it — the contract already says what it must answer; or delete it from openapi.yaml, and re-add it contract-first when a consumer exists*, followed by *"There is deliberately no ignore list. An operation that is 'not built yet' is exactly the finding."* ⚠️ This is the one row in this batch that mutates `docs/specs/openapi.yaml`, which the other lane also edits; the lane was verified clean immediately before and after. |
| SD-3 | `SuiteService.findOne` drops the `projectId` gate and looks the suite up by id alone | `Detail.aSiblingProjectsSuiteIsNotReachableThroughThisProject` fails. **Nothing else does** — the row is in the caller's own company and satisfies the FK, so every cross-company assertion in the class still passes. Same shape as the tree's own persona-3 row, one endpoint over. | ✅ **RUN 2026-08-22 — RED, exactly 1: `a suite in a sibling project is 404, though the caller may see this one`.** MATCH, including the *"nothing else does"* half. **A second `D1` data point beside `IH-1`**: where a fixture holds a sibling project the dropped project predicate is caught, so that family is blind for want of fixtures rather than because the predicate is redundant. |
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
| PR-2 | `ProblemErrorAttributes.detailOf` returns `getError(webRequest).getMessage()` instead of the curated sentence | `theDetailIsCuratedRatherThanForwarded` fails on `com.tcms` or `com.fasterxml` appearing in `detail`. The row that matters most for a machine caller: the forwarded message is *longer and more specific*, which is exactly why it looks like an improvement in review. | ✅ **RUN 2026-08-22 — RED, exactly 1: `the detail never carries the exception's own message`.** MATCH. |
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
| RK-3 | Delete the per-company `set_config` loop from `V44`, leaving the three `UPDATE`s at the top level | Every `Rewrite` and `Collation` test fails, and **the migration itself still reports success** — `FORCE ROW LEVEL SECURITY` covers the owner Flyway connects as, so the naive spelling is a clean run that changes nothing on every table for every company. `withoutContextTheUpdateIsASilentNoOp` is the assertion that names the mechanism. | ⚠️ **RUN 2026-08-23 (wave S) — RED, 3, and the migration reported SUCCESS.** The row's mechanism is confirmed: with the per-company `set_config` loop gone, Flyway ran clean and the three `UPDATE`s touched **nothing**, so `every company's rows are migrated, not just the first`, `THE §151 invariant: a release candidate sorts BEFORE its release in SQL` and `every backfilled row becomes exactly what the application would write today` all fall. **`withoutContextTheUpdateIsASilentNoOp` did NOT fall** — it demonstrates the mechanism directly rather than observing the migration, which is worth knowing: it *names* the trap, three other tests *catch* it. |
| RK-4 | Add a fourth table with a `release_sort_key` column in a later migration and do not name it in `V44` | `everyTableWithTheColumnIsCovered` fails naming it. Nothing else does — the new table simply carries keys in the old format for ever, ordering its releases backwards, which is the shape this computed check exists to refuse. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `everyTableWithTheColumnIsCovered`.** MATCH, and *"nothing else does"* is confirmed — `SchemaConventionTest` was in scope and contributed zero. Run as a **stated proxy**: rather than a new table (a new `V*.sql` cannot be reverted by exact path), `release_sort_key` was added to `test_plan` by appending to the newest migration. The test computes the carrier set from `information_schema` and asserts it is exactly three, so a fourth carrier is what it sees either way. |

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
| UX-2 | Delete `Outbox.record` and leave `UNEXERCISED_BY_DECISION` naming it | The rule fails on the *missing* element rather than an extra one. This is the direction an ignore list cannot express, and it is what stops the argument at `Outbox#record` from outliving the code it argues about — §152's `countLiveCasesBySuite` in reverse. | ⚠️ **RUN 2026-08-23 (wave S) — RED, exactly 1, AS A STATED PROXY.** The row's literal mutation is **stale**: `UNEXERCISED_BY_DECISION` is `Map.of()` today and `Outbox.record` acquired two product callers in §218, so there is no entry to leave behind. The row's *question* — can the rule fail on a missing element? — was run instead, by adding an entry naming a method the product now calls. `everyPublicBeanMethodIsExercisedInProduction` reddens. **The both-ways property holds**; what has gone is the specific member. |
| UX-3 | Make one `@Scheduled` method public — e.g. `OutboxScheduler.dispatchAllCompanies` | The rule fails naming it, because the container is its only real caller and bytecode cannot see that. **This is a false positive by construction and the row exists to bound the rule's claim**: the `public` narrowing works here only because all four `@Scheduled` methods in this codebase are package-private, which is a property of this codebase and not of Java. If that ever stops being true, the rule needs an annotation-based exclusion rather than another argued entry. | ❗ **RUN 2026-08-22 — ZERO, and the rule is bounded somewhere else entirely.** Making `OutboxScheduler.dispatchAllCompanies` `public` reddens **nothing** — not because the `public` narrowing holds, but because the rule **skips a method with no caller at all**: `if (callers.isEmpty() || callers.stream().anyMatch(PRODUCTION_NAMES::contains)) continue;`. Its own message says *"every caller of this one is a test"*, and the strictly worse case — **nothing** calls it, not even a test — falls through that same arm silently. `dispatchAllCompanies` has zero references anywhere under `backend/src`. So the false positive this row exists to bound **cannot happen**, and the reason is not the one written down. **What would close it:** treat an empty caller set as unexercised *unless* the method carries `@Scheduled`/`@EventListener` — naming the container explicitly, which is what the row's own last sentence asks for. |
| UX-4 | Drop a verb from `OperatorCommands.ALL` — e.g. `GRANT_INSTANCE_ADMIN` | `OperatorCommandsTest.everyVerb` fails naming that verb. Nothing else does: the command bean still runs, does its work correctly, and then **never exits**, because the jar was never switched to non-web. The failure is a hang an operator meets at 2am, and before this slice no test observed the guard at all. | ⚠️ **RUN 2026-08-23 (wave S) — GREEN. ZERO-UNEXPECTED, and the row's prediction is wrong in an instructive way.** `everyVerb` is **derived from `OperatorCommands.ALL` itself** — it asserts that every member of the set switches the jar to non-web — so removing a verb *shrinks the set the test iterates* and the test cannot see the removal. A set-derived assertion catches an extra member, never a missing one; the comment beside it (*"a fourth verb is covered by the day it is added"*) is true and is only half the question. **The hang an operator meets at 2am is unobserved.** What would close it: assert `ALL` contains each named constant, or compare it against the verbs the runner actually dispatches on. |

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
| §154 RL-4 | Stop excluding `ArchitectureRulesTest.java` from its own corpus (`OWN_FILE`) | **Five members vanish and the rule goes green while the defect stands.** Every key is the string `"Entity.field"`, so each argument for keeping a column is itself a literal naming that column. This is the rule's own most dangerous failure mode and the reason the measured population went 7 → 13. | ⚠️ **NOT RUN 2026-08-23 (wave S) — STALE, adjudicated by reading.** `OWN_FILE` **no longer exists**: §188 narrowed the reader rule's corpus to `src/main` only, which the file's own header records as having made the shape *unreachable* rather than merely safe. The live form of this mutation is `§188 NR-1`, run in the same batch — and it reddens, with the whole map going absent. |
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
| LV-5 | `ExecutionService` writes the `run.started` audit row unconditionally instead of on a return of 1 | Every result claims to have started the run. Should redden `a second result does not re-start it, and writes no second run.started` and `recording in an already-running run leaves its start alone` — the two that count audit rows. **The row that pins why the statement returns an int at all.** | ✅ **RUN 2026-08-22 — RED, exactly the 2 predicted**: `a second result does not re-start it, and writes no second run.started` and `recording in an already-running run leaves its start alone`. MATCH. The `int` return is asserted, not merely documented. |
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
| `WL-1` | Delete one entry from `WRITERLESS_BY_DECISION` — e.g. `Project.archivedAt` — leaving the field as it is | The rule fails naming it as an **unexpected** member. Nothing about the column changed; only the argument for keeping it went away. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `everyMappedColumnHasAWriter`.** MATCH. Run with a **substituted example**: the row names `Project.archivedAt`, which left the map in §159 by acquiring a writer (that is `AR-M5`'s subject), so `CompanyMembership.avatarUrl` was deleted instead. The rule fails on the unexpected member either way. |
| `WL-2` | Give a pinned column a writer without removing its entry — call `Project.archive(now)` from `ProjectService` | The rule fails on the **missing** element `Project.archivedAt`, which is the direction an ignore list cannot express. Note what else this mutation shows: nothing else in the suite reddens, because no test asserts a project can be archived. | ⬜ NOT RUN |
| `WL-3` | Restore the first draft's corpus — `writesIn(literalsOf(source))` in place of `writesInLiterals(source)` | **Two members disappear and the rule goes green.** `CaseHealth.unlinked` and `IngestionJob.error` read as written because `DocPageEndpointTest`'s `"UPDATE key_sequence SET next_value = 1"` has no terminator, so `[^;]*` swallows every later literal in that file and an `@DisplayName` containing the word "unlinked" stands in for a writer. **The most important row in this block**: it is the rule's own failure mode, and it fails silently in the direction that hides findings. | ⬜ NOT RUN |
| `WL-4` | Drop the SQL arm — keep `isWrittenInJava` and delete both `namedIn` calls | Every column written only by native SQL is reported: the five `case_health` signals that *do* have a writer (`never_executed`, `long_unexecuted`, `stale_automation`, `undeclared_automation`, `failing_repeatedly`), `computed_at`, and the `test_run` columns `startIfNotStarted` sets in JPQL. Bounds the claim that bytecode alone is the wrong observer here — the mirror of §154's measured "212 of 635". | ⬜ NOT RUN |
| `WL-5` | Drop the field-name arm alone (`namedIn(writeText, null, field.getName())`), keeping the column-name one | The JPQL writers go: `startIfNotStarted` names `r.startedAt`, `V1` names `started_at`, and only the field-name arm connects the two. This is the row that says why there are two spellings rather than one. | ✅ **RUN 2026-08-22 — RED, exactly 1: `every mapped column has a writer — the writerless tally, computed`.** MATCH. **Both spellings are load-bearing**: dropping the field-name arm alone breaks the rule, because the JPQL writers name `r.startedAt` and `V1` names `started_at`. |
| `WL-6` | Drop `SQL_ROOTS` — the `.sql` corpus — keeping the Java one | A column written only by a migration is reported. `V44`'s backfill is the worked example, and the general point is that a migration that *backfills* is a writer while one that only creates the column is not, because `CREATE TABLE` is neither verb. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `everyMappedColumnHasAWriter`.** MATCH. The `.sql` half of the corpus is load-bearing: with the migration walk removed, a column written only by a migration is reported. |
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
| `SA-5` | Replace the version comparison with the timestamp one — `c.updated_at > approved.at` | Predicted **zero** reds, and that is the deliverable: this fixture cannot tell the two apart, because the case that separates them is a save whose content hash is unchanged (no new version, `updated_at` moved). Writing the row is what records that the argument at the method is unasserted. A red here would mean the fixture is stricter than it reads. | ❗ **RUN 2026-08-22 — RED, 1. The fixture IS stricter than it reads, and the argument at the method is asserted after all.** `APPROVED at a version the case has since moved past is STALE_APPROVAL` falls when the version comparison is replaced by `c.updated_at > approved.at` over `max(r.approved_at)`. The row's own reading of its fixture was wrong, in the reassuring direction. Informs `D9`'s `SA-5 ≡ §158 CD-6 ≡ §169 XL-6` pairing: the version-vs-timestamp distinction **is** observable here, so the family is not uniformly blind. |
| `SA-6` | Add `stale_approval` to `ExecutionFacts.recomputeAutomationDrift`'s `ON CONFLICT` update list | The last statement in the tick blanks the third one's work. Should redden `APPROVED at a version the case has since moved past is STALE_APPROVAL` — and it is the concrete instance of the hazard `signals this job does not compute survive a recompute` has guarded against hypothetically since §53. | ⬜ NOT RUN ❗ **RUN 2026-08-23 (wave 2) — RED, 1, and NOT the predicted test.** `APPROVED at a version the case has since moved past is STALE_APPROVAL` stayed **green**; only `otherSignalsAreNotClobbered` fell. **The row has the tick order backwards**: drift is statement 2 and approval is statement 3, so approval rewrites the correct value inside the same transaction. §247 `SK-M17`'s shape — the boundary test sees it and the consequence test cannot. |

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
| §158 `CD-5` | `coalesce(drift.drifted, false)` → `drift.drifted` | `bool_or` over zero rows is NULL and the column is `NOT NULL`, so this is not a wrong answer but an **aborted transaction**, taking the other three statements' work with it. Should redden `a snapshot with no conditions raises nothing rather than failing`, `a case with no version at all has nothing to have drifted from`, and — because the whole tick rolls back — most of the class. ✅ **RUN 2026-08-22 — RED, 90 — and the blast radius IS the deliverable.** `bool_or` over zero rows is NULL into a `NOT NULL` column, so the tick aborts and takes every other statement's work with it: automation drift, stale approval, completeness, the documentation mirror, the linkage signals, the owner check, the two execution signals and `computed_at` all fall, alongside both arms the row names by hand. Ninety of the class. **The row exists to measure how far the blast radius reaches**, since the failure mode is a job that stops rather than one that lies. | see above |
| §158 `CD-6` | Replace the content comparison with the timestamp one — `lib.updated_at > newest.created_at` | Predicted **some** reds (`an entry still saying what the snapshot recorded raises nothing`, since every fixture condition is written after its case's version row) but **not** the one that matters: nothing here expresses "edited and changed straight back", which is the state where the timestamp spelling can never heal. **The deliverable is the gap**, and closing it is a fixture that writes a version, bumps `condition.updated_at`, and restores the body. | ⬜ NOT RUN |
| §158 `CD-7` | Add `condition_drift` to `ApprovalFacts.recomputeStaleApproval`'s `ON CONFLICT` update list | The third statement in the tick blanks the fourth's — or rather, being third, is blanked *by* it, so the mutation that shows the hazard is the one on the **later** statement. Should redden `an entry edited since the snapshot drifts the case that references it`. `SA-6`'s row for the other pair; the column-ownership rule now binds four statements and the number of ways to break it grows with it. | ❗ **RUN 2026-08-23 (wave 2) — RED, 1, and not the predicted test.** `an entry edited since the snapshot drifts the case that references it` stayed **green**; only `otherSignalsAreNotClobbered` fell. Identical to `SA-6` and for the same newly-measured reason: approval is statement 3 and drift is statement 4, so the later statement repairs it before the transaction ends. |

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
| `AR-M1` | In `ProjectArchiveService.apply`, delete the `project.isArchived() == target` early return | Every request writes: the timestamp is re-stamped on an already-archived project and a second audit row is written. Should redden `setting the state a project already has writes nothing at all` **and nothing else** — which is the row that says the idempotence is in the service and not an accident of the gate. | ✅ **RUN 2026-08-22 — RED, exactly 1: `setting the state a project already has writes nothing at all`, and nothing else.** MATCH — the idempotence is in the service. |
| `AR-M2` | `PROJECT_SETTINGS` → `"project:read"` | Every visible caller may retire a project. Should redden `a Test Engineer is refused with 403, and nothing moves` **and nothing else.** The narrow blast radius is the point: it demonstrates that the 404-over-403 rows are answering the *visibility* gate rather than passing because the permission happened to be missing too. | ⬜ NOT RUN |
| `AR-M3` | In the controller's switch, answer `Unreachable` with 403 instead of 404 | Should redden `a project the caller cannot see is 404, never 403` — **both arms**, the no-grant one and the other company's. Worth measuring rather than assuming, because the two reach the same arm by different routes and a mutation that reddens only one would mean the second assertion is decorative. | ⬜ NOT RUN |
| `AR-M4` | In `ProjectArchiveService.set`, treat a null `archived` as `false` instead of a field error | A malformed body silently un-archives. Should redden `an absent archived field is a 400 on the field, not a silent un-archive`. **This is the mutation a reviewer would call a simplification** — `Boolean.TRUE.equals(request.archived())` reads as tidier than the null check and is exactly the defect `ProjectSettingsRequest` documents for `requiredPeerReviews`. | ⬜ NOT RUN |
| `AR-M5` | Re-add `Project.archivedAt` to `ArchitectureRulesTest.WRITERLESS_BY_DECISION` | The population is eleven and the map says twelve. Should redden `every mapped column has a writer — the writerless tally, computed`. It measures the rule from the direction that matters now: the column has a writer, so the by-decision entry is the thing that would be wrong, and the rule must say so rather than tolerating a stale entry. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `every mapped column has a writer — the writerless tally, computed`.** MATCH. The map fails on a **stale entry** and not only on a missing one, which is the direction that matters now. |
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
| `CE-M3` | In `constantsOf`, revert `JavaModifier.ENUM` to `JavaModifier.STATIC` | Restores the bug the rule shipped with for one run: `execution.AggregationPolicy.DEFAULT` — a `public static final` alias for `LATEST`, never serialized — is counted as a constant and reported as SERVER-ONLY. **Should redden the same rule with a false finding**, which is the failure mode worth measuring: a green suite proves the rule finds things, and nothing here proves it does not manufacture them. If it stays green, the alias has gone and the mutation needs a new target. | ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `a contract enum and its server type hold the same members`.** MATCH, and the row's fallback is not needed: the alias is still there, so the rule reports `AggregationPolicy.DEFAULT` as SERVER-ONLY. **The rule can be made to manufacture a false finding, and the check on that is now measured** — which was the whole point of keeping this row over the two that were run inline. |

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

## §163 — the request echo nobody wrote

*Three mutations, one per rule. **All three were run** before the slice's prose was written, for
`§162 MT-1`…`MT-4`'s reason and `CE-M1`/`CE-M2`'s before them: each mutates a **check** rather than
the product — no screen, no request, no user-visible behaviour — and a rule shipped without seeing
it fail is the rule §162 exists because of. §65's deferral covers the browser pass and the product's
own behaviour, and both are deferred (`§163 EC-1` in the browser queue is **not run**).*

*The slice's opening measurement is not a mutation and is recorded here anyway, because it is the
only reason the slice went the way it did: a throwaway standalone `MockMvc` returning each envelope
the way its controllers return it printed `{"type","title","status","detail","instance":"/detail"}`
for `ProblemDetail` and `{"type","title","status","detail"}` for `api.Problem`, under both
`Accept: */*` and `Accept: application/json`. The hypothesis under test was that `instance` is
written on **zero** refusals — `setInstance` occurs zero times in `src/main` — and it is written on
**all twenty-one** `ProblemDetail` controllers, by Spring's own return-value handler.*

| # | Mutation | Expected | Result |
|---|---|---|---|
| `§163 EC-1` | In `ProblemEnvelope.beforeBodyWrite`, delete the `detail.setInstance(null)` arm | Should redden **two** things in different harnesses: `no refusal body carries a property the contract does not declare` (standalone `MockMvc`, naming `instance` as the member not declared) and `a controller's own ProblemDetail carries no instance either` (real HTTP, a controller-written 403). The pair is the point — the standalone fixture proves the mechanism and the real-HTTP one proves it on a body that came back over a socket, and a single red would mean one of the two is unobserved. It also proves the **advice runs after the return-value handler**, which is the only reason clearing the property works at all. | ✅ **RUN 2026-08-11 — both red, in both harnesses, as predicted** (`22 tests completed, 2 failed`) |
| `§163 EC-2` | Re-declare `instance: { type: string, nullable: true }` under `Problem`'s properties in `openapi.yaml` | Should redden `the Problem schema declares nothing that echoes the request back` — **and leave the server-side subset rule green**, which is the half worth checking. That is exactly the loophole this rule exists to close: with only the subset check, a future slice could satisfy it by re-declaring the property instead of by suppressing it, which is the edit §163 reversed. | ✅ **RUN 2026-08-11 — red, and ONLY that one** (`12 tests completed, 1 failed`): the subset rule stayed green over a body carrying `instance`, which is the loophole in as many words |
| `§163 EC-3` | In `ErrorBodyPolicyTest.Refusals.detailWithErrors`, add `problem.setProperty("traceId", "abc")` | Should redden `no refusal body carries a property the contract does not declare`, naming `traceId`. Proves the server rule is a **subset** check against the contract and not an `instance` blacklist — the general form of §148's and §162's family (a body and a document disagreeing about what is in the body), in the direction neither covered. | ✅ **RUN 2026-08-11 — red on `traceId`** (`12 tests completed, 1 failed`) |

> **Not mutation-tested and worth knowing** — carried forward from §162 and now the slice's own
> named candidate: nothing pins that the *record* and `ProblemDetail` carry the same field names.
> `ContractConventionTest.RESPONSE_RECORDS` does that job for four response schemas and `Problem` is
> not one of them, because it is written by two Java types and this project owns only one. §163
> removed the single member that differed, so the two agree **today** with no check saying so.

## §165 — the members nobody compared

*Four mutations, all of them **checks over checks** — `§162 MT-*`, `§163 EC-*` and `CE-M*`'s reason:
the thing §165 ships is a rule, and the only way to know a rule reports is to break what it watches.
Running them is not the §65 exception, because none of them exercises the product. §65's deferral
covers the browser pass and the product's own behaviour, and both are deferred — `§165 RC-1` and
`§165 RC-2` in the browser queue are **not run**, and they are the two that matter most, because
each is a screen that looks correct while showing a key the server has never sent.*

| # | Mutation | Expected | Result |
|---|---|---|---|
| `§165 RC-1` | In `openapi.yaml`, remove `keySeq` from `TestPlanSummary`'s `properties` and `required` — i.e. revert this slice's contract edit | Should redden `a schema and the record serving it name the same members, both ways` with **`TestPlan.keySeq` as SERVER-ONLY** and nothing else. This is the slice's own finding replayed: the state before this commit, which every gate in the repo has been green over since §74. | ✅ **RUN 2026-08-11 — red, `unexpected: ["TestPlan.keySeq"]`, only that test** |
| `§165 RC-2` | Rename `DocBlock.coverage` to `coverageXX` in `openapi.yaml`, leaving its `MEMBER_DIVERGENCE_BY_DECISION` entry in place | Should redden the same rule from **the healed arm** — `elements not found: ["DocBlock.coverage"]` — proving the by-decision map fails in *both* directions and an argument cannot outlive its gap. This is the property §162 found that one-directional exemption lists do not have. | ✅ **RUN 2026-08-11 — red, `elements not found: ["DocBlock.coverage"]` and `not expected: ["DocBlock.coverageXX"]`** |
| `§165 RC-3` | Delete the `Facet` entry from `RESPONSE_RECORDS` | Should redden `every schema a record is named for is paired, and every pairing is a real schema`, naming `Facet`. Proves the population rule reports a schema whose record nobody has claimed — the direction that did not exist at all before this slice, when the map was four entries and everything else was silently unchecked. | ✅ **RUN 2026-08-11 — red, `["Facet"]`** |
| `§165 RC-4` | Two variants on `HOMONYMS`: (a) delete the `auth.CurrentUserService.TeamRef` entry; (b) add a bogus `com.tcms.project.NoSuchTeamRef` entry, keeping both real ones | (a) should report the record as **unexplained** — the mechanism that would have caught §147's "TeamRef is two copies" when it is three; (b) should report it as **stale**, an argument naming a collision that does not exist. Both arms of the same map, and (a) short-circuits (b), so they are run separately. | ✅ **RUN 2026-08-11 — (a) red, `["com.tcms.auth.CurrentUserService.TeamRef (named for contract schema TeamRef)"]`; (b) red, `["com.tcms.project.NoSuchTeamRef"]`** |

> **Not mutation-tested and worth knowing.** The rule compares a schema's declared properties to a
> record's **components**, statically. It cannot see what Jackson actually writes — a
> `@JsonIgnore`, a `@JsonProperty` rename, or a custom serializer would satisfy it while changing
> the bytes. §164's `bothEnvelopesSerializeTheSameKeys` is the shape that closes this for one
> schema, over a real `MockMvc` response, and generalising *that* to 79 pairings needs a fixture per
> pairing rather than a class literal. The narrowing is the same one §164's queue recorded from the
> other side, and it is the reason `Problem` stays in `PAIRED_ELSEWHERE` rather than being folded in.

## §166 — the result every case said it never had

*Five mutations, **NONE RUN**. §165's four were checks over checks and exercised no product
behaviour, which is why running them was not the §65 exception; this slice ships a derivation that
decides what a user reads on every case row, so §65 applies in full — write the row, run nothing.
Two of the five (`LR-4`, `LR-5`) are the ones worth running first if this queue is ever drained
partially: each reddens on a state the product reaches routinely and neither has a sibling
assertion elsewhere.*

| # | Mutation | Expected | Result |
|---|---|---|---|
| `§166 LR-1` | In `ExecutionFacts.recomputeExecutionSignals`, delete `AND e.executed_at IS NOT NULL` from the lateral | Should redden **nothing**, and that is the finding rather than a pass. The predicate exists because `ORDER BY … DESC` sorts NULLs **first** in Postgres where `max()` skipped them, so a row with a status and no `executed_at` would win — and `TestExecution.record()` sets both together, so no fixture may legitimately build that row. A mutation that kills no test on a line that is load-bearing is exactly what §53's note about `status_id IS NOT NULL` recorded from the other side. **If it DOES redden, the fixture has drifted into an unreachable state and that is the more interesting finding.** | ⬜ not run |
| `§166 LR-2` | Change the lateral's `ORDER BY e.executed_at DESC, e.id DESC` to `ORDER BY e.executed_at ASC, e.id DESC` | Should redden `the newest execution wins, and the stale one is the PASSING one` — `REVISITED`'s two rows carry opposite kinds precisely so this reports a **green case that is currently red**, not merely an old date. Should also redden `long_unexecuted`'s own assertion, and the pair reddening together is the property: one statement, one row, five columns. | ⬜ not run |
| `§166 LR-3` | In the `ON CONFLICT DO UPDATE`, drop the three `last_*` assignments (keep `never_executed`, `long_unexecuted`, `computed_at`) | Should redden `removing the executions clears the last result on the next tick` and **only** that one — every other assertion runs against a first recompute, which takes the INSERT arm. That asymmetry is the reason the removal test exists: the update arm is unreachable from a fresh fixture, and it is the arm a live deployment is in on every tick after the first. | ⬜ not run ✅ **RUN 2026-08-23 (wave 2) — RED, exactly 1.** MATCH including the *and only that one* half. |
| `§166 LR-4` | In the same `ON CONFLICT`, guard the three with `COALESCE(EXCLUDED.last_status_kind, case_health.last_status_kind)` etc. — i.e. "only update when non-null", the tidy-looking edit | Should redden `removing the executions clears the last result on the next tick` while leaving `never_executed` correct — so the two **disagree on one grid row**, which is the exact failure the shared-row design is arranged against. Distinct from `LR-3` because this one is what a reader trying to be careful would write. | ⬜ not run ✅ **RUN 2026-08-23 (wave 2) — RED, exactly 1.** ⚠️ The row's *extra* claim is not reached: `theBooleanAndTheValueCannotDisagree` stayed **green**, because no fixture case goes from executed back to unexecuted, so the two never disagree on a grid row. |
| `§166 LR-5` | In `CaseHealth.lastResult()`, key on `lastStatusKind == null` instead of `lastExecutedAt == null` | Should redden **nothing today** — the two columns are written together, so they are null together. It is recorded because the accessor's javadoc claims the choice is not arbitrary: keying on the moment renders a kind-less row as an incomplete-but-real result, and keying on the kind renders it as "never run", which is the falsehood this whole slice removed. A mutation with no killer is a claim with no check, and saying so is the point of the row. | ⬜ not run |

> **Not mutation-tested and NO TEST IS POSSIBLE — the `id DESC` tiebreak.** Two executions of one
> case recorded in the same microsecond (two runs, `uq_execution_run_case` permitting) have no
> `executed_at` ordering between them. Without the tiebreak Postgres may return either row, so a
> test naming one would be **flaky rather than red**, and a test asserting that two recomputes agree
> passes on a small fixture whichever way the plan goes. The tiebreak buys determinism *across
> recomputes* — without it a case's shown release version can change with nothing having happened,
> which is a bug report nobody can reproduce. Recorded here because "no test can catch this" is a
> legitimate entry and an unwritten one is indistinguishable from an oversight.

## §167 — the specification points a case claims to verify, mirrored into a table

*Five mutations, **NONE RUN** — §65 in full, same terms as §166: this slice ships a derivation that
decides what a reader sees on the case detail's traceability panel, not a check over a check.*

| # | Mutation | Expected | Result |
|---|---|---|---|
| `§167 DL-1` | In `DocumentationFacts.refreshCaseDocLinks`, delete the leading `DELETE FROM case_doc_link` | Should redden `removing the executions clears the last result on the next tick`'s sibling in `TheDocumentationLinks` — a link withdrawn (block deleted, page deleted, or the link itself removed) stays in the mirror forever. Same defect `BlockLinkService` refuses in `doc_link` itself, arriving one table later, and the reason the refresh is delete-then-insert rather than `ON CONFLICT`. | ⬜ not run |
| `§167 DL-2` | Change `stale`'s predicate from `coalesce(page_content.at, p.created_at) > coalesce(case_content.at, c.created_at)` to compare `p.updated_at > c.updated_at` instead | Should redden a `TheDocumentationLinks` assertion on a case that was given an owner or moved status after its content last changed for real — the timestamp spelling forgives every accumulated spec change on an act that read nothing, the exact refusal `ApprovalFacts` already made for `STALE_APPROVAL`. | ⬜ not run |
| `§167 DL-3` | Delete `AND p.project_id = c.project_id` from the mirror statement | Should redden nothing in this module's own fixtures (single-project) but is the row worth running first if this queue is ever drained partially: it is the one predicate that can only ever drop a row a defect produced, and its absence would surface as a sibling project's page title rendered on a case whose reader holds no grant on that project. | ⬜ not run ❗ **RUN 2026-08-23 (wave 2) — RED, 2**, against a fixture that already existed: `a page of a sibling project is not shown` and `a page of a sibling project is no link either`. `CaseHealthRecomputeTest` holds a sibling-project page and this row did not know. **A registered gap that does not exist — WITHDRAWN**, and NOT by wave 2's fixture. |
| `§167 DL-4` | In `HealthRecomputeOnLinksChanged`, delete the class (or gate it behind a property that defaults off) | Should redden nothing — no test asserts the outbox latency directly, the same gap `§166 LR-1`'s browser row exists to cover rather than a unit test. Recorded because "no test can catch this" here is a live gap and not a design choice, unlike `id DESC` above. | ⬜ not run |
| `§167 DL-5` | In `CaseRenderer.docLinksFor`, change `computeIfAbsent(...).add(...)` to overwrite (`put`) instead of accumulate | Should redden a `TheDocumentationLinks` assertion on any fixture case with more than one link — the map would carry only the last link found per case instead of the full list, and `findForCases`'s `ORDER BY l.pageKey, l.blockId` is what makes "last found" deterministic rather than merely usually wrong. | ⬜ not run |

## §168 — the coverage marker, computed from the mirror §166 built

*Five mutations, **NONE RUN** — §65 in full, same terms as §166 and §167. This slice ships a
derivation that decides what a reader sees against every paragraph of every specification, and the
counts are the kind of number nobody re-derives by hand once it is on screen.*

| # | Mutation | Expected | Result |
|---|---|---|---|
| `§168 CV-1` | In `BlockLinks#coverage`, drop the `LinkRelationship.VERIFIES` half of the guard, so every case link counts | Should redden `a block linked only by REFERENCES has links and NO marker`, and **only** that one. The narrowness is the finding to check: every other coverage test links with `VERIFIES`, so a single test carries the whole rule that keeps this marker agreeing with `CoverageMatrixReport`'s spine about which links are coverage claims. | ⬜ not run |
| `§168 CV-2` | Change the `LinkedHashSet` collecting a block's verifiers to an `ArrayList` — i.e. stop deduping | Should redden `one case linked to one block twice counts once`. Worth running because the dedupe looks like defensive tidiness and is not: `DocLinkRepository#findForSource` states its idempotency check cannot stop a concurrent double-submit, so the duplicate row is a state the product reaches without any defect at all. | ⬜ not run |
| `§168 CV-3` | In `CatalogueFacts#lastResultKinds`, delete `AND c.project_id = :projectId` | Should redden **nothing**, and that is the row's whole point. The ids reaching this method were already resolved through `#resolve`, which applies the predicate — so this one is a restatement that can only ever matter to a *later* caller that skipped that path. A mutation with no killer on a predicate that is deliberately redundant is the honest shape of "belt and braces"; if it ever DOES redden, some caller has started passing unresolved ids and that is the more interesting finding. | ⬜ not run ✅ **RUN 2026-08-23 (wave 2) — ZERO, as predicted and for the stated reason.** The ids reached this method through `#resolve`, which applies the predicate. Argued zero now measured; the row stays a gap and stays correct. |
| `§168 CV-4` | In the same method, change the `JOIN case_health` to a `LEFT JOIN` and drop `AND h.last_status_kind IS NOT NULL` | Should redden **nothing** — a null kind and an absent row are collapsed to the same answer by the caller on purpose, so both spellings produce identical output today. Recorded rather than skipped because the collapse is an *argument* in `lastResultKinds`' javadoc, and an argument with no check is what §166's `LR-5` row exists to name. The day a fourth count is added to the contract, this row is the one that stops being a no-op. | ⬜ not run |
| `§168 CV-5` | In `BlockLinks#coverage`, compute `notRun` as a literal `0` instead of `keys.size() - passed - failed` | Should redden `the three counts always sum to linkedCases`, `a BLOCKED case is notRun`, `a case that has never been executed is notRun` — three tests, which is the sum property being genuinely load-bearing rather than asserted once. The failure this prevents is a marker that reads "2 cases · 1 passed" and leaves a reader to conclude the second one failed. | ⬜ not run |

> **Not mutation-tested and NO TEST IS POSSIBLE HERE — the `passed` bucket versus `counts_as_pass`.**
> This marker buckets by `StatusKind`, so a project that adds a "Passed with comment" status (kind
> `PASSED`, `counts_as_pass = false` — the split `StatusKind`'s own javadoc designs) sees it counted
> as a pass here, while `CoverageMatrixReport`'s Verified column, which reads `counts_as_pass`, does
> not. **The disagreement is real, and it is not reachable from this module**: `case_health` carries
> the *kind* and not the status id, by `V45`'s decision, and the status row lives behind
> `execution`. No test can be written here that would not first have to build the join this slice
> exists because it cannot make. Recorded so the next reader meets the seam rather than discovering
> it from a client's screenshot; closing it means a second mirrored column, not a query.

## §169 — the two external target kinds, and the mirror's second arm

*Six mutations, **NONE RUN** — §65 in full. Two of them (`XL-1`, `XL-2`) guard values this product
cannot check against reality: nothing here talks to a git host, so a bad pin is caught at the write
or never.*

| # | Mutation | Expected | Result |
|---|---|---|---|
| `§169 XL-1` | In `ExternalLinkService.SHA`, widen the pattern to `.+` | Should redden `a branch name where a commit belongs is refused`, and **only** that one. Worth running because the field is a `TEXT` column, a `String` field and a free-text input end to end: this regex is the entire distance between #26's decision and a link that lies. | ⬜ not run |
| `§169 XL-2` | In `urlErrors`, delete the scheme check (keep the parse and the host check) | Should redden `a javascript: URL is refused` and `a data: URL is refused for the same reason`. This is a stored-XSS gate, not a tidiness rule — the value is rendered as an anchor's `href` on the case detail, and `rel="noopener"` in the SPA is the other half of the same problem. | ⬜ not run |
| `§169 XL-3` | In `sameTarget`, drop the line-range comparison (keep repo, path, sha) | Should redden `a different range in the same file at the same commit is a second link`. The mutation is the *cheap* way to write that method, which is why it is queued: it fails by answering the author's request with somebody else's link and a 200 on top. | ⬜ not run |
| `§169 XL-4` | Replace `sameTarget`'s Java comparison with a JPQL predicate using `=` on all six columns | Should redden `a whole-file link matches itself — the null comparison SQL gets wrong`, and nothing else. **The one mutation here that reproduces a defect nobody would find by reading**: `external_line_start = :start` is UNKNOWN, not true, when both sides are null, so a double-clicked whole-file link writes two rows while every other test stays green. | ⬜ not run |
| `§169 XL-5` | In `DocumentationFacts.refreshCaseDocLinks`, delete `AND c.deleted_at IS NULL` from the **second** arm only | Should redden `a soft-deleted case keeps no external links either`. It is the one predicate the external arm keeps from the block arm, and the only one it *can* keep — the other three are about a page this arm does not join. | ⬜ not run |
| `§169 XL-6` | In the same arm, replace the literal `false` for `stale` with the block arm's version comparison | Should redden **nothing today**, and that is the row's point: an external link has no page, so the comparison's left side is NULL, and NULL into a `NOT NULL boolean` aborts the whole refresh rather than writing a wrong value. Expected outcome is therefore a *broken recompute*, not a failed assertion — run it to confirm the failure mode is loud, because a silently-false column and a loudly-aborted refresh are very different things to inherit. | ⬜ not run |

> **Not mutation-tested and no test is possible here — the pin going out of date.** `stale` is false
> for an external link by decision (`V47` §3): the sha pins the lines, so the target cannot change,
> and what *can* fall behind is the pin itself. Answering "is this pin current" needs the product to
> read a git host, which is an integration and not a column. Recorded so the next reader meets the
> limit as a decision rather than as a bug: a git link pinned a year ago looks exactly as fresh as
> one pinned this morning, and nothing on the screen says otherwise.

## §170 — the population the pairing rule could not see

*Four mutations. **`PR-1`…`PR-3` are checks over checks and were run** — §165's precedent: none of
them exercises the product, so running them is not the §65 exception §72–§76 wrongly took. **`PR-4`
is the product one and is NOT RUN.***

| # | Mutation | Expected | Result |
|---|---|---|---|
| `§170 PR-1` | Delete the `TestCaseInput` entry from `RESPONSE_RECORDS` | Should redden `every object schema is paired with a record, or says why it has none` naming `TestCaseInput`, and **nothing else** — in particular *not* `every schema a record is named for is paired`, because no record is called `TestCaseInput` or `TestCaseInputView`. That silence is the defect this slice found, reproduced on demand. | ✅ **RUN 2026-08-12 — red, `Expecting empty but was: ["TestCaseInput"]`, and the name-match rule stayed green exactly as predicted** |
| `§170 PR-2` | Add `PageMeta` to `RESPONSE_RECORDS` pointing at `CasePageView`, leaving its `BRANCH_ONLY_SCHEMAS` entry | Should redden two rules at once: the member comparison with `PageMeta.items` and `PageMeta.facets` as SERVER-ONLY, and `every exemption is still exempting something`. Proves the false positives an allOf branch generates when paired with the record serving the composition — the reason the exemption exists rather than being a shortcut. | ✅ **RUN 2026-08-12 — and the first arm was WRONG, which is why it was worth running.** Red on the member comparison (`unexpected: ["PageMeta.facets", "PageMeta.items"]`) and **green on the exemption rule**: `BRANCH_ONLY_SCHEMAS` re-derives whether anything `$ref`s the schema whole, which pairing it does not change, so the contradiction went unnamed and only a consequence reddened. Fixed in the same commit — `everyObjectSchemaIsPairedOrExempt` now asserts the three accounting maps are **disjoint**, and the re-run reddens both rules |
| `§170 PR-3` | In `openapi.yaml`, rename `TestCaseSummary.lastResult`'s `statusKind` property to `statusKindX` | Should redden `an inline shape and the record serving it name the same members, both ways` with both directions at once — `TestCaseSummary.lastResult.statusKindX` CONTRACT-ONLY and `…statusKind` SERVER-ONLY. The inline rule's first real exercise, at the exact position §166 spent a slice on. | ✅ **RUN 2026-08-12 — red, both directions: `["TestCaseSummary.lastResult.statusKind", "TestCaseSummary.lastResult.statusKindX"]`, and only that test** |
| `§170 PR-4` | Revert `IngestionRequest` to nine components (restore `branch`, `commitSha`) and POST an ingestion body carrying both | **Not a rule mutation — the product one, and the claim it checks is "removing them changed nothing".** Expected: the same 2xx and the same stored run either way, because `FAIL_ON_UNKNOWN_PROPERTIES` is off. Run it against a real instance with the components *removed* and the fields *present* in the body; a 400 there would mean a custom `ObjectMapper` this slice did not find. | ⬜ not run |

> **Not mutation-tested, and it is the same limit §165 recorded.** These rules compare a schema's
> declared properties to a record's **components**, statically — a `@JsonIgnore`, a `@JsonProperty`
> rename or a custom serializer satisfies them while changing the bytes. §170 widened the population
> from 79 pairings to 144 and did not widen what a pairing can see.

## §171 — the form parts nobody compared

*Four mutations, **all four run**, §165's and §170's precedent: every one is a check over a check,
so running them is not the §65 exception §72–§76 wrongly took. Two of them touch a production file
and are reverted in the same command — the mutation is of the **handler's signature**, which is the
only side of this comparison that lives in `src/main`.*

| # | Mutation | Expected | Result |
|---|---|---|---|
| `§171 FP-1` | In `openapi.yaml`, delete the `caption` property from the evidence form (leaving `stepId` and `file`) | Should redden `no multipart handler binds a part the contract does not declare`, and **only** that one — the reverse direction has nothing to say, because every remaining declared part is still bound. The direction that matters least to the server and most to a reader: the field vanishes from both copies of `schema.d.ts`, so no generated client can send it and the handler's argument is null forever. | ✅ **RUN 2026-08-12 — red on exactly that test, every other rule in the class green** |
| `§171 FP-2` | In `openapi.yaml`, move `caption` into the evidence form's `required` array | Should redden `a form part is required on both sides or optional on both` and nothing else. The mutation is the cheap contract edit somebody makes when a caption starts feeling mandatory, and it ships a document promising a 400 the handler will never give. | ✅ **RUN 2026-08-12 — red on exactly that test; the two membership rules stayed green, which is the point of keeping requiredness a separate rule rather than folding it into the set comparison** |
| `§171 FP-3` | In `EvidenceController`, rename the bound part — `@RequestParam(value = "caption")` → `"legend"` — leaving the Java field name alone | Should redden **both** membership rules at once: `caption` declared and unbound, `legend` bound and undeclared. **The row worth reading**: this is the actual defect the whole slice exists for, and before this commit it was a 201 with a silently dropped caption and no test anywhere that could see it. | ✅ **RUN 2026-08-12 — red on both, as predicted; requiredness stayed green because the disagreement is about the name, not the flag** |
| `§171 FP-4` | In `CaseAttachmentController`, delete the `@RequestPart("file")` annotation entirely, leaving a bare `MultipartFile file` parameter | Should stay **GREEN**, and that is the assertion — Spring binds an unannotated `MultipartFile` by parameter name, so `formPartsBoundBy`'s third arm is the difference between reading the running server and reading two annotations. Without it a handler rewritten this way would read as binding *nothing* and both membership rules would go quiet on the one endpoint somebody had just touched. | ✅ **RUN 2026-08-12 — green, all six tests; the arm is load-bearing rather than defensive** |

> **What these rules still cannot see, and it is the multipart mirror of §165's limit.** The
> comparison is *names and requiredness*, both read statically. A part bound to the right name with
> the wrong **type** passes — `@RequestParam UUID stepId` against a contract saying
> `type: string, format: uuid` agrees, and so would `type: integer`. So does a part the handler
> binds and then ignores. The one direction that is genuinely closed is the one that used to fail
> silently: a name that exists on one side and not the other.

## §174 — the claim corrected in place

Three rows, all against `backend/`. Each names a one-line edit, the test that must redden, and what
a green build after the edit would have meant. Revert each in the same command.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§174 RL-1` | In `DocLink.relabel`, drop the `if (relationship == next) return false;` guard so it always stamps and returns `true` | `ExternalLinkEndpointTest.Relabelling.reStatingTheSameClaimWritesNothing` | A correction nobody made is recorded against a user and raises a company-wide recompute, once per duplicate click. The response is identical either way — 200 with the row — so no client and no other test can see it. | ✅ **RUN 2026-08-22 — RED, exactly 1**, the named test. MATCH.
| `§174 RL-2` | In `DocLink`, restore `updatable = false` on the `relationship` column | `…Relabelling.theClaimIsCorrectedAndStamped` and `PlanDocumentEndpointTest.Links.theClaimIsCorrectable` | The endpoint answers **200 with the corrected view**, the audit row is written, the event fires, and **the database never moves** — Hibernate silently drops the column from the UPDATE. The response is a lie the whole way down, and only a read-back of the row catches it, which is why both tests re-read rather than trusting the body. ✅ **RUN 2026-08-22 — RED, exactly the 2 predicted**, one in each class. MATCH. |
| `§174 RL-3` | In `BlockLinkService.relabel`, default an unparseable relationship to `VERIFIES` instead of returning `Invalid` (the create's line, copied) | `…Relabelling.thereIsNoDefaultOnACorrection` and `PlanDocumentEndpointTest.Links.relabelIsGatedAndValidated` | A typo'd or absent relationship silently **promotes the link to coverage**. The one direction of this field that inflates a coverage number is the one a copied default would take, on the endpoint whose reason for existing is that the field was wrong. |

## §175 — one TeamRef, and the three rules now holding it

Three mutations, **all three run** — §165's and §170's precedent, and this slice is the clearest
case for it: every one is a check over a check, and what they are checking is that a *consolidation*
cannot be silently undone. An extraction is only worth more than the three copies it replaced if
re-introducing a copy fails a build, and that claim is unfalsifiable without running these. Revert
each in the same command.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§175 TR-1` | Add a record with the same components back into a feature module: `public record GranteeGroup(UUID id, String name) {}` nested in `identity.RoleAssignmentView` | `ArchitectureRulesTest.noRecordOutsideApiRepeatsAnApiShape` | The fourth copy is written and nothing says so. This rule is the entire reason `api.TeamRef` is an extraction rather than a tidy-up: it is matched on **shape, not name**, so the copy that actually gets written — under some other name, in a module that could not import this one — is caught too. → ✅ **RUN 2026-08-12 — red on exactly that test, and on nothing in `ContractConventionTest`**, which is correct: `GranteeGroup` matches no schema's name, so every name-keyed rule is blind to it and the shape rule is the only observer there is. |
| `§175 TR-2` | In `openapi.yaml`, revert `RoleAssignment.team` to the inline `{id, name}` object it was before this slice | `ContractConventionTest.everyInlineShapeIsPairedOrExempt` | An anonymous shape re-enters the contract and nothing asks which record serves it — which is precisely the state §170 closed and §175 depended on. The Java side would still compile and still send the same two keys, so the regression is invisible to every other gate. → ✅ **RUN 2026-08-12 — red on exactly that test, alone.** |
| `§175 TR-3` | In `openapi.yaml`, add `isLead: { type: boolean }` back to the `TeamRef` schema's properties | `ContractConventionTest`'s member comparison for `TeamRef` (the `RESPONSE_RECORDS` direction) | The schema promises a property `api.TeamRef` cannot produce — §148's direction, and the one that used to be untestable here because the schema and the record that served it were only related by a name. It is also the exact shape of what §175 removed, so this row is the check that the removal was made in both places. → ✅ **RUN 2026-08-12 — red on `a schema and the record serving it name the same members, both ways`, alone.** |

**`TR-1` was attempted first as a same-named record and does not compile**, which is recorded
rather than quietly re-worded (§164 `EN-3`'s precedent). `public record TeamRef` nested in a file
that imports `com.tcms.api.TeamRef` is a name clash the compiler refuses — so the *literal* re-copy
this slice replaced can no longer be written in the one file it used to live in, and the mutation
had to be renamed to be possible at all. That is a weaker guarantee than it sounds and the reason
the rule matches on shape: rename it and it compiles fine, which is exactly what the run above did.

> **What these do not cover, stated because the slice's whole argument is about coincidence.** The
> three positions are now one schema, so nothing can compare them to *each other* any more — there
> is nothing left to disagree. That is the intended end state and it is also a loss: the day
> `ReviewAssignee.team` genuinely needs a member the other two must not have, the rules will report
> nothing and the decision is a human's. `TeamRef`'s own schema comment carries that warning.

## §176 — the query parameter nobody declared

Three mutations, **all three run** — §165's, §170's and §171's precedent, and the same argument:
the slice ships one test method and nothing else, so an unrun row here is the whole slice taken on
trust. `QP-2` is the ownership row and is deliberately an **expected-green** one on the rule this
slice added; reading it beside `QP-1` is what shows the media-type skip is a boundary rather than a
hole. Revert each in the same command.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§176 QP-1` | In `WorkItemController.list`, add a parameter no schema declares: `@RequestParam(name = "assignedBefore", required = false) String assignedBefore` | `ContractParameterBindingTest.noHandlerBindsAnUndeclaredQueryParameter` | The defect the rule exists for, in the form it actually arrives in — somebody adds a filter to a handler and forgets `openapi.yaml`. **Nothing else in the repo can see it**: the code compiles, every existing test passes, and the parameter is simply unreachable, because no generated client can name a key the contract does not declare. → ✅ **RUN 2026-08-12 — red on exactly that test, message `GET /work-items  binds [assignedBefore]  (contract declares [projectId, teamId, mine])`; the other six tests in the class green** |
| `§176 QP-2` | The same undeclared parameter, added instead to `EvidenceController.attach` — a `multipart/form-data` operation | `noHandlerBindsAnUndeclaredFormPart`, and **`noHandlerBindsAnUndeclaredQueryParameter` must stay GREEN** | Both halves matter. If the query rule reds, `@RequestParam` on a form is being read as a query key and the two rules report one name under two diagnoses — the ambiguity §171 recorded and refused to guess at. If the *form* rule stays green, the skip has become a hole and a dropped part on the four upload paths is unobserved again. → ✅ **RUN 2026-08-12 — red on `no multipart handler binds a part the contract does not declare` alone; the query rule green, so each name has exactly one owner** |
| `§176 QP-3` | In `openapi.yaml`, rename the `GET /work-items` query parameter `mine` → `mineOnly`, leaving the handler alone | **both** membership rules: `everyDeclaredQueryParameterIsBound` and `noHandlerBindsAnUndeclaredQueryParameter` | The rename case, and it is the row that shows what was and was not already covered. A rename produces a declared-and-unbound name *and* a bound-and-undeclared one, so the old rule was already red on half of it — this slice's rule is what makes an **addition** visible, where the old rule has nothing to say at all. → ✅ **RUN 2026-08-12 — red on both, as predicted; the four form/handler rules green** |

**The floor was measured rather than guessed.** Temporarily raising
`assertThat(compared).isGreaterThan(5)` to `500` printed **13** — thirteen non-multipart operations
bind at least one query parameter — so the floor is a live tripwire against the mapping lookup
silently matching nothing, which is the failure mode `patternsOf`'s javadoc names. It is left at 5
rather than 13 for the same reason the sibling rule's is: a floor that tracks the exact count fails
on every legitimate deletion of a filter and teaches the next author to edit the number.

## §177 — the columns V1 created for a milestone that arrived

**Five rows, none run** — §65's standing policy, and this slice is the ordinary case for it rather
than §170/§171/§176's exception. Those three shipped a test method and nothing else, so an unrun row
was the whole slice on trust; this one ships product behaviour across two stacks, and the batched
pass is what it is for. Recorded in executable form.

The pair worth reading together is `GT-M1` and `GT-M2`. They mutate the two halves of one claim —
the value reaching the **row** and the value reaching the **response** — and each must redden a
*different* test. If either mutation reds both, the two tests are one test written twice, and the
slice's central risk is unobserved: a field echoed back out of the request it arrived in looks
identical to a field that was stored.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§177 GT-M1` | In `TestRun.create`, drop `created.commitSha = edit.commitSha();` | `RunCreateEndpointTest.GitTraceability.theColumnsAreActuallyWritten` **and** `bothAreStoredAndServed` — both, because the response is built from the entity | Exactly §170's defect restored: the request binds, the value goes nowhere, the caller gets a 2xx. A green build here means nothing in the suite reads `test_run.commit_sha` and the whole slice is a no-op that compiles. |
| `§177 GT-M2` | In `RunService.view`, pass `null` where `run.getCommitSha()` goes | `bothAreStoredAndServed` **only** — `theColumnsAreActuallyWritten` must stay **GREEN** | The stored-versus-served distinction. A value in the column that no endpoint serves is invisible to every client, and the *green* half is what proves the other test asserts the row rather than the echo. ✅ **RUN 2026-08-22 — RED, exactly 1: `bothAreStoredAndServed`, and `theColumnsAreActuallyWritten` STAYED GREEN.** MATCH on both halves — the required green is the informative one. ⚠️ Scope correction: the row's tests live in `RunCreateEndpointTest`, not `ExecutionEndpointTest`. |
| `§177 GT-M3` | In `RunEdit.validateCreate`, delete the `SHA.matcher(...)` branch | `aBranchNameIsNotACommitId` and `IngestionEndpointTest.aMalformedShaRefusesTheWholePost` | The failure both declarations of the rule exist for — an unchecked value accepted, stored, and discovered to be a branch name by its reader. Two tests in two classes because the rule is enforced once and reached by two endpoints; a green ingestion test with a red create test would mean CI has its own validation path. |
| `§177 GT-M4` | In `RunCloneService.derive`, replace the two `null`s with `source.getCommitSha()` / `source.getBranch()` | `RunCloneEndpointTest.theGitCoordinateIsNotInherited` | The one fallback that goes the other way. A clone inheriting the sha ships a run claiming a commit it never tested, and — with no run edit endpoint — the only correction is deleting the run. This is the row that pins a *deliberate absence*, which is the kind a later "consistency" cleanup removes. ✅ **RUN 2026-08-22 — RED, exactly 1: `theGitCoordinateIsNotInherited`.** MATCH. The absence is pinned. |
| `§177 GT-M5` | In `RunEdit`, change `SHA` to `Pattern.compile("[0-9a-f]{7,40}")` — lower-case only | `GitObjectNameRuleTest.everyDeclarationAgrees` **and** `RunCreateEndpointTest.caseIsPreserved` | Two observers for one drift, deliberately. The convention test is the one that matters: it reads the source text of both declarations, so it fails on a change that is *behaviourally* almost invisible — a mixed-case sha pasted out of a GUI is refused on runs and accepted on doc links, and nothing else in the repo compares the two. |

## §178 — the two signals a panel's table already answered

**Five rows, none run** — §65's standing policy, and §177's paragraph applies unchanged: this slice
ships product behaviour, so it is the ordinary case rather than §170/§171/§176's exception.

The row that matters most is `SL-M1`, and it is a **statement-order** mutation rather than a content
one. Nothing else in `CaseHealthRecomputeTest` can fail that way, and if it does not redden exactly
one test then the ordering argument at `SpecLinkage`'s header is an unobserved claim — the panel and
the chip would drift by one tick with every other assertion in the class still green.

`SL-M2` and `SL-M3` are the two directions of THE decision, and they must redden **different** tests.
A mutation that reds both means the two relationship calls have been collapsed into one rule, which is
the collapse the slice exists to refuse.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§178 SL-M1` | In `CaseHealthRecomputeService.recompute()`, move `specLinkage.recomputeLinkageSignals(now)` **above** `documentationFacts.refreshCaseDocLinks()` | `CaseHealthRecomputeTest.LinkageSignals.aLinkMadeSinceTheLastTickCountsOnThisTick` — **and nothing else in the class** | The lag §167's whole arrangement was built against, reintroduced by a reordering no reviewer would question. Both signals would be derived from the previous tick's mirror: a case linked a minute ago keeps its `UNLINKED` chip while its own Traceability panel, refreshed in the same transaction, already lists the link. A green build means the ordering is untested and the only thing holding it is a javadoc. If it reds a *second* test, that test is accidentally asserting the order and its name does not say so. |
| `§178 SL-M2` | In `SpecLinkage`, add `AND l.relationship = 'VERIFIES'` to the `case_doc_link` lateral | `LinkageSignals.everyRelationshipCountsAsALink` (via `UNLINKED`) — and **not** `aMovedSpecRaisesTheErrorAndNotTheInfo` | The "consistency with the coverage matrix" edit, which is the single likeliest change anybody makes to this file. A green build means the decision is prose only: a case whose panel lists a `REFERENCES` link would be chipped "Unlinked", one inch away from the link it denies. |
| `§178 SL-M3` | In `SpecLinkage`, change `bool_or(l.stale)` to `bool_or(l.stale AND l.relationship = 'VERIFIES')` | `LinkageSignals.anyStaleLinkIsEnough` — and **not** `everyRelationshipCountsAsALink` | The opposite direction of the same decision, and this row is why `LINKED_TO_ONE_MOVED_SPEC_OF_TWO`'s moved link is `DERIVED_FROM`: **writing this row is what found that the fixture's first draft made both links `VERIFIES`**, at which point the mutation could not fail and half of THE decision — that an ERROR fires on a case *written from* a rewritten statement — was pinned by nothing. Fixed in the fixture rather than recorded as a gap. A green build now means the narrowing is prose only. |
| `§178 SL-M4` | In `SpecLinkage`, change `bool_or` to `bool_and` | `LinkageSignals.anyStaleLinkIsEnough` **only** — `aMovedSpecRaisesTheErrorAndNotTheInfo` must stay **GREEN**, because that case has exactly one link | The reason a case needs *two* links in a fixture at all. With one link the two folds are indistinguishable, so the green half is what proves the multi-link fixture earns its keep: a case verifying two statements is wrong the moment either is rewritten, and `bool_and` would put a clean chip above a panel carrying a stale marker. |
| `§178 SL-M5` | In `SpecLinkage`, remove `unlinked` from the `ON CONFLICT … DO UPDATE SET` list (leave it in the `INSERT`) | `LinkageSignals.aStaleFlagIsCleared` and `bothSignalsSelfHeal` | The latching failure, and the one no fixture can produce by accident — the only writer of this column is the statement under test, so a flag that never clears is invisible to everything except a test that sets one by hand. §6o's "no button for lying" rule from the other side: a signal that cannot go false is a claim nobody can act on. ❗ **RUN 2026-08-23 (wave 2) — RED, 53**, against 2 predicted — and the cause is a fact nobody had written down. `SpecLinkage` is the **fifth** statement of the tick, so the row always exists by the time it runs and it is **always in the `ON CONFLICT` arm**. Dropping `unlinked` from that arm does not make the flag latch; it means the column is **never written at all**. The `INSERT` column list is reachable only for the tick's first statement. |

## §179 — the severity nobody compared, and the order that hid the failure

**Six rows, none run** — §65's standing policy, and the ordinary case again (this slice ships
behaviour a user sees). Ids are `§179 RS-M*`; grep before minting a prefix — `RS` is unused, and the
three collisions in this file all came of two lanes picking the same obvious initials.

Two of these are *deletion* mutations against a decision that moved between stacks, which is the
shape this slice is about: **the guarantee `HealthChip.test.tsx` used to hold has to still be held by
something after the list it guarded is gone.** `RS-M1` is that check, and it is the row that matters
most — if it reddens nothing, then moving severity onto the wire silently deleted the only observer of
the automation pair's asymmetry, which is §153's finding with the stacks swapped.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§179 RS-M1` | In `HealthSignal`, change `UNDECLARED_AUTOMATION`'s severity from `WARNING` to `ERROR` | `RaisedSignalTest.Severities.theAutomationPairStaysAsymmetric` **and** `everySignalCarriesItsDecidedSeverity` | The tidy change the pair invites — two mirror signals given a matched appearance — and the one §120 argued against in writing. Both tests must fail: the named one because it is the argument, the table one because a severity that is not the decided one is a product decision nobody typed twice. A green build means `SEVERE_SIGNALS`' deletion cost the repo its only check on the distinction. ⚠️ **RUN 2026-08-23 (wave S) — RED, 3.** MORE than the 2 predicted; both named arms fell (`theAutomationPairStaysAsymmetric` and `everySignalCarriesItsDecidedSeverity`) plus `severityTravelsWithItsOwnSignal`, which reads the same decided value. **`SEVERE_SIGNALS`' deletion did not cost the repo its check on the distinction** — the answer this row most wanted. |
| `§179 RS-M2` | In `HealthSignal`, swap `CONDITION_DRIFT`'s severity to `WARNING` | `everySignalCarriesItsDecidedSeverity` **only** | The exact member the SPA's deleted list got wrong. It must redden by the *table* rather than by a behaviour test, because there is no behaviour that distinguishes them — which is the whole reason the table is written out instead of read off the enum. ✅ **RUN 2026-08-23 (wave S) — RED, 2.** `everySignalCarriesItsDecidedSeverity` as predicted, plus `severityTravelsWithItsOwnSignal` for the same reason as `RS-M1`. The table catches it, which is the row's point: there is no behaviour that distinguishes the two. |
| `§179 RS-M3` | In `CaseHealth.signals()`, delete the `raised.sort(Comparator.comparing(HealthSignal::severity))` line | `RaisedSignalTest.Order.theWorstSignalComesFirst` **and** `everySeveritySorts` — and **not** `tiesKeepDeclarationOrder`, which passes either way | The state this slice found: declaration order, so `CaseGrid`'s one visible chip is `UNLINKED` on a case whose automation has stopped reporting. The green third test is the point of having it — a tie-break assertion that fails when the sort is removed is asserting the sort, not the tie-break. ⚠️ **RUN 2026-08-23 (wave S) — GREEN. ZERO-UNEXPECTED, and this is wave S's best finding.** Deleting `raised.sort(...)` reddens **nothing**: `signals()` iterates `HealthSignal.values()` in declaration order, and **every pair the `Order` fixtures choose is already declaration-ordered worst-first.** `theWorstSignalComesFirst` picks `UNLINKED` (idx 6, INFO) and `STALE_AUTOMATION` (idx 3, ERROR); `everySeveritySorts` picks `CONDITION_DRIFT` (2, ERROR), `NEVER_EXECUTED` (4, WARNING), `UNLINKED` (6, INFO) — both already in the expected order without any sort at all. **A control proves the sort is live rather than dead**: `§179 RS-M4`, run in the same batch, reddens `everySeveritySorts` by reordering `Severity`, so the comparator does execute and that test does observe its result. The declaration order is **not** globally severity-sorted — `STALE_APPROVAL` (1, WARNING) precedes `CONDITION_DRIFT` (2, ERROR), and `UNLINKED` (6, INFO) precedes `FAILING_REPEATEDLY` (7, WARNING) — so the repair is one line: pick a pair that crosses, e.g. `UNLINKED` + `FAILING_REPEATEDLY`. |
| `§179 RS-M4` | In `HealthSignal.Severity`, reorder the constants alphabetically (`ERROR, INFO, NEUTRAL, WARNING`) | `severityIsDeclaredWorstFirst`, **and** `everySeveritySorts` | Two observers for one edit, and the second is what makes the first more than a style rule: a `Comparator` over an enum orders by ordinal, so alphabetising inverts both this sort and `DashboardService.health`'s tiles. `severityIsDeclaredWorstFirst` alone would look like a lint; the behaviour test is the consequence. ✅ **RUN 2026-08-23 (wave S) — RED, exactly 2: `severityIsDeclaredWorstFirst` and `everySeveritySorts`.** MATCH, and the second is exactly what the row said made the first more than a lint. **It also served as `§179 RS-M3`'s control** — it is the measurement proving the sort executes and is observed, which is what makes RS-M3's zero attributable to the fixtures rather than to dead code. |
| `§179 RS-M5` | In `RaisedSignalView.of`, return `new RaisedSignalView(signal, HealthSignal.Severity.ERROR)` | `severityTravelsWithItsOwnSignal` | The pair coming apart — every chip red, which reads as a product in crisis rather than as a bug. It is the cheap version of the off-by-one this record exists to make impossible (two lists assembled separately and zipped); the honest version cannot be written any more, which is the improvement. ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `severityTravelsWithItsOwnSignal`.** MATCH. |
| `§179 RS-M6` | In `ContractEnumTest.OWNERS`, delete the `HealthSignalSeverity` entry | `everyContractEnumHasAnOwner` | The named-enum population rule, on the enum this slice named. A green build means the schema is on the wire in two places with nothing comparing its members to the server's — the state `DashboardTile.severity`'s inline enum was in from M0 until §179, and the reason it was invisible to §161's audit. ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `everyContractEnumHasAnOwner`.** MATCH. The named-enum population rule holds on the enum §179 named. |
| `§180 TL-M1` | In `DashboardView.TileView`, add `String label` back and populate it in `DashboardService.health` from a new `signal.name()` | `DashboardEndpointTest.Tiles.tilesCarryNoWords` | The regression this slice exists to prevent, in the form it would actually take — somebody re-adding display text because a client asked for it. The test asserts **absence**, which is the only assertion that can fail on an addition; an assertion on the tile's other fields passes happily beside a new one. |
| `§180 TL-M2` | In `DashboardService.segments`, restore the `"var(--color-" + status.name().toLowerCase() + ")"` argument and the matching `SegmentView` component | `ByFeature.barsKeepDataWordsAndSegmentsLoseRenderedOnes` | The colour half, which had **no test on either stack** before this slice — the server composed it by concatenation and `StatusChip.tsx` held the identical four strings in a table, and nothing compared them. A green build means the wire can carry presentation for a closed enum again without anybody noticing, which is how it got there the first time. |
| `§180 TL-M3` | In `DashboardService.byFeature`, replace the bar's `labels.get(entry.getKey())` with `entry.getKey().toString()` | `ByFeature.hierarchyIsReadable`, `oneJoinTableHoldsEveryDimension` **and** `barsKeepDataWordsAndSegmentsLoseRenderedOnes` | The **other side of the line this slice draws**, and the reason it is worth mutating: a bar's label is data only the server can resolve, so deleting it is a real loss where deleting a tile's label was deleting a copy. Three tests must redden. A slice that took "labels belong to the client" one step further than the argument supports would make this mutation green. |
| `§180 TL-M4` | In `LifecycleStatus`, change `APPROVED`'s label to `"approved"` | `CaseBulkEndpointTest`, the assertion on *"Only a test lead can change a case that is already approved."* | The one surviving caller, and the check that `label()` is still load-bearing after the deletion. It is a **sentence** the server composes, not a field a client renders — §149's rule — and a green build here would mean the accessor could have been deleted with the rest and nothing would have said so. |

## §181 — the last writerless signal, and the two maps that moved

**Six rows, none run** — §65. Ids are `§181 OS-M*`; `OS` is unused in this file.

`OS-M1` and `OS-M2` are the two halves of the **decision**, and they are the rows that matter: the
predicate is three `OR` terms and two of them are narrowings somebody will one day read as
omissions. `OS-M5` and `OS-M6` are about the two by-decision maps, where this slice moved one member
in each direction and the second direction is the one no rule could have reported alone.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§181 OS-M1` | In `OwnerStanding`, drop the `c.owner_id IS NOT NULL` conjunct | `anUnownedCaseIsNotAnOrphan` | The widening that looks like a bug fix: an unowned case reported as orphaned. `owner_id` is nullable and unowned cases are ordinary, so this puts an INFO chip on most rows of a young project — `UNLINKED`'s volume failure with none of its traceability argument. A green build means the decision has no observer and the next author will make it, because "a case with no owner is surely orphaned" is the more natural reading of the word. |
| `§181 OS-M2` | In `OwnerStanding`, replace the three-term disjunction with `m.status IS DISTINCT FROM 'ACTIVE'` | `anInvitationOutstandingIsNotAnOrphan` | The *shorter* predicate, and the one a reader arrives at by simplifying rather than by deciding. It reports a colleague being onboarded as somebody who left, for the whole of their first week, and then heals the day they accept — a signal that is wrong exactly while somebody is watching a new person's name. Note it must **not** redden `someoneWhoWasNeverAMemberHereIsAnOrphan`: `IS DISTINCT FROM` is null-safe, so the missing-row term survives the mutation, which is why that test cannot stand in for this one. |
| `§181 OS-M3` | In `OwnerStanding`, delete the `u.status = 'DEACTIVATED'` term | `aDeactivatedAccountIsAnOrphan` **only** | The term with no production writer, deleted as dead code — which is what it looks like to a scan, and the reason the row is here. A green build means the account screen can ship and silently stop covering the state it introduces, which is the failure mode this whole family of by-decision maps exists to make loud. ✅ **RUN 2026-08-22 — RED, exactly 1: `aDeactivatedAccountIsAnOrphan`, and only it.** MATCH — the term with no production writer is not dead code by the suite's reckoning. |
| `§181 OS-M4` | In `OwnerStanding`, delete `AND m.company_id = c.company_id` from the membership join | **nothing, under RLS** — and that is the expected result | The honest row: with `FORCE ROW LEVEL SECURITY` on, the predicate is redundant and `theSameContractorIsJudgedPerCompany` passes without it. It is not there for isolation; it is there for **cardinality** with RLS disabled, which `V1`'s header permits per deployment, where one owner in three companies contributes three copies of every case they own and `ON CONFLICT DO UPDATE` refuses to affect a row twice. Recorded as a mutation whose green is the finding: if you want to observe it, the check needs a suite that runs with RLS off, which does not exist. |
| `§181 OS-M5` | Add `CaseHealth.orphaned` back to `WRITERLESS_BY_DECISION` | `everyMappedColumnHasAWriter` | The both-ways property of the map, in the direction §162 built it for — an entry that no longer exempts anything must fail. This is the sixth time a member has left that list by being written; a green build means the list has become one-directional again and can accumulate members that stopped needing to be on it.  ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `everyMappedColumnHasAWriter`.** MATCH. The both-ways property holds — an entry that no longer exempts anything fails the build. |
| `§181 OS-M6` | In `CaseHealthRecomputeTest.signalsOf`, remove `"incomplete"` from the column list | `everyMappedColumnHasAReader` **and** `Boundaries.otherSignalsAreNotClobbered` | The edit that looks like tidying a stray column out of a helper about *signals* — and `incomplete` is not one, which is exactly why it was chosen. Two rules must redden together: the boundary test loses its only witness (every other `case_health` boolean now has a writer), and the reader rule reports the column as readerless again, correctly, because the SELECT was its only reader. A green build on either would mean the two are no longer coupled, and the next author deletes the word. |

## §182 — the last unwritten column, and the witness that had to stop being one

**Six rows, none run** — §65. Ids are `§182 CC-M*`; `CC` is unused in this file.

`CC-M1`–`CC-M3` are the three terms of the **decision**, and each is a narrowing somebody will read
as an omission: the gate that keeps drafts out, the format branch that keeps Gherkin cases out, and
the either-spelling reading of "expected results". `CC-M6` is the interesting one — it mutates the
*test*, because this slice replaced the boundary assertion's shape rather than its witness, and the
new shape has a property the old one structurally could not have.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§182 CC-M1` | In `CaseCompleteness`, drop the `c.lifecycle_status = 'APPROVED'` conjunct | `theGateIsTheSignal` **and** `aRetiredCaseIsNotUnfinished` | The widening that reads as generosity: every case somebody is currently writing reported as incomplete, which is most of an active project. §6o's wording is *"Approved but missing…"*, and the gate is not a filter on the signal — it **is** the signal, since the fault being named is a sign-off that covered unfinished work. Two tests, because DRAFT and DEPRECATED are separate decisions with separate arguments. |
| `§182 CC-M2` | In `CaseCompleteness`, replace the `CASE WHEN c.step_format = 'GHERKIN'` branch with the `ELSE` arm alone — i.e. read `test_step` for every case | `aScenarioIsNotMissingItsSteps` | **The likeliest edit anybody makes here**, because "has steps" obviously means "has `test_step` rows". It reports every Gherkin case in the product as unfinished — not a near-miss but the whole repository of a Gherkin-first team — and every other assertion in the group passes while it does. `step_format` makes the two storages alternatives (plan §6a); the branch is the derivation knowing that. |
| `§182 CC-M3` | In `CaseCompleteness`, delete the `coalesce(btrim(c.expected_outcome), '') = ''` conjunct, leaving only the per-step check | `theCaseLevelOutcomeCounts` | The stricter reading of "expected results", and the one the plural in §6o suggests. `TestCase.expectedOutcome`'s own comment says the two columns answer different questions; requiring the per-step one calls a house style a gap, and the term is really asking whether a tester can tell a pass from a fail. A green build means the two spellings have silently become a checklist. |
| `§182 CC-M4` | In `CaseCompleteness`, change both `coalesce(btrim(…), '') = ''` tests to `… IS NULL` | `blankIsNotStated` | A present, empty string is what a rich-text editor leaves behind when somebody clears a field, so `IS NULL` is the spelling that looks equivalent and is not. The fixture makes **both** columns whitespace, so an implementation testing either for null passes the row — which is why the case exists rather than being folded into `CC-M3`. ✅ **RUN 2026-08-22 — RED, 2. MORE, and better than the row credited**: both halves of the `IS NULL` swap are separately observed — the Gherkin arm by `anEmptyScenarioIsIncomplete` and the expected-outcome arm by `blankIsNotStated`. The fixture is not blind to either column. |
| `§182 CC-M5` | Add `CaseHealth.incomplete` back to `WRITERLESS_BY_DECISION` | `everyMappedColumnHasAWriter` | The both-ways property of the map, on the member that was its reference entry — and the third time this one column has moved between the two by-decision maps without the column itself changing. A green build means the list is one-directional again (§162), and the entry that documented the state best is the one left describing a state that has ended.  ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `everyMappedColumnHasAWriter`.** MATCH, on the member that was the pair's reference entry. Run beside `WL-1` (remove an entry), `AR-M5` and `§181 OS-M5`: **all four directions of the `D5` family measured, all four red.** |
| `§182 CC-M6` | In `CaseHealthRecomputeTest.Boundaries.otherSignalsAreNotClobbered`, add `"unlinked"` to `OwnerStanding.recomputeOrphaned`'s `owns` list | `otherSignalsAreNotClobbered` itself, on its **partition** assertion | The row that pins the new shape's own guard rail. The seven statements must own every column this job derives *exactly once*, so a column claimed twice fails before any statement runs. Under the old witness shape this mutation was unexpressible — the assertion watched one column outside all seven and could not have seen two of them colliding on a column inside. A green build means the partition check has stopped being a check, and the loop degenerates into seven weaker assertions. ✅ **RUN 2026-08-22 — RED, exactly 1: `otherSignalsAreNotClobbered`, on its partition assertion.** MATCH — a column claimed twice fails before any statement runs, which is what the new witness shape was for. |

## §185 — the blob roots nobody provisioned, and the rules that were green by cache

**Four rows, none run** — §65. Ids are `§185 SR-*`; `SR` is unused in this file (grepped before
minting it, per the §154 banner: `AG`, `CD` and `RL` all collided because two lanes reached for the
same initials).

**`SR-4` is the row this block exists for**, and it is unlike every other row in this file: it does
not mutate a *statement*, it mutates the *build definition*, and the thing it proves is that the
other three rows can fail at all. Every source-text rule in this suite was green by cache until
phase 1 — `BUILD SUCCESSFUL`, `:test UP-TO-DATE`, rule not executed — and a mutation row against a
rule that does not run is a check on a check that is itself a claim.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§185 SR-1` | In `backend/Dockerfile`, delete the `RUN mkdir -p /var/lib/tcms/… && chown …` line, leaving the comment block above it intact | `everyStorageRootIsCreatedInTheImage` | The measurement phase 1 already took once, kept as a row because the *comment* is the interesting half. That block names the paths by brace expansion (`{evidence,reports,raw-reports}`), so the rule fails today; spell one path out in the prose and the rule starts passing over its own explanation. A green build means the comment-stripping has broken and the rule is satisfied by the text describing it — §162's two dead exemptions, one layer up. |
| `§185 SR-2` | In `compose.yaml`, delete the `- tcmsblobs:/var/lib/tcms` mount, leaving the `volumes:` declaration at the bottom | `everyStorageRootIsPersistedLocally` | Exactly the state both live passes met, and the one a reader "tidying an unused volume" would produce — the declaration at the bottom still looks like the feature is there. A green build means `docker compose up --build` can go back to 500ing on the first evidence upload with nothing to say so, which is how this defect survived being found the first time. |
| `§185 SR-3` | In `application.yml`, add a fourth `root: ${TCMS_ARCHIVE_ROOT:/var/lib/tcms/archive}` under `tcms.storage` | `everyStorageRootIsCreatedInTheImage` **and** `everyStorageRootIsMountedInProduction` | The row the rules were actually written for: a fourth store must fail the build until it is provisioned in every artifact. ✅ **RUN 2026-08-22 — RED, exactly the 2 required.** MATCH: a fourth store does fail the build until it is provisioned in every artifact. Both must redden, because the two artifacts fail in different ways — the image one loses the bytes to a container recreate, the production one writes them into the writable layer where `pull && up -d` destroys them (§107's recorded outage). A green build on either means the next `RawReportStore` reaches a live host unmounted, which is what §160 did. |
| `§185 SR-4` | In `backend/build.gradle.kts`, delete the `inputs.files(…)` block declaring the out-of-source artifacts, then run `SR-1` **without** `--rerun-tasks` | `everyStorageRootIsCreatedInTheImage` — and it will **not** | **The row whose expected result is a green build, and the green is the finding.** Gradle tracks a Test task's classpath and resources; `Dockerfile` and both compose files are neither, so the task reports UP-TO-DATE and the rule about the edit never executes. ✅ **RUN 2026-08-22 — GREEN, and the green is the finding, reproduced exactly.** Four steps by hand through `tcms-remote` (the runner drives one edit and one run, not a sequence): delete `inputs.files`, run once, break `backend/Dockerfile` so it no longer creates the `raw-reports` root, run again → **`> Task :test UP-TO-DATE` · `BUILD SUCCESSFUL in 921ms`**. **And a control, which is what makes it conclusive**: with `inputs.files` restored and the *same* broken `Dockerfile`, `everyStorageRootIsCreatedInTheImage` FAILS. The declaration is load-bearing, and Gradle's up-to-date check is exactly what would hide its absence. Run it to see a broken Dockerfile pass. `IdempotencyKeyCapTest` read `../docs/specs/openapi.yaml` this way from §136 to §185. If this row ever *does* redden without the block, Gradle has started tracking something it did not, and `SR-1`…`SR-3` no longer need it. |

## §186 — the suite conditions nobody could write, and the reconcile that must not churn

**Five rows, none run** — §65. Ids are `§186 SQ-*`; `SQ` is unused across both queue files and every
handoff section (grepped before minting it, per the §154 banner: `AG`, `CD` and `RL` all collided
because two lanes independently reached for the obvious initials, and `SC` was already taken by
§153's repository rows).

**`SQ-3` is the row this block exists for.** Phase 1 already took that measurement once — with the
reuse branch deleted, exactly one of the eleven new endpoint tests failed and the other ten stayed
green — and it is kept as a row because the *shape* is the interesting half: `SuiteConditions` and
`CaseConditions` are the same algorithm over two tables, and the argument that makes them the same
is written down on the case side as a reason it *did not matter there*. A reader tidying the two
into one shared method would produce exactly this mutation and would have the case-side javadoc
agreeing with them.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§186 SQ-1` | In `SuiteConditions.reconcile`, replace the reuse branch (`reusable.remove(conditionId)`) with `null`, so every save deletes and reinserts | `reorderingPreservesTheAttachmentRow` | The measurement phase 1 took, kept because it is the one property nothing else in the class observes. ✅ **RUN 2026-08-22 — RED, exactly 1: `reorderingPreservesTheAttachmentRow`.** MATCH, including the ten-stay-green half. Ten of the eleven condition tests stay green: the lists come back in the right order, the counts are right, the detach still works. What is gone is every `case_condition_optout` row, on **every save of the suite** — a rename would silently re-impose a condition twenty cases had declined, and the rows proving they declined it would be cascaded away by the foreign key. A green build means the two reconciles have been "unified" and the only table where the difference matters has lost it. |
| `§186 SQ-2` | In `SuiteConditions.reconcile`, drop the `!live.contains(row.getConditionId())` guard, so a retired attachment becomes a removal candidate | *nothing* — a **hole**, and **measured as one** rather than predicted: phase 3 applied this mutation together with `SQ-5` and both suite classes stayed green | The retired bound, transferred from `CaseConditions` with its argument and **not** with a test: the fixture has a retired library entry but nothing attaches it to a suite through the endpoint, because validation refuses one (which is the point of the bound). Reproducing it needs a hand-written attachment to `RETIRED_CONDITION` on a suite, then a rename. Recorded as unrun rather than quietly written, because the honest version of this row is "the check does not exist yet, here is what it would take". |
| `§186 SQ-3` | In `SuiteEdit.normalizeConditions`, return `List.of()` instead of `stored` when `submitted == null` | `absentIsNotEmpty` | Absent collapsing into empty — the failure the whole absent/empty split exists for, and the one a reader "simplifying a null check" produces. The SPA's rename dialog does send both lists, so the SPA would not notice; the CLI's `suite edit --name` does **not** (`suiteInputFrom` drops them, deliberately), so every CLI rename would detach the suite's whole standing procedure and report success. A green build means the two clients disagree about what a rename does. |
| `§186 SQ-4` | In `SuiteService.conditionsBySuite`, bucket on a fixed `ConditionKind.PRE` instead of `attachment.getKind()` | `theTwoKindsAreSeparated` (read) **and** `aCreateAttachesBothKinds` (write) | The split that closed the contract gap `SuiteConditionView` recorded for eighty-eight slices. Both lists would still serialize as arrays, both would still be non-null, and every other assertion about conditions would pass — the response would simply say every cleanup step is setup, which is the undifferentiated state the single `conditions` array was in and which no gate ever noticed. |
| `§186 SQ-5` | In `SuiteWriteService.rename`, drop the two `acceptableConditionIds.addAll(stored…)` lines, keeping the library half | *nothing* — a second **hole**, the same shape as `SQ-2` and measured in the same run | The stored half of §64's union, whose failure needs a condition to be retired *after* being attached. ✅ **RUN 2026-08-22 — ZERO, as predicted: the second hole is real.** Nothing in the suite tests reaches that state, so this passes green today. **What would close it**: retire a library condition a suite already attaches, then rename that suite. What it breaks in the field: the day a condition is retired, every suite attaching it becomes unrenameable — refused over an attachment the author never made and, because the read drops retired rows, cannot see. `CaseUpdateEndpointTest` has the case-side equivalent; the suite side does not, and saying so is the row. |

## §187 — the opt-out nobody could write, and the hash that must not depend on the caller

**Six rows, none run** — §65. Ids are `§187 DC-*`; `DC` is unused across both queue files and every
handoff section (grepped before minting it, per the §154 banner — `AG`, `CD` and `RL` all collided
because two lanes independently reached for the obvious initials, and `CD` in particular is already
twice-taken).

> **THREE OF THE FOUR HOLES ARE CLOSED — §192, 2026-08-13.** `DC-1`, `DC-3` and `DC-5` each said
> "the fixture needs a decline, then a move" and no test performed one. §192's slice is that move:
> `ConditionWriteEndpointTest$Declining` now moves `DECLINE_CASE` between two folders and
> `CaseBulkEndpointTest` moves a declined case through `MOVE_TO_SUITE`. **The mutations are not
> re-usable as written, because §192 changed what they mutate** — the guard `DC-1` deletes is now
> two guards and only one of them is the protection, and `DC-3`'s union is followed by a retention
> intersection. Their replacements are `§192 DF-1`…`DF-3` below; the rows are kept here because
> what they were written against is what §192 reversed, and because `DC-4` is **still** a hole for
> exactly the reason it gives.

**`DC-3` is the row this block exists for, and `DC-6` is the one whose expected result is green.**
The hash rule — what a case declines *after* this save, never what the caller happened to know
about it — is the slice's least obvious decision and the one a reader "simplifying a union" would
undo. `DC-6` is a recorded **hole**: the shape it breaks needs a state no test fixture reaches.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§187 DC-1` | In `CaseConditions.reconcileOptouts`, drop the `declinable.contains(...)` guard on the delete side, so every stored decline becomes a removal candidate | *nothing* — a **hole**, recorded rather than claimed | The protection for a decline whose attachment no longer reaches the case (the case moved folders, or the entry was retired). Reproducing it needs a decline, then a move, then a save that omits it — three steps, none of which any current test performs. What it breaks in the field is `§187 M2-22f`: the first save after a move silently discards a decision an author made, and the evidence it was ever made goes with it. Writing "this is untested" is the honest form of the row. ✅ **RUN 2026-08-22 — ZERO, as predicted. The hole is real.** |
| `§187 DC-2` | In `CaseConditions.declinableAttachmentIds`, drop the `live.contains(...)` filter so retired entries' attachments become declinable | `refusesARetiredAttachment` | The bound that keeps a decline from being stored against a row no list carries — the state §187 removed. A green build means a client can decline something it was never shown, which is the writerless shape inverted: a row the product can create and cannot render. |
| `§187 DC-3` | In `CaseEdit.normalizeDeclines`, delete the loop that unions the invisible stored declines back in, returning `chosen` alone | *nothing today* — the **third hole**, and the most expensive one | **The row the block is named for.** The hash input stops being a function of the stored row: a save that sends the field answers a different `CaseEdit` from one that omits it, for one identical state, so a save that changed nothing writes a version whose diff is empty. It needs the same three-step fixture `DC-1` does, plus a version-number assertion across two saves. Recorded as unrun because the alternative — asserting it against a state no test can reach — is a test that passes for the wrong reason. ✅ **RUN 2026-08-22 — ZERO, as predicted. The third hole is real**: the stored-declines union can be deleted whole with nothing observing it, so the hash's independence from the request shape is asserted by nothing. |
| `§187 DC-4` | In `CaseUpdateService.apply`, move the `declinable` resolution to **after** `testCase.applyEdit(...)` | *nothing today* — the **fourth hole** | The declines would be validated against the folder the case is moving *to* rather than the one the client was looking at, so a save that both moves a case and keeps its declines would 400 on ids the response had just served. Needs a single request that changes `suiteId` **and** sends `declinedConditionIds`, which nothing does: the SPA's move is a bulk action and the bulk path sends neither. |
| `§187 DC-5` | In `CaseBulkService.loadContext`, pass `List.of()` instead of `declinesByCase.getOrDefault(...)` to `CaseEdit.of` | *nothing today* — the **fifth hole**, and it is `classificationOf`'s trap on a third field | A bulk `MOVE_TO_SUITE` over a case carrying a decline would write a version hashing as though it declined nothing, and the next ordinary save would be credited with turning the rule back off. The fixture is a declined case put through a bulk action; `CaseBulkEndpointTest` has no case with a suite-level attachment, so nothing observes it. §95 recorded the identical hole for the conditions and it has never been closed either. |
| `§187 DC-6` | In `ArchitectureRulesTest`, delete `CaseConditionOptout.of` and its two call sites in `CaseConditions` — i.e. put the table back as it shipped | `everyEntityIsWritableByTheProduct` — **and `everyMappedColumnHasAWriter` will NOT** | **The row whose value is the pair.** The new rule names `CaseConditionOptout`; the thirty-one-section-old one stays green, because two test fixtures INSERT into that table and its corpus reads `src/test`. Run both to see the exact blindness §187 was written about. If the older rule ever *does* redden here, its corpus has been narrowed — and the eight findings that narrowing produces (measured in phase 3) need answering first. ✅ **RUN 2026-08-22 — RED, 2: BOTH rules.** Which is the amendment two blocks below, confirmed by measurement rather than by reading: the corpus *has* been narrowed, the pair no longer diverges, and that is **the intended end state of this row rather than a failure of it**. The original expectation (`everyMappedColumnHasAWriter` stays green) is superseded. |

## §188 — the corpus that counted a fixture, and the claim two headers made

**Six rows.** Ids are `§188 NR-*`; `NR` is unused across both queue files and every handoff section
(grepped before minting it, per the §154 banner — `AG`, `CD` and `RL` all collided because two lanes
independently reached for the obvious initials).

**Five are unrun (§65). `NR-6` is recorded as ✅ RUN and is not an exception to the batching
policy** — it is the mutation-verification of a check written in this slice, taken while writing it,
which is what makes a green first run evidence rather than a coincidence (§175's `GranteeGroup` is
the precedent). It is written down here because the *measurement* belongs in the queue whether or
not the queue was executed.

**`NR-2` is the row this block exists for.** An explicit `= null` is an assignment, an assignment is
a writer to a bytecode rule, and two of the three unwritten `change_comment` columns were invisible
for that reason alone — inside `src/main`, where no corpus change can reach them. It is the one
finding here that narrowing the corpus does *not* explain.

**`§187 DC-6` is now half-answered.** Its last sentence said "if the older rule ever *does* redden
here, its corpus has been narrowed — and the eight findings that narrowing produces need answering
first." It has, and they are. Re-run `DC-6` against this tree and the pair no longer diverges, which
is the intended end state of that row rather than a failure of it.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§188 NR-1` | In `ArchitectureRulesTest`, put `Path.of("src", "test", "java")` back into the reader rule's corpus | `everyMappedColumnHasAReader` — with **seven** unexpected *absences*, not presences | The direction this map fails in is the one worth seeing: the entries do not go stale, they go *unreachable*, and the rule reports the by-decision list as over-long rather than reporting a column. That is the both-ways property, and it is what stops the corpus being quietly widened back to make a red run green.  ⚠️ **RUN 2026-08-23 (wave S) — RED, exactly 1: `everyMappedColumnHasAReader` — and BIGGER than the row predicted.** The direction is exactly as written (absences, not presences), but not seven: with `src/test/java` back in the corpus the computed readerless set is **`[]`** and **all sixteen** entries of `READERLESS_BY_DECISION` are reported as unexpected absences. Every one of them has a test naming it. The both-ways property is what stops the corpus being quietly widened, and it is stronger than the row claimed. |
| `§188 NR-2` | In `SuiteVersion.of`, restore `version.changeComment = null;` | `everyMappedColumnHasAWriter` — the entry becomes unreachable | **The row the block is named for.** One no-op assignment silences the rule for that column, permanently and invisibly, and it reads as tidy defensive initialisation. Two of the three `change_comment` columns were in exactly this state; the only one ever reported was the one whose factory happened to omit the field. A green build means a writer rule that any author can switch off by writing a line that does nothing. ✅ **RUN 2026-08-22 — RED, exactly 1: `everyMappedColumnHasAWriter`.** MATCH — the rule does notice a no-op assignment, so the switch-off is not available. |
| `§188 NR-3` | In `everyMappedColumnHasAWriter`, drop the `field.isAnnotatedWith(Version.class)` skip | the rule, with **fifteen** `lockVersion` members | Not a finding and not noise to be filtered downstream: Hibernate's assignment exists in no source file and no bytecode this rule can read, so reporting them is the rule asserting something untrue. Recorded because the skip looks like an exemption and is the opposite of one — the mirror rule deliberately has none, since `lock_version` really is read.  ⚠️ **RUN 2026-08-23 (wave S) — RED, exactly 1: `everyMappedColumnHasAWriter` — with EIGHTEEN `lockVersion` members, not fifteen.** The count moved because three more entities have gained `@Version` since §188. The skip is doing the work the row says it does, at a larger scale. |
| `§188 NR-4` | In `everyMappedColumnHasAWriter`, restore `src/test/resources` to the SQL corpus | *nothing today* — a **hole**, and stated as one | There is no column whose only SQL writer is a fixture's `.sql` right now, so this passes green. It is recorded because the `.sql` half of the corpus is the half a reader is most likely to widen "for symmetry" with the reader rule, and the day a fixture's init script fills a column, the rule goes quiet with no other symptom. The Java half is covered by `NR-1`.  ✅ **RUN 2026-08-23 (wave S) — GREEN.** ZERO-AS-PREDICTED: no column's only SQL writer is a fixture's `.sql` today, so widening the corpus to `src/test/resources` reports nothing. **The hole is confirmed as a hole**, and the day a fixture's init script fills a column the rule goes quiet with no other symptom. (Measured twice — the first run's log shows the assertion `PASSED` before an SSH drop, the re-run returned `:test UP-TO-DATE`, which is the same mutant's cached green rather than an unexecuted one.) |
| `§188 NR-5` | In `SeedDataTest.areasMatchTheSpa`, compare `seeded.keySet()` to `permissionAreasFromTheSpa().keySet()` instead of the maps | *nothing* — a **hole by construction**, and the point of the row | The mutation produces exactly the check that already existed twice over, which is the whole finding: `catalogueMatchesTheContract` and the SPA's own `satisfies` both hold the same 55 keys, and neither looks at the value. A reader "de-duplicating" this test into a key comparison would restore the M0 state and every gate would agree with them. ✅ **RUN 2026-08-22 — ZERO, as predicted, and it is the hole-by-construction the row describes.** The key-comparison form passes. Nothing else in the suite reads `permission.area`'s **value**. |
| `§188 NR-6` | ✅ **RUN.** In `frontend/src/lib/permissions.ts` alone, move `'library:manage'` from `'repository'` to `'project'` | `areasMatchTheSpa` — **it did** | Measured, and it settles two things at once. The check can fail, which a green first run cannot show. And `:test` **re-ran** although the only edit in the tree was a `.ts` file in another stack — so `build.gradle.kts`'s new `inputs.files` line works and the rule is not green by cache, which is §185's failure and the one this kind of check meets most often. |

## §189 — the two ids neither machine surface could name

**MOVED 2026-08-22 to `packages-and-frontend-mutation-checks.md`.** `§189 CN-1`…`CN-7` mutate
`packages/cli`, `packages/mcp` and `packages/client`. Ids are unchanged.

## §190 — the comments nothing validates

**Six rows; `SM-1`…`SM-3` are RUN and the other three are not** — §65. Ids are `§190 SM-*`; `SM` is
unused across both queue files and every handoff section (grepped before minting it, per the §154
banner — `SC` was the obvious pick and §152 already holds it).

**The three that are run are the mutation-verification of a check written in this slice**, taken
while writing it, which is what makes a green first run evidence rather than a coincidence (§175's
`GranteeGroup`, §188's `NR-6`). Everything else here is deferred.

**`SM-2` is the row this block exists for**, because it did not merely go red as predicted — it
went red *and* exposed that the rule was silent on its own main population. The first
`isPartOfAQualifiedName` asked only whether the next character was a dot, so a sentence ending
"…through `suite_parent_link`." was skipped as though `suite_parent_link` were a table whose column
was being named. **A sentence-final identifier is the most ordinary way a comment names a table**,
so the bare rule would have shipped green, scanning, and blind. It was found by mutating, not by
reading — the mutation was written to prove the rule fires and it proved the opposite first.

**Every row here runs as `cd backend && ./gradlew test --tests 'com.tcms.schema.SchemaCommentTest'`**
on a Linux shape (`~/bin/tcms-remote`), and each takes ~14s: the class boots the context, migrates a
fresh container and reads three catalog queries.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§190 SM-1` | In `V49`, restore `suite_condition`'s comment to "resolved by walking `suite.path` upward" | `everyQualifiedReferenceNamesALiveColumn`, naming `suite.path` under `table suite_condition` | ✅ **RUN 2026-08-13** — red exactly there, the other three rules green. This is the pre-`V49` tree, so the run is also the measurement of the finding: the comment stood for fifteen sections, past `ddl-auto: validate`, `SchemaConventionTest` and 2632 tests, none of which read a sentence. |
| `§190 SM-2` | In the same comment, name a table that does not exist, **at the end of a sentence** — "…through `suite_parent_link`." | `everySchemaIdentifierNamesALiveObject`, naming `suite_parent_link` | ✅ **RUN 2026-08-13** — and it was **green first**, which is the finding above: the full stop was read as a qualifier. Red after the fix, green after the revert. A green build here means the bare rule reports only identifiers that happen not to end a sentence. |
| `§190 SM-3` | Move the catalog read back into `@BeforeAll` | all four rules, on their **corpus pins** — 0 comments, 0 tokens, 0 resolved references | ✅ **RUN 2026-08-13** — the first version of this class was exactly this, and every rule failed on its pin rather than on its subject. Flyway runs when the Spring context is created, which is after the class-level callback: `pg_description` had 5286 rows (all `pg_catalog`'s own) and `public` had **zero tables**. **Without the pins this class would have passed, permanently, checking nothing** — §154's shape, met on the day the check was written. |
| `§190 SM-4` | Delete the `isPartOfAQualifiedName` call from `everySchemaIdentifierNamesALiveObject` | the rule, naming `links_changed` under `column case_doc_link.page_key` | The skip is load-bearing, not tidiness: `documentation.links_changed` is the outbox event type §167 added, and `app.current_company` is the RLS GUC. Without it both come back as offenders and the honest repair is three exemptions — which is how an ignore list grows past the point where anyone reads it (§162). ⬜ NOT RUN |
| `§190 SM-5` | Add a reference to a **dropped table in dotted form** — e.g. `suite_parent_link.label` — to any comment | *nothing* — a **hole**, and stated as one | Neither rule can see it: the qualified rule ignores an unknown left half (it must, or every `plan §6f` and `docs/specs` becomes an offender), and the bare rule skips both halves of a dotted name. A dropped table written **bare** is caught (`SM-2`); written dotted it is invisible. The alternative is demanding that every module, package and event name in every sentence be a table, which is a rule nobody could keep. |
| `§190 SM-6` | Add `"suite_case"` to `NOT_A_SCHEMA_IDENTIFIER` | `everyExemptionStillExemptsSomething` on its **second** direction — an exemption for a name the catalog declares | The map's two directions are different failures. A dead entry excuses nothing today; an entry shadowing a live name excuses nothing today **and starts excusing it on the day the object is dropped** — the exact event the class exists to catch, arriving pre-suppressed. ⬜ NOT RUN |

## §192 — the decline that outlived its folder

**Seven rows, none run** — §65. Ids are `§192 DF-*` (*declines follow the filing*); `DF` is unused
across both queue files and every handoff section (grepped before minting it, per the §154 banner —
`AG`, `CD` and `RL` all collided because two lanes independently reached for the obvious initials).

**`DF-1` is the row this block exists for.** The retention bound is one `if` in
`CaseConditions.reconcileOptouts` sitting immediately above the bound §187 wrote, and the two read
almost identically: *not in `retained` → delete* against *not in `declinable` → keep*. A reader
consolidating them into one guard gets a rule that either deletes a retired entry's decline or keeps
a moved-away one, and both are silent.

**`DF-4` is the row §187 could not write.** Three of that block's five holes (`DC-1`, `DC-3`,
`DC-5`) were holes for one reason — *"needs a decline, then a move, and no test performs one"* — and
this slice's fixtures perform exactly that on both writers. `DC-4` is **still** a hole, and its
reason is unchanged: nothing sends one request that moves a case *and* names a decline… except that
`DF-3` now does, which is what makes `DC-4`'s mutation observable at last. It is recorded here
rather than back-patched into §187's block, per the id rule.

**`DF-7` needs a database, not a build.** It is the only row in either queue whose subject is a
`DO $$` block, and the only way to observe it is to migrate a container that already holds a
violating row — `IntegrationTestBase` migrates a fresh one, so no test in this repository can see
`V50` do anything at all.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§192 DF-1` | In `CaseConditions.reconcileOptouts`, delete the `!retained.contains(...)` arm, leaving §187's `declinable` guard alone — i.e. the code as it stood before this slice | `aMoveDeletesTheDeclinesItInvalidates` **and** `aDeclineDoesNotFollowTheCaseHome` (edit path) **and** `moveDropsTheDeclinesItInvalidates` (bulk) | The whole of §192. The row survives the move, shows on no list, cannot be undone from any screen, and switches a rule back off the day anything files the case back where it came from. It is the state §187 described in its own javadoc as this design's cost, which is why the reversal had to be pinned by three tests on two writers rather than one. | ❗ **RUN 2026-08-22 — RED, 2 of the 3 predicted, and BOTH are the bulk writer.** FEWER. `moveDropsTheDeclinesItInvalidates` falls, plus `and the version it writes hashes the case WITHOUT them`; the **edit path's** two arms — `aMoveDeletesTheDeclinesItInvalidates` and `aDeclineDoesNotFollowTheCaseHome` — **stayed green**, so neither pins the `retained` arm this row says they pin. The reason is a second mechanism upstream: `CaseEdit.normalizeDeclines` applies its own `.filter(retained::contains)` before `reconcileOptouts` is ever reached, so on that path the deletion is over-determined. **The reversal is pinned by one writer, not two.** |
| `§192 DF-2` | In the same method, **swap the two arms** — check `declinable` first and `continue`, then `retained` | `aRetiredEntryKeepsTheDecline` | The half §192 did **not** reverse, and the ordering is what separates them. A retired entry's attachment is not declinable but *is* retained, so testing `declinable` first deletes a decision the client could not restate — the exact failure §187's bound exists for, reintroduced by a reordering that looks like tidying. Two guards whose order is the rule is worth one row. ⬜ NOT RUN |
| `§192 DF-3` | In `CaseEdit.normalizeDeclines`, drop the `.filter(retained::contains)` from the `submitted != null` return, keeping it on the absent branch | `aMoveThatRestatesTheDeclineIsNotARefusal` — on the **response**, not on the row | The hash and the row would disagree for one request shape only: a save that moves the case *and* restates the decline. `reconcileOptouts` still deletes the row (`DF-1`'s bound is independent), so the version records a decline the case does not carry and the next save — changing nothing — is credited with un-declining it. §187's `DC-3` from the other direction, and the reason the intersection is applied before `CaseVersions.decide` rather than after. | ❗ **RUN 2026-08-22 — ZERO. A FINDING: the response does not catch it.** `aMoveThatRestatesTheDeclineIsNotARefusal` did not move. `reconcileOptouts` still deletes the row, so only the **hash** diverges — and nothing in the suite asserts the hash for the one request shape that produces the divergence: a save that moves the case *and* restates the decline. |
| `§192 DF-4` | In `CaseBulkService`, delete the `context.declines().put(row.getId(), surviving)` line, keeping the reconcile | `theMoveVersionHashesTheSurvivingDeclines` | `DC-5`'s trap on the field that now moves. The rows go, the version says they did not, and every one of §95's and §187's warnings about reconstructing an edit from a stale context applies verbatim — the difference being that this is the first bulk command that *changes* one of the reconstructed fields rather than merely carrying it. | ❗ **RUN 2026-08-22 — ZERO, and the mechanism is in the code rather than in the fixture.** The line is **redundant today**: `writeVersion` passes `context.declinedIdsOf(row.getId())` into `CaseEdit.of`, and `normalizeDeclines`' absent branch returns `stored.stream().filter(retained::contains)` — which is `retainedDeclinesOf` recomputed. Removing the `put` changes nothing *while that filter stands*. This is the second implementation of one rule that `retainedDeclinesOf`'s own javadoc warns about, arriving as harmless redundancy — and it becomes load-bearing the moment `DF-3`'s branch is touched. |
| `§192 DF-5` | In `CaseBulkService.loadContext`, resolve `retainedAtTarget` from the case's **current** suite instead of `move.suiteId()` | `moveDropsTheDeclinesItInvalidates` | One suite id, and it inverts the rule: every decline is retained because every decline is imposed by the folder the case is leaving. It reads correctly — "the attachments reaching this case" — which is precisely the sentence the bound must **not** be about. ⬜ NOT RUN |
| `§192 DF-6` | In `CaseUpdateService`, resolve `retained` from `testCase.getSuiteId()` unconditionally, dropping the `request.suiteId()` branch | `aMoveDeletesTheDeclinesItInvalidates` | `DF-5`'s mutation on the other writer, and the one a reader makes while "removing a redundant conditional" — the two suite ids are equal on every save that does not move the case, which is nearly all of them, so the mutation is invisible in the ordinary path and total in the one that matters. ⬜ NOT RUN |
| `§192 DF-7` | Migrate a database that already holds a violating row (decline a case through `PATCH`, move it with `UPDATE test_case SET suite_id = …` behind the app's back, then run `V50`) | *nothing in the build* — a **hole**, and it needs a hand-built database | The sweep is the half a code change cannot reach: the writers hold the invariant from now on, and the rows already stored are `V50`'s alone. It is also the row where an RLS mistake is silent — a `DELETE` with no `app.current_company` set matches **zero rows and reports success** (V44 §3), so the migration would report a clean run over a database it changed nothing in. Run it by hand against a container with two companies' rows and check both are gone. | ⏭ **NOT RUN — manual lane.** Needs a hand-built database holding a violating row before `V50` runs; no code change reaches it. |

## §193 — the decline that ended without a sentence

**Four rows, none run** — §65. Ids are `§193 LD-*` (*lost declines*); `LD` is unused across both
queue files and every handoff section (grepped before minting it, per the §154 banner).

**`LD-1` is the row this block exists for, and it is the defect this slice already hit once.** The
report is read out of the same context the mutation narrows in place, so the *order* of two adjacent
statements is the whole rule — and getting it wrong is not a failure, it is a field that is correct
on every preview and silently empty on every commit. It was measured, red, on the first run of this
slice's gate rather than reasoned about.

**`LD-4` is a hole, and it is structural rather than an omission.** Since §98 a suite is flat, so
`CaseConditions.ancestry` resolves a case's inheritance to **one** suite — its own — and a stored
decline names an attachment of the folder the case is *leaving*. On the bulk path there is therefore
no reachable state in which a decline **survives** a move: `retainedDeclinesOf` is empty for every
selection, and `endsADecline` reduces to *"did this case decline anything"*. The bound is observable
in the direction that deletes (`LD-1`, and `§192 DF-1` on the edit path) and unobservable in the
direction that keeps, and no mutation can close that — it needs a *fixture* the product cannot
currently produce.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§193 LD-1` | In `CaseBulkService.apply`, move the `endsADecline` call **below** the `if (!dryRun) mutate(...)` block | `theMoveReportsTheDeclineItEnds` — and **not** `thePreviewReportsTheDeclineBeforeItEndsIt`, which is the finding | §192 made `MOVE_TO_SUITE` the first bulk command that *writes back* one of the reconstructed fields (`context.declines().put(...)`), so after the mutation the stored list **is** the retained one and the two sizes agree. The preview never mutates, so it keeps answering correctly — a client would see the warning on the dialog and an empty `declinesEnded` on the commit that acted on it. Exactly one of the two test arms sees this, which is why both exist. ⬜ NOT RUN |
| `§193 LD-2` | Delete the `command instanceof BulkCommand.MoveToSuite` guard from `endsADecline` | `anAuthoredNonMoveClaimsNoLoss` | `Context.retainedAtTarget` is empty for the seven commands that are not a move — for the reason *"nobody asked"*, not *"the folder imposes nothing"* — so without the guard every stored decline in the selection narrows to nothing and a `SET_AUTOMATION_STATUS` reports the loss of rows it does not touch. A response that invents a consequence is worse than one that omits it, and this is the shape that does it. ⬜ NOT RUN |
| `§193 LD-3` | In `CaseBulkService.mutate`, restore the local narrowing — `stored.stream().filter(context.retainedAtTarget()::contains).toList()` — in place of `context.retainedDeclinesOf(row.getId())` | *nothing* — a **hole**, and stated as one | The two expressions are the same rule written twice, which is exactly the state §193 removed: they agree today, and the first edit to either makes the response describe an operation the database did not perform. There is no failing state to assert *while they agree*, so the check is that one projection has one implementation — a reading, not a test. Recorded rather than dropped because "a green build proves nothing here" is the honest verdict, not a reason to leave the duplication. |
| `§193 LD-4` | Move a case whose decline the **destination also imposes**, and assert it is absent from `declinesEnded` | *nothing* — a **hole**, and it needs a fixture no schema state can produce, see above | The retention half of the bound has no witness. Deleting the filter is not the mutation for it — that reddens `LD-1` and `moveDropsTheDeclinesItInvalidates` immediately, because `endsADecline` and the write both read this one projection. What has no test is the arm where a decline is **kept**, and while a suite is flat there is no pair of folders sharing an attachment id to build it from. The consequence to hold on to: today `declinesEnded` and *"this case declined something and moved"* are the same set, and only the first of those is the sentence the product means. |

## §200 — the case that could not be created from a plan row

`CaseEdit.validate` gains one parameter, `ProcedureRule`, and it is the only rule in that method
that now differs by path: **REQUIRED** on `PATCH`, **DEFERRED** on `POST` and inside
`CaseEdit.of`'s reconstruction of a stored row. Everything below is a mutation of *which* path gets
which value, because that is the whole surface the slice added — there is no new statement, no new
query and no migration.

**One of these was already measured red rather than reasoned about.** `PR-3` was run during the
slice, before the repair, and it is the reason the reconstruction arm exists at all: with `of`
still passing `REQUIRED`, every stub a plan authored came back from a bulk move as *skipped*, under
a message telling its author to open and save it once — which the update path refuses for the very
same missing steps. Two rules each correct alone, and a dead end reachable by an ordinary click.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§200 PR-1` | In `CaseCreateService`, pass `CaseEdit.ProcedureRule.REQUIRED` instead of `DEFERRED` | `TheProcedureMayBeDeferred`'s four behaviour tests, and `contractRequiredMatchesWhatCreateEnforces` **must stay green** — `steps` is not in `required` either way | The slice reverted, which is the state §97 through §199 shipped: the plan's Test cases tab answers 400 on every Save with a row in it. The contract check staying green is the finding rather than a miss — `required` cannot see this rule, which is why §144 left the conditional four off it and why the behaviour tests are the only observer. ⬜ NOT RUN |
| `§200 PR-2` | In `CaseUpdateService`, pass `DEFERRED` instead of `REQUIRED` | `theEditPathStillRefusesIt`, and `contractRequiredMatchesWhatTheServerEnforces` **stays green** | The deferral stops being a create-path concession and becomes the product's position: the case editor would save a `STRUCTURED` case with no steps, with a success toast and a version bump, and the only thing left refusing it would be the SPA's own Zod schema — i.e. a rule `curl` does not run. The asymmetry is the design; a mutation that erases it must be visible. ⬜ NOT RUN |
| `§200 PR-3` | In `CaseEdit.of`, pass `REQUIRED` instead of `DEFERRED` | `aStubIsMovableLikeAnyOtherCase` | ✅ **RUN, red for the right reason** — the stub came back `skipped` rather than `changed`. A validator that refuses to reconstruct a row the database legally holds makes every authored bulk action skip it, and the skip's own sentence sends the author to a save that will be refused. Nothing about the create path changes, so `TheProcedureMayBeDeferred` stays green throughout: the two arms are independent and only this test sees the second. |
| `§200 PR-4` | Delete the `statesItsProcedure &&` guard from the `GHERKIN` arm alone, leaving the `STRUCTURED` one | `gherkinWithoutAScenarioIsAStubToo` | The two spellings of *procedure* split apart. `StepFormat` makes steps and a scenario alternatives, so half-deferring means the same act — creating a case whose procedure is not written yet — is accepted or refused depending on a format the author may not have chosen deliberately, and `CaseCompleteness` (which reads them as one question) would report the two halves inconsistently. This is the mutation that makes "one rule, two spellings" a claim with a test behind it. ⬜ NOT RUN |
| `§200 PR-5` | In `CaseCompleteness.recomputeIncomplete`, drop the `c.lifecycle_status = 'APPROVED'` term | `theGateIsTheSignal`, `aRetiredCaseIsNotUnfinished` | Pre-existing tests, listed here because §200 is what makes the term expensive rather than merely correct: with a stub now creatable from a plan row, every plan a team writes fills the project with drafts that have no procedure. Without the gate, the dashboard reports each of them as a fault and `INCOMPLETE` becomes a chip on ordinary work in progress. The gate is what made deferring the rule cheap. ⬜ NOT RUN |

## §207 — CRUD stops being authorship-scoped

**Four rows, none run** (§65). Ids are `§207 DQ-*`; `DQ` is unused elsewhere in this file.

`V51` moves three grants and adds no code. So every mutation below is a mutation of **data or of a
gate's premise**, not of a statement — and two of them exist to prove that the *retained but
unpopulated* `OWN` machinery still works, which is the one claim §207 makes that no green test
currently exercises end to end.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§207 DQ-1` | In `V51`, drop statement 1 (leave `case:delete`/`evidence:delete` at `OWN`) | `ownIsCarriedByNoGrant`, and `CaseDeleteEndpointTest.engineerDeletesSomeoneElses` + `anUnattributableCaseIsNoLongerSpecial` | The slice reverted. Two observers should catch it and they catch different things — the seed check reads the grant, the endpoint check drives HTTP — so a green **endpoint** with a red seed would mean the gate stopped consulting the qualifier at all, which is worse than the revert. ⚠️ **RUN 2026-08-23 (wave S) — RED, 5.** MORE than the 3 predicted, and the row's own diagnostic is answered in the reassuring direction: **both** observers went red, so there is no green-endpoint-with-red-seed and the gate does still consult the qualifier. The three named arms fell plus `deleting a peer's case attributes the removal to the DELETER, not the author` and `everyQualifierIsAll` — both reading the same reverted grant. |
| `§207 DQ-2` | In `V51`, drop statement 2 (`plan:delete` not granted) | `testEngineerToTestLeadBoundary`'s positive assertion, and `agentIsTestEngineerMinusFive` (the delta falls back to four) | The tier half reverted. Note the second failure is the interesting one: it proves the Agent delta is genuinely computed from Test Engineer and not transcribed, which is the property §207 leaned on. ⚠️ **RUN 2026-08-23 (wave S) — RED, 3 — and the INTERESTING half did not happen.** `testEngineerToTestLeadBoundary` fell twice (the seed's copy and the evaluator's) plus `every cell of spec §A3 is the grant the database holds`. But `agentIsTestEngineerMinusFive` — now `agentIsTestEngineerMinusNothingItCouldUse` — stayed **green**, and the reason matters: `V52` replaced `V40`'s enumeration with a **derivation**, so Agent follows Test Engineer automatically and the delta is empty whatever Test Engineer holds. **The property this row was written to prove is no longer falsifiable by this mutation** — which is `§210 DQ-3`'s registered gap arriving from the other side, and the two together are the case for it. |
| `§207 DQ-3` | **The retained-machinery proof.** Leave `V51` intact and add a follow-up `UPDATE` setting Test Engineer's `case:delete` back to `OWN` — i.e. simulate the future grant the mechanism is kept for | `ownIsCarriedByNoGrant`, **and `CaseDeleteEndpointTest.engineerDeletesSomeoneElses` must go red with a 403 whose `detail` contains "only delete test cases you created"** | ⚠️ **The row that matters most.** §207's whole justification for keeping `PermissionQualifier.OWN`, `canDoOnAuthored`'s arm and `CaseDeleteService`'s `NotTheAuthor` outcome is that a future `OWN` grant must work *without code changes*. Nothing currently proves that from seeded data — the unit tests mock the qualifier. A green endpoint here means the machinery has silently rotted and the retention argument is false. ✅ **RUN 2026-08-22 — RED, 5. THE MACHINERY WORKS, and the retention argument holds.** A follow-up `UPDATE` setting Test Engineer's `case:delete` back to `OWN` re-engages the authorship gate through **three** `CaseDeleteEndpointTest` arms (the colleague's case, the authorless case, the attribution one) **and both** seed assertions (`ownIsCarriedByNoGrant` and `every grant is unqualified`). The engineer's delete flips 204 → 403 at `CaseDeleteEndpointTest.java:371`. **One half is unpinned**: the curated sentence the row asks about is still wired at `CaseController.java:700`, but no test asserts the *body* — only the status flip is observed. |
| `§207 DQ-4` | In `CaseDeleteService.apply`, delete the `if (!authorship.mayActOn(...))` block | **Nothing** — every test stays green | ✅ **Expected green, and that is the finding, not a pass.** With no `OWN` grant the check is unreachable, so the suite cannot tell the retained arm from a deleted one. Run `DQ-3` first and this row becomes meaningful; run it alone and it measures the gap. Recorded because a reviewer who deletes this block for being dead code will see a green build and be right about the evidence. |

## §208 — the gate that moved, and the ordinal that went with it

**Four rows, none run** (§65). Ids are `§208 DM-*`; `DM` is unused elsewhere in this file — grep
before minting a prefix (`§154`'s banner).

§208 writes no migration and moves no grant. The whole slice is four lines of `permissionFor` plus a
deleted `rank`, so every mutation below is a mutation of **one boolean expression**, and the value of
the block is that the rule now has a total observer (`LifecycleLadderTest.theWholeTable`) where before
it had five endpoint assertions over sixteen pairs.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§208 DM-1` | In `LifecycleLadder.permissionFor`, restore the old first line: `if (requested == APPROVED \|\| requested == DEPRECATED) return keys.approve();` | `theWholeTable`, `Edit.retiringAnUnapprovedArtifact`, `CaseBulkEndpointTest.engineerDeprecatesADraft`, `SuiteWriteEndpointTest.engineerDeprecatesADraft` | The slice reverted. Four observers at three altitudes — the total table, the unit arm, and both endpoints — and they should all go red. A green *endpoint* with a red table would mean the endpoint test stopped driving the persona it names. |
| `§208 DM-2` | Change the second line to `return current == DEPRECATED ? keys.approve() : keys.edit();` — i.e. gate the way back and free the way in | `theWholeTable`, `Edit.revivingARetiredArtifact`, `SuiteWriteEndpointTest.engineerRevivesARetiredSuite`, and `Approve.leavingAnApproval` | ⚠️ **The row that matters most.** This is the one-way door the slice was written to avoid, and it is the mutation a reviewer is most likely to *propose* — "surely reviving a retired thing needs the higher key". It must be reddened by the revive assertions AND by `leavingAnApproval`, because the same edit silently frees `APPROVED → DRAFT`, which is §75's whole finding. If only the revive rows go red, the approval half has no independent observer and §75 is one refactor from being undone again. ✅ **RUN 2026-08-22 — RED, 6.** All four predicted fell **plus two more suite arms** (`an engineer cannot deprecate an APPROVED suite`, `an engineer cannot take a lead's approval back by asking for DRAFT`), so the approval half **does** have independent observers and §75 is not one refactor from being undone. Note the reds come from `LifecycleLadderTest` and `SuiteWriteEndpointTest`: `ArtifactEndpointTest` contributed none, because every artifact arm the row names lives in the ladder's own unit test. |
| `§208 DM-3` | Delete the `requested == APPROVED` early return entirely, keeping only `current == APPROVED ? approve : edit` | `theWholeTable`, `Approve.grantingTheSignOff`, `CaseBulkEndpointTest.engineerMayNotApprove`, `SuiteWriteEndpointTest.engineerCannotApprove` | The gate §6g calls *"the one gate"* removed — a Test Engineer approves their own draft. The interesting half is `grantingTheSignOff`, which loops over all four starting states: the two endpoint tests each drive **one** edge into `APPROVED`, so a rule that granted approval from `DRAFT` and `DEPRECATED` but not `IN_REVIEW` would pass both of them. |
| `§208 DM-4` | In `CaseBulkService.leadOnly`, move `DEPRECATED` back to its own arm returning `"Only a test lead can deprecate cases."` | `CaseBulkEndpointTest.engineerCannotDeprecateAnApprovedCase` | The refusal sentence describing the wrong thing. Not a permission defect — the gate still refuses — which is exactly why it needs its own row: the string is the only part of this that a caller reads, and after §208 the only way to reach that arm is with an approved row, where "you cannot deprecate" is true of nothing and hides the one fact the caller cannot see from a selection of four hundred. §149's shape, one sentence over. |

## §209 — the acts that write the two states, and the rule that gates them both

**Five rows, none run** (§65). Ids are `§209 AM-*`; `AM` is unused elsewhere in this file — grep
before minting a prefix (`§154`'s banner).

§209 writes no migration and moves no grant. It adds two acts over an enum that had one writer, and
the rule worth mutating is the *same-scope* check — it is the only thing standing between the create's
gate and a second row that nothing else gates.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§209 AM-1` | In `ArtifactWriteService.validateSupersedes`, delete the scope/plan comparison | `Supersession.theEngineerCannotRetireWhatTheyMayNotEdit` and `theScopeRuleIsAboutThePlanToo` | ⚠️ **The row that matters.** A Test Engineer registers a plan-scope note whose `supersedes` names the project's test strategy, and retires a document plan §6n gives them read-only — with a `201` that looks entirely correct. The create's own gate passed; it passed for the *other* row. ✅ **RUN 2026-08-22 — RED, exactly the 2 predicted.** MATCH. |
| `§209 AM-2` | In `setStatus`, change `rowScope.mayActOn(artifact.getScope() == PLAN)` to `mayActOn(true)` | `Circulation.theQualifierIsReadAgainstTheRow` | The qualifier read against nothing. One assertion, and that is the point: the mirror half of the same test (`cycleDoc` → 200) stays green either way, so a suite that only proved the engineer *can* archive would report success on a build where they can archive anything. |
| `§209 AM-3` | In `Artifact.supersededBy`, delete the `this.status = SUPERSEDED;` line, keeping the pointer | `ArtifactCirculationTest.supersessionWritesBoth`, `Supersession.theReplacementFlipsTheOldRow`, `Circulation.supersededCannotBeLeft` | The status and the pointer disagreeing — a register that names a replacement while calling the row current. Three observers at three altitudes, which is deliberate: the unit test is the one that names the invariant, the endpoint tests are the ones that prove it survives a round trip. ✅ **RUN 2026-08-22 — RED, 7.** All three predicted fell, plus four more — every arm that reads `SUPERSEDED` status. The three-altitude design working, rather than coupling. |
| `§209 AM-4` | In `parseCirculation`, replace the two comparisons with a lookup over `ArtifactStatus.values()` | `ArtifactCirculationTest.supersededIsNotACirculationValue`, `Circulation.supersededCannotBeSent` | **The implementation somebody will propose**, and it is why the unit test asserts a correctly-spelled member is refused rather than only that a typo is. A caller could then assert a replacement that does not exist. |
| `§209 AM-5` | In `applySupersession`, delete the `artifacts.flush()` | **Possibly nothing** | ⚠️ **Expected green, and recorded as the gap rather than as a pass.** Hibernate orders inserts before updates within one flush, so the foreign key is satisfied either way today. The call is explicit because the guarantee is the ORM's rather than the code's; if this row goes green, the file's own comment is the only thing holding it. Same shape as `§207 DQ-4`. |
| `§210 DQ-1` | In `V52`, delete statement 2 (the `artifact:edit` de-qualification) | `SeedDataTest.artifactEditIsUnqualifiedEverywhere`, `everyQualifierIsAll` | The two data assertions §210 inverted rather than deleted. **The interesting half is what does NOT go red:** no gate test fails, because `writeGrantedByRowScope` still runs and simply narrows again — the widening was silent in both directions, which is the whole reason the assertion lives in the seed data and not at the endpoint. | ⚠️ **RUN 2026-08-23 (wave S) — RED, 3.** MORE than the 2 predicted (`ownIsCarriedByNoGrant` falls too, reading the same column) — **and the interesting half is confirmed exactly as written**: `ArtifactEndpointTest` was deliberately put in scope and contributed **zero reds**. `writeGrantedByRowScope` still runs and simply narrows again, so the re-qualification is invisible at the endpoint in both directions. That is precisely why the assertion lives in the seed data. |
| `§210 DQ-2` | In `V52`, delete statement 3's `AND te.permission_key <> 'company:read'` | `SeedDataTest.companyReadIsAlwaysExplicit` | Reproduces the find. A PROJECT-scope `company:read` on a token-carried role is unanswerable by `evaluateCompanyWide` — a company surface on the roles screen that is permanently false at the gate. This is the §43 rule that reddened on `V52`'s first run; re-running it proves the check still discriminates rather than having been satisfied by the migration edit. | ⚠️ **RUN 2026-08-23 (wave S) — RED, 2.** `companyReadIsAlwaysExplicit` falls as predicted, and `agentIsTestEngineerMinusNothingItCouldUse` falls beside it — the delta check firing in the *other* direction, on an extra key reaching Agent. The §43 rule still discriminates; it was not satisfied by the migration edit. |
| `§210 DQ-3` | In `V52` statement 3, replace the derived `SELECT … FROM role_permission te` with an enumerated `VALUES` list of the same 32 keys | **Expected green** | ⚠️ **Recorded as the gap, not as a pass.** The enumeration and the derivation produce identical data *today*, so nothing can distinguish them — which is exactly the drift `V40`'s enumeration allowed and `V51` walked into. Nothing proves the *derivation* is what keeps Agent equal to Test Engineer until a migration widens the contributor role and only the derived form follows. The real check is the NEXT permission migration, and `agentIsTestEngineerMinusNothingItCouldUse` is what will fail if it is written the enumerated way. |  ✅ **RUN 2026-08-23 (wave S) — GREEN.** ZERO-AS-PREDICTED: the derivation replaced by an enumerated `VALUES` list of the same 32 keys reddens nothing, because the two produce identical data today. **The gap is now measured from both sides** — `§207 DQ-2` in the same batch shows the mirror image, a Test Engineer key removed and the Agent delta staying empty because the derivation follows. Neither mutation can tell an enumeration from a derivation, and the real check is still the next permission migration. |
| `§210 DQ-4` | Grant `case:approve` to Test Engineer in a scratch migration | `SeedDataTest.testEngineerToTestLeadBoundary`, `agentIsTestEngineerMinusNothingItCouldUse` | Proves the closed seven-key set is closed in the direction that matters — a key *leaving* the lead's exclusive list. Both must fail, and the second is the one worth watching: it means the tier line is load-bearing for every outstanding **agent credential** since §210, not only for people. | ⚠️ **RUN 2026-08-23 (wave S) — RED, 6.** MORE than the 2 predicted, and both named arms are among them. The extras are coherent: the evaluator's two persona tests, the team-grant resolution, and `every cell of spec §A3 is the grant the database holds`. **The second-order point of the row is confirmed** — `agentIsTestEngineerMinusNothingItCouldUse` reddens, because the grant lands *after* `V52`'s derivation has run and Agent does not follow it, so the closed seven-key set is load-bearing for every outstanding agent credential and not only for people. |
| `§210 DQ-5` | Swap a delete route's gate constant onto its neighbour: `suite:delete`→`suite:manage` in `SuiteWriteService`, `artifact:delete`→`artifact:edit` in `ArtifactWriteService`, `doc:publish`→`doc:edit` in `DocPageWriteService`, `run:close`→`run:execute` and `result:amend`→`result:submit` in the execution services | **Expected GREEN — every one of them.** | ⚠️ **The check §210 COST US, recorded as a gap rather than as a pass.** Each of those pairs was previously told apart by the Test Engineer persona holding one key and not the other, and each swap reddened exactly one test. `V52` gives the contributor both halves of all five pairs, and **no seeded role now holds one without the other**, so the discrimination is gone from the whole suite. The keys are still separate in the catalogue and the routes still ask the right ones — that is now upheld by review and by the service's constant, not by a test. Restoring it needs a persona built from an **ad-hoc grant** rather than a built-in role, which the suite has no helper for. This is the single largest thing §210 traded away.  ✅ **RUN 2026-08-23 (wave S) — GREEN, all five pairs in one measurement.** ZERO-AS-PREDICTED, and this is now measured rather than argued: `suite:delete`→`suite:manage`, `artifact:delete`→`artifact:edit`, `doc:publish`→`doc:edit`, `run:close`→`run:execute` and `result:amend`→`result:submit` applied together, scoped over `SuiteWriteEndpointTest`, `DocPageEndpointTest`, `ExecutionEndpointTest`, `ArtifactEndpointTest` and `ArtifactFileEndpointTest` — **not one red.** The gap this row registers is real, and wave S found four more sightings of it (`DA-2`, `FA-11`, `§240 AR-M4`, `§242 LG-M5`). The closure is the catalogue-derived convention test, not five more personas. |
| `§210 DQ-6` | In `PermissionEvaluatorTest.theQualifierIsOverriddenToo`, delete the `qualify(...)` call and its `finally` | `theQualifierIsOverriddenToo` | The replace-don't-union rule for qualifiers — one of the two rules the scope model rests on. It used to be driven by seeded data (`artifact:edit` was `PLAN_SCOPE` for Test Engineer) and `V52` removed that, so the test now **writes the qualifier itself**. Without the fixture both branches answer `ALL` and the test passes while proving nothing, which is exactly the shape it is guarding against. Also confirm the `finally` restores it: `role_permission` is instance-wide with no `company_id`, so a leak narrows a grant for every class that runs after. | ⬜ NOT RUN |

## §217 — the schema an OIDC callback can read, and the resolver that has no caller yet

**Six rows, none run** (§65). Ids are `§217 FD-*`; `FD` is unused elsewhere in this file — grep
before minting a prefix (`§154`'s banner).

§217 phase 1 is `V53` plus two lines of `SchemaConventionTest`. It adds no Java, so there is no
behaviour to mutate — every row below mutates **DDL**, and the block's value is that three of the six
are expected **green**: the resolver function has no caller until Task 6, so the most important thing
in the migration is currently observed by nothing at all.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§217 FD-1` | In `V53`, change `tcms_resolve_identity_provider` from `SECURITY INVOKER` back to `SECURITY DEFINER` with the plain `LANGUAGE sql` body the plan specified | **Expected GREEN — and that is the finding.** | ⚠️ **The row that matters most.** This is `V7`'s defect re-authored verbatim: `identity_provider` carries FORCE RLS, FORCE subjects the table owner to its own policies, so a definer function owned by `tcms_owner` matches an unset `app.current_company` and returns **zero rows** — in production only, presenting as "that provider does not exist". Nothing in the suite can see it today because nothing calls the function. It becomes observable the moment Task 6 wires `SsoLoginService`, and the check that would catch it must run as `tcms_app` with no context open — the shape `RlsIsolationTest` already has. **Until that test exists, this migration's central decision rests on the V53 header and on review.** ❗ **RUN 2026-08-22 — RED, 8. The row predicted GREEN and it is now STALE, in the good direction.** §217's own later phases built `SsoFlowIntegrationTest` (`FD-10`…`FD-15`, run 2026-08-15), and **that class does observe `V7`'s defect re-authored**: `FORCE ROW LEVEL SECURITY` subjects the definer to its own policies, so the plain-SQL `SECURITY DEFINER` body resolves no provider at all and the whole flow dies — the takeover-vector arm, the disabled-mid-flow refusal, `FD-10`, `FD-11` and `FD-14` among them. **Record which class does NOT see it**: `RlsIsolationTest`, this row's manifest scope, contributed **zero** reds. |
| `§217 FD-2` | Delete `sso_login_attempt` from `INSTANCE_LEVEL_TABLES` in `SchemaConventionTest` | `everyCompanyOwnedTableForcesRls` | Proves the exception-list entry is load-bearing rather than decorative — the table would be demanded to carry RLS, which is exactly the demand that makes an OIDC callback unresolvable. The `company_id NOT NULL` arm stays green either way, because the column *is* there: it is a target, not a discriminator.  ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `everyCompanyOwnedTableForcesRls`.** MATCH. The exception-list entry is load-bearing rather than decorative. Read beside `§217 FD-3` in the same batch, which shows the *other* half of the list is not: the entry decides whether RLS is demanded, and nothing at all decides the table's shape. |
| `§217 FD-3` | Make `sso_login_attempt.company_id` nullable | **Expected GREEN.** | ⚠️ **Recorded as the gap, not as a pass.** Everything on the exception list is skipped by every arm of `SchemaConventionTest`, so the ERD's "**non-nullable** `company_id` as a *target*" is a sentence in a markdown table and nothing more. Thirteen tables are now exempt and none of them has its shape pinned anywhere. The same hole covers `api_token_lookup` and `membership_invitation`, so this is not new with `V53` — it is the first time it has been written down.  ✅ **RUN 2026-08-23 (wave S) — GREEN.** ZERO-AS-PREDICTED, in both `SchemaConventionTest` and `SsoFlowIntegrationTest`. **The gap is now measured**: making `sso_login_attempt.company_id` nullable reddens nothing, because every arm of `SchemaConventionTest` skips the exception list, and no other check reads the column's nullability. The ERD's *"non-nullable `company_id` as a target"* really is a sentence in a markdown table. Thirteen exempt tables, none with its shape pinned. |
| `§217 FD-4` | Drop `FORCE` from `user_identity`, keeping `ENABLE` | `everyCompanyOwnedTableForcesRls` | The first of the two RLS traps, on the table that holds the account-takeover key. Under plain `ENABLE` the owner bypasses the policy, so one company's IdP could resolve a subject to another company's user — the single thing §6r's per-company issuer trust exists to stop. ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `everyCompanyOwnedTableForcesRls`.** MATCH. The RLS trap on the table holding the account-takeover key is pinned by exactly one assertion. |
| `§217 FD-5` | Remove the `nullif(…, '')` from `company_email_domain`'s policy | `everyPolicyHasTheExactIsolationPredicate` | `V4`'s trap. Worth keeping a row on because the failure mode is not a leak but an exception — `invalid input syntax for type uuid` on every *warm pooled* connection and none of the cold ones, which reads as flakiness rather than as a policy defect. ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `everyPolicyHasTheExactIsolationPredicate`.** MATCH. `V4`'s trap is caught by the text comparison, which is the only thing that could catch it — the failure it prevents is a warm-connection-only exception, not a leak. |
| `§217 FD-6` | Change `company_email_domain.domain` from `CITEXT` back to the plan's `TEXT` | **Expected GREEN.** | ⚠️ **Recorded as the gap.** On `TEXT`, `acme.test` and `ACME.test` both satisfy `uq_company_email_domain` and provider discovery answers differently depending on which spelling a lookup normalised to. `app_user.email` is `CITEXT` for this reason one label over. Nothing tests it until discovery has a caller (Task 6), and the case-folding would then live in Java where the *constraint* cannot see it. |

## §218 phase 2 — the notification-channel REST surface, and the gate on its reads

**~~Six rows~~ ten rows, none run** (§65). Ids are `§218 NC-*`; `NC` is unused elsewhere in this file
— grep before minting a prefix (`§154`'s banner).

~~**§218 phase 1 added no rows to this file, and that is a gap this block does not close.** The spine
— `V54`, the cipher, the redaction, the three adapters, the two producers and the payload pins — is
phase 4's to record, and inventing rows for code this phase did not write would be worse than
leaving the omission visible.~~ → **closed by phase 4: `NC-7`…`NC-10` below are the spine's.** That
paragraph was right to leave the omission visible and right about who owed it; what it did not say is
how lopsided the block was — **every one of `NC-1`…`NC-6` is about the REST edge**, and the four
things in this slice whose failure is silent (a duplicate message, a silenced channel, a reopened run
announced as finished, a disabled channel that still posts) all live behind it.

What this phase's rows are about is one decision and one omission: **the reads cost
`integration:manage`**, which phase 1's service javadoc said the opposite of; and **there is no
`Idempotency-Key`** behind the create, which §205 makes the expensive kind of omission to get wrong
in the other direction.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§218 NC-1` | In `NotificationChannelService.list`, `find` and `history`, swap `projects.readGranted(userId, projectId, MANAGE, …)` back to `projects.readVisible(userId, projectId, …)` (and unwrap the `GateResult` at the three call sites) | `NotificationChannelTest.Gate.anEngineerIsRefused` | ⚠️ **The row that matters.** This is phase 1's shape restored verbatim, and it is the one a later reader is most likely to re-author, because the class javadoc argued *for* it before this phase struck it through. A green build would mean every reader of a project can read `secretHint` — the tail of a live credential — and every line a remote endpoint said about a failed POST. ✅ **RUN 2026-08-22 — RED, 2.** The predicted `anEngineerIsRefused` fell. ⚠️ **The second red is honestly attributable to the mutation's harness, not to the gate**: `readVisible` returns `Optional`, and the adapter written to keep the signature collapses a null `Allowed` into `Unreachable`, which is what moves `a channel in a sibling project is the same 404`. Stated rather than counted as coverage. |
| `§218 NC-2` | In `NotificationChannelController.read`, change the `GateResult.Denied` arm to `ResponseEntity.notFound().build()` | **Expected GREEN — and that is the finding.** | ⚠️ **Recorded as the gap, not as a pass.** Nothing in this suite drives these endpoints over HTTP; the gate is asserted at the service and the status mapping is asserted by review. The consequence is not a leak but a lie in the other direction: an administrator without the key would be told the project has no channels rather than that they may not look, which is the exact reading `GateResult`'s own header says 403 exists to prevent. The check that would catch it is a `MockMvc` pass this slice does not have. |
| `§218 NC-3` | In `NotificationChannelService.update`, delete the `expectedVersion != null && …` comparison, keeping the `@Version` backstop | `NotificationChannelTest.Gate.aStaleEditIsRefused` | The explicit precondition. The backstop alone does **not** cover it: `lock_version` only moves when a write commits, so two sequential edits from one stale read both succeed and the second silently discards the first administrator's recipient list. `anAbsentPreconditionSaves` must stay green — it is the mirror that proves the check discriminates rather than refusing everything. |
| `§218 NC-4` | In `NotificationChannelService.update`, change `if (input.secret() != null && !input.secret().isBlank())` to an unconditional `channel.replaceSecret(…)` | `NotificationChannelTest.anAbsentSecretMeansUnchanged` | The null-means-unchanged inversion, which is the single most consequential line in the class. A green build would mean a rename blanks the credential — a row that still reads as configured, delivering nothing, failing hours later at a time unconnected to the edit that caused it. |
| `§218 NC-5` | Add a `secret` property to the `NotificationChannel` schema in `openapi.yaml` and a matching `secret` component to `NotificationChannelView` | `ContractConventionTest.pairedSchemasAndRecordsNameTheSameMembers` ✅ **RUN 2026-08-15 — now RED.** `ContractConventionTest.pairedSchemasAndRecordsNameTheSameMembers` is still not the observer; `packages/client/src/test/responseCredentials.test.ts` (§219) is, and it catches `NotificationChannel.secret` by the same walk that catches `IdentityProvider.clientSecret` | ~~⚠️ **Expected GREEN, and it is the most uncomfortable row here.**~~ → **closed by §219.** The pairing rule compares the two sides to *each other*, so adding the member to both satisfies it. Nothing in this repository asserts that a credential is absent from the wire; what stands in its place is `NotificationChannelView`'s header, `NotificationChannel`'s schema description, and the fact that the entity has no plaintext accessor at all — three prose statements and one type-level obstacle. A rule that read the *response schemas* for a credential-shaped property is the check this slice did not write. |
| `§218 NC-6` | Add `parameters: [{ $ref: '#/components/parameters/IdempotencyKey' }]` to `createNotificationChannel` | **Expected GREEN.** | ⚠️ **The §205 shape, recorded as a gap.** That slice found `tcms_report.generate` sending an `Idempotency-Key` to the one create of eleven that takes none, and the check it built — `writeConformance.test.ts` — reads the **packages** write registry, which has no notification-channel arm. So a header declared here and honoured by nothing would be observed by no gate in either stack. It is a 202-shaped defect: a client that trusted the header replays a timed-out create and gets a second channel that delivers every notice twice. |
| `§218 NC-7` | In `NotificationLedger.beginAttempt`, change the `existing.isPresent()` arm to `return true` — keeping the row, dropping only the `getStatus() != SENT` refusal | `NotificationDeliveryTest.theDeliveryIsKeyedOnTheOccurrence` | **The idempotency gate, and it is what makes at-least-once delivery legal here.** An outbox row whose handler succeeded and then lost the settle is re-delivered on the next tick; a green build would mean that is a second message in somebody's release channel, and the retry ladder makes it up to **eight**. The unique index on `(company_id, channel_id, event_id)` is not the observer — it stops a second *row*, not a second POST, and the POST is the part a person sees. ❗ **RUN 2026-08-22 — ZERO. A FINDING, and the row's own warning was understated.** `theDeliveryIsKeyedOnTheOccurrence` never reaches the branch: it dispatches twice and asserts one row with `attempt = 2` — so its first delivery does **not** end `SENT`, and the `getStatus() != SENT` refusal is never exercised by anything. Nothing in the suite stops the second POST. **What would close it:** a fixture whose first dispatch *succeeds*, then make the outbox row due again and assert the webhook double received exactly one request. |
| `§218 NC-8` | In `NotificationSubscription.wants`, change `failures == null \|\| failures >= minFailures` to `failures != null && failures >= minFailures` | `NotificationRenderingTest.anEventWithoutCountsIsNotSilenced`. `NotificationDeliveryTest.theThresholdFilters` **must stay green** — it is the mirror that proves the predicate still discriminates rather than matching everything | ⚠️ **Read the direction before running it.** The mutation does not open a channel, it **silences** one: every event type with no notion of failures — today `work.item_assigned`, tomorrow anything added — matches nothing, forever, on a subscription somebody ticked. A green build would mean a channel configured through the screen, showing as subscribed, that never once fires. The observer is a **unit** assertion on `wants(null)`, not a fan-out: `work.item_assigned` reaches no end-to-end test in this suite (see `§218 NT-8`), so the pair above is the whole of what watches this line. |
| `§218 NC-9` | In `RunStatusService.move`, delete the `if (target == RunStatus.COMPLETED)` guard around `announce(projectId, view)`, so every status move records the occurrence | `NotificationDeliveryTest.onlyCompletionIsAnOccurrence` | Aborting is not completing and a reopen is not an ending. A green build would mean *"TR-14 finished"* posted to a release channel when somebody reopened it to fix a result — an announcement that is not merely noise but **false**, and one no recipient can distinguish from the real thing. The one-event-per-status-move design pushes that discrimination onto every subscriber and one of them gets it wrong. |
| `§218 NC-10` | In `NotificationChannelRepository.findSubscribed`, delete the `AND c.enabled = TRUE` line from the `@Query` | `NotificationDeliveryTest.aDisabledChannelIsSilent` | The off switch. `enabled` is the only control an administrator has that is **not** a delete — it is what you reach for when a webhook is misbehaving at 2am and you want the history kept. A green build would mean the switch renders, saves, reads back as off, and changes nothing at all. |

## §217 phases 3–6 — the federation flow, and the four things a green suite is not watching

**Nine rows, none run** (§65). Ids continue `§217 FD-*` from phase 1, so `FD-7` follows `FD-6`.

Phase 1's block was DDL only. These are Java, and the split is the point: three rows have a real
observer in the suite, and **six are expected green**. The six are one shape — *this slice ships an
HTTP flow that no test drives over HTTP, against an issuer no test stands up.* `FD-1` said the
resolver would become observable "the moment Task 6 wires `SsoLoginService`"; it is wired now, and
it is still observed by nothing, because the wiring has no integration test either. That is the
honest state and it is what the browser/Keycloak pass in Task 8 exists to change.

| Row | Mutate | Must redden | What a green build would mean |
|---|---|---|---|
| `§217 FD-7` | In `FederatedIdentityService.resolve`, move the `findByProviderIdAndSubject` branch *below* the email branch | `FederatedIdentityServiceTest.anExistingIdentityWinsBeforeAnyLinkingPolicyIsConsulted` | The ordering that is the whole of §6r's identity rule. A green build would mean a person whose corporate address changed is forked into a second account, losing their memberships and their authorship, with no error anywhere. |
| `§217 FD-8` | In the same method, drop `&& claims.emailVerified()` from the auto-link condition | `FederatedIdentityServiceTest.anUnverifiedEmailNeverLinksEvenWhenAutoLinkIsOn` | The account-takeover vector. An issuer that lets anyone assert an unverified `email` claim would reach any existing account at that address. |
| `§217 FD-9` | In `OidcIdTokenVerifier.audienceIs`, change the arm to `audience == null \|\| audience.contains(clientId)` | `OidcIdTokenVerifierTest.aCognitoAccessTokenIsRejectedBecauseItCarriesNoAudience` | "No audience" read as "any audience" — the standard Cognito trap, and the reason that test exists rather than only the wrong-client one. |
| `§217 FD-10` | In `SsoLoginService.callback`, move the `attempts.consume(...)` call to *after* the token exchange | ✅ **RUN 2026-08-15 — now RED.** `SsoFlowIntegrationTest.aReplayedStateIsRefused` | ~~⚠️ **Recorded as the gap.** The single-use guarantee has no test at all: nothing drives two concurrent callbacks with one `state`, and nothing drives one callback twice. The conditional `UPDATE` is the mechanism and review is its only observer. The check that would catch it is an integration test with a stubbed issuer, which is what Task 8's Keycloak buys. |~~ → **§221 built the observer.** The conditional UPDATE now commits before the exchange under a test that presents one `state` twice against a live stub issuer; moving `consume` after the exchange lets the second callback through.
| `§217 FD-11` | In `SsoLoginService.complete`, replace `verifier.verify(...)` with a decode that skips the nonce comparison | ✅ **RUN 2026-08-15 — now RED.** `SsoFlowIntegrationTest.theNonceFromTheAttemptIsTheOneRequiredOfTheToken` | ~~⚠️ **Recorded as the gap, and it is the worst of the six.** `OidcIdTokenVerifierTest` proves the *verifier* refuses a wrong nonce; nothing proves the *flow* passes the attempt's nonce to it. Without that binding an `id_token` obtained from any other sign-in at the same issuer completes somebody else's callback. Two green tests either side of an unobserved wire. |~~ → **§221 built the observer, and this was the sharpest of the four.** `OidcIdTokenVerifierTest` proved the verifier refuses a wrong nonce and nothing proved the service handed it the attempt's own — two green tests either side of nothing. The stub issuer echoes a nonce the test chooses, so a decode that skips the comparison now signs somebody in.
| `§217 FD-12` | In `SsoLoginService.complete`, change `providers.findByIdAndEnabledTrue` to `providers.findById` | ✅ **RUN 2026-08-15 — now RED.** `SsoFlowIntegrationTest.aProviderDisabledMidFlowRefuses` | ~~⚠️ Recorded as the gap. A provider disabled between the redirect and the callback would still complete a sign-in. The lifecycle check is deliberately the RLS-protected read *inside* the opened context — the half `tcms_resolve_identity_provider` returns rather than filters — and nothing exercises it. |~~ → **§221 built the observer.** The provider is disabled between the authorize redirect and the callback, so `findById` in place of `findByIdAndEnabledTrue` completes a sign-in through a route that is off.
| `§217 FD-13` | In `SsoLoginService.landingUrl`, change the `#` to `?` | ✅ **RUN 2026-08-15 — now RED.** `SsoFlowIntegrationTest.theLandingUrlPutsTheSessionInTheFragment` | ~~⚠️ Recorded as the gap. The session would move from the URL fragment into the query string, and therefore into this instance's access log, the reverse proxy's, and the `Referer` header of the first outbound link the SPA renders. Nothing in either stack asserts which separator is used. |~~ → **§221: partly closed, and the queue was too pessimistic about it.** The plan expected this row to stay a person's job entirely. The *separator* is observable without a browser after all — the test asserts `#token=` is present and that nothing before the fragment carries the session, so `?` in place of `#` reddens. What is still browser-only is `§217 SO-4`, the stronger claim that the fragment reaches no request line, which needs a network log and stays a person's.
| `§217 FD-14` | In `IdentityProviderService.validate`, drop the `issuerUri.startsWith("https://")` check | ✅ **RUN 2026-08-15 — now RED, twice over.** `IdentityProviderEndpointTest.aPlainHttpIssuerIsRefused` and, for the second gate, `OidcDiscoveryTest.aPlainHttpIssuerIsRefused` | ~~⚠️ Recorded as the gap. `IdentityProviderServiceTest` does not exist — the CRUD is gated, validated and audited, and none of it is driven. Over `http` the discovery document, and therefore the `jwks_uri` every signature is checked against, is nominated by anyone on the path. |~~ → **§221 built both observers, and found a gate this queue did not know about.** There are TWO https refusals, not one: the registration validator this row names, and `OidcDiscovery.forIssuer` at RUNTIME on every authorize and callback. `OidcDiscovery` had no test of any kind; it does now.
| `§217 FD-15` | Add `clientSecret` to `IdentityProviderView` and to the `IdentityProvider` schema | ✅ **RUN 2026-08-15 — now RED**, naming `IdentityProvider.clientSecret` | ~~⚠️ **`§218 NC-5` a second time, and that repetition is the finding.**~~ → **§219 built it.** `packages/client/src/test/responseCredentials.test.ts` is the observer: it walks every 2xx response body, follows `$ref` transitively, and fails on a credential-shaped `type: string` property that is not a recorded decision. Driven with the mutation this row describes and confirmed RED before the revert. The third slice built the rule rather than recording it. |

**Not mutations, but owed to the same pass** — the account-takeover case has no unit-testable form,
because what stops it is RLS rather than a branch: company A's provider asserting a `sub` that
exists in company B must not resolve to B's user. `FederatedIdentityServiceTest`'s header says why
it is absent there. It is the single most important row in the browser/Keycloak queue.

## §229 phase 2 — the register's first edit path (A6)

Scope for every row: `./gradlew test --tests 'com.tcms.documentation.ArtifactEndpointTest'`.

| id | Mutation | Expected red | Why this matters |
|---|---|---|---|
| `§229 PM-14` | In `ArtifactWriteService.edit`, use `projects.writeGranted` instead of `writeGrantedByRowScope` | `Correcting.aPlanScopedGrantCannotCorrectTheProjects` | **The only test in the suite that can see this**, and it has to hand-qualify the grant to do it: `V52` emptied `role_permission.qualifier`, so no seeded persona distinguishes the two gates any more. Roles spec §A5 keeps the mechanism against a future migration — and a gate nothing exercises is one that could be deleted with every other test still green, failing **open** the day the qualifier comes back. |
| `§229 PM-15` | In `ArtifactWriteService.edit`, read the scope from the request instead of the row (add a `scope` field to `ArtifactEditInput` and gate on it) | `Correcting.aPlanScopedGrantCannotCorrectTheProjects` | The status endpoint's rule, one act over: an omitted field would let a Test Engineer correct the project's test strategy by not mentioning where it lives. Note the mutation is *bigger than one line* — that is the point, and it is why the input record has no scope field at all. ✅ **RUN 2026-08-22 as a stated PROXY — RED, exactly 1**, the named test. `ArtifactEditInput` has no scope field, so *"read it from the request"* was modelled as `rowScope.mayActOn(true)` — the plan-scope reading an omitted field supplies. **This row corrects `FA-1`'s**: the `PLAN_SCOPE` seam is still wired and still discriminates. This test can see it only because `Correcting`'s fixture **grants the qualifier by hand**, which its own header says is the only way the suite can. |
| `§229 PM-16` | In `ArtifactWriteService.edit`, delete the `artifact.isWriteOnce()` refusal | `Correcting.aWriteOnceRowIsRefused` | `write_once`'s stated meaning is that corrections supersede and never overwrite — a rule about **editing** before it is one about deleting. Nothing sets the column yet, so the fixture sets it in SQL; without that the guard would be enforcement nobody could observe, which is the `Company.suspend()` shape the delete's own header names. |
| `§229 PM-17` | In `ArtifactWriteService.edit`, refuse a `SUPERSEDED` row the way `setStatus` does | `Correcting.aSupersededRowIsEditable` | The decision, pinned from the side that would be silently lost. Copying the status endpoint's refusal reads as consistency and makes a typo in what the register says was replaced permanently uncorrectable — which is the dead end A6 exists to close, one row further back. |
| `§229 PM-18` | In `ArtifactWriteService.validateEdit`, drop the blank-title check | `Correcting.aBlankTitleIsRefused` **and** `Correcting.anOmittedTitleIsRefused` | Two reds, and the second is the one worth reading: §68 makes an absent field and an explicit null the same Java value, so an omitted title is a request to clear a `NOT NULL` column — a 500 from the database where a field error belongs. |
| `§229 PM-19` | In `ArtifactWriteService.edit`, drop the `unchanged` short-circuit | `Correcting.anUnchangedCorrectionIsSilent` | An audit row per repeated save buries the change that happened under ones that did nothing — `setStatus`'s call, and the same reasoning. |
| `§229 PM-20` | In `Artifact.amend`, also set `this.scope = ...` (add a parameter and pass the request's) | **Predicted: zero reds from the mutation alone** — the column is `updatable = false`, so Hibernate discards the write | The measured number is the deliverable, and a zero here is the *good* answer: it is what `Correcting.theUneditableColumnsAreUntouched` is really asserting, and the row exists to record that the protection is the **mapping** rather than the service. If it ever reddens, somebody removed the `updatable = false` and the register's supersede-don't-swap rule (plan §6n) went with it. ✅ **RUN 2026-08-22 — ZERO, the good answer, measured rather than assumed.** `amend` was made to flip `this.scope` outright, and against a real Postgres the `updatable = false` discards it. |
| `§229 PM-21` | In `ArtifactController.update`, answer `Outcome.Unchangeable` with `badRequest()` | `Correcting.aWriteOnceRowIsRefused` | 400 says the payload is malformed; the payload is fine and the permission is held — it is the record's own nature that refuses, and the way forward is superseding it. |
| `§229 PM-22` | In `ArtifactController.update`, answer `Outcome.ForbiddenAtScope` with a bodiless 403 | `Correcting.aPlanScopedGrantCannotCorrectTheProjects` (the `errors.scope` assertion), and `ErrorBodyPolicyTest.bodilessForbiddenSitesArePinned` (scope: `com.tcms.contract.ErrorBodyPolicyTest`) | Two rules catch this from opposite sides, which is worth knowing: the endpoint test reads the *field* the form lands on, and the contract test reads the *construction site*. §229 phase 1 met the second one live on its first draft. |

## §229 phase 1 — the assignee gets a writer, and the environments get a reader (A4)

Scope for every row: `./gradlew test --tests 'com.tcms.execution.ExecutionEndpointTest'`.

| id | Mutation | Expected red | Why this matters |
|---|---|---|---|
| `§229 PM-1` | In `ExecutionAssignmentService`, change `PERMISSION` to `"run:create"` | `Assignment.theRobotMayNotReassignWork` — **and nothing else** | The whole of the gate decision. `run:create` and `result:submit` both admit every persona this endpoint should admit, so only the `Automation Service` credential can tell the three keys apart: a leaked CI token would reorganise a team's workload, with every other test in the file green. If this reddens more than the one test, the extra failures are the finding. ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `theRobotMayNotReassignWork`.** MATCH, and the row's conditional is answered — **nothing else fell**, so no other test in the file is coupled to this constant. §A3 confirms the reasoning: Automation Service is the only persona that can separate `run:execute` from `run:create`. |
| `§229 PM-2` | Same, to `"result:submit"` | `Assignment.theRobotMayNotReassignWork` | The other wrong candidate, and the more tempting one — it is the key the neighbouring PATCH charges. Same single red for the same reason. ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `theRobotMayNotReassignWork`.** MATCH, identical to `§229 PM-1`. One test carries the whole gate decision against both wrong candidates. |
| `§229 PM-3` | In `ExecutionAssignmentService.apply`, replace `people.isActiveMember(...)` with an existence check against `app_user` | `Assignment.anotherCompanysUserIsRefused` | `app_user` is instance-level (ERD §0.1's exception list), so existence accepts a user id belonging to **another client entirely** — Umbra's lead lands on Acme's checklist with a 200. `RunCreateService`'s own comment records that this exact distinction cost it a red test; this is the same column's second writer. |
| `§229 PM-4` | In `TestExecution.assignTo`, also set `executedBy = assigneeId` | `Assignment.theVerdictIsUntouched` | Attributes an engineer's verdict to whoever reorganised the work — a claim about who ran a test, made by a scheduling act, with a 200 and no other tell. |
| `§229 PM-5` | In `ExecutionAssignmentService.apply`, delete the `Objects.equals(...)` short-circuit | `Assignment.repeatingIsIdempotent` | Two people handing the same case to the same tester are agreeing; a row per repeated click buries the change that happened under ones that did nothing. |
| `§229 PM-6` | In `ExecutionAssignmentService.apply`, delete the `target.run().isClosed()` guard | `Assignment.aClosedRunIsFrozen` | A closed run's checklist gets reorganised, so a task nobody can act on appears on somebody's queue. This is also the row that would catch the *opposite* decision being made silently — see the outcome's own header for why the freeze is a judgement rather than an inheritance. ✅ **RUN 2026-08-22 — RED, exactly 1**, the named test. MATCH — the freeze is a judgement *and* it is asserted. |
| `§229 PM-7` | In `ExecutionAssignmentService.assign`, move the `targets.resolve` call **after** the gate and answer `Forbidden` for an unresolved target | `Assignment.unreachableBeatsForbidden` | 403 on a path naming a sibling project's run confirms that run exists — §6t's oracle, on the endpoint whose ids a caller can enumerate cheaply. |
| `§229 PM-8` | In `ExecutionAssignmentService.apply`, drop the `previous` value from the audit body | `Assignment.theAuditRowNamesTheMove` | "Who took this off Ana" is unanswerable from a row that only names where it landed — and for an unassignment that is nothing at all. |
| `§229 PM-9` | In `ConfigurationService.list`, replace `projects.readVisible(...)` with a bare `of(projectId)` | `Configurations.noGrantSeesNothing`, `Configurations.theOtherCompanyIsRefused` | The second is the one that matters: RLS would still scope the rows to the caller's company, so the cross-*company* half is defended by the database and the **no-grant** half by nothing. A project's environment names are project configuration. ✅ **RUN 2026-08-22 — RED, exactly the 2 predicted.** MATCH, with one correction to the row's reasoning: the cross-company half **does** move, because RLS scopes the rows but the endpoint then answers an *empty list* where that arm requires a 404. So both arms see the gate's removal, by different routes. |
| `§229 PM-10` | In `ConfigurationService.of`, order by `name` alone (`findAll` filtered in Java) | `Configurations.everyReaderSeesThem` | Default-first is the order a picker wants and the order a plan already lists them in. Silent, and the cost is a run form whose first option is whichever environment sorts first alphabetically — chosen by nobody. |
| `§229 PM-11` | In `RunController.assign`, answer `Outcome.RunClosed` with `runClosedProblem()` instead of `assignmentFrozenProblem()` | `Assignment.aClosedRunIsFrozen` | The shared sentence says "its results and evidence can no longer change", which is true and about neither thing this caller was doing — it sends the reader looking for a result they never recorded. |
| `§229 PM-12` | In `RunController.assign`, answer `Outcome.Forbidden` with `.build()` | `ErrorBodyPolicyTest.bodilessForbiddenSitesArePinned` (scope: `com.tcms.contract.ErrorBodyPolicyTest`) | **Already observed once, live** — the first draft did exactly this and the rule caught it in the full build. Recorded so the row exists rather than only the memory of it: a refusal that says nothing is the one a model retries (§141). ✅ **RUN 2026-08-22 — RED, exactly 1: `bodilessForbiddenSitesArePinned`.** MATCH — the rule that caught this live once still catches it. 9 s. |
| `§229 PM-13` | In `PlanService`, restore the private `configurationsOf` body (the repository call and the `ConfigurationView` mapping) instead of delegating | **Predicted: zero reds.** Two identical assemblers agree until one changes | The measured number is the deliverable, and a zero is the expected answer: this is E1, so nothing observable moves *today*. It is recorded because the next edit to either — an attribute the parse should skip, a different order — lands on one of the two, and the plan detail and the run form would then disagree about the same table with both suites green. |

## §228 — the single-case status endpoint

**The row ids below say `§227` and stay that way** — minted before the section was numbered §228,
and an id here is never renumbered.

| id | Mutation | Expected red | Why this matters |
|---|---|---|---|
| `§227 PM-9` | In `CaseStatusService`, replace `KEYS = BulkOperations.CASE_KEYS` with a fresh `new LifecycleLadder.Keys("case:edit", "case:edit")` | `CaseBulkEndpointTest.SingleCaseStatus.engineerCannotApprove` and `.engineerIsForbiddenWhereBulkWouldSkip` | The gate collapses to the edit key and a Test Engineer approves and un-approves at will. The mutation is exactly the shape §75 found live on the bulk path, arriving on a second endpoint — which is why the constant is read from the neighbour rather than re-spelled. |
| `§227 PM-10` | In `CaseStatusService.setStatus`, gate on the requested target alone: `RequiredPermission.onProject(requested == APPROVED ? KEYS.approve() : KEYS.edit())` | `CaseBulkEndpointTest.SingleCaseStatus.engineerIsForbiddenWhereBulkWouldSkip` | The target-decides rule. Granting the sign-off is still Lead-only and *rescinding* one drops to the edit key, so a contributor un-approves a lead's case with the permission they use every day. Nine of this class's thirteen tests stay green. |
| `§227 PM-11` | In `CaseStatusService.apply`, delete the `reviews.refuses(...)` arm and go straight to `move(...)` | **Predicted: zero reds** in this class; possibly `ReviewGateTest`/`ReviewPolicyTest` | The measured number is the deliverable, and a zero is a finding: §6g's author-cannot-approve-own-work and minimum-peer-LGTM would then bind on the bulk path and on suites and plans, and **not** on the endpoint a person actually uses to approve one case. The fixture here has review switched off, so nothing in `CaseBulkEndpointTest` can see it. |
| `§227 PM-12` | In `CaseStatusService.move`, delete the `reviews.moved(...)` call | **Predicted: zero reds.** No test here reads `review` rows after a single-case move | The measured number is the deliverable. Submitting would move the status and open no review record, so `ReviewPanel` renders "Not in review" on a case whose chip says In review — and approving would close nothing. The bulk path's equivalent call is covered; this one is not, and the gap is exactly the kind a second writer introduces. |
| `§227 PM-13` | In `CaseStatusService.action`, return `"case.bulk.status_changed"` for every target | `CaseBulkEndpointTest.SingleCaseStatus.perActAuditVerbs` | Plan §7 lists the acts by name so "what did we sign off last quarter" is one query on the action column. Collapsing them also makes the two endpoints indistinguishable in the trail, which is the one thing the trail is for here. |
| `§227 PM-14` | In `CaseController.setStatus`, answer `Outcome.Refused` with `ResponseEntity.badRequest()` | `CaseBulkEndpointTest.SingleCaseStatus.aDraftCannotBeApprovedDirectly` | 400 says the payload is malformed; the payload is fine and the permission is held — it is the case's state that refuses, which is a different thing to fix and the reason `Conflict` exists. |
| `§227 PM-15` | In `CaseStatusService.apply`, treat `Decision.Unchanged` as a `Move` | `CaseBulkEndpointTest.SingleCaseStatus.unchangedWritesNothing` | A repeated click writes an audit row and re-stamps `updatedAt`, burying the change that actually happened under one that did nothing. |

## §227 phase 1 — the report list, the model's own gate, and the reaper

| id | Mutation | Expected red | Why this matters |
|---|---|---|---|
| `§227 PM-1` | In `ReportReadService.openModel`, change `VIEW_REPORT` to `EXPORT_REPORT` | `ReportEndpointTest.Model.screenOnlyReadsTheModel` | This is the slice. The whole point of the route is that reading a report costs less than carrying it away; gated on the export key it is an exact copy of the download route and the two-key split describes a distinction nothing implements again. ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `screenOnlyReadsTheModel`.** MATCH. Worth stating how: roles spec §A3 grants `report:view` and `report:export` to the **same** five roles, so **no seeded persona can tell this pair apart** — the discrimination comes entirely from the class's hand-built screen-only role. This is the `ArtifactEndpointTest.Correcting` pattern the wave-1 record names as the repair for the blind gate family, working. |
| `§227 PM-2` | In `ReportReadService.open`, change `EXPORT_REPORT` to `VIEW_REPORT` | `ReportEndpointTest.Model.screenOnlyStillCannotDownload`, `Downloading.viewDoesNotDownload` | The other half. The split is only real if it holds in both directions, and a download that dropped to the cheaper key would be invisible — every existing download test is run by a persona holding both. ✅ **RUN 2026-08-23 (wave S) — RED, exactly 2: `screenOnlyStillCannotDownload` and `viewDoesNotDownload`.** MATCH, and the split holds in both directions — the same hand-built persona is what makes either half visible. |
| `§227 PM-3` | In `ReportJobView.modelUrl`, key on `job.getStatus() == COMPLETED` instead of on `getModelArtifactId() == null` | **Predicted: zero reds.** The two agree for every job this product writes | The measured number is the deliverable. They diverge only for a job completed by a deployment older than §88's stored model — a row this suite cannot produce, because the worker that writes it always writes both. If it mutates green, the field's nullability is defended by review alone, and the failure it prevents is a link offered on the reports list that 404s. |
| `§227 PM-4` | In `ReportReadService.list`, drop the `project.getId()` argument and call a `findTop100ByOrderByRequestedAtDesc` over the company | `ReportEndpointTest.Listing.theListIsScopedToTheProjectInThePath` | The gate is evaluated against the project in the path; a list resolved company-wide is served *through a gate that passed for a different project*, which is the version of the defect that survives an access-control test. RLS does not catch it — both projects are the same company. |
| `§227 PM-5` | In `ReportJobRunner.runPendingJobs`, delete the `failStuckJobs()` call | `ReportJobPipelineTest.Reaping.anAbandonedClaimIsFailed` | Without it a job whose worker died stays `RUNNING` for ever: the client polls a spinner with no end and no `error`, and nothing in the product can tell that reader or an operator what happened. |
| `§227 PM-6` | In `ReportJobRunner.failStuckJobs`, use `clock.instant()` as the cutoff instead of `clock.instant().minus(properties.stuckJobTimeout())` | `ReportJobPipelineTest.Reaping.aRecentClaimSurvives` | Reaps every claimed row on sight — including the one a healthy worker is about to complete — so every report fails while the log reports a working reaper. `anAbandonedClaimIsFailed` stays green, which is why the young-claim half exists. |
| `§227 PM-7` | In `ReportJobRunner.runPendingJobs`, fold the reaped count back into the loop bound (`int run = failStuckJobs();`) | **Predicted: zero reds.** No test queues 25 jobs and strands 25 more | The measured number is the deliverable. A company that lost a tick's worth of jobs to a restart would spend its whole next tick on rows nobody is computing and leave the live queue untouched — a slow report queue with no error anywhere, which is exactly the class of defect that gets blamed on the database. |
| `§227 PM-8` | In `ReportingProperties`, delete the non-positive `stuckJobTimeout` check | **Predicted: zero reds.** No test binds a bad value | The measured number is the deliverable, and the gap is the shape §216 named: a constraint's *consequence* has to be proved separately from the constraint. A zero timeout parses, validates, starts, and fails every report at the instant it is claimed. |

## §225 — the transaction-scoped memo and the V56 scope-shape triggers

| id | Mutation | Expected red | Why this matters |
|---|---|---|---|
| `§225 PM-1` | In `RoleGrantService.revoke`, delete the `permissions.forgetMemoizedAnswers()` call | `RoleGrantEndpointTest` — the lockout tests, and `PermissionEvaluatorTest.TransactionScopedMemo.mutationInsideTheTransactionNeedsTheForgetSeam` | The self-lockout check re-asks the gate question after the delete, in the same transaction; without the seam it is answered from the memo populated by the gate — from before the delete — and never trips. A caller can then revoke the grant carrying their own ability to revoke, leaving a company with no administrator. |
| `§225 PM-2` | In `PermissionEvaluator.MemoKey`, drop the `token` component | **Predicted: zero reds.** No test drives two token principals through one transaction | The measured number is the deliverable. If this mutates green, the credential's presence in the key is defended by review alone — worth knowing, since two resolutions under different credentials sharing an entry is a cap bypass. A test would need `TokenContext` swapped mid-transaction, which no production path does. |
| `§225 PM-3` | In `V56`, delete the `role_assignment_scope_shape` trigger (Testcontainers migrates fresh, so editing the migration is observable) | `RoleScopeShapeTest.companyRoleNamingAProjectIsRefused` and `systemRoleInRoleAssignmentIsRefused` | The trigger is the only refusal below `RoleGrantService.scopeError`; without it a hand-written COMPANY-role-on-a-project row resolves as project permissions and replaces the subject's inherited default via the override switch. ✅ **RUN 2026-08-23 (wave S) — RED, exactly 2.** MATCH: `companyRoleNamingAProjectIsRefused` and `systemRoleInRoleAssignmentIsRefused` both fall with the trigger deleted from `V56`. ⚠️ **Scope correction**: `RoleScopeShapeTest` is `com.tcms.schema`, not `com.tcms.identity` — the runner's `ERROR-NO-TESTS` guard caught it rather than a green build. |

## §224 — the decoder that outlived its provider, and the table with no deleter

| id | Mutation | Expected red | Why this matters |
|---|---|---|---|
| `§224 SP-1` | In `OidcIdTokenVerifier`, change the cache key back to `expected.providerId()` | ✅ **RUN 2026-08-15 — RED by construction.** `OidcIdTokenVerifierTest.anEditedProviderIsNotServedItsOldChecks` (the mutation is exactly the state the test was written against) | Keyed by row id, the decoder built on a provider's first callback kept the old issuer, audience and `jwks_uri` until JVM restart — so the admin screen's whole reason to exist (editing `issuerUri`/`clientId`) silently did not take effect, and `OidcProperties.discoveryTtl`'s stated purpose (*"a rotated `jwks_uri` must eventually be picked up without a restart"*) was defeated one class downstream of where it is enforced. |
| `§224 SP-2` | Delete the `@Scheduled` annotation from `SsoLoginAttemptPurge.purgeExpired` | **Predicted: zero reds — and that is the row.** `SsoLoginAttemptPurgeTest` calls the method directly, so the *wiring* is defended by review alone | Nothing else ever deletes an attempt row, and the writer is unauthenticated: every anonymous `GET …/authorize` that resolves an enabled provider inserts one. Un-wired, the table `V53` calls "empty in steady state" grows with crawler traffic until an operator notices the disk. The live observation is cheap: on a running stack, `SELECT count(*) FROM sso_login_attempt WHERE expires_at < now() - interval '2 hours'` must be **0** once the app has been up past the first hourly tick. ✅ **RUN 2026-08-22 — ZERO, as predicted: the wiring is defended by review alone.** The scope was widened to `ArchitectureRulesTest` as well and no rule sees it either — `purgeExpired` is package-private, and the unexercised-seam rule examines public methods only (see `UX-3`). |

## §223 — the directory sign-in, driven against the compose stack it has never met

**These four are live-stack drives, not suite mutations** — the suite already drives the encrypted
path against an in-memory server (`LdapDirectoryTest`, `LdapLoginEndpointTest`), so what is unrun is
the same flow against the compose stack and a real directory. The stack has no LDAP server today;
the sibling browser queue's §223 rows carry the two options (an `osixia/openldap` service in
`compose.yaml`, or the lab directory), and these rows need the same choice made first.

| id | What to drive | Expected |
|---|---|---|---|
| `§223 LD-1` | Register an LDAP provider through `POST /identity-providers` against the compose stack, enable it, sign in with `POST /auth/ldap/{id}/login` | 200 with a session; `user_identity` gains one row whose `subject` is the directory's own identifier and not a DN |
| `§223 LD-2` | Rename the person's `mail` in the directory and sign in again with the new address | 200, **the same** `user_identity` row, no second `app_user` |
| `§223 LD-3` | Six wrong passwords in a row | The sixth is refused with no LDAP connection opened — check the directory's own log, not TCMS's |
| `§223 LD-4` | Point a provider at `ldap://` without StartTLS through the API | 400 with an `issuerUri` field error, and no row written |

## §231 — social login: the single-use claim and the linking race

**These two are live-stack drives** — the suite already pins both behaviours
(`SocialLoginServiceTest.replayFails`, `SocialIdentityServiceTest.aTakenSubjectRefuses` and the
`DataIntegrityViolationException` arm in `SocialIdentityService.link`), so what is unrun is the
same pair against the compose stack with a real provider round-trip.

| id | What to drive | Expected |
|---|---|---|---|
| `§231 SB-1` | Complete one social sign-in, then replay the same callback URL (same `state`, same `code`) | The failure redirect and **nothing written**: `social_login_attempt.consumed_at` unchanged from the first pass, no second session, `user_social_identity.last_login_at` not re-stamped. |
| `§231 SB-2` | Two concurrent LINK callbacks presenting the same provider subject against two different accounts (two browsers, two `state`s, one upstream identity) | Exactly **one** `user_social_identity` row for that `(provider, subject)` afterwards; the loser sees the one opaque failure marker, not a 500 — `uq_user_social_identity_subject` surfaced as policy. |

## §232 — the asymmetries (audit section B)

**Seven rows, none run** (§65). Ids are `§232 PB-*`; `PB` is unused elsewhere in this file.

**Two of them predict zero reds and say so** — a prediction that is itself the finding if it turns
out wrong.

| id | Mutation | Expected red | Why this matters |
|---|---|---|---|
| `§232 PB-1` | In `CaseUpdateRequest.overlay`, take every field from `sent` (the pre-B1 reading) | `CaseUpdateEndpointTest.PartialEdits.absentLeavesEverythingElseAlone` and `nullClears` | This is the defect itself: a delta edit emptied four columns and deleted every step, and answered 200. **Nothing else in 2,900 tests catches it** — every other test sends the complete set the SPA sends, which is why it survived for two hundred sections. If any test outside `PartialEdits` reddens, that is worth recording: it means something else had come to depend on the erasure. |
| `§232 PB-2` | Take the four required fields from `stored` instead of `sent` (make the patch fully partial) | `CaseUpdateEndpointTest.contractRequiredMatchesWhatTheServerEnforces` | The check that made this decision. It derives `TestCaseInput.required` by dropping each field in turn from a body the endpoint accepts, so a fully partial patch empties its population and it fails on its own vacuity guard — which is how the suite said that a schema shared with `POST /cases` cannot be true of one verb and false of the other. |
| `§232 PB-3` | In `CaseUpdateService`, pass `ProcedureRule.REQUIRED` unconditionally | `CaseUpdateEndpointTest.PartialEdits.aStubIsSaveable` **and** `CaseCreateEndpointTest.theEditPathRoundTripsIt` | Two classes, deliberately: the second is where the old decision was written down, and it is the one a reader would find first. |
| `§232 PB-4` | In `ArtifactWriteService.createFile`, move the `replayed(...)` guard below `writeFile` | `ArtifactFileEndpointTest.Idempotency.aRetryStoresNothingNew` | The assertion that matters there is `attachmentRows`, not the artifact count: the attachment is written first and flushed, so a replay that skipped only the artifact still leaves an orphan row pointing at a second blob. |
| `§232 PB-5` | Drop the `status = 'COMPLETED'` predicate from `findCompletedByIdempotencyKey` | `IngestionEndpointTest.Refusals.aCorrectedRetryUnderTheSameKeyWorks` | The whole of B6's trade. Without it the corrected retry finds the failure and replays it, so the caller is handed the refusal again for a payload they have already fixed — with nothing to do but invent a new key, which is what the old no-row behaviour existed to avoid. |
| `§232 PB-6` | In `RunCreateService.scopeRequestOf`, record `edit.caseIds()` instead of the request's arms | `RunCloneEndpointTest.RecordedScope.theRequestIsRecorded` | The one way to get B7 subtly wrong: it looks like provenance, renders plausibly, and answers "why is this case here" with the same list it was asked about. The assertion is that `caseIds` is **empty** for a suite-scoped run. |
| `§232 PB-7` | Remove `@JdbcTypeCode(SqlTypes.JSON)` from `TestRun.scopeRequest` | **Predicted: a whole nested class red, in tests that mention no scope** — `RunCloneEndpointTest$Gating` and `$WhatComesAcross` | Recorded because of how it presents: Postgres refuses `character varying` into `jsonb`, so every run create fails with a batch error naming twenty-four columns, and the failing tests are about approval gates. It is the shape of failure worth recognising rather than a check worth having. |
| `§232 PB-8` | Delete the `writeGranted` wrapper from `IngestionService.recordFailure` | **Predicted: zero reds** — `IngestionEndpointTest.aRefusedCallerRecordsNothing` covers the Viewer, and a caller with no project visibility at all is not driven | The row is written under the gate so posting rubbish cannot write into a project's history, or disclose that the project exists. The Viewer case is covered; the unreachable-project case is the one no persona in that class can express, and it is named here rather than implied. ❗ **RUN 2026-08-22 — RED, 1. The row predicted ZERO and is wrong, in the direction one wants to be wrong in.** `aRefusedCallerRecordsNothing` **does** catch an ungated `recordFailure`, so "posting rubbish into a project you may not write to" is pinned rather than merely intended. |
| `§232 PB-9` | In `PlanCreateService`, resolve live suite ids from `request.suiteIds()` alone | `PlanCreateEndpointTest.CompleteInOneRequest.theRestOfThePlanArrives` | Not a mutation of new code so much as a re-introduction of the defect B9 uncovered: a section naming a suite was refused as "that suite is not in this project any more" about a suite that is. **Predicted: exactly one red.** More than one means something else began depending on the narrow resolution. |

## §234 — the two columns that finally have writers (C6 + C7)

| # | Mutate | Expect |
|---|---|---|---|
| `§234 PM-1` | In `ArtifactWriteService.create`, pass `null` for the report artifact id instead of reading it back from `ReportFacts` | **`ArtifactEndpointTest.PinnedReports.aCompletedReportIsPinnable` red** — the row would register with no payload and `url` would fall through to `externalUrl`, which is null for this kind. The assertion is on the composed URL rather than on the column, so it catches the projection and the write together. ✅ **RUN 2026-08-22 — RED, exactly 1**, the named test. MATCH. |
| `§234 PM-2` | Drop `updatable = false` from `Artifact.writeOnce` and have `amend` set it from a field | **`SignOffRecords.anEditCannotSetIt` red.** Two layers say no today — the record has no such component and the column is not updatable — so this mutation needs both changed, which is the point: a one-line "cleanup" cannot reach it. |

## §235 — the first reader a search_vector has ever had (C3)

**Three rows, none run** (§65). Ids are `§235 PM-*`.

| # | Mutation | Expected observation |
|---|---|---|---|
| `§235 PM-1` | In `DocPageRepository#search`, delete the `p.company_id = :companyId` line | **`DocPageEndpointTest$Searching.theCompanyBoundIsEnforced` red.** The mutation is the whole subject of §2 of the handoff: RLS is still underneath and would still refuse, so this row is really asking *whether the suite can tell*. If it goes **green**, the belt-and-braces predicate is unobserved and the argument for writing it is weaker than the header claims — which is worth knowing either way, and is why this is the first row. ❗ **RUN 2026-08-23 (wave 2) — ZERO. `theCompanyBoundIsEnforced` stays GREEN**, which is the answer the row asked for and the less comfortable one: RLS refuses the foreign row anyway, the belt-and-braces predicate is unobserved, and the argument for writing it is weaker than the header claims. Proxied — the predicate was replaced with `CAST(:companyId AS uuid) IS NOT NULL` rather than deleted, because an unbound `@Param` will not start. |
| `§235 PM-2` | In `DocPageService#search`, drop the re-ordering and return the entity read's own order | **`Searching.rankingUsesTheStoredWeights` red.** `IN` has no order, so the answer becomes whatever the JPQL read returns — usually insertion order, which agrees with the ranking often enough that a fixture of two pages ranked the same way as they were created would not catch it. The fixture is authored body-match-first for exactly this reason. |
| `§235 PM-3` | Change `ts_headline`'s source from `body.text` to `p.title` | **`Searching.theSnippetLocatesTheMatch` red on both halves at once**: the blockless page stops serving null (a title is never null), and the prose page's fragment stops containing the match. One mutation, two failures, which is the pin that the snippet is drawn from the same corpus the vector was built from. |

## §236 — defect links get a writer (C5), and the runner sends a stepId (C9)

**Eight rows, none run** (§65). Ids are `§236 DK-*` and `§236 SE-*`; both prefixes were grepped
against this file and the browser queue before minting. Scope for every row is
`com.tcms.execution.ExecutionEndpointTest`.

**`SE-1` is the only row on this slice's frontend half**, and it is here rather than in the browser
queue because what it mutates is a server rule the SPA merely obeys. C9 shipped no backend change.

| # | Mutation | Expected observation |
|---|---|---|---|
| `§236 DK-1` | In `DefectLink.to`, set `provider` to `"JIRA"` instead of `PROVIDER_EXTERNAL` | **`DefectLinks.providerNamesNoTracker` red.** This is `V62`'s whole argument as a mutation: the value the column used to default to, restored by one line, naming a tracker the instance has never spoken to. A green build would mean the constant is decorative and M4 inherits rows it cannot tell apart from real Jira ones. |
| `§236 DK-2` | In `DefectLinkService.write`, delete the `existing.isPresent()` branch and always insert | **`DefectLinks.replayIsNotATwin` red** — and *how* it fails is the interesting half. If it fails with a **500 rather than a wrong 201**, the unique index is doing the work and the branch is only choosing the status code; if it fails with a duplicated row, the index is not there. Both are findings and they are different ones, so read the failure rather than the count. |
| `§236 DK-3` | In `DefectLinkRepository.findByExecutionIdAndProviderAndExternalKey`, drop `AndProvider` from the name | Expected **green**, and that is the point of running it: every row this writer makes carries `EXTERNAL`, so the narrower lookup agrees with the wider one until M4 writes a second provider. Recorded so the day it stops being green is not a surprise — the lookup must name exactly the index's columns (`V11`'s rule, five tables over), and a green here is a gap in the fixture, not in the code. |
| `§236 DK-4` | In `DefectLinkService.urlErrors`, delete the scheme check | **`DefectLinks.refusesASchemeThatExecutes` red.** The security row. `javascript:` reaches `href` on three screens — the run detail, the case history and the plan statistics — and React does not sanitise an `href`. A green build would mean stored XSS behind a `defect:create` grant. ❗ **RUN 2026-08-22 — ZERO. THE SECURITY ROW PASSES FOR THE WRONG REASON, and this is the finding of the batch.** Deleting the scheme check reddens **nothing**, because `refusesASchemeThatExecutes` posts `javascript:alert(document.cookie)` — which has no authority component, so `parsed.getHost()` is null and the **host** check refuses it, with a different message. The test asserts only that `errors.url` is non-null, so it cannot tell the two refusals apart. The scheme check is over-determined for that one payload and is currently pinned by nothing. **What would close it:** assert the *message*, and add a payload that carries a host — the `scheme://host/…` shape — so the scheme check is the only thing that can refuse it. |
| `§236 DK-5` | In `DefectLinkService.write`, move the `run.isClosed()` check *below* the validation and the lookup | Expected **green** — no test drives a link against a closed run, and that is a recorded gap rather than a decision. `EvidenceService` orders it this way deliberately (a caller must not learn what is linked on a run whose links are frozen), and this slice copied the ordering without copying the observer. **The cheapest row to close before running the file.** |
| `§236 DK-6` | In `DefectLinkController.unlink`, change the gate constant from `CREATE_DEFECT` to a key every role holds (`case:read`) | **`DefectLinks.unlinkNeedsTheKeyToo` red.** The reuse-rather-than-seed decision is the one thing about this endpoint a reader is most likely to "correct"; this is its witness. ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `unlinkNeedsTheKeyToo`.** MATCH. §A3 discriminates via the Viewer, who holds `case:read` and not `defect:create`. |
| `§236 DK-7` | In `DefectLinkRepository.findByIdAndExecutionId`, drop `AndExecutionId` | **`DefectLinks.unlinkIsScopedToTheExecutionInThePath` red.** The two-segment defect: a link removed through a path naming a different execution, 204 and all. |
| `§236 SE-1` | In `EvidenceService.attach`, replace the `stepOutcomes.findByExecutionIdAndStepId` empty branch with `stepResultId = null` (attach to the execution instead of refusing) | Expected **green** — nothing asserts the refusal, only the success path. The consequence is what C9's UI is built around: a tester attaches to step 3, the file silently lands on the execution, and the runner shows it under no step at all. **A missing observer, and the second row to close.** |

## §237 — quarantine consumers (C2)

**Nine rows, none run** (§65). Ids are `§237 QR-*` (the reports) and `§237 QB-*` (the badge); both
prefixes were grepped against this file and the browser queue before minting. Files:
`reporting/ExecutionSource.java`, `reporting/AggregateExecutionReport.java`,
`reporting/RunSummaryReport.java`, `execution/CaseFacts.java`, `execution/ExecutionViews.java`.

**The two rows to run first are `QR-2` and `QR-6`.** Both name the decision the slice turns on — that
the *headline* figure does not move — and a green on either would mean the guard that decision rests
on is not actually observing it.

| # | Mutate | Expect |
|---|---|---|---|
| `§237 QR-1` | In `ExecutionSource.quarantinedCases`, drop the `AND f.quarantined` predicate | **`RunSummaryReportTest.Quarantine.*` and `AggregateVariabilityTest.Quarantine.*` red.** Every case in the project would be treated as quarantined, so both excluded rates collapse to `—` and every section names every case. The loudest possible failure, which is why it is first. |
| `§237 QR-2` | In `AggregateExecutionReport.metadata`, change the headline entry to `share(totals.trustedPassed(), totals.trustedExecutions())` — i.e. make the single printed rate the excluded one | **`AggregateVariabilityTest.theCrossCheckedNumbersAreUntouched` red.** *The* row. This is the "simplification" a future reader is most likely to make, it is what the audit's own one-line wording literally asks for, and it would silently put this report out of agreement with `GET /plans/{planId}/statistics`. Confirm whether `AggregateExecutionCrossCheckTest` also reddens — it asserts per-status counts rather than the rate, so it may **not**, and if it does not, this row is the only observer. |
| `§237 QR-3` | In `AggregateExecutionReport.totals`, change the empty-quarantine predicate from `"false"` to `"true"` | **Red** in the empty case: with nothing quarantined every row would be counted as excluded, so the trusted denominator goes to zero and the rate prints `—` where the two rates must agree. Guards the branch that exists only because `IN ()` is not valid SQL. |
| `§237 QR-4` | In `AggregateExecutionReport.totals`, move the three quarantine `FILTER`s off `resolved` and onto `selected` | **`theExclusionIsCountedOverTheRESOLVEDSet` red.** The exclusion must follow the selection's aggregation policy; counting over the un-collapsed set reports more exclusions than the denominator ever held, and the arithmetic stops closing under `LATEST`. |
| `§237 QR-5` | In `AggregateExecutionReport.quarantineSection`, return `Section.text(...)` unconditionally (drop the table) | **`theReasonIsOnTheFaceOfTheReport` red.** The reason column is the point: without it the only way to evaluate an exclusion is to read the test, which is the work the quarantine deferred. |
| `§237 QR-6` | In `RunSummaryReport.metadata`, delete the `Pass rate (excluding quarantined cases)` entry | **`RunSummaryReportTest.theHeadlineRateDoesNotMove` red.** The other half of `QR-2`: this slice is worthless if the second figure is not printed, and it is a one-line deletion that looks like tidying. |
| `§237 QR-7` | In `ExecutionSource.quarantinedCases`, add `AND c.deleted_at IS NULL` to the `test_case` join | Expected **green** — no fixture quarantines a soft-deleted case. Recorded as a **known gap, not a decision made by the test**: the predicate's absence is deliberate (`caseLabels`' reason — the fact log projects a deleted case's results, so dropping the case here would leave its executions inside the *excluded* denominator), and nothing currently observes it. `work.BoardFacts.quarantinedCases` deliberately does the opposite. **The cheapest row to close.** |
| `§237 QB-1` | In `CaseFacts.quarantinedCaseIds`, return `Set.of()` unconditionally | **`ExecutionEndpointTest.QuarantineBadge.bothScreensCarryIt` and `itIsAPropertyOfTheCaseAndNotOfTheRun` red.** The whole badge, gone, with the property still on the wire as `false` — which is the silent shape: nothing errors and every screen still renders. |
| `§237 QB-2` | In `ExecutionViews.render`, key the quarantine set on `TestExecution::getId` instead of `getCaseId` | **`itIsAPropertyOfTheCaseAndNotOfTheRun` red** — no row would ever badge, since an execution id is never a case id. Guards a substitution between two uuids of the same shape on the same line, which no gate can see. |
| `§237 QB-3` | In `TestExecutionView.of`, pass `false` instead of `summary.quarantined()` | **`bothScreensCarryIt` red** on its second assertion only. The checklist keeps its badge and the screen it opens loses one — the exact asymmetry the shared renderer exists to prevent, and the reason that test asserts both halves rather than one. |
| `§238 SV-1` | In `SuiteVersions.reconcileAfterCaseWrite`, drop the `before` set from the union (reconcile only what holds the cases now) | **`SuiteCompositionVersionTest.theLosingSuiteAdvancesToo` and `deletingACase` red.** *The* row for this slice. Every gaining assertion still passes, which is the whole point: a suite that loses a case is unfindable after the write, so the failure is silent and one-directional — folders quietly stay pinned to compositions they no longer have. |
| `§238 SV-2` | In `SuiteVersions.decide`, return `new Decision(currentVersionNo + 1, true, hash)` unconditionally (delete the hash comparison) | **`Unchanged.aNoOpSuiteSave`, `aCaseContentEdit`, `filingWhatIsAlreadyTagged` and `taggingACase`'s first assertion red.** The pre-§238 header argued a `decide()` sibling could not be tested because its false branch was unreachable; this row is the measurement that it now is, on four different acts. |
| `§238 SV-3` | In `SuiteVersions.reconcileOne`, snapshot the suite's DEFINITION instead of the resolved union — e.g. hash `SuiteCaseRepository.findForSuite` + `SuiteTagRuleRepository.findForSuite` rather than `membership.caseIdsIn` | **`filingWhatIsAlreadyTagged` red**, and `taggingACase` **still green** — which is exactly why that first test exists. This is the plausible alternative reading of C1, it passes almost everything, and what it produces is a `suite_version` table that disagrees with the tree's counts and a run's scope. |
| `§238 SV-4` | In `Suite.applyCompositionVersion`, add `this.updatedAt = now` (and take a `now`) | Expected **green** — nothing asserts a suite's `updatedAt` across a case write. Recorded as a **known gap, not a decision the tests make**: the omission is deliberate (a suite nobody touched must not sort to the top of "recently updated", nor name as its editor whoever tagged a matching case), and nothing observes it. **The cheapest row to close**, and it wants an assertion on `updatedAt`/`updatedBy` after a `retag` of a case a tag rule reaches. |
| `§238 SV-5` | In `SuiteVersions.reconcile`, delete the `suites.flush()` | Expected **red**, but confirm *which* tests and whether it is order-dependent: `caseIdsIn` is a native query, and Hibernate's pre-native-query flush may already cover it. If everything stays green the flush is load-bearing only by argument, and the row should say so rather than the comment claiming a defence nothing exercises. ❗ **RUN 2026-08-22 — ZERO, and the row's own conditional decides it: the flush is load-bearing only by argument.** Everything stayed green across `SuiteWriteEndpointTest`, `SuiteCompositionVersionTest` and `CaseUpdateEndpointTest`. Hibernate's `FlushMode.AUTO` already flushes before a native query whose query spaces it cannot compute, so the explicit `suites.flush()` in `reconcile` is covered by the session. **The comment above the line claims it is *"load-bearing rather than defensive"*, and for this suite that is not observable.** |
| `§238 SV-6` | In `SuiteWriteService.create`, revert `versions.writeInitial(created, project.getId(), …)` to write an empty composition | **`Creation.aCreateWithMembers` red** on its snapshot assertion, and `versionNumbers` **still `[1]`** — the shape of the original defect exactly: the right number of rows, each saying something false. |
| `§238 SV-7` | In `SuiteStatusService.move`, pass `suite.getCurrentVersionNo() + 1` to `reviews.moved` | **`ThePin.thePinMoves` red.** Guards the pin against a "fix" that looks like it is making the version fresher and instead names a composition that does not exist. |
| `§238 SV-8` | In `Suite.applyStatus`, add a `currentVersionNo++` | **`approvingDoesNotMintItsOwnVersion` red**, and `thePinMoves` red too. This is C1's literal wording implemented, and the row exists so the divergence is a measured refusal rather than a paragraph. ✅ **RUN 2026-08-22 — RED, exactly the 2 predicted.** MATCH: the divergence from C1 is now a measured refusal. |
| `§238 SV-9` | In `CaseBulkService`, move the `suitesBefore` read below the mutation loop | **`Bulk.oneVersionPerSuite` red** on the `from` suite. `SV-1` at the other call site, and worth its own row because the bulk path is the one where the read is textually far from the write. |
| `§238 SV-10` | In `CaseBulkService`, drop the `dryRun` guard on `suitesBefore` and on the reconcile | **`Bulk.aPreviewIsNotAWrite` red.** A preview endpoint that writes is the one failure this operation's whole contract is about. |

## §239 — retiring the shared-step library (C4)

**Four rows, none run** (§65). Ids are `§239 RT-*`, shared with the browser queue's block of the
same name.

**A retirement has little to mutate, and saying so is more useful than padding the list.** This
slice adds no new branch — it deletes a validator, a mapping, two endpoints and a picker. Three of
the four rows below therefore mutate *the deletion itself*, asking whether anything would notice the
feature coming back. **`RT-M2` is the one that matters**, and it is expected **green**, which is the
finding.

| # | Mutate | Expect |
|---|---|---|---|
| `§239 RT-M1` | In `IdempotencyKeyCapTest`, set `EXPECTED_DECLARATIONS` back to `10` | **Red** — `theCountIsPinned`. Cheap, and it confirms the count is measured over `src/main` rather than asserted from the javadoc beside it. The number went **down** for the first time in this slice, so the direction is worth exercising once.  ⚠️ **RUN 2026-08-23 (wave S) — RED, exactly 1: `theCountIsPinned`, run with a SUBSTITUTED number.** `EXPECTED_DECLARATIONS` is **10 again** — a declaration was added after §239 took it down to nine — so *"set it back to 10"* is a no-op today. It was set to **9** instead, one below the live count, which exercises the same direction the row wanted. The count is measured over `src/main`, not asserted from the javadoc: note the `@DisplayName` still says *"one cap, nine declarations"* while the assertion says ten, and only the assertion is checked. |
| `§239 RT-M2` | Re-add `sharedStepId` to `CaseUpdateRequest.StepInput` and to `TestStep`'s mapping, and have `CaseUpdateService` persist whatever arrives — i.e. resurrect the field with **no validation at all**, as it was between M1 and §64 | Expected **GREEN**, and that is the point of the row. The contract no longer declares the property, so nothing in the backend suite sends it and nothing would catch a server that quietly started storing it again. Recorded as a **known gap**: `ContractConventionTest` checks that a declared schema has a Java shape, not that a Java shape declares no undeclared field. The nearest real observer is `packages`' `contractExchange`, which reads request payloads — but only for payloads the packages surface sends. **The cheapest row to close, and it wants an assertion that an undeclared property on `TestStepInput` is refused or ignored, not persisted.** ✅ **CONFIRMED BY READING 2026-08-22 — not mutated, and the reading is conclusive.** `sharedStepId` occurs nowhere as a live field: every hit across `backend/src`, `docs/specs/openapi.yaml`, `frontend/src` and `packages/` is prose or the retired `V63`/`V24` migrations. There is no `StepInput` field, no contract property and no test payload — so a resurrected field would be sent by nothing and observed by nothing, which is the GREEN the row predicts. A three-file resurrection would have measured the same zero at much greater cost. |
| `§239 RT-M3` | In `V63`, replace the two `COMMENT ON` statements with `ALTER TABLE test_step DROP COLUMN shared_step_id;` | Expected **green** — nothing maps the column, so `validate` is satisfied and every test passes. That is exactly why the drop is owed to a later slice as a **decision** rather than left to whoever next tidies the migration: no gate distinguishes the safe migration from the destructive one. Confirm `SearchVectorTest` — it still asserts `shared_step`'s trigger and index, so a `DROP TABLE` (unlike a `DROP COLUMN`) **would** redden something, which is worth knowing before the contract slice is written. ⚠️ **RUN 2026-08-23 (wave S) — RED, 1, AND THE ROW PREDICTED GREEN.** `SchemaCommentTest.everyQualifiedReferenceNamesALiveColumn` falls: `V59`'s comment on `shared_step.created_idempotency_key` names `test_step.shared_step_id` **in prose**, and that rule reads it. So **the claim *"no gate distinguishes the safe migration from the destructive one"* is false** — one does, three migrations away and entirely by accident. `SearchVectorTest.doNotSilentlySwapTheMechanisms` stayed green exactly as the row expected of a `DROP COLUMN` (it asserts `shared_step`'s own trigger and index, which a `DROP TABLE` would take). Worth inheriting before the drop slice: the gate exists, it is incidental, and it would be lost the day that comment is reworded. |
| `§239 RT-M4` | In `CaseEdit.normalizeSteps`, drop the `existingStepIds` membership check | **Red** — the step-identity tests. Not about this slice, but the check now sits alone where it used to sit beside the shared-step one, and this row confirms the survivor was not removed with its neighbour. |

---

## §240 — the buffer, the archive, and the two report headers (audit D + E)

**None run** (§65). Three migrations, so three families: `DR` (D1, `V64`), `AR` (D2, `V65`) and
`RF` (E1 + E2, `V66`). D3 wrote no code and therefore has no row — its deliverable is a note, and a
note is mutated by deleting it, which no gate can observe.

**`DR-M2` and `RF-M3` are the two worth running first.** Each asks whether the *reversal* in its
slice is actually load-bearing, which is the question a slice that reverses a shipped decision has
to be able to answer.

| # | Mutate | Expect |
|---|---|---|---|
| `§240 DR-M1` | In `CaseUpdateService`, change `drafts.deleteForCase(testCase.getId(), userId)` back to deleting every draft on the case (add a repository method that omits the user predicate) | **Red** — `anotherPersonsSaveLeavesYoursAlone`. This is the exact behaviour §128 shipped and pinned, so the row confirms the new assertion replaced the old one rather than sitting beside a gap. ✅ **RUN 2026-08-22 — RED, exactly 1**, the named test. MATCH — the new assertion **replaced** the old one. |
| `§240 DR-M2` | In `CaseDraftService.read`, drop the `userId` predicate — `drafts.findScopedById(caseId, userId)` → a case-only lookup returning any row | **Red** — `oneBufferPerPerson`, on the 204 arm. Worth running because a case-only read is the *cheapest* way to write this service and the one a later refactor would reach for; without that arm's assertion the two-row case still passes every other test in the class. |
| `§240 DR-M3` | In `V64`, drop the `DELETE FROM test_case_draft WHERE updated_by IS NULL` and keep the `SET NOT NULL` | **Red at migration time** on any database holding such a row — and **green on Testcontainers**, which migrates a fresh container. Recorded as a **known gap**: no gate in this repository can see a migration that is only unsafe against existing data. The row is here to say so, not to be fixed. ⏭ **NOT RUN 2026-08-22 — manual lane**, on the row's own terms: Testcontainers migrates a fresh container, so the measurement would be a green that proves nothing. Filed beside `§192 DF-7`. |
| `§240 AR-M1` | In `TestPlanRepository.findLiveInProject(projectId)`, drop the `AND p.archivedAt IS NULL` predicate | **Red** — `archivingRemovesItFromTheWorkingList`. The whole feature is that predicate. |
| `§240 AR-M2` | In `PlanArchiveService.apply`, re-stamp on a repeat: delete the `archived == plan.isArchived()` early return | **Red** — `theSecondArchiveIsANoOp`, on both the timestamp and the empty audit list. |
| `§240 AR-M3` | In `TestPlan.applyArchived`, also set `status = PlanStatus.DRAFT` when archiving | **Red** — `archivingRemovesItFromTheWorkingList` asserts the status survives. This is the fourth-status conflation arriving as a one-line "tidy up", which is exactly how it would arrive. |
| `§240 AR-M4` | Change `PlanArchiveService.EDIT_PLAN` to `"plan:delete"` | **Red** — `engineerCanArchive`. The persona in that class holds `plan:edit` and not `plan:delete`, which is what makes the two keys distinguishable at all (§71's recorded problem, still paying). ⚠️ **RUN 2026-08-23 (wave S) — GREEN. ZERO-UNEXPECTED, and the row's own premise is stale.** `engineerCanArchive` does **not** tell the two keys apart any more: `V51` (§207) granted `plan:delete` to Test Engineer on 2026-08-14, eight days before this row was written, so §A3 now gives `plan:edit` and `plan:delete` to the same four roles and the persona holds both. The test's own comment — *"This is the persona that tells the two apart"* — is false as written. Same family as `CA-1`/`FA-11`/`§210 DQ-5`. What would close it: a hand-granted persona holding one key, or the catalogue-derived convention test. |
| `§240 RF-M1` | In `AggregateExecutionReport`, delete the `freshness()` override so it inherits `LIVE` | **Red** — `theHeaderStatesItsFreshness`. The default is the safe direction (a new report claims nothing), so this row confirms the override is what makes the one projected report say so. |
| `§240 RF-M2` | In `ReportHeaders.of`, pass `generatedAt` where `watermark` is expected | Expected **GREEN**, and that is the finding. The two instants are within milliseconds of each other in a job, so no assertion can tell them apart — the distinction only pays on a deployment where the refresh is slow. Recorded as a **known gap**: closing it wants a test that stalls the refresh, which is a clock seam this service does not have. |
| `§240 RF-M3` | In `ReportJobService.accept`, delete the replay block (the `findByProjectIdAndCreatedIdempotencyKey` lookup) but keep writing the key | **Red twice** — `aReplayedKeyQueuesNothing` on the status, and then a **unique-constraint violation** from `report_job_created_idempotency_key_idx` on the second insert. The second failure is the more interesting one: it shows the index is the enforcement and the lookup is the courtesy, which is `V14`'s design and the reason its three columns must match the predicate. |
| `§240 RF-M4` | In `ReportController.generate`, answer `202` for `Replayed` as well as `Accepted` | **Red** — `aReplayedKeyQueuesNothing`. The status is the only thing that tells a caller their retry queued nothing, and this is the most likely way to lose it. |
| `§241 OC-1` | In `OrgChartService`, replace the `reach.maySee(...)` filter on the project axis with `true` | **Red — five arms, and RUN during the slice rather than recorded.** `noColumnIsServedForAProjectTheReaderCannotOpen`, plus the four §4.4 project-axis arms. **The whole disclosure boundary in one predicate**: the response gets *bigger*, every screen still renders, and nothing looks wrong. Recorded here anyway because the row's value is the *next* time somebody touches that line. ✅ **RE-RUN 2026-08-22 — RED, 5, and they are the five the row names.** The in-slice run is confirmed rather than trusted: the whole disclosure boundary really is in that one predicate. |
| `§241 OC-1b` | The same mutation, watching **only** the two §8.3 agreement arms | Expected **GREEN**, and it is the finding that shaped the test class. §8.3 pins the SUBJECT's `canView`, and a cell for a project the reader cannot open still names somebody who legitimately holds a role there — so the agreement test as specified does not observe the disclosure boundary at all. The reader-side sweep is a third arm and is what fails. Measured, not reasoned. |
| `§241 OC-2` | In `OrgChartService.row`, replace `administers && account != null` with `account != null` for `lastLoginAt` | **Red** — `lastLoginIsWithheldFromAnOrdinaryMember`. Field-level redaction has one precedent (`UserView`) and copying it is how the second copy forgets. The contract already declares the field nullable, so **nothing about the shape changes**. |
| `§241 OC-3` | In the generalised project-role query, delete `AND ra.company_id = :companyId` from both union arms | Expected **GREEN**. ⚠️ **Recorded as the gap, not as a pass.** `RoleAssignmentRepository` measured exactly this on the sibling query: RLS independently scopes the table, so the second layer holds when the first is removed. It becomes load-bearing the moment a deployment takes `V1` up on its offer to disable RLS, and no test covers that configuration. |
| `§241 OC-4` | Remove `@Transactional(readOnly = true)` from `RoleAssignmentRepository` | **Red** — `RepositoryTransactionTest`, which derives its list from the live context. The zero-rows-under-RLS defect: fail-closed and silent, `CompanyContext` holding the right company while every read returns nothing. This slice adds no repository, so the row is about the one it changed. ✅ **RUN 2026-08-22 — RED, exactly 1: `EVERY repository interface carries @Transactional, so a new one cannot forget`.** MATCH. **This answers `D6` for the whole family** — `§242 LG-M11`, `§243 RN-M9` and `§247 SK-M10` are the same line on a different repository, and one measurement is enough for all four. |
| `§241 OC-5` | In `EffectiveRoleNames.on`, return the named role **and** the inherited one instead of replacing | **Red — twelve arms across three classes, and RUN during the slice.** `EffectiveRoleNamesTest` (4), `OrgChartEndpointTest` (5) and `ProjectEndpointTest` (3). *"The one place in this model where adding a grant REMOVES a permission"*: a union renders the colleague you just demoted to Viewer as still a Test Lead. **The `ProjectEndpointTest` failures are the interesting third** — they are the evidence that the helper is genuinely shared across the module boundary rather than merely extracted. |
| `§241 OC-6` | In `EffectiveRoleNames.stronger`, return `existing` unconditionally — "first row wins" | **Red** — `strongerWinsRegardlessOfRowOrder` and `theStrongerOfTwoGrantsWins`. Two grants on one project, one string on the wire, and *"which role is stronger" must be derived rather than ranked by name*. Row order is a plausible-looking answer that changes between runs, which is why that test drives **both** orders. |
| `§241 OC-7` | In `IdentityProjectAccess`, point `effectiveProjectRoleNames` at a private copy of the override instead of `EffectiveRoleNames` | Expected **GREEN**. ⚠️ **Recorded as the gap.** The copy and the shared helper produce identical data *today* — nothing can distinguish them, which is exactly the drift the extraction prevents. The real check is the **next** change to §A1.1, and `OC-5` above is what will fail if the copy comes back. Same shape as `§210 DQ-3`. |
| `§241 OC-8` | In `OrgChartService.companyRolesByPerson`, filter on `role.assignable` instead of on `Role.COMPANY_MEMBER_ID` | Expected **GREEN** today, and recorded as a gap. Both spellings drop the baseline, so `theBaselineIsNotABadge` cannot tell them apart. It breaks the day a non-assignable COMPANY-scope role is a real fact about a person — the baseline-specific-vs-general conversion `V40` §2 and `V41` §1 name as the one-line "cleanup" that breaks four unrelated sites. |

## §241 amendment (2026-08-17) — `V67`, the index T5 found

**None run** (§65). One row, and it is recorded as a **gap** rather than as a check, because that is
the honest answer: this repository has no observer for a query plan.

| # | Mutate | Expect |
|---|---|---|---|
| `§241 IX-1` | Drop `team_membership_user_idx` (the whole of `V67`) and re-run the backend suite | Expected **GREEN — every one of the 3082 tests.** ⚠️ Recorded as the **gap, not as a pass.** An index changes no result, only a plan, and nothing in this repository asserts on a plan: there is no performance lane, a timing assertion would be flaky on shared CI, and Testcontainers seeds tens of rows where the finding needs tens of thousands. So the index's *presence* is gated (Flyway + `SchemaConventionTest.companyIdLeadsCompositeIndexes` on its column order) and its *value* is not. The evidence that it is worth having is the `EXPLAIN (ANALYZE, BUFFERS)` output in plan §6.1, taken as `tcms_app` with `app.current_company` set, and re-deriving it means rebuilding that container — the method is written down there for exactly that reason. **If this index is ever removed, no test will tell you.**  ✅ **RUN 2026-08-23 (wave S) — GREEN.** ZERO-AS-PREDICTED. Scoped over `SchemaConventionTest`, `SchemaCommentTest`, `OrgChartEndpointTest` and `PermissionEvaluatorTest` — the four places that could plausibly have noticed — and not one red. **The registered gap is confirmed by measurement**: an index changes a plan, not a result, and `companyIdLeadsCompositeIndexes` has nothing to say about an index that is *absent*. (Run as `V67`'s body replaced by `SELECT 1;` rather than as a deleted file, so the revert stays by exact path.) |

## §242 amendment (2026-08-18) — the audit log reader

**None run** (§65). The suite these rows predict against **is** green: `./gradlew build` passed at
**3097 tests** after `shanks` came back mid-slice. Two of the rows below were effectively driven by
accident rather than by design, and are marked where they are — the first implementation of the
repository failed fourteen of fifteen arms on an HQL type-inference defect, and the fixture for the
unresolvable actor could not be inserted at all. See handoff §242.

| # | Mutate | Expect |
|---|---|---|---|
| `§242 LG-M1` | In `AuditEventRepository.pageForProject`, widen the predicate to `e.projectId = :projectId OR e.projectId IS NULL` | **Red** — `aCompanyScopeActIsAbsentFromEveryProjectLog`. The single most likely edit to this query, because it *looks* like generosity: it folds every member invitation and vocabulary change into every project's log, and the screen still renders. |
| `§242 LG-M2` | Delete `, e.id DESC` from both `ORDER BY` clauses, keeping `e.occurredAt DESC` | **Red** — `theSortIsStableWhenTimestampsCollide`. ⚠️ **Expect this row to be flaky as a mutation check even though the test is not**: whether Postgres actually reorders two equal-key rows between two `LIMIT/OFFSET` reads depends on the plan, so a small fixture may pass by luck. If it comes back green, that is a finding about the *test's* fixture size, not evidence the tiebreaker is unnecessary. ❗ **RUN 2026-08-23 (wave 2) — ZERO, with both tiebreaks deleted.** The row predicted this risk in its own warning and it is the right reading: a finding about the **test's fixture size**, not evidence the tiebreak is unnecessary. `IngestionEndpointTest.SameInstant` is what this fixture has to become. |
| `§242 LG-M3` | In `AuditLogService.forProject`, pass `query.projectId()` instead of `project.getId()` to the repository | Expected **GREEN**, and recorded as the **gap**. Nothing sends `projectId` on the project trail — the SPA strips it and the controller passes null — so the mutation makes the query return nothing only if a caller supplies it. The defect it would open is real (pass the gate on a project you may audit, read the rows of one you may not) and **no test constructs that request**. Closing it wants an arm that sends `?projectId=<other>` to the project path. ❗ **RUN 2026-08-22 — RED, 9. The row predicted GREEN and the gap it registers does not exist.** Nothing sends `projectId` on the project trail, so `query.projectId()` is **null** — and the repository filters on it rather than reading null as *"no filter"*, so the trail returns nothing and **every arm in `AuditLogReadTest` falls**: paging, the actors and the project trail alike. The substitution is caught loudly, not silently. |
| `§242 LG-M4` | In `AuditLogService.forCompany`, replace the `evaluateCompanyWide(userId).has("audit:view")` gate with `true` | **Red** — `aCallerWithoutAuditViewIsRefused`. A Test Lead would read every project's acts through the company trail, including projects they hold nothing on. |
| `§242 LG-M5` | Change `AuditLogService.READ_COMPANY_AUDIT` to `"project:audit"` | **Red** — `aCallerWithoutAuditViewIsRefused`. The two keys are distinguishable only because that test's persona holds exactly one of them, which is what makes the pair worth asserting in a single test. ⚠️ **RUN 2026-08-23 (wave S) — GREEN. ZERO-UNEXPECTED, and the cause is a SCOPE mismatch rather than a catalogue one.** The row is right that a Test Lead holds `project:audit` and not `audit:view` — **at project scope**. This gate is `permissions.evaluateCompanyWide(userId).has(...)`, which answers from the SYSTEM and COMPANY terms only (§43's deliberate design), and there **Company Admin's COMPANY-scope role enumerates every project key including `project:audit`** (`V3`, the `…0002` grant) while the Test Lead's PROJECT-scope grant is invisible. So at the scope this gate is evaluated at, the two keys are held by exactly the same principals and neither persona in the test moves. A third sighting of the blind-gate family with a **new** cause: not "the catalogue gives both keys to the same roles", but "the evaluator cannot see the grant that separates them". |
| `§242 LG-M6` | In `AuditLogController.read`, answer `ResponseEntity.ok(emptyPage)` for `Denied` instead of 403 | **Red** — `aCallerWithoutProjectAuditIsRefused`, which asserts the status *and* that the body does not carry the acts. **This is the mutation the whole design is about**: an empty audit trail reads as "nothing happened", and the reader cannot detect the difference. |
| `§242 LG-M7` | In `ProjectService.readGranted`'s caller, gate on the permission before visibility (swap the two arms) | **Red** — `anUnreachableProjectIsNotDistinguishableFromAMissingOne`, which asserts 404 on a project the caller cannot see. 403 there confirms the project exists, which is §6t's oracle. |
| `§242 LG-M8` | In `AuditLogService.render`, drop `actorTokenId`/`actorTokenName` from `AuditEventView` and serve only the person | **Red twice** — `aTokenDrivenActNamesBothThePersonAndTheCredential`, and then `ContractConventionTest.pairedSchemasAndRecordsNameTheSameMembers` on the two contract-only members. The second failure is the more useful one: it is what stops the wire shape and the contract drifting apart without anybody driving the screen. |
| `§242 LG-M9` | In `AuditLogService.nameOf`, return `"Unknown"` instead of `null` for an unresolvable actor | **Red** — `anUnresolvableActorLosesOnlyTheirName`. A placeholder is the screen inventing a fact; the contract declares the property nullable precisely so the client owns that copy. ⚠️ **Note what that test's fixture had to become**: `audit_event.actor_user_id` carries a FOREIGN KEY to `app_user`, so an invented id cannot be recorded. The actor is a real account with no membership — the ACCOUNT always exists and the MEMBERSHIP is what resolves the name. |
| `§242 LG-M10` | Filter `AdminDirectoryService.peopleById` to `status != REMOVED` | **Red** — `anOffboardedActorKeepsTheirName`, and ⚠️ **this is the row that corrects the plan.** The plan for this slice *expected* a removal to null the name and specified a test asserting it. It does not, offboarding marks rather than deletes, and the behaviour the code has is the one an audit trail wants: "who did this" must not become unanswerable the day somebody leaves. The mutation would also silently change the org chart and the directory, which is why it is worth a row. ✅ **RUN 2026-08-22 — RED, exactly 1: `anOffboardedActorKeepsTheirName`.** MATCH, and the correction this row makes to its own slice's plan is backed by the measurement. |
| `§242 LG-M11` | Remove `@Transactional(readOnly = true)` from `AuditEventRepository` | **Red** — `RepositoryTransactionTest`, which derives its list from the live context. The zero-rows-under-RLS defect, fail-closed and silent. |
| `§242 LG-M12` | Delete the two `AuditEvent` entries this slice removed from `ArchitectureRulesTest.READERLESS_BY_DECISION` — i.e. put them back | **Red** — `everyExemptionStillExempts`. `containsExactlyInAnyOrderElementsOf` means a stale exemption fails the build, which is §162's rule and the reason removing them was part of this slice rather than a later tidy-up.  ⚠️ **RUN 2026-08-23 (wave S) — RED, exactly 1 — but NOT the test the row names.** The red is `everyMappedColumnHasAReader`, the rule that owns `READERLESS_BY_DECISION` via `containsExactlyInAnyOrderElementsOf`. `everyExemptionStillExemptsSomething` is `SchemaCommentTest`'s, about *comment* exemptions, and is a different check in a different file. The property the row asserts holds; the citation was wrong. |
| `§242 LG-M13` | Drop `AuditEvent` from `ContractConventionTest.RESPONSE_RECORDS` | **Red** — `everyNameMatchedRecordIsPairedOrExplained`. `AuditEventView`'s *name* matches the schema, so leaving it unpaired is a schema compared to no server type in either direction — the hole §170 closed.  ⚠️ **RUN 2026-08-23 (wave S) — RED, 2.** MORE than the 1 predicted, and both are the same hole from opposite ends: `every object schema is paired with a record, or says why it has none` and `every schema a record is named for is paired, and every pairing is a real schema`. §170's closure is asserted in both directions, not one. |
| `§242 LG-M14` | ⚠️ **A gap, not a mutation.** Run the project trail against a company with a large `audit_event` table and read the plan | **No observer exists.** `V1` indexes `(company_id, target_type, occurred_at DESC)` and `(company_id, actor_user_id, occurred_at DESC)`; the project trail's own predicate is `(company_id, project_id, occurred_at DESC)` and **there is no index for it**. This slice deliberately takes no Flyway version — the plan reserves `V68` for slice 3 and `V69` for slice 5, and renumbering is refused — so the index is recorded here as owed rather than added. `§241 IX-1`'s note applies verbatim: an index changes no result, only a plan, and nothing in this repository asserts on a plan. **If this becomes slow, no test will tell you.** |

---

## §243 amendment (2026-08-21) — review-workflow notifications

**None run** (§65). The suite these rows predict against **is** green — see handoff §243 for the
measured counts. Four new event names, three payload pairs, four handlers and one port; no
migration and no permission moved.

**The row to read first is `RN-M7`.** It is the mutation this slice's whole naming argument is
about, and it is **expected GREEN** — which makes it the recorded gap rather than a check.

| # | Mutate | Expect |
|---|---|---|---|
| `§243 RN-M1` | In `ReviewService.addAssignee`, record the occurrence unconditionally instead of only when `store` returned true | **Red** — `ReviewEndpointTest.reAskingAnnouncesNothing`. The idempotent re-ask writes no row, so an event there announces a second time that a colleague was asked once, and at-least-once delivery means the channel sees both. |
| `§243 RN-M2` | In `ReviewService.apply`, swap the two arms of the verdict switch (`LGTM` → `REVIEW_CHANGES_REQUESTED` and vice versa) | **Red** — `ReviewNotificationTest.theVerdictsAreSeparatelySubscribable`, whose channel subscribes to exactly one of the two. This is the mutation a single "a peer spoke" event name would have made **undetectable**, because both verdicts would arrive on the same subscription. |
| `§243 RN-M3` | In `ReviewService.moved`, move the `outbox.record` call out of the `to == APPROVED` branch so it also fires on the rejection/withdrawal arm | **Red** — nothing today. ⚠️ **Recorded as a gap.** `ReviewNotificationTest` asserts the approval arm delivers and asserts silence when review is *off*; it has **no arm driving a rejection with a channel subscribed**, so a sign-off announced for a rejected artifact would ship. Closing it is one arm: reject, dispatch, assert zero `review.approved` deliveries. It is the shape `onlyCompletionIsAnOccurrence` already has for runs. |
| `§243 RN-M4` | Replace `review.getSubjectVersionNo()` in `ReviewApprovedEvent` with the artifact's current version | **Red** — `ReviewNotificationTest.theApprovalIsDelivered` asserts `"subjectVersionNo": 1`. The fixture approves without editing in between, so ⚠️ **this row is weak by construction**: the two numbers are equal there. A stronger arm edits the plan while it is in review and asserts the payload still names the submitted version, which is §6g's diff-since-approval case. |
| `§243 RN-M5` | In each notifier, mint `UUID.randomUUID()` for the `dispatch` event id instead of passing `payload.eventId()` | **Red** — `ReviewNotificationTest.theDeliveryIsKeyedOnTheOccurrence`. Two delivery rows instead of one, which is the duplicate message the `(company_id, channel_id, event_id)` key exists to prevent. This is the master plan's first named trap. |
| `§243 RN-M6` | In `ReviewSubjects.describe`, return `Optional.empty()` unconditionally | **Red** — `ReviewRenderingTest` covers the *rendering* of an absent subject but not the resolution, so the arm that actually reddens is `ReviewNotificationTest`'s message assertions only if they read the delivered text. ⚠️ **Check when running**: if the e2e asserts only the payload and the delivery row, this mutation is **green**, and the honest fix is an arm that reads the rendered message out of `notification_delivery`'s error text or drives a reachable webhook. |
| `§243 RN-M7` | ⚠️ **A gap, not a mutation.** Bulk-approve 200 cases in a review-enabled project with one channel subscribed to `review.approved` | **Expected GREEN, and this is the known hazard shipped knowingly.** 200 occurrences, 200 delivery attempts. Nothing in the suite bounds it, because each message is true and names a different subject. What would close it is a subscriber-side digest, which is a slice of its own; see `OutboxEvents#REVIEW_APPROVED` for why coalescing here would be untruthful instead. **Run this row to get the real number before anybody subscribes a production channel.** |
| `§243 RN-M8` | Delete `PlanReviewSubjects` (or make it claim `CASE`) | **Red** — `ReviewSubjectDirectoryTest.theIndexIsTotal`, both arms. **Verified by doing it**: the mutation was applied and reverted during the slice, and both arms reddened. This is the check that catches a fourth `ReviewSubjectType` added by somebody who cannot see the three implementations. |
| `§243 RN-M9` | Remove `@Transactional(readOnly = true)` from any of the three `*ReviewSubjects` beans | **Red** — the read runs on the dispatcher's thread, which holds no transaction, so the RLS-scoped query fails or returns nothing. ⚠️ Which of the two it is has **not been observed**; `RepositoryTransactionTest` covers repositories rather than these components. |
| `§243 RN-M10` | In `ReviewRequestedNotifier`, resolve the assignee with `notices.nameOf(payload.assigneeTeamId())` for the team arm | **Red** — nothing today, ⚠️ **another gap**: `ReviewEndpointTest.askingATeamIsAnOccurrence` asserts the *payload*, and `ReviewRenderingTest.aTeamIsAReviewer` passes the name in already-resolved. **No test drives a team request through the handler**, so a team resolved as a person — which yields null and renders "someone who is no longer on the instance" — would ship. One e2e arm closes it. |
| `§243 RN-M11` | In `NotificationTexts.reviewEndorsed`, change the title to `"Approved by " + who` | **Red** — `ReviewRenderingTest.theEndorsementIsNotASignOff`, which asserts the title contains no form of "approv". The whole naming argument of this slice, asserted on the one string a phone actually shows. |
| `§243 RN-M12` | Delete the `submitter != null` guard in `NotificationTexts.verdict` so the fact is always added | **Red** — `ReviewRenderingTest.nobodyIsNotAPerson`. A fact saying the artifact was submitted by nobody invites the reader to look for a person who was never recorded. |

---

## §244 amendment (2026-08-22) — the duration that was already there

⚠️ **Read this before the table.** Every other section here opens by saying the suite behind it is
green. **This one cannot.** Area A was built under a directive to run no gates at all, so
`./gradlew build` has **not been run** against this code. Two targeted classes were run before that
directive arrived — `ExecutionDurationTest` (4 arms) and `IngestionEndpointTest` — and reported
BUILD SUCCESSFUL. Nothing else. The rows below are predictions against unrun code, not against a
measured baseline.

**No migration.** `test_execution.duration_ms BIGINT` shipped in `V1__initial_schema.sql:845`. The
slice is a write path for a column that had **no writer of any kind** since 2026-07-30 — the reads
(`TestExecutionSummaryView`, `TestExecutionView`, `CaseHistoryView`, `ExecutionSource`'s copy into
`execution_fact`, `RunSummaryReport`'s sum) all shipped years-of-commits ago and all answered null.

**The row to read first is `ED-M6`**, the one that is expected **GREEN** and is therefore the
recorded gap rather than a check.

| # | Mutate | Expect |
|---|---|---|---|
| `§244 ED-M1` | In `TestExecution.record`, guard the duration assignment: `if (durationMs != null) this.durationMs = durationMs;` | **Red** — `ExecutionDurationTest.omittingTheDurationClearsIt`. The single most likely edit to this method, because it *looks* like defensiveness: it makes the field un-clearable and silently diverges from `comment`, which sits on the same form and takes the opposite reading of the same absence. |
| `§244 ED-M2` | Guard it on truthiness instead: `if (durationMs != null && durationMs > 0)` | **Red** — `ExecutionDurationTest.zeroIsADurationAndNotAnAbsence`. A run of sub-second automated checks recorded by hand is the case where this loses real data, and it is invisible to any assertion written about a nonzero duration. |
| `§244 ED-M3` | In `ExecutionService.validate`, `return errors` immediately after putting the `durationMs` fault | **Red** — `ExecutionDurationTest.aNegativeDurationIsAFieldError`, whose payload carries a second fault and asserts **both** keys come back. The caller is a tester at the end of a test, which is the worst moment to be sent round a loop for one message at a time. |
| `§244 ED-M4` | Delete the `request.durationMs() < 0` check entirely | **Red** — the same test's 400 assertion. ⚠️ **Note what does NOT catch it**: `openapi.yaml` declares `minimum: 0`, and nothing in this repository enforces an OpenAPI `minimum` at runtime. The contract is documentation here; the check is the check. |
| `§244 ED-M5` | In `IngestionService.write`, pass `null` where `result.durationMs()` now goes | **Red** — `IngestionEndpointTest.theReportedDurationIsRecorded`. ⚠️ **This is the mutation that restores the state the product actually shipped in for a year**, which is why it earns a row: the parsers always recovered the number and it always went into the job's payload JSON instead of the column. |
| `§244 ED-M6` | ⚠️ **A gap, not a mutation.** Record durations by hand across a run, then generate that run's **Run Summary** report and read the "recorded duration" line | **Expected GREEN, and this is the recorded gap.** `RunSummaryReport` sums `execution_fact.duration_ms`, which `ExecutionSource` refreshes from `test_execution` on a schedule and on `execution.results_changed`. **No test in this repository asserts that a hand-recorded duration reaches the report** — the reporting suite's fixtures predate any writer, so they were all summing zero. The whole "how long did this cycle take last time" question this area exists to answer runs through that path, and nothing observes it end to end. Closing it is one arm in `reporting` that records a duration, refreshes, and reads the sum. |
| `§244 ED-M7` | Drop `duration_ms` from `ExecutionSource`'s `ON CONFLICT DO UPDATE` set-list | **Red** — nothing today. ⚠️ **A second gap.** The column is in that seven-column set-list already, so an **amended** duration corrects itself on the next tick and a dropped entry would freeze the first value ever recorded. Until now that was unobservable because the value was always null, which is exactly why no test covers it. Same fix as `ED-M6`, one more assertion. ✅ **RUN 2026-08-23 (wave 2) — RED, exactly 1: `an amended result IS corrected on the next refresh`.** The row predicts *red — nothing today* and calls itself a second gap. **It is not a gap** — `ExecutionFactRefreshTest`'s set-list test already covers it. |
| `§244 ED-M8` | In `ExecutionPage`, remove `durationIsNonsense` from the verdict buttons' `disabled` | **Red** — nothing. ⚠️ **Frontend gap, recorded rather than closed.** `ExecutionPage.tsx` has **no component test in this repository** and none was invented for this slice. The behaviour is `§244 ED-3` in the browser queue. `frontend/src/lib/format.test.ts` covers `parseDurationSeconds` returning `undefined` for unreadable text, which is the half that *can* be tested without a harness. |
| `§244 ED-M9` | In `ExecutionGrid.mark` (and the grid-mode Blocked dialog), delete the `durationMs: detail.durationMs` line | **Red** — nothing. ⚠️ **The most dangerous gap in this slice.** Both call sites carry a field they show no control for, purely because the body is total state; omitting it clears a duration recorded on another screen. It is `§244 ED-5` in the browser queue and has no automated observer at all. |

---

## §246 — the company data export (`export-company`)

**Area E of `docs/plans/2026-08-18-unplanned-gaps.md`, stepped into
`docs/plans/2026-08-18-gap-e-company-data-export.md`.** Operator CLI command, no contract change, no
migration, no frontend surface. Fixture prefix `7c`.

**⚠ NOTHING IN THIS AREA HAS BEEN GATED.** The lane ran under the batch-2 standing directive to run
no tests: `./gradlew build` was not run, no targeted class was run, and the assertions below are
written from the code rather than from a green suite. The rows are additionally unrun per §65.

**The row to read first is `CE-M1`.** It is the defect the master plan named — the whole area is
shaped around it — and if it comes back green the entire test class is decorative.

| # | Mutate | Expect |
|---|---|---|---|
| `§246 CE-M1` | Move `CompanyContext.open(company.getId())` out of `ExportCompanyCommand.run` and make it the first line of `ExportCompanyService.export` | **Red** — `ExportCompanyTest.everyWalkedTableMatchesADirectCountTakenAsSuperuser`, with **every** walked table at 0, and `theExportRefusesWithNoCompanyContext`. **The headline mutation of the area.** `CompanyAwareTransactionManager` reads the context in `doBegin`, so a context opened inside the `@Transactional` method arrives after the GUC decision: every policy evaluates `company_id = NULL`, and the archive opens, parses and is empty. |
| `§246 CE-M2` | Drop `isolation = Isolation.REPEATABLE_READ` from `ExportCompanyService.export` | **Expected GREEN**, ⚠️ **and recorded as a gap.** Nothing in the suite mutates a company *during* an export, so the snapshot argument has no observer. Closing it needs a concurrent writer mid-walk, which is a test of its own; the argument for the isolation level is in the method's javadoc, where a reader deciding to "simplify" it will meet it. |
| `§246 CE-M3` | Remove `key_sequence` from `ExportCompanyService.SENTINEL_TABLES` | **Red** — `ExportCompanyTest.everySentinelTableIsNamedInTheRefusal`, which asserts the refusal message names **all five** rather than merely that it aborted. That arm exists because a check written about "did it abort" passes with four of the five sentinels decorative. |
| `§246 CE-M4` | In `ExportRedactions.DROPPED_COLUMNS`, delete the `app_user → password_hash` entry | **Red twice, and the two reds are the point** — `ExportRedactionSweepTest.everySuspectColumnIsRedactedExcludedOrDeliberatelyKept` (reads the catalogue) **and** `ExportCompanyTest.noRedactedValueAppearsAnywhereInTheArchive` (reads the archive's bytes for the digest itself). One is a claim about the list; the other is a claim about what actually travelled.  ⚠️ **RUN 2026-08-23 (wave S) — RED, 4.** MORE than the 2 predicted, and the extras strengthen the row's own point rather than diluting it. Both named arms fell — `every suspect column is redacted, in an excluded table, or deliberately kept` (the catalogue) and `no redacted value appears anywhere in the archive` (the bytes) — plus `the five known secrets are on the dropped list, by name` and `the manifest names every redaction, every exclusion and the Flyway head`. **Four independent observers of one deleted entry**, at the list, at the manifest and at the archive's contents. *(The runner recorded `ERROR`: the run completed on shanks and the SSH connection dropped afterwards. The result was recovered from the run's own JUnit XML, `failures="2"` in each of the two classes.)* |
| `§246 CE-M5` | Add a credential-shaped column that did not exist when the sweep was written: a scratch migration with `ALTER TABLE project ADD COLUMN webhook_secret TEXT` | **Red** — `ExportRedactionSweepTest.everySuspectColumnIsRedactedExcludedOrDeliberatelyKept`. **This is master task 34's stated acceptance and the row that proves it**: *"a test that fails if a new secret-bearing column appears unredacted."* Revert the **migration file**, not just the column — a `flyway_schema_history` row for a deleted script fails the next boot. ✅ **RUN 2026-08-22 — RED, exactly 1: `everySuspectColumnIsRedactedExcludedOrDeliberatelyKept`.** MATCH. **Master task 34's stated acceptance, demonstrated**: `ALTER TABLE project ADD COLUMN webhook_secret TEXT` fails the sweep on the very next run. |
| `§246 CE-M6` | In `ExportTables.EXCLUDED`, add `test_case` with a made-up reason | **Red** — `ExportTablesTest.everyRlsProtectedTableIsWalked`, and `everyTableIsEitherWalkedOrExcludedForAReason`'s "never both" half. The failure mode with no symptom: a client's cases quietly left out of their own export, in an archive that still opens and still parses. |
| `§246 CE-M7` | Make the rename unconditional — move `Files.move(partial, out, …)` above the `try`'s failure paths, or delete both `deleteQuietly(partial)` calls and rename in a `finally` | **Red** — `theExportRefusesWithNoCompanyContext`, `theExportRefusesWhenTheContextNamesAnotherCompany` and `aCompanyWithNoSeededVocabulariesAbortsRatherThanExporting`, all three of which assert **no file exists** at `--out`. The whole "a path that exists is a path whose walk finished" property. |
| `§246 CE-M8` | In `ExportCompanyCommand.report`, `return EXIT_OK` unconditionally | **Red** — `ExportCompanyTest.aRowNamingBytesThatAreGoneIsReportedRatherThanSkipped` asserts `result.wasComplete()` is false, which is what `report` switches on. ⚠️ **Check when running**: the arm asserts the **result**, not the process exit status, because nothing in this suite runs the command as a process. If exit-code coverage matters, that is a gap and the note below says so. |
| `§246 CE-M9` | Rewrite `harvestBlobKeys` to list `<root>/<companyId>/` through `BlobInventory` instead of reading the three columns | **Expected GREEN on the happy path**, ⚠️ **and that is the finding.** `companyBsBytesAreNotInTheArchive` asserts on **entry names**, and beta's key is under beta's own uuid prefix, so a filesystem sweep still gets that arm right by accident. What it would break is invisible to the suite: an orphan left by a crashed `ReportJobRunner` (bytes first, row second, on purpose) would be exported as client data. Closing it needs a fixture with a real orphan asserted absent from the archive. |
| `§246 CE-M10` | Write `manifest.json` **first** instead of last | **Expected GREEN.** ⚠️ Recorded: nothing in the suite kills the process mid-archive. The ordering is a property of a **truncated** archive and only a killed run demonstrates it. |
| `§246 CE-M11` | Delete the `WHERE t.company_id = ?` predicate from the walked-table statement, leaving only the RLS policy | **Expected GREEN for the policied tables and RED for `membership_invitation`**, which carries `company_id` as a target column and **no policy at all** — so `ExportCompanyTest.companyBIsAbsentFromEveryLine` should catch it there. ⚠️ **Check when running**: if it comes back fully green, the belt-and-braces predicate has no observer on the policied tables and the four target-column tables are the only thing holding it in place. |
| `§246 CE-M12` | In `ExportTables.classify`, drop the `unclassified` refusal and silently skip instead | **Red** — nothing in the suite today, because the catalogue currently has no unclassified table. ⚠️ **A gap by construction**: `ExportTablesTest.everyTableIsEitherWalkedOrExcludedForAReason` asserts the *partition*, which stays true, and the runtime throw exists for the deployment running a build older than its database — a case no test can reach. Closing it needs a temporary table created in a fixture and asserted to make `classify` throw. |
| `§246 CE-M13` | Remove the second `requireGucMatches` call (S2b, at the end of the walk) | **Expected GREEN.** ⚠️ **Recorded, and it is the honest statement about S2.** Nothing in the suite makes the walk span two transactions, so only the *first* GUC read has an observer. S2b costs one statement and is the only in-production check that would catch a walk which lost `SET LOCAL` partway; there is no cheap way to redden it from a test. |

**No frontend browser rows.** This area has no browser surface at all — it is an operator CLI
command — so `frontend-browser-checks.md` gains nothing. Stated rather than left silent: "nothing to
drive, and here is why" is a legitimate outcome and a silent skip is not.

**One operator-level check that is neither a mutation nor a browser row**, recorded here because
there is nowhere else for it: nothing in this repository runs `export-company` as a **process**, so
the four exit codes and the usage text have no automated observer. The runbook's §7a is the
instruction; a real run against a provisioned instance is the check, and it is owed.

---

## §245 — bulk execution assignment (area B of the gaps plan)

⚠️ **Unrun, and the code behind them is ungated.** No `./gradlew build` and no targeted class was
run against area B; the arms named below exist in `ExecutionBulkAssignTest` and have never been
executed. A row saying "Red — arm X" is therefore a claim about what the arm *asserts*, not a
measurement of it failing.

Ids are `§245 BA-M*`. **The section number is `§245`, not the `§244` the stepped plan wrote** —
area A took `§244`.

| Row | Mutate | Expect | Result (2026-08-28, `shanks`) |
|---|---|---|---|
| `§245 BA-M1` | ⚠️ **The mutation with the worst consequence in this area.** In `ExecutionBulkAssignRequest.previewing`, return `dryRun != null && dryRun` — so an absent `dryRun` means *commit* | **Red** — `ExecutionBulkAssignTest.aDryRunChangesNothing`, whose second half sends the body with `dryRun` **absent** for exactly this. A client that omits the field would otherwise commit an act nobody confirmed, over up to 500 rows, and the response would look correct. |
| `§245 BA-M2` | In `ExecutionBulkAssignService`, mint the `bulkOperationId` **inside** the row loop instead of once per act | **Red** — `oneActIsOneBulkOperationId`, whose first assertion is that the twelve ids are all **equal**. A per-row id renders as twelve one-row groups in the trail, which is exactly the flood the column exists to prevent, with every other assertion in the class still green. ✅ **RUN 2026-08-22 — RED, exactly 1**, the named test, with every other assertion in the class green exactly as the row says. MATCH. |
| `§245 BA-M3` | In `destinations`, round-robin over `request.executionIds()` instead of the run-ordered `ordered` list | **Red** — `theSelectionIsASetAndTheRunDecidesTheOrder`, which sends the same selection shuffled. Two coordinators selecting the same rows would otherwise get different splits and **neither could detect it**. |
| `§245 BA-M4` | Sort `assigneeIds` before distributing (`assignees.stream().sorted()`) | **Red** — `distributeRoundRobinsInRunOrder`'s remainder half. The caller's order is a statement about who takes the extra rows; a server that sorted it would silently overrule the coordinator with nothing on any screen to show it had. |
| `§245 BA-M5` | Treat an id that names no execution in this run as `Outcome.NotFound` instead of a `SkippedRow` | **Red** — `aForeignExecutionIdSkipsOneRow`. This is the "simplification" a reader is most likely to make: a stale selection is an ordinary event, and refusing four hundred rows over one of them is worse than the problem. |
| `§245 BA-M6` | Delete the `Objects.equals(execution.getAssigneeId(), to)` branch, so an unchanged row is written and audited anyway | **Red** — `aRetryChangesNothingAndWritesNoSecondOperation` and `anAlreadyAssignedRowIsUnchanged`. ⚠️ **This is the mutation that would make the trail unreadable while every response assertion stayed correct**, and it is the one that destroys the endpoint's answer to idempotency. |
| `§245 BA-M7` | Remove `@Transactional` from `ExecutionBulkAssignService.assign` | **Red** — `aStrangerIsAFieldErrorAndNothingIsWritten`'s "nothing was written" half. ⚠️ **Check when running**: validation runs before any save, so the mutation may stay green unless a fault is raised *mid-loop*. If it does, that is a gap and the honest closure is an arm that faults partway through — noted rather than hidden. |
| `§245 BA-M8` | In `validate`, use `people.resolve(...)` instead of `people.isActiveMember(...)` for `payload.assigneeIds` | **Red** — `aStrangerIsAFieldErrorAndNothingIsWritten`, both halves. `app_user` is instance-level (ERD §0.1's exception list), so "the account exists" accepts a user id belonging to **another client entirely** and puts a stranger's name on this company's screens. The `INVITEE` half additionally proves an unaccepted invitation is not membership. |
| `§245 BA-M9` | In `ExecutionAssignmentAudit.action`, return `"execution.bulk_assigned"` from `bulkRow` | **Red** — `theTrailReadsTheSameWhicheverDoorWasUsed`. A separate verb splits every "who took this off Ana" query in two and leaves `auditActionLabel`'s curated map with two entries meaning one thing. |
| `§245 BA-M10` | Move the `run.isClosed()` check **after** `validate` in `apply` | **Expected GREEN**, ⚠️ **and recorded as a gap.** `aClosedRunIsFrozen` sends a valid body, so the ordering has no observer. Closing it needs an arm sending an invalid body against a closed run and asserting **409, not 400** — the rule being that a field somebody could fix is not worth reporting to a caller whose save cannot succeed at all. |
| `§245 BA-M11` | Delete the `AuditEvent.bulkOperationId` writer — make the eight-argument `record` overload ignore its last parameter | **Red twice, and the second red is the point** — `oneActIsOneBulkOperationId`, and `ArchitectureRulesTest.everyMappedColumnHasAWriter`, which now reports the column as writerless while `WRITERLESS_BY_DECISION` no longer names it. §162's both-directions property, arriving from the direction this area created. |
| `§245 BA-M12` | In `inRunOrder`, drop the `.thenComparing(TestExecution::getId)` tiebreak | **Expected GREEN**, ⚠️ **and recorded as a gap.** Every fixture case resolves its pin, so no two rows share a `keySeq` and the tiebreak never decides anything. It is there because an unresolvable pin sorts at `Long.MAX_VALUE` and **two** of them would make the split depend on the row order the database happened to return. Closing it needs a fixture with two executions whose pinned versions are unreadable, which is a test about RLS rather than about assignment. ✅ **RUN 2026-08-22 — ZERO, as predicted: the gap is real.** Every fixture case resolves its pin, so no two rows share a `keySeq` and the tiebreak never decides anything. |

---

## §247 — scheduled runs (area C of the gaps plan)

⚠️ **Unrun, and the code behind them is completely ungated.** Area C was built under the batch-3
no-gates directive: no `./gradlew build`, no `./gradlew test`, and not one targeted class.
`CronScheduleTest` (7 arms), `RunScheduleEndpointTest` (26) and `RunScheduleFiringTest` (15) exist
and **have never executed**, and neither have the two arms added to `AsyncCompanyContextTest`.
The counts are `grep -c '@Test'` against the files rather than a tally kept by hand, which is the
one thing this repository has got wrong three times inside the check written to end hand-kept
tallies. A row
below saying "Red — arm X" is a claim about what that arm *asserts*, not a measurement of it
failing.

Ids are `§247 SK-M*`. `SK` was grepped against every row id in this file and in
`frontend-browser-checks.md` before minting.

**The area's one migration is `V68`** and it is the only one in the master plan. It creates
`run_schedule` and swaps `test_run_source_check` for a three-value CHECK.

| Row | Mutate | Expect | Result (2026-08-28, `shanks`) |
|---|---|---|---|
| `§247 SK-M1` | ⚠️ **The mutation this whole area is shaped around.** In `RunCreateService.apply`, replace `UUID actor = source == RunSource.SCHEDULED ? null : userId;` with `UUID actor = userId;` | **Red** — `RunScheduleFiringTest.aFiredScheduleCreatesARunWithNoCreator` (the `created_by IS NULL` half) and `theAuditRowForAScheduledRunNamesNobody` (both actor columns, at the row and through `identity.AuditLogService`). This is the mutation that writes *"Ana created TR-91"* about a night Ana did nothing — a lie that reads correct on every screen, and the reason `§242 LG-5` was recorded as not drivable until this area landed. ✅ **RUN 2026-08-22 — RED, exactly the 2 predicted.** MATCH — the mutation this whole area is shaped around is pinned at the column **and** at the reader. |
| `§247 SK-M2` | In the same line, keep the null but ALSO pass `actor` to `projects.writeGranted` and to `gate(...)` | **Expected RED at the compile boundary or at runtime** — a null caller has no grants, so `writeGranted` answers `Denied` and the fire records `LOST_GRANT` for every schedule. `anOwnerWhoLostTheGrantStopsTheSchedule` would go green for the wrong reason and `aFiredScheduleCreatesARunWithNoCreator` red. ⚠️ **Recorded because the two halves look interchangeable in the diff**: authority is `userId`, attribution is `actor`, and one line carries both. |
| `§247 SK-M3` | In `RunScheduleFiringService.fire`, move `scheduleService.recomputeNextFire(schedule, now)` inside the success branch | **Red** — `aFailedFireStillAdvances` and `anArchivedProjectStopsTheFireAndSaysSo`'s third assertion. A schedule that fails once and never advances is re-fired **on every tick for ever**; a broken schedule becoming a hot loop is the worst outcome available in this area, and every other assertion in the class stays green under it. |
| `§247 SK-M4` | Key the idempotency string on `now` instead of on `slot` — `"schedule:" + id + ":" + now` | **Red** — `theSecondFireOfOneSlotMakesNoSecondChecklist`. Two application instances polling one tick mint two keys a millisecond apart and the run is created **twice**: a second checklist, one tester's results split across two runs, and two pass rates over denominators nobody chose. |
| `§247 SK-M5` | In `RunScheduleService.recomputeNextFire`, advance from `schedule.getNextFireAt()` instead of from `from` — i.e. catch up | **Red** — `aMissedSlotIsNotCaughtUp`, whose `nextFireAt` assertion fails immediately. ⚠️ **The run-count half of that arm is expected to stay GREEN** under this mutation, because one `fire` call still creates one run: the loop that would produce a week of backdated runs is the poller's, and the poller is not driven here. **That gap is real and is `SK-M6`.** |
| `§247 SK-M6` | ⚠️ **Not a mutation — a hole.** Nothing in the suite drives `RunScheduleScheduler.fireDueForAllCompanies` at all | **No red is available.** The poller is disabled in `IntegrationTestBase` (it CREATES RUNS, so a tick landing mid-suite would materialise checklists against whichever fixtures had seeded), and its own content is the company loop, which `AsyncCompanyContextTest` covers at the boundary rather than through the bean. What is unobserved is the loop's `continue`: one company throwing must not cost every company after it. Closing it needs a context with the property on and a company whose fire throws. Written down rather than fixed. |
| `§247 SK-M7` | Delete the archived check from `RunScheduleFiringService.attempt` | **Red** — `anArchivedProjectStopsTheFireAndSaysSo`. A job that quietly resurrects archived work is worse than a job that stops, which is master task 20's decision. ⚠️ **Note what this does NOT prove**: archiving blocks no other write in this product, so the rule belongs to schedules alone and there is no second observer anywhere. |
| `§247 SK-M8` | Make `RunScheduleEdit.validate` refuse a `suiteId` that names nothing live, on `RunEdit`'s terms | **Red** — `RunScheduleEndpointTest.Validation.aDeadScopeArmIsNotAFieldError`. ⚠️ **This is the mutation a reader is most likely to make, because it looks like a missing check.** A schedule is evaluated over months; refusing the edit because a suite was deleted last quarter makes the schedule uneditable — **including the edit that would have removed the dead arm**. The emptiness belongs to a night, and `last_fire_error` is where it lands. |
| `§247 SK-M9` | In `RunScheduleEdit.validate`, swap `people.isActiveMember(...)` for `people.resolve(...)` on `assigneeId` | **Red** — `anAssigneeFromAnotherCompanyIsRefused`. `app_user` is instance-level (ERD §0.1's exception list), so "the account exists" accepts a user id belonging to **another client entirely** — and every run the schedule ever makes would then assign this client's testing to a stranger, nightly. |
| `§247 SK-M10` | Remove `@Transactional(readOnly = true)` from `RunScheduleRepository` | **Red** — `RepositoryTransactionTest`, which derives its list from the live context, plus every arm in both schedule classes. Spring Data gives a transaction only to the inherited CRUD methods, so the three declared queries would run with no company context and RLS would answer **zero rows, silently**. |
| `§247 SK-M11` | Do the cron arithmetic in UTC — in `CronSchedule.nextAfter`, use `moment.atZone(ZoneOffset.UTC)` | **Red** — `CronScheduleTest.aWeekdayMorningIsWallClockInTheSchedulesZone`. Right for half the year and silently an hour out for the other half, with nothing on any screen to explain it. That arm asserts the local hour on **both** sides of a DST boundary *and* that the two instants carry different offsets, because the local-hour assertion alone would also pass for a UTC implementation that converted back for display. |
| `§247 SK-M12` | In `CronSchedule.nextAfter`, use an inclusive next (return `moment` when it matches) | **Red** — `theNextOccurrenceIsStrictlyAfterTheMomentAskedAbout`, and it is the arm that keeps `SK-M3`'s hot loop closed from the other side: a schedule fired at its own slot would recompute to that same slot and be due again on the very next tick, for ever. |
| `§247 SK-M13` | Make `CronSchedule.parse` accept five fields (Unix cron) | **Red** — `anUnparseableExpressionIsRefusedRatherThanStored`'s last assertion, and `RunScheduleEndpointTest.Validation.fiveFieldCronIsRefused`. Accepting it shifts every field by one, so `0 7 * * MON-FRI` fires at 7 **seconds** past every minute of an hour nobody chose — and the schedule looks correct in the list. |
| `§247 SK-M14` | In `RunScheduleFiringService`, treat `Outcome.Replayed` as a failure and record a `lastFireError` | **Red** — `theSecondFireOfOneSlotMakesNoSecondChecklist`'s third assertion. A replay is another instance winning the tick: the run exists and is the right one, and putting a red sentence on a schedule that worked is the failure mode of the honesty column itself. |
| `§247 SK-M15` | In `RunSchedule.recordFireFailure`, also set `lastFiredAt = now` | **Red** — `aFailedFireDoesNotClaimARunHappened`. `last_fired_at` means *when a run last happened*; with no catch-up it is the only honest record of a gap, and a failure that moved it would make the gap invisible in the one place a reader could see it. |
| `§247 SK-M16` | In `RunSchedule.recordFire`, stop clearing `lastFireError` | **Red** — `aSuccessfulFireClearsTheError`. A stale sentence beside a fresh `lastRunId` is a screen saying the schedule is broken while it works — which trains a reader to ignore the field the whole feature depends on. |
| `§247 SK-M17` | Delete `run_schedule`'s `FORCE ROW LEVEL SECURITY` from `V68` (leave `ENABLE`) | **Red** — `SchemaConventionTest.everyCompanyOwnedTableForcesRls`, and `AsyncCompanyContextTest.aFireInOneCompanyCannotSeeAnothersSchedules`. `FORCE` is what subjects the table's own owner to the policy; without it the failure is invisible to any test connecting as a superuser, which is why `IntegrationTestBase`'s three-role split exists. ⚠️ **RUN 2026-08-23 (wave S) — RED, 1 of the 2 predicted.** `everyCompanyOwnedTableForcesRls` falls; **`AsyncCompanyContextTest.aFireInOneCompanyCannotSeeAnothersSchedules` stayed GREEN**, with both classes in scope. That is the finding: the test the row leans on to demonstrate the *consequence* does not observe the loss of `FORCE`, because it connects as `tcms_app` and plain `ENABLE` already isolates a non-owner role. **Only the convention test sees it** — which is exactly the argument for `IntegrationTestBase`'s three-role split, arriving as the reason the split is not enough on its own. |
| `§247 SK-M18` | Reorder `run_schedule_due_idx` to `(next_fire_at, company_id)` | **Red** — `SchemaConventionTest.companyIdLeadsCompositeIndexes`. ⚠️ **Nothing measures the index's VALUE**, only its presence and its column order — §241-A's standing gap, and it applies here: remove the index entirely and `companyIdLeadsCompositeIndexes` has nothing to complain about. ✅ **RUN 2026-08-23 (wave S) — RED, exactly 1: `companyIdLeadsCompositeIndexes`.** MATCH, and the row's own warning is confirmed alongside `§241 IX-1` in the same batch: **removing the index entirely reddens nothing**, so what is gated is the column order of an index that exists, never its existence or its value. |
| `§247 SK-M19` | Leave `test_run_source_check` at two values and write `SCHEDULED` anyway | **Red** at the database — every fire fails on the constraint. Recorded because it is the shape a **partially applied** `V68` produces (the table created, the CHECK swap missing), which is not a state Flyway can reach but is exactly what a hand-repaired environment looks like. ✅ **RUN 2026-08-23 (wave S) — RED, 7 — the whole firing class.** MATCH against the row's qualitative prediction: with the `CHECK` swap omitted from `V68`, every fire fails on the constraint at the database. The partially-applied-`V68` shape is reproduced exactly. |
| `§247 SK-M20` | In `RunScheduleService`, spell `"run:create"` again instead of importing `RunCreateService.CREATE_RUN` | **Expected GREEN**, ⚠️ **and recorded as a gap.** Two literals agree today, so nothing reddens; §228's finding is that they stop agreeing the day one moves. There is no rule in this repository comparing two permission literals across two services, which is the same instrument gap `CaseStatusService.KEYS` records.  ✅ **RUN 2026-08-23 (wave S) — GREEN.** ZERO-AS-PREDICTED, and the zero is **analytic rather than empirical**: the two spellings are the same string, so no test could have separated them and the run confirms only that the substitution compiles and behaves. The measurement worth having is the one the row asks for and this repository cannot make — a rule comparing two permission literals across two services — which is the same instrument gap `CaseStatusService.KEYS` records. |
| `§247 SK-M21` | Return `null` from `RunScopeDocument.of` for a `SCHEDULED` request, as `scopeRequestOf` does for `INGESTED` | **Red** — every create arm, at the database: `run_schedule.scope_request` is `NOT NULL`. Recorded because the extraction moved eight lines out of a method whose remaining content is exactly that null arm, and folding the arm back in is the tidy-up that breaks this. |
| `§247 SK-M22` | Truncate `nameFor`'s composed run name from the **back** instead of the front — or not at all | **Expected GREEN**, ⚠️ **and a gap.** No fixture names a schedule near `RunEdit.MAX_NAME`, so an over-long composed name is unobserved. Without the truncation a schedule saved right up to the cap fires into a `400` on `name` and records it as `last_fire_error` for ever — a schedule that saved cleanly and can never run. Closing it needs an arm with a 200-character schedule name. |

---

## §249 — cross-project case search

**Nine rows. `CS-M3` is the point of the area** — that predicate has been a documented survivor
mutation on the project-scoped read since it was written, and this slice is what makes it killable.
Two rows are **expected green and recorded as gaps** rather than as passes.

| # | Mutate | Expect |
|---|---|---|---|
| `§249 CS-M1` | In `CrossProjectSearchService`, drop the `CaseScope` narrowing (pass every visible project's ids, or none) and filter the page in Java with `.filter(row -> gatedIds.contains(row.getProjectId()))` | **Red** — `CaseSearchTest.theTotalIsNarrowedToo` (7 ≠ 2) and `.thePageCannotHideTheCount` (an empty page beside a total of 7). ⚠️ **Verify by doing it**: this is the defect the whole area is about, and the claim that *two* arms catch it should be observed rather than trusted. If only one reddens, say which. |
| `§249 CS-M2` | In `CaseSpecifications`, change `root.get("projectId").in(scope.projectIds())` back to `cb.equal(…, scope.projectIds().iterator().next())` | **Red** — every cross-project arm collapses to one project (`aCompanyAdminSeesEveryProject` answers 2 rather than 7). Also confirms the project grid is **unaffected**, which is the regression this widening could have caused: `CaseListEndpointTest` should stay green, because it passes a one-element set. |
| `§249 CS-M3` | Delete the project predicate from `CaseSpecifications` entirely | **Red on `CaseSearchTest`, and — check — GREEN on `CaseListEndpointTest`.** ⚠️ **This row is the point.** Record which suites reddened, because that answer is the evidence that the rewritten comment above the line is now true: the mutation was a documented survivor on the grid and is fatal here. If `CaseListEndpointTest` also reddens, the old comment was wrong and that is a finding in its own right. |
| `§249 CS-M4` | In `CrossProjectSearchService`, return `visible` instead of the intersection when `request.projectIds()` is non-empty | **Red** — `theProjectFilterNarrowsAndNeverWidens`. The parameter becomes a no-op rather than a leak, but a filter silently ignored returns everything under a heading claiming it is narrowed. |
| `§249 CS-M5` | Remove the `gated.isEmpty()` short-circuit | **Red** — `theGrantlessMemberSeesNothingAndIsNotRefused`, and it should fail as an **exception** (`CaseScope`'s `IllegalArgumentException`) rather than an assertion. ⚠️ **Confirm it is not a full read**: if the caller sees every project's cases, the `CaseScope` guard was removed too and that is a separate, worse finding. |
| `§249 CS-M6` | Remove the `IllegalArgumentException` from `CaseScope`'s compact constructor | **Expected GREEN**, ⚠️ **and recorded as a gap.** It guards a future edit, not a current defect — `CS-M5` is what proves the empty state is unreachable today. Kept after reading the code rather than out of habit: the alternative is an arm constructing a `CaseScope` directly, which would pin a package-private record's constructor and not the endpoint. |
| `§249 CS-M7` | In `CrossProjectSearchService.hit`, resolve `projectKey`/`projectName` from a fresh `ProjectRepository.findById` instead of the gated map | **Expected GREEN** ⚠️ — **a gap, and it is the one worth closing.** Nothing asserts the project fields came from the *gated* set, so the mutation is the shape that would serve an ungranted project's NAME the day the gate and the lookup diverge — and a name is a disclosure where an id is not (`AdminReach`). One arm closes it: seed a same-company ungranted project whose name differs, and assert the hit's `projectName` for a granted row. Kept as a gap because closing it properly needs the lookup to be *able* to diverge, which today it cannot. |
| `§249 CS-M8` | Change `CrossProjectSearchQuery.isBounded()` to `return true` | **Red** — `anUnboundedSearchIsRefused` and `anEnumFilterAloneIsNotABound`. The endpoint becomes "every case in every project you can see", which is the response `/work-items` refuses to serve for the same reason. |
| `§249 CS-M9` | Remove the trailing `cb.asc(root.get("keySeq"))` from `CaseSort.orderBy` | **Red** — `pagingIsStableAcrossProjects`, **if the fixture is large enough to page**. ⚠️ Postgres may return a stable order anyway on a seven-row heap; the fixture pages at `size=3` across three pages specifically so it can, but if this comes back green the fixture is too small and **that is the finding** — the row is then a request for a larger one, not a pass. ❗ **RUN 2026-08-22 — RED, 1, but NOT `pagingIsStableAcrossProjects`, which stayed GREEN. So the finding stands: the fixture is too small, and this row is a request for a larger one.** Postgres returned a stable order over the seven-row heap anyway. What fell instead was `a Company Admin sees every project` — and note why the blast radius is small: the default sort is `keySeq,asc`, so the trailing tiebreak is redundant on the default path and bites only under a non-default sort. |

## §254 — the report type catalogue (`ReportCapabilities.types`)

**Two rows.** The slice's backend half is one field: `GET …/reports/capabilities` now answers the
report TYPES this deployment can compute as well as the formats it can render. It exists because
`type` is an open string whose catalogue was reachable only through `ReportTypes.refusal` — which
names it *after* the caller has guessed wrong — and `tcms_report` on the MCP surface was exactly
that caller.

| # | Mutate | Expect |
|---|---|---|---|
| `§254 RC-M1` | In `ReportReadService.capabilities`, pass `List.of()` instead of `types.supported()` | **Red** — `ReportEndpointTest.Capabilities.whatThisServerCanCompute` (the list no longer contains `run_summary`) and `.theTypeListAgreesWithTheRefusal` becomes vacuous rather than failing. ⚠️ **Record whether the second reddens at all**: a pairing check that iterates an empty list passes, and if it does, that is the finding — the check needs its own non-empty guard, exactly as `formats` would. |
| `§254 RC-M2` | In `ReportCapabilitiesView.of`, filter `types` against a hardcoded `List.of("run_summary")` the way `formats` is filtered against `ASKABLE` | **Red** — `ReportCapabilitiesTest.theTypesAreTheRegistrys`. The two fields get opposite treatment on purpose and the reason is worth the row: a format is filtered because the wire declares three and a deployment renders two, while a type's whole set *is* whichever computations are registered, so filtering it here would be the second copy `ReportTypes` refuses to keep. |

---

---

---

# RUN RECORD — 2026-08-22, wave 1

**The 90 wave-1 rows were executed.** Wave 1 is the set the queue's own authors flagged — *"THE
row"*, *"the slice's own defect, armed"*, *"the security row"*, *"the row worth running first"* —
selected by people who had the code in front of them. Run plan:
`docs/plans/2026-08-22-backend-mutation-backlog-run-plan.md`. Per-row detail is in
`mutation-results.tsv` (one adjudicated line each) and `mutation-manifest.tsv` (`status=RUN:<outcome>`);
the runner's raw append log is `mutation-results.raw.tsv`. Every row's `Result` cell in this file is
filled in place.

**Baseline** `91448743`: `./gradlew test` = 3249 passed, 0 failed, 10m31s. Every measurement is
against **HEAD plus the other lane's in-flight `docs/specs/openapi.yaml` edit** — a declared Gradle
test input read as *text* by `IdempotencyKeyCapTest` and `ConfigConventionTest`. The lane was
verified clean before and after the one row that mutates that file (`SD-2`). The pass ran in its own
remote tree (`builds/tcms-mut`) and its own Gradle home (`.gradle-mut`), per run plan §7.

| Outcome | Rows |
|---|---|
| MATCH — reddened exactly what the row predicted | 44 |
| MORE — reddened the predicted tests plus others | 20 |
| FEWER — reddened less than predicted | 4 |
| ZERO-AS-PREDICTED — row predicted 0, measured 0 | 10 |
| **ZERO-UNEXPECTED — row predicted ≥1, measured 0 (the findings)** | **8** |
| STALE — the code moved out from under the row | 2 |
| NOT-A-MEASUREMENT / CONFIRMED-BY-READING | 3 |
| Routed to another lane (infra · manual · TypeScript) | 4 |

**Nine of the 90 rows predicted a zero and reddened anyway** — `CL-29`, `DR-9`, `SA-5`, `PB-8`,
`LG-M3`, `§217 FD-1`, `§187 DC-6` and two more. That is the direction nobody plans for, and it is why
a zero-prediction is still worth measuring: five of the nine were filed as *known gaps* that turn out
not to exist.

## The runner now checks the two traps itself

Both had already produced a false zero before this wave started, and neither is visible in a green
build. `~/bin/tcms-mutate` (machine-local) gained four guards:

- **the diff must be substantive** — not whitespace- or comment-only;
- **`--gone TEXT` / `--occurs N:TEXT`** prove the mutant no longer contains what the patch meant to
  remove. `R7-1`'s unused-CTE no-op is the shape; `--occurs` exists because the line being mutated is
  often one of several *identical* lines, where "gone" is not expressible but "one fewer" is;
- **the scope must contain the test the row names** — the class must exist under `backend/src/test`,
  and every `--test` must occur in it **on a line that is not a comment**;
- **a scope matching no test is `ERROR-NO-TESTS`, never a zero.**

It also takes several files (a row like `13k` is one row and two symmetric sites) and several
comma-separated scopes, and it appends to `mutation-results.raw.tsv` rather than to the
hand-adjudicated `mutation-results.tsv`.

**The guards earned their keep immediately.** Six manifest scopes were wrong and every one was caught
by a guard rather than by a green build: `AD-2` and `ST-10` carried their section's *first-named*
class, `CR-3` and `§177 GT-M2` named a class the test does not live in, `IK-6` and `UX-3` had the
wrong package. And the comment-only rule was added mid-pass because `FA-1` had already slipped
through: the test that row names exists **only** as a `{@link}` in a class javadoc, which `javac`
does not resolve, so a plain grep was satisfied by it.

**One no-op still got through the guards and was caught by re-reading the mutant.** `OB-4`'s first
proxy put `@Transactional` on a **self-invoked private method**, which Spring never intercepts — the
very defect that row describes, arriving as trap one. The substantive-diff check passed it. Reading
the mutant is not optional.

## The findings

Ordered by what a reader should act on first.

### 1. `§236 DK-4` — the security row passes for the wrong reason

Deleting the `javascript:` scheme check from `DefectLinkService.urlErrors` reddens **nothing**.
`refusesASchemeThatExecutes` posts `javascript:alert(document.cookie)`, which has no authority
component — so `parsed.getHost()` is null and the **host** check refuses it, under a different
message. The test asserts only that `errors.url` is non-null, so it cannot tell the two refusals
apart. **The scheme check is pinned by nothing.** Repair: assert the *message*, and add a payload
carrying a host (the `scheme://host/…` shape) so the scheme check is the only thing that can refuse it.

### 2. `§218 NC-7` — nothing stops a second POST

`theDeliveryIsKeyedOnTheOccurrence` dispatches twice and asserts one row with `attempt = 2`, so its
first delivery never ends `SENT` and the `getStatus() != SENT` refusal is never exercised. The row's
own warning — *"the unique index stops a second row, not a second POST"* — is right and understated.
Repair: a fixture whose first dispatch **succeeds**, then make the outbox row due again and assert the
webhook double received exactly one request.

### 3. The gate distinctions that are real in the code and invisible in the seeded catalogue

`DA-1` found this family; this wave added three more sightings and one correction.

- **`CA-1`** — swapping the case-attachment delete to `writeGrantedByAuthorship(evidence:delete)`
  moves nothing, for **two independent reasons**: `V51` removed `OWN` from *every* grant, so the
  authorship arm is inert; and roles spec §A3 gives `case:edit` and `evidence:delete` to the **same
  four roles**, so the key cannot discriminate either. `CA-7` proves the test is alive — it reddens
  when the delete stops deleting. What is dead is only its sight of the gate.
- **`FA-1`** — `V52` left `PLAN_SCOPE` carried by **no seeded row**, and the test the row names no
  longer exists. ⚠️ **Corrected by `§229 PM-15` later in the same wave**: the seam is still wired and
  still discriminates. `ArtifactEndpointTest.Correcting` sees it only because its fixture **grants the
  qualifier by hand**. Copying that persona into `ArtifactFileEndpointTest.Gates` is the repair.
- **`DA-13`** — `auditNamesThePage` asserts on the **add** event while its comment argues about the
  row that survives the hard delete, and asserts the payload rather than the target.

**What closes the family is not another persona.** For `CA-1` none exists: no seeded role can tell
`case:edit` from `evidence:delete`. The closure is a **catalogue-derived convention test** that fails
when a gate's key is interchangeable with the constant a plausible mutation would substitute — a check
that a permission swap is observable at all.

### 4. `UX-3` — the unexercised-seam rule is bounded somewhere other than where it says

Making a `@Scheduled` method `public` reddens nothing — not because the `public` narrowing holds, but
because the rule **skips a method with no caller at all** (`if (callers.isEmpty() || …) continue;`).
Its message reads *"every caller of this one is a test"*; the strictly worse case falls through the
same arm silently. Repair: treat an empty caller set as unexercised **unless** the method carries
`@Scheduled`/`@EventListener` — naming the container, which the row's own last sentence asks for.

### 5. `§238 SV-5` — a flush that is load-bearing by argument only

Deleting `suites.flush()` from `SuiteVersions.reconcile` leaves everything green. Hibernate's
`FlushMode.AUTO` already flushes before a native query whose query spaces it cannot compute. The
comment above the line calls it *"load-bearing rather than defensive"*, and for this suite that is not
observable.

### 6. `§192 DF-1` and `DF-4` — one writer pinned, and one line already redundant

`DF-1` reddens **only the bulk writer**: the edit path's two named arms stay green, because
`CaseEdit.normalizeDeclines` narrows the list before `reconcileOptouts` is reached, so the deletion is
over-determined there. **The reversal is pinned by one writer, not two.** `DF-4`'s
`context.declines().put(...)` is redundant for the same reason — `normalizeDeclines`' absent branch
recomputes `retainedDeclinesOf` — and becomes load-bearing the moment `DF-3`'s branch is touched.

### 7. Rows that were wrong in the reassuring direction — five registered gaps that do not exist

`CL-29` (`aRetriedIntentClonesOnce` already counts), `§232 PB-8` (`aRefusedCallerRecordsNothing` does
catch an ungated `recordFailure`), `§242 LG-M3` (a null `projectId` reddens **all nine** arms of
`AuditLogReadTest`), `SA-5` (the fixture is stricter than it reads, so the version-vs-timestamp
argument **is** asserted), and `DR-9` (the `P0D` inversion is observed by three tests; only the
positive-duration **guard** is untested). Each of these should be **withdrawn** from the gap register
rather than carried.

### 8. `§217 FD-1` — stale in the good direction

The row predicted GREEN as its finding. It is now RED across eight arms: §217's own later phases built
`SsoFlowIntegrationTest`, and that class **does** observe `V7`'s defect re-authored. Worth recording
which class does **not**: `RlsIsolationTest`, the row's manifest scope, contributed zero reds.

## Two rows whose value was the pair, and both held

- **`§185 SR-4`** — the row whose expected result is a *green build*. Reproduced exactly: with
  `inputs.files` deleted, a `Dockerfile` that no longer creates the `raw-reports` root leaves
  `> Task :test UP-TO-DATE` · `BUILD SUCCESSFUL in 921ms`. **And a control**, which is what makes it
  conclusive: with `inputs.files` restored and the *same* broken `Dockerfile`,
  `everyStorageRootIsCreatedInTheImage` fails. The declaration is load-bearing and Gradle's
  up-to-date check is exactly what would hide its absence.
- **`§207 DQ-3`** — the retained-machinery proof. An `OWN` grant re-engages the authorship gate through
  five assertions, so `V51`'s retention argument holds. One half is unpinned: the curated 403 sentence
  is wired at `CaseController.java:700` and no test reads the body.

## What the families answered

- **`D1` (the dropped project predicate, 12 rows)** — `IH-1` **and** `SD-3` both redden, each with a
  fixture holding a sibling project. The family is blind **for want of fixtures**, not because the
  predicate is redundant.
- **`D6` (`@Transactional(readOnly = true)` on a repository, 4 rows)** — `§241 OC-4` reddens
  `RepositoryTransactionTest`. One measurement answers for `§242 LG-M11`, `§243 RN-M9` and
  `§247 SK-M10`.
- **`D7` (attachment owners)** — `CA-4` measures `findOwnedById`, the **shared** two-segment gate, so
  it answers for `DA-7` and `FA-7` as well.
- **`D8` (the `ORDER BY` tiebreak)** — still blind. `IH-6` is a held zero awaiting wave 2's
  same-instant fixture, and `§249 CS-M9`'s own observer stayed green because the fixture is too small.

## Held, retired and routed

**BLIND — re-run after wave 2** (decision 3's category, recorded as held rather than as coverage):
`13k` and `IH-6`, both waiting on the two-instant fixture.

**Retired as `NOT-A-MEASUREMENT`**, on each row's own words and confirmed by trying: `CL-8` (no
one-line proxy is faithful — what closes it is an **ArchUnit rule** that nothing in `repository`
other than `CaseCreateService` saves a `TestCase`) and `TA-5` (a 42-call-site refactor).
`§239 RT-M2` was **confirmed by reading**: `sharedStepId` occurs nowhere as a live field, so the
resurrection would measure the same zero at much greater cost.

**Routed to another lane:** `DP-1` (infrastructure — `docker build`), `§192 DF-7` and `§240 DR-M3`
(manual — each needs a hand-built database), `AP-13` (a TypeScript row the wave-0 relocation left
behind in the manifest).

## Proxies, stated rather than discovered

Four rows were run as **proxies** because the mutation they describe is bigger than one edit, and each
says so in its own `Result` cell: `OB-4` (the self-invocation half is not expressible in one line),
`§229 PM-15` (`ArtifactEditInput` has no scope field), `§207 DQ-3` (the follow-up `UPDATE` was appended
to the newest migration rather than added as a new script, so the revert is by exact path) and
`§246 CE-M5` (the same). A proxy measures what it measures; saying which half was applied is what keeps
the number attributable.


# RUN RECORD — 2026-08-23, wave S

**The 74 wave-S rows were executed.** Wave S is the run plan's *scripted sweeps* (§6) — rows that
share one mechanical form rather than one subject. **The set was not marked when this pass began:**
the manifest's `wave` column held only `W1` and `-`, so the wave was neither resumable nor
size-checkable. It was derived from run plan §5 and §6 in four stated families, written into the
manifest as `wave=S`, and committed before any row ran. The criteria are in the manifest's header so
the set can be re-derived rather than re-guessed.

| Family | Criterion | Rows |
|---|---|---|---|
| **S-A** gate-constant swaps | run plan §5's named id list (26 ids; `CA-1`, `DA-1`, `FA-1` and `§128 CD-4` already run in wave 1) | **22** |
| **S-B** migration edits | the mutated file is under `backend/src/main/resources/db/migration/` — excluding the Hetzner `V20`–`V33` lane, `GAP` rows and the manual lane | **25** |
| **S-C** convention-register edits | the mutated file is a convention/ArchUnit/schema test, or a production constant register whose only observer is one, **and** the edit adds/removes a register entry, corpus root or exclusion (not rule logic) | **17** |
| **S-D** enum/severity ordering | enum declaration order, severity value, or severity rank | **10** |

**The derived count is 74 against the plan's ~100 estimate, and the manifest is authoritative.**
The whole of the gap is S-B: run plan §6 estimates *"~49 `V*` migration edits"*, and only **25** rows
actually edit a migration — the rest of the earlier count is rows that merely *mention* a `V` number
as context. S-A is 26 ids where the plan's prose says 28; S-C is 17 against ~15 and S-D 10 against
~10.

**Baseline** `b5ef2826`, lane clean at the start (`tcms-mutate --baseline` reported nothing dirty,
`docs/specs/openapi.yaml` included — the other lane had nothing in flight, unlike wave 1). The pass
ran in its own remote tree (`builds/tcms-mut`) and its own Gradle home (`.gradle-mut`), per run plan §7.

| Outcome | Rows |
|---|---|
| MATCH — reddened exactly what the row predicted | 37 |
| MORE — the predicted tests plus others | 16 |
| FEWER — less than predicted (in all three cases the predicted test stayed **green**) | 3 |
| ZERO-AS-PREDICTED | 9 |
| **ZERO-UNEXPECTED — the row predicted ≥1 and measured 0** | **6** |
| STALE — the code moved out from under the row | 2 |
| ERROR-COMPILE — the mutation cannot reach a test | 1 |

## The findings

### 1. `§179 RS-M3` — the sort is unpinned, and a control proves it is not dead code

Deleting `raised.sort(Comparator.comparing(HealthSignal::severity))` from `CaseHealth.signals()`
reddens **nothing**. `signals()` iterates `HealthSignal.values()` in declaration order, and **every
pair the `Order` fixtures choose is already declaration-ordered worst-first**:
`theWorstSignalComesFirst` takes `UNLINKED` (index 6, INFO) and `STALE_AUTOMATION` (index 3, ERROR);
`everySeveritySorts` takes `CONDITION_DRIFT` (2, ERROR), `NEVER_EXECUTED` (4, WARNING) and `UNLINKED`
(6, INFO). Both are already in the asserted order with no sort at all.

**`§179 RS-M4` is the control** and is why this is conclusive rather than suggestive: reordering
`Severity`'s constants reddens `everySeveritySorts`, so the comparator does execute and that test
does observe its result. The zero is the *fixtures*, not a dead line.

Declaration order is **not** globally severity-sorted — `STALE_APPROVAL` (1, WARNING) precedes
`CONDITION_DRIFT` (2, ERROR), and `UNLINKED` (6, INFO) precedes `FAILING_REPEATEDLY` (7, WARNING) —
so the repair is one line: pick a crossing pair, e.g. `UNLINKED` + `FAILING_REPEATEDLY`.

### 2. `UX-4` — a set-derived assertion cannot see a member leave the set

Dropping a verb from `OperatorCommands.ALL` reddens nothing. `everyVerb` is **derived from `ALL`
itself** — it asserts that every member switches the jar to non-web — so removing a verb shrinks the
set the test iterates and the test cannot see the removal. The comment beside it (*"a fourth verb is
covered by the day it is added"*) is true and is only half the question. The failure the guard exists
for is a hang an operator meets at 2am, and it is unobserved. Repair: assert `ALL` contains each
named constant, or compare it against the verbs the runner actually dispatches on.

This is the same shape as finding 1 and as `§133 AG-6` below: **a check computed from the thing it
checks is one-directional unless something else pins the population.**

### 3. The blind-gate family — five more sightings, and a THIRD distinct cause

Wave 1 found this family (`DA-1`, `CA-1`, `FA-1`) and named one cause. Wave S adds five sightings and
one cause nobody had written down.

- **`§210 DQ-5`** — the family's own closing row, now **measured rather than argued**. All five pairs
  applied together (`suite:delete`→`suite:manage`, `artifact:delete`→`artifact:edit`,
  `doc:publish`→`doc:edit`, `run:close`→`run:execute`, `result:amend`→`result:submit`) over five test
  classes: **not one red.**
- **`DA-2`** and **`FA-11`** — `CA-1`'s shape in the other two attachment owners. `V51` emptied `OWN`
  so the authorship arm is inert, and §A3 gives each pair to the same four roles. `FA-11` is stale in
  the same breath: the test it names was rewritten by `V52`.
- **`§240 AR-M4`** — the test's own comment says *"This is the persona that tells the two apart"* and
  has been **false since `V51` landed, eight days before the row was written**.
- **`§242 LG-M5` is the new cause.** The row is right that a Test Lead holds `project:audit` and not
  `audit:view` — *at project scope*. This gate is `permissions.evaluateCompanyWide(userId).has(...)`,
  which answers from the SYSTEM and COMPANY terms only (§43's deliberate design), and there **Company
  Admin's COMPANY-scope role enumerates every project key including `project:audit`** (`V3`, the
  `…0002` grant) while the Test Lead's PROJECT-scope grant is invisible. Not *"the catalogue gives
  both keys to the same roles"* but *"the evaluator cannot see the grant that separates them"*.

**And the counter-example that names the repair.** `§227 PM-1` and `§227 PM-2` discriminate
`report:view` from `report:export` — which §A3 grants to **identical** roles — purely on the class's
hand-built screen-only persona. That is `ArtifactEndpointTest.Correcting`'s pattern working, and it
is what wave 1 proposed. Where no persona is possible (`CA-1`), the catalogue-derived convention test
is still owed.

### 4. `§239 RT-M3` — a gate the row says does not exist, and it is accidental

The row predicts **green** for replacing `V63`'s two `COMMENT ON` statements with
`ALTER TABLE test_step DROP COLUMN shared_step_id;`, and argues that *"no gate distinguishes the safe
migration from the destructive one"*. It reddens: `SchemaCommentTest.everyQualifiedReferenceNamesALiveColumn`
fails, because **`V59`'s comment on `shared_step.created_idempotency_key` names `test_step.shared_step_id`
in prose** and that rule reads it. So a gate does exist — three migrations away, entirely by accident,
and it disappears the day that comment is reworded. `SearchVectorTest` stayed green exactly as the row
expected of a `DROP COLUMN`. Worth inheriting before the drop slice.

### 5. `§247 SK-M17` — the convention test sees it and the consequence test does not

Dropping `FORCE` from `run_schedule` reddens `everyCompanyOwnedTableForcesRls` and **not**
`AsyncCompanyContextTest.aFireInOneCompanyCannotSeeAnothersSchedules`, with both classes in scope.
The test the row leans on to demonstrate the *consequence* connects as `tcms_app`, and plain `ENABLE`
already isolates a non-owner role. Only the convention test sees the loss — which is the argument for
`IntegrationTestBase`'s three-role split arriving as the reason the split is not enough on its own.

### 6. `§133 AG-6` — caught, and caught better than the row expected

Adding an `OWN`-qualified `case:delete` to Agent in `V40` leaves the delta test **green** (`V51` gave
Test Engineer the key and `V52` derives Agent from it, so the *key set* is unchanged) and reddens
`ownIsCarriedByNoGrant` and `everyQualifierIsAll` instead. `V52` statement 3 inserts
`ON CONFLICT DO NOTHING`, so **`V40`'s `OWN` row survives the de-qualification**. A delta over key sets
cannot see a qualifier; two catalogue-derived rules cover the half it cannot.

### 7. `CL-9` — a gate added in front of an already-gated path can only narrow

The predicted test stayed green and two others fell. `cloneCase` has no gate of its own — the create
path's `case:create` is the gate — so neither variant the row names (`case:edit`, or the "honest"
`project:read`) can loosen anything, and a Viewer's status does not change. What moved was the
404-before-403 ordering, because a `holds()` refusal answers 403 before visibility is resolved. So the
clone route's **key** is unobservable and its **order** is observed by two tests.

### 8. `§207 DQ-2` + `§210 DQ-3` — one registered gap, measured from both sides

`§210 DQ-3` (enumeration replacing the derivation) is green as predicted. `§207 DQ-2` is its mirror
image: removing `plan:delete` from Test Engineer leaves the Agent delta **green**, because `V52`
replaced `V40`'s enumeration with a derivation and Agent follows whatever Test Engineer holds. Adding
a key and removing one, and **neither can tell an enumeration from a derivation.** The real check is
still the next permission migration — `§210 DQ-4` shows what it looks like when the grant lands
*after* the derivation has run, and there the delta does redden.

## Registered gaps confirmed by measurement rather than by argument

`AS-5` (a migration that deletes production data — every fixture creates its grants after the
migration), `IK-1` (two concurrent creates under one idempotency key, on any of the six tables, since
`V11`), `§217 FD-3` (nothing at all reads a `company_id`'s nullability on an exempt table; thirteen
tables, none with its shape pinned), `§241 IX-1` (an absent index reddens nothing in any of the four
classes that could plausibly notice), `§188 NR-4` (no column's only SQL writer is a fixture's `.sql`
today), `R6-4` (`severityRank`'s `ELSE` arm is unreachable because `theLadderNamesEveryKind` asserts
the ladder is exactly `StatusKind.values()`), and `§247 SK-M20` — whose zero is **analytic**, since
the two spellings are the same string and no test could have separated them.

`§247 SK-M18` closes beside `§241 IX-1`: what is gated is the **column order of an index that
exists**, never its existence and never its value.

## Two rows that came in larger than they claimed

Both counted off the run's own JUnit XML rather than off the failure list.

- **`§188 NR-1`** — the direction is exactly as written (absences, not presences) but not seven:
  widening the reader corpus back to `src/test/java` empties the computed set and **all sixteen**
  entries of `READERLESS_BY_DECISION` are reported absent. Every one has a test naming it.
- **`§188 NR-3`** — **eighteen** `lockVersion` members, not fifteen. Three more entities have gained
  `@Version` since §188.

## Structural rather than testable

**`§124 AG-1` does not compile, in either copy.** Adding `BEST` back to `execution.AggregationPolicy`
fails at `PlanStatisticsService.java:490`; adding it to `reporting.AggregationPolicy` fails at
`AggregateExecutionReport.java:846` — *the switch expression does not cover all possible input values*
in both. An exhaustive switch per copy is the guard, and it fires before any test runs. The class the
row names, `AggregateExecutionPolicyTest`, does not exist.

**`§133 AG-7` variant 2** is the same lesson at the database: a *near-miss* qualifier value aborts the
migration (`FlywayMigrateException` → `FlywaySqlScriptException` → `PSQLException`) and takes all 24
tests of `SeedDataTest` with it at context startup, so the derived rule the row names never runs.
Variant 1 — the exact-value spelling — is green and stale, because `V52` sets that row to `ALL` anyway.

## Stale, substituted and proxied — stated rather than discovered

**STALE:** `§133 AG-5` (the exclusion it defends was deleted by §210, over a recorded objection) and
`§154 RL-4` (`OWN_FILE` does not exist — §188 narrowed the corpus and the file's own header records
that this made the shape *unreachable*; `§188 NR-1` in the same batch is its live form, and it
reddens).

**Substituted, because the thing the row names has moved:** `WL-1` (its example column acquired a
writer in §159 — that is `AR-M5`'s subject, so a different entry was deleted), `§239 RT-M1`
(`EXPECTED_DECLARATIONS` is **10 again**, so *"set it back to 10"* is a no-op; it was set to 9),
`UX-2` (`UNEXERCISED_BY_DECISION` is `Map.of()` and `Outbox.record` gained two product callers in
§218, so the row's *question* was run instead of its literal mutation).

**Proxied:** `RK-4` — a new `V*.sql` cannot be reverted by exact path, so the fourth carrier of
`release_sort_key` was added to an existing table by appending to the newest migration. The test
computes the carrier set from `information_schema` and asserts it is exactly three, so a fourth
carrier is what it sees either way. `§210 DQ-4` and `§241 IX-1` were shaped the same way (append to
`V68`; replace `V67`'s body with `SELECT 1;`).

## Citation corrections found while running

- **`§242 LG-M12`** names `everyExemptionStillExempts`. That is `SchemaCommentTest`'s rule about
  *comment* exemptions. The rule that owns `READERLESS_BY_DECISION` is
  `ArchitectureRulesTest.everyMappedColumnHasAReader`, and it is what reddens. The property holds; the
  citation was wrong.
- **`§239 RT-M1`** — `IdempotencyKeyCapTest`'s `@DisplayName` still says *"one cap, nine declarations"*
  while its assertion says ten. Only the assertion is checked.
- **Scopes corrected in the manifest:** `RX-2` (carried `PlanStatisticsEndpointTest`, its section's
  first-named class; the `RX-*` rows are §124's `run:execute` family in `ExecutionEndpointTest`),
  `§240 AR-M4` (→ `PlanLifecycleEndpointTest`), `§247 SK-M20` (→ `RunScheduleEndpointTest`),
  `§242 LG-M5` (was `?` → `AuditLogReadTest`), and `§225 PM-3` — `RoleScopeShapeTest` is
  `com.tcms.schema`, not `com.tcms.identity`, caught by the runner's `ERROR-NO-TESTS` guard rather
  than by a green build.

## Two runner artefacts, recorded so the numbers stay attributable

`§246 CE-M4` and one attempt at `§188 NR-4` both completed on shanks and then lost the SSH connection,
so the runner wrote `ERROR` with a wall-clock reflecting the dead socket rather than the work. Both
results were recovered from the run's own JUnit XML on the box (`failures="2"` in each of `CE-M4`'s
two classes; `everyMappedColumnHasAWriter PASSED` for `NR-4`), and `NR-4` was re-run — returning
`:test UP-TO-DATE`, which is the **same mutant's cached green** and not an unexecuted one. A 3-second
zero is `§185 SR-4`'s shape and is only safe to record because the preceding run's log shows the
assertion actually executing.


# RUN RECORD — 2026-08-23, wave 2

**Wave 2 is different in kind from waves 1 and S: it SHIPS TEST CODE.** Every row before this was
applied, measured and reverted. Here three fixtures are a permanent addition to the suite, gated by
`./gradlew build` on shanks in the **shared** `builds/tcms` tree (`BUILD SUCCESSFUL in 8m 48s`) and
committed *before* a single row ran against them — a row measured against an uncommitted fixture is
not reproducible, and the runner's dirty-tree guard aborts on it anyway. The 28 mutation runs then
used the pass's own tree (`builds/tcms-mut`) and Gradle home (`.gradle-mut`), per run plan §7.

## The set, and its disagreement with the plan

**The wave-2 set was not marked when this pass began** — the manifest's `wave` column held `W1`, `S`
and `-`. It was derived from run plan §5's `D1`/`D3`/`D8` id tables plus `13k`, the row wave 1 held
as BLIND for the same fixture `D8` needs, written into the manifest as `wave=2` and **committed
before any test code was written**, so the wave is resumable and its size is checkable.

**The derived count is 30 against the plan's "~10 rows + 3 tests", and the manifest is
authoritative** — the same rule wave S applied when its own derivation came in 26 rows under the
estimate. The two numbers are not about the same thing: the plan's ~10 is its **collapse** (keep 2 of
`D1`, 3 of `D8`, 3 of `D3`), and 30 is the family membership the fixtures have to answer for.
Eleven of the 30 carry `W1+2` — measured in wave 1, re-measured here — which is decision 3's
*"BLIND — re-run after wave 2"* category being collected on rather than quietly dropped.

| | Rows |
|---|---|
| Wave-2 set (manifest `wave` contains `2`) | **30** |
| Measured in this pass | 26 (28 runs — `CM-8` and `R8-5` are two halves each) |
| Conclusive from wave 1, not re-run | 2 (`IH-1`, `SD-3` — both RED, and both are what made `D1` worth a fixture) |
| Held, because wave 2 wrote no fixture for them | 2 (`§245 BA-M12`, `§249 CS-M9`) |

| Outcome | Rows |
|---|---|
| MATCH | 11 |
| MORE | 7 |
| FEWER — the predicted test stayed **green** and a different one fell | 2 |
| ZERO-AS-PREDICTED | 3 |
| **ZERO-UNEXPECTED — the row predicted ≥1 and measured 0** | **4** |

## The three fixtures, and the boundary each crosses

Each says in its own comment which boundary it crosses and why a fixture that does not cross it would
be green — the §179 `RS-M3` lesson, which is this wave's subject. And each lands with a **control**,
because a fixture added without one is an assertion nobody has seen fail.

- **`reporting.SiblingProjectReadsTest`** — one company, **two projects**. Every other class in the
  package seeds one project per company, and that arrangement cannot see a dropped
  `AND x.project_id = :projectId` *at all*: `FORCE ROW LEVEL SECURITY` refuses the foreign row before
  the `WHERE` clause is consulted, so the query returns nothing whether or not it asked to. Here both
  projects are the same company, one session sees both, and the bound is entirely the query's —
  which `Reachability.bothProjectsRowsAreVisibleToOneSession` asserts directly rather than leaving as
  the comment's claim. **The controls** re-ask every question bounded to the sibling's own project,
  so an empty result is the predicate's doing and not the fixture's.
- **`IngestionEndpointTest.SameInstant`** — six jobs sharing one `created_at`, **written by SQL and
  never by racing the clock**. A test that hoped two inserts would land in the same microsecond
  passes or fails by machine speed, and the one thing it must never do is go green because the tie
  did not happen. The ids ascend in insertion order and the required answer descends, so the two are
  opposites rather than merely different.
- **`FailingRepeatedlyTest.LiveWriters`** — both writers of a `case_health` row, each deriving its
  value from evidence, in both orders. The two tests that cover this today establish the sibling's
  columns with an `UPDATE`, and **a hand-set boolean has no writer to lose a race with**. `THRICE` is
  the subject because its failures are old: it raises `failing_repeatedly`, which only `reporting`
  writes, *and* four columns only `repository` writes. **The control** asserts each writer alone
  raises something the other cannot.

## The findings

### 1. `§235 PM-1` — the belt-and-braces predicate is unobserved, and the row asked to know

Replacing `p.company_id = :companyId` in `DocPageRepository#search` with a tautology reddens
**nothing**. `theCompanyBoundIsEnforced` — the test named for exactly this — stays green, because RLS
refuses the foreign company's row underneath and the suite cannot tell the two layers apart. The row
put both outcomes on the record in advance (*"if it goes green, the belt-and-braces predicate is
unobserved and the argument for writing it is weaker than the header claims"*), and this is the less
comfortable one. **The predicate is not wrong; the claim that the suite checks it is.**

Proxied, and it matters: the line was replaced with `CAST(:companyId AS uuid) IS NOT NULL` rather
than deleted, because a Spring Data `@Query` with an unbound `@Param` will not start. Same shape as
`AP-7` below.

### 2. The same-instant fixture is owed in FOUR places, and exists in one

This is the wave's largest single result, and it was invisible until three rows were measured
together.

- **`IH-6`** — closed. `SameInstant` reddens it; `History.theListIsSummaries`, which posts two reports
  milliseconds apart, stays green. That green is the fixture's own claim, measured.
- **`CM-8` (b)** — `doc_block`'s `ORDER BY b.position, b.id` with only the tiebreak dropped: **ZERO**,
  exactly as the row predicted. Nothing in the fixture ties a position, and `doc_block` has no unique
  constraint that would stop one.
- **`§242 LG-M2`** — `audit_event`'s two `ORDER BY e.occurredAt DESC, e.id DESC` clauses, both
  tiebreaks deleted: **ZERO**. The test is *called* `theSortIsStableWhenTimestampsCollide` and its
  fixture is too small for Postgres to reorder. The row predicted this risk and read it correctly in
  advance: a finding about the test's fixture, not about the tiebreak.
- **`FQ-10`** — the `lag()` window in `recomputeFlakeScores`, `ef.execution_id` removed: **ZERO**. The
  fixture spaces results a day apart. One of decision 1's six *undetermined* rows, now settled as a
  gap.

Add wave 1's `§249 CS-M9` — *"Postgres returned a stable order over the seven-row heap anyway"* — and
`§245 BA-M12`, and the count is **six**. `SameInstant` is the shape all six need, and it is three
lines of fixture each: enough rows to defeat a small sort, keys written by SQL, inserted in the
reverse of the asserted order.

### 3. `case_health`'s statement ORDER decides what a set-list mutation can do — and nobody had written it down

Three rows measured together produce one fact that changes how the whole `D3` family reads.

- **`SA-6`** (add `stale_approval` to `recomputeAutomationDrift`) and **`§158 CD-7`** (add
  `condition_drift` to `recomputeStaleApproval`) both redden **1**, and in **neither case is it the
  test the row named**. Both consequence tests stay green. The reason is the tick's fixed order:
  drift is statement 2 and approval is statement 3, so approval rewrites its own column inside the
  same transaction; approval is 3 and drift is 4, likewise. **A set-list naming a LATER statement's
  column is always repaired before the transaction ends**, and only `otherSignalsAreNotClobbered`,
  which drives each statement alone, can see it. §247 `SK-M17`'s shape — the boundary test sees it
  and the consequence test structurally cannot.
- **`§178 SL-M5`** reddens **53** against 2 predicted, and the cause is the other half of the same
  fact. `SpecLinkage` is statement **5**, so the row always exists by the time it runs: it is
  **always in the `ON CONFLICT` arm**, never the `INSERT` arm. Dropping `unlinked` from that arm does
  not make the flag latch — it means the column is **never written at all**. For six of the seven
  statements the `INSERT` column list is unreachable, and only the tick's first statement ever
  inserts.

The row's own words — *"the update arm is unreachable from a fresh fixture, and it is the arm a live
deployment is in on every tick after the first"* (`§166 LR-3`) — are true for statement 1 and exactly
backwards for statements 2–7.

### 4. `§167 DL-3` and `IK-4` — two more registered zeros that are not zeros

`§167 DL-3` was retired to `DOCUMENTED GAPS` as a project-predicate zero-prediction. It reddens
**2** — `a page of a sibling project is not shown` and `a page of a sibling project is no link
either` — because `CaseHealthRecomputeTest` already holds a sibling-project page and the row did not
know. **Not closed by anything wave 2 wrote.** `IK-4` is the same shape: its caveat is about the
doc-page class, and the suite class has a second granted project. Wave 1 withdrew five registered
gaps that did not exist; this is two more, and both were found by running rather than by re-reading.

`§244 ED-M7` is a third, one register over: the row calls itself *"a second gap"* and
`ExecutionFactRefreshTest`'s set-list test covers it.

### 5. `IN-11` — the fixture is HALF there, which is a smaller repair than six sections assumed

Dropping the project predicate from `CaseFacts.keyedLookup` reddens nothing, and the reason is
precise rather than general: `IngestionEndpointTest` **does** hold a second project — `SAVINGS`, and
its header says why — but holds no **case** in it, so the lookup has no sibling row to reach. One
case in `SAVINGS` closes the row. Six sections have been asking for "a two-project fixture" as though
it were a slice of work; here it is one line.

### 6. `R8-5` (b) — a RED that is as unrepeatable as the GREEN the row feared

Dropping the `versionNo` term from the divergence comparator reddens 2. The row predicted it *"may
come back GREEN by luck, since `gen_random_uuid()` orders arbitrarily"* — and
`AggregateVersionDivergenceTest` does write `test_case_version.id` with `gen_random_uuid()`, so what
the sort fell back on this run was random. **A red does not discharge the repair the row named.** A
deterministic version id in that fixture is still owed, and is listed under *Tests to write*.

### 7. `12c` — the second gate is untested, as the row suspected

Dropping `AND s.project_id = c.project_id` from the suite arm reddens nothing: `RunEdit.liveSuiteIds`
refuses one step earlier, so the query's own bound is never reached. The row said so and named the
repair — a test that bypasses the validator — and the measurement is what turns that from a
suspicion into a fact.

### 8. `DR-5` — 57 reds, and why the number is still worth having

Adding eight sibling columns to `recomputeAutomationDrift`'s set-list reddens 57 tests across both
classes. That is a blast radius, not a measurement of the row — §158 `CD-5`'s shape. What makes it
worth recording is *which* 57: two of them are the new `LiveWriters` group's directional tests, so a
fixture in which **both** values are derived discriminates here exactly as the hand-set boundary test
does. `FQ-8` is the clean version of the same family — RED, exactly 1, against
`theAuthoredHalfIsUntouchable`, which is the one `D3` test that already drove two *live* writers.

## Which of the three families are now sighted, and which are still blind

- **`D1` — SIGHTED where a fixture exists, and the blindness is now per-package rather than
  per-method.** Ten of its twelve rows are measured. `R7-6`, `R8-7`, `CM-6` (wave 2's fixture),
  `§167 DL-3`, `IK-4`, `IH-1` and `SD-3` (wave 1) all redden. `§168 CV-3` and `12c` are zeros the
  rows derived correctly. **Still blind: `IN-11`** (needs one case in `SAVINGS`) **and `AP-7`** (needs
  a second project in `AiProposalEndpointTest`). The seam is not blind any more; two classes are.
- **`D8` — SIGHTED for the two rows held BLIND, and the family's real answer is a fixture pattern
  rather than a verdict.** `IH-6` and `13k` both redden. `11e`, `13g` and `CM-8` (a) redden without
  needing a tie at all. **Still blind: `CM-8` (b), `FQ-10`, `§242 LG-M2`, `§245 BA-M12`,
  `§249 CS-M9`** — five places needing what `SameInstant` demonstrates.
- **`D3` — SIGHTED, and the family collapses further than the run plan assumed.** All eight rows
  redden. But six of the eight are caught by `otherSignalsAreNotClobbered` alone, and two of those
  six (`SA-6`, `§158 CD-7`) are caught *only* by it — so the family's real content is one
  per-statement boundary test plus finding 3's ordering fact, not eight measurements.

## Stated rather than discovered

- **Proxies.** `§235 PM-1` and `AP-7` replaced a predicate with a bound tautology rather than
  deleting it, because a Spring Data `@Query` with an unbound `@Param` will not start. A proxy
  measures what it measures; saying which half was applied is what keeps the number attributable.
- **One build gated four files**, not one build per fixture: they share a tree and a Gradle daemon,
  and three full builds of a suite that boots Postgres per context buy nothing the one over the union
  does not.
- **`§245 BA-M12` and `§249 CS-M9` were not re-run.** Wave 2 wrote no fixture either of them could
  see, and re-running a row against an unchanged fixture reproduces the number rather than measuring
  anything. Held, and named in finding 2's list of six.
- **§138 is a BACKEND section sitting in `packages-and-frontend-mutation-checks.md`.** Its own header
  reads *"Scope is `./gradlew test --tests 'com.tcms.repository.AiProposalEndpointTest'`"* and all
  fourteen of its rows are Java. The wave-0 relocation moved it for `AP-13` alone. Recorded rather
  than moved back — section homes, like ids, are quoted from handoff sections — and `AP-7`'s result
  is filled in where the row lives.

## Citation corrections found while running

- **`13g`** names `keepsTheLatestExecutionPerCasePerConfiguration`; the test is
  `keepsTheLatestPerCasePerConfiguration`, and `PlanSnapshotRefreshTest` is `com.tcms.reporting`, not
  `com.tcms.execution`. Caught by the runner's scope guard, not by a green build — the fifth and
  sixth wrong scopes this pass has found that way.
- **`CaseFacts.caseIdsByDisplayKey`'s javadoc says `uq_case_key` makes the display key "unique per
  project".** `V1` declares `CONSTRAINT uq_test_case_key UNIQUE (company_id, key)` — it is unique per
  **company**. The method's safety argument (*"a token that is not a key of this project matches
  nothing here"*) rests on the predicate this row mutates, not on the constraint, so the conclusion
  survives and the reason given for it does not.
- **`§166 LR-4`'s extra claim is not reached.** The row predicts the two signals will *disagree on
  one grid row*; `theBooleanAndTheValueCannotDisagree` stayed green, because no fixture case goes
  from executed back to unexecuted.


# DOCUMENTED GAPS — rows retired from the run, 2026-08-22

**Decided by the user on 2026-08-22**, as part of the run plan in
`docs/plans/2026-08-22-backend-mutation-backlog-run-plan.md`. A row lands here when it says, in its
own text, that it **cannot produce a measurement** — the state is unreachable behind a `CHECK`, the
test class hand-builds its subject, a single-threaded test cannot arrange the interleaving, the
fixture points every root at one temp directory. Running such a row buys a confirmed zero the row
has already derived.

**This is a stated deviation from this file's own rule** — *"a row predicting zero is not a row to
skip, the measured number is the deliverable"* — and it is deliberately narrow:

- A row that **predicts** zero and derives it structurally → retired here.
- A row that says *"if this reddens nothing, that is the finding"* → **runs.** The conditional is the
  opposite of a prediction: the row expects a red and is saying what a zero would prove. The first
  count of this register was **95 rows** because a grep swept the conditionals in with the
  predictions; classified per row it is **44**. Recorded because the wrong number is what the
  decision was taken against.
  **And a second correction, found by deriving the set twice:** 44 was itself hand-built, and a
  re-derivation over `mutation-manifest.tsv` disagreed with it in both directions — it had missed
  `CM-6`, `R7-6`, `R8-7`, `§166 LR-5`, `§167 DL-3` and `§168 CV-3`, all zero-predictions in the
  project-predicate family, and had counted ids the prose merely *mentions*. **The authority is the
  manifest's `gap` column**, not this list; the tables below are the reasoned subset, kept because
  the reasoning is the deliverable. Two hand-kept tallies of one set disagreeing is the shape
  §152–§156 replaced four times, arriving inside a register about that very problem.
- A row that says *"may redden nothing, and that would be the finding"* → **runs.** The zero is
  undetermined and the measurement settles it: `FQ-10`, `FQ-14`, `FQ-24`, `AD-5`, `GA-1`, `DR-6`.

**Retiring is not filing away.** Every row below keeps its id — ids are quoted from handoff sections
and are never renumbered — and every one names what would close it. Three of them are not gaps at
all but work, and they are separated out below rather than buried, because that is where the value
of this register actually is.

## A. Nothing to run and nothing cheap to write — the true gaps

| Row | Why no measurement is available | What would close it |
|---|---|---|---|
| ~~`13k`~~ | ~~No fixture writes two rollup rows at measurably different instants, so oldest-vs-newest `computedAt` is unobservable~~ | **WITHDRAWN 2026-08-23** — `RollupAgreementTest.TwoInstants` is that fixture, and the row measures **RED, 2** against it |
| `AT-3` | Automation Service's keys are unqualified in the seeded catalogue, so key-vs-qualifier cannot be told apart | One token carrying Test Engineer, whose `case:delete` is `OWN` |
| `CM-8` (first half) · `CM-9` | `doc_block` has no unique `(page_id, position)`; the fixture has no dangling `doc_link` | A tied-position pair; one `doc_link` naming a `gen_random_uuid()` |
| `CA-6` · `CA-15` · `CA-18` · `CA-20` · `DA-11` (ordering half) · `DA-14` · `FA-4` · `FA-7` · `FA-9` | Single-threaded tests cannot arrange a failure between two writes; no test in this repository counts queries; every storage root is one temp directory in `IntegrationTestBase` | A fault-injecting store; a query counter; a second root in the harness |
| `ST-2` · `ST-12` · `ST-13` | Structural — the cost is a Spring startup failure or a compile error, not a red test | Nothing; the row is the record that ST-1 is the only observer |
| `ST-11` | No interleaving to arrange | A concurrent writer |
| `RR-M3` · `RR-M5` | Same two shapes at the raw-report root: one temp directory, and an ordering that only matters on rollback | Browser row `RR-7`; a rollback-injecting test |
| `§128 CD-13` | The assertion is ASCII, so a `String.length()` ceiling and a UTF-8 one agree | A multi-byte fixture string |
| `§166 LR-1` | `TestExecution.record()` sets status and `executed_at` together, so no fixture may legitimately build the row the predicate guards against | Nothing in-suite; the predicate is load-bearing and unobservable |
| `§167 DL-4` | No test asserts outbox latency directly | A browser row, not a unit test |
| `§168 CV-4` | A null kind and an absent row are collapsed by the caller on purpose, so both spellings agree today | A fourth count in the contract |
| `DR-8` · `GA-6` · `FQ-13` | A second transaction, a concurrent caller, a second writer — none exists | Each is a slice of its own |
| `IH-5` | The defect is a `JSONB` blob pulled per row; no assertion changes | A query log over a 5,000-test history |
| `TS-9` | Two concurrent invocations; the Vitest lane cannot express it | The live pass |
| `§247 SK-M6` | The poller is disabled in `IntegrationTestBase` because it **creates runs** | A direct test of the company loop's `continue` |

## B. Not gaps — tests worth writing, named here so they are not lost

| Row | What it actually is |
|---|---|
| `§133 AG-9` | **The highest-value row in its section.** Not a mutation: the `Agent` design rests on `evaluate(ownerId) ∩ permissionsOf(Agent)` and **nothing drives it**. Issue an `Agent` token owned by a Viewer, `POST` a case, expect 403. §132 already recorded that `IngestionEndpointTest.Attribution` is the only place in the suite where a write is authenticated by a real `api_token` at all. |
| `DR-9` | `CaseHealthProperties`' positive-duration check has **no test on either half**, and with `P0D` the stale/clean pair *inverts* rather than over-fires. |
| `GA-10` | No test asserts on logging, for the widest grant in the product. |

## C. Not gaps — rows that belong to the manual / live lane

`§242 LG-M14` (the project trail has **no index** for `(company_id, project_id, occurred_at DESC)` —
read the plan against a large `audit_event`), `§243 RN-M7` (200 approvals → 200 deliveries; run it to
get the real number), `§244 ED-M6` (nothing in this repository asserts that a hand-recorded duration
reaches the Run Summary report). Each needs the compose stack, not `./gradlew test`.

## WITHDRAWN by wave 2 — 2026-08-23

**Five rows leave this register**, four of them measured RED and one because the fixture that closes
it now exists. Decision 1 required every retired row to name what would close it; this is that
requirement being collected on, and it is the reason the naming was mandatory.

| Row | Withdrawn because | Measured |
|---|---|---|---|
| `R7-6` | `reporting.SiblingProjectReadsTest` — one company, two projects | **RED, 1.** `AggregateVariabilityTest` stayed green, so the row's zero was a fact about its own class |
| `R8-7` | the same fixture, one method over | **RED, 1** |
| `CM-6` | the same fixture, `DocumentationSource.findPage` | **RED, 1**, and the first measurement any of the six project-predicate files has had |
| `§167 DL-3` | **nothing wave 2 wrote** — `CaseHealthRecomputeTest` already holds a sibling-project page | **RED, 2.** A registered gap that never existed, and the third time this pass has found one |
| `13k` | `RollupAgreementTest.TwoInstants` — two rollup rows, two instants | **RED, 2**, both `computedAt` methods |

**`§168 CV-3` stays**, now measured rather than argued: ZERO, because the ids reaching
`lastResultKinds` were already resolved through `#resolve`, which applies the predicate. That is the
row's own reasoning, confirmed.

**Two rows the register does NOT hold, and should be read beside it**, because both predicted a zero
that did not happen: `IK-4` (**RED, 1** — the suite class has a second granted project; the row's
caveat is about the doc-page class) and `§244 ED-M7` (**RED, 1** — the row calls itself *a second
gap*; `ExecutionFactRefreshTest` covers it).

**And one new gap the register gains**: `FQ-10`, one of decision 1's six undetermined rows, measured
ZERO. The `lag()` window's `execution_id` tiebreak never decides anything because the fixture spaces
results a day apart. What closes it is the same-instant fixture, in a fourth place.

## Corrections made while writing this register

- `§232 PB-9` was swept in by the phrase *"not a mutation of new code so much as a re-introduction of
  the defect B9 uncovered"*. It **predicts exactly one red** and runs.
- `§169 XL-6` predicts no red but explicitly asks to be run, because its expected outcome is a
  **loudly aborted recompute** (NULL into a `NOT NULL boolean`) rather than a silent wrong value, and
  those are very different things to inherit. It runs.
- `R6-3` and `R6-5` appear as zero-predictions in their original table and were **already re-run RED
  after R6 phase 4**. They are stale rows, not gaps; the phase-4 table is the live one.

---

## §255 — the unified step model (initiative 001, T1)

✅ **RUN 2026-08-28 on `shanks`, against a green baseline (backend `3408 / 0`).** Every row below was
executed: the mutation applied to the working tree, the named classes run, the result recorded in the
last column, the mutation reverted. **17 of 17 resolved — 13 confirmed as written, 2 with the count
corrected, 1 corrected outright (`SM-M9`), and 2 registered gaps that hold.**

**The one to read is `SM-M9`.** It claimed two arms failing *in opposite directions*, and that pair
does not exist: `anUnmigratedScenarioIsIncomplete`'s fixture carries no `expected_outcome`, so the
expected-outcome/`THEN` disjunct holds it incomplete whatever the step/blob term says. The guard is
one-sided. **And `SM-M15` has a trap for whoever re-runs it**: dropping `ck_test_step_one_data`
without also dropping its `COMMENT ON CONSTRAINT` makes `V69` itself fail, which reddens the whole
suite for a reason that is not the mutation.

Ids are `§255 SM-M*`. `SM` was grepped against every row id in this file and in
`frontend-browser-checks.md` before minting.

**The area's one migration is `V69`** — `ck_test_step_one_data`, `test_case.imported_*`,
`condition.imported_key` and its partial unique index.

| Row | Mutate | Expect | Result (2026-08-28, `shanks`) |
|---|---|---|---|
| `§255 SM-M1` | ⚠️ **The mutation this task is shaped around.** In `CaseEdit.normalizeSteps`, delete the R1 block (the `format == StepFormat.GHERKIN` guard and everything inside it) | **Red** — `CaseEditTest.aKeywordlessStepIsRefused`, `.allTheFaultsCollect`, `.aLeadingAndIsRefused`, `.aLeadingButIsRefused`, plus `CaseUpdateEndpointTest.gherkinNeedsAKeywordOnEveryStep`, `.theFirstStepCannotContinueNothing`, `.allTheStepErrorsCometogether` and `CaseCreateEndpointTest.gherkinNeedsKeywords`. **8.** Without R1 a Gherkin case saves with keywordless rows, which render as `*` in a `.feature` file — legal Gherkin, and not what anybody wrote. | ✅ **RUN — CONFIRMED.** exactly 8, the predicted set |
| `§255 SM-M2` | Narrow R1's guard from the raw value to the parsed one — `if (keyword == null)` instead of `if (keyword == null && trimToNull(step.keyword()) == null)` | **Red — exactly 1**, `CaseEditTest.aBadKeywordIsNotAlsoAMissingOne`. Two messages for one fault, the second of them false: the author is told the step needs a keyword under the sentence saying the keyword they sent is not one. The endpoint arms stay green, which is the point of the unit arm existing. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 SM-M3` | Drop the `i == 0` term from R2, so any `AND`/`BUT` is refused | **Red — exactly 1**, `CaseEditTest.aLaterAndIsFine`. `AND` and `BUT` are the ordinary body of a scenario; refusing them everywhere makes the Gherkin lens unusable and every other R2 arm stays green. | ✅ **RUN — CONFIRMED, count corrected.** 2, not 1. CaseUpdateEndpointTest "R2 allows And/But anywhere but first" also reddens; the row claims the endpoint arms stay green. |
| `§255 SM-M4` | In `normalizeSteps`, drop the R3 check (`nonNulls(...) > 1`) | **Red** — `CaseEditTest.twoSpellingsAreRefused`, `.theOtherPairIsRefusedToo`, `CaseUpdateEndpointTest.oneDataSpellingPerStep`. **3.** The save then reaches `ck_test_step_one_data` and the caller gets a **500** where a field error belongs (catalog I.4). | ✅ **RUN — CONFIRMED.** exactly 3 |
| `§255 SM-M5` | In `normalizeTable`, return `List.of()` instead of `null` for an empty outer list | **Red — exactly 1**, `CaseEditTest.anEmptyTableIsNotASpelling`. `[]` then counts as a data spelling, so a form that submits an empty grid for a mode nobody chose refuses a step whose only real data is `testData`. | ✅ **RUN — CONFIRMED, count corrected.** 6, not 1. Returning [] makes every table-less step carry [], which breaks the round-trip, snapshot and create arms too. |
| `§255 SM-M6` | In `normalizeTable`, trim each cell (`cell.trim()`) | **Red — exactly 1**, `CaseEditTest.cellsKeepTheirWhitespace`. Leading whitespace in a `\|`-cell is escaped content in the Gherkin spec, so trimming makes render∘parse differ from the file — the round-trip property T3 is built on, broken one task before it exists. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 SM-M7` | ⚠️ In `CaseEdit.validate`, restore `format == StepFormat.GHERKIN ? List.of() : steps` in the `Outcome.Valid` construction | **Red** — `CaseEditTest.gherkinKeepsItsSteps`, `CaseUpdateEndpointTest.switchingLensKeepsTheSteps`, `.blockArgumentsRoundTrip`, `.theSnapshotCarriesTheBlockArguments`, `CaseCreateEndpointTest.gherkinCasesStoreSteps`, `ExecutionEndpointTest.theBlockArgumentsReachTheChecklist`. **6.** This is the pre-§255 behaviour: a save answers **200** having discarded the rows the author just wrote, and takes their ids — and therefore every `step_result` recorded against them — with them. | ✅ **RUN — CONFIRMED.** exactly 6, the predicted set |
| `§255 SM-M8` | Drop the `THEN` term from `CaseCompleteness.recomputeIncomplete` (the third `NOT EXISTS`) | **Red — exactly 1**, `CaseHealthRecomputeTest.Completeness.aThenRowSaysWhatShouldHappen`. Every finished Gherkin case in the product wears an `INCOMPLETE` chip, because a scenario states its outcome in its `Then` clause and nowhere else. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 SM-M9` | Restore the `step_format = 'GHERKIN'` branch of that predicate (`coalesce(btrim(gherkin_source), '') = ''`) | **Red** — `.aThenRowSaysWhatShouldHappen` (a migrated case has a null blob, so the branch calls it incomplete) and `.anUnmigratedScenarioIsIncomplete` (the branch calls the un-migrated case healthy). **2**, and they fail in opposite directions, which is what makes the pair worth having. | ⚠️ **RUN — CORRECTED.** 1, not 2. anUnmigratedScenarioIsIncomplete does NOT redden: its fixture has no expected_outcome, so the expected-outcome/THEN disjunct holds it incomplete whatever the step/blob term says. The "opposite directions" pair the row describes does not exist. |
| `§255 SM-M10` | In `TestCaseDetailView.of`, serve `row.getGherkinSource()` unconditionally | **Red — exactly 1**, `CaseDetailEndpointTest.gherkinCasesCarryRowsAndNotTheBlob`. One procedure on the screen twice; the two disagree after the first edit, because nothing writes the blob back. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 SM-M11` | In the same place, serve `null` unconditionally | **Red — exactly 1**, `.aCaseWithNoRowsFallsBackToTheBlob`. Every un-migrated Gherkin case in the product silently loses its procedure on upgrade — the failure the whole `002-` gate exists to prevent, arriving early. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 SM-M12` | ⚠️ In `TestStepView.of`, pass `null, null` for `docString`/`dataTable` (the "converter dropped" arm) | **Red** — `CaseDetailEndpointTest.blockArgumentsAreServed`, `CaseUpdateEndpointTest.blockArgumentsRoundTrip`, `.theSnapshotCarriesTheBlockArguments`, `CaseCreateEndpointTest.gherkinCasesStoreSteps`, `ExecutionEndpointTest.theBlockArgumentsReachTheChecklist`. **5.** The snapshot arm is the one that matters: this view IS the version snapshot's step shape, so a break here reaches the tester and not the author. | ✅ **RUN — CONFIRMED.** exactly 5, the predicted set |
| `§255 SM-M13` | In `CaseFacts.steps`, drop the two new arguments (pass `null, null` to `PinnedStep`) | **Red — exactly 1**, `ExecutionEndpointTest.theBlockArgumentsReachTheChecklist`. **Every case-screen assertion stays green**: execution renders the pinned snapshot, not `test_step`, so this is the break no test on the authoring side can see. | ✅ **RUN — CONFIRMED.** exactly 1; every case-screen arm stays green as the row says |
| `§255 SM-M14` | Restore `this.gherkinSource = edit.gherkinSource()` in `TestCase.applyEdit` (requires putting the component back on `CaseEdit`) | **Expected red at the compile boundary**, and if made to compile, **red in `ArchitectureRulesTest.everyMappedColumnHasAWriter`**: the `TestCase.gherkinSource` entry in `WRITERLESS_BY_DECISION` names a column that now has a writer, and that map is pinned in both directions. ⚠️ Recorded because the exemption is what stops the column quietly re-acquiring a writer before `002-` can drop it. | ✅ **RUN — CONFIRMED.** both halves - compile error at the literal mutation, then everyMappedColumnHasAWriter red once made to compile |
| `§255 SM-M15` | Drop `ck_test_step_one_data` from `V69` | **Expected: no red.** ⚠️ **A registered gap, stated rather than discovered.** R3 refuses the payload first, so nothing in the suite reaches the constraint — it is the backstop for a writer that is not `CaseEdit`, and the backfill (T5) and the import (T6) are those writers. What would close it is a test that inserts a two-spelling step through raw SQL and asserts the constraint refuses it; it is not written, because this task adds no such writer. | ✅ **RUN — CONFIRMED.** registered gap holds - 0 red across 1072 arms. NOTE the mutation must also drop the COMMENT ON CONSTRAINT or V69 itself fails and everything reddens for the wrong reason. |
| `§255 SM-M16` | Drop `condition_imported_key_idx` from `V69` | **Expected: no red.** ⚠️ **A second registered gap, and the same shape.** Nothing maps `condition.imported_key` until T6, so the index has no writer to collide. It is added here rather than in T6's migration because `V69` is the one `ALTER` this initiative gets and splitting it to keep a rule quiet is the tail wagging the schema. T6's `FeatureImportTest` race arm is what closes it. | ✅ **RUN — gap holds, and a claim in it is false.** 0 red as predicted, BUT the row's forward claim is false: it says "T6's FeatureImportTest race arm is what closes it" and T6 IS in this run. The index is still unexercised - the dedup is in application code and the race needs concurrent requests the harness cannot stage (cf FI-M41). |
| `§255 SM-M17` | Remove the `StepKeyword` entry from `ContractEnumTest.OWNERS` | **Red — exactly 1**, `ContractEnumTest`'s pairing rule. The map is asserted to cover the contract's enum schemas *exactly*, so extracting a named enum and not naming its owner fails — which is the §255 half of the check that also fails when a member is added on one side only. | ✅ **RUN — CONFIRMED, count corrected.** 2, not 1: "no production enum shares a contract enum's name without being it" reddens too. |

---

## §255 — the parser, paste and render (initiative 001, T3)

✅ **RUN 2026-08-28 on `shanks`, against a green baseline.** **24 of 24 resolved — 15 confirmed as
written, 6 with the count corrected, 1 corrected outright (`GP-M10`), and 2 registered gaps that
hold.**

**Two findings outlive the table.** `GP-M7` showed that
`everyConstructSurvivesTheRoundTrip` **cannot see a keyword the parser drops**: it compares
`parse∘render∘parse` against `parse`, so a loss on the way in is a loss on both sides and the
fingerprints agree. It is an *idempotence* check, not a fidelity one — the fidelity check is the
`Constructs` arms, and `GP-M23` measured that at 8 of them, not the 9 the row claimed. And `GP-M10`
reddens a *different* arm than the one it names: `postconditionCommentsAreNotReRead`'s fixture has
no preceding `# expected:` for the block to continue onto, so only the endpoint round trip catches
it. **`GP-M23` also needs `rsync --delete`** — a deletion mutation that does not reach the runner
reports a silent, false 0.

Ids are `§255 GP-M*`. `GP` was grepped against every row id in this file, in
`packages-and-frontend-mutation-checks.md`, in `frontend-browser-checks.md` and across
`docs/handoff/` before minting, per the §154 banner.

**T3 adds no migration.** `V69` is still the head and `V70` is reserved for `002-`. The one build
change is the `io.cucumber:gherkin` dependency, pinned at **42.0.1** with `messages` and `jspecify`
pinned beside it — deviation **D5**.

| Row | Mutate | Expect | Result (2026-08-28, `shanks`) |
|---|---|---|---|
| `§255 GP-M1` | ⚠️ **The mutation criterion 3 is shaped around.** In `CaseController.parseGherkin`, delete the `catch (GherkinSyntaxException refused)` block so the exception propagates | **Red** — `CaseGherkinEndpointTest.aSyntaxErrorIsA400`, `.anOutlineIsRefused`, `.theOtherTwoRefusalsAreDistinct`. **3.** Every one of them then answers **500**: a stack trace where a line number belongs, on the one refusal a person can actually fix. | ✅ **RUN — CONFIRMED.** exactly 3 |
| `§255 GP-M2` | In `CaseController.typeOf`, return `"about:blank"` for every reason | **Red** — the same three arms, on the `type` assertion rather than the status. **3.** The four refusals become indistinguishable to a machine client, which is the whole reason they are typed at all. | ✅ **RUN — CONFIRMED.** exactly the same 3 |
| `§255 GP-M3` | In `GherkinScenarios.parse`, delete the wrapped retry (both `Attempt wrapped` lines) | **Red** — `GherkinScenariosTest.aBareStepListParses`, `.aScenarioBlockParses`, `.aBareListInAnotherDialectParses`. **3.** The commonest paste in the product — three step lines copied out of a ticket — refuses at line 1, which is the failure the fallback exists for. | ✅ **RUN — CONFIRMED, count corrected.** 4, not 3: "a multi-line value comes back joined" also depends on the wrapped retry |
| `§255 GP-M4` | ⚠️ In `parse`, drop the `direct.feature() == null` guard so a document that parsed is wrapped anyway | **Red — exactly 1**, `.nothingToParseIsRefused[3]` (the `Feature:` + `Background:` case). It becomes a **plausible success**: the file's own `Feature:` and `Background:` lines land inside a synthetic scenario, where they read as the objective. The other two `nothingToParseIsRefused` rows stay green, which is why the third exists. | ✅ **RUN — CONFIRMED.** exactly 1 (nothingToParseIsRefused[3]) - the scratchpad defect stays caught |
| `§255 GP-M5` | In `wrap`, drop the `declaresScenario` guard so a `Scenario:` is always added | **Red — exactly 1**, `.aScenarioBlockParses`. A pasted `Scenario:` block refuses as *"This text holds 2 scenarios"* — its own, plus the one wrapped around it. | ✅ **RUN — CONFIRMED, count corrected.** 2, not 1: same extra continuation arm |
| `§255 GP-M6` | ⚠️ In `keywordOf`, move the `"*".equals(keyword)` test below the five keyword-list tests | **Red — exactly 1**, `.starStepsCarryNoKeyword`. The dialect lists `*` under **every** keyword, so a format-neutral step silently becomes `GIVEN` — legal, plausible, and not what the author wrote. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 GP-M7` | In `keywordOf`, delete the `getButKeywords` branch | **Red** — `.everyKeywordMaps`, `.theDeclaredDialectIsHonoured`, and `everyConstructSurvivesTheRoundTrip[All five keywords…]`. **3.** `BUT` falls through to `null`, so a `But` step renders as `*` and the round trip is lossy for a keyword an author typed. | ✅ **RUN — CONFIRMED, count corrected.** 2 of 3. everyConstructSurvivesTheRoundTrip stays GREEN and structurally cannot bite: it compares parse-render-parse against parse, so a keyword the parser drops is dropped identically on both sides. It is an IDEMPOTENCE check, not a fidelity one. |
| `§255 GP-M8` | ⚠️ In `cell`, escape the pipe before the backslash (swap the two `replace` calls) | **Red** — `.dataTableEscapesRoundTrip` and `everyConstructSurvivesTheRoundTrip[Block arguments]`. **2.** A cell holding `a\b` grows a backslash on every round trip — the failure that is invisible on one pass and compounding on many. | ✅ **RUN — CONFIRMED.** exactly 2 |
| `§255 GP-M9` | In `document`, delete the two `annotation(...)` calls after the step lines | **Red** — `everyConstructSurvivesTheRoundTrip[The two fields…]` and `CaseGherkinEndpointTest.whatIsRenderedIsWhatIsParsed`, `.theWholeCaseRenders`. **3.** `expectedResult` and `testData` have no Gherkin syntax, so dropping their comment lines makes "view as .feature" a **lossy export** rather than a view. | ✅ **RUN — CONFIRMED.** exactly 3 |
| `§255 GP-M10` | In `stepComments`, treat any comment as a continuation (drop the `CONTINUATION_PREFIX` and adjacency tests) | **Red** — `.postconditionCommentsAreNotReRead` and `.aMultiLineAnnotationIsJoined` stays green. **1.** A case's postconditions are appended to the last step's expected result on every re-import — a postcondition silently becoming part of a step. | ⚠️ **RUN — CORRECTED.** 1 red as the count says, but a DIFFERENT arm. postconditionCommentsAreNotReRead stays green - its fixture has no preceding "# expected:"/"# data:" for the block to continue onto, so it cannot catch this. Only CaseGherkinEndpointTest's round trip does. |
| `§255 GP-M11` | In `document`, delete the `postconditions` block | **Red — exactly 1**, `CaseGherkinEndpointTest.theWholeCaseRenders`. The text is then a lossy view of the case for the human reading it, and nothing on the parse side notices because the parser is supposed to ignore the block. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 GP-M12` | In `isOutline`, drop the `matches(dialect.getScenarioOutlineKeywords(), …)` term | **Red — exactly 1**, `.aKeywordOnlyOutlineIsRefused`. A template whose `Examples:` block is still to be written imports `Given a balance of <amount>` as a **literal step**, and `.anOutlineIsRefusedWithItsLine` stays green. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 GP-M13` | In `isOutline`, drop the `getExamples().isEmpty()` term instead | **Expected: no red.** ⚠️ **A registered gap.** Both fixtures spell the keyword, and the parser sets `Scenario Outline` on any scenario with examples — so the keyword term alone answers every case the suite has. What would close it is a scenario named with a `Scenario Outline` **alias** (`Scenario Template`) or a dialect where the two differ; it is not written because the keyword list already covers both aliases and the arm would assert the library rather than this code. | ✅ **RUN — the registered gap HOLDS.** 0 red across 73 arms including FeatureImportTest |
| `§255 GP-M14` | In `sentence`, return `error.getMessage()` unchanged | **Red** — `.aSyntaxErrorNamesTheLine` and `CaseGherkinEndpointTest.aSyntaxErrorIsA400`. **2.** The author is shown `(4:3):`, which is a spelling no end user reads and which the SPA cannot turn into a line badge. | ✅ **RUN — CONFIRMED.** exactly 2 |
| `§255 GP-M15` | In `dedent`, return the description verbatim | **Red** — `.theObjectiveIsDedented` and `everyConstructSurvivesTheRoundTrip[Block arguments]`. **2.** The objective arrives with four leading spaces on every line, the editor shows them, and a save stores them — so the second round trip indents it again. | ✅ **RUN — CONFIRMED, count corrected.** 3, not 2: the paste endpoint's 200-shape arm also bites |
| `§255 GP-M16` | In `backgroundOf`, return one entry for the whole Background (join the steps) | **Red** — `.aBackgroundIsOneEntryPerStep`, `CaseGherkinEndpointTest.theWholeResultIsServed`, `.whatIsRenderedIsWhatIsParsed`. **3.** AM1's decision reversed: one library condition holding two unrelated setup steps, which no other case can reuse half of. | ✅ **RUN — CONFIRMED.** exactly 3 |
| `§255 GP-M17` | In `GherkinScenarios.render(TestCaseDetailView)`, delete the zero-row `gherkinSource` fallback | **Red — exactly 1**, `CaseGherkinEndpointTest.aLegacyCaseRendersItsBlob`. Every un-migrated Gherkin case renders an **empty `Scenario:`** — telling a reader the case has no procedure while the detail screen is still showing them one. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 GP-M18` | In the same method, use `view.key()` unconditionally as the feature name | **Red — exactly 1**, `.theWholeCaseRenders`. `<Feature name>#<Scenario name>` is what a Cucumber report keys on (`CucumberReportParser.fullName`), so every exported case stops matching the reports the team already posts. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 GP-M19` | In `CaseDetailService.parseGherkin`, call `GherkinScenarios.parse(text)` directly instead of through `projects.readVisible` | **Red — exactly 1**, `CaseGherkinEndpointTest.theGateIsProjectVisibility`. A member of the company holding no grant can drive the parser against any project id. It discloses nothing — the endpoint reads no row — and it is still a gate this endpoint is specified to have (V5.3). | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 GP-M20` | In `CaseController.parseGherkin`, delete the `MAX_PASTE_BYTES` check | **Red — exactly 1**, `.anOversizedPasteIsA413`. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 GP-M21` | Remove `consumes = MediaType.TEXT_PLAIN_VALUE` from the `@PostMapping` | **Red — exactly 1**, `.aJsonBodyIsA415`. Spring then binds the JSON **document** as the scenario text and answers `gherkin-syntax` about a body the caller never meant as Gherkin. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 GP-M22` | Remove the `GherkinParseResult` and `ParsedBackground` entries from `ContractConventionTest.PAIRS` | **Red** — `ContractConventionTest.everyObjectSchemaIsPairedOrExempt` for `ParsedBackground`; `GherkinParseResult` is proposed by the name match and stays green. **1.** §249's cache entry records a slice missing exactly this, so the row exists to say which half of the pair the name match cannot reach. | ✅ **RUN — CONFIRMED, count corrected.** 2, not 1: the reverse pairing arm reddens too |
| `§255 GP-M23` | Delete `backend/src/test/resources/gherkin/everything.feature` | **Red** — every arm that reads it: the parameterized round trip, `.theFixtureIsStillTheContractWithTheLibrary`, and the nine `Constructs` arms that call `scenarioNamed`. ⚠️ Recorded because the fixture **is** the contract with the library (`integrations.md` X1): a major bump that changes any construct has to fail against a file this repo owns rather than in a customer's import. | ✅ **RUN — CONFIRMED, count corrected.** 10 red - 8 Constructs arms (not the 9 claimed), the round trip's initializationError, and the fixture guard. NOTE: a DELETION mutation needs rsync --delete; without it the runner silently reports 0 red. |
| `§255 GP-M24` | Change `MAX_PASTE_BYTES` to count characters (`source.length()`) instead of UTF-8 bytes | **Expected: no red.** ⚠️ **A registered gap.** The 413 fixture is ASCII, so the two counts agree on it. What would close it is a paste of ~200 000 multi-byte characters — over the byte cap and under the character cap — asserted as 413; it is not written because a 400 KB literal in a test source is a cost the row can name instead of paying. | ✅ **RUN — the registered gap HOLDS.** 0 red - the 413 fixture is ASCII, so byte and char counts agree |

## §255 T5 — `migrate-gherkin-source`, the backfill off `gherkin_source`

✅ **RUN 2026-08-28 on `shanks`, against a green baseline.** This heading now covers **both** T5's
`BF-M*` rows and T6's `FI-M*` rows, and all 67 were executed. **T5: 24 of 24 — 12 confirmed as
written, 6 with the count corrected, 4 registered gaps that hold, 1 refuted and 1 not reachable.
T6: 43 of 43 — 14 confirmed, 8 with the count corrected, 9 registered gaps that hold, 3 refuted,
1 corrected, 1 broader than written, and 1 not expressible.**

**`BF-M1` is the row to read first, and it is REFUTED.** It claims the loudest failure in the class;
in fact **nothing anywhere drives `MigrateGherkinSourceCommand.run`** — `MigrateGherkinSourceTest`'s
own helper opens the `CompanyContext` and calls `backfill.run` directly, and `run` ends in
`System.exit`, which no in-process test survives. So the §246 context/transaction ordering this
command depends on has no test at all, and `BF-M2` is unreachable for the same reason. Three more
were refuted in T6 and each names a real hole: **`FI-M20`** (the load-bearing per-row permission
order — the arm previews `catalogue.feature`, which has no `Background:`, so the order is
unobservable), **`FI-M31`** (the orphan filter — the arm previews against a suite holding only a
case the files do not name), and **`FI-M35`** (nothing asserts that an imported case is actually
*filed* into the suite the plan created; the counts hold with every case unfiled). `FI-M11`, the row
called the initiative's likeliest real defect, is confirmed **uncovered**.

Ids are `§255 BF-M*`. `BF` was grepped against every row id in this file, in
`packages-and-frontend-mutation-checks.md`, in `frontend-browser-checks.md` and across
`docs/handoff/` before minting, per the §154 banner.

**T5 adds no migration and no contract change.** `V69` is still the head, `V70` is reserved for
`002-`, and `openapi.yaml` is untouched — this is an operator command, not an endpoint. The one
structural change is where the command **lives**: `com.tcms`, the composition root, rather than
`com.tcms.provisioning` — deviation **D11**, and `BF-M14` is the row that would catch it being
"tidied" back.

| Row | Mutate | Expect | Result (2026-08-28, `shanks`) |
|---|---|---|---|
| `§255 BF-M1` | ⚠️ **The §246 row.** In `MigrateGherkinSourceCommand.run`, move `CompanyContext.open(...)` so it wraps nothing — call `backfill.run(...)` outside the try-with-resources | **Red** — `MigrateGherkinSourceTest.theRunIsNotEmpty`, and then every arm in `Migrating` and `Refusing`. `CompanyContext.require()` throws, so this one is loud. The row exists for its **sibling**, `BF-M2`, which is the quiet version of the same defect. | ❌ **RUN — REFUTED. The named arm does not redden.** 0 red, not "every arm in Migrating and Refusing". NOTHING DRIVES MigrateGherkinSourceCommand.run: the test helper opens CompanyContext itself and calls backfill.run directly, and `run` ends in System.exit so no in-process test can. A REAL GAP the queue recorded as covered. |
| `§255 BF-M2` | ⚠️ In `GherkinBackfill.run`, wrap the whole body in `transactions.execute(...)` and open the context inside it | **Red — exactly 1**, `.theRunIsNotEmpty`. This is the failure that looks like success: `CompanyAwareTransactionManager` reads the context in `doBegin`, so a context opened after the transaction began leaves the RLS GUC unset, every policy evaluates `company_id = NULL`, and the command reports **a clean nothing** with exit 0 — which `002-`'s gate would then read as "this instance is done". Every other assertion in the class passes, because they are all about rows that were never read. | ❌ **RUN — NOT REACHABLE by this harness.** 0 red. The mutation spans GherkinBackfill.run AND the caller's context ordering, and that caller is the untested command.run. Inside the backfill alone the ambient context is already open before any transaction begins, so it cannot bite. |
| `§255 BF-M3` | In `migrateOne`, delete the `versions.decide` / `advanceVersion` / `versions.write` block, keeping `steps.reconcile` | **Red** — `.aVersionIsMinted`, `.theSnapshotCarriesTheSteps`, `.aReplayIsANoOp`. **3.** The rows land and look right. What breaks is months later and elsewhere: `CaseVersions`' hash covers `CaseEdit`, so every migrated case carries a stored hash that no longer describes it, and **the next ordinary save by a real author writes the version attributing this migration to them** — in the one screen whose whole job is to say who changed what. | ✅ **RUN — CONFIRMED.** exactly 3 |
| `§255 BF-M4` | In `migrateOne`, pass `null` instead of `actorUserId` to `versions.write` | **Red — exactly 1**, `.aVersionIsMinted` on the `createdBy` assertion. The column is nullable, so nothing in the schema refuses it, and `002-`'s reviewer loses the one column that says the migration happened rather than somebody's save. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 BF-M5` | In `migrateOne`, render the snapshot **before** `steps.reconcile` | **Red — exactly 1**, `.theSnapshotCarriesTheSteps`. Every execution planned afterwards pins a version whose procedure is empty — the rows right, the checklist blank, which is precisely the state this initiative exists to end. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 BF-M6` | In `GherkinBackfill.run`, return before the `dryRun` check — i.e. delete `if (dryRun) { … }` from `migrateOne` | **Red** — `.aDryRunWritesNothing` (four assertions). **1 arm.** The flag an operator reaches for *because* they are unsure would migrate a client's whole repository. | ✅ **RUN — CONFIRMED, count corrected.** 2 arms, not 1 |
| `§255 BF-M7` | In `migrateOne`, delete the `!stepRows.findForCase(...).isEmpty()` guard | **Red** — `.aMigratedCaseIsSkipped` and `.aReplayIsANoOp`. **2.** A replay writes a second version on every case — the empty-diff noise plan §6j added the content hash to keep out, arriving on the whole repository at once — and `CaseSteps.reconcile` would delete the existing row and re-insert it, **reassigning its id** and orphaning every `step_result` recorded against it. | ✅ **RUN — CONFIRMED, count corrected.** 3, not 2 |
| `§255 BF-M8` | In `TestCaseRepository.findStillOnTheBlob`, add `AND NOT EXISTS (SELECT 1 FROM TestStep s WHERE s.caseId = tc.id)` | **Red — exactly 1**, `.aMigratedCaseIsSkipped` (no entry for `MGSL-5`). The second run prints nothing at all, which an operator cannot tell from a run against the wrong company or from a command that silently did not execute. The report's value on run two is that it lists the same cases and says each is done. | ✅ **RUN — CONFIRMED, count corrected.** 3, not 1 |
| `§255 BF-M9` | In `migrateOne`, catch `GherkinSyntaxException` and return `Entry.migrated(key, 0, null)` | **Red** — `.unparsableTextIsListed`, `.anOutlineIsListed`, `.theGateIsCountable`. **3.** A case whose text a person wrote is recorded as migrated with no steps; `002-` then drops the column and the procedure is gone with no record that one ever existed. | ✅ **RUN — CONFIRMED, count corrected.** 4, not 3 |
| `§255 BF-M10` | In `migrateOne`, re-word the refusal — return `"could not be parsed"` instead of `refused.getMessage()` | **Red** — `.unparsableTextIsListed` on the `Line \d+` pattern, `.anOutlineIsListed` on `Line 2`. **2.** The report becomes a list of case keys with no line to look at, on the one artifact whose entire purpose is telling a human what to fix. | ✅ **RUN — CONFIRMED.** exactly 2 |
| `§255 BF-M11` | In `GherkinBackfill.run`, treat an invisible project as visible — delete the `contextFor(...) == null` branch and let `renderer.vocabularies` answer `List.of()` | **Red — exactly 1**, `.anUngrantedProjectIsListed`. ⚠️ **And that arm is weaker than the defect.** The fixture's projects have no classification dimensions, so the snapshot would be identical either way; what the arm actually pins is that the case is *listed* rather than migrated. **What would close it** is seeding `tcms_seed_project_defaults` on `ACME_MOBILE`, classifying `UNSEEN`, and asserting the snapshot's `classificationValueIds` — not written, because the mutation is caught by the outcome assertion today and the fixture cost is a project's whole vocabulary. | ✅ **RUN — CONFIRMED, count corrected.** 2, not 1 |
| `§255 BF-M12` | In `GherkinBackfill.validate`, pass `ClassificationVocabulary.none()` as the *stored* classification too — i.e. replace `classifications.liveValueIdsOf(...)` with `Set.of()` | **Expected: no red.** ⚠️ **A registered gap, and the same hole as `BF-M11` from the other side.** No fixture case is classified, so the two arguments agree. The defect it would cause is the one `CaseEdit.of`'s javadoc names in as many words: every migrated version hashes as though the case had been un-classified, and the next ordinary save reads as a change that restored it. Closed by the same fixture `BF-M11` names. | ✅ **RUN — the registered gap HOLDS.** 0 red |
| `§255 BF-M13` | In `GherkinBackfill.validate`, build the request directly instead of through `CaseUpdateRequest.overlay` — e.g. pass `null` for `stored.title()` | **Red** — `.theBlobBecomesRows` would still pass; `.aVersionIsMinted` reddens because validation fails on `title` and the case is reported `NOT_MIGRATED`. **1.** Recorded because the *plausible* version of this mutation — a hand-written 21-field copy that happens to be correct today — is exactly the second normalizer the overlay exists to prevent, and **no arm here would catch that one**. What would catch it is a case saved through `PATCH` immediately after migration asserting no second version; not written, because it needs the MockMvc lane in a class that has no controller in it. | ✅ **RUN — CONFIRMED, count corrected.** 7, not 1 - and the row's claim that theBlobBecomesRows "would still pass" is false; it reddens. |
| `§255 BF-M14` | Move `MigrateGherkinSourceCommand` into `com.tcms.provisioning` and add `"repository"` to that package's `allowedDependencies` | **Red** — `ArchitectureRulesTest.crossModuleDependenciesPointOnlyAtTheFoundation` and `ModuleStructureTest.declarationsNameOnlyTheFoundationModules`. **2, in two different classes.** The row exists because the tidy-up is obvious and the two rules are the only thing that says no: `repository` is not a foundation module, and it must not become one — `execution` may not depend on it, and that is `execution`'s whole design (plan §3). Deviation **D11**. | ✅ **RUN — CONFIRMED.** both halves, exactly the 2 named rules in the 2 named classes (measured separately: the declaration alone trips ModuleStructureTest, a real provisioning->repository reference trips ArchitectureRulesTest). NOTE the literal move also breaks compilation - the tests use package-private members. |
| `§255 BF-M15` | In `MigrateGherkinSourceCommand.resolve`, accept any membership row — drop the `MembershipStatus.ACTIVE` argument for `findByCompanyIdAndUserId(...).isPresent()` | **Red — exactly 1**, `.anInvitedMemberIsRefused`. An invitation that was raised and never accepted becomes an author: a name in a client's version history belonging to somebody who never agreed to be there. `CompanyMembershipRepository`'s header calls the ACTIVE check "*the* gate". | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 BF-M16` | In `resolve`, look the actor up **after** opening the company context | **Expected: no red.** ⚠️ **A registered gap.** `app_user` is instance-level and carries no RLS, so the lookup answers the same either way and every arm stays green. What it costs is the criterion's "nothing opened" — an unknown address would open a client's company before refusing. Closing it needs an observer for "was a context ever opened", which this codebase has none of; recorded rather than faked with a spy that would only assert its own wiring. | ✅ **RUN — the registered gap HOLDS.** 0 red |
| `§255 BF-M17` | In `exitCodeOf`, answer `EXIT_USAGE` for `NoSuchCompany` as well | **Red — exactly 1**, `MigrateGherkinSourceCommandTest.theExitCodes`. A wrapper script can no longer tell "you typed the company wrong" (a typo) from "grant this account a membership" (work somebody has to do). | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 BF-M18` | In `report`, return `EXIT_OK` unconditionally | **Expected: no red.** ⚠️ **A registered gap, and the most consequential one in this table.** Nothing asserts the exit code of a *run* — `report` is private and `run` ends in `System.exit`, which no in-process test survives. `002-`'s release gate is "the report shows zero cases left on the blob", and exit 3 is how a script reads that without parsing prose. What would close it: extract the code from the report the way `Resolution` was extracted from `resolve` (`static int exitCodeOf(GherkinBackfillReport)`), and assert it over the three shapes. **Not done in T5**, and named here rather than left to be discovered — `report.stillOnTheBlob()` *is* asserted (`.theGateIsCountable`), so the arithmetic is covered and only the mapping is not. | ✅ **RUN — the registered gap HOLDS.** 0 red - 002-'s release signal genuinely has no test |
| `§255 BF-M19` | In `MigrateGherkinSourceCommand.parse`, ignore a value on `--dry-run` (delete the `takes no value` check) | **Red — exactly 1**, `.refusesAValueOnTheFlag`. `--dry-run=false` reads to whoever typed it as "do not do it", and a flag whose value is ignored does the opposite — against a client's whole repository. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 BF-M20` | In `GherkinBackfill.noteFor`, return `null` unconditionally | **Red — exactly 1**, `.aBackgroundIsListedAndTheScenarioSurvives` on the `note()` assertion. The scenario still migrates and its Background's setup steps are silently dropped — `data-model.md` §4 says they are the operator's to attach by hand, and the note is the only place anybody is told. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 BF-M21` | In `noteFor`, refuse the case instead — return a `NOT_MIGRATED` entry when the Background is non-empty | **Red** — `.aBackgroundIsListedAndTheScenarioSurvives` (both halves) and `.theGateIsCountable`. **2.** The over-correction of `BF-M20`: a perfectly parsable scenario is left on the blob to make a point about its preconditions, and `002-` is blocked by cases nothing is wrong with. | ✅ **RUN — CONFIRMED.** exactly 2 |
| `§255 BF-M22` | In `migrateOne`, apply the parsed `title` to the case (call `TestCase.applyEdit` with it) | **Red — exactly 1**, `.aBackgroundIsListedAndTheScenarioSurvives` on the title assertion. `data-model.md` §4 says the blob's `Feature:` header and `Scenario:` name are ignored — the case's own title is authored, and the template copied it into the scenario name, so honouring it is mostly a no-op and occasionally an overwrite of a sentence somebody typed. **`MGSL-2` is the only fixture where the two differ**, and it differs on purpose: on every other case a mutation that wrote the scenario name back would be invisible. | ✅ **RUN — CONFIRMED.** exactly 1, but ONLY when both halves are applied. Putting the parsed title in the request reddens NOTHING on its own: migrateOne never calls applyEdit, so the backfill never writes the case row's own columns. The title protection is structural, not request-assembly. |
| `§255 BF-M23` | In `migrateOne`, clear `gherkin_source` after a successful migration | **Red — exactly 1**, `.theBlobSurvives`. The migration becomes irreversible on text nobody has re-read, and `002-`'s own gate — *"the report shows zero cases left on the blob"* — stops being checkable against the column it is about. | ✅ **RUN — CONFIRMED, count corrected.** 2, not 1 |
| `§255 BF-M24` | In `GherkinBackfill.run`, iterate the candidates without grouping — resolve `contextFor` once per case | **Expected: no red.** The answers are identical; what changes is `2 + 2n` project reads instead of `2` per project. Recorded because it is the N+1 `CaseRenderer`'s two-step `render` exists to avoid one class over, and because a one-time operator command is exactly where nobody would notice it. | ✅ **RUN — the registered gap HOLDS.** 0 red - answers identical, only the read count changes |
| `§255 FI-M1` | In `FeatureImportService.existingCases` / `planScenario`, spell the key by hand — replace `AutomationKeys.cucumber(feature, scenario.name())` with `feature + " - " + scenario.name()` | **Red** — `FeatureImportTest.theKeysMatchTheReport`, `.theWholeFolderIsPlanned` (the `keysOf` assertion), and every arm that looks a case up by key. **Many, and that is the point of the row rather than a weakness of it:** the defect it models is silent in production. Nothing throws when the import's spelling and `CucumberReportParser`'s drift — every ingested result simply stops matching, `unmatched_count` equals the number of tests that ran, and a run reads as a suite nobody has mapped. `com.tcms.execution.AutomationKeysTest.theParserAgreesWithTheSpeller` is the same check from the module that cannot import this one. | ✅ **RUN — CONFIRMED.** 18 red across the class - "many, and that is the point" |
| `§255 FI-M2` | In `AutomationKeys.join`, change the separator to `"::"` | **Red** — `AutomationKeysTest.theKeysAreTheImportsKeys` (the four hand-written strings) and `FeatureImportTest.theKeysMatchTheReport`. **2 classes.** The hand-written expectations are what make this catchable at all: `theParserAgreesWithTheSpeller` alone would stay GREEN, because both sides go through the changed method. Recorded because that is the trap in any "one speller" test. | ✅ **RUN — CONFIRMED, and broader than written.** 36 red across FeatureImportTest, AutomationKeysTest AND IngestionEndpointTest. The row named 2 classes; the separator is load-bearing through the whole ingestion path. |
| `§255 FI-M3` | In `IngestedResult.fullName`, inline the old join back (`className + "#" + name`) instead of calling `AutomationKeys.join` | **Expected: no red.** ⚠️ **A registered gap.** The two spellings agree today, which is the whole point, so re-duplicating the code changes no answer. What it costs is the property, not the value: the next edit to the separator moves one copy. **What would close it** is an ArchUnit rule — "no production string literal `"#"` outside `AutomationKeys` in `execution` or `repository`" — not written, because `#` is a one-character literal with dozens of innocent uses and the rule would be mostly exemptions. | ✅ **RUN — the registered gap HOLDS.** 0 red |
| `§255 FI-M4` | In `keptStepIds`, return `scenario.steps()` unchanged — i.e. never assign an id | **Red — exactly 1**, `FeatureImportTest.stepIdsSurviveAnUpdate` (all three assertions). Every re-import deletes and re-inserts every step of every updated case, **taking the `step_result` rows anchored to them**. The case screen looks correct afterwards; the evidence, verdicts and comments of every run that executed it are gone, and nothing reports it. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 FI-M5` | In `keptStepIds`, do not `available.remove(i)` — leave a claimed row claimable | **Expected: no red.** ⚠️ **A registered gap.** No fixture scenario repeats a step, so no submitted step ever competes for one stored row. The defect is that a scenario with two identical `And I wait` lines binds both to the first row's id, and `CaseSteps.reconcile` is then handed a duplicate id. **What would close it**: a fixture feature whose scenario repeats one step verbatim, asserting the two rows keep two ids. Not written — it is a sixth fixture file for one arm, and the claim-and-consume loop is three lines that read as what they are. | ✅ **RUN — the registered gap HOLDS.** 0 red |
| `§255 FI-M6` | In `planScenario`, drop the sibling-project check — delete the `!existing.getProjectId().equals(projectId)` branch | **Red — exactly 1**, `.aSiblingProjectsKeyIsRefused`. The row plans `CREATE`, the insert hits `test_case_automation_key_idx` (unique per **company**, `V1:542-543`), and the whole request rolls back with a 409 — so one scenario whose key a neighbouring project already holds refuses the other thirty-nine files, with a message naming a constraint rather than a case. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 FI-M7` | In `planExisting`, move the `imported_source` comparison AFTER the `keys.edit()` check | **Expected: no red.** The fixture's `UPDATE` callers all hold `case:edit`, so the order is unobservable. What it costs: a caller without `case:edit` re-importing an unchanged folder is told they need a permission to do **nothing**, on every row. Recorded rather than fixed by a fixture, because the arm would need a persona with `case:create` and not `case:edit`, and no role in the catalogue holds that pair either. | ✅ **RUN — the registered gap HOLDS.** 0 red, as predicted |
| `§255 FI-M8` | In `planExisting`, delete the `imported_source` comparison entirely | **Red** — `.anUnchangedFileMintsNoVersion` and `.aReplayIsANoOp` (the version count). **2.** Every re-import of an unchanged folder mints a version per case whose diff is empty — plan §6j added the content hash to keep exactly that out of a client's history, and this would produce it in bulk, from a button labelled "import". | ✅ **RUN — CONFIRMED, count corrected.** 2 red, but aReplayIsANoOp stays GREEN; the local-edit arm reddens in its place |
| `§255 FI-M9` | In `planExisting`, compare against the case's CURRENT rendered text instead of `imported_source` (e.g. `GherkinScenarios.render(...)`) | **Red — exactly 1**, `.aLocalEditIsOverwritten`. The plausible-looking version of `FI-M8`, and worse than it: a case somebody edited in TCMS would read as "changed" and be silently reverted to the file **only when they had edited it**, which is the one case where the edit was deliberate. `imported_source` compares the FILE against the FILE. | ✅ **RUN — CONFIRMED.** the local-edit arm reddens as predicted (3 total) |
| `§255 FI-M10` | In `planExisting`, allow `APPROVED` — delete the `LifecycleStatus.APPROVED` branch | **Red — exactly 1**, `.approvedIsUntouched` (the first assertion). An approved case is one somebody signed off; §6g's whole design is that an ordinary save cannot move it, and this would let a file in an automation repository rewrite the procedure of every approved case in a project without anybody pressing anything. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 FI-M11` | In `caseRequest`'s update overload, pass `Priority.MEDIUM` instead of `existing.getPriority()` | **Expected: no red.** ⚠️ **A registered gap, and the most likely real defect in this task.** No fixture case has a non-`MEDIUM` priority, so the two arguments agree. `CaseUpdateRequest.overlay` takes the four contract-REQUIRED fields from the body as sent rather than from the row — deliberately — so this line is the only thing making `api-surface.md` §5.4's *"priority untouched"* true. **What would close it**: set `priority = 'CRITICAL'` on one imported case before the re-import and assert it survives. Not written because the arm belongs beside `.humanConditionsSurvive` and would double that method's fixture; recorded here instead of pretended. | ✅ **RUN — the registered gap HOLDS.** 0 red - the initiative's likeliest real defect is genuinely uncovered |
| `§255 FI-M12` | In `UPDATES_EVERYTHING`, add `putNull("severity")` | **Expected: no red**, same reason as `FI-M11` and the same closure: no fixture case carries a severity. Recorded as the pair, because the `PatchBody` and the `sent` record are two independent ways to reset a column the import has no opinion about. | ✅ **RUN — the registered gap HOLDS.** 0 red |
| `§255 FI-M13` | In `caseRequest`, send `postconditions` as `List.of()` instead of null | **Red — exactly 1**, `.humanConditionsSurvive` (the `postconditions` assertion). Null is "leave them alone" and an empty list is "detach them all" — one character apart on `CaseUpdateRequest`, and the difference is whether a re-import silently strips every postcondition a human attached to every case it touches. Catalog addendum I.3. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 FI-M14` | In `humanAttachedPre`, return every attached PRE id rather than only the non-owned ones | **Red — exactly 1**, `.humanConditionsSurvive` (the `preconditions` order assertion, which would list the owned three twice). The import-owned attachments would be sent twice per re-import and the case would grow a duplicate precondition set on every run. | ✅ **RUN — CONFIRMED, count corrected.** 3, not 1 |
| `§255 FI-M15` | In `humanAttachedPre`, return `List.of()` unconditionally | **Red — exactly 1**, `.humanConditionsSurvive`. The other direction: a precondition somebody attached by hand is detached by the next import, with no record that it was ever there. Ownership (`imported_key` non-null) is the whole rule and this deletes it. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 FI-M16` | In `Backgrounds.keyOf`, hash the step's POSITION as well — append the index to the normalised text | **Red** — `.aReorderedBackgroundMintsNothing` (both halves) and `.theWholeFolderIsPlanned` (four conditions becomes five: the same `Given I am signed in as a customer` is at position 0 in orders/payments and 0 in rules, but `And the catalogue has been loaded` and `And my basket is empty` would separate). **2.** A positional key means a reformat of an automation repository mints a new library entry per moved step and leaves the old ones orphaned. `data-model.md` §3 states this as the design; the arm is what holds it. | ✅ **RUN — CONFIRMED, count corrected.** 8, not 2 |
| `§255 FI-M17` | In `Backgrounds.keyOf`, drop the whitespace normalisation (`.strip().replaceAll(...)`) | **Expected: no red.** ⚠️ **A registered gap.** Every fixture file indents its `Background:` the same way, so nothing distinguishes the two. The defect is that two features indenting differently — which is a linter's choice, not an author's — hash to two entries for one step. **What would close it**: a seventh fixture file repeating one Background step with four spaces of extra indentation, asserting `conditions[]` still has one entry for it. | ✅ **RUN — the registered gap HOLDS.** 0 red |
| `§255 FI-M18` | In `Backgrounds.distinctIn`, key the map per FILE instead of across the request (e.g. prefix the path) | **Red — exactly 1**, `.theWholeFolderIsPlanned` (four conditions becomes six: `Given I am signed in as a customer` appears in three files). Amendment AM1's project-wide dedup is what makes the result a library rather than a pile — and every case in the project would then attach a different row saying the same sentence. | ✅ **RUN — CONFIRMED, count corrected.** 9, not 1 |
| `§255 FI-M19` | In `planConditions`, answer `CREATE` when the key already exists (drop the `already == null` test) | **Red** — `.aReplayIsANoOp` (`conditions` `UNCHANGED`, and the `condition` count) and `.aReorderedBackgroundMintsNothing`. **2.** Every re-import raises `condition_imported_key_idx` and 409s the whole request — so the second run of a working import fails, which is the failure mode most likely to be read as "the import is broken" rather than "the dedup is". | ✅ **RUN — CONFIRMED, count corrected.** 9, not 2 |
| `§255 FI-M20` | In `planScenario`/`planCreate`, keep the `library:manage` refusal where it was before this task — i.e. move it ABOVE the `keys.create()` check | **Red — exactly 1**, `TheGate.aReadOnlyCallerGetsAPlan`. A Viewer previewing a folder is told they need `library:manage`, which is true and is not the thing they need first; the plan's own criterion says a `case:read`-only caller sees `"needs case:create"`. The row is here because the ordering looks arbitrary and is not: the row's OWN key is the one the caller has to be given. | ❌ **RUN — REFUTED. The named arm does not redden.** 0 red. aReadOnlyCallerGetsAPlan previews catalogue.feature, which has NO Background:, so ownedKeys is empty and the check ORDER is unobservable. The load-bearing per-row permission order has NO test. Closure: preview a feature that HAS a Background as VIEWER. |
| `§255 FI-M21` | In `planCreate`, attach the Background anyway when `library:manage` is missing — delete the refusal and let `attachments()` answer empty | **Red — exactly 1**, `TheGate.theBackgroundNeedsTheLibraryKey`. A case is imported without its setup steps, and a tester runs it and fails for a reason the file already stated. `api-surface.md` §5.4: the whole scenario is refused, never attached partially. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 FI-M22` | In `run`, gate with `projects.writeGranted(userId, projectId, "case:create", …)` instead of `readVisible` | **Red — exactly 1**, `TheGate.aReadOnlyCallerGetsAPlan` (403 rather than 200). The endpoint stops being able to answer *"which of my forty files could I import"*, which is the question the preview exists for, and a Viewer is told nothing at all instead of forty specific things. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 FI-M23` | In `plan`, ask `projects.holds` once and reuse the answer for all four keys (e.g. `new Keys(create, create, create, create)`) | **Red** — `TheGate.theBackgroundNeedsTheLibraryKey` (a caller holding `case:create` and not `library:manage` reads as holding both). **1 arm, and it needs the custom role**: no seeded role holds the two apart, which is why `FeatureImportTest.customRole` exists at all. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 FI-M24` | In `plan`, skip the `writeConditions`/`writeSuites` calls when `apply` is true (delete the `if (apply)` block) | **Red** — `Apply.theFolderIsImported` (`condition` and `suite` counts), `.aRuleIsCarried`, `.humanConditionsSurvive`. **3+.** The rows would still create cases, with no preconditions and filed nowhere — a successful-looking import of half the model. | ✅ **RUN — CONFIRMED, count corrected.** 8, not 3 |
| `§255 FI-M25` | In `plan`, run `writeConditions`/`writeSuites` unconditionally (delete the `apply` guard the other way) | **Red — exactly 1**, `Preview.nothingIsWritten` (`condition` and `suite` counts). **The failure this endpoint's preview exists to not have**: pointing `PREVIEW` at a repository you are unsure about would write half of it. Asserted by row counts on five tables rather than by the absence of ids in the response, because a service that wrote and then failed to report would answer an identical body. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 FI-M26` | In `parseAll`, let `GherkinSyntaxException` propagate instead of catching it | **Red** — `Preview.aParserErrorIsOneRow` and `Apply.refusalsAreNotFatal`. **2.** One malformed file 500s a request carrying thirty-nine good ones, and the caller is told nothing about which file. | ✅ **RUN — CONFIRMED.** exactly 2 |
| `§255 FI-M27` | In `parseAll`, replace `refused.getMessage()` with a fixed `"could not be parsed"` | **Red — exactly 1**, `.aParserErrorIsOneRow` on the `Line \d+` pattern. The row becomes a file name with no line, on the one artifact whose entire purpose is telling somebody what to open and where. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 FI-M28` | In `GherkinScenarios.parseFeature`, retry inside the paste wrapper when the direct attempt has no feature (copy `parse`'s `wrap` branch) | **Expected: no red.** ⚠️ **A registered gap.** The only fixture with no `Feature:` is the inline `"# nothing here"` file, which produces no scenario either way and still answers `"Feature has no name"`. The defect is a file that is a bare scenario being imported under a synthetic nameless feature, keyed `#Scenario name` — a key no Cucumber report can ever produce. **What would close it**: a fixture file holding a `Scenario:` and no `Feature:`, asserting the whole-file refusal. Not written; the branch is absent rather than present-and-wrong, which is the cheaper half to inspect. | ✅ **RUN — the registered gap HOLDS.** 0 red |
| `§255 FI-M29` | In `GherkinScenarios.scenarioOf`, bound the last step's comments with `Integer.MAX_VALUE` instead of `endsBefore` | **Expected: no red.** ⚠️ **A registered gap.** The bound only matters when a `# expected:` / `# data:` comment sits BELOW a scenario's last step and BELOW the next scenario's first step, and the fixture has no such pair — `annotationFor` takes the first match, and in `orders.feature` the first match is already the right one. The defect it models is a comment written under one scenario being read as the previous scenario's last step, which is invisible in a paste (one scenario, nothing after it) and is the first thing a FILE can get wrong. **What would close it**: a fixture whose second scenario opens with a `# expected:` line above its first step, asserting the first scenario's last step has none. Not written — one more fixture file for a bound that is three characters and reads as what it is. | ✅ **RUN — the registered gap HOLDS.** 0 red |
| `§255 FI-M30` | In `GherkinScenarios.slice`, start at the first step's line instead of the scenario's | **Red — exactly 1**, `.provenanceIsRecorded` on the `startsWith("  Scenario: Cancel an order")` assertion. Subtler than it looks: `imported_source` would then be identical for two scenarios with the same steps and different names, so renaming a scenario would read as `"unchanged since import"` and the case would keep the old title for ever. | ✅ **RUN — CONFIRMED.** provenanceIsRecorded reddens as predicted (2 total) |
| `§255 FI-M31` | In `orphans`, include cases whose key IS in the files | **Red — exactly 1**, `Preview.orphansAreListed` (one orphan becomes two). Every imported case is reported as an orphan of its own import — a list a human is being asked to act on, whose every row is wrong. | ❌ **RUN — REFUTED. The named arm does not redden.** 0 red. orphansAreListed previews payments.feature against a suite holding only TC-900, a case the file does not name - so the `named` filter removes nothing and is unexercised. Closure: assert orphans after an APPLY, or file a named case into the target suite first. |
| `§255 FI-M32` | In `orphans`, delete or soft-delete the rows instead of listing them | **Expected: no red** on the plan assertions; `.orphansAreListed` still passes and only a row-count arm would catch it, and there is none. ⚠️ **A registered gap, and the most destructive mutation in this table.** Whether a missing scenario is a rename or a retirement is a question only a human can answer, and deleting the case destroys every run it appears in. **What would close it**: `assertThat(count("test_case"))` inside `.orphansAreListed`, one line — **not added**, because the arm is a `PREVIEW` and `Preview.nothingIsWritten` already counts the table for the whole mode. State the residue plainly: an `APPLY`-mode orphan-deletion would not be caught by anything here. | ⚠️ **RUN — CORRECTED.** 3 red, not "no red" - and they are exactly the row-count arms the row said would be the only thing to catch it. orphansAreListed passes as stated. Coverage is BETTER than the row believed. |
| `§255 FI-M33` | In `planSuites`, answer `REUSE` on the first match when two suites share the name | **Red — exactly 1**, `Preview.ambiguousSuitesRefuse`. The import silently picks one of two suites a human made deliberately, and which one depends on the tree's ordering — so the same folder imports into different suites on two instances. | ✅ **RUN — CONFIRMED, count corrected.** 2, not 1 |
| `§255 FI-M34` | In `planSuites`, ignore `request.suiteId()` — match and create suites anyway | **Red — exactly 1**, `Preview.anExplicitSuiteMatchesNothing` (`suites` is no longer empty). The escape hatch stops being one: a repository whose feature names do not match the tree grows a second parallel tree beside it on every import. | ✅ **RUN — CONFIRMED.** exactly 1 |
| `§255 FI-M35` | In `writeSuites`, do not write the created suite's id back onto the action | **Red** — `Apply.theFolderIsImported` (`suite` count still 7, but the cases file nowhere) and `Preview.orphansAreListed` is unaffected. **1, and weaker than the defect:** the created suites exist and every case lands unfiled, which no assertion here reads directly. **What would close it**: assert `suiteId` on the served case in `.theFolderIsImported`. Recorded rather than added, because the arm belongs with a suite-membership assertion this class does not otherwise make. | ❌ **RUN — REFUTED. The named arm does not redden.** 0 red. Without the created suite's id written back every imported case is created UNFILED, and nothing asserts filing - the counts still hold. No arm checks that an imported case lands in the suite the plan created. |
| `§255 FI-M36` | In `FeatureImportService.stamp`, write only `imported_path` and `imported_line` (drop `source`) | **Red** — `.provenanceIsRecorded` (the `imported_source` assertions) and `.anUnchangedFileMintsNoVersion`. **2.** `imported_source` is the column with the reader; without it every re-import rewrites every case. | ✅ **RUN — CONFIRMED, count corrected.** 3, not 2 |
| `§255 FI-M37` | In `TestCase.recordImport`, assign the three fields inside `applyEdit` instead — i.e. make provenance part of the edit | **Expected: no red.** ⚠️ **A registered gap.** The values are the same, so every assertion passes. What changes is that provenance enters `CaseEdit` and therefore `CaseVersions`' content hash: a re-import that changed only the file's PATH would mint a version whose diff a reader cannot see, and a case's history would claim its author moved a file. **What would close it**: re-import the same content under a different `path` and assert the version count is unchanged — not written, because it needs the `imported_source` short-circuit disabled to reach the write at all. | ⬜ **NOT EXPRESSIBLE here.** the literal mutation needs the three provenance fields on CaseEdit (the row's own point - it enters CaseVersions' content hash). Every cheaper expression either changes nothing observable or adds a field to the entity and breaks the JPA mapping. Predicted "no red"; the gap stands unmeasured. |
| `§255 FI-M38` | In `ConditionService.createImported`, call `create` (the public, gated one) instead | **Expected: no red** for a `library:manage`-holding caller — every fixture `APPLY` is `LEAD`. ⚠️ **A registered gap with a stated consequence**: the gate would be evaluated once per distinct Background step per request, and a caller who lost the grant between the plan and the write would get a `Forbidden` outcome this code has no arm for. Recorded because the package-private overload looks like an avoidable duplicate and is not. | ✅ **RUN — the registered gap HOLDS.** 0 red - every fixture APPLY is LEAD, who holds library:manage |
| `§255 FI-M39` | In `Condition.createImported`, do not assign `importedKey` | **Red** — `.aReplayIsANoOp` (`conditions` `UNCHANGED` becomes `CREATE`, and the count doubles) and `.aRemovedBackgroundStepDetaches`. **2.** Every import mints a fresh copy of every Background step, and — because ownership *is* `imported_key` being non-null — the next import treats all of them as human-attached and never detaches any. | ✅ **RUN — CONFIRMED, count corrected.** 6, not 2 |
| `§255 FI-M40` | In `FeatureImportController`, return `201` with a `Location` for an `APPLY` | **Expected: no red.** The tests assert `200` only on the `PREVIEW` path and read the body elsewhere. Recorded as a design row rather than a defect row: a successful import creates a suite, three conditions and forty cases, and a `Location` would have to choose one of them. The `200` is the answer to "what happened", which is what the body is. | ✅ **RUN — CONFIRMED.** no red, as predicted - a design row |
| `§255 FI-M41` | In `FeatureImportController.raced`, answer `duplicate-automation-key` unconditionally (drop the `condition_imported_key_idx` test) | **Expected: no red.** ⚠️ **A registered gap, and the only one about the 409 at all.** Nothing here provokes a race — two concurrent `APPLY`s against one project is not a shape this harness can stage — so the whole handler is unexercised by tests and by the product's own arms. The remedy the two types distinguish really does differ (re-read the plan vs. plain retry). **What would close it**: insert a `condition` row with the key the fixture would mint, inside the same transaction, and assert the 409's `type` — not written, because the insert has to land between the plan and the write and this class has no seam there. | ✅ **RUN — the registered gap HOLDS.** 0 red - the 409 handler is wholly unexercised |
| `§255 FI-M42` | In `shapeErrors`, drop the total-size check (`total > MAX_TOTAL_BYTES`) | **Expected: no red.** The fixture files total a few kilobytes. The bound exists so the refusal is a field error on `files` rather than the container's bodiless 413 at 12 MB (`application.yml`), which a drop zone cannot render beside the files it is holding. **What would close it**: a request of 200 synthetic 64 KiB files — a 9 MB fixture in a test class, which is why it is a row and not an arm. | ✅ **RUN — the registered gap HOLDS.** 0 red |
| `§255 FI-M43` | In `FeatureImportService`, resolve the sibling project's key through `ProjectService.findVisible(userId, …)` instead of `ProjectRepository.findScopedById` | **Red — exactly 1**, `.aSiblingProjectsKeyIsRefused` — `LEAD` is granted on `FPAY` here, so it would still pass; **the arm that reddens is the one that does not exist**, and this row is really about the caller who is NOT granted on the sibling project, who would then read `"in project another project"`. ⚠️ **Recorded as a registered gap with the reverse concern stated:** the current code discloses a sibling project's KEY to a caller with no grant on it, bounded by RLS to the caller's own company. `api-surface.md` §5.4 fixes that sentence; §6t's argument is about not confirming ids a caller guessed, and this key was not guessed. **If the human wants the narrower behaviour**, the exchange is one line and a second reason sentence, and it should be decided rather than drifted into. | ✅ **RUN — the registered gap HOLDS.** the literal switch is unobservable because LEAD holds the grant, exactly as the row states. Forcing the fallback instead reddens exactly 1, which proves the arm does assert the project KEY. The ungranted-caller gap stands. |
