# §86 — the fact log gets a writer: the module that had to exist first, and the dimensions that must not move

**Date:** 2026-08-04 · **Backend** 1530 → **1553** · **no migration (`V27` still free), no contract
change, no new permission key, no new table, no new column** · policy count unchanged at **58**

The first slice of M2.5's reporting half (`docs/plans/2026-08-04-m2.5-reporting-slice-plan.md`,
R1 + R2). It ships the product's **tenth Spring Modulith module**, the first writer
`execution_fact` has ever had, and the third of `case_health`'s nine signals — which is the half a
user can see, and the reason R1 did not ship alone.

## What was actually absent

Three things the plan verified on paper and this slice confirmed against the code:

- **There was no `com.tcms.reporting` package.** `CLAUDE.md`, ERD §0.2 and this project's own
  orientation cache have listed `reporting` among the modules since M0; nine existed.
  `ModuleStructureTest.detectedModulesMatchTheSourceTree` hard-coded those nine and its
  `@DisplayName` rationale said in as many words that adding one *"must be a deliberate edit, so
  that whoever adds one also reads the `allowedDependencies` rule they are now subject to."*
- **`execution_fact` had five purpose-built indexes and zero rows.** `V1` created it, `V4` gave it
  the `nullif` RLS predicate, and nothing has ever inserted into it. Its comment says *"Do NOT defer
  this — reporting built on live joins cannot be converted to a read model incrementally (plan
  §6e)."*
- **`case_health.failing_repeatedly` was a column with a `DEFAULT false` and no writer**, while
  `HealthSignal.FAILING_REPEATEDLY` has been on the wire since §47, labelled in `lib/labels.ts`, and
  renderable as a dashboard tile and a `CASE_MAINTENANCE` board card since §53. Every deployment has
  therefore been quietly asserting that nothing is failing repeatedly.

## The module: `reporting`, and it had to be created before anything could be written

The projection names `test_execution` and `test_run` (`execution`), `test_case` and `suite`
(`repository`), `case_status` and `case_classification` — **three modules, and no existing module may
write it.** `repository` may not see `test_run`; `execution` may not see `suite`; `work` is the
board's module and has no business filing a report. That is §81's argument — *concentrate a
cross-module read where no other module may write the query* — arriving at a module that did not
exist yet, and it gives the same answer from the other end: create it, and give it one seam file.

`reporting.ExecutionSource` is the **fourth** instance of that shape, after `execution.CaseFacts`,
`repository.ExecutionFacts` and `work.BoardFacts` (`documentation.PlanFacts` is a fifth of the
family). Nothing else in the package may name a foreign table.

**The dashboard stays in `repository`, and that is not an oversight.**
`repository.DashboardController`'s header already argued it: *"a `reporting` module would mean that
module reading this one's tables — the cross-module edge ArchUnit refuses — to compute numbers this
module already knows how to count."* Still correct; not overturned.

### The declaration is `{"tenancy"}` alone — a fact, not a forecast

The plan proposed `{"tenancy", "project", "identity"}`, matching `repository` / `execution` /
`work`. This slice declares **`tenancy` only**, because nothing here is reached through an endpoint:
there is no project gate to ask for and no person to name.

`documentation` is the precedent, in both directions — it declared `tenancy · project` under a
package comment saying *"nothing in it renders a person"* until §61's artifact register made that
false, at which point the list and the comment were corrected together. Declaring the three this
module will eventually want makes the declaration a **forecast**, and a forecast cannot fail the
build when it stops being true.

So the widening is named rather than pre-approved, in the package comment and in
`ModuleStructureTest`'s rationale: the first report endpoint needs `project` (for
`report:view` / `report:export` through `ProjectService`), and a generated-by line needs `identity`
through `project.PeopleDirectory`. That is R3's edit.

## The decision that is most of the slice: which columns an `ON CONFLICT` may touch

`execution_fact` is filled by one idempotent
`INSERT … SELECT … ON CONFLICT (execution_id) DO UPDATE` per company — decision A of the slice plan,
and the reason is that **an event-only fact log has no rebuild path**. The product has zero
`CompanyScopedEvent` implementations, no outbox consumer, and `@Async` handlers whose failures have,
in `CompanyScopedEvent`'s own words, *"nowhere to report"*. A handler that throws, a restart
mid-dispatch, or a result recorded before the handler shipped each produce a log that is permanently
and silently short — and a report computed from a short log renders **identically to a correct one**.
This project has now paid for that shape four times (§47, §53, §72, §81).

Given a statement that reruns every fifteen minutes for ever, the question becomes *what may it
rewrite*. **Seven columns move and fifteen do not.**

