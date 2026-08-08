# §132 — The three columns that record what a credential did

**Date:** 2026-08-08 · **The first slice after decision round 3 closed**, and it was re-derived from
the code rather than taken from a list (§28, §29, §46) · **Plan:**
[`docs/plans/2026-08-08-token-attribution.md`](../plans/2026-08-08-token-attribution.md)
**Three phases, no migration, no contract change.** Flyway head stays `V39`; the policed-table count
stays 62; both wire fields were already in `openapi.yaml`.

It also lands phase 1 of the MCP WIP plan's §7, whose own note says it is *"independent of everything
below and worth landing first — both repair live gaps on **existing** CI tokens whether or not any of
the rest happens."* That plan's three open questions are all about the tool/command surface and none
of them touches this.

## What was broken

`V1` gave the schema three places to record that a **credential** acted rather than a person. All
three were empty, and each was empty in a different way:

| Column | State on arrival |
|---|---|
| `audit_event.actor_token_id` | Mapped on the entity. **No Java outside that entity mentioned it at all.** |
| `test_execution.executed_by_token` | **Not mapped.** `TestExecution` had no field — while `RunSource`, `TestExecutionSummaryView`, `CaseHistoryView` and `IngestionService` all discussed it in prose. |
| `api_token.last_used_at` | Mapped, with a getter and no setter. |

§116 issued the first token and §117 made CI ingest with one, so **all three have had a writer's
worth of traffic since M3 and recorded none of it.** What that cost, concretely: a CI job's audit row
said a *person* did it; the case history rendered `ingested by CI` for every credential on the
instance; and *"revoke the credentials nobody has used"* could not be answered.

**Every one of the three carried a javadoc asserting its own emptiness, and two of those were
already false.** `AuditEvent.actorTokenId`: *"always null today — nothing authenticates by token
yet"* (untrue since §116). `ApiToken.lastUsedAt`: *"written by the ingestion path, not by this
slice"* — it never was, by any path. **§100's rule, sixth and seventh sighting.**

## THE FINDING: `TokenContext` had written down the condition under which it would have to move, and this slice met it

`AuditEvent.record` has 42 call sites across 34 files. None of them gained a parameter, because
`TokenContext`'s own header already refuses that shape for the permission bound:

> *"Threading a token id through every service signature to reach it would touch a hundred files to
> express one fact, and every one of those signatures would be a place a future call site could
> forget to pass it."*

The argument is **sharper for attribution than for the bound**: a forgotten bound defaults to the
unbounded answer, which is wrong and detectable; a forgotten attribution defaults to *"a person did
this"*, which is a false record that reads correct. So `record` reads the ambient token exactly as
it already reads the ambient company.

That required moving `TokenContext` from `identity` to `tenancy` — `AuditEvent` is in `tenancy` for
the reason its own header gives (every module writes there, so the entity cannot live in whichever
one wrote first) and `tenancy` may not import `identity` (ERD §0.2). The type is a `ThreadLocal`
over five UUIDs with no imports, so the move was a package line and four import statements.

**And the move is what made a missing guard buildable.** `TokenContext.clear()` had recorded an
exposure and then argued it away: a pooled worker can inherit a token from a task that leaked one,
`CompanyContextTaskDecorator` cannot clear it across the module boundary, and it is survivable
because *"this context only ever narrows"* — an inherited token makes background work do **less**,
where an inherited company reads another client's rows. The paragraph ended by naming its own
expiry:

> *"it stops holding the moment anything reads this context to **grant** rather than to bound."*

Reading it to **attribute** is neither, and it breaks the asymmetry from the other side: an
inherited token does not make an audit row do less, it makes the row say something false. The
decorator clears both contexts now, and `TokenContext.clear()` returns a `Scope` like
`CompanyContext.clear()` does.

**Both actor columns are written, never one instead of the other.** The owner is who can be asked
about a leaked credential; the token is what actually acted.

## Phase 2 — and the reason the column was still empty is not the same as the reason the others were

