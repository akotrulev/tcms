# Notifications — M4's first integration, and the outbox's first payload

**Date:** 2026-08-15 · **Status:** ~~in progress~~ **all four phases shipped** — see
`docs/handoff/218-the-arm-with-no-caller.md` · **Branch:** ~~`worktree-notifications` (a concurrency
worktree — other lanes are live in the primary checkout)~~ **merged to `master` at `c9cadb6`; the
worktree has served its purpose and must not be committed into again** · **Milestone:** M4, plan §5
*Integration architecture notes* and Phase 2 line 159

---

## 0. The finding that shapes the whole slice

`Outbox.record` — the payload-carrying, past-tense arm of the outbox — **has no caller in the
product**, and has not since §131 built it. Its own javadoc says so at length, names the fact as a
decision rather than an oversight, and states the debt the first real caller inherits:

> **What the first real caller owes.** Not just a handler: a consumer-side test pinning the payload
> shape, because `OutboxMessage#payload()` says the compiler cannot see across that boundary and a
> test on each side is what replaces it. Half of that pair does not exist yet.

`OutboxMessage`'s header states the other half in the conditional — *"stated in the conditional
because no such pair exists (§153)"* — and `OutboxEvents` states the third: *"the first name whose
handler wants to know **which** row moved is the one that has to answer `Outbox.record`'s standing
debt."*

**Notifications is that caller.** "Run completed" is unusable as a coalesced state event: a Slack
message that says *something in this company finished* is not a notification. Every event this
feature needs is an occurrence with a payload, which is why three separate files predicted this slice
without naming it.

So this is not merely the first integration adapter. It is the first crossing of the module boundary
that carries a *shape*, and the honesty mechanism for that crossing — a producer-side test and a
consumer-side test pinning one payload — **is part of this slice's deliverable, not a follow-up.**

## 1. What exists already, and what does not

