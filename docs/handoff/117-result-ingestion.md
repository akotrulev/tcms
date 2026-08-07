# §117. CI result ingestion — the four ways a red suite reads as green, and the gate that stops a token laundering one

**Date:** 2026-08-07 · **Milestone:** M3, slice 2 · **Backend only** ·
Plan: [`docs/plans/2026-08-07-m3-result-ingestion.md`](../plans/2026-08-07-m3-result-ingestion.md)

**Gates:** `./gradlew build` **BUILD SUCCESSFUL — 1946 tests across 407 result classes, 0 failures,
0 skipped** (up from 1909/394 at §116: 21 + 17 new). `npm run build` clean after `gen:api`,
`npm run lint` 0 warnings. **Eight mutations were run** — a stated §65 deviation, for §115's reason,
and one of them corrected the code's own comment.

## Why this slice

§116 closed with the sentence this one opens on: *"`ingestion_job` and `automation_key_mapping`
still have no writer. Two of the three tables this milestone opened with."* Both have one now, and
`api_token`'s credential is what a CI job presents to reach them. `V1`'s three writerless tables are
down to zero.

**No migration.** Every table, column and `CHECK` this slice writes to has been in `V1` since M0 —
`V36` is still the head and `V37` still free, and the policy count is still 61. What was missing was
never the schema.

## The four parsers, and the direction each one fails in

`ReportParser` is a Strategy keyed on `ReportFormat`, whose four values **are**
`ingestion_job.format`'s `CHECK` values — so the set cannot grow without a migration, and
`ReportParsers` asserts every value has a bean **at startup** rather than 500ing on the first CI job
that posts the fifth dialect.

Each dialect has one shape that, read carelessly, reports a failing suite as passing. They are not
variations of one bug; they are four different bugs that happen to fail in the same direction:

| Dialect | The all-green shape |
|---|---|
| JUnit | A passing `<testcase>` says **nothing** — there is no `status="passed"` — so the outcome is the *absence* of a child. Read with `getElementsByTagName` (descendants) rather than direct children, a suite-level `<error>` lands on the first case; read one level shallow, a nested suite's failures vanish. |
| TestNG | An unrecognised `status` falling through to passed. Also `is-config="true"`: a `@BeforeSuite` ingests as an extra test that fails whenever setup does. |
| Allure | `broken` and `unknown` are real statuses and neither is a pass. |
| Cucumber | **The big one.** A scenario has no status of its own — it is the worst of its steps. "Take the last step's status" reports a scenario that failed *mid-way* as **skipped**, because Cucumber marks every step after a failure as skipped, and a variant treating skipped as passed reports it green. On the format most used for acceptance tests. |

`IngestedOutcome.severity()` is the ordering, and it has **two** callers on purpose — the Cucumber
scenario reduction, and `IngestionService` reducing several tests that matched one case. Same
question at two altitudes; a second copy is how one of them ends up ranking a skip above a failure.

## `ReportXml` is the only `DocumentBuilderFactory` in the application

This endpoint takes an XML document, over the network, from a credential holder, and the default
JAXP configuration resolves external entities. A report opening
`<!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/tcms/tcms.env">]>` is read as an instruction to inline
that file into a test name, which is then stored, matched and served back — the production signing
key, the database password and the SMTP credentials, exfiltrated through a feature working exactly
as designed. §108 from the other end.

So the hardening is **not a checklist item on each parser**: a second construction site is the whole
of the vulnerability, and it is the one somebody adding a fifth format will write.

**The overlap between the four flags was measured and it corrected the header.** Setting
`disallow-doctype-decl` to `false` left the external-entity test **green** — the
`external-*-entities` features and `ACCESS_EXTERNAL_DTD` refuse the file read on their own — and
reddened only the *expansion* test. So the entity pair is what stands between this endpoint and
disclosure, and the DOCTYPE ban is what stands between it and a billion-laughs denial of service.
The first draft's comment said the DOCTYPE ban "alone is sufficient, and the rest are defence",
which is true of expansion and backwards about disclosure. **Neither half is redundant, and the
mutation is what said so.**

## §6c's ladder — three PASSES, not a chain per test

```
1. Exact automation_key           CaseFacts.caseIdsByAutomationKey
2. A display key in the name      CaseFacts.caseIdsByDisplayKey
3. Learned mapping                AutomationKeyMappingRepository
4. No match                       counted, named, nothing written
```

The ladder reads like a per-test chain of responsibility and **must not be written as one**. A CI
report is thousands of tests; a chain asks each rung per test, so a 5,000-test report is 15,000
queries inside one transaction. That is not a slow endpoint — it is one that works perfectly on
every fixture and times out on the first real customer. Each rung is one batched `IN (…)` over
everything still unmatched, so the query count is **three**, whatever the report's size.

