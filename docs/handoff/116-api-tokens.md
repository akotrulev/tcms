# §116. API tokens — the credential presented before there is a company, and the cap that is an intersection

**Date:** 2026-08-07 · **Milestone:** M3, slice 1 · **Backend only** ·
Plan: [`docs/plans/2026-08-07-m3-api-tokens.md`](../plans/2026-08-07-m3-api-tokens.md)

**Gates:** `./gradlew build` **BUILD SUCCESSFUL — 1909 tests across 394 result classes, 0 failures,
0 skipped** (up from 1870/383 at §115: 26 + 13 new). `npm run build` clean after `gen:api`.
**Built and unverified** in the §65 sense — 12 mutation rows queued, none run. **No browser rows:
there is no frontend surface in this slice**, and the token screen is named as the next one.

## Why this slice

M2.5's reporting plan finished at §114 and §115 took the last unblocked item off its §8. So the next
slice was re-derived from the code (§28, §29, §46), and the code said M3: **three `V1` tables have
never had a writer** — `api_token`, `ingestion_job`, `automation_key_mapping`. Tokens come first
because the other two have no caller without one. `test_execution.executed_by_token` and
`audit_event.actor_token_id` have been columns since M0 with nothing able to fill them.

**Automation Service has been seeded, granted, repaired and machine-checked since `V3`/`V16`, and
was inert.** `SeedDataTest.everyProjectScopeRoleHoldsTheVisibilityPrecondition` keeps its grant
honest; nothing could hold it. That is the **fifth** permission to be right in the catalogue and
unreachable in the product — `company:teams` before §54, `case:approve` before §62,
`library:manage` before §64, `company:vocabulary` before §69.

## The problem the slice is actually about

`api_token` is in `V1`'s `company_owned` array, so it carries `FORCE ROW LEVEL SECURITY`. A request
arriving with `Authorization: Bearer tcms_…` must resolve that string **before** `CompanyContext` is
open — there is nothing to open it from. Under the policy that lookup returns **zero rows**, so the
instance answers 401 to a valid token, for ever, with no error anywhere.

This is `V7`'s defect (§18/§19), which returned nothing in production past 167 green tests because
the harness migrated as a superuser. `V8`'s closing paragraph names the answer in advance:

> if that ever stops being true, the answer is **an instance-level index of the membership fact**,
> not a bypass.

### `api_token_lookup`, and the ERD exception list grows by one

`(token_hash PK, token_id, company_id)`. No RLS. The filter reads it with no context, opens the
company it names, and then reads the **policed** `api_token` row for everything else. So the
unprotected table carries a digest and a company id and **nothing else** — no name, owner, role or
project — and every authorization fact still comes from inside the isolation boundary.

`membership_invitation`'s decision clause for clause (`V6`): presented before authentication, a
**non-nullable** `company_id` that is a *target* rather than a discriminator, only a digest on the
row. **The rejected alternative is `V6`'s too** — encoding the company into the token string — and
it is refused for the same reason: it makes request input select the tenancy.

**The hash MOVES rather than being copied.** `V1`'s `api_token.token_hash` is dropped. Two homes for
one credential digest diverge the first time a revocation writes one and a filter reads the other,
and the failure is a revoked token that keeps working with no row that looks wrong.

### The uniqueness ERD §0.1 names by name and `V1` never created

§0.1 point 3 lists the constraints that must lead with `company_id`: *"project.key, team.name,
**api_token.name**, test_case.automation_key, key_sequence…"*. Every one exists in `V1` except that
one. `SchemaConventionTest` asserts that `company_id` **leads every index that includes it**, which
is silent about an index that was never created at all — so a named invariant went eight months
unimplemented behind a green suite. `V36` adds it, **partial on `revoked_at IS NULL`** for §93's
reason on `doc_page`: rotation must be able to reuse the name written into somebody's CI
configuration.

## The other half — a token must not be a second authorization path

`SessionTokens`' header refuses to put grant claims in the session JWT, quoting §6b: *"a permission
resolved inconsistently between two code paths is the bug class the design exists to prevent."* A
token principal that resolved its own permissions would be that path.

**So the caller identity stays the token's owner and the evaluator gains one narrowing step:**

```
effective = evaluate(ownerId, projectId)  ∩  permissionsOf(token.roleId)      [∩ the token's project]
```

Intersection, never union. Three properties follow **by construction**:

1. **A token can never exceed its role.** Roles spec §A6's list is an upper bound because it is one
   side of an intersection, not because somebody granted exactly it.
2. **A token can never exceed its owner.** A Test Engineer cannot mint themselves a Company Admin
   credential, and demoting a person shrinks every token they issued — on the next request.
