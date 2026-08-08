# The outbox — decision round 3's step 9 (#52)

**Date:** 2026-08-08 · **Status:** PROPOSED, awaiting approval · **Round-3 step:** 9 of 9, the last
**Sequenced after #46** by [decision round 3](2026-07-30-tcms-high-level-plan.md#8) — *"widest blast
radius of the round, and by then it lands on a Linux gate that has been green for eight steps, so a
reddening is attributable."*

> **#46 IS DONE, AND HAS BEEN SINCE 2026-08-06** — discovered on 2026-08-08 when this slice went to
> record the deviation of proceeding without it. The `shanks` runner is registered and online with
> the `tcms-lab` label, and `tests.yml` has *run the full backend suite on Linux twice and passed
> both times* (`workflow_dispatch` 2026-08-06 21:44, `783fab1`; the nightly `schedule` 2026-08-08
> 02:59, `c73d27e`, `BUILD SUCCESSFUL in 6m16s` with `:test` executed). Every document in this repo
> still described the lane as written-but-inert. **The sixth instance of a blocker note outliving
> its blocker**, and the first where the thing said to be missing had been working for two days.
> §7 records what that means for this slice.

---

## 1. What is actually broken

`outbox` is a `V1` table with a `COMMENT` and no code. That alone is the shape this round exists to
close, but it is not the interesting half. The interesting half is that **the code makes a promise
about it**:

> `CompanyScopedEvent`, since M0: *"a handler that fails must land there rather than in a
> `CompletableFuture` nobody joins. Wiring that up belongs to the module that publishes the first
> real event."*

There is no first real event. Three later files quote the absence as a premise for their own design
(`ExecutionSource`, `RollupRefreshService`, the M2.5 slice plan §285) — *"zero `CompanyScopedEvent`
implementations, no outbox consumer, and `@Async` handlers whose failures have nowhere to report"* —
so the gap is not merely unbuilt, it is **load-bearing for four other decisions**.

## 2. The constraint that decides the design

**A cross-module dependency may point only at a foundation module, and that rule has no exemption
for an event type** (`ArchitectureRulesTest.crossModuleDependenciesPointOnlyAtTheFoundation` checks
`getDirectDependenciesFromSelf`, which is every import). So `reporting` cannot import
`com.tcms.execution.ResultRecorded`, and the obvious Spring shape — publish a typed event, handle it
with `@TransactionalEventListener` — **cannot be built between two feature modules in this codebase
at all** without either putting domain event types into `tenancy` or widening the rule.

That makes the outbox more than durability. **It is the only mechanism by which two feature modules
can exchange a shape:** the event type is a `TEXT` column and the payload is `JSONB`, so nothing
crosses the boundary but bytes and a string. The `V1` table was designed for reliability and it
turns out to be the answer to an architectural question as well.

