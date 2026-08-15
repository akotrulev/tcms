# §218 — The arm with no caller, and the first secret that had to be readable back

**Four phases**, every stack: `backend/` (a thirteenth module, `V54`, a controller), `openapi.yaml`
plus both `schema.d.ts` copies, `frontend/` (a settings screen and a dialog), and this. **No
permission was added and none moved** — `integration:manage` has been seeded since `V3` and Lead-only
since `V52` left it alone — so the catalogue is still 55 keys, the roles spec's §A3 is untouched, and
both matrix copies and `matrixCopies.test.ts` never came into it. **No `packages/` surface**: neither
the CLI nor the MCP server offers notification channels.

Phases 1–2 were built in the `worktree-notifications` concurrency worktree alongside §217 on
`master`; phase 3 was merged at `c9cadb6`. The plan is
`docs/plans/2026-08-15-notifications.md`.

---

## The finding the slice was picked for: `Outbox.record` had no product caller for eighty sections

`Outbox#record` — the payload-carrying, past-tense arm of the outbox — was built in §131 and called
by nothing but `OutboxDispatchTest` until this slice. It was
`ArchitectureRulesTest.UNEXERCISED_BY_DECISION`'s single entry. **That entry is gone and the map is
now empty**, kept as a map so the next unexercised seam still costs an argument somebody has to
defend.

Three separate files predicted this slice without naming it, and all three are corrected rather than
left standing: `Outbox#record`'s *"what the first real caller owes"*, `OutboxMessage#payload`'s
*"stated in the conditional because no such pair exists (§153)"*, and `OutboxEvents`' *"the first
name whose handler wants to know **which** row moved"*.

**Why it took eighty sections, and this is the rule for choosing an arm.** Every event type before
these was consumed by a *whole-company recompute*, which takes no arguments — so there was nothing
truthful for a payload to carry and `recordCoalesced` was right both times. A notification is the
exact opposite: the payload **is** the message, because a consumer in `integration` may not read
`test_run` or `work_item` to find out anything the event did not say. *"Something in this company
finished"* is not a notification.

### The debt was a pair and the payment is three tests

`Outbox#record`'s javadoc named what the first caller owed: a producer-side **and** a consumer-side
test pinning the payload, because plan §3 forbids the two modules from sharing a class and
`ArchitectureRulesTest` reads every import, so the compiler cannot see across that boundary. Two
records declare each payload, one per side.

| Test | Fails when |
|---|---|
| `com.tcms.execution.RunCompletedPayloadTest`, `com.tcms.work.ItemAssignedPayloadTest` | the producer's field names or values move |
| `com.tcms.integration.RunCompletedPayloadTest`, `…ItemAssignedPayloadTest` | the consumer stops requiring a field, or starts requiring a new one |
| `com.tcms.integration.NotificationDeliveryTest` | **the two no longer meet** |

**The third one is the one that matters, and it is not what the debt asked for.** Two pins updated in
step redden neither — which is precisely what renaming a field looks like. So the third test drives
the real service, writes a real `outbox` row, runs the real dispatcher and asserts a delivery
attempt. **Generalised: a pair of pins on two sides of a boundary is a description, not a check. The
check is the one that crosses.** If a fourth event type is added, all three are the cost.

The consumer pin's asymmetry is deliberate: an *unknown* field is tolerated (a producer may widen
before every consumer wants it), an *absent* one is not — it deserializes to null or zero in silence
and a renderer then says "0 failures" about a run that failed.

---

## The first recoverable secret in this codebase

