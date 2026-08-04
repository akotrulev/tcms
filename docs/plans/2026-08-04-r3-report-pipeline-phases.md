# R3 — the job record, the store, and the pipeline end to end: phase breakdown

**Date:** 2026-08-04 · **Status:** **SHIPPED — all seven phases, see `docs/handoff/87-the-report-pipeline.md`**
(the two the summary called most likely to be skipped, 6 and 7, both landed) · **Slice:** R3 of
`docs/plans/2026-08-04-m2.5-reporting-slice-plan.md` · **Predecessor:** §86 (R1 + R2)

The slice plan argues *what* R3 builds and *why*. This file is the execution order: seven phases,
each ending at a gate and a commit, in the sequence that keeps every commit a state whose gates were
actually run. It exists because R3 is the largest slice in the plan — a migration, five entity
families, two ports, a renderer pair, a worker and three endpoints — and "commit at the end of each
phase" needs the phases named before the work starts, not discovered halfway.

Section references (`§6e`) point at `docs/plans/2026-07-30-tcms-high-level-plan.md`; `§NN` alone at
`docs/handoff/NN-*.md`; `R3`, `decision B` etc. at the slice plan.

---

## 0. Pre-flight — three things to settle before Phase 1

These are decisions, not tasks. Each one changes code in more than one phase if it lands late.

1. **Retention (`[TBD — user]`, slice plan §7 Q1).** R3 is the slice that writes bytes to a disk that
   nothing ever cleans. The honest v1 answer is *never delete, and say so in the migration comment* —
   but it must be a decision on the record, because the alternative (`expires_at` on
   `report_artifact`) is a column, and adding a column to a table R3 starts writing is a second
   migration. **Recommend: never delete in v1**, written into `V27`'s header as an accepted cost with
   §6n named, and revisited when the register grows a pin.
2. **The custom test role (`[TBD — qa]`, §7 Q5 — resolved as "no").** Confirmed against the code: the
   only built-in role withheld `report:view`/`report:export` is Automation Service, and API-token
   auth is M3, so **no persona an endpoint test can build today lacks either key**. Without a custom
   `PROJECT`-scope role holding `case:read` + `project:read` and *not* the report keys, both gates in
   this slice are unfalsifiable and the mutation that deletes them reddens nothing. This is Phase 6
   work and it is **not optional** — see that phase.
3. **Fixture UUID prefix.** The slice plan names `re-`, `rj-`, `rf-` as unclaimed *at planning time*.
   Hand-managed, no check behind it, **collided five times** (§19 … §62). Re-grep at write time:
   `grep -rn "'rj-\|\"rj-" backend/src/test` before claiming one.

---

## Phase 1 — the module widens, and `V27` lands

**Builds.**

- `reporting/package-info.java`: `allowedDependencies` from `{"tenancy"}` to
  `{"tenancy", "project", "identity"}` — **plus the package comment**, which currently states the
  narrow bound as a fact, **plus `ModuleStructureTest`'s `@DisplayName` rationale**, which names
  `reporting` as the narrowest of the lot with `tenancy` ALONE. Three places, none of which the build
  catches: prose does not fail `-Werror`. §61 made exactly this edit on `documentation` and is the
  precedent to copy.
- `V27__report_job.sql` — the table, its RLS policy, its claim index, and the widened
  `report_artifact.format` CHECK (decision F: `+'JSON'`).
- `SchemaConventionTest`'s policy count **58 → 59** (`SchemaConventionTest.java:262`).

**The migration's shape**, following `V25` (the most recent table-creating migration and the closest
model):

```
report_job(
  id, company_id NOT NULL REFERENCES company(id), project_id NOT NULL REFERENCES project(id),
  definition_id NULL REFERENCES report_definition(id), type, format,
  parameters JSONB NOT NULL DEFAULT '{}',
  status CHECK IN ('PENDING','RUNNING','COMPLETED','FAILED'),
  artifact_id NULL, model_artifact_id NULL, error, attempts NOT NULL DEFAULT 0,
  requested_by NULL, requested_at, started_at NULL, finished_at NULL)
```

Four things the file must get right, each with a defect behind it:

- **The RLS predicate is `V4`'s, not `V1`'s** —
  `nullif(current_setting('app.current_company', true), '')::uuid`. A table born with the bare
  spelling raises `invalid input syntax for type uuid` on a pooled connection whose GUC has reset to
  `''`, on exactly this table and no other. `V25`'s header states it; copy the statement, not the
  memory of it.
- **`company_id` leads the claim index** — `(company_id, status, requested_at)`. ERD §0.1 point 3,
  and `SchemaConventionTest` fails the build on an index that mentions the column without leading
  with it.
- **`requested_by` is nullable**, like every other actor column since §66: an offboarded account does
  not un-request a report.
- **The policy count moving is the point.** It is what keeps the predicate check non-vacuous; a
  migration that adds a policy without moving the count leaves the assertion passing on 58 of 59.

**Gate.** `cd backend && ./gradlew build` — the boot itself is the gate here (`ddl-auto: validate`
catches entity drift at startup, and Phase 2 is what adds the entities, so this phase's build proves
the migration applies and the module list still matches the source tree).

**Commit.** `§87 phase 1 — the module widens and the job record lands`.

**Risk.** The `report_artifact.format` CHECK widening is a `DROP CONSTRAINT` + `ADD CONSTRAINT` on a
table with zero rows today. Trivial now; it is the last moment it is trivial.

---

## Phase 2 — entities, repositories, and the store port

**Builds.** `ReportJob` · `ReportArtifact` · `ReportDefinition` entities + their repositories ·
`ReportStore` (port) + `FilesystemReportStore` + `tcms.storage.reports.root` in `application.yml`.

**The port is a deliberate copy of `execution.EvidenceStore`** — four methods over an opaque key,
same signatures. Decision C accepts this as §58's E1 (the copy that drifts) because `reporting` may
not import `execution`, and requires **the debt named in both headers**: `ReportStore`'s javadoc says
it is a copy of `EvidenceStore` and why, and `EvidenceStore`'s gains the reciprocal line. The day
either grows a fifth method, the other is where to look. Without both halves the pairing is invisible
from the file you happen to open.

**Every repository interface carries `@Transactional(readOnly = true)`.** Not advisory: without it
the derived and `@Query` methods run outside a transaction, `app.current_company` is never set, and
every read returns **zero rows** while `CompanyContext` holds the right company — fail-closed and
silent. `RepositoryTransactionTest` derives its list from the live context and will fail the build,
which is the intended outcome and not a surprise to work around.

**Gate.** `./gradlew build`. `validate` now has entities to check against `V27`.

**Commit.** `§87 phase 2 — the entities and the second storage port`.

---

## Phase 3 — `ReportModel`, the renderers, and the first report type

**Builds.** `ReportModel` (the serializable value: title, metadata, sections, tables, chart series) ·
`MarkdownReportRenderer` · the JSON writer · `RunSummaryReport` — the first type, computed over
`execution_fact` only.

**No endpoint, no worker, no bytes yet** — this phase is pure computation and rendering, testable
directly, and that is why it is its own commit. A renderer bug found here is found without a job
pipeline in the way.

**Why MD + JSON and not HTML.** Markdown needs no template engine, no CSS inlining and no browser, so
R3 proves the *pipeline* rather than a renderer; HTML is R4 and PDF is refused (Phase 5). The JSON
artifact is not a debug convenience — decision F makes it what the on-screen view (F2) reads, so the
screen and the export are provably one model.

**Reads `execution_fact` only**, which is the whole reason R1 came first. Nothing in this phase may
name a foreign table: `ExecutionSource` is the module's one seam file and it already exists.

**Gate.** `./gradlew build`.

**Commit.** `§87 phase 3 — the model, the markdown renderer, and the first report`.

---

## Phase 4 — the worker: request, claim, run, complete

**Builds.** `ReportJobService` (writes the `PENDING` row) · `ReportJobRunner` (claims, runs, stores,
completes) · `ReportJobScheduler` (the company loop).

**The scheduler is `CaseHealthScheduler` copied with intent**, and its header already argues every
line worth arguing:

- A scheduled task starts with **no company at all** — `CompanyContext` is not
  `InheritableThreadLocal` and `CompanyContextTaskDecorator` clears it, so a job that forgets fails
  closed.
- `company` is deliberately **not** under RLS so it can be read before any context exists. That is
  what makes the loop possible — and here it is not an optimisation but **the only way the worker can
  see a job at all**: `report_job` is RLS-protected, so a "find all pending jobs" query with no
  context returns **zero rows** and the worker logs a successful tick having done nothing. That
  failure is completely silent, which is why it is written down twice.
- **One transaction per company**, never one spanning the loop — changing `app.current_company`
  inside a transaction is the one thing `CompanyAwareTransactionManager` exists to prevent.
- **Catch per company**; one company's lock timeout must not cost the other forty-nine.

**The refresh step.** The runner calls `ExecutionFactRefreshService.refresh()` for the open company
**before computing** (decision A's answer to the freshness cost), so a generated report's watermark is
exact and the schedule is only the floor for anything reading facts outside a job. This is a call to
shipped §86 code, not new work.

**Bytes first, row second — the inversion of `EvidenceService`, and it must be argued in the code.**
§67 chose rows-first because a missing blob is findable by an operator and an orphan blob is not.
Here **the job row already names the work**, so an orphan blob *is* findable (it is the job's), and
the failure that matters is a `COMPLETED` job whose download 404s. Write the reasoning into
`ReportJobRunner`'s header; a reader who knows `EvidenceService` will otherwise read this as a bug.

**v1 concurrency: one job at a time, single-threaded, no `SKIP LOCKED`.** The filesystem adapter
already pins the deployment to one replica, so a second worker has no deployment to run in.
`attempts` and `error` land now; **no retry policy** — a `FAILED` job is re-requested by the user,
which is honest and costs one click.

**No `Instant.now()`** — `ArchitectureRulesTest.nothingCallsNowDirectly` fails the build. Take the
`Clock` bean.

**Gate.** `./gradlew build`.

**Commit.** `§87 phase 4 — the worker, the company loop, and bytes before the row`.

---

## Phase 5 — the contract, the three endpoints, and the mock

**Builds.** `openapi.yaml` first, then `npm run gen:api`, then the controller and the corrected mock —
in that order, in one commit, per the working agreement.

**The contract changes.**

- `POST …/reports/generate` — already declared; the request body gains `parameters` typing for the
  first report type. **`format`'s enum currently reads `[PDF, MD, HTML]`** and PDF is refused at
  request time (below), which is a server behaviour rather than a contract narrowing — leave the enum
  and describe the 400.
- **`GET …/reports/jobs/{jobId}`** — new. There is nothing to poll today, and `ReportJob.status`'s own
  comment says a poller reads it to decide whether to keep polling.
- **`GET …/reports/artifacts/{artifactId}/content`** — new. `ReportJob.downloadUrl` is **required and
  nullable**; a generate endpoint shipped alone would have to invent the URL and every client would
  take the invention for the contract (§67, verbatim).
- **Every new schema gets its `required` array, including inline nested objects.** `required` does not
  descend; without it every generated property is optional and no consumer can use it.
  `ContractConventionTest` and `requiredPropertiesHaveAComponent` both check, and §80's slip was
  caught by exactly that.

**The gates.** `report:export` on generate and on artifact download; `report:view` on the job poll.
Both derived from `V3`, **neither new** — this is their first consumer since M0. The download route
follows `EvidenceController.content`'s precedent for what *not* to invent: no `report:download` key,
because a key `V3` seeds to nobody is a 403 no administrator can grant their way out of.

**PDF is a `400` at request time, not a job that fails an hour later.** The contract's `format` enum
offers it and a client cannot tell an unsupported format from a broken server by watching a job go
`FAILED`. Name PDF rendering as not installed in the message.

**The mock is corrected in this commit, not later.** `frontend/src/mocks/handlers/reporting.ts`
currently answers `{ id, status: 'QUEUED', format: 'PDF', requestedAt, downloadUrl }` — `id` where the
contract says `jobId`, `requestedAt` which the contract does not declare, and `QUEUED` which is **not
one of the four enum values**. `GET …/reports` answers `[]` unconditionally. It is §52's *"the mock
agrees with an idea of the feature"* class, cheap only because nothing calls it yet — §81 found the
same class after it had been wrong for 28 slices.

**Gates.** `./gradlew build` **and** `cd frontend && npm run build && npm run lint` (0 warnings).

**Commit.** `§87 phase 5 — three operations, the gates, and the mock that had never matched`.

---

## Phase 6 — the tests that can fail

Named as its own phase because the slice plan's sharpest finding is that **most of R3's tests would
pass against a broken implementation**, and the fixes are structural rather than additional.

- **The custom test role.** `role` is an instance-level table with no `company_id` and a global unique
  name, so an endpoint test may insert a custom `PROJECT`-scope role holding `case:read` +
  `project:read` and *not* the report keys, and grant it to a persona. **This is the first custom role
  in the test suite**; the ERD's *"there are no custom roles in v1"* note is about the product, not
  the fixture, and this does not change the product. Without it, both gates in Phase 5 are decorative.
- **The RLS test that matters** is the worker one: a `PENDING` job for company A must be invisible to
  a context-less query and must be claimed when A's context is open. Assert with **two companies**,
  the way `CaseHealthRecomputeTest` does rather than trusting the comment.
- **The `COMPLETED`-with-no-bytes test.** A job that reports success must have a downloadable
  artifact; this is the failure the bytes-first ordering exists to prevent, and it is the one that
  looks fine in every other assertion.
- **The PDF 400** — a request-time refusal, not a `FAILED` job.

**Gate.** `./gradlew build` — the full ~1553+ suite. **CI does not run backend tests**
(`-x test`, changed 2026-08-02), so this local run is the only thing that ever executes them.

**Commit.** `§87 phase 6 — the first custom role, and the gates that can now fail`.

---

## Phase 7 — the queues and the handoff

**Appends, runs nothing.** Per §65 standing policy, restated 2026-08-02: **deferred means all of
them.** §72–§76 each ran a handful inline and recorded them as `✅ RUN`, which made the exception look
like the rule. Write the rows, run nothing.

- `docs/verification/backend-mutation-checks.md` — at minimum: swap `report:export` for `report:view`
  on generate (must redden **only** because of Phase 6's custom role); delete the company loop and
  query globally (must redden, and would not have before Phase 6); invert the bytes/row ordering;
  remove the PDF refusal.
- `docs/verification/frontend-browser-checks.md` — the mock's three corrected shapes, which have no
  screen until F1 but do have a handler that can be driven.
- `docs/handoff/87-<slug>.md` **plus one index row in `docs/HANDOFF.md`** — never append to
  `HANDOFF.md` itself.
- `docs/agent-context/be-java.md` — **in this slice's own commit**: the new seam entries, the second
  storage port and its E1 pairing, the policy count 59, `V27` as head.

**A slice in an unrun queue is *built and unverified*, and the handoff must describe it that way** —
not as done.

**Commit.** `§87 phase 7 — the queues, the cache, and the handoff`.

---

## Summary

| Phase | Builds | Gate | Migration |
|---|---|---|---|
| 1 | module widening (3 places), `V27`, policy count 58→59 | `./gradlew build` | **`V27`** |
| 2 | entities, repositories, `ReportStore` + filesystem adapter | `./gradlew build` | — |
| 3 | `ReportModel`, MD + JSON renderers, `RunSummaryReport` | `./gradlew build` | — |
| 4 | `ReportJobService`, `Runner`, `Scheduler` (company loop) | `./gradlew build` | — |
| 5 | contract +2 ops, `gen:api`, controller, mock corrected | `./gradlew build` + `npm run build` + `npm run lint` | — |
| 6 | the custom test role, the two-company RLS test, the 400 | `./gradlew build` (full suite) | — |
| 7 | verification queues, handoff §87, orientation cache | — | — |

**Seven commits. One migration, in Phase 1. No new permission key anywhere.**

**The two phases most likely to be skipped are 6 and 7**, and they are the two the slice is worth
least without: Phase 6 is what makes the gates falsifiable at all, and Phase 7 is what keeps the
slice honest about being unverified.
