# §232 — the asymmetries, and the patch that stopped erasing

**Section B of `docs/audits/2026-08-15-flow-review-and-improvements.md`, all eleven rows.** The A
run (§227 → §230) closed the P1s; this is the P2s, authorised in full after it.

Four phases, every stack, **three migrations** — `V59`, `V60`, `V61` — and **no permission added or
moved**. The catalogue is still 55 keys and §A3 is untouched.

> **Numbering:** §231 is the concurrent lane's social-login slice. This is §232, and the frontend
> browser queue's prefixes `SL`, `SO` and `GH` are theirs.

---

## Phase 1 — the case `PATCH` stops erasing, and the picker stops lying (B1, B2, B3, B5)

### B1 — a `PATCH` that could not be asked what it was sent

Jackson gives an absent field and an explicit `null` the same Java value, so every `PATCH` in this
API had to pick one meaning for both, and picked **clear it**. That is the safe reading for exactly
one client — the SPA, whose editor submits every field it renders — and a trap with no symptom for
everybody else: `{"title": "x"}` renamed a case, emptied its objective, description, expected
outcome and estimate, deleted all three of its steps, and answered **200**.

**Nothing in the suite could see it.** Every test sent the complete set the SPA sends, which is why
1,500 frontend tests and 2,900 backend tests were green over it.

`com.tcms.api.PatchBody` is the fix: the body binds as a `JsonNode` **and** as the request record,
so the service can ask which fields arrived. `CaseUpdateRequest.overlay` lays what was sent over the
case as it stands — `CaseEdit.asRequest`, extracted so the bulk reconstruction and the patch base
are one mapping rather than two normalizers that agree until one changes.

**What is deliberately NOT partial: the four fields `TestCaseInput` requires.** They come from the
body as sent, so omitting one is the 400 it always was. This is the *plan's* arrangement —
`TestPlanUpdateInput` requires a `name` and leaves everything else alone — and it is what the audit
asked for in as many words. Resolving them too would make the schema's `required` array true of
`POST /cases` and false of the `PATCH` that shares it, and would empty the population of
`CaseUpdateEndpointTest.contractRequiredMatchesWhatTheServerEnforces`, which derives that array by
dropping each field in turn from a body this endpoint accepts. **The suite caught exactly that**, on
the first run, which is the argument for keeping checks that read a document against behaviour.

