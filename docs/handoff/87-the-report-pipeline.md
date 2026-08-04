# §87 — the report pipeline: the job record, the second store, and the gates that could not fail

**Date:** 2026-08-04 · **Backend** 1553 → **1615** · **`V27__report_job.sql`** · policy count
**58 → 59** · contract **+2 operations**, one schema widened · **no new permission key** ·
`V28` is the next free number

R3 of `docs/plans/2026-08-04-m2.5-reporting-slice-plan.md`, executed as the seven phases of
`docs/plans/2026-08-04-r3-report-pipeline-phases.md` — **the largest slice in the plan**, and the
first end-to-end one M2.5 has: a request writes a row, a worker turns it into bytes, and three
endpoints let somebody ask for it, watch it and take it away. §86 gave `execution_fact` its first
writer; this is what reads it.

Every commit is one phase and each landed after its own gate.

## What was absent

- **`report_job` did not exist.** `V1` designed *what to compute* (`report_definition`) and *what
  came out* (`report_artifact`) and **never the record of a request in flight**. §6e specifies the
  202-plus-poll shape, so `ReportJob.downloadUrl` was a contract field nothing could fill and the
  202 had nothing to name.
- **`report_definition` and `report_artifact` had no entity and no writer** — two more `V1` tables
  in the state §76 found `review` in.
- **`POST …/reports/generate` was declared in the contract and served by nothing.** The poll and the
  download did not exist at all.
- **The mock had shipped the feature wrong**, and nothing could see it because nothing called it.

## The seven phases

| Phase | What landed | Tests |
|---|---|---|
| 1 | the module widening (three places), `V27`, policy count 58 → 59 | 1553 |
| 2 | `ReportJob` / `ReportArtifact` / `ReportDefinition`, two repositories, `ReportStore` + filesystem adapter | 1553 |
| 3 | `ReportModel`, the Markdown and JSON renderers, `RunSummaryReport` | 1584 |
| 4 | `ReportJobService`, `ReportJobRunner`, `ReportJobScheduler` | 1596 |
| 5 | `openapi.yaml` +2 operations, `gen:api`, `ReportController`, the corrected mock | 1596 |
| 6 | **the two custom test roles, and the gates that can now fail** | **1615** |
| 7 | the queues, the cache, this file | — |

---

## The decisions

### The module widened, and §86 is why that is a fact rather than a forecast

`reporting` declared **`{"tenancy"}` alone** in §86 — narrower than every sibling — because nothing
in it was reached through an endpoint. §86 named the widening rather than pre-approving it, and R3
arrives with **both halves of what it named**: `project` for the gate on all three endpoints,
`identity` for the person a job records as having requested it. The declaration is now
`{"tenancy", "project", "identity"}`.

**Three places state that bound and the compiler catches none of them**: the annotation, the package
comment (which asserted the narrow bound as a fact), and `ModuleStructureTest`'s `@DisplayName`
rationale (which named `reporting` as the narrowest of the lot). Prose does not fail `-Werror`. §61
made exactly this edit on `documentation` and is the precedent.

### A table, not an `@Async` dispatch

`@Async` + `@TransactionalEventListener(AFTER_COMMIT)` is the shape `CompanyScopedEvent`'s javadoc
illustrates, is less code and has lower latency. It also **loses every in-flight job on a restart,
with no symptom anywhere but a spinner that never stops** — and the whole reason §6e specifies a job
id rather than a long request is that the work *outlives* the request. So the request writes a
`PENDING` row in its own transaction and returns; a `@Scheduled` worker claims it later, in another
thread, another transaction, possibly another process.

### The company loop is not an optimisation — it is the only way the worker sees anything

`report_job` is company-owned and therefore under `FORCE ROW LEVEL SECURITY`, so the tempting global
*"find all pending jobs"* query returns **zero rows** and the tick logs success having done nothing —
for ever, while every client polls a job that never moves. `company` is deliberately not under RLS
precisely so it can be read before any context exists. `CaseHealthScheduler`'s loop, and §86's after
it; **asserted with two companies rather than trusted to a comment**, because the shape that always
answers empty passes any single-company test that checks only that its own job ran.

`CompanyContext.require()` in the runner makes the *loud* half of this testable: a worker with no
company open **refuses** rather than claiming nothing quietly.

### Bytes first, row second — the inverse of `EvidenceService`, argued in the code

