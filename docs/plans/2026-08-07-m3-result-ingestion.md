# M3 slice 2 — CI result ingestion

**Date:** 2026-08-07 · **Milestone:** M3, slice 2 · **Backend only** · Follows
[§116 — API tokens](../handoff/116-api-tokens.md), which gave the credential this endpoint is
presented with.

`ingestion_job` and `automation_key_mapping` are the last two `V1` tables with no writer. This slice
gives both one, and implements §6c's four-step matching ladder that joins an incoming CI result to a
case.

---

## 1. What arrives, and how

`POST /api/v1/projects/{projectId}/ingestions`, `Idempotency-Key` **required**.

```jsonc
{
  "format": "JUNIT",              // JUNIT | TESTNG | ALLURE | CUCUMBER — V1's own CHECK
  "report": "<testsuite …/>",     // the report file, verbatim, as a string
  "runId": "…",                   // optional — post into an existing run
  "runName": "CI #4412",          // used only when runId is absent
  "configurationId": "…",         // "
  "releaseVersion": "2.4.0", "buildNumber": "4412", "branch": "main", "commitSha": "…"
}
```

**Decision A — the report travels as a string in a JSON body, not as `multipart/form-data`.**
Two of the four formats are XML and two are JSON, so a `Content-Type`-keyed body would have to
dispatch on a header that says nothing about which *dialect* arrived — `application/xml` is true of
both JUnit and TestNG and distinguishes neither. `format` is an explicit field for that reason, and
once the format is a field the payload may as well be one. It also keeps the surface identical to
every other write in the product: one JSON body, one `ApiProblem` on refusal, no second content
negotiation path to secure.

**Decision B — `Idempotency-Key` is REQUIRED here, where `RunCreateService` makes it optional.**
`uq_ingestion_idempotency (company_id, idempotency_key)` is `NOT NULL`, and the caller is a CI job:
the one caller in the product that retries automatically, and whose retry double-posts a whole
report rather than one row. An optional key would let exactly that caller opt out of the constraint
written for it. Absent ⇒ 400 with a field error keyed `Idempotency-Key`, `RunCreateService`'s
existing spelling.

**Decision C — a size cap on `report`, and it is a validation error rather than a 413.**
1 MiB. Unbounded, this is the one endpoint in the product that accepts an arbitrary blob into a
`JSONB` column, and a CI job posting a 400 MB Allure directory dump would be a memory fault rather
than a refusal. The cap is stated in the message so a caller can split the report.

## 2. The four parsers

`ReportParser` is a **Strategy over a fixed, `CHECK`-constrained set** — `ReportFormat`'s four
values are `ingestion_job.format`'s four values, so the set cannot grow without a migration. Beans
in a `Map<ReportFormat, ReportParser>`, the shape `ReportComputations` (§115) already uses in
`reporting`.

Every parser answers the same `List<IngestedResult>`:

```java
record IngestedResult(String className, String name, IngestedOutcome outcome,
                      Long durationMs, String message) { String fullName(); }
```

