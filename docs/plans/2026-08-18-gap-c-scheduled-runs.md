# Scheduled Runs Implementation Plan

**Date:** 2026-08-22 · **Written by:** `ep-writing-plans` · **Area C of
`docs/plans/2026-08-18-unplanned-gaps.md` (tasks 15–25)**

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a run automatically on a saved schedule, so a regression cycle happens on a cadence
rather than because somebody remembered. A schedule stores *what to ask for* — the same
`{planId, caseIds, suiteIds, tags}` document `V61` already stores on `test_run` — plus *when*, and
the poller re-asks it.

**Architecture:** One new company-owned table (`run_schedule`, `V68` — the only migration in the
master plan), one entity/repository pair, one CRUD service and controller in `com.tcms.execution`,
one cron parser, and a **per-company scheduler modelled on `CaseHealthScheduler`**. Run creation is
**not reimplemented**: the poller enters `RunCreateService` through a new package-private door, so
the pin, the display-key allocation, the approval gate and the audit row stay in the one place that
has ever written a run. One new SPA screen, one new route.

**Tech Stack:** Spring Boot 3.5.6 / Java 21 / Flyway / Postgres 17 · Spring's own
`org.springframework.scheduling.support.CronExpression` (already on the classpath via
`spring-context` — **no new dependency**) · `openapi.yaml` as the contract, generated into
`frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts` · React 19 / TypeScript / MUI v7 /
TanStack Query.

---

## Read these first, in this order

1. `CLAUDE.md` — the working agreement, the gate commands, and **tests run on `shanks`**.
2. `docs/agent-context/be-java.md` — the seam list. Trust the body over the `Baseline commit:`
   header and re-grep anything a later commit could have moved.
3. `docs/plans/2026-08-18-unplanned-gaps.md` — Area C (tasks 15–25), the parallelism section, and
   the Scope Check that requires **this** document to exist before any code is written.
4. `docs/specs/2026-07-30-tcms-erd.md` **§0.1** — the `company_id` rule and the RLS exception list.
   `run_schedule` is **company-owned**: it is not an exception, it takes `company_id UUID NOT NULL`
   and a `FORCE ROW LEVEL SECURITY` policy.
5. `backend/src/main/resources/db/migration/V61__test_run_scope_request.sql` — the scope document
   this area reuses rather than reinventing.
6. `backend/src/main/java/com/tcms/repository/CaseHealthScheduler.java` — the poller shape.