§67 chose rows-first because a row whose blob is missing is findable by an operator and an orphan
blob is not. Here **the job row already names the work** and the storage key is built from its id, so
an orphan blob *is* findable — while a `COMPLETED` job whose download 404s is a client that has
already been told the report is ready, and no sweep repairs it. A reader who knows `EvidenceService`
will otherwise read this as a bug, so `ReportJobRunner`'s header carries the reasoning.

### Everything the server knows at request time is refused at request time

An unknown report type and an unrenderable format are **400s**, derived from the two registries
rather than from a list beside the check — a literal is correct today and silently wrong the day R4
lands the HTML renderer. **PDF says "not installed on this server"**, which is the truth and the
thing an operator can change, rather than "not supported", which reads as a bad request.

What stays late is a report's **own** parameters: a `runId` naming nothing is a `FAILED` job carrying
the computation's sentence, because validating it on the request path would be a second copy of every
report's rules or a database read per type.

### Two artifacts per job, and the JSON one is not a debug convenience

Decision F: the on-screen view (F2, a later slice) reads the **stored model**, so the screen and the
export are one model rendered twice and cannot disagree. A JSON request writes **one** artifact with
both columns naming it — two rows over identical bytes are two files that are equal only until
something between the two renders changes.

### `readGranted` — the product's first read gated on a key beyond visibility

`GateResult`'s header said three states were a *write's* shape. That argument is about **visibility**,
which `readGranted` still answers with 404. What answers **403** is a feature of a project the caller
is demonstrably looking at: telling them their own report request does not exist is false in the one
direction a reporting pipeline must never be wrong.

### No new permission key, for the eighth time

`report:view` and `report:export` have been seeded by `V3` since M0 and this is their **first
consumer**. Generating is exporting; watching your own request finish is not taking anything away.
A key `V3` seeds to nobody is a 403 no administrator can grant their way out of — §67's argument
against `evidence:read`, §79's, §80's, §82's.

---

## Phase 6 — the finding, and the reason it was its own phase

**Until phase 6, both gates were decorative, and no test written before it could have shown that.**

`V3` grants `report:view` and `report:export` **together** to Instance Admin, Company Admin, Test
Lead, Test Engineer and Viewer. The only built-in withheld from them is Automation Service — which
holds no `project:read` either, so it 404s on the visibility gate one step earlier and **never
reaches a permission check at all**. So no persona the suite could build lacked either key, every
gate assertion available was satisfied by an implementation with **no gate**, and the mutation that
deletes one would have reddened nothing.

### Two custom roles, and one would not have been enough

`role` is instance-level with no `company_id` and a globally unique name, so a fixture may insert
one. The ERD's *"there are no custom roles in v1"* is a statement about the **product** — no endpoint
creates one, nothing offers one — and this fixture does not change that.

A single persona holding **neither** key gets 403 from all three operations, which proves *a* gate
runs and says nothing about **which key** it asks for: swapping `report:export` for `report:view` on
generate keeps every assertion green. So there are two:

- **Report Reader** — `report:view` and **not** `report:export`. The role §6e's two-key split exists
  to make expressible: *may read reports on screen, may not carry them off.* They poll a job (200)
  and are refused the generate and the download (403). **This is what makes the two keys
  distinguishable**, in both directions: a poll gated on `report:export` refuses them, and a generate
  gated on `report:view` accepts them.
- **Report Blind** — neither key. This is what keeps the **poll's** gate non-vacuous: with only the
  persona above, deleting that check outright leaves every assertion in the class green.

Both hold `project:read` (which *is* visibility — `ProjectAccess.canView`), so their refusals are
**403** where a member with no grant gets **404**. That is `readGranted`'s split asserted rather than
described.

**The fixture proves itself**, which matters because a broken one would fail closed and look right:
Report Reader's **200** on the poll is the evidence the custom role confers visibility *and*
`report:view`; Report Blind's **403** rather than 404 is the evidence theirs confers visibility and
not the key.

### The fixture rows another test class asserts the *absence* of

`SeedDataTest.sixBuiltInRoles` asserts that `SELECT name FROM role WHERE NOT built_in` is
**empty**. A leaked custom role therefore reddens a class with nothing to do with reporting, **in the
full suite only, at a position that moves with test order** — §19's fixture-collision defect through
a mechanism **no UUID prefix protects against**, because the collision is with an assertion about
absence rather than with another fixture's id.