| Thing | State |
|---|---|
| `outbox` table, dispatcher, lease, backoff, `DEAD` on no-handler | **Built** (§131), unchanged here |
| `Outbox.recordCoalesced` + two state events | **Built**, two producers, three handlers |
| `Outbox.record` (payload arm) | **Built, zero product callers** — this slice is the first |
| `team.notification_channel JSONB` (`V1`:136) | **A writerless column since M0.** See §3 |
| `integration:manage` permission | **Seeded** (`V3`), Test Lead and up (`V52` left it there) |
| `integration` module (plan §5's module list) | **Does not exist.** This slice creates it |
| Any credential encryption anywhere in `backend/src` | **Does not exist.** See §4 |

`integration:manage` already being seeded and already being Lead-only is the load-bearing half: this
slice adds **no permission**, so the catalogue, the roles spec's §A3, both matrix copies and
`matrixCopies.test.ts` are all untouched. A slice that needed a 56th permission would be a much
larger slice, and it is worth saying why it does not: channel configuration is project configuration,
which is exactly what `V52` decided the Test Engineer → Test Lead line *is*.

## 2. Module placement — `integration`, and why not `execution` or `storage`

Plan §5 names `integration` in the module list (*"Jira, notification channels, Git, outbound
webhooks"*), so this is a declared module arriving, not a new one being invented.

It declares **`tenancy`, `identity`, `project`** and nothing else — the foundation set. It must not
depend on `execution` or `work`, which is the constraint that forces every producer to go through the
outbox and is the reason the outbox is the only mechanism available (`ArchitectureRulesTest`
`crossModuleDependenciesPointOnlyAtTheFoundation` checks *every import*).

**It is not a foundation module** and must not become one. §125 admitted `storage` as a fourth
foundation module on three stated grounds — it declares `tenancy` alone, holds no domain concept, and
answers a question the database cannot. `integration` fails the second: a notification channel is a
domain concept with a lifecycle, an owner and a permission. Nothing may depend on it.

## 3. Schema — `V54`, and the column `V1` has been carrying since M0

**This lane pre-assigned `V53` and shipped `V54`, because the rule fired.** While phase 1 was
building, the `master` lane landed §217's `V53__external_identity_oidc.sql`, so this migration was
renumbered at integration in landing order — which is exactly what
`docs/plans/2026-07-31-parallel-execution-rework.md` prescribes and the first time this repo has
actually had to do it.

It was cheap only because of *when* it happened: the migration had never run against anything but
Testcontainers, which migrates a fresh container every time, so no `flyway_schema_history` row
anywhere named `V53`. The renumber was a file rename plus a reference sweep, not a repair. **The
same rename after a single deployment would have been one.**

And it was invisible to every gate, which is the half worth keeping. `out-of-order` is unset, so a
`V53` landing after a `V54` is refused on any already-migrated database — but two lanes each writing
a `V53` produce two *filenames*, merge clean with no conflict, and pass every test on both branches,
because Testcontainers never has an already-migrated database to refuse them. Nothing observed this
but a human reading `git ls-tree master`.

Three tables, all in the `company_owned` set, all under `FORCE ROW LEVEL SECURITY` with the
`nullif(current_setting('app.current_company', true), '')::uuid` predicate that `SchemaConventionTest`
pins on all 56 — 59 after this.

**`notification_channel`** — `company_id`, `project_id`, `kind` (`SLACK` · `TEAMS` · `EMAIL`),
`name`, `enabled`, `config JSONB`, `secret BYTEA`, audit columns, `lock_version`. Unique on
`(company_id, project_id, name)` — company-scoped uniqueness leading with `company_id`, per §0.1.

**`notification_subscription`** — which event types a channel wants, and the one filter this slice
ships: `min_failures INT`. Plan line 159 lists *"run failed threshold"* as a fourth event; **it is
not an event, it is a predicate on `run.completed`**, and modelling it as one keeps the producer from
knowing what any consumer considers alarming. A separate `RUN_FAILED` event would have to be recorded
by `execution` against a threshold configured in `integration`, which is the dependency this module
layout forbids and which would be wrong on its own terms.

**`notification_delivery`** — plan §5's *"delivery-attempt log"*: `channel_id`, `event_type`,
`outbox_id`, `status`, `attempt`, `error`, `created_at`. This is the operator-facing half and the
reason the feature is debuggable at all.

**`team.notification_channel JSONB` is dropped by this migration.** It is a `V1` column with no
writer, no reader, and no entity mapping — the writerless-column shape §116, §117, §132 and §151 each
found and each treated as a defect. Leaving it beside a real `notification_channel` table would be
worse than either: two spellings of one concept, one of them dead, and the dead one is the one an
agent greps first. **A drop is a decision** and is recorded here rather than in the migration alone.

## 4. The webhook URL is a credential, and nothing in this repo encrypts anything

A Slack or Teams incoming-webhook URL *is* the authentication — anyone holding it can post to the
channel. Plan §6r requires integration credentials encrypted at rest, and unlike the API tokens
(`V40`) a digest is not available: the value has to be **recoverable** to be used.

Decisions:

- **AES-GCM, key from `${TCMS_INTEGRATION_SECRET_KEY}`** — no colon, so an unresolvable placeholder
  throws at bind time. This is §108's lesson taken literally: `${VAR:?msg}` is a *shell* form that
  Spring reads as a default, and that is how this instance signed production sessions with a string
  from this repo for a year. `ConfigConventionTest` reads the text and will see it.
- **Write-only across the contract.** A channel's secret is accepted on create and update and is
  **never** returned; the read model carries a masked hint (`https://hooks.slack.com/…/AbC1`) so the
  screen can show *which* webhook without being able to replay it.
- **Never in an outbox payload.** `OutboxEntry#getPayload()` already says *never a credential*, and
  §131 refused the invitation email as a producer on exactly this ground. The payload names the
  *event*; the handler looks the channel up.
- **Never in a delivery-log error.** A failed POST's message can contain the URL. It is redacted on
  the way into `notification_delivery.error`, and that redaction gets its own test, because this is
  the class of leak that is invisible in every green run.

## 5. Events — two names, both occurrences, both payload-carrying

Added to `OutboxEvents` (a foundation module edited for a feature module's reason, which that file's
header already accepts as the price of one spelling per name):

| Name | Producer | Payload |
|---|---|---|
| `execution.run_completed` | `RunStatusService.setStatus`, inside the producing transaction | run id, display key, name, project id, final status, pass/fail/blocked counts |
| `work.item_assigned` | the work-item write path, on create-with-assignee and on assignee change | item id, key, title, project id, assignee id, previous assignee id |

**Plan line 159's fourth event, `defect created`, is not built, and the reason is that it has no
producer.** `defect:create` is seeded in `V3` and there is no defect entity, table or filing path
anywhere in `backend/src` — defect filing is M4 scope behind the `IssueTracker` port (§5, §6l) and
is unbuilt. Adding a `work.defect_created` constant with nothing recording it would put a
writerless name in a foundation module, which is §3's shape one level up from the column this same
migration drops. **It arrives with the defect view, and the subscription enum is the only thing that
has to change when it does.**

Both are **past-tense occurrences recorded through `Outbox.record`**, which is the convention
`OutboxEvents` states and which has never once discriminated anything because both existing entries
are the coalesced half. This slice is where the convention starts doing work.

**Coalescing would be wrong for both and the reason is the same**: a coalesced event stands for
every change since the last delivery, so its payload could only name one of them truthfully — and a
notification whose entire content is the payload cannot be sent from an event that has none.

## 6. The port and its three adapters

`NotificationChannelPort`: `deliver(RenderedNotification, ChannelConfig) → void`, throwing to fail.

Three adapters, built together on plan §5's stated reasoning for the four `IssueTracker` adapters —
*"a port with a single implementation always leaks that implementation's vocabulary"*. Slack's block
kit and Teams' MessageCard disagree enough to keep the port honest; SMTP disagrees with both about
whether a message even has a *channel*.

- **Slack** — incoming webhook, JSON `blocks`.
- **Teams** — incoming webhook, MessageCard.
- **SMTP** — Spring's `JavaMailSender`, recipients on the channel config, plain text + HTML part.

Rendering is **one model, three renderers**, which is the shape §6e already uses for reports and
which is the only arrangement in which "run completed" means the same thing in all three places.

**The delivery must never fail the producing transaction** (plan §5, and the `outbox` table's own
`COMMENT`). It cannot here — the handler runs on the dispatcher's thread, outside any transaction the
outbox holds, and a throw is recorded on the outbox row and retried on a backoff. That property is
inherited, not built, and it is the reason this feature is an outbox consumer rather than an
`@Async` listener.

## 7. Phases

Each phase commits after its own gate. Staging is `git add <paths>` only.

1. **Backend spine** — `V54`, the `integration` module, the port, three adapters, the renderer, the
   handler, the crypto, the delivery log. Producers and the two payload-pinning tests. Gate:
   `./gradlew build` (run on `shanks`, which is the Linux observer `ci.yml` is not).
2. **Contract + REST** — `openapi.yaml` (this worktree holds the contract lock for the duration),
   controllers, **both** `schema.d.ts` copies regenerated. Gate: `./gradlew build`, `packages`
   build + tests.
3. **Frontend** — project-settings screen: channel list, create/edit dialog with the write-only
   secret, delivery history. Gate: `npm run build`, `npm run lint` 0, `npm run test:run`.
4. **Docs** — `docs/handoff/NN-*.md` + one index row, both orientation caches, and the rows this
   slice owes to `docs/verification/frontend-browser-checks.md` and
   `docs/verification/backend-mutation-checks.md`. Per §65 those rows are **written and not run**.
   → **Shipped as `docs/handoff/218-the-arm-with-no-caller.md`.** Reading the two queues *as a set*
   is what this phase found worth doing: phases 2 and 3 had written `NC-1`…`NC-6` and `NT-1`…`NT-7`,
   and every backend row was about the REST edge while the four failures with no symptom — a
   duplicate message, a silenced channel, a reopened run announced as finished, a disabled channel
   that still posts — all sit behind it. `NC-7`…`NC-10` and `NT-8` close that; `NT-8` is also the
   only place either queue drives `work.item_assigned`. Gate re-run on the merged tree:
   `./gradlew build` **BUILD SUCCESSFUL, 2791 / 0 / 0**.

## 8. What this slice does not do

- **No Jira, no Git traceability, no outbound generic webhook.** They share the module and nothing
  else; building them here would make the port's first three implementations four unrelated things.
- **No per-user notification preferences.** Channels are project-level. A person-level digest is a
  different feature with a different table and a different unsubscribe story.
- **No retry UI.** The outbox already retries; an operator-facing replay button needs an answer to
  "replay into which channel" that the delivery log does not yet carry.
- **No `run:close`-adjacent behaviour change.** The producer is added *to* `setStatus`; nothing about
  when a run closes moves.