3. **Every gate is covered**, including ones not yet written: a new endpoint asks the evaluator, so
   it is capped the day it exists.

The **qualifier** is intersected too, through a new `PermissionQualifier.narrowedBy` — the meet
where `strongest` is the join. `OWN` against `PLAN_SCOPE` answers `NONE` here and **throws** there,
and the asymmetry is deliberate: a narrowing that cannot be expressed has a safe answer and a
widening does not.

The bound travels in `TokenContext`, `CompanyContext`'s shape. **It is not covered by
`CompanyContextTaskDecorator`** — `tenancy` may not import `identity` — and that omission is safe in
one direction only: this context *only ever narrows*, so an inherited token makes background work do
less. It stops being safe the moment anything reads it to grant.

## The red run, and it was not a mutation

**The first draft of `ApiTokenAuthenticationService` was one `@Transactional` method that opened the
company between its two reads. Eight tests went red at once and every valid credential got a 401.**

`CompanyAwareTransactionManager` establishes isolation in `doBegin` — it sets the Hibernate filter
and the `app.current_company` GUC **when the transaction starts**. A transaction that begins with no
context open therefore runs to completion without one, whatever `CompanyContext.open` is called
afterwards: the scope guard writes a `ThreadLocal` that nothing re-reads. So the policed read ran
under RLS with no company and returned nothing — `V36`'s own defect, one layer up, in the code
written to avoid it.

Two `TransactionTemplate` calls, not two `@Transactional` methods: a self-invocation is not
intercepted by the proxy, so splitting them into private methods produces the identical single
transaction and the identical silent 401. `ReportJobRunner`'s argument for its three transactions.

**It was observable only because the assertions are made over HTTP.** Any harness that opened a
context before calling in would have been green — which is the same structural blindness §19 records
about the superuser harness, in a third shape.

## Decisions worth not re-litigating

- **The filter produces a `Jwt`.** Every controller takes `@AuthenticationPrincipal Jwt` and reads
  `getSubject()`; a different principal type means touching all of them, and each touch is a place a
  future endpoint handles one shape and not the other. The object carries `sub` (the **owner**) and
  `co`, so `CompanyContextFilter` opens the company by the same path a session takes. It carries **no
  permissions, no role, no project** — nothing signs it, so a grant on it would be a fact the filter
  asserts rather than one anybody can verify.
- **`pv` does not apply to a token, and the account gate does.** `PermissionVersionFilter` branches
  on the `tkn` claim: the epoch exists because a *signed* artifact outlives a revocation, and an API
  token is looked up in the database on every request. Fabricating a `pv` would be a comparison
  against a number the same request produced. The **account** check stays, and it is the half that
  matters — deactivating a person must stop the credentials they issued.
- **A `BearerTokenResolver` that returns null for `tcms_`.** Without it the resource server parses
  the credential as a JWT and 401s **before** the token filter is reached: the right credential
  failing exactly like a wrong one.
- **The secret is shown once and the audit row carries the prefix.** `IssuedApiTokenView` is a
  separate type from `ApiTokenView` rather than a nullable field, so no read path is one forgotten
  null away from serving a credential.
- **Revocation does not delete the lookup row.** One enforcement point (`isUsable`) covers both
  revocation and expiry; a second would be exercised by one of the two ways a token dies.
- **`ApiTokenSecrets` delegates to `InvitationTokens`** — one digest implementation in the codebase,
  two policies on top. Its bcrypt argument applies harder here: this is verified on every CI request.
- **There is no `ownerId` field on the request.** A nominated owner is a way to mint a credential
  more powerful than yourself, which is the escalation the cap exists to prevent, offered as a form
  field. `ApiTokenAuthenticationTest.theOwnerCapsTheRole` seeds that row **directly** anyway, because
  the evaluator must not depend on the endpoint being the only writer.
- **`last_used_at` stays unwritten**, mapped and on the wire. A write per authenticated request is a
  write-amplification decision wanting `plan_snapshot`'s batching answer, not an `UPDATE` in a filter.

## What this unblocks, and what is next

- **M3 slice 2 — the ingestion endpoint**: `POST` results, the four parsers (`JUNIT`/`TESTNG`/
  `ALLURE`/`CUCUMBER` are already a `CHECK` on `ingestion_job.format`), §6c's four-step matching
  ladder, and `automation_key_mapping`'s writer. Every one of them needed a credential to exist.
- **The token screen** — company-admin surface, one list and two writes. Deferred here because it
  needed this contract first, and it is the slice that will carry this feature's browser rows.
- **`ingestion_job` and `automation_key_mapping` still have no writer.** Two of the three tables this
  milestone opened with.
