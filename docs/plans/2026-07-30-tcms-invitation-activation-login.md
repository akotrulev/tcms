# M0.5 — Invitation, activation and local login

**Date:** 2026-07-30
**Slice:** the path from `provision-company`'s `INVITED` administrator to an authenticated
API caller. Closes handoff §17's *"the one thing that blocks a usable deployment"*.
**Run through:** `be-java` (Mode B — extend) + `data:data-migrations` / `data:data-postgresql`
for `V6`. **No plugin skill covers local BCrypt+JWT issuance** — the two auth skills on disk
(`auth0`, `cognito`) are provider-side OIDC recipes and do not apply. Said out loud per
`CLAUDE.md`: general Spring Security rules are used, no invented guidance substituted.

---

## 1. What this slice delivers

1. **An invitation record with a real token** — issued by provisioning, redeemable once.
2. **Activation** — the invitee sets a password; `app_user` `INVITED → ACTIVE`,
   `company_membership` `INVITED → ACTIVE`.
3. **Local login** — `POST /auth/login` returns a signed JWT; the first REST surface in the
   codebase, and the first path that populates `CompanyContext` from an authenticated
   session rather than from a test.
4. **Real SMTP** — the invitation email, sent after commit, with the activation link.
5. **`/me` and `/me/permissions`** — already in the contract, and the cheapest possible proof
   that the token, the company context and `PermissionEvaluator` agree.

**Out of scope, deliberately:** refresh tokens, a company switcher, admin-initiated
invitations (`POST /companies/{id}/invitations`), password reset, login throttling/lockout,
`@PreAuthorize` on business endpoints (there are none yet).

---

## 2. The two decisions that shape everything else

### 2.1 The token carries `sub`/`co`/`pv` — not grants. Yet.

Plan §6b decided grants travel in the JWT (`sys`/`cmp`/`prj`) with a mandatory `pv`
revocation epoch. This slice ships **the epoch and not the grants**, and the reason is that
the two halves have different risk profiles:

- `pv` is **load-bearing whatever the token carries**. It is the revocation channel, and
  building it later means re-issuing every token in flight.
- The grant claims are an **optimization** — they remove a query from the hot path. Until
  they exist, `PermissionEvaluator` answers every question, which is one resolution path
  instead of two. §6b's own warning is that a permission resolved inconsistently between two
  code paths is the bug class the design exists to prevent; shipping the second path before
  there is a hot path to optimize would *create* that risk to avoid a cost nobody has
  measured.

The claim names `sys`/`cmp`/`prj`/`prjOverflow` are **reserved and documented** now, so
adding them is additive: no change to issue semantics, no change to `pv`, no change to the
filter chain.

**Consequence for TTL.** §6b's ~10-minute access token exists because a token carrying
grants is a stale-authorization risk. This token carries none — every request re-resolves
through the evaluator behind the account, membership and company-status gates — so a longer
TTL costs nothing that `pv` does not already cover. Default **8 hours**, `rememberMe`
**30 days**, both configurable. When grants move into the token, the TTL comes down with
them; that coupling is recorded in the options class so it is not forgotten.

### 2.2 `membership_invitation` is instance-level (ERD §0.1 exception list)

The accept path runs **before authentication**, so there is no session to resolve a company
from, and this repo's rule is absolute: `CompanyContext` is populated from the authenticated
session, **never** from a request parameter, header or path segment. A company-owned,
RLS-protected invitation table would therefore be unreadable exactly where it must be read —
the same shape as the `role_assignment.company_id IS NULL` defect in handoff §11, where a row
under RLS with no reachable context is a row that exists and can never be seen.

The rejected alternative was encoding the company id into the token (`<companyId>.<secret>`)
so the accept path could open a context first. It keeps the exception list closed and it
discloses nothing the invitee does not already know — and it is still wrong here, because it
makes request input select the tenancy. Once that is permissible for one endpoint, the rule
that keeps 56 policies meaningful is a convention rather than an invariant.