`executed_by_token` had no field on the entity, so four files referred in prose to a column no Java
could reach. `record` fills it from the ambient token, and **it is not exclusive with
`executedBy`**: ingestion nulls the person deliberately (*"they hold the token, they did not run the
test"*), but Automation Service also holds `result:submit`, so a credential posting a single verdict
through the ordinary endpoint fills both — two columns answering two questions.

The wire field is a `String`, so `ExecutionTokens` resolves the id to the token's **name**, batched.
A derived `findAllByIdIn` rather than `findAllById`, because `EntityManager.find` does not apply the
Hibernate `@Filter` — `findScopedById`'s documented trap, one method away. The name is unique only
among a company's **live** tokens (`V36`), so it is ambiguous across a rotation; that is recorded in
the class rather than designed away, because the unambiguous alternative is a prefix nobody
recognises.

### The red run: `Map.of().get(null)` — §38's trap, SEVENTH sighting, one slice after the sixth

`tokenNames` is `Map.of()` when no row on the page named a token, which is the **ordinary** case —
and `Map.of().get(null)` throws where `HashMap.get(null)` returns null. Every case history 500'd.
**Eighteen tests went red and not one of them is about tokens**: withdrawn runs, version markers,
the scope parameter, derived insight. §121 phase 4 recorded this exact trap eight days ago in the
sentence *"whose comment this code copied the words of and not the behaviour"*, and this slice
copied the words and not the behaviour.

A lookup keyed by a nullable column must guard the key. The guard is one line; the finding is that
the sixth sighting being written down did not prevent the seventh.

### The second finding: the SPA's author caption branched on the wrong noun

`CaseHistoryPage` decided between *"ingested by X"* and *"by \<person\>"* on `entry.source` — a
property of the **run** — while the actor is a property of the **verdict**. The two came apart the
moment this column got a writer: a credential posting into a MANUAL run rendered as *"by \<the
owner's display name\>"*, a person's name on a machine's claim, which is the one thing attribution
must not do. `authorLine()` branches on the actor now, and the verb still comes from the source
because "ingested" and "recorded" are genuinely different events.

**No backend test can see this.** Both fields are correct and the branch is in the SPA — it is
browser row `A3-16`, and it is the kind of defect §65's deferral makes arrive later and all at once.

## Phase 3 — and the reason it is deliberately imprecise

The `last_used_at` bump rides `ApiTokenAuthenticationService`'s **second** transaction — the one
already open inside the company on the already-loaded policed row. A writer in the filter would get
a transaction of its own, which begins *outside* the company scope and therefore updates zero rows
under RLS: §116's red run, one layer up, and silent.

`ApiToken.markUsed(now, resolution)` returns whether the row changed, and usually it has not.
`tcms.api-token.last-used-resolution` defaults to an hour, because the naive writer is one `UPDATE`
per authenticated request all contending on a busy CI token's single row — to maintain a value whose
only reader is an administrator asking *"is this credential still in use"*, a question that cannot
tell an hour from a second. **No `@Version`**, for the reason the class header already gives about
`revoke`: two concurrent requests on one credential are the ordinary case, and a 409 over a lost
timestamp bump would fail an unattended pipeline.

**Only a *usable* token is recorded.** The `isUsable` filter runs first, so "last used" is not "last
presented" — an administrator deciding what to revoke must not read a live date on a credential
nothing accepts.

The token screen grew the **Last used** column its own header had promised: *"the day it gets a
writer, this is a one-line change."* It was. `Never used` is a sentence rather than a blank, and the
value renders as a date-time and never as "3 minutes ago", which would claim a precision the column
does not have.

## Also

- **`IngestionEndpointTest.Attribution` is the only place in the suite where a write is
  authenticated by a real `api_token`.** Every other endpoint test issues a session for a user id.
  That absence is the mechanism by which three token columns survived two slices that shipped
  tokens: the code path a CI job actually takes was never driven by a test that wrote anything.
- **No audit-trail reader.** `AuditEvent.getActorTokenId()` exists for the tests. `audit:view` and
  `project:audit` still have zero consumers and the screen is M6 (#54).
- **`ApiToken`'s class header is corrected**: it claimed the product "has never been able to issue"
  a token, which stopped being true at §116.

## Not built, and why

- **No `executedByToken` on the run or execution screens.** The contract carries it on the
  case-history entry only, and widening the contract is a different slice.
- **No expiry policy and no `Agent` role.** Those are the MCP plan's phases 2–3 and they are
  decisions, not omissions.

**Built and unverified** — 15 mutation rows `TA-1`…`TA-15` and three browser rows `A3-15`, `A3-16`,
`A3-17`, **none run**, per §65.