**Skills to load:** `data:data-migrations` and `data:data-postgresql` before Task 1 (the DDL and the
RLS/`company_id` rule), `be-java:be-java-database-code` before Task 3 (the entity and the
repository's `@Transactional`).

---

## Contract ownership — this document does not edit `openapi.yaml`

This plan was written as one of five concurrent planning lanes and **another lane holds
`docs/specs/openapi.yaml`**. Every contract change area C needs is therefore written out here as a
fenced `yaml` fragment, in Phase 3, to be applied by whoever executes this plan. **Do not run
`npm run gen:api` until the fragments are actually in the file** — the generated copies are compared
byte-for-byte by `schemaFreshness.test.ts` and there is exactly one right moment to regenerate them.

---

## Parallelism

Per the master plan's table: area C **holds the contract** and **holds `V68`**, works in the
`execution` backend module, and adds `RunSchedulePage.tsx` plus a `router.tsx` entry.

| Runs beside | Verdict |
|---|---|
| **E** (company export) | ✅ — disjoint module, no contract, no frontend. E gains `run_schedule` coverage the moment C lands. |
| **F** (keyboard execution) | ✅ — F is `ExecutionPage`/`ExecutionQueue` only and holds no contract. |
| **D** (cross-project search) | ❌ — both hold the contract **and** both edit `router.tsx`. |
| **A**, **B** | ❌ — same module, same contract. |
| **G** | ❌ — G sweeps every frontend file. |

**Eligibility is not authorisation.** `docs/specs/parallel-work-rules.md` opens with *"Work is
sequential"* and the standing cap is one subagent; a second lane needs the user's explicit
permission first.

**`V68` is pre-assigned and is the only migration in the master plan.** Head is `V67`,
`out-of-order` is unset. **Do not renumber it and do not add a second migration** — if this area
needs another index later, that is a follow-up with its own version, recorded in the handoff rather
than smuggled in.

**Fixture UUID prefix: `e7`.** Pre-assigned, verified unused on 2026-08-22 (`grep -rn "e7[0-9a-f]*-0000-4000" backend/src/test`
returns nothing; the neighbouring run tests use `eb`). Every id this area's tests mint is
`e7XXXXXX-0000-4000-8000-…`. Do not choose another — the prefix space is hand-managed with no check
behind it and has collided five times.

---

## Nine decisions the master plan did not make, and why

The master plan sized area C and named its traps. These are the calls this document adds. Each is a
place where a plausible alternative exists, so each says which one and what it costs.

### 1. `test_run.source` gains a third member, `SCHEDULED`

Today the CHECK is `source IN ('MANUAL','INGESTED')` (`V1__initial_schema.sql:806`) and `RunSource`
has two members. A scheduled run entering through `RunCreateService` would be recorded as `MANUAL`,
which is the door metaphor `TestRun#create`'s header uses — and under that metaphor a poller is a
third door, not the manual one.

The cost of not doing it is concrete and appears on a shipped screen: `RunListPage.tsx:161` renders
`run.source === 'INGESTED' ? 'from CI' : 'manual'`, so every scheduled run would be labelled
**"manual"** beside a `createdBy` of nobody. That is a false statement about who did something,
which is exactly the attribution defect `AuditEvent.actorTokenId`'s header refuses.

So `V68` replaces the CHECK, `RunSource` gains `SCHEDULED`, the contract's two inline
`enum: [MANUAL, INGESTED]` sites gain it, and **the ternary becomes a `satisfies Record<RunSource, string>`
map in `lib/labels.ts`** — which is the working agreement's rule (`New display logic goes in the
shared layer`) and is what makes the *next* member a compile error instead of a wrong word.

This is the one existing table area C touches. Everything else it adds is new.

### 2. Authority and attribution are split: the owner's grant is re-checked, the audit row names nobody

A schedule cannot fire on nobody's authority — that would be a standing, unrevokable right to create
runs in a project. So `run_schedule.created_by` is `NOT NULL` and **the owner's `run:create` grant is
re-evaluated on every single fire**, through the ordinary `ProjectService.writeGranted` gate
(`ProjectService.java:253`). A schedule whose owner was offboarded, lost the project grant or had
their role narrowed stops firing, silently and correctly, with the reason recorded on the row.

But the *actor* on both the run row and the audit row is **null**, because at 03:00 nobody performed
the act. `AuditEvent.record`'s own parameter doc says so — *"the authenticated caller; null only for
an unattended job"* (`AuditEvent.java:152`) — and the alternative, stamping the owner, produces
"Ana created TR-91" about a night on which Ana did nothing. That is the same class of lie the
`actorTokenId` header refuses, and it is worse here because it is not even a credential's act.

Mechanically this is a two-line split inside `RunCreateService.apply`: the gate keeps taking
`userId`, and `TestRun.create` and `AuditEvent.record` take `source == SCHEDULED ? null : userId`.
Task 12.

**This is what makes `§242 LG-5` drivable — see the dedicated section below.**

### 3. No catch-up. A missed slot is a slot that did not happen

`next_fire_at` is recomputed **from the moment of firing**, not from the missed slot. A deployment
down for a day does not produce a day's worth of backdated regression runs on restart.

The alternative — loop `CronExpression.next` forward from `last_fired_at` until it passes now, firing
each — is what a job scheduler does and what a *test* scheduler must not: seven identical runs
appear at once, each materialising a full checklist against today's case versions, and none of them
means what its timestamp says. A regression pass that did not happen on Tuesday cannot be made to
have happened on Tuesday.

The honest record of the gap is `last_fired_at`, which the screen shows, and which a reader can
compare against the cadence themselves.

### 4. The double-fire guard is the idempotency key that already exists

`RunCreateService` already refuses a second checklist for a repeated `Idempotency-Key`
(`RunCreateService.java:298-306`, backed by `TestRunRepository.findByIdempotencyKey`). The poller
mints `schedule:<scheduleId>:<slotInstant>` — deterministic in the schedule and the slot, well
inside the 200-character cap — so two application instances polling the same tick, or a retry after a
partial failure, produce **one** run and a `Replayed` outcome for the loser.

Nothing new is built for this. Writing a second guard would be a second implementation of the one
property whose absence `RunCreateService`'s comment calls *"a second CHECKLIST"*.

### 5. The archived-project refusal lives in the firing service, not in `RunCreateService`

The master plan (Task 20) requires that a schedule whose project was archived must not fire, and it
is right: a job that quietly resurrects archived work is worse than a job that stops.

**Archiving blocks no write anywhere else in this product today** — `grep -rn "isArchived()"
backend/src/main/java` finds it only in `ProjectArchiveService` (the toggle itself), two view
projections and `TestPlan`'s own separate flag. So this is a **new rule that belongs to schedules**,
not a rule about run creation, and putting it inside `RunCreateService` would add a sealed
`Outcome` arm that `RunController`'s exhaustive switch must handle and that the HTTP path can never
produce — a dead arm on a shipped endpoint.

`RunScheduleFiringService` therefore asks it inside its own `writeGranted` lambda, which is handed
the `Project`. The cost is one extra permission evaluation per fire (the nested `createScheduled`
re-gates), which is per *fire*, not per request, and buys the new rule staying entirely inside the
new code.

### 6. No new permission. The catalogue stays at 55 keys

Writing a schedule is gated on **`run:create`** — creating a schedule is delegated run creation, and
a caller who may create a run now may create one every Monday. Reading a schedule is gated on
project visibility alone, like the run list.

A `schedule:manage` key would need seeding grants for six roles, which is a **second migration**, and
`V68` is the only one this plan has. It would also need `openapi.yaml`'s permission enum,
`lib/permissions.ts`, `docs/specs/permission-matrix-summary.md` and the README table — all four
compared cell-by-cell in both directions by `matrixCopies.test.ts`. None of that is justified by a
distinction the product does not draw.

### 7. A cron expression is parsed in exactly one place, and validated at write time

`CronSchedule` wraps `org.springframework.scheduling.support.CronExpression` — Spring's six-field
form, already on the classpath. A bad expression is a **400 at write time**, never a schedule that
silently never fires. The parser is also what computes `next_fire_at`, so the write path and the
poller cannot disagree about what an expression means: they call the same method.

The master plan names the trap in as many words — *"Do not scatter parsing across the service and the
poller."*

### 8. A zone is stored, because "every weekday at 07:00" is not a time without one

`zone_id TEXT NOT NULL`, validated with `ZoneId.of` at write time. Defaulting to UTC in code and not
storing it would put a Sofia team's morning regression at 10:00 for half the year and 09:00 for the
other half, with nothing on the screen to explain it. The zone is the operator's answer, so it is a
stored column and a form field.

### 9. `next_fire_at` is a stored column, not a computation over every row

`WHERE enabled AND next_fire_at <= :now` is one index range scan
(`run_schedule_due_idx (company_id, next_fire_at) WHERE enabled`). The alternative — read every
schedule in the company and evaluate its cron in Java — is a full read of the table on every tick per
company, to discard nearly all of it. It is also the shape whose cost is a function of how many
schedules *other* projects have.

Stored state must be repaired when the expression or the zone changes, which is why exactly one
method writes it (Task 8, `RunScheduleService#recomputeNextFire`) and it is called from create,
update, enable/disable and fire.

---

## Why `CaseHealthScheduler` and not `OutboxScheduler`

The master plan is explicit — mirror `CaseHealthScheduler.recomputeAllCompanies()`
(`backend/src/main/java/com/tcms/repository/CaseHealthScheduler.java:73-87`), not `OutboxScheduler`.
**Read both before writing the poller.** They look nearly identical, which is the reason this
section exists: the difference is not in the loop, it is in what the loop calls.

The loop itself is the same in both and is non-negotiable in either:

```java
for (Company company : companies.findAll()) {
    try (var ignored = CompanyContext.open(company.getId())) {
        …
    } catch (RuntimeException failure) {
        log.warn("… failed for company {}", company.getId(), failure);
    }
}
```

and both class headers explain why it cannot be one query: a scheduled task starts with **no company
at all** — `CompanyContext` is deliberately not inheritable and `CompanyContextTaskDecorator` clears
it on every task — so the tempting shape, one query for every due row in the instance, **returns zero
rows and logs a successful tick having delivered nothing**. `company` is one of the few tables not
under RLS precisely so it can be read before any context exists.

**What makes `OutboxScheduler` the wrong model is everything behind that loop:**

| | `OutboxScheduler` | This poller |
|---|---|---|
| What it calls | `OutboxDispatcher.dispatchDue()` — a **lease-based queue drain**: claim rows under a lease, run the handler, record the failure *on the row*, retry with backoff, eventually dead-letter | One domain write per due schedule |
| Delivery semantics | At-least-once, with a ledger (`NotificationLedger`) making the duplicate harmless | **At-most-once**, guarded by the run's own idempotency key. A duplicate regression run is not harmless — it is a second checklist |
| Failure handling | Recorded on the event row and **retried**; the dispatcher never lets a handler failure reach the scheduler | The next tick simply tries the next slot. There is no retry of a missed slot — see decision 3 |
| Cadence | Configurable (`tcms.outbox.interval`), and it must agree with `tcms.outbox.lease` or `OutboxProperties` refuses the combination | A fixed literal. The tick rate is the resolution of the cron, not an operator's latency budget |
| Module | `tenancy` — infrastructure everything depends on | `execution` — a product feature |

Copying `OutboxScheduler` would mean copying a lease column, a retry counter, a backoff and an
`OutboxProperties`-style consistency check into a table that needs none of them, and it would frame a
missed run as something to be redelivered. `CaseHealthScheduler` is the precedent because it does
what this does: **iterate companies, open each, do one idempotent thing, catch per company, log the
id, continue.** Its own header says why the `continue` matters — *"losing the other forty-nine
companies because one has a lock timeout is a much worse outcome than a warning in the log."*

One property is taken from *both* siblings deliberately: **suspended companies are polled too.**
Both headers argue it (suspension is reversible by policy, §6s) and the alternative here would be a
schedule that silently skips its slots for the length of a suspension with no record of having done
so — while `last_fired_at` would say it last ran before the suspension, which is true and readable.

---

## The scope shape this area reuses — `V61`, and nothing new

**Do not invent a second scope encoding.** The document already exists and is already normalised,
served, parsed and written:

| Piece | Where | What it is |
|---|---|---|
| The column | `backend/src/main/resources/db/migration/V61__test_run_scope_request.sql` — `ALTER TABLE test_run ADD COLUMN scope_request JSONB` | `{planId, caseIds, suiteIds, tags}`, normalised, as sent |
| Why JSONB and not four columns | `V61`'s header, *"JSONB AND NOT FOUR COLUMNS / A JOIN TABLE"* | A run's request is written once, never edited, read whole, never joined against — it is a document |
| The writer | `RunCreateService.scopeRequestOf` — `backend/src/main/java/com/tcms/execution/RunCreateService.java:266-288` | Builds the document from a `RunWriteRequest`, de-duplicated and trimmed |
| The reader | `RunScopeRequestView.parse` — `backend/src/main/java/com/tcms/execution/RunScopeRequestView.java:39-63` | Tolerant of a missing key and of a null; anything unreadable is *not recorded* rather than a 500 |
| The wire shape | the contract's `RunScopeRequest`, served on `TestRunDetailView` | Already generated into both `schema.d.ts` copies |

`run_schedule.scope_request` is **the same document, in the same four keys**, and the pipeline is a
loop:

```
RunScheduleWriteRequest ──▶ scope document (RunCreateService.scopeRequestOf's shape)
                             │  stored on run_schedule.scope_request  (NOT NULL)
                             ▼
                          RunScopeRequestView.parse   ← the existing reader, reused verbatim
                             │
                             ▼
                          RunWriteRequest  ──▶ RunCreateService  ──▶ test_run.scope_request
```

So a scheduled run's own `scope_request` ends up byte-equivalent to the schedule's, written by
`scopeRequestOf` from the reconstructed request. That is not a coincidence to be tidied away later —
it is what makes "why is TC-41 in this run" answerable for a run nobody asked for.

**Three differences from `test_run.scope_request`, each deliberate:**

- `run_schedule.scope_request` is **`NOT NULL`**. On `test_run` the column is nullable because
  ingestion materialises from a report and *there was no request* (`V61`'s header). A schedule that
  asked for nothing is a schedule that can only ever create empty runs, which `RunEdit` refuses
  anyway — refusing it at the schedule is refusing it once instead of every night.
- It is **edited**, where a run's is written once. That is the difference between a standing request
  and a record of one, and it is why the schedule carries `updated_at`/`lock_version`.
- It is **re-resolved on every fire**, by construction: the whole point of a schedule is that
  "everything tagged `smoke`" means *today's* smoke cases. Materialisation-once still holds — it
  holds per run, which is the level `V61`'s header states it at.

---

## `§242 LG-5` — this area is what makes it drivable

`docs/handoff/242-the-audit-log-reader.md:207` records the gap, and
`docs/verification/frontend-browser-checks.md:3103` is the row itself:

> `§242 LG-5` | ⚠️ **NOT DRIVABLE — recorded rather than skipped.** An act with `actorUserId` null
> renders as **"Automatic"** with no person and no token | **No producer exists.** Every write path
> in the product carries a caller … It becomes drivable when slice 5's scheduled runs land — a
> poller firing a run is the first act nobody requested.

Decision 2 above is what delivers it: the `run.created` audit row written on a scheduled fire carries
`actorUserId = null` and `actorTokenId = null`, so `AuditLogPage.tsx:281` renders **"Automatic"** for
the first time against a row the product actually wrote.

**Three things follow, and all three are this area's job:**

1. Task 15 asserts it in the backend — the row exists, both actor columns are null, and it reads
   correctly through `identity.AuditLogService`.
2. Task 22 **rewrites the `§242 LG-5` row in place**. The id is stable and quoted from a handoff
   section, so it is **never renumbered** — the row keeps its id and loses its ⚠️, gaining a
   `Before you start` naming the schedule the browser pass must create.
3. The handoff section says so explicitly, because the next reader of §242 will otherwise still
   believe the shape has no producer.

`AuditLogReadTest.anUnattendedJobHasNoPerson` already covers the *shape* with a hand-inserted row.
It stays: what changes is that the product now produces one.

---

## What already exists — do not rebuild it

| Piece | Where | What it gives you |
|---|---|---|
| Run creation, whole | `execution/RunCreateService.java` | Case-set resolution, version pinning, display-key allocation, the §6g approval gate, the checklist, the audit row — one transaction |
| Its second door | `RunCreateService#createIngested:229` | The precedent for a **package-private** entry that names its own `RunSource`. Its header says why ingestion did not grow a second creation path; the same argument covers this one |
| Idempotent create | `RunCreateService.java:298`, `TestRunRepository#findByIdempotencyKey` | `Outcome.Replayed` and no second checklist |
| The scope document | `RunCreateService#scopeRequestOf:266`, `RunScopeRequestView#parse:39` | Writer and reader, both reused |
| The permission gate | `ProjectService#writeGranted:253` | Visibility then permission, in that order, with `Unreachable` vs `Denied` kept distinct |
| The company loop | `repository/CaseHealthScheduler.java:73-87` | The poller shape, and its header carries the argument |
| Audit | `tenancy/AuditEvent#record:157` | `companyId` from context, `actorTokenId` from `TokenContext`, `actorUserId` a parameter that is **null for an unattended job** |
| The audit reader | `identity/AuditLogService`, `frontend/src/features/audit/AuditLogPage.tsx:281` | Renders a null actor as "Automatic" — shipped in §242 |
| Cron | `org.springframework.scheduling.support.CronExpression` | Six-field parse + `next(Temporal)`. Already on the classpath |
| Schema conventions | `backend/src/test/java/…/SchemaConventionTest.java` | `everyCompanyOwnedTableHasCompanyIdNotNull`, `companyIdLeadsCompositeIndexes`, `everyCompanyOwnedTableForcesRls`, `everyPolicyHasTheExactIsolationPredicate` — all four derive from the **live schema** and all four fail the build if `V68` gets any of it wrong |

---

## File structure

**Create (backend):**

```
backend/src/main/resources/db/migration/V68__runs_can_be_scheduled.sql
backend/src/main/java/com/tcms/execution/RunSchedule.java
backend/src/main/java/com/tcms/execution/RunScheduleRepository.java
backend/src/main/java/com/tcms/execution/CronSchedule.java
backend/src/main/java/com/tcms/execution/RunScheduleEdit.java
backend/src/main/java/com/tcms/execution/RunScheduleWriteRequest.java
backend/src/main/java/com/tcms/execution/RunScheduleView.java
backend/src/main/java/com/tcms/execution/RunScheduleService.java
backend/src/main/java/com/tcms/execution/RunScheduleController.java
backend/src/main/java/com/tcms/execution/RunScheduleFiringService.java
backend/src/main/java/com/tcms/execution/RunScheduleScheduler.java
```

**Modify (backend):**

```
backend/src/main/java/com/tcms/execution/RunSource.java          — SCHEDULED
backend/src/main/java/com/tcms/execution/RunCreateService.java   — createScheduled + the actor split
```

**Create (tests):**

```
backend/src/test/java/com/tcms/execution/CronScheduleTest.java
backend/src/test/java/com/tcms/execution/RunScheduleEndpointTest.java
backend/src/test/java/com/tcms/execution/RunScheduleFiringTest.java
```

**Modify (tests):** `backend/src/test/java/com/tcms/tenancy/AsyncCompanyContextTest.java`

**Contract:** `docs/specs/openapi.yaml` (fragments in Phase 3), then both generated copies —
`frontend/src/api/schema.d.ts` and `packages/client/src/schema.ts`.

**Create (frontend):** `frontend/src/features/runs/RunSchedulePage.tsx` and its `.test.tsx`.

**Modify (frontend):** `frontend/src/app/router.tsx` · `frontend/src/api/queries/runs.ts` ·
`frontend/src/lib/labels.ts` · `frontend/src/features/runs/RunListPage.tsx`

**Docs:** `docs/handoff/<next>-…md` + one index row in `docs/HANDOFF.md` ·
`docs/agent-context/be-java.md` · `docs/agent-context/fe-react.md` ·
`docs/verification/backend-mutation-checks.md` · `docs/verification/frontend-browser-checks.md`
(including the **rewrite of `§242 LG-5` in place**) · `docs/features.md`

---

# Phase 1 — the schema

**One migration. `V68`. Do not add a second.**

### Task 1: Write `V68__runs_can_be_scheduled.sql`

**Files:**
- Create: `backend/src/main/resources/db/migration/V68__runs_can_be_scheduled.sql`

- [ ] **Step 0: Load `data:data-migrations` and `data:data-postgresql`, and confirm the head**

```bash
ls backend/src/main/resources/db/migration | sort -V | tail -3
grep -rn "out-of-order\|outOfOrder" backend/src/main/resources/application*.yml
```

Expected: `V67…` is the highest, and `out-of-order` appears nowhere. If either is false, **stop and
re-read the master plan's Flyway paragraph** rather than picking a number.

- [ ] **Step 1: Confirm the CHECK constraint's real name before writing the swap**

The `source` CHECK is inline in `V1__initial_schema.sql:806`, so Postgres named it. Verify rather
than assume:

```bash
docker compose up -d db
docker compose exec db psql -U tcms_owner -d tcms -c '\d test_run' | grep -i check
```

Expected: `test_run_source_check`. If it differs, use the real name in Step 2 — a `DROP CONSTRAINT`
naming a constraint that does not exist fails the migration on first boot, in every environment at
once.

- [ ] **Step 2: Write the migration**

The header states what the table is *for*, as every migration in this directory does. The DDL:

```sql
-- =============================================================================
-- V68 — a run can be asked for on a cadence, not only by a person
--
-- Area C of docs/plans/2026-08-18-unplanned-gaps.md.
--
-- WHAT IS MISSING TODAY
--
-- A run is created by somebody clicking Create, or by CI posting a report.
-- A weekly regression cycle is therefore a recurring calendar entry in somebody
-- else's tool, and the run that should have happened on a Monday when that
-- person was on leave simply did not.
--
-- WHAT THIS TABLE IS
--
-- A STANDING REQUEST. It stores what a run should be ASKED for and when to ask,
-- and a poller re-asks it. It stores no results, no executions and no state
-- about a run in flight — the run it creates is an ordinary test_run and
-- everything downstream of creation is unchanged.
--
-- scope_request IS V61'S DOCUMENT, NOT A SECOND ENCODING
--
-- The same {planId, caseIds, suiteIds, tags} JSONB that V61 added to test_run,
-- written by the same code (RunCreateService.scopeRequestOf) and read by the
-- same code (RunScopeRequestView.parse). V61's header carries the argument for
-- JSONB over four columns or a join table and it applies unchanged: the request
-- is read as a whole and never joined against.
--
-- Three differences from V61's column, each deliberate:
--   * NOT NULL here. V61's is nullable because an INGESTED run had no request at
--     all. A schedule with no request could only ever create empty runs, which
--     RunEdit refuses anyway — refusing it once, here, beats refusing it nightly.
--   * It is EDITED. A run's request is written once; a standing request is
--     maintained. Hence updated_at and lock_version.
--   * It is RE-RESOLVED on every fire. "Everything tagged smoke" means today's
--     smoke cases. Materialisation-once is unharmed: it is a property of each
--     run, which is the level V61 states it at.
--
-- WHY next_fire_at IS STORED
--
-- The poller's query is one index range scan per company. The alternative reads
-- every schedule in the company on every tick and evaluates its cron in Java, to
-- discard nearly all of it — and makes one project's cost a function of how many
-- schedules every other project has. Stored state must be repaired, so exactly
-- one method writes it (RunScheduleService#recomputeNextFire) and create,
-- update, enable/disable and fire all go through it.
--
-- NO CATCH-UP, and the column shape says so: next_fire_at is recomputed FROM THE
-- MOMENT OF FIRING. A deployment down for a day does not produce a day of
-- backdated regression runs on restart — seven identical runs appearing at once,
-- each materialised against today's case versions, none of them meaning what its
-- timestamp says. The honest record of the gap is last_fired_at.
--
-- NO deleted_at. A schedule is configuration, not a record of work: deleting one
-- destroys nothing that happened, because the runs it created are ordinary runs
-- that outlive it. The audit trail keeps the delete.
--
-- created_by IS NOT NULL AND IT IS LOAD-BEARING
--
-- It is the authority the fire runs under. RunScheduleFiringService re-evaluates
-- that person's run:create grant on EVERY fire through the ordinary
-- ProjectService.writeGranted gate, so a schedule outlives neither the grant nor
-- the account. A nullable owner would be a standing, unrevokable right to create
-- runs in a project.
--
-- It is NOT the actor on what gets written. The run row and the audit row a fire
-- produces both carry a null actor, because at 03:00 nobody performed the act —
-- see AuditEvent.record's own @param ("null only for an unattended job") and the
-- actor_token_id header's refusal to say a human did what a pipeline did.
--
-- test_run.source GAINS A THIRD MEMBER
--
-- The CHECK below is replaced rather than left alone. TestRun#create's header
-- reads source as THE DOOR the request came through; a poller is a third door.
-- Leaving it at MANUAL is not neutral: RunListPage renders anything that is not
-- INGESTED as "manual", so every scheduled run would be labelled as a person's
-- act beside a created_by of nobody.
--
-- company_id IS FIRST IN BOTH INDEXES, per ERD §0.1 point 3 and
-- SchemaConventionTest.companyIdLeadsCompositeIndexes. run_schedule is
-- company-owned and is NOT on §0.1's RLS exception list, so it takes
-- FORCE ROW LEVEL SECURITY and V4's nullif() predicate, exactly as written —
-- SchemaConventionTest.everyPolicyHasTheExactIsolationPredicate compares the
-- text.
-- =============================================================================

CREATE TABLE run_schedule (
    id               UUID PRIMARY KEY,
    company_id       UUID NOT NULL REFERENCES company(id),
    project_id       UUID NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    name             TEXT NOT NULL,
    cron_expression  TEXT NOT NULL,
    zone_id          TEXT NOT NULL,
    scope_request    JSONB NOT NULL,
    configuration_id UUID REFERENCES configuration(id),
    assignee_id      UUID,
    enabled          BOOLEAN NOT NULL DEFAULT TRUE,
    next_fire_at     TIMESTAMPTZ,
    last_fired_at    TIMESTAMPTZ,
    last_run_id      UUID REFERENCES test_run(id),
    last_fire_error  TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by       UUID NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by       UUID,
    lock_version     BIGINT NOT NULL DEFAULT 0
);

COMMENT ON TABLE run_schedule IS
    'A STANDING REQUEST for a run — what to ask for and when. The poller re-asks it and creates an '
    'ordinary test_run; nothing about execution changes. Deleting one destroys nothing that '
    'happened, because the runs it made outlive it.';

COMMENT ON COLUMN run_schedule.scope_request IS
    'V61''s document, unchanged — {planId, caseIds, suiteIds, tags}, normalised. NOT NULL here '
    'because a schedule that asked for nothing could only ever make empty runs. RE-RESOLVED on '
    'every fire: "everything tagged smoke" means today''s smoke cases. Materialisation-once is '
    'untouched — it is a property of each run.';

COMMENT ON COLUMN run_schedule.zone_id IS
    'An IANA zone, validated with ZoneId.of at write time. Stored rather than defaulted in code '
    'because "every weekday at 07:00" is not a time without one, and a DST shift with nothing on '
    'the screen to explain it is the failure this column prevents.';

COMMENT ON COLUMN run_schedule.next_fire_at IS
    'When the poller will next ask. Recomputed FROM THE MOMENT OF FIRING, never from a missed slot '
    '— there is no catch-up, by decision. NULL means the expression yields nothing further, which '
    'CronExpression can legitimately answer.';

COMMENT ON COLUMN run_schedule.created_by IS
    'The AUTHORITY, re-evaluated on every fire: the poller runs the create through this person''s '
    'run:create grant, so a schedule outlives neither the grant nor the account. It is NOT the '
    'actor on what gets written — a fire''s run row and audit row both carry a null actor, because '
    'nobody performed the act.';

COMMENT ON COLUMN run_schedule.last_fire_error IS
    'Why the last attempt produced no run — the owner lost the grant, the project was archived, the '
    'resolved scope was empty, the approval gate refused. NULL after a fire that made a run. Read '
    'by the settings screen: a schedule that silently stops is the failure mode of this whole '
    'feature.';

CREATE INDEX run_schedule_project_idx ON run_schedule (company_id, project_id);

-- The poller's whole query. Partial on `enabled` because a disabled schedule is
-- never due, and it is the one predicate that never varies.
CREATE INDEX run_schedule_due_idx ON run_schedule (company_id, next_fire_at) WHERE enabled;

-- -----------------------------------------------------------------------------
-- RLS
-- -----------------------------------------------------------------------------

ALTER TABLE run_schedule ENABLE ROW LEVEL SECURITY;
ALTER TABLE run_schedule FORCE ROW LEVEL SECURITY;

CREATE POLICY company_isolation ON run_schedule USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);

-- -----------------------------------------------------------------------------
-- test_run.source gains SCHEDULED — see the header
-- -----------------------------------------------------------------------------

ALTER TABLE test_run DROP CONSTRAINT test_run_source_check;
ALTER TABLE test_run ADD CONSTRAINT test_run_source_check
    CHECK (source IN ('MANUAL', 'INGESTED', 'SCHEDULED'));

COMMENT ON COLUMN test_run.source IS
    'The DOOR the request came through, never the caller''s role — MANUAL (a person, including an '
    'Automation Service token through the public endpoint), INGESTED (a posted report), SCHEDULED '
    '(the poller, V68). A SCHEDULED run has a null created_by and a null actor on its audit row.';
```

**No `CREATE INDEX CONCURRENTLY`** — `V17`, `V42`, `V59` and `V62` all refuse it on record, Flyway
runs a migration in one transaction, and this table is created empty in the same migration.

- [ ] **Step 3: Apply it against a real database and read the result back**

```bash
docker compose down -v && docker compose up --build -d
docker compose logs backend | grep -i "flyway\|migrat" | tail -20
docker compose exec db psql -U tcms_owner -d tcms -c '\d run_schedule'
docker compose exec db psql -U tcms_owner -d tcms \
  -c "SELECT relname, relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname='run_schedule';"
```

Expected: `V68` applied, both booleans `t`, both indexes present with `company_id` leading.

### Task 2: Prove the schema conventions still hold

**Files:** none — this is a gate, run before any Java is written

- [ ] **Step 1: Run the convention suite**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.*.SchemaConventionTest'
```

Expected: PASS. Four of its arms read the live schema, so this is where a missing `company_id`, a
non-leading index, a missing `FORCE`, or a policy predicate that is a paraphrase rather than V4's
exact text shows up. **Fix it here** — every later failure will be attributed to something else.

- [ ] **Step 2: Commit the migration alone**

```bash
git add backend/src/main/resources/db/migration/V68__runs_can_be_scheduled.sql
git commit -m "feat: a run can be asked for on a cadence"
```

---

# Phase 2 — the entity, the repository, and the one cron parser

### Task 3: `RunSchedule` and `RunScheduleRepository`

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/RunSchedule.java`
- Create: `backend/src/main/java/com/tcms/execution/RunScheduleRepository.java`

- [ ] **Step 1: Load `be-java:be-java-database-code`, then write the entity**

Model it on `TestRun` — `@Filter(name = CompanyFilter.NAME, …)`, `companyId` from
`CompanyContext.require()` in a static factory, no public setters for anything the service should
not move, `@Version` on `lockVersion`.

```java
package com.tcms.execution;

/**
 * A standing request for a run — what to ask for, and when.
 *
 * <h2>It is a request, not a run</h2>
 *
 * <p>Nothing here records work. The run a fire produces is an ordinary {@code test_run}, created
 * through the one door that has ever created one, and deleting this row destroys nothing that
 * happened.
 *
 * <h2>{@link #createdBy} is authority, not attribution</h2>
 *
 * <p>Every fire re-evaluates that person's {@code run:create} grant through
 * {@code ProjectService.writeGranted}, so a schedule outlives neither the grant nor the account —
 * and it is <strong>not</strong> the actor stamped on what gets written. A fire's run row and audit
 * row both carry null, because nobody performed the act at 03:00. See {@code AuditEvent#record}'s
 * own parameter doc.
 *
 * <h2>{@link #scopeRequest} is {@code V61}'s document and not a second encoding</h2>
 *
 * <p>Written by {@code RunCreateService#scopeRequestOf} and read by {@code RunScopeRequestView#parse},
 * both reused verbatim. It is NOT NULL here and re-resolved on every fire — a tag arm means today's
 * cases, which is the whole point of a schedule and takes nothing away from materialisation-once,
 * a property of each run.
 */
@Entity
@Table(name = "run_schedule")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
class RunSchedule { … }
```

Mapping notes that are easy to get wrong:

- `scopeRequest` is `String` with `@JdbcTypeCode(SqlTypes.JSON)` — **not** `columnDefinition`. See
  `InstanceAuditEvent#getChanges()` for the trap that distinction avoids and why it only shows up on
  the first insert.
- `zoneId` is stored as `String`, converted at the edges by `CronSchedule`. A `ZoneId` attribute
  converter would be a third place a bad zone can enter.
- No `deletedAt`. Delete is a real `DELETE`.

- [ ] **Step 2: Write the repository — and put `@Transactional(readOnly = true)` on the interface**

```java
/** Schedules, by project and by dueness. {@code @Transactional} per {@link TestPlanRepository}. */
@Transactional(readOnly = true)
interface RunScheduleRepository extends JpaRepository<RunSchedule, UUID> {

    @Query("SELECT s FROM RunSchedule s WHERE s.projectId = :projectId ORDER BY s.name ASC, s.id ASC")
    List<RunSchedule> findInProject(@Param("projectId") UUID projectId);

    @Query("SELECT s FROM RunSchedule s WHERE s.projectId = :projectId AND s.id = :scheduleId")
    Optional<RunSchedule> findInProject(
            @Param("projectId") UUID projectId, @Param("scheduleId") UUID scheduleId);

    /**
     * Every due schedule in the company the context is open on.
     *
     * <p>Matches {@code run_schedule_due_idx} exactly, including the {@code enabled} predicate the
     * index is partial on. There is deliberately no cross-company variant: {@code run_schedule} is
     * under FORCE RLS, so a query for every due row in the instance returns <strong>zero rows and
     * logs a successful tick</strong> — see {@code RunScheduleScheduler}'s header.
     */
    @Query("SELECT s FROM RunSchedule s WHERE s.enabled = TRUE AND s.nextFireAt <= :now "
            + "ORDER BY s.nextFireAt ASC, s.id ASC")
    List<RunSchedule> findDue(@Param("now") Instant now);
}
```

**Without `@Transactional(readOnly = true)` every derived query returns zero rows under RLS** and
`RepositoryTransactionTest` fails the build. This is not a style rule.

The secondary sort key on both list queries is not decoration: without it, two schedules with the
same name or the same `nextFireAt` swap places between reads and the SPA's list flickers.

- [ ] **Step 3: Compile**

```bash
~/bin/tcms-remote backend ./gradlew compileJava
```

`hibernate.ddl-auto: validate` means the context will refuse to boot if the mapping and `V68`
disagree, so the first test run is the real check — but compile first.

### Task 4: `CronSchedule` — the only place an expression is parsed

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/CronSchedule.java`

- [ ] **Step 1: Write it**

```java
package com.tcms.execution;

/**
 * The one place a cron expression and a zone become a time.
 *
 * <p><strong>One parser, called by both the write path and the poller.</strong> Scattering parsing
 * across a validator and a poller is how a schedule gets accepted with an expression the poller
 * later reads differently — and the visible symptom of that is not an error, it is a schedule that
 * never fires and never says why.
 *
 * <p>Spring's six-field {@link org.springframework.scheduling.support.CronExpression} rather than a
 * new dependency: it is already on the classpath through {@code spring-context}, {@code @Scheduled}
 * uses it, and a second cron dialect in one application is two answers to "what does {@code 0 0 7 *
 * * MON-FRI} mean".
 *
 * <p>{@link #nextAfter} may answer <strong>empty</strong>, and that is a real answer rather than a
 * failure: an expression pinned to a date that has passed yields nothing further. The column is
 * nullable for it and the screen says "will not fire again".
 */
final class CronSchedule {

    /** Longest expression accepted — a cron field set is short, and this is not a script. */
    static final int MAX_EXPRESSION = 100;

    static Optional<CronSchedule> parse(String expression, String zoneId) { … }

    Optional<Instant> nextAfter(Instant moment) { … }
}
```

Rules to implement, each of which is a test in Task 5:

- An unparseable expression, a blank one, or one over `MAX_EXPRESSION` → empty. The caller turns
  that into a **400 field error**, never a stored row.
- An unknown zone (`ZoneId.of` throws `DateTimeException`) → empty, keyed to `zoneId`.
- `nextAfter` converts to the schedule's zone, asks `CronExpression.next`, converts back to
  `Instant`. **Do the arithmetic in the zone, not in UTC** — the entire reason the zone is stored is
  that "07:00 on a weekday" is a wall-clock statement.

- [ ] **Step 2: Compile**

### Task 5: `CronScheduleTest` — a plain unit test, no Spring, no container

**Files:**
- Create: `backend/src/test/java/com/tcms/execution/CronScheduleTest.java`

- [ ] **Step 1: Write it first and watch it fail**

This is the one class in the area that needs no database, so it is the one place the DST arithmetic
can be pinned cheaply. Load `be-java:be-java-unit-testing`.

```java
/**
 * The cron parser, in isolation.
 *
 * <p>Here rather than inside the endpoint test because the failures are arithmetic, not
 * integration: a Testcontainers boot per DST case would cost minutes to assert something that has
 * no database in it.
 */
class CronScheduleTest {

    @Test
    void aWeekdayMorningIsWallClockInTheSchedulesZone() {
        // "0 0 7 * * MON-FRI", Europe/Sofia. Assert the next fire from a January instant and from a
        // July instant land on 07:00 LOCAL — i.e. two DIFFERENT UTC offsets. The defect this
        // catches is doing the arithmetic in UTC, which is right for half the year and silently an
        // hour out for the other half.
    }

    @Test
    void anUnparseableExpressionIsRefusedRatherThanStored() { }

    @Test
    void anUnknownZoneIsRefused() { }

    @Test
    void anExpressionWithNoFutureOccurrenceAnswersEmpty() {
        // A date-pinned expression already in the past. Empty is a real answer: the column is
        // nullable for it and the screen says "will not fire again".
    }

    @Test
    void anExpressionLongerThanTheCapIsRefusedBeforeItIsParsed() { }
}
```

- [ ] **Step 2: Run it green on shanks**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.execution.CronScheduleTest'
```

- [ ] **Step 3: Commit the persistence layer and the parser**

```bash
git add backend/src/main/java/com/tcms/execution/RunSchedule.java \
        backend/src/main/java/com/tcms/execution/RunScheduleRepository.java \
        backend/src/main/java/com/tcms/execution/CronSchedule.java \
        backend/src/test/java/com/tcms/execution/CronScheduleTest.java
git commit -m "feat: a schedule row, and the one place a cron expression is read"
```

---

# Phase 3 — the contract

**Another lane holds `docs/specs/openapi.yaml`.** Apply these fragments when the file is free, in one
edit, and regenerate both copies once afterwards.

### Task 6: Add the five operations and the three schemas

**Files:**
- Modify: `docs/specs/openapi.yaml`

- [ ] **Step 1: Add the two paths**

Beside the run paths, so the two read as one family.

```yaml
  /projects/{projectId}/run-schedules:
    parameters:
      - $ref: '#/components/parameters/ProjectId'
    get:
      summary: The project's run schedules
      description: |
        Standing requests for a run. Ordered by name.

        Visible to anybody who can see the project — the same gate the run list uses. **Creating,
        editing and deleting one needs `run:create`**, because a schedule is delegated run creation:
        a caller who may create a run now may create one every Monday. No new permission was added
        for this; the catalogue is unchanged at 55 keys.
      operationId: listRunSchedules
      tags: [Runs]
      responses:
        '200':
          description: The schedules.
          content:
            application/json:
              schema:
                type: object
                required: [items]
                properties:
                  items:
                    type: array
                    items: { $ref: '#/components/schemas/RunSchedule' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
    post:
      summary: Create a run schedule
      operationId: createRunSchedule
      tags: [Runs]
      parameters:
        - $ref: '#/components/parameters/IdempotencyKey'
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/RunScheduleInput' }
      responses:
        '201':
          description: Created.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RunSchedule' }
        '400': { $ref: '#/components/responses/ValidationProblem' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }

  /projects/{projectId}/run-schedules/{scheduleId}:
    parameters:
      - $ref: '#/components/parameters/ProjectId'
      - name: scheduleId
        in: path
        required: true
        schema: { type: string, format: uuid }
    get:
      summary: One run schedule
      operationId: getRunSchedule
      tags: [Runs]
      responses:
        '200':
          description: The schedule.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RunSchedule' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
    put:
      summary: Replace a run schedule
      description: |
        A total statement of the schedule, not a patch of it — an omitted `assigneeId` or
        `configurationId` **clears** it, on the same terms as the rest of this API's writes.
      operationId: updateRunSchedule
      tags: [Runs]
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/RunScheduleInput' }
      responses:
        '200':
          description: Updated.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/RunSchedule' }
        '400': { $ref: '#/components/responses/ValidationProblem' }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
        '409': { $ref: '#/components/responses/Conflict' }
    delete:
      summary: Delete a run schedule
      description: |
        Deletes the standing request only. **The runs it created are ordinary runs and are
        untouched** — a schedule records no work, so there is nothing here to preserve.
      operationId: deleteRunSchedule
      tags: [Runs]
      responses:
        '204': { description: Deleted. }
        '403': { $ref: '#/components/responses/Forbidden' }
        '404': { $ref: '#/components/responses/NotFound' }
```

- [ ] **Step 2: Add the two schemas — with their `required` arrays, including on the nested object**

`required` does not descend. `scope` is an inline object and needs its own.

```yaml
    RunScheduleInput:
      type: object
      required: [name, cronExpression, zoneId, scope, enabled]
      properties:
        name:
          type: string
          maxLength: 200
          description: |
            The schedule's label, and the **prefix of every run it creates** — a run is named
            `"<name> — <date>"` in the schedule's own zone, so a year of them sorts and reads as one
            series. One field rather than two because a separate run-name template is a second thing
            to keep in step with the first, for no question it answers.
        cronExpression:
          type: string
          maxLength: 100
          description: |
            A **six-field Spring cron expression** — `second minute hour day-of-month month
            day-of-week`, e.g. `0 0 7 * * MON-FRI`. Parsed and validated on write: an expression
            this server cannot read is a `400`, never a stored schedule that silently never fires.
        zoneId:
          type: string
          description: |
            An IANA zone id, e.g. `Europe/Sofia`. Required rather than defaulted, because
            "every weekday at 07:00" is not a time without one — the arithmetic is done in this
            zone, so a run lands at 07:00 local across a DST boundary rather than drifting an hour.
        scope:
          type: object
          required: [caseIds, suiteIds, tags]
          description: |
            What to ask for, in exactly the four arms `POST /runs` takes and
            `TestRun.scopeRequest` records — this is the same document, not a second encoding of it.

            **It is re-resolved on every fire.** A `tags` arm means the cases carrying that tag
            *that night*, which is the point of a schedule. Each run it creates is still materialised
            once and pinned to the case versions live at its own creation.
          properties:
            planId:
              type: string
              format: uuid
              nullable: true
            caseIds:
              type: array
              items: { type: string, format: uuid }
            suiteIds:
              type: array
              items: { type: string, format: uuid }
            tags:
              type: array
              items: { type: string, maxLength: 60 }
        configurationId:
          type: string
          format: uuid
          nullable: true
          description: Omitted means the project's default, exactly as on `POST /runs`.
        assigneeId:
          type: string
          format: uuid
          nullable: true
          description: |
            Everybody on the created run's checklist. Optional, and a schedule with nobody on it is
            accepted — but a nightly run nobody is assigned to is a run nobody looks at, which is why
            area B (bulk assignment) lands before this one.
        enabled:
          type: boolean
          description: |
            A disabled schedule keeps its next fire time and simply is not asked. Pausing a cadence
            and deleting it are different acts, and only one of them is recoverable.
    RunSchedule:
      type: object
      required:
        [id, projectId, name, cronExpression, zoneId, scope, enabled, createdAt, createdBy]
      properties:
        id: { type: string, format: uuid }
        projectId: { type: string, format: uuid }
        name: { type: string }
        cronExpression: { type: string }
        zoneId: { type: string }
        scope: { $ref: '#/components/schemas/RunScopeRequest' }
        configurationId: { type: string, format: uuid, nullable: true }
        assigneeId: { type: string, format: uuid, nullable: true }
        assigneeName: { type: string, nullable: true }
        enabled: { type: boolean }
        nextFireAt:
          type: string
          format: date-time
          nullable: true
          description: |
            When it will next be asked. `null` means the expression yields nothing further — a real
            answer, not an error, and the screen says "will not fire again".
        lastFiredAt: { type: string, format: date-time, nullable: true }
        lastRunId: { type: string, format: uuid, nullable: true }
        lastRunKey: { type: string, nullable: true }
        lastFireError:
          type: string
          nullable: true
          description: |
            Why the last attempt produced no run — the owner no longer holds `run:create`, the
            project was archived, the resolved scope was empty, the approval gate refused. `null`
            after an attempt that made a run.

            **This field is the feature's honesty.** A schedule that stops silently is the failure
            mode of scheduling anything, and there is no other place a reader could find out.
        createdAt: { type: string, format: date-time }
        createdBy: { type: string, format: uuid }
        createdByName: { type: string, nullable: true }
```

Reuse the existing `RunScopeRequest` schema on the read side rather than declaring a second shape —
`grep -n "RunScopeRequest:" docs/specs/openapi.yaml` to find it. If the input's inline `scope` and
that schema turn out to be structurally identical, `$ref` the input at it too and delete the inline
copy.

- [ ] **Step 3: Add `SCHEDULED` to the two `source` enums**

Both sites — `openapi.yaml:10621` and `openapi.yaml:11129` at the time of writing; **re-grep, do not
trust the line numbers**:

```bash
grep -n "enum: \[MANUAL, INGESTED\]" docs/specs/openapi.yaml
```

```yaml
        source:
          type: string
          enum: [MANUAL, INGESTED, SCHEDULED]
          description: |
            The **door** the run came through, never the caller's role. `MANUAL` — a person, or an
            Automation Service token through the public endpoint. `INGESTED` — a posted report.
            `SCHEDULED` — a `run_schedule` fired, which is the one value whose run has **no
            `createdBy` and a null actor on its audit row**, because nobody performed the act.
```

- [ ] **Step 4: Regenerate both copies and confirm they agree**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
diff ../../frontend/src/api/schema.d.ts src/schema.ts && echo IDENTICAL
```

Expected: `IDENTICAL`. Anything else fails `schemaFreshness.test.ts` — fix it now, and **never
hand-merge generated output**.

- [ ] **Step 5: Commit the contract on its own**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts
git commit -m "contract: runs can be asked for on a schedule"
```

---

# Phase 4 — validation, the service, the controller

### Task 7: `RunScheduleEdit` — validation, in the module's established shape

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/RunScheduleEdit.java`
- Create: `backend/src/main/java/com/tcms/execution/RunScheduleWriteRequest.java`

- [ ] **Step 1: Write the request record, mirroring `RunWriteRequest`**

```java
record RunScheduleWriteRequest(
        String name,
        String cronExpression,
        String zoneId,
        UUID planId,
        List<UUID> caseIds,
        List<UUID> suiteIds,
        List<String> tags,
        UUID configurationId,
        UUID assigneeId,
        Boolean enabled) {
}
```

Flat rather than nested even though the wire nests `scope`, because everything downstream — the
normalisation, `RunEdit.tagsOf`, `scopeRequestOf` — already takes the four arms flat. The controller
flattens once.

- [ ] **Step 2: Write the validator as a sealed `Outcome`, per `RunEdit`**

Field errors are **collected, not short-circuited** — the same rule `RunEdit` and `ExecutionService`
follow, and for the same reason: somebody filling in a form should not be sent round the loop three
times.

| Field | Rule | Why it is here and not later |
|---|---|---|
| `name` | non-blank, ≤ 200 (`RunEdit.MAX_NAME`) | It is the run's name prefix |
| `cronExpression` | parses via `CronSchedule`, ≤ 100 | An unreadable expression is a schedule that never fires and never says why |
| `zoneId` | `ZoneId.of` accepts it | Same |
| `tags` | each ≤ `RunEdit.MAX_TAG` (60) | A scope entry longer than a tag can be is one that can never match anything — `RunEdit.MAX_TAG`'s own header |
| the scope | **at least one arm present** | `V68`'s `NOT NULL` in the honest place. A schedule that asked for nothing can only ever create empty runs |
| `assigneeId` | `ExecutionPeople#isActiveMember(companyId, …)`, **not** `resolve` | `app_user` is instance-level (ERD §0.1's exception list), so `resolve` finds any account on the instance and would accept a user from another client entirely. **This distinction has already cost a red test once** — see `RunCreateService.java:340`'s comment |
| `configurationId` | in this project | Same predicate `RunCreateService#resolveConfiguration` applies |

**A `caseId`, `suiteId` or `planId` naming nothing live is NOT a field error here**, and this is the
one deliberate divergence from `RunEdit`. A schedule is a standing request evaluated over months; a
suite it names may be deleted next quarter, and refusing the *edit* at that point would make the
schedule uneditable because of something the editor did not do. The emptiness is caught where it
matters — **at fire time**, as `last_fire_error`, which is the field that exists for exactly this.
Record this divergence in the header; it will otherwise read as an oversight.

### Task 8: `RunScheduleService` — the five operations, and the one writer of `nextFireAt`

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/RunScheduleService.java`
- Create: `backend/src/main/java/com/tcms/execution/RunScheduleView.java`

- [ ] **Step 1: Write the view**

`RunScheduleView` carries the contract's `RunSchedule` exactly, including `assigneeName`,
`createdByName` and `lastRunKey`. Resolve the two names through `ExecutionPeople` and the run key
through `TestRunRepository` in **one query for the whole list**, not per row — `TestRunRepository`
already has `findPlanKeys` as the precedent for that shape.

- [ ] **Step 2: Write the service**

Every write goes through `projects.writeGranted(userId, projectId, CREATE_RUN, …)`; every read
through `projects.readGranted(...)` with visibility only. The permission is `RunCreateService`'s
existing `CREATE_RUN` constant — **import it, do not spell the string again.**

```java
/**
 * CRUD over standing run requests.
 *
 * <h2>{@code run:create} and no new key</h2>
 *
 * <p>A schedule is delegated run creation: a caller who may create a run now may create one every
 * Monday. A {@code schedule:manage} key would need six seeded grants — a second migration this plan
 * does not have — plus the contract enum, {@code lib/permissions.ts} and two matrix copies that
 * {@code matrixCopies.test.ts} compares cell by cell in both directions, for a distinction the
 * product does not draw.
 *
 * <h2>{@link #recomputeNextFire} is the only writer of {@code nextFireAt}</h2>
 *
 * <p>Create, update, enable/disable and fire all call it. It is stored state derived from two other
 * columns, and the way stored derived state goes wrong is a second place that writes it.
 */
```

Method shape:

| Method | Gate | Notes |
|---|---|---|
| `list(userId, projectId)` | `readGranted` (visibility) | Names resolved in bulk |
| `read(userId, projectId, scheduleId)` | `readGranted` | |
| `create(userId, projectId, request, idempotencyKey)` | `writeGranted` + `CREATE_RUN` | Validate → build the scope document with **`RunCreateService`'s own `scopeRequestOf` normalisation** → `recomputeNextFire` → save → `audit run_schedule.created` |
| `update(userId, projectId, scheduleId, request)` | `writeGranted` + `CREATE_RUN` | Total replacement. `recomputeNextFire` after, because the expression or zone may have moved. `@Version` gives the 409 |
| `delete(userId, projectId, scheduleId)` | `writeGranted` + `CREATE_RUN` | Real delete + `audit run_schedule.deleted` |

- [ ] **Step 3: Make `scopeRequestOf` reachable without duplicating it**

It is `private` on `RunCreateService` (`:266`). Widen it to package-private `static` and take the
`ObjectMapper` as a parameter, or move it to a small package-private helper both call. **Do not
copy the eight lines** — the whole reason `run_schedule.scope_request` is `V61`'s document is that
one piece of code writes that shape.

### Task 9: `RunScheduleController`

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/RunScheduleController.java`

- [ ] **Step 1: Write it, on `RunController`'s pattern**

`@RequestMapping("/api/v1/projects/{projectId}/run-schedules")`, an exhaustive `switch` over the
sealed `Outcome`, `Unreachable → 404`, `Denied → 403` with the same problem body `RunController`
builds, `Invalid → 400` with the field map. Flatten the wire's nested `scope` into
`RunScheduleWriteRequest`'s four arms here and nowhere else.

- [ ] **Step 2: Compile and boot**

```bash
~/bin/tcms-remote backend ./gradlew compileJava compileTestJava
```

### Task 10: `RunScheduleEndpointTest` — the CRUD surface over HTTP

**Files:**
- Create: `backend/src/test/java/com/tcms/execution/RunScheduleEndpointTest.java`

- [ ] **Step 1: Write it, using the `e7` prefix throughout**

Model it on `RunCreateEndpointTest` — `@AutoConfigureMockMvc`, `IntegrationTestBase`, two companies
seeded so the isolation arms are real.

```java
/**
 * {@code /projects/{projectId}/run-schedules} over HTTP.
 *
 * <p>Ids are unique to this class — {@code e7…}, pre-assigned 2026-08-22 by area C of
 * {@code docs/plans/2026-08-18-unplanned-gaps.md}.
 */
@AutoConfigureMockMvc
class RunScheduleEndpointTest extends IntegrationTestBase {

    private static final UUID ACME  = UUID.fromString("e7000000-0000-4000-8000-00000000000a");
    private static final UUID UMBRA = UUID.fromString("e7000000-0000-4000-8000-00000000000b");
    private static final UUID ACME_BANKING = UUID.fromString("e7001000-0000-4000-8000-000000000001");
    private static final UUID UMBRA_BANKING = UUID.fromString("e7002000-0000-4000-8000-000000000001");
    private static final UUID LEAD     = UUID.fromString("e7003000-0000-4000-8000-000000000001");
    private static final UUID VIEWER   = UUID.fromString("e7003000-0000-4000-8000-000000000003");
    …
}
```

Arms:

- [ ] A lead creates a schedule; the response carries `nextFireAt` in the future and the scope
      round-trips in all four arms.
- [ ] **A Viewer is refused with 403** — Viewer is the only role that does not hold `run:create`
      (roles spec §A3), which is the same line `RunCreateEndpointTest` leans on.
- [ ] An unparseable `cronExpression` is a **400 keyed to `cronExpression`**, and **nothing is
      stored** — assert the count, not just the status.
- [ ] An unknown `zoneId` is a 400 keyed to `zoneId`.
- [ ] A scope with all four arms empty is a 400.
- [ ] An `assigneeId` naming a member of **the other company** is a 400, not a stored row. This is
      the `isActiveMember`-versus-`resolve` distinction and it is worth its own arm.
- [ ] A `PUT` replaces totally: a schedule created with an assignee and updated without one comes
      back with `assigneeId` null.
- [ ] `GET` of a schedule id belonging to **the other company's project** is **404, not 403** —
      `writeGranted`'s ordering rule, so an id cannot be used as an existence oracle.
- [ ] `DELETE` removes the schedule and **leaves any run it created alone** — create a run manually,
      point `last_run_id` at it, delete, assert the run is still live.

- [ ] **Step 2: Run it, then the full gate**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.execution.RunScheduleEndpointTest'
~/bin/tcms-remote backend ./gradlew build
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/tcms/execution backend/src/test/java/com/tcms/execution
git commit -m "feat: run schedules are created, edited and deleted"
```

---

# Phase 5 — the firing path

This is the half of the area that is genuinely new behaviour. Everything above is CRUD.

### Task 11: Add `SCHEDULED` to `RunSource`

**Files:**
- Modify: `backend/src/main/java/com/tcms/execution/RunSource.java`

- [ ] **Step 1: Add the member and extend the header**

```java
public enum RunSource {

    MANUAL,
    INGESTED,

    /**
     * A {@code run_schedule} fired — {@code V68}.
     *
     * <p>The third door, and the one whose runs have <strong>no creator</strong>: {@code created_by}
     * is null and so is the actor on the audit row, because nobody performed the act. The schedule's
     * owner is the <em>authority</em> the create ran under, re-checked on every fire, and that is a
     * different question with a different column.
     */
    SCHEDULED
}
```

- [ ] **Step 2: Find every exhaustive switch this breaks**

```bash
~/bin/tcms-remote backend ./gradlew compileJava
grep -rn "RunSource\." backend/src/main/java backend/src/test/java
```

Java compiles with `-Werror`, so an exhaustive `switch` over the enum is a **compile error** until it
handles the new member. That is the point of adding it to the enum rather than treating it as a
string.

### Task 12: The second door into `RunCreateService`, and the actor split

**Files:**
- Modify: `backend/src/main/java/com/tcms/execution/RunCreateService.java`

- [ ] **Step 1: Add `createScheduled`, beside `createIngested`**

```java
    /**
     * The same create, for the run a schedule fires — {@code RunScheduleFiringService}'s only way to
     * make one.
     *
     * <p><strong>This exists so that scheduling does not grow a second run-creation path</strong>,
     * which is {@link #createIngested}'s argument and it is unchanged: materialising a run resolves
     * a case set, resolves each case's current version, allocates a display key, applies §6g's
     * approval gate, writes an execution per case and audits the whole thing as one transaction. A
     * copy of that in a poller would be a second implementation of the pin.
     *
     * <p>{@code ownerId} is the schedule's {@code created_by} and it is an <strong>authority, not an
     * actor</strong>. It is what {@code run:create} is asked of — so a schedule outlives neither the
     * grant nor the account — and it is deliberately <em>not</em> what gets stamped on the run or on
     * the audit row. See the actor split one method down.
     *
     * <p>Package-private, and {@code source} is not a parameter a caller chooses from a request
     * field: there are exactly three doors and each names its own.
     */
    @Transactional
    Outcome createScheduled(
            UUID ownerId, UUID projectId, RunWriteRequest request, String idempotencyKey) {

        return create(ownerId, projectId, request, idempotencyKey, RunSource.SCHEDULED);
    }
```

- [ ] **Step 2: Split authority from attribution inside `apply`**

At the top of `apply`, beside `UUID companyId = CompanyContext.require();`:

```java
        // AUTHORITY vs ATTRIBUTION, and they are the same value on two of the three doors.
        //
        // `userId` is who the request is GATED as — for a SCHEDULED run that is the schedule's
        // owner, re-asked on every fire so a schedule cannot outlive the grant that created it.
        //
        // `actor` is who DID it, and for a SCHEDULED run that is nobody: at 03:00 no person acted.
        // Stamping the owner would put "Ana created TR-91" on a night Ana did nothing, which is the
        // attribution AuditEvent#actorTokenId's header refuses one layer over — and AuditEvent#record
        // says the same from the other side: "the authenticated caller; null only for an unattended
        // job". This is that job, and it is the product's first one.
        UUID actor = source == RunSource.SCHEDULED ? null : userId;
```

Then pass `actor` — **not `userId`** — in exactly two places:

```java
        TestRun created = TestRun.create(…, source, actor, idempotencyKey, scopeRequestOf(…), now);
```

```java
        auditEvents.save(AuditEvent.record(project.getId(), actor, AUDIT_ACTION, …));
```

**The gate keeps `userId`**: `projects.writeGranted(userId, …)` in `create`, and `gate(userId, …)`
for §6g. Changing either to `actor` would make a scheduled run ungated, which is the opposite of the
decision.

- [ ] **Step 3: Refuse the approval-gate override on the scheduled door**

`overrideApprovalGate` is *"a statement about this request"* (`RunWriteRequest`'s header) and §6g's
whole word is **documented** — a lead deciding, in the moment, to run unapproved cases. A schedule
cannot make that decision in advance for every night to come. `RunScheduleFiringService` therefore
builds its `RunWriteRequest` with `overrideApprovalGate = null`, and the gate refusing is recorded
as `last_fire_error` rather than overridden. Say so in `createScheduled`'s header.

- [ ] **Step 4: Compile**

### Task 13: `RunScheduleFiringService` — one schedule, one fire

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/RunScheduleFiringService.java`

- [ ] **Step 1: Write it**

```java
/**
 * Fires one due schedule.
 *
 * <h2>Separate from the poller on purpose</h2>
 *
 * <p>{@code CaseHealthScheduler}'s split, and its header carries the reason: the {@code @Transactional}
 * belongs here so each schedule commits on its own. A transaction spanning the loop would have to
 * change {@code app.current_company} inside itself, which is the one thing
 * {@code CompanyAwareTransactionManager} exists to prevent, and one schedule's failure would roll
 * back every schedule fired before it.
 *
 * <h2>Every refusal is recorded, never thrown</h2>
 *
 * <p>The owner lost the grant, the project was archived, the scope resolved to nothing, §6g's gate
 * refused — each is a legitimate answer and each is written to {@code last_fire_error}, with
 * {@code nextFireAt} still advanced. A schedule that stops silently is the failure mode of
 * scheduling anything, and this column is the only place a reader could find out.
 *
 * <h2>The archived refusal lives here, not in {@code RunCreateService}</h2>
 *
 * <p>Archiving a project blocks no other write in this product — {@code isArchived()} is read by the
 * archive toggle and two view projections and nothing else. So "do not fire into an archived
 * project" is a rule about <em>schedules</em>, and putting it in {@code RunCreateService} would add
 * a sealed {@code Outcome} arm that {@code RunController}'s exhaustive switch must handle and the
 * HTTP path can never produce. The cost is one extra permission evaluation per fire, because
 * {@code createScheduled} re-gates inside this lambda — per fire, not per request.
 */
@Service
class RunScheduleFiringService {

    @Transactional
    void fire(UUID scheduleId, Instant now) { … }
}
```

The body, in order:

1. Re-read the schedule **inside this transaction** (the poller passes an id, not a detached entity)
   and re-check `enabled` and `nextFireAt <= now` — two instances can select the same row.
2. `slot = schedule.getNextFireAt()` — captured **before** anything advances it. It is half the
   idempotency key, so it must be the slot, not `now`.
3. `projects.writeGranted(schedule.getCreatedBy(), schedule.getProjectId(), CREATE_RUN, project -> …)`:
   - `project.isArchived()` → record `"The project is archived."`, no run.
   - otherwise `RunScopeRequestView.parse(json, schedule.getScopeRequest())` → build a
     `RunWriteRequest` with the schedule's four arms, configuration, assignee, and a name of
     `schedule.getName() + " — " + LocalDate.ofInstant(slot, zone)`.
   - `runCreateService.createScheduled(owner, projectId, request, "schedule:" + scheduleId + ":" + slot)`.
4. Map the outcome:

| Outcome | Recorded |
|---|---|
| `Created` | `lastRunId`, `lastFiredAt = now`, `lastFireError = null` |
| `Replayed` | **The same, and it is not an error.** Another instance won the tick; the run exists and is the right one |
| `Invalid` | `lastFireError` = the first field message. The common case is an empty resolved scope — a suite deleted since the schedule was written |
| `Refused` | `lastFireError` = §6g's refusal sentence |
| `Denied` | `"The schedule's owner no longer holds run:create on this project."` |
| `Unreachable` | `"The project is no longer visible to the schedule's owner."` |

5. **`recomputeNextFire(schedule, now)` in every branch, including every failure.** A schedule that
   fails once and never advances its `nextFireAt` is re-fired on every tick forever — a failing
   schedule becoming a hot loop is the worst available outcome here.

- [ ] **Step 2: Compile**

### Task 14: `RunScheduleScheduler` — the company loop

**Files:**
- Create: `backend/src/main/java/com/tcms/execution/RunScheduleScheduler.java`

- [ ] **Step 1: Read both precedents before writing a line**

```bash
sed -n '1,90p' backend/src/main/java/com/tcms/repository/CaseHealthScheduler.java
sed -n '1,90p' backend/src/main/java/com/tcms/tenancy/OutboxScheduler.java
```

The class this mirrors is **`CaseHealthScheduler`**. Why the other one is the wrong model is a
section of this document — reproduce its conclusion in the header, because the next reader will see
two nearly identical loops and pick whichever they find first.

- [ ] **Step 2: Write it**

```java
package com.tcms.execution;

/**
 * Fires due {@link RunSchedule}s for every company, on a schedule.
 *
 * <h2>The company loop is the only way this job can see anything</h2>
 *
 * <p>{@code CaseHealthScheduler}'s and {@code OutboxScheduler}'s shared property, repeated here
 * rather than referenced because getting it wrong is silent: {@code run_schedule} is company-owned
 * and therefore under {@code FORCE ROW LEVEL SECURITY}, while a scheduled task starts with
 * <strong>no company at all</strong> — {@code CompanyContext} is deliberately not inheritable and
 * {@code CompanyContextTaskDecorator} clears it on every task, so a job that forgets fails closed.
 * The tempting shape, one query for every due row in the instance, <strong>returns zero rows and
 * logs a successful tick having fired nothing.</strong> {@code V4}'s
 * {@code nullif(current_setting(…), '')} is what makes an unset context match nothing rather than
 * everything — the right failure, and a completely invisible one.
 *
 * <p>{@code company} is one of the few tables not under RLS, precisely so it can be read before any
 * context exists. That is what makes this loop possible and why the loop is all this class does.
 *
 * <h2>Why this is {@code CaseHealthScheduler}'s shape and not {@code OutboxScheduler}'s</h2>
 *
 * <p>The two loops are nearly identical and the difference is entirely in what they call.
 * {@code OutboxScheduler} drives a <strong>lease-based queue drain</strong> — claim under a lease,
 * run a handler, record the failure on the row, retry with backoff — whose semantics are
 * at-least-once and whose duplicates are made harmless by {@code NotificationLedger}. Nothing here
 * is like that. A fire is <strong>at-most-once</strong>: a duplicate is a second checklist, which is
 * the outcome {@code RunCreateService}'s idempotency comment calls out by name, and a missed slot is
 * <em>not redelivered</em> — a regression pass that did not happen on Tuesday cannot be made to have
 * happened on Tuesday. Copying the outbox would mean copying a lease, a retry count and a backoff
 * into a table that needs none of them, and framing a missed run as something owed.
 *
 * <p>{@code CaseHealthScheduler} is the precedent because it does what this does: iterate companies,
 * open each, do one idempotent thing, catch per company, log the id, <strong>continue</strong>.
 *
 * <h2>One transaction per fire, not one for the run</h2>
 *
 * <p>{@link RunScheduleFiringService#fire} carries the {@code @Transactional}. A single transaction
 * spanning the loop would have to change {@code app.current_company} inside itself — the one thing
 * {@code CompanyAwareTransactionManager} exists to prevent — and one company's failure would roll
 * back every company fired before it.
 *
 * <h2>Suspended companies are polled too</h2>
 *
 * <p>Both siblings make the same call for the same reason: suspension is reversible by policy (§6s).
 * Skipping them would produce a schedule that silently misses its slots for the length of the
 * suspension with no record of having done so, where {@code lastFiredAt} at least says the truth.
 */
@Component
@ConditionalOnProperty(prefix = "tcms.run-schedule", name = "enabled", matchIfMissing = true)
class RunScheduleScheduler {

    private static final Logger log = LoggerFactory.getLogger(RunScheduleScheduler.class);

    private final CompanyRepository companies;
    private final RunScheduleRepository schedules;
    private final RunScheduleFiringService firing;
    private final Clock clock;

    …

    /**
     * Every minute, after three minutes' grace.
     *
     * <p>A fixed delay rather than a cron, for {@code CaseHealthScheduler}'s stated reason: a cron at
     * a round time puts this on the same tick as every other job anyone adds later. One minute is
     * the <strong>resolution of the feature</strong> — a schedule can be no more precise than the
     * poll — and it is not configurable, because a knob here is a knob that gets set to something
     * that never runs. The three-minute initial delay keeps it off the startup path and off its two
     * siblings' one and two, so the three do not start in lockstep and stay there.
     */
    @Scheduled(initialDelayString = "PT3M", fixedDelayString = "PT1M")
    void fireDueForAllCompanies() {
        Instant now = clock.instant();
        int fired = 0;
        for (Company company : companies.findAll()) {
            try (var ignored = CompanyContext.open(company.getId())) {
                for (RunSchedule due : schedules.findDue(now)) {
                    firing.fire(due.getId(), now);
                    fired++;
                }
            } catch (RuntimeException failure) {
                // Named, not swallowed: without the company id this line is unactionable, and
                // without the continue one bad company silently costs every company after it.
                log.warn("Run-schedule firing failed for company {}", company.getId(), failure);
            }
        }
        if (fired > 0) {
            log.debug("Run schedules fired {} runs", fired);
        }
    }
}
```

Note `firing.fire(due.getId(), now)` takes an **id**: the entity was loaded in the poller's own
(non-transactional) read and the firing service re-reads it inside its transaction. Passing the
detached entity across the boundary is how a stale `nextFireAt` gets written back over a fresher one.

- [ ] **Step 3: Compile and run the whole backend gate**

```bash
~/bin/tcms-remote backend ./gradlew build
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/tcms/execution
git commit -m "feat: a schedule fires, per company, on nobody's authority but its owner's"
```

---

# Phase 6 — the tests that are the point of the area

### Task 15: `RunScheduleFiringTest`

**Files:**
- Create: `backend/src/test/java/com/tcms/execution/RunScheduleFiringTest.java`

- [ ] **Step 1: Write the arms**

```java
/**
 * What happens when a schedule fires.
 *
 * <p>Ids are unique to this class — {@code e7…}.
 *
 * <p>The class exists for four failures the CRUD test cannot see: a run attributed to a person who
 * was asleep; a schedule that fires into an archived project; a schedule that fires twice; and a
 * schedule that fails once and then never advances, becoming a hot loop.
 */
class RunScheduleFiringTest extends IntegrationTestBase { … }
```

- [ ] **`aFiredScheduleCreatesARunWithNoCreator`** — the run exists, `source = SCHEDULED`,
      `created_by IS NULL`, and its checklist has one execution per resolved case.
- [ ] **`theAuditRowForAScheduledRunNamesNobody`** — **this is `§242 LG-5`.** Assert
      `actor_user_id IS NULL` **and** `actor_token_id IS NULL` on the `run.created` row, and then
      read it back through `identity.AuditLogService` and assert the view resolves to no person and
      no token. Asserting only the column would pass against a reader that invents an actor.
- [ ] **`theScopeIsReResolvedOnEveryFire`** — a schedule scoped by a tag; fire; add a new case
      carrying that tag; fire again; the second run's checklist is one longer. This is the property
      that distinguishes a schedule from a clone, and `RunCloneService` deliberately does the
      opposite.
- [ ] **`theSecondFireOfOneSlotMakesNoSecondChecklist`** — call `fire` twice with the same slot and
      assert **one** run. The guard is the idempotency key, so this also pins the key's shape.
- [ ] **`anArchivedProjectStopsTheFireAndSaysSo`** — archive the project, fire, assert no run,
      `last_fire_error` non-null, and **`nextFireAt` advanced**.
- [ ] **`anOwnerWhoLostTheGrantStopsTheSchedule`** — demote the owner to Viewer, fire, assert no run
      and the grant message. This is the arm that proves the schedule is not a standing right.
- [ ] **`aFailedFireStillAdvances`** — the hot-loop arm. Fire into a broken state twice and assert
      `nextFireAt` moved both times.
- [ ] **`aMissedSlotIsNotCaughtUp`** — set `nextFireAt` a week in the past on a daily schedule, fire
      once, assert **one** run and a `nextFireAt` in the future. The decision, pinned.
- [ ] **`aDisabledScheduleIsNotDue`** — belt and braces over the partial index's predicate.

- [ ] **Step 2: Run it**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.execution.RunScheduleFiringTest'
```

### Task 16: The tenancy boundary, in the family that already owns it

**Files:**
- Modify: `backend/src/test/java/com/tcms/tenancy/AsyncCompanyContextTest.java`

- [ ] **Step 1: Add the arm to the existing class rather than starting a new one**

The master plan says so, and it is right: that class already exists to prove the context rules that
this poller depends on.

- [ ] **`aFireInOneCompanyCannotSeeAnothersSchedules`** — two companies, both with a due schedule;
      open company A only and run the firing path; assert **A's run exists and B's schedule is
      untouched** (`lastFiredAt` still null).
- [ ] **`theDueQueryWithNoCompanyOpenReturnsNothing`** — the invisible failure, made visible. Call
      `findDue` with **no** `CompanyContext` open and assert an empty list. This is the arm that
      would have caught the tempting one-query-for-the-instance shape, and it fails *closed*, which
      is why nothing else would have.

- [ ] **Step 2: Run it**

### Task 17: The full backend gate

- [ ] **Step 1: Run it**

```bash
~/bin/tcms-remote backend ./gradlew build
```

Expected: BUILD SUCCESSFUL. **Record the test count** — the handoff needs it against §243's 3128.

This is also where a `hibernate.ddl-auto: validate` mismatch between `RunSchedule` and `V68`
surfaces, and where `ArchitectureRulesTest` will complain if `run_schedule` has a column nothing
reads. `lastFireError` is read by the view; `lastRunId` is read by the view; if anything else turns
out writerless or readerless, **fix the code rather than adding an exemption** — the master plan's
area B is busy deleting one of those.

- [ ] **Step 2: Commit**

```bash
git add backend/src backend/src/test
git commit -m "test: a scheduled run has no actor, no catch-up and no second checklist"
```

---

# Phase 7 — the screen

### Task 18: The hooks

**Files:**
- Modify: `frontend/src/api/queries/runs.ts`

- [ ] **Step 1: Add the five hooks and one `keys` entry**

`useRunSchedules(projectId)`, `useRunSchedule(projectId, id)`, `useCreateRunSchedule`,
`useUpdateRunSchedule`, `useDeleteRunSchedule`. One `keys` factory entry —
`keys.runSchedules(projectId)` — and every mutation invalidates it. **TanStack Query owns all server
state**; nothing about a schedule goes into a client store.

Give the reads the same per-endpoint timeout budget the neighbouring run reads carry — copy the
number from the file, do not invent one.

### Task 19: `RunSchedulePage`

**Files:**
- Create: `frontend/src/features/runs/RunSchedulePage.tsx` and `RunSchedulePage.test.tsx`
- Modify: `frontend/src/app/router.tsx`

- [ ] **Step 1: Load `/fe-react` and only the current phase's skills**

- [ ] **Step 2: Build the screen**

- Route `/projects/:projectId/run-schedules`, lazy, beside the other run routes.
- **`DataRegion`** for the loading / loaded / failed / empty / stale matrix — do not hand-roll it.
- **`Identifier`** for the run key and any id.
- Filter state (enabled-only) in the **URL** via `useSearchParams`, not in component state.
- **`useCan('run:create')` gates the create and edit controls — `disabled`, not hidden.** The
  working agreement's §31 rule; §242's hidden nav entries were an explicit, argued exception, and
  this is not one.
- The **`lastFireError`** is rendered prominently on any schedule that has one. A schedule that
  silently stopped is the feature's failure mode and this string is the only place it is visible.
- `nextFireAt === null` renders as **"Will not fire again"**, never as a blank cell — a blank reads
  as "loading" or "soon", both false.
- The cron field carries an example and the zone is a select, because a free-text zone is a typo away
  from a 400 the user cannot diagnose.

- [ ] **Step 3: Write the tests**

Use **`createTestQueryClient`** — never build your own `QueryClient`; only that one has the caches.
A test whose read or write fails must call `drivingAFailedRead()` / `drivingAFailedWrite()` and say
why, or `renderFailures.test.tsx` fails the suite.

Arms: the empty state; a schedule with a `lastFireError` shows it; `nextFireAt` null renders the
sentence; a Viewer sees the create control **disabled**; creating invalidates the list; the
contract-conformance double compares the served body, the payload and the status against
`openapi.yaml` via `src/test/fixtures.ts` — anchor the path-keyed mock arm
(`/^\/projects\/[^/]+\/run-schedules$/` and `/^\/projects\/[^/]+\/run-schedules\/[^/]+$/`) so the
collection arm does not swallow the item.

### Task 20: The `source` label — the trap this area would otherwise ship

**Files:**
- Modify: `frontend/src/lib/labels.ts`
- Modify: `frontend/src/features/runs/RunListPage.tsx`

- [ ] **Step 1: Replace the ternary with a labelled map**

`RunListPage.tsx:161` currently reads:

```tsx
{run.source === 'INGESTED' ? 'from CI' : 'manual'}
```

Under a third enum member that silently labels every scheduled run **"manual"**, which is precisely
the false attribution the whole actor split exists to avoid — and it typechecks, so no gate catches
it. Move it to `lib/labels.ts`:

```ts
export const runSourceLabels = {
  MANUAL: 'manual',
  INGESTED: 'from CI',
  SCHEDULED: 'scheduled',
} satisfies Record<RunSource, string>
```

The `satisfies` is the point: the **next** member is a compile error until it is labelled, which is
the guarantee the working agreement asks for and the ternary never had.

- [ ] **Step 2: Check for other consumers of `source`**

```bash
grep -rn "\.source" frontend/src/features frontend/src/lib | grep -v "\.test\."
```

`CaseHistoryPage.tsx:288` has `const ingested = entry.source === 'INGESTED'` — that one is a
*boolean about ingestion*, not a label, and reads correctly for a scheduled row (it is not ingested).
Leave it, and say so in the diff so it does not look overlooked.

- [ ] **Step 3: Frontend gates**

```bash
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote frontend npm run lint
~/bin/tcms-remote packages npm run build
~/bin/tcms-remote packages npm run test:run --workspaces
```

Expected: all green, lint at **0 warnings**. `citedFiles.test.ts` sweeps all four corpora, so a path
named in a comment that does not exist reddens the **packages** job — check any file path this area's
comments name.

- [ ] **Step 4: Commit**

```bash
git add frontend/src
git commit -m "feat: run schedules have a screen, and a run's source has a label"
```

---

# Phase 8 — the record

**One commit.** The docs a later reader needs, and the deferred-verification rows §65 requires.

### Task 21: The handoff section

**Files:**
- Create: `docs/handoff/<next>-runs-can-be-scheduled.md`
- Modify: `docs/HANDOFF.md` — **one index row appended to the table. Never append prose to
  `HANDOFF.md` itself.**

- [ ] **Step 1: Take the next free number**

```bash
ls docs/handoff | sed 's/-.*//' | sort -n | tail -1
```

§243 is the highest at the time of writing; areas A and B land before C, so expect 245 or 246. **Do
not hardcode a number from this document.**

- [ ] **Step 2: Write it**

Cover, in the register the numbered sections use:

- The nine decisions above, and specifically **authority versus attribution** — it is the reusable
  one, and the sentence to carry is that *a job's authority and a job's actor are different columns
  and conflating them writes a lie that reads correct*.
- **`§242 LG-5` is drivable and its row was rewritten in place**, keeping its id.
- Why `CaseHealthScheduler` and not `OutboxScheduler`, in one paragraph, because the two loops look
  identical and the next reader will find whichever comes first.
- **No catch-up**, stated as a decision with its reason, so nobody "fixes" it later.
- `V68` was the master plan's only migration and it stayed that way; the CHECK-constraint swap is
  the one existing table it touched.
- Measured gate numbers from every phase, against §243's backend **3128** / frontend **1602 / 120** /
  packages **336**.
- **"Built and unverified"** — the queues below are unrun, and the area must be described that way.

- [ ] **Step 3: Add exactly one index row**

```markdown
| [§NNN](handoff/NNN-runs-can-be-scheduled.md) | **Runs can be scheduled** — area C of `docs/plans/2026-08-18-unplanned-gaps.md`, stepped first into `docs/plans/2026-08-18-gap-c-scheduled-runs.md`… |
```

### Task 22: The verification queues — including the row this area unblocks

**Files:**
- Modify: `docs/verification/backend-mutation-checks.md`
- Modify: `docs/verification/frontend-browser-checks.md`

**§65 is standing policy: write the rows, run nothing.** Not the cheap one, not the highest-signal
one. A slice that lands without them has skipped the check outright.

- [ ] **Step 1: Backend mutation rows** (stable ids, `RS-` series)

| id | Drive | Expect |
|---|---|---|
| `RS-1` | Create a schedule `0 * * * * *` scoped by a tag; wait two polls | Two runs, `source = SCHEDULED`, `created_by IS NULL` on both |
| `RS-2` | `SELECT actor_user_id, actor_token_id FROM audit_event WHERE action='run.created'` for those runs | Both null — **the first actorless act the product has ever written** |
| `RS-3` | Add a case carrying the tag, wait one poll | The next run's checklist is one longer |
| `RS-4` | Archive the project, wait one poll | No run; `last_fire_error` names the archive; `next_fire_at` still advanced |
| `RS-5` | Demote the owner to Viewer, wait one poll | No run; `last_fire_error` names the grant |
| `RS-6` | Stop the app for ten minutes with a one-minute schedule, restart | **One** run, not ten — the no-catch-up decision, observed |
| `RS-7` | Delete the schedule | The runs it created are still live |
| `RS-8` | `\d test_run` | `test_run_source_check` allows exactly three values |

- [ ] **Step 2: Frontend browser rows** — the new lane rows, and **the rewrite**

New rows go in the lane whose **state** they need: **A3 (execution)**, which already has a project,
suites, cases and tags. Give them stable ids in the `§NNN` series and name the fixture the pass's own
charter authors — a schedule named `Nightly smoke` scoped to the `smoke` tag, `0 0 3 * * *`,
`Europe/Sofia`.

A row that needs data no lane creates must say what is missing and what the options are: **a real
fire on a three-a.m. cron cannot be waited for in a browser pass.** The options are (a) create a
second schedule on a one-minute cron for the duration of the lane and delete it after, or (b) drive
the poller from `psql` by setting `next_fire_at = now()`. State both; do not silently skip.

- [ ] **Step 3: Rewrite `§242 LG-5` in place — do not renumber it**

`docs/verification/frontend-browser-checks.md:3103`. The id is quoted from `docs/handoff/242` and is
never renumbered. Replace the ⚠️ cell with the drivable form and **strike the old claim rather than
deleting it**, per the working agreement:

> `§242 LG-5` | An act with `actorUserId` null renders as **"Automatic"** with no person and no
> token | ~~**NOT DRIVABLE.** No producer exists…~~ → **superseded 2026-08-22 by area C.** A
> `run_schedule` firing is the product's first actorless writer. **Before you start:** the `Nightly
> smoke` schedule from lane A3 must have fired at least once. Open the audit log, filter to
> `run.created`, and confirm the scheduled run's row shows "Automatic" with no person and no token
> beside a hand-created run's row that shows a name.

Also correct the file's §242 preamble at `:3092`, which still says the shape *"has no producer in the
product"*.

- [ ] **Step 4: Correct `docs/features.md`**

Strike the *"No scheduled runs"* gap entry rather than deleting it, and point at this document.

### Task 23: Both orientation caches, then push

**Files:**
- Modify: `docs/agent-context/be-java.md`
- Modify: `docs/agent-context/fe-react.md`

**Update them in this area's own commit** — that is the cache protocol, not a follow-up. And note the
master plan's warning: **one writer for `docs/agent-context/*.md`**, so if another lane is open,
stagger the closes.

- [ ] **Step 1: `be-java.md`**

Replace the `Baseline commit:` / `Last verified:` header with this slice's, keeping the previous one
below as superseded (the file's convention), and add a seam entry covering:

- `run_schedule` — company-owned, `V68`, `scope_request` is **`V61`'s document reused, not a second
  encoding**, and the writer/reader pair that keeps it that way.
- **The third door into `RunCreateService`** and the rule it generalises: *a job's authority is the
  grant it runs under; a job's actor is who did it; they are different columns and a poller has only
  the first.*
- **`RunScheduleScheduler` follows `CaseHealthScheduler`, not `OutboxScheduler`**, and the one-line
  reason (at-most-once domain write versus at-least-once queue drain).
- No catch-up, and where it is pinned.

- [ ] **Step 2: `fe-react.md`**

One short entry: `RunSchedulePage`, and the `runSourceLabels` map — a `satisfies Record<RunSource, string>`
replacing a ternary that would have labelled every scheduled run "manual" and typechecked while doing
it.

- [ ] **Step 3: Run every gate one last time, commit, push**

```bash
~/bin/tcms-remote backend ./gradlew build
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote frontend npm run build
~/bin/tcms-remote packages npm run build
git add docs/
git commit -m "docs: scheduled-runs handoff, seams and verification rows"
git push
```

---

## Open questions for the implementer

Four, each with a recommendation and none of them blocking. Settle them in the handoff rather than
leaving them implicit.

1. **Is `test_run_source_check` really the constraint's name?** It is inline in `V1:806`, so Postgres
   named it, and the default is `<table>_<column>_check`. **Task 1 Step 1 verifies it against a live
   database.** A `DROP CONSTRAINT` naming something that does not exist fails `V68` on first boot in
   every environment at once.

2. **Should `POST /run-schedules` take an `Idempotency-Key`?** The fragment declares one, matching
   every other create in this API. It is nearly worthless here (a duplicate schedule is visible and
   deletable, unlike a duplicate checklist) but the cost is one column-free lookup and the
   consistency is worth more than the saving. **Recommendation: keep it.** If it is dropped, drop it
   from the fragment too rather than declaring a header nothing honours — `writeConformance.test.ts`
   asserts that an operation *declares* the header its `kind` implies.

3. **What does a schedule do when its resolved scope is empty on a given night?** The plan records
   `last_fire_error` and creates no run, because `RunEdit` refuses an empty run anyway and a run with
   0 of 0 renders **as though it had finished**. The alternative — create the empty run so the
   cadence is visible — was rejected for that reason. It is worth one sentence in the handoff because
   somebody will ask why a Monday is missing from the run list.

4. **Should the CLI and the MCP server get schedule commands?** Out of scope here: neither
   `packages/cli` nor `packages/mcp` is in the master plan's file list for area C, and adding a write
   arm obliges `writeConformance.test.ts` in both. **Recommendation: record it as owed** in the
   handoff — an agent that can create a run and not a schedule is a gap a reader will notice.

---

## Self-review

**Coverage against the master plan's tasks 15–25.** Task 15 (`V68`, `company_id` first in every
index, `FORCE RLS`, reuse `V61`'s scope shape, a header saying what the table is for) → Phase 1.
Task 16 (entity, repository, `@Transactional(readOnly = true)`) → Task 3. Task 17 (contract + CRUD
service, five operations, `required` on every schema, both copies regenerated) → Tasks 6–9. Task 18
(cron parsing in exactly one place, validated at write time) → Tasks 4–5. Task 19 (the per-company
scheduler mirroring `CaseHealthScheduler:73-87`) → Task 14, with the `OutboxScheduler` comparison
written out because the master plan asked for the reason and not just the choice. Task 20 (decide and
pin the archived behaviour) → decision 5 plus `anArchivedProjectStopsTheFireAndSaysSo`. Task 21
(tenancy boundary, in `AsyncCompanyContextTest`'s existing family) → Task 16. Task 22 (the actorless
audit row, read through the shipped reader) → `theAuditRowForAScheduledRunNamesNobody` plus the
in-place rewrite of `§242 LG-5`. Task 23 (backend gate) → Task 17. Task 24 (the settings screen,
`DataRegion`, `Identifier`, URL filter state, `useCan`, disable-don't-hide) → Task 19. Task 25 (close)
→ Phase 8.

**One migration, and it is `V68`.** No second migration anywhere in this document. The
`test_run.source` CHECK swap is inside the same file. If a `run_schedule` index turns out to be
needed later, it is a follow-up with its own version — recorded, not smuggled.

**Nothing invents a second implementation.** The scope document is `V61`'s, written by
`scopeRequestOf` and read by `RunScopeRequestView.parse`; run creation is `RunCreateService`'s, with
a third door rather than a copy; the permission gate is `ProjectService.writeGranted`; the
idempotency guard is the existing `Idempotency-Key` path; cron parsing is one class called by both
the validator and the poller; the enum label is one `satisfies Record` map.

**What this plan deliberately leaves to execution.** The fixture seeding in the two integration test
classes is named and its source is named (`RunCreateEndpointTest`), not transcribed — copying two
hundred lines of seeding into this document would be duplication that has drifted by the time it is
read. The SPA's exact MUI composition is scoped rather than drafted, because the shared components it
must use (`DataRegion`, `Identifier`, `useCan`) are named and the arrangement is not where this
area's defects live.

**Known softness, stated rather than hidden.** Three line numbers in this document
(`RunCreateService.java:266`, `RunListPage.tsx:161`, `frontend-browser-checks.md:3103`) were read on
2026-08-22 and four other lanes are committing in this tree. **Re-grep before editing at a line
number** — the surrounding text quoted here is the durable reference, not the number.