**The cost, stated rather than glossed:** the fact *"this user was invited to this company"*
lives outside RLS. Three mitigations, all in the DDL:

- the row carries **no email, no name, no display name** — only two foreign keys, a hash and
  three timestamps;
- `consumed_at` is set on redemption and the row is **purgeable** once expired;
- `token_hash` is the SHA-256 of the secret, so the table is not a credential store.

`instance_role_assignment` and `app_user` already hold instance-level facts of the same
class, and this is a decision recorded in ERD §0.1 rather than a quiet addition to a `Set.of`.

---

## 3. Phases, each with its own gate and commit

### Phase 1 — `V6`, the entity, and the schema decision

| Item | Detail |
|---|---|
| `V6__membership_invitation_and_permission_version.sql` | The table above + `app_user.permission_version INT NOT NULL DEFAULT 0`. **No RLS**, by the §2.2 decision, with the reasoning in the table comment. Reachable by `tcms_app` through the existing `ALTER DEFAULT PRIVILEGES` — no new grant. |
| `SchemaConventionTest` | `membership_invitation` added to `INSTANCE_LEVEL_TABLES` with its one-line reason. The both-directions assertion means a stale entry fails too. |
| ERD §0.1 | The exception list is the authority; the test defers to it. Both change together or neither does. |
| `MembershipInvitation` entity + repository | `identity` module. Factory `issue(...)`, mutator `consume(now)`. No company filter (instance-level), which `CompanyFilterTest` asserts from `pg_class.relrowsecurity` rather than from a list. |

**Gate:** `./gradlew build` green; `SchemaConventionTest` and `CompanyFilterTest` both pass
*because of* the decision, not around it.

### Phase 2 — issuing and redeeming