- **Move**: `status_kind`, `counts_as_pass`, `executed_at`, `executed_by`, `duration_ms`,
  `release_version`, `release_sort_key` — everything an amended verdict can legitimately change.
- **Frozen**: `automation_status` (moves when a case is automated), `test_type_id` / `test_level_id`
  / `feature_ids` (move when a case is reclassified), and — **this slice's addition to the plan** —
  `suite_id` / `suite_path` (move when a case is filed elsewhere).

An `EXCLUDED.*` set-list is shorter, is what anybody would write, and **retroactively re-files last
quarter's results under this quarter's taxonomy**: the aggregate report's "failures by feature" and
"failures by suite" sections would change their own history every fifteen minutes and nobody could
name when. This is a star schema — a fact's dimensions are as-of the fact, which is exactly what §6e
means by *"deliberately duplicating dimensions to avoid joins"*.

The plan listed only the classification and automation columns. **`suite_id` / `suite_path` are
frozen on the same argument**, and the safe direction is to freeze: a frozen dimension that should
have moved is one statement to change, while a moving dimension that should have been frozen has
already destroyed the history it rewrote.

## Two soft-delete calls that point opposite ways, and are consistent

- **The fact log projects soft-deleted cases and runs.** `V1` calls it an *"append-only fact log"*
  and this writer never deletes, so filtering `deleted_at IS NULL` on insert while never removing
  what was already inserted would make the log's contents a function of **when the job last ran
  relative to the delete** — silently short, undetectable from outside, and the exact class the
  recompute design exists to prevent. Deleting a run does not un-run it.
- **`case_health` derives live cases only.** `CaseHealthRecomputeTest` already pins that a deleted
  case gets no row — *"a signal with nothing to navigate to"*.

The fact log is history; `case_health` is a worklist about cases that exist. The alternative reading
of the first — that a soft delete should hide results from reports — is defensible, is **not** a
predicate (it needs a delete pass over facts whose source has gone), and belongs to R6 with the
selection.

## `failing_repeatedly`: FAILED, not "did not pass"

§6o words the signal *"failed the last N executions"* and the tile says **Failing repeatedly**. The
tempting predicate is `NOT counts_as_pass` — it is the column an aggregate report's pass rate uses,
and it is right there in the fact row. It is wrong: `BLOCKED`, `SKIPPED`, `RETEST` and `UNTESTED`
all satisfy it, so **a case blocked three times running — nobody could run it, nothing failed —
would be reported as a broken test**, and the lead would be sent to fix the case instead of the
environment. The predicate is `status_kind = 'FAILED'`, and `status_kind` rather than the status's
name is precisely what `V1`'s comment says `kind` exists for: *"lets reporting aggregate
project-custom statuses without knowing their names."*

**The window must be FULL.** The count is compared to the window rather than to the rows found, so a
case with two results never raises a three-deep signal. The other reading — *all of what we have are
failures* — is natural and is worst exactly where it matters: on a young project every case has one
result, so the first failure anybody records lights the tile and a panel headed "Needs attention"
reads as a repository in crisis on the day it was created.

**N is 3, and the floor is what carries the argument.** At 1 the signal restates the newest result,
which the case grid, the run page and the dashboard's own counts already say — a slower second copy
of a fact the reader has. At 2 a flake plus a real failure raises it, which is the coincidence §6o's
*"decaying"* severity exists to filter out. Above 3 it needs a history a young project has not got.
`ReportingProperties` **refuses to start below 2**, for `CaseHealthProperties`' reason: a
misconfigured number that reads as a data catastrophe should fail loudly rather than render.

It is a **count, not a `Duration`**, unlike `longUnexecuted` beside it: a case run nightly and a case
run quarterly are both failing repeatedly after three failures, and expressing this in time would
silently mean something different per project.

## The first time a second module writes `case_health`

`repository.ExecutionFacts`' header has prescribed the rule since §53 — *"a signal's owner writes its
own column and no others; the row is shared, the columns are not"* — and **nothing had ever
exercised it**, because `repository` was the only writer. `reporting` writes `failing_repeatedly` and
`computed_at`; `repository` keeps writing `never_executed` and `long_unexecuted`.

`FailingRepeatedlyTest.theOtherModulesSignalsSurvive` is the first test in the product that can fail
when a second writer forgets. The defect it prevents would appear an hour after this ships, present
as a bug in `CaseHealthRecomputeService`, and that job would be innocent.

`computed_at` is deliberately shared by both writers: it means *somebody looked*, which is the
distinction `CaseHealth`'s header says the column exists to draw.

## Why a company loop and not `@Async`