Hence an `@AfterAll` that deletes both roles, with the assignments going first:
`role_assignment.role_id` has no `ON DELETE CASCADE` where `role_permission` does. The mutation queue
carries a row for deleting that cleanup, because a cleanup nothing checks is a cleanup that gets
removed as noise.

### Two scoping tests that need a caller holding *both* projects

`report_artifact` carries **no `project_id`** (`V1` scoped it by company and definition), so the job
is the only row that scopes it. A job or an artifact resolved by id alone is served through a gate
that passed for a **different project** — the version of the defect that *survives* an access-control
test, because the access control ran, on the wrong row. Company isolation still holds through RLS;
what is opened is one of a client's own projects reading another's reports through a path they hold.
LEAD is granted two Acme projects for exactly this pair of assertions.

---

## What the mock had been doing

`frontend/src/mocks/handlers/reporting.ts` answered `{ id, status: 'QUEUED', format: 'PDF',
requestedAt, downloadUrl }` — **`id` where the contract says `jobId`**, a `requestedAt` the contract
never declared, and **`QUEUED`, never one of the four values `ReportJob.status` has had**. `GET
…/reports` answered `[]` unconditionally. §52's *"the mock agrees with an idea of the feature"*, §81's
finding one slice earlier — and **cheap only because it was corrected before a screen depended on
it**, where §81's had been wrong for 28 slices.

It is now a real pipeline: jobs in module state advancing **one state per poll**, so `PENDING` and
`RUNNING` are reachable in dev rather than being states that only ever appear in production.

---

## Gates

Every phase ran `./gradlew build` (phase 5 also `npm run build` + oxlint, 0 warnings). The final
gate: **BUILD SUCCESSFUL — 1615 tests across 318 classes, 0 failures, 0 skipped** (1553 → 1615),
counted from the XML rather than the console.

**Phases 3, 4 and 6 each had every new test pass on the first run once the fixture compiled**, which
is stated plainly rather than taken as evidence they can fail. That is what the mutation rows are
for.

Two fixture faults found by running phase 6, both mechanical and both worth the note: a `varchar`
bound where Postgres wanted a `uuid` in the cleanup statement, and **`uq_test_run_key` being
`(company_id, key)` rather than per project** — the two projects in that fixture share a company, so
two runs both keyed `TR-1` collide.

## Found and not fixed

- **The scheduler's company loop is asserted nowhere.** `tcms.reporting.enabled=false` in
  `IntegrationTestBase` (for §19's reason), so nothing drives `ReportJobScheduler`; what the suite
  covers is the *loud* failure, not the loop. §86 left the identical hole in `ExecutionFactScheduler`
  and the two mutation rows should be read together.
- **The bytes-before-row ordering is observable only if the store write fails**, and nothing in the
  suite makes it fail. The honest expected value of that mutation row is 0, and 0 is the finding: the
  fix is a store adapter that can be made to fail, not a change to the ordering.
- **`case_health` still has no index on `failing_repeatedly`** (§86's finding). `V27` was the
  opportunity and it was not taken — the migration is about `report_job` and widening it to carry an
  unrelated index would have made the one migration in the plan two things.
- **Retention: nothing is ever deleted, and that is a decision on the record** (user, 2026-08-04),
  written into `V27`'s header with §6n named as what will eventually govern it and the reaper named
  as one more arm on the loop the worker already runs. Unbounded growth is the accepted cost.

## Verification status

**Built and unverified.** Per §65 (restated 2026-08-02) **zero mutations were run inline** and no
browser pass was driven. Queued this slice:

- `docs/verification/backend-mutation-checks.md` — **eleven rows**, of which the first six are what
  phase 6 bought and should be run first.
- `docs/verification/frontend-browser-checks.md` — **eight rows**, five of them console-driven
  against the mock (there is no screen yet, and recording it that way is more honest than deferring
  the handler until one exists) and three live-only, including the two-key persona that mock mode
  structurally cannot show.

## Named next

- **F1 / F2 — the request form and the on-screen report view.** The contract, the mock and the model
  artifact are all in place for them; `modelUrl` exists for F2 specifically.
- **R4 — the HTML renderer**, which is what `ReportRenderers`' derived `supports()` was built to
  absorb without a second `switch`.
- **The remaining nine report types in §6e's catalogue**; `run_summary` is the first, and three of
  its five sections — the other two need case labels the fact log deliberately does not carry.
- **The two remaining board generators** and §6l's per-project toggles, cap and notifications, still
  outstanding from §84.
