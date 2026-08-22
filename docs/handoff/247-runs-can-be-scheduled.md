# §247 — Runs can be scheduled, and a job's authority is not a job's actor

**Area C of `docs/plans/2026-08-18-unplanned-gaps.md` (master tasks 15–25), executed against the
stepped plan `docs/plans/2026-08-18-gap-c-scheduled-runs.md`.** One migration — **`V68`**, the
master plan's only one. Contract changed. **No permission seeded or moved; the catalogue is still
55 keys.**

> ⚠️ **IMPLEMENTED AND COMPLETELY UNGATED. NOTHING WAS COMPILED.**
>
> The lane ran under the batch-3 no-gates directive. No `./gradlew build`, no `./gradlew test`, no
> targeted class, **no `./gradlew compileJava`**, no `npm run test:run`, no `npm run build`, no
> `npm run lint` — locally or on `shanks`. The four new test files hold **7 / 26 / 15 / 11** arms
> (`CronScheduleTest`, `RunScheduleEndpointTest`, `RunScheduleFiringTest`,
> `RunSchedulePage.test.tsx`) and the two arms added to `AsyncCompanyContextTest` make **61 arms
> that have never executed**. Those counts are `grep -c` against the files, not a tally kept by
> hand.
>
> **Every claim in this document is read off the code, not off a green suite.** Both verification
> queues are unrun. This area is *built and unverified*, and must be described that way.
>
> It ran **concurrently with area F (§248)** at the user's explicit permission; the two lanes share
> no source file. F closed and pushed first, which is why its index row precedes this one.

---

## THE RULE THAT OUTLIVES THE AREA

> **A job's AUTHORITY and a job's ACTOR are different columns, and a poller has only the first.
> Conflating them writes a lie that reads correct on every screen.**

`run_schedule.created_by` is `NOT NULL` and it is the authority: the owner's `run:create` grant is
re-evaluated through the ordinary `ProjectService.writeGranted` gate **on every single fire**, so a
schedule outlives neither the grant nor the account. A schedule whose owner was offboarded, lost the
project grant or had their role narrowed stops firing, and says so.

The **actor** on both the run row and the audit row is **null**, because at 03:00 nobody performed
the act. `AuditEvent#record`'s own parameter doc has said so since the table existed — *"the
authenticated caller; null only for an unattended job"* — and until `V68` **there was no such job in
the product**.

Mechanically it is one line in `RunCreateService.apply`:

```java
UUID actor = source == RunSource.SCHEDULED ? null : userId;
```

`actor` reaches exactly two places: `TestRun.create`'s creator parameter and `AuditEvent.record`'s.
**The gate keeps `userId`** — `projects.writeGranted(userId, …)` in `create`, and `gate(userId, …)`
for §6g. Changing either to `actor` makes a scheduled run *ungated*, which is the opposite of the
decision; `§247 SK-M2` is that mutation, recorded because the two halves look interchangeable in the
diff.

The alternative — stamping the owner — produces *"Ana created TR-91"* about a night on which Ana did
nothing. That is the attribution `AuditEvent#actorTokenId`'s header refuses one layer over, and it is
worse here because it is not even a credential's act.

---

## §242 LG-5 IS DRIVABLE, AND ITS ROW WAS REWRITTEN IN PLACE

`docs/handoff/242-the-audit-log-reader.md` records that an audit act with `actorUserId` null
*"is not drivable"* because every write path in the product carries a caller. **`V68`'s poller is
the first actorless writer this product has ever had**, so that row is now executable.

Three things follow and all three were done:

1. **`RunScheduleFiringTest.theAuditRowForAScheduledRunNamesNobody`** asserts it in the backend, at
   **both** levels: `actor_user_id IS NULL` and `actor_token_id IS NULL` on the `run.created` row,
   *and* the same row read back through the shipped `identity.AuditLogService`, where
   `actorUserName` and `actorTokenName` must both resolve to nothing. **Asserting only the column
   would pass against a reader that invented an actor** — resolving the schedule's owner, say,
   because it is the only person on the row.