A Slack or Teams incoming-webhook URL **is** the authentication for the room it posts to. Every other
credential this product holds is digested (`V40`'s API tokens) because it only ever has to be
*compared*. This one has to be **used**, so a digest is unavailable and the value must come back out.

AES-GCM, nonce prepended to the ciphertext, key from **`${TCMS_INTEGRATION_SECRET_KEY}` — no colon**.
That is §108's lesson taken literally rather than cited: `${VAR:?msg}` is a *shell* form Spring reads
as a **default**, which is how this instance signed production sessions with a string from this
repository for a year. `IntegrationProperties` refuses anything that is not Base64 of exactly 32
bytes rather than hashing a passphrase, because a short passphrase makes a perfectly valid AES key
and nothing the running product does would ever reveal it.

**It is a required property with no default, so every context in the suite needs one** —
`IntegrationTestBase` supplies it beside the JWT secret, with the same warning attached: that
override replaces the shipped expression outright, so no test can see a defect in its *text*.
`ConfigConventionTest` is what reads that.

Four rules, each with a test:

1. **Never returned.** `NotificationChannelView` has no `secret` component. `secretHint` — the last
   four characters, masked — is all that leaves the module.
2. **Never in an outbox payload.** Asserted in the producer pin, not only stated in prose.
   `OutboxEntry#getPayload()` has said *never a credential* since §131.
3. **Never in a delivery-log error.** `SecretRedaction` makes two passes — the exact secret, then any
   `http(s)://` loses its path — before anything durable sees the text. **This is the leak that is
   invisible in a green run:** an HTTP client's message quotes the URL it posted to, nothing fails,
   and a live credential sits in a table an operator browses and a backup copies.
4. **Never in `outbox.last_error` either — and this is the one a reader would not predict.** The
   redaction on the way into `notification_delivery.error` is the obvious half. The other durable
   place is the *outbox row*, because `OutboxDispatcher` writes the thrown exception's message into
   it, and that is code in a different module which cannot know a credential passed nearby. So
   `NotificationDeliveryFailure` names channels and **carries no cause at all**.
   `theCredentialIsInNeitherErrorColumn` checks both columns, because checking one would have read
   as covering the class.

`ChannelConfig` is the only place a plaintext URL exists, it is built immediately before one
delivery, and it has **no `toString` override on purpose** — a redacting one would make the type look
safe to interpolate, and the next component added to it would not be.

---

## The read gate, which is the reusable half of phase 2

Six operations under `/projects/{projectId}/notification-channels`. **Both halves cost
`integration:manage`, reads included — and phase 1's javadoc argued the opposite.** It is struck
through in place rather than deleted, per the working agreement.

`getProjectSettings` is deliberately readable by anybody who can see the project, and it is the
nearest precedent, so the argument had to be made rather than assumed. Its reason is that *every*
client has a decision to make from it — whether to render a review panel — while none of those
readers is an administrator. **Neither half transfers here.** Nothing in this product branches on
whether a channel exists, so there is no reader who needs the answer and cannot have the key; and
what the reads *serve* points the other way — `secretHint` is the tail of a live credential,
`recipients` are people's addresses, and a delivery row's `error` is whatever a remote endpoint said.

So the reads went to `ProjectService.readGranted`: **visibility still answers 404, the permission
half answers 403**, which is `report:view`'s split (§87) and the reason that method exists.

> **Generalised: read the gate off what the response carries, not off the screen the endpoint belongs
> to.**

Three more placements, recorded because each had a plausible alternative:

- **The delivery log hangs off the CHANNEL** (`…/{channelId}/deliveries`), not the project. A
  delivery is only ever asked about as one — *is this webhook working* — and deleting a channel takes
  its history with it, so a project-level log would outlive the channels it names.
- **Subscriptions travel in the channel body as a complete reconciled set.** That is
  `updateProjectSettings`' argument plus the half it does not have to make: an upsert-only
  sub-resource can add and change a subscription and can **never take one away**.
- **No `Idempotency-Key` on the create**, on `POST /projects/{projectId}/conditions`' terms — there
  is no idempotency record behind the path — and §205 is what makes declaring one anyway the
  expensive direction: a client that trusted the header would replay a timed-out create and get a
  second channel that delivers every notice twice.

---

## §31 answered the other way, because the DATA is what is withheld

The SPA's third `/settings/` screen, and **the first in this product whose reads are gated**.
`ReviewSettingsPage`'s render-it-read-only pattern is therefore unavailable: there is no read-only
view to render, because the list 403s. Four parts, one decision:

| | |
|---|---|
| The rail item | **Stays.** A missing entry teaches a contributor that this product cannot send notifications — a lie about the product rather than about their access. |
| The page | Renders a sentence naming who can configure channels. **Never an empty table**: "no channels" is a fact this caller is not entitled to. |
| The reads | `enabled` is driven by the gate's verdict. **Nothing is fetched at all.** An ungated fetch 403s and raises a global "Something didn't load" over a screen working exactly as designed. |
| While pending | Renders nothing. `Gate.can` is fail-closed while loading, which is right for a button and would flash a denial at a Test Lead on every visit. |

**The rail item is the load-bearing part of the argument**, and it is the half that inverts §31's
usual reading. §31 is *disable, don't hide*, and its usual instrument is a live control rendered
inert. Here there is no control to render inert and no data to render it over — so the only thing
left that can carry the product's shape is the navigation entry, and hiding it would be the one
choice that removes information rather than access.

## `secret` absent means unchanged — the one field in this API where an omission is not a clear

Everywhere else a `PUT` is a full replacement and an omitted field is a clear. Here the screen
**never sees a secret** — the read model carries only `secretHint` — so an edit form has nothing to
send back, and the contract inverts the convention to match.

The dialog therefore builds the payload with a spread rather than an assignment:

```ts
...(trimmedSecret === '' ? {} : { secret: trimmedSecret })
```

`secret: value || null` and `secret: value ?? ''` are both the defect, and **the defect has no error
and no symptom**: the save returns 200, the row goes on reading as configured, and delivery stops
hours later at a time entirely unconnected to the edit that caused it. That failure shape is why one
field is worth a section.

The assertion that catches it is on the payload's **key set** —
`expect(Object.keys(body)).not.toContain('secret')` — because `expect(body.secret).toBeUndefined()`
passes for `{ secret: undefined }`, a property that is present.

`kind` is disabled on an edit **and still submitted**: the `PUT` ignores it and the input schema
requires it, so dropping the control is not available either. `subscriptions` is seeded from the
channel and submitted whole, so unticking is how a channel unsubscribes.
`NotificationDelivery.eventType` is a bare string with a fallback-carrying lookup, unlike every other
map in `lib/labels.ts`: the log is **history** and may name an event this instance no longer sends.

---

## The Flyway renumber actually fired, and no gate observed it

This lane pre-assigned **`V53`** per
`docs/plans/2026-07-31-parallel-execution-rework.md`. While phase 1 was building, the `master` lane
landed §217's `V53__external_identity_oidc.sql`, so this migration was renumbered at integration in
landing order and shipped as **`V54`**. **This is the first time this repository has actually had to
do it.**

It was cheap only because of *when*. The migration had never run against anything but Testcontainers,
which migrates a fresh container every time, so no `flyway_schema_history` row anywhere named `V53`.
The renumber was a file rename plus a reference sweep. **The same rename after a single deployment
would have been a repair.**

And the half worth keeping: **nothing observed the collision but a human reading `git ls-tree`.**
`out-of-order` is unset, so a `V53` landing after a `V54` is refused on any already-migrated
database — but two lanes each writing a `V53` produce two *filenames*, merge clean with no conflict,
and pass every test on both branches, **because Testcontainers never has an already-migrated database
to refuse them.** The rule the plan states was right; what it did not say is that the rule has no
observer in this build, and the pre-assignment convention is the entire mechanism.

### `V54` — three tables, and a `V1` column with no writer since M0

Policy count **64 → 67**; `SchemaConventionTest` pins it.

- **`notification_channel`** — unique `(company_id, project_id, name)`. `kind` is `updatable = false`:
  turning a Slack channel into an email one would keep the name and the history while replacing
  everything they were about. `lock_version` **is** a real `@Version` here, unlike `work_item`'s,
  because a channel edit is a field edit typed over what somebody read.
- **`notification_subscription`** — `min_failures` is **a predicate on the event, not an event**.
  Plan line 159's *"run failed threshold"* reads like a fourth event name and is refused as one: a
  `RUN_FAILED` event would have `execution` record a distinct name against a number configured in
  `integration`, which the module layout forbids and which breaks anyway the moment two subscribers
  disagree about what a bad run is. `0` is every run, and **a null failure count matches every
  threshold**, because a filter that silently silenced a channel is indistinguishable from one that
  never fires.
- **`notification_delivery`** — the operator's log **and** the idempotency record. Unique
  `(company_id, channel_id, event_id)`, and `NotificationLedger.beginAttempt` refuses a second
  attempt against a row already `SENT`. Without it one crash between the POST and the settle is a
  duplicate message, and the outbox's retry ladder makes it up to eight. **`event_id` is minted by
  the producer and carried in the payload**, not taken from the outbox row's id — `OutboxMessage`
  deliberately hands a handler no row identity.

**`team.notification_channel JSONB` is dropped.** A `V1` column with no writer, no reader and no
entity mapping — the shape §116, §117, §132 and §151 each found and each treated as a defect. Leaving
it beside a real `notification_channel` table would have been worse than either: two spellings of one
concept, the dead one first in `V1`, and the dead one is what an agent greps first.

## `integration` is the thirteenth module and must never become a foundation module

Declares `{"api", "tenancy", "project"}`. The plan also named `identity` and it is **left off**: a
person's display name arrives through `project.PeopleDirectory`, which is that module's port
precisely so a module which may not import `identity` can still ask *who is this*. **A declaration is
a fact about code that exists, never a forecast.**

It fails §125's second property for a foundation module outright — a notification channel is a domain
concept with a lifecycle, an owner and a permission — so nothing may depend on it, and
`ArchitectureRulesTest.nothingDependsOnIntegration` pins that from the other side. Modulith only
catches a module *declaring* the edge; the leaf rule also catches the composition root and a module
widening its own declaration in the same commit.

**The constraint is the design.** `integration` may see neither `execution` nor `work`, and every
fact it renders belongs to one of them — so the outbox is not one mechanism among several here, it is
the only one. The temptation the leaf rule exists for is specific: calling
`NotificationDispatchService` from `RunStatusService` is one line shorter than recording an event,
and it puts a POST to somebody else's server inside the transaction that closed the run, which is
exactly what `V1`'s comment on `outbox` forbids.

---

## The three things this slice deliberately did not build

- **`work.defect_created`, the plan's fourth event — it has no producer.** `defect:create` is seeded
  in `V3` and there is **no defect entity, table or filing path anywhere in `backend/src`**; defect
  filing is M4 scope behind the `IssueTracker` port and is unbuilt. A constant in a foundation module
  with nothing recording it is the writerless shape one level up from the column this same migration
  drops. It arrives with the defect view, and the subscription enum is the only thing that has to
  change when it does.
- **A project-level delivery log.** Refused for the placement reason above: a delivery is asked about
  as a channel's, and a project-level list would outlive the channels it names once one is deleted.
- **Retry UI.** The outbox already retries on the ladder; an operator-facing replay button needs an
  answer to *"replay into which channel"* that the delivery log does not yet carry, and inventing one
  would mean a second idempotency story beside the one `beginAttempt` owns.

Also not built, and for the record: no Jira, no Git traceability, no generic outbound webhook (they
share the module and nothing else); no per-user notification preferences (channels are project-level;
a person-level digest is a different table and a different unsubscribe story); and **no message
carries a link to the run** — that needs a base URL this module does not have, and borrowing
`tcms.invitation.base-url` would be `identity`'s property read from a module that may not see it.

---

## Gates

| Phase | Gate | Result |
|---|---|---|
| 1 | `cd backend && ./gradlew build` (macOS) | **2768 tests / 0 skipped / 0 failures**, 558 classes |
| 2 | `cd backend && ./gradlew build` (macOS) | **2774 tests / 4 failures — all four pre-existing**, `ArchitectureRulesTest` on §217 phase 2's `IdentityProvider` and `UserIdentity`, reproduced identically with this phase stashed entire |
| 2 | `packages`: build, lint, `test:run` | clean · 0 warnings · **323** |
| 3 | `frontend`: `npm run build`, `npm run lint`, `npm run test:run` (macOS) | clean · 0 warnings · **1373 across 101** (+24) |
| 4 | `cd backend && ./gradlew build` (macOS, on the merged tree) | see below |

**Phase 2's four failures were §217's and §217 phase 3 closed them.** That claim had not been checked
since the merge, so it was re-run for this phase rather than repeated: see the gate row above. No
`frontend/` or `packages/` gate was run for phase 4, because **phase 4 changes no code in either
stack** — it is markdown only, and running them would imply the documents were what passed.

## Verification — written and not run (§65)

`docs/verification/backend-mutation-checks.md` **`§218 NC-1`…`NC-10`** and
`docs/verification/frontend-browser-checks.md` **`§218 NT-1`…`NT-8`**.

`NC-1`…`NC-6` and `NT-1`…`NT-7` were written by phases 2 and 3. Phase 4 read them **as a set** and
found the gap the block itself had already named: *"§218 phase 1 added no rows to this file, and that
is a gap this block does not close."* Every phase-2 row is about the REST edge — the gate, the
precondition, the contract — and **the entire dispatch spine had none**. `NC-7`…`NC-10` close that:
the idempotency gate, the threshold predicate, `COMPLETED`-only, and `enabled`. `NT-8` closes the
other half of it — **`work.item_assigned` was driven by nothing in either queue**, which is a
peculiar shape given that the whole slice is about a payload crossing a boundary and only one of the
two events has ever crossed it outside a unit test.

**`§218 NC-5` is a real gap and stays open.** Nothing in this repository asserts that a credential is
absent from a *response* schema; `ContractConventionTest.pairedSchemasAndRecordsNameTheSameMembers`
compares the two sides to **each other**, so adding `secret` to both the schema and the view
satisfies it. §217's `FD-15` recorded the same missing rule two days earlier on `clientSecret`. **The
check belongs in `packages/`** — `client/src/test/` already parses `openapi.yaml`, and the backend
suite is the one `ci.yml` does not run — and it is written down here as the next slice's candidate
rather than built in a docs phase.

**`§218 NT-6` needs a webhook sink no lane creates.** The block names three options and recommends a
local HTTP sink, because it is the only one that also proves the *body* the adapter posts. The
fixture charter now carries a pointer to it, since a reader preparing the pass meets the charter two
thousand lines before the row.

## What is NOT done

- **`NC-5`'s rule is not built.** Recorded twice now, by two slices in two days. The third should
  build it.
- **No delivery has ever succeeded under observation.** `NotificationDeliveryTest` points at port 1
  on loopback deliberately, because the failing path is where the credential leaks — so the `SENT`
  branch of `beginAttempt`, `succeeded`, and every one of the three adapters' bodies reaching a real
  endpoint are unexercised end to end. `NT-6`'s local-sink recommendation is what changes that.
- **Nothing drives these endpoints over HTTP.** The gate is asserted at the service and the
  404-versus-403 mapping is asserted by review; `NC-2` records it as a gap rather than a pass. A
  `MockMvc` pass is what would close it.
- **The Linux lane has not seen phases 1–3 as a set.** `tests.yml` runs nightly on the default
  branch; the merge at `c9cadb6` is on `master` and unpushed at the time of writing.
- **No `packages/` surface for notification channels**, and that is a decision rather than an
  omission: an agent that could create a channel could point one at a URL it chose.