**Each rung is tested ALONE**, and that is the fixture's whole design: only the first case carries an
`automation_key`, the second is reachable only by its display key, the third only by a mapping. A
ladder that collapsed to rung 1 would keep its matched count and pass a report exercising all three.
Mutation IN-3 confirmed it — dropping rungs 2 and 3 reddened exactly the two tests written for them.

### Step 2's pattern is fixed, and that is a stated departure from §6c

§6c specifies *"configurable regex per project"*. No column exists for one and inventing a settings
column is a schema decision with no consumer. The extractor is `\b([A-Z][A-Z0-9]{0,9}-\d+)\b` and
every token is looked up against **this project's** `test_case.key`.

**That bound is what makes a fixed pattern safe.** A false extraction — `HTTP-404 returns not found`
yields `HTTP-404` — costs one entry in an `IN` list and matches nothing, because `HTTP` is not a key
prefix of this project. A wrong *configurable* regex could match a real key of a real case and post
a result to it. The failure modes are not symmetric and the fixed one fails safe.

**A name yielding two distinct keys matches neither.** `TC-1 and TC-2 both work` names two cases;
picking the first is picking one arbitrarily.

### Matching is bounded to the project, though `automation_key` is company-unique

§6c scopes the key to the company so a CI job need not know which project owns a case. This endpoint
*is* project-scoped, and materialising a sibling project's case would put a `test_execution` and its
`test_case` in two different projects — a state every read in the module assumes cannot arise. The
company scope still buys what it was for: the key is unambiguous, so the bound can never silently
select the wrong one of two. There is only ever one.

## Nothing is auto-created for an unmatched test, and the endpoint that makes that defensible

§6c offers *"quarantine folder, optionally auto-creating a case stub"*. Declined. A quarantine folder
is not expressible since §98 flattened suites, and decisively: **a CI job that can mint cases turns
every test rename into a permanent duplicate of a case that already exists** — the identity problem
`automationKey` exists to solve, reintroduced by the feature meant to use it.

That is only a design rather than a gap if the alternative is usable, so `automation_key_mapping`
gets its writer in the same slice. An unmatched test comes back with the exact `normalizedName` the
mapping endpoint takes — **served rather than left for the client to recompute**, because the
normalization rule lives in one place and a client re-deriving it is a second implementation of the
one thing that must agree exactly or every mapping silently stops matching.