2. **`aHandCreatedRunStillNamesItsCreator`** is the control, in the same fixture: a run created by a
   person through the ordinary endpoint must still name them. Without it, *"the scheduled row names
   nobody"* would also be true of a build that had simply stopped recording actors.
3. **The queue row `§242 LG-5` was rewritten in place and keeps its id.** Ids are quoted from handoff
   sections and are never renumbered. It loses the ⚠️ NOT DRIVABLE and gains a **Before you start**
   naming the `Nightly smoke` schedule the pass creates in `§247 SK-1`; the old claim is **struck
   through rather than deleted**, per the working agreement. **Its lane preamble was corrected too**
   (`frontend-browser-checks.md`, the `LG` lane's fixture note), which still said the shape *"has no
   producer in the product at all"* — a reader who fixed only the row would have found the preamble
   contradicting it.

**The expectation is deliberately not "Automatic renders".** It is *the scheduled row beside a
hand-created run's row, in one screenshot*: "Automatic" alone is also what a build that had stopped
recording actors would render, which is the same trap as (2) one layer up.

`AuditLogReadTest.anUnattendedJobHasNoPerson` stays — it covers the *shape* with a hand-inserted
row. What changed is that the product now produces one.

---

## THE ARCHIVED-PROJECT DECISION, AND WHY IT LIVES WHERE IT DOES

**Decision: a schedule whose project is archived does not fire, records why, and still advances.**

The refusal lives in **`RunScheduleFiringService.attempt`**, inside its own `writeGranted` lambda —
**not** in `RunCreateService`. Three reasons, and the second is the one that decided it:

- **Archiving blocks no other write in this product.** `grep -rn "isArchived()" backend/src/main/java`
  finds it in `ProjectArchiveService` (the toggle itself), two view projections, and nowhere else.
  So *"do not fire into an archived project"* is a **new rule that belongs to schedules**, not a rule
  about run creation. Putting it in `RunCreateService` would state the opposite.
- **It would have added a dead arm to a shipped endpoint.** `RunCreateService.Outcome` is sealed and
  `RunController`'s switch over it is exhaustive at compile time, which is the property
  `GateResult`'s header asks for. A seventh arm meaning *"the project is archived"* would have to be
  handled there — and the HTTP path can never produce it, because `POST /runs` has no such rule. A
  `case` nobody can reach is worse than no case: the next reader has to work out whether it is dead
  or whether they are missing something.
- **The cost is one extra permission evaluation per fire**, because the nested `createScheduled`
  re-gates inside this lambda. That is per **fire**, not per request — and §225's per-transaction
  memo in `PermissionEvaluator` absorbs most of it, since both evaluations share
  `(scope, companyId, userId, projectId, tokenPrincipal)` inside one transaction. Paying it buys the
  new rule staying entirely inside the new code.

An undecided answer here is a job that quietly resurrects archived work, which is worse than a job
that stops. `anArchivedProjectStopsTheFireAndSaysSo` asserts **all three halves**: no run,
`last_fire_error` names the archive, and `nextFireAt` **still advanced**.

---

## NO CATCH-UP, AND EVERY BRANCH STILL ADVANCES

`next_fire_at` is recomputed **from the moment of firing**, never from the missed slot. A deployment
down for a day does not produce a day of backdated regression runs on restart.

The alternative — loop `CronExpression.next` forward from `last_fired_at` until it passes now, firing
each — is what a *job* scheduler does and what a *test* scheduler must not: seven identical runs
appear at once, each materialising a full checklist against **today's** case versions, and none of
them means what its timestamp says. **A regression pass that did not happen on Tuesday cannot be made
to have happened on Tuesday.** The honest record of the gap is `last_fired_at`, which the screen
shows and which a reader compares against the cadence themselves — and which is therefore
deliberately **not** moved by a failed fire (`aFailedFireDoesNotClaimARunHappened`).

**`recomputeNextFire` is called in EVERY branch of a fire, including every failure.** A schedule that
fails once and never advances is re-fired on every tick for ever; a broken schedule becoming a hot
loop is the worst outcome available in this area, and it is the one a naive implementation lands on
by putting the call inside the success arm. `aFailedFireStillAdvances` is the observer and
`§247 SK-M3` is the measurement.

**`CronExpression.next` is exclusive**, which closes the same loop from the other side: a schedule
fired at exactly its own slot would otherwise recompute to that same slot and be due again on the
next tick, for ever (`theNextOccurrenceIsStrictlyAfterTheMomentAskedAbout`, `SK-M12`).

---

## THE DOUBLE-FIRE GUARD IS THE IDEMPOTENCY KEY THAT ALREADY EXISTS

`RunCreateService` already refuses a second checklist for a repeated `Idempotency-Key`. The poller
mints **`schedule:<scheduleId>:<slot>`** — deterministic in the schedule and the **slot**, never in
`now`. Two application instances polling the same tick, or a retry after a partial failure, produce
**one** run and an `Outcome.Replayed` for the loser.

**A `Replayed` is not an error and is not recorded as one.** The run exists, it is the right one, and
there is exactly one of it; putting a red sentence on a schedule that worked is the failure mode of
the honesty column itself (`SK-M14`).

Nothing new was built for this. A second guard would be a second implementation of the one property
whose absence `RunCreateService`'s own comment calls *"a second CHECKLIST"*.

---

## THE `Idempotency-Key` WAS REMOVED FROM THE CREATE — AGAINST THIS PLAN'S OWN RECOMMENDATION

The stepped plan's **open question 2** recommended keeping it: *"nearly worthless here … but the cost
is one column-free lookup and the consistency is worth more than the saving."* **That was reversed,
and the fragment was removed from the contract rather than left declaring a header nothing honours.**

- **What this repository calls idempotency is a unique `idempotency_key` column on a *created
  row*.** `V68` declares none, and `V68` is this area's only migration. So the header would have been
  read by nothing.
- **That is §205's finding arriving from the same side it arrived from last time.** `packages/mcp`
  declared and sent a key `ReportController` never read — *a header that looks right and does
  nothing*, which is the exact failure the parameter's own description warns about. A client that
  trusted it would replay a timed-out create and get a second schedule.
- **Two in-repo precedents decline it on these terms**: `POST /projects/{projectId}/conditions`, and
  the notification-channel create (§218), whose note reads *"there is no idempotency record behind the
  path"*.
- **The cost of a duplicate is not a run's.** A second schedule is visible in the list and deletable;
  a second checklist splits one tester's results across two runs, each reporting a pass rate over a
  denominator nobody chose. The consistency argument does not survive that asymmetry.

`writeConformance.test.ts` is unaffected: neither the CLI nor the MCP server offers schedules
(open question 4, recorded as **owed** below).

---

## `V61`'s DOCUMENT IS REUSED, AND THE EXTRACTION IS WHAT MAKES THAT TRUE

`run_schedule.scope_request` is the **same** `{planId, caseIds, suiteIds, tags}` JSONB `V61` added to
`test_run` — same writer, same reader, same bytes.

Making that a property of the *code* rather than of two methods currently agreeing required an
extraction the plan left open (its Task 8 step 3 allowed either widening the private method or moving
it):

- **`RunScopeDocument.of(ObjectMapper, RunWriteRequest)`** is new, package-private, and is now the
  **one writer** of that document. It was `RunCreateService#scopeRequestOf`, private, with one caller
  until `V68` gave the shape a second home.
- **`RunScopeRequestView.parse`** was already the one reader and is reused verbatim, including by
  `RunScheduleWriteRequest.scope`, which is that very record — so the contract's `RunScopeRequest`
  serves **three** positions now (`TestRunDetail.scopeRequest`, `RunSchedule.scope`,
  `RunScheduleInput.scope`) and one record serves all three.
- What stayed behind in `scopeRequestOf` is the `INGESTED → null` arm, which is that path's own
  question and not the document's. Folding it into the extracted class is `§247 SK-M21`: every create
  would fail at the database, because `run_schedule.scope_request` is `NOT NULL`.

So a scheduled run's own `scope_request` is byte-equivalent to the schedule's, written from the
reconstructed request by the same code. **That is what makes "why is TC-41 in this run" answerable for
a run nobody asked for**, and `theRunRecordsTheSameRequestTheScheduleHolds` compares the two documents
directly.

**Three differences from `test_run.scope_request`, each deliberate:** `NOT NULL` here (a schedule that
asked for nothing could only ever make empty runs); it is **edited**, hence `updated_at` and a real
`lock_version`; and it is **re-resolved on every fire**, which `theScopeIsReResolvedOnEveryFire`
pins — a tag arm means *tonight's* cases, and that is the property distinguishing a schedule from a
clone, where `RunCloneService` deliberately does the opposite. Materialisation-once is untouched: it
is a property of each run, which is the level `V61`'s header states it at.

---

## RUN CREATION IS NOT REIMPLEMENTED — A THIRD DOOR, NOT A THIRD PATH

`RunCreateService#createScheduled` sits beside `#createIngested`, package-private, and
`RunScheduleFiringService` is its only caller. `#createIngested`'s argument is reproduced unchanged
because it is unchanged: materialising a run resolves a case set, resolves each case's **current
version**, allocates a display key, applies §6g's approval gate, writes an execution per case and
audits the whole thing as one transaction. A copy of that in a poller would be a second
implementation of **the pin** — the one thing `RunCreateService`'s header says must be resolved once.

`source` is not a parameter a caller chooses from a request field: **there are exactly three doors and
each names its own.** `RunSource` gained `SCHEDULED` as an enum member rather than a string precisely
so an exhaustive `switch` over it is a compile error under `-Werror` until it handles the new door.

**The approval-gate override is unreachable from this door, and that is the design.**
`RunWriteRequest#overrideApprovalGate`'s own header calls it *"a statement about this request"*, and
§6g's whole word is **documented** — a lead deciding, in the moment, to run unapproved cases. A
schedule cannot make that decision in advance for every night to come. So the firing service builds
its request with the field null and a gated project's refusal is recorded as `last_fire_error`, which
is a reader watching the policy work rather than a standing exemption from it.

---

## THE POLLER IS `CaseHealthScheduler`'s SHAPE, NOT `OutboxScheduler`'s

The two loops are nearly identical, which is why the reason is written into `RunScheduleScheduler`'s
own header rather than referenced: the next reader will see two loops and pick whichever they find
first.

| | `OutboxScheduler` | `RunScheduleScheduler` |
|---|---|---|
| What it drives | a **lease-based queue drain** — claim under a lease, run a handler, record the failure on the row, retry with backoff, dead-letter | one domain write per due schedule |
| Semantics | at-least-once, duplicates made harmless by `NotificationLedger` | **at-most-once** — a duplicate is a second checklist |
| A missed slot | redelivered | **not redelivered**, by decision |
| Cadence | configurable, and must agree with the lease | a fixed literal; the tick rate is the feature's resolution |
| Module | `tenancy` — infrastructure | `execution` — a product feature |

Copying the outbox would mean copying a lease column, a retry counter and a backoff into a table that
needs none of them, and framing a missed run as something *owed*.

**The company loop is not an optimisation and it is the only way this job can see anything.** A
scheduled task starts with **no company at all** — `CompanyContext` is deliberately not inheritable
and `CompanyContextTaskDecorator` clears it on every task — so the tempting shape, one query for every
due row in the instance, **returns zero rows and logs a successful tick having fired nothing**. `V4`'s
`nullif(current_setting(…), '')` is what makes an unset context match nothing rather than everything:
the right failure, and a completely invisible one. `company` is one of the few tables not under RLS
precisely so it can be read first.

The **per-company catch and `continue`** is copied verbatim from `CaseHealthScheduler:73-87`, whose
header carries the reason: losing the other forty-nine companies because one has a lock timeout is a
much worse outcome than a warning in the log.

**Suspended companies are polled too** — both siblings make the same call, suspension is reversible
by policy (§6s), and the alternative is a schedule silently missing its slots for the length of a
suspension with no record of having done so.

**The poller passes an `id`, never the entity.** It was read in the poller's own non-transactional
read; `RunScheduleFiringService.fire` re-reads it inside its transaction and re-checks `enabled` and
`nextFireAt <= now`. Passing the detached row across the boundary is how a stale `nextFireAt` gets
written back over a fresher one.

**`tcms.run-schedule.enabled` is `false` in `IntegrationTestBase`** — the fourth scheduler switched off
there, and the most destructive of the four if left on: this one **creates runs**. A tick three minutes
into a suite run would materialise checklists against whichever fixtures had seeded, in every company
in the container, and unlike case health **nothing repairs it** — the runs and their executions stay.

---

## THE TENANCY ARM HAD TO BE SPLIT ACROSS TWO CLASSES, AND THE MODULE BOUNDARY IS WHY

Master task 21 and the stepped plan both say: prove the boundary in `AsyncCompanyContextTest`, the
existing family that owns it. That is right and it is **not sufficient**, because
`RunScheduleRepository` is **package-private in `com.tcms.execution`** and `AsyncCompanyContextTest`
is in `com.tcms.tenancy`. It cannot call `findDue`.

So the arm is two arms in two places, and each says why at the site:

- **`AsyncCompanyContextTest`** gains a fifth surface with the poller's **own predicate in raw SQL**
  (`enabled AND next_fire_at <= now()`), which is what `findDue` compiles to plus the isolation
  policy — and it is the *policy* half that is invisible when it is wrong.
  `aFireInOneCompanyCannotSeeAnothersSchedules` seeds a due schedule in each of two companies;
  `theDueQueryWithNoCompanyOpenReturnsNothing` runs it on a connection with **no**
  `app.current_company` and asserts empty. That second one is the arm that would have caught the
  one-query-for-the-instance shape, and **it is the only thing that could**: nothing else in the
  suite fails when a scheduled job quietly does nothing.
- **`RunScheduleFiringTest.theDueQueryIsCompanyScoped`** drives the actual repository method from the
  package that owns it, both directions.

The generalisable half: **when the family that owns a boundary cannot reach the method, assert the
boundary there and the method next door, and write the reason at both** — a single arm in either place
alone covers the query or the policy, never both.

---

## WHAT THE PLAN MISSED, AND WHAT A GATE WOULD HAVE CAUGHT

**`SchemaConventionTest`'s policy count is a literal, and `V68` moves it.**
`everyPolicyHasTheExactIsolationPredicate` asserts `checked == 70`, and the number is what makes the
predicate check non-vacuous — a company-owned table added without its policy would otherwise pass by
not being looked at. `run_schedule` makes it **71**, changed in the migration's own commit. The
stepped plan named four `SchemaConventionTest` rules that `V68` had to satisfy and did not mention
this literal at all; under a no-gates directive nobody would have observed the red.

It is also §218's worked example one section on: two branches landing three tables each on a base of
64 both wrote 67, git merged the assertion line with no conflict, and only the comment beside it was
marked. **Recount against the schema, never against the previous sentence.**

---

## `everyMappedColumnHasAWriter` — NO EXEMPTION WAS NEEDED, AND THE RULE WAS READ BEFORE IT WAS TRUSTED

`ArchitectureRulesTest:1238` decides "written" as

```java
isWrittenInJava(field) || namedIn(writeText, null, columnOf(field)) || namedIn(writeText, null, field.getName())
```

— the last two are a **name-text match over source text**, which has already produced one false
negative in this repository: `IngestionService`'s `node.put("durationMs", …)` satisfied the sweep for
`duration_ms`, a column with **no writer at all for a year** (§244). So every column below rests on
the **first disjunct only** — a genuine bytecode `SET` from a method with production callers.

| Column(s) | Writer, and its caller |
|---|---|
| `id`, `company_id`, `project_id`, `created_at`, `created_by` | `RunSchedule.create` ← `RunScheduleService.create` |
| `name`, `cron_expression`, `zone_id`, `scope_request`, `configuration_id`, `assignee_id`, `enabled`, `updated_at`, `updated_by` | `create` **and** `replace` ← `create` / `update` |
| `next_fire_at` | `applyNextFire` ← `RunScheduleService.recomputeNextFire`, **the one writer** |
| `last_run_id`, `last_fired_at` | `recordFire` ← `RunScheduleFiringService` |
| `last_fire_error` | `recordFire` (clears) and `recordFireFailure` (sets) ← `RunScheduleFiringService` |
| `lock_version` | `@Version` — **skipped by the rule by construction**, and rightly: Hibernate's assignment is in no source file |

**`WRITERLESS_BY_DECISION` is untouched and still holds seven.** §217's rule was the thing to watch —
*a self-write counts only if the writing method has callers* — and every mutator above is called from
`src/main`, not from a fixture.

The mirror rule is satisfied too: every column has a getter called from `RunScheduleService#viewsOf`,
except `updatedAt`, `updatedBy` and `lockVersion`, which the text corpus matches (verified by grep —
`updated_at` and `updated_by` appear in `SELECT` contexts, and `lockVersion` in string literals across
the suite). That is §154's documented over-match, and it is load-bearing here rather than lucky.

---

## `@Version` IS MAPPED HERE, WHERE `TestRun` REFUSES IT

`ApiToken`'s rubric — *is a lost update here a discarded edit, or a duplicated intent?* — answers the
other way for this row. A schedule **is** edited from a form somebody read, so two people moving the
cadence from screens opened minutes apart is a **discarded edit**, which is exactly the case
`TestRun`'s header says a counter is for. And the cost `TestRun` refuses it over — contention on the
product's most-used write — does not arise: the only writes here are a person's save and one poller
tick per schedule.

There is **no `If-Match`** on the `PUT` and the view serves no `lockVersion`. The 409 comes from the
entity's own `@Version` at flush, which is a genuine interleaving a client could not have anticipated;
serving the counter would offer a header nothing reads, which is §205's finding again.
`RunScheduleController#onWriteRace` turns it into a 409 rather than a 500.

---

## SMALLER DECISIONS, EACH WITH ITS ALTERNATIVE

- **No new permission.** Writing a schedule is `run:create`: creating one is **delegated run
  creation**, and a caller who may create a run now may create one every Monday. A `schedule:manage`
  key would need six seeded grants — a **second migration**, where `V68` is this area's only one —
  plus the contract's permission enum, `lib/permissions.ts` and two matrix copies that
  `matrixCopies.test.ts` compares cell by cell in both directions. The catalogue stays at **55**.
  The constant is **imported from `RunCreateService.CREATE_RUN`, not re-spelled** (which is why it is
  package-private now): two endpoints gating one capability on two literals is §228's shape, where
  both look correct and the day one moves only one of them moves.
- **Reading costs project visibility alone**, like the run list. A schedule carries a name, a cadence,
  a scope and an owner — nothing a project member may not already see — so a second question here
  would refuse a reader a fact the run list already tells them.
- **A dead scope arm is NOT a field error, and this is the one deliberate divergence from
  `RunEdit`.** A run is created once against a set the caller has just chosen, so an id resolving to
  nothing there means the two of you disagree about what exists. A schedule is a **standing** request
  evaluated over months: a suite it names may be deleted next quarter, and refusing the edit at that
  point would make the schedule uneditable **including by the edit that would have removed the dead
  arm**. The emptiness is caught at fire time as `last_fire_error`, which is the column that exists
  for exactly this. `aDeadScopeArmIsNotAFieldError` pins it and `SK-M8` measures it — it is the
  mutation a reader is most likely to make, because it looks like a missing check.
  What **is** refused is a scope with **no arm at all**: that is knowable now and can only ever create
  empty runs.
- **An empty resolved scope on a given night creates NO run.** `RunEdit` refuses an empty run anyway,
  and a run with 0 of 0 renders **as though it had finished** — 0 of 0, 100%, complete. The
  alternative (create the empty run so the cadence stays visible) was rejected for that reason.
  Somebody will ask why a Monday is missing from the run list; the answer is on the schedule's row.
- **`isActiveMember`, never `resolve`.** `app_user` is instance-level (ERD §0.1's exception list), so
  "the account exists" accepts a user id belonging to **another client entirely** — and here that
  would hand a client's testing to a stranger *nightly*. `ACTIVE`, not merely present.
- **One cron parser, `CronSchedule`, called by both the validator and the poller**, so the write path
  and the fire cannot disagree about what an expression means. Spring's six-field `CronExpression`,
  already on the classpath — a second cron dialect in one application is two answers to one string.
  **Five fields is refused**: it is the other dialect and accepting it shifts every field by one.
  The arithmetic happens **in the schedule's zone**, which is the whole reason `zone_id` is a stored
  column: "every weekday at 07:00" is a wall-clock statement, and UTC is right for half the year and
  silently an hour out for the other half.
- **`nextAfter` may answer EMPTY, and that is a real answer.** An expression pinned to a date that has
  passed yields nothing further; the column is nullable for it and the screen says **"Will not fire
  again"** rather than a blank cell, which reads as *"loading"* or *"soon"* — both false.
- **A real `DELETE`, no `deleted_at`.** A soft delete exists to keep a record of work and a schedule
  records none: the runs it made are ordinary runs and outlive it. The audit row keeps the act.
- **`test_run_source_check`'s name was not guessed.** It is inline in `V1`, so Postgres named it
  `<table>_<column>_check` — and **five migrations in this repository have already dropped a
  constraint minted that way by that exact name** (`V22`, `V27`, `V32` twice, `V39`). Those precedents
  are the evidence; a `DROP CONSTRAINT` naming something that does not exist fails `V68` on first boot
  in every environment at once.

---

## THE FRONTEND HALF IS ONE SCREEN AND ONE LABEL MAP

**`runSourceLabels` is the part that would have shipped broken.** `RunListPage` rendered
`run.source === 'INGESTED' ? 'from CI' : 'manual'`, which was correct while there were two doors.
Under `SCHEDULED` the else-branch becomes a lie: **every scheduled run would be labelled "manual"
beside a `createdBy` of nobody** — precisely the false attribution the whole actor split exists to
avoid — and it **typechecks, lints and passes every test**. It is now
`satisfies Record<RunSource, string>` in `lib/labels.ts`, which makes the *next* member a compile
error until it is labelled. `RunSource` is named in `api/types.ts` for that `satisfies` to bind to,
`RunStatus`' arrangement.

`CaseHistoryPage.tsx`'s `entry.source === 'INGESTED'` was **left alone** with a comment saying so: it
is a boolean *about ingestion*, not a label, and it reads correctly for a scheduled row.

**`RunSchedulePage`** (`/projects/:projectId/run-schedules`, lazy, beside the run routes but **not
nested under them** — a schedule outlives every run it makes). `DataRegion` for the state matrix,
`Identifier` for the cron expression and the run key, the enabled-only filter in the **URL**, and
`PermissionGate` on `run:create` **disabling rather than hiding** the three writes (§31 — §242's
hidden audit entries were an argued exception about a screen whose subject is who did what; this
screen's subject is a cadence).

Three renderings carry the area's decisions and each is asserted:

- **`lastFireError` is an `Alert` on the row**, not a caption or a tooltip. It is the only place the
  poller's reason for making no run is ever visible, and a schedule that stops silently is the failure
  mode of scheduling anything.
- **`nextFireAt === null` renders "Will not fire again"**, never a blank cell.
- **The Owner column is headed *Owner*, not *Created by***, because a reader who carried that name
  onto the run list would be reading the lie this whole area is about.

**The save states the WHOLE schedule.** The `PUT` is a total replacement and the server reads an
omission as a clear, so the dialog sends back `assigneeId`, `configurationId` and the plan/case/suite
arms it shows **no control for**. That is §244's rule on a screen showing fewer fields than the body
carries, and the test asserts the whole body rather than today's off-screen fields.

The zone control is a **curated picker, not free text**: a mistyped zone is a 400 nobody can diagnose,
and `Europe/Sofiaa` looks exactly like `Europe/Sofia`. Not `Intl.supportedValuesOf('timeZone')` — it
is not in this project's `lib` (`ES2023`). **The schedule's own value is always offered first**, or
editing a schedule set outside the list would silently move it on save, which is the total-statement
rule turning a curated list into data loss.

---

## WHAT HAS NO OBSERVER — STATED HERE, NOT ONLY IN THE QUEUE

A row in `docs/verification/backend-mutation-checks.md` marked **expected green** means *no observer
exists*, not *this passes*. Two of them are this area's real holes and belong in the narrative, because
a reader who only sees the queue will read green as coverage:

- **`§247 SK-M6` — nothing drives `RunScheduleScheduler.fireDueForAllCompanies` at all.** The poller is
  disabled in `IntegrationTestBase` (it creates runs), `AsyncCompanyContextTest` covers the *boundary*
  rather than the bean, and `RunScheduleFiringTest` drives the firing service directly. **So the
  loop's per-company `continue` is unobserved**: one company throwing must not cost every company
  after it, and nothing in this build says it does not. Closing it needs a context with the property
  on and a company whose fire throws. This is the one gap in the area that could lose a customer's
  scheduled runs silently.
- **`§247 SK-M5`'s run-count half is expected green.** Advancing from the missed slot instead of from
  the moment of firing reddens `aMissedSlotIsNotCaughtUp`'s `nextFireAt` assertion — but **not** its
  run-count assertion, because one `fire` call still creates one run. The loop that would produce a
  week of backdated runs is the *poller's*, and the poller is not driven. The no-catch-up decision is
  therefore pinned at the *arithmetic* and not at the *outcome*; the outcome is `SK-M6`'s gap wearing
  a different hat.

Two smaller ones, recorded at their rows: **`SK-M20`** (nothing in this repository compares two
permission literals across two services — the same instrument gap `CaseStatusService.KEYS` records)
and **`SK-M22`** (no fixture names a schedule near `RunEdit.MAX_NAME`, so `nameFor`'s front-truncation
is unobserved; without it a schedule saved right up to the cap fires into a 400 for ever).

**And the inline `source` enum is compared to nothing.** `ContractEnumTest.OWNERS` covers **named**
enum schemas; `source` is declared inline at two sites, so `RunSource` gaining a third member was
checked by no rule in either direction. Pre-existing, widened by one member here, written down rather
than fixed.

---

## OWED

- **No CLI and no MCP surface.** Neither `packages/cli` nor `packages/mcp` offers schedules — out of
  scope per the master plan's file list, and adding a write arm obliges `writeConformance.test.ts` in
  both. **An agent that can create a run and not a schedule is a gap a reader will notice**, and it is
  the stepped plan's open question 4, recorded here as owed rather than left implicit.
- **No rail entry.** `/projects/:projectId/run-schedules` is reachable by URL only. The browser rows
  say so; adding it is a `AppShell` edit this area did not take.
- **The scope control offers tags only** and preserves the plan/case/suite ids invisibly.
  `§247 SK-11` asks the live pass whether that is acceptable, with the two alternatives named (reuse
  the run dialog's full picker, or a read-only summary line). Neither is built.
- **No index on `(company_id, project_id)` beyond `run_schedule_project_idx`**, and **nothing measures
  either index's value** — §241-A's standing gap applies here: `companyIdLeadsCompositeIndexes` gates
  the column order and Flyway gates the presence, and removing the index entirely would make neither
  complain.

---

## FIXTURE PREFIX AND IDS

`RunScheduleEndpointTest` and `RunScheduleFiringTest` take **`3d…`**, pre-assigned and verified unused
on 2026-08-22 across `backend/src`, `frontend/src` and `packages/*/src` (the stepped plan proposed
`e7`; `3d` was assigned by the batch instead). `CronScheduleTest` touches no row and takes no prefix.
Verification ids are **`§247 SK-*`** (browser, lane `A3`) and **`§247 SK-M*`** (backend mutation), both
grepped against every existing row id before minting.
