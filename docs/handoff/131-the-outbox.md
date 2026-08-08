# §131 — The outbox, and the rule the schema had already made

**Date:** 2026-08-08 · **Decision round 3's step 9 (#52), the round's last** ·
**Plan:** [`docs/plans/2026-08-08-the-outbox.md`](../plans/2026-08-08-the-outbox.md)
**Two phases, no migration, no contract change.** Every column, `CHECK` and index this uses has been
in `V1` since M0.

## What was broken

`outbox` was a `V1` table with a `COMMENT` and no code. That alone is the shape this round exists to
close, and it is not the interesting half. The interesting half is that **the code made a promise
about it** — `CompanyScopedEvent`, since M0:

> *"a handler that fails must land there rather than in a `CompletableFuture` nobody joins. Wiring
> that up belongs to the module that publishes the first real event."*

There was no first real event. **Three later files quote the absence as a premise for their own
design** (`ExecutionSource`, `RollupRefreshService`, the M2.5 slice plan §285) — *"zero
`CompanyScopedEvent` implementations, no outbox consumer, and `@Async` handlers whose failures have
nowhere to report"* — so the gap was not merely unbuilt, it was load-bearing for four other
decisions.

## THE FINDING: the outbox is not only durability — it is the only way two feature modules may exchange a shape

Plan §3 allows a cross-module dependency only toward a foundation module, and
`ArchitectureRulesTest.crossModuleDependenciesPointOnlyAtTheFoundation` reads
`getDirectDependenciesFromSelf` — **every** import, with no exemption for an event type. So
`reporting` cannot import `com.tcms.execution.ResultRecorded`, and **the obvious Spring shape — a
typed event and a `@TransactionalEventListener` — is unbuildable between two feature modules in this
codebase at all.**

That is why `event_type` is `TEXT` and `payload` is `JSONB`: nothing crosses the boundary but a
string and some bytes. The `V1` table was designed for reliability and turns out to be the answer to
an architectural question as well. The cost is real and stated where it is paid — the producer's
shape and the consumer's are two declarations of one thing and the compiler cannot compare them, so
what keeps them honest is a test on each side.

## Phase 1 — the mechanism (`tenancy`)

Eight types, and the design is mostly forced rather than chosen.

**`V1`'s partial index decides the status semantics.** `outbox_pending_idx` is
`ON (next_attempt_at) WHERE status = 'PENDING'`, which settles a question every outbox has to answer:
*what status does a row wear between a failed attempt and its retry?* Anything but `PENDING` and the
retry is invisible to the only index the schema has for finding it. So a retrying row **stays
`PENDING`** with `next_attempt_at` pushed out — the column is the schedule and the status is only
"is this still ours to deliver".

That leaves `FAILED` and `DEAD` meaning genuinely different things, and both are written:

- **`FAILED`** — the ceiling reached against something that might stop happening. Replayable.
- **`DEAD`** — structurally undeliverable: no handler claims the type, or the payload is unreadable.
  Retrying cannot help.

**An unclaimed event type is `DEAD`, never `SENT`.** A `SENT` row that nobody consumed is a claim
with no evidence behind it, rendering identically to a delivered one — §47's empty panel, §53's
silent card, §72's every-run-not-started, §81's mock. A deployment with the consuming module
disabled *should* accumulate visible dead rows.

**Three transactions per event, and the handler is inside none of them.** Both simpler shapes fail:

- *One transaction around everything* — the handler's own exception marks it rollback-only, so the
  attempt count and the error message it was supposed to record are discarded with it. The row
  retries for ever and the table says nothing ever went wrong. This is also why `OutboxLedger` is a
  second bean: a `@Transactional` method called from another method of the same bean is not
  intercepted at all (§116's defect).
- *A lock held across the handler* — holds a pooled connection across somebody else's network call,
  which is the exact thing `V1`'s comment is about.

**What replaces the long lock is a lease.** `claim` bumps `attempts` and pushes `next_attempt_at`
out; the status stays `PENDING` throughout the attempt. A crash therefore costs one interval instead
of a row — whereas a `SENDING` status is one only a live process can leave, so every such row is
stuck for ever after a restart with nothing to notice it. The price is **at-least-once**, which
`OutboxHandler` carries as a written contract rather than a hope. `attempts` moves at the *claim*
and not at the failure, which is what makes a payload that kills the process exhaust rather than
loop.

**Two refusals, both for defects with no symptom.** `Outbox.record` outside a transaction — the row
still saves, delivery still works, every test still passes, and the atomicity the whole pattern
exists for is simply gone. And `dispatchDue` inside one, which would collapse the three transactions
into the poisoned shape above.

## The design the build changed twice

**Several handlers may claim one event type.** The first draft failed the context at startup on a
duplicate, reasoning that it is a code error and therefore checkable before anything runs. That was
right about code and wrong about the product: phase 2's event moves both the fact log in `reporting`
and the health signals in `repository`, neither module may call the other, and one row is how both
are told. Fan-out is the feature. The price is stated where it is paid — they share a row, so one
handler's failure retries all of them, which is affordable only because every handler is required to
be idempotent anyway.

**`recordCoalesced`, and it takes no payload and no aggregate.** A tester works through a run and
records fifty verdicts; one ingestion writes a thousand executions in one transaction. Both
consumers are whole-company recomputes that take no arguments by design, so an event per result is
the cost of saying one thing fifty times. Two notices that a company's results moved **are** one
notice.

It has no payload because there is nothing truthful to put in one: a coalesced event stands for
every change since the last delivery, so naming the first of them is a fact about one presented as
the reason for all — and a handler reading `runId` would quietly scope a whole-company recompute to
one run. The parameter does not exist, so it cannot be added by accident.

**And it folds only into a row with `attempts = 0`.** A claimed row may already have read the
database *before* the write now being recorded committed; folding into it would mean the newest
change is not projected until the next scheduled tick — the staleness the event exists to remove,
restored silently. Unclaimed means read by nobody, so it can still stand for both.

## Phase 2 — the producer and the two consumers

`execution.ExecutionService.record` and `IngestionService` both record
`execution.results_changed`, in the same transaction as the verdicts. `reporting` refreshes the fact
log and then the rollups (§101's order, in **one** handler precisely because two would run in an
undefined order); `repository` recomputes the health signals.

**Every line of this was pre-authorized by the code it changes.** `ExecutionSource`'s header:
*"Landing `ResultRecorded` later adds a handler that calls the same service and changes nothing
here."* It did, and it does not — the handler is four lines and the schedulers stay, because they
are the rebuild path an event-only design does not have. `CaseHealthRecomputeService` says the same
and names the half an event can never cover: **`LONG_UNEXECUTED` becomes true through the passage of
time, not through anything happening.** What the event buys is latency and only latency.

The handlers share their schedulers' `@ConditionalOnProperty`, so `tcms.reporting.enabled=false`
means the fact log does not move rather than that it moves by a different route.

**What is deliberately NOT the first producer: the invitation email.** It looks like the perfect
candidate — a third-party network call, one attempt today, failure logged and lost (§59's
undeliverable link, one layer down) — and it **cannot** go through the outbox. Only a *digest* of
the invitation token is stored, so a payload carrying the accept URL would put a live credential at
rest in a table an operator browses and a backup copies; and a dispatcher could not reconstruct the
secret from the hash anyway, so a "retry" would have to **mint a new token**, which is not a
redelivery of the same artifact but the issue of a different one (§122's grant-is-a-state /
invitation-is-an-artifact distinction, one slice over).

## Also

- **`OutboxEvents` puts the event names in a foundation module**, which is a real cost — adding an
  event type means editing `tenancy` for a feature module's reason — for one property: there is
  exactly one spelling of each name, on both sides of a boundary the compiler is forbidden to see.
  The naming convention carries a behaviour: a past-tense verb is an occurrence (`record`), a
  *changed* is a state (`recordCoalesced`), and getting it backwards is not a compile error.
- **The company loop is `ExecutionFactScheduler`'s, unchanged**, and for its stated reason: the
  table is under `FORCE ROW LEVEL SECURITY`, so an instance-wide query returns zero rows and logs a
  successful tick having delivered nothing.
- **No `SKIP LOCKED`**, which the plan called for. The claim's lock is held across a read and one
  update; skipping matters when the lock spans the work, and here the *lease* does that job.
- **Four javadocs corrected** — `CompanyScopedEvent`, `ExecutionSource`, `RollupRefreshService` and
  the plan's own row — because each asserted an absence this slice ends. §100's rule, fifth sighting.
## THE SECOND FINDING: a defect the whole suite was structurally unable to see

The two handlers were first written as `reporting.ResultsChangedHandler` and
`repository.ResultsChangedHandler` — **one simple name, two packages, both `@Component`**. Spring's
default bean name is the simple class name, so the two collide and the context **fails to start**:
`ConflictingBeanDefinitionException`, taking every `@SpringBootTest` with it.

**And no test in this repository would ever have reproduced it.** Both handlers carry
`@ConditionalOnProperty`, sharing their schedulers' switches — and `IntegrationTestBase` turns
**both** of those properties off, for reasons recorded in §120 and §101 that have nothing to do with
this. Conditions are evaluated during scanning, so neither bean was ever registered, so the two
names never met. The collision would have occurred exactly once: **on the first boot of a real
deployment**, where both modules are enabled. 2200 green tests, and a context that does not start.

The names are `FactRefreshOnResultsChanged` and `HealthRecomputeOnResultsChanged` now, which also
say what they do rather than what they listen to. **The rename is not the fix** — this repository's
own rule is that a catalogue-wide invariant needs a computed check rather than a careful reader — so
`ArchitectureRulesTest.springStereotypesHaveDistinctSimpleNames` reads the *bytecode*, not a running
context, precisely because a context-based check inherits the conditional-evaluation blindness that
hid the defect in the first place.
- **NO RED RUN in either phase.** Stated rather than omitted: 19 tests passed on their first
  execution, and the only two build failures were a missing `import java.util.ArrayList` after the
  fan-out change and the bean-name collision above.

## Not built, and why

**No operator screen and no endpoint.** Reading and replaying a `FAILED` row is operator-console
work, which §123 folded into M6. `DEAD` and `FAILED` rows are currently visible only in `psql` —
recorded here so it is a known absence rather than a discovery.

**No notifications, no webhooks, no SMTP events.** M4. This slice builds the transport those need
and delivers none of them.

**Built and unverified** — 21 mutation rows `OB-1`…`OB-21`, **none run**, per §65. No browser rows:
nothing here is visible in a browser, and the latency it does improve is invisible except as a
number that used to be older.
