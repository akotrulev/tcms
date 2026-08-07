# M3, slice 1 — API tokens: the credential ingestion cannot exist without

**Date:** 2026-08-07 · **Milestone:** M3 (automation ingestion) · **Backend only**

## Why this slice, and why now

M2.5's reporting plan is complete (§111–§115) and everything it deliberately left is blocked on
infrastructure or a deferred decision — a headless renderer, the `shared` module, a notification
channel. So the next slice is re-derived from the code, per §28/§29/§46, and M3 is what the code
says is next: **three `V1` tables have never had a writer.**

| Table | State |
|---|---|
| `api_token` | No entity, no repository, no endpoint. Nothing in Java names it except two FK columns. |
| `ingestion_job` | Same. |
| `automation_key_mapping` | Same. |

M3's four deliverables are result parsers, automation-key matching, API tokens and the quarantine
flow. **Tokens come first because the other three have no caller without one**: a CI job is not a
browser session, `test_execution.executed_by_token` and `audit_event.actor_token_id` are columns
nothing can populate, and the whole security argument for ingestion — roles spec §A6, *"deliberately
the smallest role in the product, because a leaked CI token is the realistic breach"* — is a
statement about a credential that does not yet exist.

**Automation Service has been seeded, granted and machine-checked since `V3`/`V16` and is inert.**
`SeedDataTest.everyProjectScopeRoleHoldsTheVisibilityPrecondition` keeps its grant honest; nothing
can hold it. That is the same shape as `company:teams` before §54, `case:approve` before §62,
`library:manage` before §64 and `company:vocabulary` before §69 — the fifth time a permission has
been correct in the catalogue and unreachable in the product.

## The load-bearing problem: the credential is presented before any company exists

`api_token` is under `FORCE ROW LEVEL SECURITY` (`V1`'s `company_owned` array). A request arriving
with `Authorization: Bearer tcms_…` must resolve that string to a token **before** `CompanyContext`
is open — there is nothing to open it from. Under RLS that lookup returns **zero rows**, and the
instance answers 401 to a valid token, for ever, with no error anywhere.

This is `V7`'s defect exactly (§18/§19: a `SECURITY DEFINER` function returning nothing in
production because `FORCE` binds the owner too), and it would be invisible to a suite whose harness
once migrated as a superuser. `V8`'s closing paragraph names the correct answer in advance:

> if that ever stops being true, the answer is **an instance-level index of the membership fact**,
> not a bypass.

### Decision A — `api_token_lookup`, an instance-level table, and the ERD exception list grows by one

```
api_token_lookup (
    token_hash  TEXT PRIMARY KEY,      -- SHA-256 of the presented secret; the only copy
    token_id    UUID NOT NULL REFERENCES api_token(id) ON DELETE CASCADE,
    company_id  UUID NOT NULL REFERENCES company(id)   -- a TARGET, not a discriminator
)
```

No RLS. The filter reads it with no context open, opens the company it names, and then reads the
**policed** `api_token` row for everything else — name, owner, role, project, `expires_at`,
`revoked_at`. So the unprotected table carries a digest and a company id and nothing else, and
every authorization fact still comes from inside the isolation boundary.

**This is `membership_invitation`'s precedent, clause for clause** (ERD §0.1, decided in `V6`):
redeemed before authentication, so there is no session to resolve a company from; a **non-nullable**
`company_id` that is a target rather than a discriminator; only a SHA-256 on the row, never the
secret. The cost accepted there is accepted here in the same words — *"this digest belongs to this
company"* sits outside the boundary — and it is bounded by the row holding nothing else.

**The rejected alternative is the one `V6` also rejected:** encoding the company id into the token
string so the filter could open a context from it. It keeps the exception list closed and it makes
**request input select the tenancy**, which is the one thing ERD §0.1 point 2 says must never be
negotiable.

### Decision B — the hash lives in `api_token_lookup` ONLY, so `V1`'s `api_token.token_hash` is dropped

Two copies of a credential digest in two tables with different protection is a divergence waiting to
happen: revoke updates one, the filter reads the other, and the revoked token keeps working. There
is one home. `api_token.revoked_at` and `expires_at` stay on the RLS'd row and are checked **after**
the context is opened — so revocation is enforced on the policed row, where an operator can see it,
and the lookup answers exactly one question: *which company*.

## The other load-bearing problem: a token must not be a second authorization path