`ExecutionFactScheduler` is `CaseHealthScheduler`'s twin — two schedulers rather than one, because
they write read models owned by different modules and `repository` and `reporting` may not call each
other.

The loop is **not an optimisation**. `execution_fact` is company-owned and RLS-forced; a scheduled
task starts with no company (`CompanyContext` is deliberately not `InheritableThreadLocal` and
`CompanyContextTaskDecorator` clears it on every task). So the tempting shape — one statement
projecting every pending execution across the instance — **returns zero rows and logs a successful
tick having done nothing.** `V4`'s `nullif` makes an unset GUC match nothing rather than everything,
which is the right failure and a completely invisible one. `company` is one of the few tables not
under RLS, precisely so it can be read before any context exists.

Both statements share **one transaction per company**, in that order: the signal is derived from the
fact log, so recomputing it first reads the previous tick's facts and is a quarter-hour stale by
construction — visible to nobody, because a stale boolean renders exactly like a fresh one.

Fifteen minutes rather than the case-health hour: health signals move on a scale of days, a fact is
stale the moment a result is recorded. The schedule is only the floor for reads *outside* a job —
R3's report job refreshes its own company synchronously before computing, so a generated report's
watermark is exact.

## Found while here

- **`max(uuid)` does not exist in Postgres**, in 17 or anywhere. The first draft of the classification
  lateral used it to collapse a `SINGLE`-cardinality dimension to one value; it compiles, it is the
  obvious spelling, and it fails at runtime. Now `(array_agg(… ORDER BY …) FILTER (…))[1]`, with the
  `ORDER BY` there so that if `V2`'s SINGLE-cardinality trigger is ever relaxed the statement keeps
  choosing the same value instead of quietly becoming non-idempotent.
- **A fixture UUID prefix has to be hex, and `rf` is not.** `ExecutionFactRefreshTest` took `rf…` as
  its namespace — the obvious mnemonic for *reporting facts* — and `UUID.fromString` threw in the
  static initialiser, so all eleven tests failed with `NoClassDefFoundError` and none of them ran.
  Every prefix already claimed in this suite is hex for exactly this reason. It is §19's
  fixture-collision family with a different mechanism, and the gate caught it. Now `ef…`; the slug
  namespace stays `rf-`, where no such constraint applies.
- **`case_health` has no index on `failing_repeatedly`.** `V1` ships four partial indexes —
  `outdated_spec_changed`, `stale_approval`, `never_executed`, `unlinked` — and this is the first
  signal with a writer and no index, so its dashboard tile is the first that has to scan.
  **Found, not fixed**: an index is a migration, and this slice states it has none. Browser row 8 is
  where the cost gets measured, and R3's `V27` is where it belongs if it is real.
- **`case_health.incomplete` still has no `HealthSignal` member** and therefore no reader — ten
  columns, nine enum values. Recorded by the plan, unchanged here; it belongs to whoever builds the
  authoring-completeness signal.
- **`execution_fact.executed_by` cannot name a token.** `test_execution` has both `executed_by`
  (`app_user`) and `executed_by_token` (`api_token`); the fact log has only the first. Harmless
  today — token auth is M3 and nothing writes `executed_by_token` — but an ingested result will
  arrive with a null actor, and §6e's per-tester throughput section will silently exclude CI. Named,
  not built.

## Gates

> **⚠ THE FULL GATE WAS NOT RUN TO COMPLETION ON THE CODE THAT LANDED. Read this before building on
> the slice.** What *was* run, and passed, is the module's own suite:
> `./gradlew test --tests 'com.tcms.reporting.*'` → **BUILD SUCCESSFUL, 23/23** (11
> `ExecutionFactRefreshTest` + 12 `FailingRepeatedlyTest`), against the final source.
> `compileJava` and `compileTestJava` succeeded under `-Werror` in that same run.
>
> The **full `./gradlew build`** — the ~1530 pre-existing tests, `ModuleStructureTest`'s 9 → 10 edit,
> `SchemaConventionTest`, `RepositoryTransactionTest`, the ArchUnit rules — **has not been confirmed
> green on this code.** One full run did complete earlier in the session, but on a *pre-fix* state
> (it reported 1541 tests / 11 failed, all eleven of them this slice's, all from the invalid-hex
> fixture prefix). Every later full run died on Windows file-lock and daemon-stop errors —
> `Unable to delete directory build/test-results/test/binary`, `Gradle build daemon has been
> stopped`, `Test Executor finished with non-zero exit value -1` — caused by concurrent `gradlew`
> invocations in this session, **not by any test**.
>
> **The expected count is 1530 → 1553** and it is arithmetic, not an observation.
> **The first thing the next session must do is run `cd backend && ./gradlew build` with nothing
> else touching Gradle**, and correct this section with the real number. Three things it could
> plausibly catch that the module's own suite structurally cannot: `ModuleStructureTest`'s
> `containsExactly` over ten modules, `RepositoryTransactionTest` (which derives its list from the
> live context — this slice adds no Spring Data repository, so it *should* be unaffected), and the
> `@ConfigurationProperties` binding of `tcms.reporting.failing-repeatedly-window`, which fails at
> **context startup** if wrong and would therefore redden every integration test at once —
> `CaseHealthProperties`' compact constructor has the same shape and its `HV000030` note records
> exactly that trap.