The second constraint is RLS. `outbox` is in `V1`'s `company_owned` list, under `FORCE ROW LEVEL
SECURITY`, so **a dispatcher that scans every pending row across the instance sees zero rows and
logs a successful tick having done nothing** — `ExecutionFactScheduler`'s exact trap, and its answer
is reused verbatim rather than re-derived: loop `company` (one of the few tables not under RLS),
open a context per company, one transaction each, catch per company.

## 3. Phase 1 — the mechanism (`tenancy`, no migration, no contract change)

`tenancy`, because the table is company-owned, because `CompanyScopedEvent` already lives there, and
because every module must be able to write to it — a mechanism in a feature module is one every
other feature module may not use.

| Type | Role |
|---|---|
| `OutboxEntry` | JPA entity over the `V1` table. `CompanyFilter` like every other company-owned entity. |
| `OutboxRepository` | `@Transactional(readOnly = true)` on the interface — the project's rule, and the one whose absence returns zero rows under RLS. |
| `OutboxMessage` | `record(companyId, aggregateType, aggregateId, eventType, payload)`. A `CompanyScopedEvent`, so the envelope contract holds for the first time in production code. |
| `Outbox` | The writer. `record(OutboxMessage)`, called **inside the producer's transaction** — that is the whole point of the pattern: the row commits with the work or not at all, and there is no window in which the work happened and the notice was lost. |
| `OutboxHandler` | The consumer port: `String eventType()` + `void handle(OutboxMessage)`. Implemented in feature modules; resolved by string, so no type crosses a boundary. |
| `OutboxDispatcher` | Claims due rows for one company and dispatches them. |
| `OutboxScheduler` | The company loop, `ExecutionFactScheduler`'s shape and its four stated properties. |
| `OutboxProperties` | Batch size, attempt ceiling, backoff schedule, `enabled`. |

**The four statuses, and why `FAILED` and `DEAD` are not the same.** `V1`'s `CHECK` allows
`PENDING · SENT · FAILED · DEAD` and its partial index is `ON (next_attempt_at) WHERE status =
'PENDING'` — which decides the semantics rather than leaving them to taste, because a retryable row
parked in any other status is invisible to the index the schema shipped for it:

- **`PENDING`** — due now, or scheduled for a retry (`next_attempt_at` bumped, `last_error` set).
  A retrying row stays `PENDING`. This is the only status the index covers and the only one the
  dispatcher reads.
- **`SENT`** — a handler returned normally.
- **`FAILED`** — the attempt ceiling was reached on a **transient** failure. Terminal for the
  dispatcher, replayable by an operator once the cause is fixed.
- **`DEAD`** — structurally undeliverable: no handler claims the event type, or the payload cannot
  be read. Retrying cannot change the outcome, so it never becomes `PENDING` again.

**No handler is `DEAD`, not `SENT`.** A deployment with `tcms.reporting.enabled=false` will
therefore accumulate dead rows, and that is the intended reading — an event nobody consumed is a
fact worth being able to see. The alternative, marking it `SENT`, is a claim with no evidence
behind it that renders identically to a delivered one, which is the failure this repository has
paid for four times (§47, §53, §72, §81).

**Claiming is `FOR UPDATE SKIP LOCKED`** even though one instance runs today. A second instance is
an operational decision, not a code change, and the version of this that double-sends is the one
that looks fine until the day somebody scales it.

**Backoff** is `1m · 5m · 15m · 1h · 6h · 6h …` with a ceiling of 8 attempts, all in
`OutboxProperties` — a literal schedule beside the retry is a deployment's policy hard-coded in the
one place a self-hosted operator cannot reach.

Tests: two companies (a dispatcher that sees another company's row is the leak the loop exists to
prevent), a handler that throws lands `PENDING` with `attempts = 1` and a future `next_attempt_at`,
the ceiling lands `FAILED`, an unclaimed type lands `DEAD`, `SKIP LOCKED` under two concurrent
claims, and the write is inside the caller's transaction (a rolled-back producer leaves no row).

## 4. Phase 2 — the first producer and the first consumers

A mechanism with no producer is the writerless shape one level up — the thing §121 phase 3 deleted a
seam for. So phase 2 lands a real path end to end:

**Producer:** `execution.ExecutionService.record` writes `execution.result_recorded`
(`aggregateType = run`, `aggregateId = runId`, payload = the run and case ids) in the same
transaction as the result.

**Consumers:** `reporting` refreshes that company's `execution_fact` + rollups; `repository`
recomputes that company's `case_health`. Both call the services the schedulers already call.

**This is pre-authorized rather than invented.** `ExecutionSource`'s own header says it in as many
words: *"Landing `ResultRecorded` later adds a handler that calls the same service and changes
nothing here"* — and the schedulers **stay**, because they are the rebuild path an event-only design
does not have. What changes for a user is latency: the plan page's headline and the board stop being
up to 15 and 60 minutes stale after a result is recorded.

**What is deliberately NOT the first producer: the invitation email.** It looks like the perfect
candidate — a third-party network call, currently one attempt, failure logged and lost (§59's
undeliverable link, one layer down) — and it **cannot** go through the outbox. Only a *digest* of
the invitation token is stored (`InvitationTokens`: a salted hash cannot be queried by, so the
secret exists in memory for the length of one request and nowhere else). An outbox payload carrying
the accept URL would put a live credential at rest in a table an operator browses, and a dispatcher
that read the row back could not reconstruct the secret from the hash anyway — a "retry" would have
to **mint a new token**, which is not a redelivery of the same artifact but the issue of a different
one (§122's grant-is-a-state / invitation-is-an-artifact distinction, one slice over). The mail
stays a fire-once after-commit send, and `InvitationMailer`'s header gets the sentence saying why,
so the next reader does not have to re-derive it.

## 5. What phase 2 must not become

- **Not a second write path.** A handler calls the same service a scheduler calls, with no
  behaviour of its own; if a handler needs logic, that logic belongs in the service and the
  scheduler is wrong without it.
- **Not a removal of the schedulers.** The event is an accelerator; the tick is the rebuild path.
- **Not idempotency by hope.** Both recomputes are already idempotent by primary key, which is what
  makes at-least-once delivery legal here. A future handler that is not must say so.

## 6. Out of scope, stated rather than left looking like an oversight

- **No operator screen and no endpoint for the outbox.** Reading and replaying a `FAILED` row is
  operator-console work, which §123 folded into M6. Recorded as unbuilt, with the note that `DEAD`
  and `FAILED` are currently visible only in `psql`.
- **No notifications, no webhooks, no SMTP events.** M4. This slice builds the transport those need
  and delivers none of them.
- **No `@Async` anywhere new.** The dispatcher is a scheduled poller, which is what makes a restart
  mid-dispatch cost nothing.

## 7. Gates

`cd backend && ./gradlew build` per phase on macOS, **and the nightly Linux run is now a real second
observer** — which is what the round's ordering was for. #52's own text names
`AsyncCompanyContextTest` as *the* suite whose timing a Linux gate sees differently, so this is the
step that most wanted the lane and it turns out to have it.

Push before relying on it: the nightly runs the default branch's workflow at 01:17 UTC, and
`workflow_dispatch` (`gh workflow run tests.yml`) is the on-demand form. **CI itself still runs no
backend tests** (`ci.yml` is `-x test`); that is unchanged and separate.

Worth stating anyway: this slice adds **no `@Async`**. Everything it schedules is a fixed-delay
poller, so the platform-timing surface it adds is small — which is a fact about the design, not the
reason it was safe to build.

Verification: rows appended to `docs/verification/backend-mutation-checks.md`. **No browser rows** —
nothing this slice ships is visible in a browser, and the latency improvement it does produce is
invisible except as a number that used to be older.

## 8. Phase list

| Phase | Content | Gate |
|---|---|---|
| 1 | The mechanism in `tenancy` + its tests. No migration, no contract change, no producer. | `./gradlew build` |
| 2 | `execution` produces; `reporting` and `repository` consume. `CompanyScopedEvent`'s and `InvitationMailer`'s headers corrected. | `./gradlew build` |
| 3 | Docs: handoff §131, `be-java.md` orientation cache, the mutation queue rows, round 3 step 9 closed in the plan. | — |