| Item | Detail |
|---|---|
| `InvitationService` | `issue(companyId, userId, actor)` → a **plaintext secret returned once** and never stored; `accept(token, password)` → validates, consumes, activates both rows, bumps `pv`. |
| Token | 32 bytes from `SecureRandom`, base64url, stored as hex SHA-256. Lookup is by hash, so a database read never yields a usable credential. |
| Single-use and single-outstanding | Issuing supersedes any unconsumed invitation for the same `(company, user)`. A redeemed or expired token is indistinguishable from a nonexistent one in the response — one generic outcome, no oracle (§6t's rule applied to a pre-auth surface). |
| `CompanyProvisioningService` | Issues the invitation as part of the same transaction, and reports the link in `ProvisionCompanyResult` so the CLI prints it. Idempotent: a re-run against an already-**ACTIVE** admin issues nothing and says so. |
| Password rules | 12–72 characters. **The 72 is not arbitrary**: BCrypt silently truncates past 72 *bytes*, so anything longer is accepted and then only partly verified. Rejected explicitly, counted in bytes, not chars. |

**Gate:** accept-once, accept-expired, accept-consumed, accept-garbage, and the
provisioning round trip, all tested; mutation pass on the consume/expiry checks.

### Phase 3 — SMTP

| Item | Detail |
|---|---|
| `spring-boot-starter-mail` + `InvitationMailer` | One interface, one SMTP implementation. Not a port-with-a-no-op: the user chose real delivery. |
| Failure semantics | Sent **after commit** (`TransactionSynchronization` / `AFTER_COMMIT`), never inside the transaction — an SMTP round trip inside a transaction holds a pooled connection on a network call, and a mail outage would otherwise roll back a provisioned company. A send failure is logged at ERROR **and the CLI still prints the activation link**, so a broken relay degrades to hand delivery rather than to a company nobody can enter. |
| Config | `tcms.mail.from`, `tcms.app.base-url` (the link's origin) as `@ConfigurationProperties`, validated — no scattered `@Value`. |
| Test | GreenMail (in-JVM, no container): the message is addressed to the invitee, and the body carries the **plaintext** token exactly once. |

### Phase 4 — login, the filter chain, and `/me`

| Item | Detail |
|---|---|
| Dependencies | `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server` (Nimbus `JwtEncoder`/`JwtDecoder` with a symmetric key). Resource-server shape now means M4's OIDC is a second decoder, not a new architecture. |
| `JwtOptions` | HS256 secret from config, **required and length-checked at startup** — a defaulted signing key is a signed-token forgery for anyone who reads the repo. |
| `AuthenticationService` | Email lookup (already `citext`-correct per §13), BCrypt verify, `UserStatus.ACTIVE` gate, `recordLogin`, token issue. **A miss verifies against a dummy hash** so an unknown address and a wrong password take the same time and return the same body. |
| Active company (`co`) | Resolved server-side from the caller's ACTIVE memberships — never chosen by the client (§6s: no pre-auth company picker). One membership → that one; several → the earliest accepted, deterministic, until the M6 switcher; none (a pure Instance Admin) → **no `co` claim**, and the request runs context-less with SYSTEM-only permissions. |
| `CompanyContextFilter` | After authentication, opens `CompanyContext` for the request in try-with-resources. This is the production path the whole tenancy layer was built for and has never had. |
| `pv` check | Every authenticated request compares the token's `pv` against the user's current value; mismatch → 401 with a re-auth hint. Cached later (Caffeine, per §6b); correctness first, exactly as the evaluator's own cache was deferred in §13. |
| Endpoints | `POST /auth/login`, `POST /auth/logout`, `GET /me`, `GET /me/permissions`, `GET /auth/invitations/{token}`, `POST /auth/invitations/{token}/accept`. |
| `/auth/logout` | Returns 204 and is honestly documented as a **client-side discard** — a stateless JWT has nothing to invalidate. A denylist is the real answer and it belongs with refresh tokens. |
| Accept returns **204**, not a session | The invitee then signs in with the password they just chose. One token-issuing path instead of two, and the sign-in proves the password round-tripped. |
| `openapi.yaml` | The two invitation endpoints added, with `required` arrays on every new schema **including inline objects** — the handoff's recurring lesson #1/#3. Then `npm run gen:api` in `frontend/`. |

**Gate:** `./gradlew build` green, `npm run build` + `npm run lint` clean after regeneration,
and — per this project's own lesson 5 — **run it**: `docker compose up`, provision a company,
read the link, accept, log in, call `/me` and `/me/permissions` against the live stack.

---

## 4. What could go wrong, listed before it does

| Risk | Handling |
|---|---|
| `CompanyContext` opened per request leaks into the next request on a pooled thread | The filter is try-with-resources; `CompanyContextTaskDecorator` already clears async tasks. A test asserts a second request on the same thread starts with no context. |
| The pre-auth endpoints become an account oracle | One generic outcome for invalid/expired/consumed, and a constant-time login miss. Asserted, not assumed. |
| Security config accidentally leaves an endpoint open | The chain denies by default; `/auth/**` and the actuator health probes are the only `permitAll` entries, and a test walks the endpoint list asserting every other path 401s anonymously. |
| Flyway's `V6` runs as owner while the app reads as `tcms_app` | Covered by the existing default privileges; verified against the live stack in the Phase 4 gate, not just in Testcontainers. |
| Mail configuration absent in dev | Send failure is logged and the link printed; no code path treats a mail outage as a provisioning failure. |

---

## 5. Definition of done

- `./gradlew build` green, every new behaviour **mutation-verified** one narrow edit at a
  time, with any surviving mutation **reported rather than re-rolled** (§13/§14/§15 practice).
- `openapi.yaml` regenerated and both frontend gates clean.
- The end-to-end path walked in the live compose stack.
- `docs/HANDOFF.md` gains §18 with the same shape as §11–§17: what shipped, what it found,
  what was deliberately not done, and what is next.