A fully partial body needs `TestCaseInput` split in two. Named as not done, with the reason: it is a
generated-type consequence (§144's defect in reverse), not a line.

### B2 — the round trip

Two refusals, neither about anything the caller changed:

- **A stub could not be saved back.** §200 made a stepless `STRUCTURED` case a legal stored row on
  the create path, and the update path required a procedure — so `GET` then `PATCH` unchanged was a
  400 about steps for every stub in the product. Now `ProcedureRule` is chosen from the **stored**
  row: `REQUIRED` for a case that has a procedure, `DEFERRED` for one that does not. What is still
  refused is `steps: []` against a case that has steps, which is the body that was ever really a
  mistake. `CaseCreateEndpointTest`'s *"PATCHing the stub back unchanged is still 400 — the
  asymmetry is the point"* is reversed in place, with the reversal argued rather than deleted.
- **`ownerId` and `automationTags` were refused when present**, and both are served by the `GET`.
  They are refused when they would **change** something now, so echoing what was read is not a
  rejection. Neither has a writer and neither gained one; what changed is that a round trip no
  longer has to know which of the fields it just read to drop again.

### B3 — the tag semantics, and a correction to the audit

The audit says three surfaces take a set of tags and *"none is labeled in the UI"*. **Two of the
three already were**: the suite's tag rules say *"Any case carrying ANY of these"* and the run's
tags arm says *"Any of these — a case carrying one of them is in the run"*. The population was one —
the case grid's filter, which ANDs and said nothing, so the only way to learn that a second tag
narrows was to add one and watch the list get shorter. It says so now.

### B5 — the format enum, kept wide and no longer offered

`PDF` is in the contract's `format` enum, in every generated client's dropdown, and refused by every
deployment. The audit proposes removing it; the codebase argues at four sites that the enum must
**not** be narrowed to what is installed, because that is a property of the deployment and a
client-side copy is wrong in both directions.

Both are right, and the conclusion drawn from the second was the mistake: it made the picker offer
what nothing can produce. **`GET …/reports/capabilities`** answers the other question — what this
server can render, read off the renderers on its classpath — and the SPA offers the intersection.
The enum stays wide, so a deployment that installs the PDF renderer can be asked for one without a
contract change, and the 400 naming what this server can produce is unchanged for a caller who
ignores the answer. `ReportCapabilitiesTest` holds the server's askable-format copy to the
contract's own enum, both ways.

---

## Phase 2 — the retry that duplicated, and the plan you can state at once (B4, B9)

### B4 — `V59`, and why the artifact register was the worst of the eight

The fifth instance of `V11`'s shape; eight tables carry `created_idempotency_key` now. The register
matters most and **not** because a duplicate row is expensive — `DELETE` has shipped since §126.
It is that one of its two creation paths writes **bytes**: a retried upload duplicated the artifact,
the `attachment` row and the blob, and the blob is the copy nobody sees — the register shows two
entries and the disk holds two signed PDFs. It is also the longest request in the product, so it is
the one that meets a proxy timeout while the server is still writing.

**Shared steps are included, reversing a recorded refusal.** `SharedStepService` said a duplicate is
*"visible in the picker next to its twin"* — `SuiteWriteService`'s argument, which `V42` already
superseded for suites on the grounds that apply here too: it reasons from a person looking at a
screen, and the caller it defends is a tool with none. A library entry is also a reference target,
so two copies split which cases point at which.

`IdempotencyKeyCapTest`'s count went 8 → 10, and the question that test exists to force was asked
again and answered the same way.

### B9 — the create that could say less than the clone

`TestPlanInput` had six properties on the argument that *a plan is named before anybody knows what
would make it releasable*. True of the flow, and it was being enforced by a schema rather than by a
form — so `POST /plans/{id}/clone` could express sections, a tag arm, prose and both criteria and
`POST /plans` could not. Every seeded role holding `plan:create` holds `plan:edit`, so the narrowness
bought a second request rather than a boundary.

All six are optional and the New Plan dialog is untouched. **One real defect fell out**: the create
resolved live suite ids from the top-level arm only, so the first section naming a suite would have
been refused with *"that suite is not in this project any more"* about a suite that is.

---

## Phase 3 — reads and previews (B8, B10, B11)

### B8 — the by-ids read, as a filter rather than an endpoint

`?id=…&id=…` on the existing case list. A filter, so it intersects with everything else and
`?id=…&lifecycleStatus=APPROVED` answers *which of these are approved*; capped at 200, the page
maximum, since more ids than a page can hold cannot be answered in one response anyway.

The consumer is the plan's Test cases tab, which held ids and joined them client-side against the
first 200 cases of the project — so a plan naming a case outside that window drew a chip with no
name on it. It now asks for exactly the ids the plan names, and the unresolved-chip copy changed
from *"outside the loaded list"* to what it now means: a case this project no longer lists.

### B10 — two destructive suite operations, each saying what it costs

- **Delete.** The server refuses while cases are *filed* here and says nothing about the other two
  arms: a case added individually or claimed by a tag rule is dropped without a word. The dialog
  now states the membership it is about to dissolve, from `caseCount` and `caseIds` which the read
  already serves. **The filed count is deliberately not inferred by subtraction** — the arms overlap,
  so `caseCount - caseIds.length` is not it, and a wrong number in a confirmation is worse than an
  absent one.
- **Detach a condition.** `case_condition_optout` is `ON DELETE CASCADE` on `suite_condition`, so
  removing a condition deletes every case's decision to opt out of it — and re-attaching does not
  restore them, because the new row has a new id. The number nobody could see is now
  `CaseConditionRef.declineCount`, and the suite form warns before Save.

### B11 — the gate that would read `null` for ever

Five criterion metrics, two of which the product can decide. The picker offered all five
identically. The audit allows hiding the three; `lib/labels.ts` already argued against it — *a
release gate somebody has agreed to is worth recording before it can be checked automatically* — so
they are **marked** rather than hidden, at the moment of selection.

`criterionMetricEvaluated` is a copy of `PlanStatisticsService.actuals`, and nothing compares them.
That is stated at the site rather than implied away: teach the server a third metric and the SPA
will say "not evaluated yet" about something that now is.

---

## Phase 4 — the two migrations that change what is recorded (B6, B7)

### B6 — a rejected report leaves a row, and the row consumes no key

A report the server could not parse left **no row at all**, deliberately: `ingestion_job`'s key is
unique per company, CI generates it once per build, and a `FAILED` row carrying it would make the
corrected retry replay the failure. Refusing to write was the only way to keep the retry usable.

What it cost is the half nobody wrote down. The 400 body is the entire record of a rejected report;
it goes to a CI job's stdout, which rotates. `GET …/ingestions` — the history a lead reads to answer
*did last night's run report* — showed the successes and was silent about every rejection, so a
pipeline posting malformed XML for a week looked exactly like one nobody had wired up.

**`V60` narrows the uniqueness to `COMPLETED` rows** and both halves are available at once. The
failure is recorded with its key, the retry finds no completed job and proceeds, and the replay
lookup is the index read back (`findCompletedByIdempotencyKey`). Two failures under one key are two
rows on purpose: a build that posted rubbish three times failed three times.

The row is written **under the gate**, in its own transaction, so posting rubbish is not a way to
write into a project's history — or to discover that the project exists.

### B7 — what a run was asked for

`test_run` kept `plan_id` and nothing else about the request, so *why is this case in this run* had
no answer, and a clone could only re-run the materialised list — `run the smoke suite again`
silently omitted every case filed into that suite since.

**`V61` adds `scope_request JSONB`**, written at create from the request and never from the resolved
set: the whole value is being able to see the difference. Materialisation-once is untouched.

- **Null means *not recorded*** — every run created before `V61`, and every run opened by ingestion,
  which had no request. The SPA renders the absence by omitting the line rather than by saying "no
  scope", which would state that a run of two hundred cases was asked for nothing.
- **`rescope` on the clone re-asks it**, and is refused by name when the source has no recorded
  request. A silent fallback to the ordinary clone would answer a different question and look
  identical afterwards.

---

## What to know before touching this again

- **`PatchBody` is opt-in per endpoint, and only the case update uses it.** The plan's and the
  suite's `PATCH`es still read absent as *clear*, which their own headers state. Extending it is
  mechanical; what it needs is a decision per endpoint about the required fields, exactly as B1's
  did.
- **`CaseController` binds its `PATCH` body as a `JsonNode`.** A body that cannot be read as the
  record is this endpoint's 400 now, not the message converter's, because the answer has to be this
  API's `Problem`.
- **`test_run.scope_request` needs `@JdbcTypeCode(SqlTypes.JSON)`**, like every other JSONB column
  here. Without it Hibernate binds a `varchar` and Postgres refuses the insert — which is a whole
  `@Nested` class of clone tests failing on a column none of them mention.
- **A new object schema needs a `ContractConventionTest` pairing entry.** `ReportCapabilities` and
  `RunScopeRequest` both hit it, as `ExecutionAssigneeInput` and `ArtifactEditInput` did in §229.

---

## Gates

Run on `shanks`, per `CLAUDE.md`. Phases 1 and 2 were gated **locally** because another session had
Gradle live in the shared `~/builds/tcms` tree all day — both commits say so — and phase 3 onward is
on shanks:

| Stack | Result |
|---|---|
| `backend` `./gradlew build` | see the phase commits; the shanks run at phase 3 was **BUILD SUCCESSFUL** |
| `frontend` build / lint / `test:run` | clean, 0 warnings |
| `packages` build / lint / tests | clean, 78 / 128 / 123 |

## Queue rows written and not run (§65)

`docs/verification/backend-mutation-checks.md` and `docs/verification/frontend-browser-checks.md`
carry §232's rows. Two of them predict **zero reds** and say so.