CI does not run backend tests, so a local full run is the only gate that exists — which is why its
absence here is a hole and not a formality.

**Two defects the gate caught that no amount of reading would have**, and both are worth the
sentence because each looked correct on the page:

- **`max(uuid)` does not exist in Postgres.** The classification lateral used it to collapse a
  `SINGLE`-cardinality dimension to one value. It is the obvious spelling, it compiles, and it fails
  at runtime with `function max(uuid) does not exist` — the class of defect a native-SQL seam trades
  away in exchange for not moving a row per execution through the application. All eleven tests of
  the first run died on it.
- **A fixture UUID prefix has to be hex.** `ExecutionFactRefreshTest` took `rf…` — the obvious
  mnemonic for *reporting facts* — and `r` is not a hex digit, so `UUID.fromString` threw in the
  static initialiser and **all eleven tests failed with `NoClassDefFoundError` before one ran**.
  Every prefix already claimed in this suite is hex for exactly this reason; it is §19's
  fixture-namespace family with a new mechanism. Now `ef…`, with the slug namespace left at `rf-`
  where no such constraint applies.

**No migration.** Verified by listing `backend/src/main/resources/db/migration/` before writing any
SQL rather than by trusting a document: head is `V26__project_settings_approval_gating.sql`, so
**`V27` is still free** for R3. `execution_fact` and `case_health` are both `V1` tables already on
`V1`'s `company_owned` RLS array, and `SchemaConventionTest`'s policy count is unchanged at **58**.

**No contract change**, so `schema.d.ts` is not stale and `npm run gen:api` was not run.
`HealthSignal.FAILING_REPEATEDLY` has been in `openapi.yaml` since §47 — ahead of its writer, like
`kind: REVIEW` was in §81.

## Not verified

The nineteenth slice in the deferred queue, and it is **built and unverified**.

**Zero mutations run inline** (§65 policy, followed). **Nine rows** in
`docs/verification/backend-mutation-checks.md`; the first two are the pair worth reading, because
both mutations are *tidier* than the code they replace and are therefore the ones a future refactor
will actually make. Two rows are deliberately recorded as **expecting zero** — the scheduler's
company loop, which nothing drives, and the refresh ordering, whose expected count was reasoned
rather than observed.

**Eight rows** in `docs/verification/frontend-browser-checks.md`, **all live-only**. No frontend file
was touched: the tile and the card have existed since §47 and §53 and only now have anything behind
them. Mock mode cannot observe any of it — `src/mocks/db.ts` invents its own health rows and has
never been fed by a fact log — so mock agreeing is not evidence, which is §52's class stated in
advance rather than discovered.

## What this leaves

R1 and R2 of ten. Next is **R3** — `V27`'s `report_job` table, the `ReportStore` port, the
`ReportModel`, the Markdown + JSON renderers and the three endpoints — and it is the biggest slice in
the plan, because a 202 nobody can poll is not shippable: `ReportJob.downloadUrl` is a *required*
contract field with no server, which is §67's finding one module over.

Still absent, and each named in the plan with its missing infrastructure: **PDF** (no Chromium in the
image; R3 refuses `format: PDF` with a 400 until it lands), **S3/MinIO**, **scheduled reports** and
**notifications** (no channel exists for any of §6g's four events), **doc-structured reports** and
the **coverage matrix** (both blocked on `doc_link` having a writer, which it does not), the
**evidence bundle** (the strongest argument for the `shared` module decision E defers), and
**`suite_run_summary` / `plan_snapshot`**, whose absence is deliberate — §46 computes plan statistics
on read instead, and building them would create a third answer to *"how did this plan do"*.

Also unchanged: **the report gate cannot be shown to discriminate.** The only built-in role withheld
`report:view` is Automation Service, and there is no API-token authentication in the product (that is
M3), so no persona an endpoint test can build lacks either report key. R3 must insert a custom
`role` row — instance-level, globally unique name — or its gates are decorative. Open question 5 of
the plan.