**Decision D — `IngestedOutcome` has four values and maps to `StatusKind`, never to a status name.**
`PASSED · FAILED · SKIPPED · ERRORED`. A project renames its statuses (`ResultStatuses`' header) and
may add a second passing one, so the parser must not name "Passed". `ERRORED` collapses to
`StatusKind.FAILED` at the boundary and is kept distinct in the parse because JUnit and TestNG
distinguish `<failure>` (an assertion) from `<error>` (the test blew up) and the *message* differs;
what it must not do is create a fifth bucket in a vocabulary the project owns.

**Decision E — XML parsing disables external entities, explicitly, in one factory method.**
This endpoint takes an XML document from a credential-holder over the network. `ReportXml.parse` is
the only place a `DocumentBuilderFactory` is constructed and it sets
`disallow-doctype-decl`, both `external-general-entities` and `external-parameter-entities`, and
`setXIncludeAware(false)` / `setExpandEntityReferences(false)`. XXE here reads files off the
application server as the `tcms_app` user. A second construction site is the whole of the
vulnerability, so there is exactly one and a test asserts a DOCTYPE payload is refused.

**Decision F — a malformed report writes NO `ingestion_job` row.** Parse runs *before* the job's
transaction. The alternative — a `FAILED` row — consumes the idempotency key, so the corrected
retry under the same key replays the failure instead of ingesting. Consequence, stated rather than
hidden: `ingestion_job.status`'s `PROCESSING` and `FAILED` arms **have no writer in the synchronous
lane**. They are the async lane's, and the `CHECK` is left wide for `LinkSourceType`'s reason (§93)
— a constraint tightened and loosened again is one nobody trusts.

## 3. §6c's matching ladder — four steps, three batched queries

Resolution order, first hit wins, per test:

| # | Step | Lookup |
|---|---|---|
| 1 | Exact `test_case.automation_key` | `CaseFacts.caseIdsByAutomationKey` |
| 2 | A display key embedded in the test name (`…@TC-1042…`) | `CaseFacts.caseIdsByDisplayKey` |
| 3 | Normalized full name → learned mapping | `AutomationKeyMappingRepository` |
| 4 | No match | counted, named in the response, nothing written |

**Decision G — three queries for the whole report, never three per test.** Each step is a batched
`IN (…)` over every still-unmatched name. A 5,000-test report is 3 queries, not 15,000. The ladder
is therefore written as three *passes* over a shrinking set rather than a per-test chain of
responsibility, which is the shape that reads better and does not survive the first real report.

**Decision H — step 2 uses the project's own display keys, not a per-project configurable regex.**
§6c says "configurable regex per project"; no column exists for one, and inventing a settings column
is a schema decision this slice does not need to make. The extractor is
`\b([A-Z][A-Z0-9]{0,9}-\d+)\b` and every token it finds is looked up against `test_case.key` **in
this project**. A token that is not a key of this project matches nothing, so a false extraction
costs a row in the `IN` list and never a wrong match — which is the property that makes the fixed
pattern safe where a wrong regex would not be.

**Decision I — matching is bounded to the project, though `automation_key` is company-unique.**
§6c scopes the key to the company so a CI job need not know which project owns a case. This endpoint
*is* project-scoped: the run it posts into belongs to the project, and materialising a sibling
project's case into it would put a `test_execution` and a `test_case` in two different projects —
which every read in the module assumes cannot happen. The company scope still buys what it was for:
the key is unambiguous, so the project bound can never silently select the wrong one of two.

**Decision J — nothing is auto-created for an unmatched test.** §6c offers "optionally
auto-creating a case stub"; the option is declined. A quarantine folder is not expressible since
§98 flattened suites, and a CI job that can mint cases turns a renamed test into a permanent
duplicate of a case that already exists — which is precisely the identity problem §8's open
question #9 says `automationKey` exists to solve. The unmatched names are returned so a human can
bind them, and §4 is the endpoint that lets them.

## 4. `automation_key_mapping` gets its writer

`GET · POST · DELETE /api/v1/projects/{projectId}/automation-mappings`.

**Decision K — the write costs `case:edit`, and Automation Service does not hold it.** The mapping
is functionally "this test name means this case", which is the same statement as setting the case's
`automation_key`; it belongs to the same gate. §A6 deliberately excludes `case:edit`, so a leaked CI
token can post results and cannot re-point where anybody's results land — which would otherwise be a
way to launder a red suite into a green case. No new permission key is invented (§67's argument, the
ninth time).

**Decision L — the mapping is company-unique and the writer upserts.** `uq_automation_mapping
(company_id, normalized_test_name)` — re-confirming a name to a different case **moves** it and
stamps `confirmed_by`/`confirmed_at`. A second row is impossible and a 409 would make correcting a
mis-binding a delete-then-create with a window in the middle.

## 5. Writing the results

One `result:submit` gate for the whole report, then per matched case:

- The run is `runId` when given, else `RunCreateService.create(…)` with the matched case ids and
  `source = INGESTED` — **its own `run:create` gate runs there**, unchanged. Two gates, each asked
  where it has always been asked; no third gating path.
- `TestExecution.record(statusId, null, message, now)` — the entity's own mutator, the same one
  `ExecutionService` calls. `executedBy` is null and `executed_by_token` carries the credential.
- `TestRun.begin(…)` once, for §72's reason.

**Decision M — ONE audit row for the job, not one per result.** `ExecutionService` writes
`execution.recorded` per verdict because a human recorded one verdict. A 2,000-test report would
write 2,000 rows and drown the project's audit log in the one event where the interesting fact is
the *job*. `ingestion.completed` carries the job id, the run key and both counts; the
`ingestion_job` row is the per-result record, and it holds the parse and the match outcome for every
test.

**Decision N — `raw_payload` holds the normalized parse and its match outcome; `raw_storage_key`
stays null.** The verbatim file belongs in an object store and there is no adapter (§67's port
argument, `EvidenceStore`). What makes the job *useful* after the fact is what was understood and
what it matched to, which is what a human binding unmatched names needs to see.

## 6. Phases

| # | Scope | Gate |
|---|---|---|
| 1 | `ReportFormat` · `IngestedOutcome` · `IngestedResult` · `ReportXml` · the four parsers · `ReportParsers` | `./gradlew build` |
| 2 | `IngestionJob` · `AutomationKeyMapping` + repositories · `CaseFacts`' two methods · `AutomationMatcher` · `IngestionService` · `IngestionController` · contract | `./gradlew build` + `npm run gen:api` |
| 3 | the mapping endpoints · tests · handoff + orientation cache | `./gradlew build` |

## 7. Not in this slice

- **Async ingestion.** `PROCESSING`/`FAILED` and `raw_storage_key` are its, per decisions F and N.
- **Drift detection** (§6c's stale-automation flag) — a report, and M2.5's plan is closed.
- **Auto-created case stubs** — decision J.
- **A frontend surface.** The token screen (§116) is still the next frontend slice, and an
  ingestion-job screen follows it.