`SessionTokens`' header already refuses to put grant claims in the session JWT, quoting §6b: *"a
permission resolved inconsistently between two code paths is the bug class the design exists to
prevent."* A token principal that resolved its own permissions would be that path.

### Decision C — a token CAPS its owner's permissions; it never confers its own

The caller identity on a token-authenticated request stays `api_token.owner_id`, so every existing
controller (`caller(jwt)` → a user id) and every `createdBy`/`OWN`-qualifier call site keeps
working unchanged. `PermissionEvaluator` gains **one** narrowing step, in the single place
permissions are computed:

```
effective = evaluate(ownerId, projectId)  ∩  permissionsOf(token.roleId)      [∩ the token's project]
```

Intersection, never union. Three properties fall out, and each is the reason it is intersection:

1. **A token can never exceed its role.** Roles spec §A6's list is an upper bound by construction,
   not by a grant somebody remembered to make.
2. **A token can never exceed its owner.** A Test Engineer cannot mint themselves a Company Admin
   token, and demoting a person shrinks the tokens they issued — the property a union would lose.
3. **`api_token.project_id`, when set, is a hard scope.** A different project resolves to the empty
   set before the intersection is even reached, so the token is inert everywhere else.

The token is carried in a `TokenContext` scope guard — `CompanyContext`'s shape exactly, opened and
closed by the filter, deliberately not `InheritableThreadLocal` — because threading a token id
through every service signature is the change that touches a hundred files to express one fact.

**Fail-closed check:** a company-wide question (`evaluateCompanyWide`) under a token intersects with
the token role's permissions too. Automation Service is PROJECT-scope, so its company-wide answer is
empty — which is correct: a CI token administers nothing.

## Decision D — the gate is `token:issue`, at COMPANY scope, and this is its first consumer

`V3` seeds it and grants it to Company Admin and Instance Admin (roles spec §A3, *"API / CI
tokens"*). It has never been asked. The verdict comes from a new `AdminReach.issueTokens()` term —
a **separate term** from `companyWide` and `manageTeams`, for `AdminReach`'s own recorded reason:
two permissions folded together make one of them unaskable.

## Decision E — the secret is shown once, at issue, and is unrecoverable afterwards

`POST` returns the plaintext in the creation response and nowhere else; the list serves a
**prefix** for recognition and never the secret. Format `tcms_<32 url-safe random bytes>`, generated
from `SecureRandom`; the stored digest is SHA-256 of the whole string. No per-token salt and no
bcrypt: this is a 256-bit random secret with no guessable structure, so the slow-hash argument
(which exists to defend low-entropy human passwords against offline search) buys nothing and would
put a bcrypt round on every CI request. `PasswordPolicy`'s bcrypt stays exactly where it belongs.

**A prefix column is therefore needed** — `api_token.token_prefix`, the first 12 characters — so a
list can say *which* token without holding the secret. That is the second half of `V36`.

## Phases

Each phase ends with `cd backend && ./gradlew build` green and its own commit.

| Phase | Content |
|---|---|
| **1** | `V36__api_token_lookup.sql` (the lookup table, drop `token_hash`, add `token_prefix`), ERD exception-list row, `ApiToken` entity + repository, `ApiTokenLookup` + repository, `ApiTokenSecrets`, `ApiTokenService` (issue · list · revoke) gated on `token:issue`, `ApiTokenController`, the contract, `ApiTokenEndpointTest`. |
| **2** | `TokenContext`, `ApiTokenAuthenticationFilter`, the `PermissionEvaluator` intersection, `SecurityConfiguration` wiring, `ApiTokenAuthenticationTest` — including the two tests that could only ever fail in production before `V8`: a token resolving with **no context open**, and a revoked token refused. |

Deliberately **not** in this slice, and recorded so the next one does not re-derive them:

- **The ingestion endpoint itself** (`POST /projects/{id}/runs/{id}/results`), the four parsers, the
  matching ladder and `automation_key_mapping`'s writer — M3 slice 2, which this unblocks.
- **The frontend token screen.** Company-admin surface, one list and two writes; it needs this
  contract to exist first.
- **`last_used_at`.** A write on every authenticated request is a write amplification decision that
  wants a batching answer (the same shape as `plan_snapshot`'s tick), not a naive `UPDATE` in a
  filter. The column stays unwritten and this sentence is why.