**The write costs `case:edit`, and that is the security property of the whole feature.** Confirming a
mapping is the same statement as writing a case's `automation_key`: it decides where that test's
results land. §A6 grants Automation Service `result:submit` and `run:create` and **not**
`case:edit` — so a leaked CI token can post results and **cannot re-point where anybody's results
land**. Without the split it could bind every failing test to a throwaway case and launder a red
suite into a green one, leaving a coverage dashboard that is confidently wrong and an audit log
recording nothing unusual. No new key was invented (§67's argument, the eleventh time).

**Re-confirming MOVES the row.** `uq_automation_mapping` is `(company_id, normalized_test_name)`, so
a second row is impossible and the choice is between moving and a 409 — and a 409 makes correcting a
mis-binding a delete-then-create with a window in the middle where the CI job silently stops
matching.

## Two gates, each asked where it has always been asked

The endpoint opens a run and records results, so it costs `run:create` and `result:submit`, and asks
for neither in a new place: `RunCreateService.createIngested` is the *same* create the public
endpoint reaches. A third `results:ingest` key was refused for §67's reason.

**`createIngested` exists so ingestion does not grow a second run-creation path.** Materialising a
run is not one row — it resolves a case set, resolves each case's current version, allocates a
display key, writes an execution per case and audits it as one transaction. A copy in
`IngestionService` would be a second implementation of the pin, which `RunCreateService`'s header
says must be resolved once and never re-resolved.

**`TestRun.create` gained a `source` parameter, and its header had already specified the rule.**
Before this slice it hard-coded `MANUAL`, above a paragraph reading *"an ingested run arrives through
M3's CI path and sets `INGESTED` there; deciding it from the caller's role instead would make a Test
Lead's run 'manual' and an Automation Service's run 'ingested' while both came through the same
browser-shaped request."* **The door the request came through, never the caller's role** — and a
token holding `run:create` through the public endpoint has created a manual run.

## The transaction, and §116's red run avoided by having read about it

The parse must happen **outside** the transaction: parsing a megabyte of XML while holding a
connection and a company GUC is the long-transaction rule in its plainest form. That means the
transaction starts partway through a public method — and the obvious way to write it is a private
`@Transactional` method called from the public one, which **Spring's proxy never sees**. The
annotation would do nothing, and under `CompanyAwareTransactionManager` that is not merely a missing
rollback: isolation is established in `doBegin`, so `app.current_company` is never set and every
RLS-protected read returns zero rows, silently. Here the visible symptom would be that the
idempotency lookup finds nothing, so **every** CI retry ingests twice.

That was written as a self-invoked `@Transactional` in the first draft and corrected to a
`TransactionTemplate` before it ran — §116's exact defect, avoided because §116 wrote it down.
`ReportJobRunner` made the same argument for its three transactions.

## Decisions worth not re-litigating

- **The report is a string in a JSON body, not `multipart/form-data`.** Two dialects are XML and two
  are JSON, so a `Content-Type` distinguishes neither JUnit from TestNG nor Allure from Cucumber.
  Once `format` must be a field the payload may as well be one, and there is no second
  content-negotiation path to secure. `format` is never **sniffed**: that would make a malformed
  report of one dialect read as a valid report of another.
- **`Idempotency-Key` is REQUIRED here** where `POST /runs` makes it optional. The column is
  `NOT NULL` and the caller is the one caller in the product that retries automatically, whose retry
  re-posts a whole report. Optional, and that caller would be the one opting out of the constraint
  written for them.
- **A malformed report writes NO job row.** A `FAILED` row consumes the idempotency key, so the
  *corrected* retry under the key CI generated once replays the failure for ever. Consequence stated
  rather than hidden: **`PENDING`, `PROCESSING` and `FAILED` have no writer in this lane.** They are
  the async lane's, and the `CHECK` is left wide for `LinkSourceType`'s reason (§93) — with the
  distinction noted on `IngestionStatus`, since these name real states of a job where
  `LinkSourceType`'s extra arms name link targets that do not exist.
- **A report matching nothing opens NO run.** A run of zero cases reports 0 of 0, 100%, complete —
  §99's defect one screen over, and worse here because the report that most often matches nothing is
  the *first* one a team posts.
- **One audit row for the job, not one per result.** A 2,000-test report would write 2,000 rows and
  bury the project's audit log in the one event whose interesting fact is the job. `raw_payload` is
  the per-result record.
- **`raw_payload` holds what was UNDERSTOOD, not the file.** The verbatim report belongs in an object
  store and there is no adapter (§67's port argument, `EvidenceStore`), so `raw_storage_key` stays
  null. What makes the row useful afterwards is the normalized parse and the match outcome per test.
- **`executedBy` stays NULL on an ingested result.** The verdict was not the token holder's — they
  hold the credential, they did not run the test — and `test_execution.executed_by_token` is the
  column `V1` added for exactly this. Filling `executedBy` would attribute a machine's claim to a
  person, the attribution `TestExecution#record` already refuses in the amendment case.
- **The approval gate is NOT overridden for a CI run.** A project requiring approved cases means it
  for the release run most of all; overriding on the caller's behalf would make §6g silently
  inapplicable to the one path nobody watches. The refusal reaches the CI log as a 400 on `report`.
- **A missing status *kind* is refused, not substituted.** A project that deleted its Skipped status
  ingests a clean build without complaint and is told the moment a test is skipped. Recording a skip
  as a failure is a verdict nobody's runner reported; dropping it makes the totals silently short.
- **`branch` and `commitSha` are NOT on the request**, though `V1` has the columns. They are unmapped
  on `TestRun` and would be two newly-mapped columns with one writer and no reader — the shape this
  slice spent its opening paragraph closing.

## The fixture found a real rule

`createCase` with `automationStatus: AUTOMATED` and no `automationKey` is a **400**: *"Give the
automated test that covers this case, so results can be matched to it."* The fixture was written
against the real endpoint rather than against SQL, so it hit it. The three non-keyed cases are
`MANUAL` — which is also §6c's *"cases receiving results while marked MANUAL"*, the unlinked
automation a team does not know it has. Drift detection is not in this slice.

## What is next

- **The two screens this slice names and does not build**, both recorded in the browser queue: an
  **ingestion history** (the repository read exists and has no endpoint, deliberately — an endpoint
  with no consumer is the shape §116's dead reads had) and an **unmatched-test binder**. Until the
  binder exists, declining §6c's auto-created stubs is only defensible for a caller who can read
  JSON, which is worth saying plainly.
- **The token screen** — still the frontend slice §116 named, and this one adds a second reason for
  it.
- **Async ingestion**, which is what `PENDING`/`PROCESSING`/`FAILED` and `raw_storage_key` are for.
- **Drift detection** (§6c's stale-automation flag, and cases receiving results while marked
  MANUAL) — a report, and it is the first thing to want after this.
- **The project-predicate-over-RLS gap is now SEVEN files** (`PlanFacts`, `CatalogueFacts`,
  `ExecutionSource`×3, `DocumentationSource`, and now `CaseFacts.caseIdsByAutomationKey`). §115's
  CM-6 recorded it third; the fix is still **one two-project fixture written once for the seam**,
  not a row per method. It is now overdue.
