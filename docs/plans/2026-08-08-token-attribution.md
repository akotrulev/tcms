# Token attribution — the three columns that record what a credential did

**Date:** 2026-08-08 · **Derived from the code, not from a list** (§28/§29/§46), after decision
round 3 ran out of agent-doable work. Lands phase 1 of
[`2026-08-08-mcp-server-wip-plan.md`](2026-08-08-mcp-server-wip-plan.md) §7 without committing to
the rest of that program: that plan's §8 open questions are all about the tool/command surface and
none of them touches this.

**No migration. No contract change.** All three columns are in `V1`, and both wire fields
(`executedByToken`, `lastUsedAt`) have been in `openapi.yaml` since their screens shipped. Flyway
head stays `V39`; the policed-table count stays 62.

## The shape

`V1` gave the schema three places to record that a *credential* acted rather than a person:

| Column | State today |
|---|---|
| `audit_event.actor_token_id` | Mapped on `AuditEvent`, **never set**. No Java outside the entity mentions it. |
| `test_execution.executed_by_token` | **Not even mapped.** The column exists; `TestExecution` has no field. `CaseHistoryView.executedByToken` is on the wire and hardcoded `null`. |
| `api_token.last_used_at` | Mapped with a getter, **no setter**. `ApiTokenView` documents it as "always null in this slice". |

§116 issued the first token and §117 made CI ingest with one, so all three have had a writer's
worth of traffic since M3 and recorded none of it. Each column's javadoc names the slice that would
fill it — `ApiToken`'s header says `lastUsedAt` is "written by the ingestion path", which is not
true and never was; `AuditEvent.actorTokenId` says "always null today — nothing authenticates by
token yet", which stopped being true at §116. **§100's rule, sixth and seventh sighting.**

What this costs today, concretely: a CI job's ingestion writes an audit row attributed to the
*person* who holds the token; the case history renders "ingested by CI" because it has nothing
better; and "revoke the credentials nobody has used" — the natural companion to any expiry policy —
cannot be answered at all.

## Phase 1 — `audit_event.actor_token_id`, and the guard the move makes buildable

**`AuditEvent.record` reads the ambient token, exactly as it already reads the ambient company.**
There are 42 call sites across 34 files and adding a parameter to all of them is the thing
`TokenContext`'s own header refuses for the permission bound: *"threading a token id through every
service signature to reach it would touch a hundred files to express one fact, and every one of
those signatures would be a place a future call site could forget to pass it."* The failure mode is
worse for attribution than for the bound — a forgotten parameter defaults to *"a person did this"*,
which is a false record rather than a missing one.

**So `TokenContext` moves from `identity` to `tenancy`.** `AuditEvent` lives in `tenancy` for the
reason its header gives (every module writes there; the entity cannot live in whichever module
wrote first), and `tenancy` may not import `identity`. The type is a bare `ThreadLocal` over a
record of five UUIDs with no imports of its own, so the move is a package line and four import
statements (`PermissionEvaluator`, `ApiTokenAuthenticationService`, `ApiTokenAuthenticationFilter`,
and the test lane). It sits beside `CompanyContext`, which is the same shape used for the same
reason by the same kind of cross-cutting infrastructure.

**The move is not only plumbing — it is what makes the missing guard buildable.**
`TokenContext.clear()`'s javadoc records an exposure and then argues it away: a pooled worker can
inherit a token from a task that leaked one, `CompanyContextTaskDecorator` cannot clear it because
`tenancy` may not import `identity`, and the leak is survivable because *"this context only ever
narrows"* — inherited, it makes background work do less rather than more. That paragraph ends with
its own expiry condition: *"it stops holding the moment anything reads this context to grant rather
than to bound."*

**Reading it to attribute is neither granting nor bounding, and it breaks the argument anyway.** An
inherited token does not make an audit row do less; it makes it say something false — a scheduled
recompute filed against a CI credential that had nothing to do with it. The module boundary that
made the guard unbuildable is the one this phase removes, so the decorator clears both contexts and
`TokenContext.clear()` returns a `Scope` like `CompanyContext.clear()` does, restore-not-drop for
the decorator's own stated reason.

Changed:

- `com.tcms.identity.TokenContext` → `com.tcms.tenancy.TokenContext` (+ four import sites)
- `AuditEvent.record` sets `actorTokenId` from `TokenContext.currentOrNull()`
- `CompanyContextTaskDecorator` clears both contexts
- `AuditEvent.getActorTokenId()` — there is no reader yet (the audit screen is M6, #54), so this is
  for the tests that pin the behaviour
- javadoc corrections on `AuditEvent.actorTokenId` and `TokenContext.clear()`

Tests: an ingestion authenticated by a token writes an audit row with both `actor_user_id` (the
owner) and `actor_token_id` set; a session write leaves the token column null; a task submitted to
the application executor while a token is open records none.

## Phase 2 — `test_execution.executed_by_token`

Map the field on `TestExecution`, set it where `IngestionService.write` records the verdict, and
resolve it on the read path.

`executedBy` stays null and the comment there stays true: *"the verdict was not this person's — they
hold the token, they did not run the test."* The column this phase fills is the one that comment
names as the right home for the fact.

**The wire field is a `String`, so the read path resolves the id to the token's name** —
`CaseHistoryService` currently passes a literal `null` and the SPA renders
``ingested by ${entry.executedByToken ?? 'CI'}``. `execution` may depend on `identity` (the
foundation rule allows FKs and imports toward `tenancy`/`identity`/`project`/`storage`), so the
lookup is a repository call, batched over the page's executions like the other resolutions in that
method.

**Known ambiguity, recorded rather than designed away:** a token *name* is unique per company only
among live tokens (`V36`), so a revoked name is reusable and two history entries years apart can
render the same name for two different credentials. The alternative — rendering the prefix — is
unambiguous and unreadable. The name is what an administrator matched against a CI configuration,
so the name is what the history shows.

## Phase 3 — `api_token.last_used_at`, coarsely

The write goes in `ApiTokenAuthenticationService`'s **second** transaction, which is the one already
open inside the company on the policed row.

**It must be coarse, and the entity's own javadoc says why**: *"a write on every authenticated
request is a write-amplification decision"* — a busy CI token would take one `UPDATE api_token` per
request, all contending on one row. So the row is touched only when the recorded value is stale by
more than `tcms.api-token.last-used-resolution` (default 1h, configurable). The value that answers
"has anybody used this credential" does not need to be accurate to the second, and the one that
answers "revoke the unused ones" needs a day at most.

`ApiToken` has no `@Version` and this phase does not add one, for the reason its header already
gives about `revoke`: concurrent writers here both want the same monotonic outcome, and a lost
update costs at most one skipped bump of a deliberately-coarse timestamp.

Frontend: `ApiTokensPage`'s header says *"the day it gets a writer, this is a one-line change"* —
the "Last used" column ships in this phase, along with the three javadocs (`ApiToken.lastUsedAt`,
`ApiTokenView.lastUsedAt`, the page header) that assert the absence.

## Out of scope

- **No audit-trail reader.** `audit:view` and `project:audit` still have zero consumers and the
  audit screen is M6 (#54). This phase writes the column; nothing in the product displays it.
- **No `executedByToken` on the run/execution screens.** The contract carries it on the case-history
  entry only, and widening the contract is a different slice.
- **No expiry policy.** `expiresAt` defaults and the `Agent` role are the MCP plan's phases 2–3.

## §65

Backend mutation rows for all three writers. No browser rows for phases 1–2 (an audit column with no
reader and a history string that only an ingested run can produce); phase 3 adds one, because the
token screen grows a visible column.
