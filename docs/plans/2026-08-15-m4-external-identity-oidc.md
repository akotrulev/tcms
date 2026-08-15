# M4 External Identity — OIDC SSO, Account Linking Implementation Plan

**Date:** 2026-08-15 · **Written by:** `ep-writing-plans` (as the `be-java` agent)

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task, or `ep-delegate-to-subagents` to run its tasks through subagents. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Company Admin configures an OIDC provider for their own company; a user signs in
through it and lands in a normal TCMS session, linked to an existing `app_user` rather than a
new one.

**Architecture:** Three new company-owned tables in the `identity` module (`identity_provider`,
`user_identity`, `company_email_domain`) plus one instance-level pre-authentication lookup
(`sso_login_attempt`), and the authorization-code+PKCE flow implemented explicitly in `auth`.
TCMS issues its own session token at the end of the flow (§6r) — the IdP token is never
forwarded to the SPA, so `PermissionEvaluator` and the whole §6b model are untouched.

**Tech Stack:** Java 21 · Spring Boot 3.5.6 · Spring Modulith 1.4.3 · Flyway · Postgres 17 with
FORCE RLS · Nimbus JOSE (already on the classpath via `spring-boot-starter-oauth2-resource-server`)
· `RestClient` for discovery and token exchange.

---

## Scope check — this is three plans, not one

`ep-writing-plans` requires each plan to produce working, testable software on its own. §6r
covers four subsystems; they are not independent, so they are sequenced rather than parallel:

| Plan | Covers | Ships |
|---|---|---|
| **1 — this file** | Federation schema · provider CRUD · OIDC authorize/callback · account linking · JIT-vs-invitation | A user can sign in via Keycloak and get a TCMS session |
| **2 — `…-claim-role-mapping.md`** | `claim_role_mapping`, `role_assignment.source`/`provider_id`, IdP-derived grants read-only, reconcile + `permission_version` bump | IdP group membership confers TCMS roles |
| **3 — `…-ldap-ad.md`** | LDAP(S) bind lane, group search, nested-group recursion, deprovisioning poll | On-prem AD deployments can sign in |

Plans 2 and 3 both build on plan 1's `user_identity` and provider config **with no schema
change to them** — which is the property that makes this sequencing safe. Their design is
sketched at the bottom of this file; each gets its own full plan when its turn comes.

**Do not start plan 2 or 3 until plan 1 is merged.** They share `openapi.yaml` and the Flyway
sequence.

---

## Lane ownership (concurrency)

Recorded 2026-08-15 at the user's direction, because an org-chart lane is running concurrently:

- **This lane holds `openapi.yaml` and `V53`.** The org-chart lane queues behind for `V54`.
- `out-of-order` is unset, so a `V53` landing *after* a `V54` is refused on any already-migrated
  database. **Testcontainers cannot see this** — it migrates a fresh container every time.
- `schema.d.ts` has **two** copies (`frontend/src/api/`, `packages/client/src/`) pinned
  byte-identical by `schemaFreshness.test.ts`. Two regenerations do not merge: resolve the
  *contract*, then regenerate, never hand-merge the output.
- **One writer for `docs/agent-context/be-java.md`.** Both lanes are `be-java`. Update it in this
  slice's own commit and re-read before editing.
- Fixture UUID prefix space is hand-managed with no check behind it and has collided five times
  (§19…§62). **This lane takes prefix `f1`** (`f1000000-…`). State it in the fixture file header.

---

## Design decisions already locked — do not re-litigate

These come from §6r, the ERD, and the resolved open questions. An implementer who reopens them
is burning time on settled ground.

| Decision | Source | Consequence for this plan |
|---|---|---|
| Identity is `(provider, subject)`, **never email** | §6r | `user_identity.subject` is the key; `email_at_link` is a record |
| **JIT default is invitation-required** | #38 | `jit_provisioning` defaults `false`; first login with no invitation is refused |
| **Keycloak is the reference IdP** | #37 | Compose gains a real Keycloak; the lane's counterparty is not a mock |
| **No SAML** | #40 | `identity_provider.kind` has no `SAML` member in this plan |
| TCMS issues its own session; IdP token never reaches the SPA | §6r | The callback ends at `SessionTokens.issue(...)`, same as local login |
| Local accounts never go away; break-glass Instance Admin authenticates locally | §6r | `AuthenticationService` is **not** modified |
| `(company_id, issuer, subject)` — issuer trust is company-local | ERD §837, §6r | A different company's IdP asserting the same `sub` must not resolve to that user |
| No pre-authentication company picker | §6s | Provider discovery is by email domain, and answers identically when unmapped |

---

## The five traps that will bite

Read this section before writing any code. Four of the five are silent when wrong.

### 1. Every SSO read happens *before* company context exists — this is the `V7` defect

`user_identity`, `identity_provider` and `company_email_domain` are company-owned, so they carry
`company_id NOT NULL` and FORCE RLS. But at callback time there is **no session and no company
context**, so `app.current_company` is unset and every one of those reads returns **zero rows**.
Fail-closed: nobody can ever sign in via SSO, and it presents as "wrong credential", not as a
schema fault.

This is not a new problem and it has a settled answer in this codebase. Follow the
**`api_token_lookup` (`V36`) / `membership_invitation` (`V6`) precedent exactly**:

- An **instance-level lookup table** carrying a **non-nullable `company_id` as a *target*, not a
  discriminator** — on the ERD §0.1 exception list, holding the minimum that answers the
  pre-authentication question and nothing else.
- The full row, with its lifecycle (`enabled`, revocation), stays **RLS-protected** and is checked
  **after** the context is open.

**Concretely:** `sso_login_attempt` is the instance-level table (see Task 2). Provider *resolution*
is done by primary key from the URL — the `identity_provider.id` UUID is opaque and leaks nothing
— through **one narrow `SECURITY DEFINER` function**, mirroring `V7`'s
`findCompanyIdsForUser`, which `AuthenticationService` documents as "the one SECURITY DEFINER call
in the codebase". After this plan there are two, and the second must be as narrow and as
commented as the first.

**Adding `sso_login_attempt` to the §0.1 exception list requires a decision** — CLAUDE.md says
nothing may be added without one. Task 1 records it in the ERD in the same commit.

### 2. Do not wire this through `oauth2Login()` or a second resource server

`SecurityConfiguration` already calls `.oauth2ResourceServer(oauth2 -> oauth2.jwt(...))` — that
validates **TCMS's own session JWT**, not an external IdP's. Adding an external issuer there
would let an IdP-minted token authenticate against the whole API directly, bypassing the session
model, `permission_version` and the company claim.

Spring's `oauth2Login()` is also the wrong shape here: `ClientRegistrationRepository` is built for
*static* registrations, while ours are **per-company database rows**; the chain is `STATELESS`
with a custom `HttpStatusEntryPoint`; and TCMS issues its own session, so the
`OAuth2AuthenticationToken` / authorized-client-repository machinery would be unused weight.

**Implement the flow explicitly** — discovery, authorize redirect, token exchange, `id_token`
validation — with `RestClient` and `NimbusJwtDecoder`. Both are already on the classpath:
`spring-boot-starter-oauth2-resource-server` brings `spring-security-oauth2-jose` and Nimbus.
**This plan adds no new dependency.**

### 3. `/api/v1/auth/**` is already `permitAll` — so `SecurityConfiguration` needs no change

Every new endpoint in this plan lives under that prefix, which is already open by the enumerated
matcher. Do **not** widen the matcher and do **not** add a new one. `EndpointAccessTest` walks
the mappings and will check the new routes; the company-admin CRUD endpoints
(`/identity-providers`) live *outside* `/auth/**` and are therefore authenticated by default,
which is correct.

### 4. Refusals must stay indistinguishable, and cost the same time

`AuthenticationService`'s header documents this at length: one refusal for unknown address, wrong
password, `INVITED` and `DEACTIVATED`, and a dummy-hash verification so a miss costs what a hit
costs. Email is unique **instance-wide**, so a finer answer is a directory spanning every client.

The same rule binds every surface this plan adds:

- `POST /auth/providers` (email → which buttons to show) must answer **identically** whether the
  domain maps to a company, maps to several, or maps to none. §6r: otherwise login is an oracle
  for which companies exist on the instance.
- The callback must not distinguish "no `user_identity`", "no invitation", "provider disabled",
  "unverified email with `auto_link_verified_email` off", or "account `DEACTIVATED`".

### 5. `identity` may not reach the network; `auth` may not own a table

`identity` declares `allowedDependencies = {"api", "tenancy", "project"}` and holds the entities.
`auth` declares `{"api", "tenancy", "identity"}` and its package comment states plainly that it
"owns no domain of its own — no entity, no table".

So the split is forced and is the right one anyway:

- **`identity`** — the three company-owned entities, their repositories, the linking service, the
  provider CRUD service. No HTTP client.
- **`auth`** — the OIDC protocol work (discovery, redirect, token exchange, `id_token`
  validation), the endpoints, and `sso_login_attempt` handling.

`auth` is a leaf; nothing may depend on it. ArchUnit enforces both declarations, so a violation
fails the build rather than shipping.

---

## File structure

**`backend/src/main/resources/db/migration/`**
- Create: `V53__external_identity_oidc.sql` — three company-owned tables + RLS, one instance-level
  lookup, one `SECURITY DEFINER` resolver

**`backend/src/main/java/com/tcms/identity/`** *(entities + linking — no network)*
- Create: `IdentityProvider.java` · `IdentityProviderKind.java` · `IdentityProviderRepository.java`
- Create: `UserIdentity.java` · `UserIdentityRepository.java`
- Create: `CompanyEmailDomain.java` · `CompanyEmailDomainRepository.java`
- Create: `IdentityProviderService.java` (CRUD, validation) · `IdentityProviderInput.java` ·
  `IdentityProviderView.java` · `IdentityProviderController.java`
- Create: `FederatedIdentityService.java` (link / JIT / refuse — the §6r policy engine)
- Create: `ProviderSecrets.java` (client-secret encryption at rest)

**`backend/src/main/java/com/tcms/auth/`** *(protocol + endpoints)*
- Create: `OidcDiscovery.java` · `OidcDiscoveryDocument.java` (cached metadata fetch)
- Create: `OidcClient.java` (token exchange) · `OidcTokenResponse.java`
- Create: `OidcIdTokenVerifier.java` (signature, `iss`, `aud`, `nonce`, `exp`)
- Create: `SsoLoginAttempt.java` · `SsoLoginAttemptRepository.java` · `SsoLoginService.java`
- Create: `SsoController.java` (`/auth/providers`, `/auth/sso/{providerId}/authorize`,
  `/auth/sso/{providerId}/callback`)
- Modify: **nothing existing.** `AuthenticationService` and `SecurityConfiguration` are untouched.

**`docs/specs/`**
- Modify: `openapi.yaml` — five operations (this lane holds the contract)
- Modify: `2026-07-30-tcms-erd.md` — §0.1 exception-list entry for `sso_login_attempt`

**Tests** — `backend/src/test/java/com/tcms/…`; the context-free unit lane is this agent's
(Phase 3), anything booting a context is `qa-api-java`'s.

---

## Task 1: The ERD decision for `sso_login_attempt`

Do this first. It is the one thing in the plan that is a *decision* rather than an
implementation, and CLAUDE.md forbids adding to the §0.1 exception list without one.

**Files:**
- Modify: `docs/specs/2026-07-30-tcms-erd.md` (the §0.1 exception-list table)

- [ ] **Step 1: Add the row to the exception-list table**

Append to the table that currently ends with `membership_invitation`:

```markdown
| `sso_login_attempt` | **Decided in `V53`.** The same decision as `membership_invitation` and
`api_token_lookup`, one credential over: an OIDC callback arrives *before* authentication, so the
`state` it carries must be resolvable with no context open, and under RLS it returns zero rows —
`V7`'s defect. Carries a **non-nullable** `company_id` as the company the attempt is *against* — a
target, like `api_token_lookup`'s, not a discriminator. Holds a SHA-256 of the `state`, the PKCE
verifier, the nonce, the provider id and an expiry, and **nothing identifying**: no email, no
subject, no user id, because at insert time none of them are known yet. Rows are single-use and
expire in 10 minutes, so the table is empty in steady state. The rejected alternative was a signed
cookie carrying the verifier, which puts a confidential client's PKCE secret in the browser and
keeps the `state`/`nonce` replay check client-side, where it is not a check |
```

- [ ] **Step 2: Commit**

```bash
git add docs/specs/2026-07-30-tcms-erd.md
git commit -m "§NNN — the ERD decision that lets an OIDC callback resolve its own attempt"
```

---

## Task 2: The migration

**Files:**
- Create: `backend/src/main/resources/db/migration/V53__external_identity_oidc.sql`

- [ ] **Step 1: Write the migration**

Match `V52`'s header convention — the `=====` rule, the *why*, and the rejected alternative.

```sql
-- =============================================================================
-- V53 — external identity: a company may authenticate its own people against
--        its own OIDC issuer, and a person may hold several identities.
--
-- Source: plan §6r, ERD "identity_provider / user_identity — M4, not V1".
--         Resolved questions #37 (Keycloak is the reference issuer), #38 (JIT
--         defaults to invitation-required), #40 (no SAML).
--
-- THE ONE THING TO UNDERSTAND BEFORE EDITING THIS FILE
--
--   Every read on this path happens BEFORE company context exists. An OIDC
--   callback carries a `state` and nothing else -- no session, no company. So
--   `app.current_company` is unset, RLS yields zero rows, and sign-in fails
--   closed for everyone, permanently. That is V7's defect exactly, and it was
--   invisible to a suite that migrated as a superuser.
--
--   The answer is V6's and V36's, not a new one: the pre-authentication lookup
--   is an INSTANCE-LEVEL table holding the minimum that answers the question,
--   while the full row and its lifecycle stay RLS-protected and are checked
--   after the context is open. `sso_login_attempt` is that table; ERD §0.1
--   carries the decision.
--
-- Rejected: putting the PKCE verifier in a signed cookie to avoid the table.
--           It hands a confidential client's PKCE secret to the browser and
--           moves the state/nonce replay check client-side, where it is not a
--           check at all.
-- =============================================================================

CREATE TABLE identity_provider (
    id                        UUID PRIMARY KEY,
    company_id                UUID NOT NULL REFERENCES company (id),
    key                       TEXT NOT NULL,
    kind                      TEXT NOT NULL,
    display_name              TEXT NOT NULL,
    issuer_uri                TEXT NOT NULL,
    client_id                 TEXT NOT NULL,
    client_secret_encrypted   BYTEA,
    jit_provisioning          BOOLEAN NOT NULL DEFAULT FALSE,
    auto_link_verified_email  BOOLEAN NOT NULL DEFAULT FALSE,
    group_claim               TEXT,
    enabled                   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMPTZ NOT NULL,
    created_by                UUID REFERENCES app_user (id),
    updated_at                TIMESTAMPTZ NOT NULL,
    updated_by                UUID REFERENCES app_user (id),
    lock_version              BIGINT NOT NULL DEFAULT 0,

    -- Not SAML: #40 resolved it out of scope, and a member nothing can create
    -- is a state the code must branch on for no reason.
    CONSTRAINT ck_identity_provider_kind CHECK (kind IN ('OIDC', 'LDAP')),

    -- jit_provisioning defaults FALSE (#38): an IdP authoritative for one
    -- organisation is not authoritative for who may enter a client's company.
    CONSTRAINT uq_identity_provider_key UNIQUE (company_id, key)
);

-- company_id LEADS, per §0.1 point 3.
CREATE INDEX identity_provider_company_enabled_idx
    ON identity_provider (company_id, enabled);

CREATE TABLE user_identity (
    id             UUID PRIMARY KEY,
    company_id     UUID NOT NULL REFERENCES company (id),
    user_id        UUID NOT NULL REFERENCES app_user (id),
    provider_id    UUID NOT NULL REFERENCES identity_provider (id),
    subject        TEXT NOT NULL,
    email_at_link  TEXT,
    linked_at      TIMESTAMPTZ NOT NULL,
    last_login_at  TIMESTAMPTZ,

    -- THE identity key. Per-company issuer trust (§6r): a DIFFERENT company's
    -- IdP asserting the same `sub` must never resolve to this user. provider_id
    -- is already company-scoped, so this is (company, issuer, subject) with the
    -- company folded into the provider -- and company_id leads the index below
    -- so the discriminator is not merely decorative.
    CONSTRAINT uq_user_identity_subject UNIQUE (provider_id, subject)
);

CREATE INDEX user_identity_company_user_idx ON user_identity (company_id, user_id);

CREATE TABLE company_email_domain (
    id          UUID PRIMARY KEY,
    company_id  UUID NOT NULL REFERENCES company (id),
    domain      TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,

    -- NOT unique instance-wide: one domain may map to several companies, which
    -- §6r requires -- the user then authenticates FIRST and chooses among their
    -- own memberships afterwards. A global unique here would make that
    -- impossible and would leak, by rejection, that some other company already
    -- claimed the domain.
    CONSTRAINT uq_company_email_domain UNIQUE (company_id, domain)
);

CREATE INDEX company_email_domain_domain_idx ON company_email_domain (domain);

ALTER TABLE identity_provider    ENABLE ROW LEVEL SECURITY;
ALTER TABLE identity_provider    FORCE  ROW LEVEL SECURITY;
ALTER TABLE user_identity        ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_identity        FORCE  ROW LEVEL SECURITY;
ALTER TABLE company_email_domain ENABLE ROW LEVEL SECURITY;
ALTER TABLE company_email_domain FORCE  ROW LEVEL SECURITY;

-- nullif(..., '') per V4: once set_config has run on a session its reset value
-- is '' rather than NULL for the rest of that session, so every POOLED
-- connection is in the second state and a bare cast raises
-- `invalid input syntax for type uuid` instead of returning nothing.
CREATE POLICY company_isolation ON identity_provider USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);
CREATE POLICY company_isolation ON user_identity USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);
CREATE POLICY company_isolation ON company_email_domain USING
    (company_id = nullif(current_setting('app.current_company', true), '')::uuid);

-- -----------------------------------------------------------------------------
-- The instance-level half. ERD §0.1 carries the decision; see the header.
-- -----------------------------------------------------------------------------
CREATE TABLE sso_login_attempt (
    id             UUID PRIMARY KEY,
    company_id     UUID NOT NULL REFERENCES company (id),
    provider_id    UUID NOT NULL REFERENCES identity_provider (id),
    state_hash     TEXT NOT NULL,
    code_verifier  TEXT NOT NULL,
    nonce          TEXT NOT NULL,
    redirect_uri   TEXT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    consumed_at    TIMESTAMPTZ,

    CONSTRAINT uq_sso_login_attempt_state UNIQUE (state_hash)
);

CREATE INDEX sso_login_attempt_expires_idx ON sso_login_attempt (expires_at);

-- -----------------------------------------------------------------------------
-- The SECOND SECURITY DEFINER function in this codebase. The first is V7's
-- findCompanyIdsForUser, and AuthenticationService's header calls it out by
-- name as "the one". Keep this one just as narrow and just as commented.
--
-- It answers exactly one question -- "which company and issuer does this
-- provider id belong to" -- for a caller that has no context yet. It takes a
-- primary key, returns four scalar columns, and reads NOTHING about a user.
-- The provider's `enabled` flag is deliberately returned rather than filtered
-- so the refusal happens in Java on the same code path as every other refusal,
-- and is therefore indistinguishable from them (§6r, trap 4).
-- -----------------------------------------------------------------------------
CREATE FUNCTION tcms_resolve_identity_provider(p_provider_id UUID)
RETURNS TABLE (company_id UUID, issuer_uri TEXT, client_id TEXT, enabled BOOLEAN)
LANGUAGE sql
STABLE
SECURITY DEFINER
SET search_path = public
AS $$
    SELECT p.company_id, p.issuer_uri, p.client_id, p.enabled
    FROM identity_provider p
    WHERE p.id = p_provider_id;
$$;

REVOKE ALL ON FUNCTION tcms_resolve_identity_provider(UUID) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION tcms_resolve_identity_provider(UUID) TO tcms_app;
```

- [ ] **Step 2: Run the schema convention gate — expect it to FAIL first**

`SchemaConventionTest` asserts `company_id NOT NULL` over every table not on the exception list,
and pins the RLS predicate on every policy. `sso_login_attempt` is instance-level, so it must be
added to the test's exception list too — the ERD entry in Task 1 is the decision, this is the
mechanism.

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.*.SchemaConventionTest'
```

Expected: FAIL naming `sso_login_attempt`. That failure is the test doing its job — it is the same
mechanism that caught `role_permission` on `V1`'s first run.

- [ ] **Step 3: Add `sso_login_attempt` to the test's exception list**

Find the exception set in `SchemaConventionTest` (the same list `api_token_lookup` and
`membership_invitation` are in) and add `sso_login_attempt` with a one-line reason pointing at
ERD §0.1.

- [ ] **Step 4: Re-run and verify green**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.*.SchemaConventionTest'
```

Expected: PASS. The policy-predicate assertion now covers 59 policies rather than 56.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V53__external_identity_oidc.sql \
        backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java
git commit -m "§NNN phase 1 — the schema an OIDC callback can actually read"
```

---

## Task 3: Entities and repositories

**Files:**
- Create: `backend/src/main/java/com/tcms/identity/IdentityProviderKind.java`
- Create: `backend/src/main/java/com/tcms/identity/IdentityProvider.java`
- Create: `backend/src/main/java/com/tcms/identity/UserIdentity.java`
- Create: `backend/src/main/java/com/tcms/identity/IdentityProviderRepository.java`
- Create: `backend/src/main/java/com/tcms/identity/UserIdentityRepository.java`

- [ ] **Step 1: Write the kind enum**

```java
package com.tcms.identity;

/** Matches {@code ck_identity_provider_kind}. No SAML member — resolved question #40. */
public enum IdentityProviderKind {
    OIDC,
    LDAP
}
```

- [ ] **Step 2: Write `UserIdentity`, factory-only like `RoleAssignment`**

`RoleAssignment` is the convention to match: plain UUID FKs (never associations — the package
comment says the graph is never navigated from an entity here), `updatable = false` on everything
structural, static factories as the only construction path, and the `@Filter`.

```java
package com.tcms.identity;

import com.tcms.tenancy.CompanyFilter;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Filter;

/**
 * One external identity, bound to one TCMS user.
 *
 * <p>The key is {@code (provider_id, subject)} and <strong>never the email</strong>: emails
 * change and get reassigned, and the IdP {@code sub} is the only stable handle (§6r). Because
 * {@code identity_provider} is itself company-scoped, that key is {@code (company, issuer,
 * subject)} — which is the property that stops a different client's IdP asserting the same
 * {@code sub} from resolving to this user. That is the multi-company account-takeover vector,
 * and it is the reason issuer trust is company-local rather than instance-wide.
 *
 * <p>{@link #emailAtLink} is a record of what the IdP said at link time. It is never read back
 * as a key.
 */
@Entity
@Table(name = "user_identity")
@Filter(name = CompanyFilter.NAME, condition = CompanyFilter.CONDITION)
public class UserIdentity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "company_id", nullable = false, updatable = false)
    private UUID companyId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "provider_id", nullable = false, updatable = false)
    private UUID providerId;

    @Column(name = "subject", nullable = false, updatable = false)
    private String subject;

    @Column(name = "email_at_link", updatable = false)
    private String emailAtLink;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** JPA only. */
    protected UserIdentity() {
    }

    public static UserIdentity link(
            UUID id,
            UUID companyId,
            UUID userId,
            UUID providerId,
            String subject,
            String emailAtLink,
            Instant now) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(
                    "An external identity needs the IdP subject — linking on email is the "
                            + "account-takeover vector §6r exists to close");
        }
        UserIdentity identity = new UserIdentity();
        identity.id = id;
        identity.companyId = companyId;
        identity.userId = userId;
        identity.providerId = providerId;
        identity.subject = subject;
        identity.emailAtLink = emailAtLink;
        identity.linkedAt = now;
        return identity;
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getProviderId() {
        return providerId;
    }

    public String getSubject() {
        return subject;
    }

    public String getEmailAtLink() {
        return emailAtLink;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
```

- [ ] **Step 3: Write the repositories — `@Transactional(readOnly = true)` is mandatory**

CLAUDE.md: isolation is established in `doBegin`, so a repository needs a transaction. Spring Data
gives one only to methods **inherited** from `SimpleJpaRepository`, never to derived or `@Query`
methods **declared on the interface**. Without the annotation those run outside any transaction,
`app.current_company` is never set, and every RLS-protected read returns **zero rows** while
`CompanyContext` holds the right company. `RepositoryTransactionTest` derives the repository list
from the live context, so omitting it fails the build.

```java
package com.tcms.identity;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

@Transactional(readOnly = true)
public interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    Optional<UserIdentity> findByProviderIdAndSubject(UUID providerId, String subject);
}
```

Write `IdentityProviderRepository` the same way, with
`Optional<IdentityProvider> findByIdAndEnabledTrue(UUID id)` and
`List<IdentityProvider> findByCompanyIdAndEnabledTrue(UUID companyId)`.

- [ ] **Step 4: Boot the context to catch entity drift**

`hibernate.ddl-auto: validate`, so booting is itself the gate — a column mismatch fails at startup.

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.*.RepositoryTransactionTest'
```

Expected: PASS, with the two new repositories in the derived list.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/tcms/identity/
git commit -m "§NNN phase 2 — the federation entities, keyed on the subject and nothing else"
```

---

## Task 4: `FederatedIdentityService` — the §6r policy engine

This is the highest-value unit-test target in the plan: it is pure policy, it has no I/O, and
every branch is a §6r rule. Test-first (`ep-test-driven-development`).

**Files:**
- Create: `backend/src/main/java/com/tcms/identity/FederatedIdentityService.java`
- Test: `backend/src/test/java/com/tcms/identity/FederatedIdentityServiceTest.java`

- [ ] **Step 1: Write the failing tests**

JUnit 5 + Mockito + AssertJ, **no Spring context** — this is the `be-java` Phase 3 lane. Inject
`Clock` and an id supplier rather than calling `Instant.now()` / `UUID.randomUUID()`.

```java
@Test
void anUnverifiedEmailNeverLinksEvenWhenAutoLinkIsOn() {
    IdentityProvider provider = providerWith(autoLink(true), jit(false));
    when(users.findByEmail("kim@acme.test")).thenReturn(Optional.of(existingUser));

    Resolution resolution = service.resolve(
            provider, new Claims("sub-1", "kim@acme.test", false), COMPANY);

    assertThat(resolution).isEqualTo(Resolution.refused());
    verify(identities, never()).save(any());
}

@Test
void aVerifiedEmailLinksToTheExistingUserRatherThanCreatingASecond() {
    IdentityProvider provider = providerWith(autoLink(true), jit(false));
    when(users.findByEmail("kim@acme.test")).thenReturn(Optional.of(existingUser));

    Resolution resolution = service.resolve(
            provider, new Claims("sub-1", "kim@acme.test", true), COMPANY);

    assertThat(resolution.userId()).isEqualTo(existingUser.getId());
    assertThat(captured(identities).getSubject()).isEqualTo("sub-1");
}

@Test
void firstLoginWithoutJitOrAnInvitationIsRefused() {
    IdentityProvider provider = providerWith(autoLink(false), jit(false));
    when(users.findByEmail(any())).thenReturn(Optional.empty());

    assertThat(service.resolve(provider, new Claims("sub-1", "new@acme.test", true), COMPANY))
            .isEqualTo(Resolution.refused());
}

@Test
void jitProvisioningCreatesAViewerWithNoProjectAccess() {
    IdentityProvider provider = providerWith(autoLink(false), jit(true));
    when(users.findByEmail(any())).thenReturn(Optional.empty());

    Resolution resolution =
            service.resolve(provider, new Claims("sub-1", "new@acme.test", true), COMPANY);

    assertThat(resolution.provisioned()).isTrue();
    assertThat(captured(roleGrants).getRoleId()).isEqualTo(VIEWER_ROLE_ID);
    assertThat(captured(roleGrants).getProjectId()).isNull();
}

@Test
void aDeactivatedUserIsRefusedRatherThanReactivated() {
    IdentityProvider provider = providerWith(autoLink(true), jit(true));
    when(users.findByEmail(any())).thenReturn(Optional.of(deactivatedUser));

    assertThat(service.resolve(provider, new Claims("sub-1", "kim@acme.test", true), COMPANY))
            .isEqualTo(Resolution.refused());
}

@Test
void anExistingIdentityWinsBeforeAnyLinkingPolicyIsConsulted() {
    IdentityProvider provider = providerWith(autoLink(false), jit(false));
    when(identities.findByProviderIdAndSubject(provider.getId(), "sub-1"))
            .thenReturn(Optional.of(existingIdentity));

    Resolution resolution =
            service.resolve(provider, new Claims("sub-1", "moved@elsewhere.test", true), COMPANY);

    assertThat(resolution.userId()).isEqualTo(existingIdentity.getUserId());
    verify(users, never()).findByEmail(any());
}
```

That last test is the one that matters most and is easiest to omit: **an already-linked subject
must resolve without consulting email at all**, or a user whose corporate address changed loses
their account and their authorship.

- [ ] **Step 2: Run and verify they fail**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.identity.FederatedIdentityServiceTest'
```

Expected: FAIL — `FederatedIdentityService` does not exist.

- [ ] **Step 3: Implement, in the order the tests fix**

Resolution order is load-bearing and is the §6r policy verbatim:

1. `findByProviderIdAndSubject` → hit means done. **Email is never consulted.**
2. Miss + `auto_link_verified_email` **and** the IdP reported `email_verified` **and** a user with
   that email exists **and** is `isActive()` → link.
3. Miss + `jit_provisioning` → create the user and a **Viewer, company-scope, no project access**
   grant (the same default as an invitation, §6b — SSO must not be a side door to more capability
   than the invite flow grants).
4. Anything else → `Resolution.refused()`, one refusal, no reason.

- [ ] **Step 4: Run and verify green**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.identity.FederatedIdentityServiceTest'
```

Expected: PASS, 6 tests, no `UnnecessaryStubbingException`.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/tcms/identity/FederatedIdentityService.java \
        backend/src/test/java/com/tcms/identity/FederatedIdentityServiceTest.java
git commit -m "§NNN phase 3 — link, provision or refuse, and the subject is consulted first"
```

---

## Task 5: OIDC protocol — discovery, token exchange, id_token verification

**Files:**
- Create: `backend/src/main/java/com/tcms/auth/OidcDiscovery.java`
- Create: `backend/src/main/java/com/tcms/auth/OidcClient.java`
- Create: `backend/src/main/java/com/tcms/auth/OidcIdTokenVerifier.java`
- Test: `backend/src/test/java/com/tcms/auth/OidcIdTokenVerifierTest.java`

- [ ] **Step 1: Write the verifier's failing tests**

The verifier is the security boundary and is pure logic given a decoder — so it is unit-testable
with a locally-generated RSA key and no network.

```java
@Test
void aTokenFromAnotherIssuerIsRejected() { … }

@Test
void aTokenForAnotherClientIsRejected() { … }

@Test
void aReplayedNonceIsRejected() { … }

@Test
void anExpiredTokenIsRejected() { … }

@Test
void aCognitoAccessTokenIsRejectedBecauseItCarriesNoAudience() {
    // The standard Cognito trap (§6r): its ACCESS tokens carry no `aud` and must be
    // distinguished by `token_use`. We validate the ID token, so a token with no
    // audience is refused rather than leniently accepted.
}
```

- [ ] **Step 2: Run and verify they fail**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.auth.OidcIdTokenVerifierTest'
```

Expected: FAIL — type does not exist.

- [ ] **Step 3: Implement with `NimbusJwtDecoder` — no new dependency**

`NimbusJwtDecoder.withJwkSetUri(jwksUri).build()`, plus a
`DelegatingOAuth2TokenValidator` of `JwtTimestampValidator`, `JwtIssuerValidator`, an audience
validator and a nonce validator. Cache the decoder per provider id — building one per callback
refetches the JWKS on every sign-in.

- [ ] **Step 4: Run and verify green**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.auth.OidcIdTokenVerifierTest'
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Implement discovery and token exchange**

`OidcDiscovery` fetches `{issuer}/.well-known/openid-configuration` with `RestClient` and caches
per issuer with a TTL. `OidcClient` posts the authorization code with `client_secret_basic` and
the PKCE `code_verifier`.

**Both need a timeout.** An IdP that accepts the connection and never answers otherwise hangs a
request thread until the container's own limit; set connect and read timeouts explicitly on the
`RestClient` builder.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/tcms/auth/Oidc*.java \
        backend/src/test/java/com/tcms/auth/OidcIdTokenVerifierTest.java
git commit -m "§NNN phase 4 — the OIDC protocol half, and the audience check Cognito needs"
```

---

## Task 6: The endpoints

**Files:**
- Create: `backend/src/main/java/com/tcms/auth/SsoController.java`
- Create: `backend/src/main/java/com/tcms/auth/SsoLoginService.java`
- Modify: `docs/specs/openapi.yaml`

- [ ] **Step 1: Add the operations to `openapi.yaml` — contract first**

Three operations under the already-`permitAll` prefix:

| Operation | Shape |
|---|---|
| `POST /auth/providers` | `{ email }` → `{ providers: [{ id, displayName }] }` — **always 200**, and an empty list for an unmapped domain, a mapped-but-disabled provider, and a domain that maps to nothing. Never 404. |
| `GET /auth/sso/{providerId}/authorize` | 302 to the IdP with `state`, `nonce`, `code_challenge` |
| `GET /auth/sso/{providerId}/callback` | `?code&state` → 302 to the SPA with the session, or the one generic failure |

**Every schema you add needs its `required` array**, including on inline nested objects —
`required` does not descend, and without it every generated property is optional and no consumer
can use it (CLAUDE.md, working agreement).

- [ ] **Step 2: Regenerate BOTH copies of `schema.d.ts`**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
```

`schemaFreshness.test.ts` compares them byte-for-byte and fails otherwise.

- [ ] **Step 3: Implement `SsoLoginService`**

The flow, with the refusal rule from trap 4 applied at every exit:

```
authorize:  resolve provider by id (SECURITY DEFINER, no context)
            → open CompanyContext for the resolved company
            → build state + nonce + PKCE verifier
            → INSERT sso_login_attempt (state_hash only)
            → 302

callback:   look up attempt by SHA-256(state)   [instance-level, no context]
            → refuse if consumed, expired, or absent
            → mark consumed BEFORE the token exchange (single-use, replay-safe)
            → open CompanyContext for the attempt's company
            → exchange code, verify id_token, check nonce
            → FederatedIdentityService.resolve(...)
            → SessionTokens.issue(userId, companyId, permissionVersion, false)
```

**Mark the attempt consumed before the token exchange, not after.** Doing it after leaves a window
where two concurrent callbacks with the same `state` both pass the check.

- [ ] **Step 4: Verify the endpoint-access gate**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.*.EndpointAccessTest'
```

Expected: PASS, with the three new routes recognised as deliberately open and
`/identity-providers` as authenticated.

- [ ] **Step 5: Commit**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts \
        packages/client/src/schema.d.ts backend/src/main/java/com/tcms/auth/Sso*.java
git commit -m "§NNN phase 5 — three endpoints, one refusal, and a single-use state"
```

---

## Task 7: Provider CRUD for the Company Admin

**Files:**
- Create: `backend/src/main/java/com/tcms/identity/IdentityProviderService.java`,
  `IdentityProviderController.java`, `IdentityProviderInput.java`, `IdentityProviderView.java`,
  `ProviderSecrets.java`
- Test: `backend/src/test/java/com/tcms/identity/IdentityProviderServiceTest.java`

- [ ] **Step 1: Write the failing validation tests**

```java
@Test
void theClientSecretIsNeverReturnedOnAnyReadPath() { … }

@Test
void aProviderWhoseIssuerIsNotHttpsIsRefused() { … }

@Test
void savingIsGatedOnCompanyIdp() { … }
```

`company:idp` has been seeded since `V3` with **no consumer** — this plan is its first one. Gate
on the permission, never on a role name (§6b), and there is no wildcard permission.

- [ ] **Step 2: Run and verify they fail, then implement**

`ProviderSecrets` encrypts `client_secret_encrypted` with a key from configuration. **It is
encryption, not hashing** — unlike `ApiTokenSecrets`, the plaintext must be recoverable to
perform a token exchange, so `InvitationTokens`' SHA-256 digest is the wrong tool here and reusing
it would make the secret unusable. Say so in the class header, because the neighbouring type does
the opposite and the asymmetry is the confusing part.

The secret is never in a response, never in an audit event, never in a log line, never in an error
message (§6r).

- [ ] **Step 3: Run the full gate**

```bash
~/bin/tcms-remote backend ./gradlew build
```

Expected: BUILD SUCCESSFUL, 2709 + N tests, 0 failures.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/tcms/identity/ backend/src/test/java/com/tcms/identity/
git commit -m "§NNN phase 6 — company:idp gets its first consumer, five migrations later"
```

---

## Task 8: Keycloak in compose, and the verification rows

**Files:**
- Modify: `compose.yaml`, `.env.example`
- Modify: `docs/verification/backend-mutation-checks.md`,
  `docs/verification/frontend-browser-checks.md`
- Modify: `docs/agent-context/be-java.md`, `docs/HANDOFF.md` + `docs/handoff/NNN-<slug>.md`

- [ ] **Step 1: Add Keycloak to compose**

#37 resolved Keycloak as the reference issuer specifically so this lane's counterparty is a real
issuer rather than a mock. Add a realm export with one client and two users so the flow is
runnable from a clean `docker compose up`.

- [ ] **Step 2: Append the verification rows — write them, run nothing**

§65 is standing policy: browser passes and backend mutation checks are **deferred and batched**.
Ship the functionality, record what you *would* have driven, run none of it. §72–§76 each ran a
handful inline and recorded them `✅ RUN`, which made the exception look like the rule; it was not.

Rows to add to the backend queue, each executable later:

- Configure a provider as Company Admin; assert the secret is absent from every read path.
- Sign in via Keycloak as an invited-but-never-activated user → refused, generic message.
- Sign in with `jit_provisioning` on → user created as Viewer with **no** project access.
- Sign in twice; assert one `user_identity` row and `last_login_at` moved.
- Replay a consumed `state` → refused.
- Company A's provider asserting a `sub` that exists in company B → **must not** resolve to B's
  user. This is the account-takeover vector and it is the single most important row in the queue.

- [ ] **Step 3: Update the orientation cache in this slice's own commit**

Per `ep-agent-orientation-cache` Phase 3: correct what the run disproved, **add the seams the run
created** (the second `SECURITY DEFINER` function, `sso_login_attempt`'s exception-list entry, the
new repositories and their `@Transactional` boundary, `ProviderSecrets` as distinct from
`ApiTokenSecrets`), re-stamp `Last verified` + `Baseline commit`, prepend one session-log line.

Re-read the file first — the org-chart lane writes it too.

- [ ] **Step 4: Commit and push**

```bash
git add compose.yaml .env.example docs/
git commit -m "§NNN phase 7 — the handoff section, the index row, both caches and the queue rows"
git push
```

---

## Plan 2 (sketch) — claim → role mapping

Gets its own file. The shape, so plan 1 does not accidentally block it:

- `claim_role_mapping` — `(provider_id, claim_name, claim_value)` → `role_id | team_id`,
  `project_id` nullable, company-owned, FORCE RLS.
- `role_assignment` gains `source` (`LOCAL · IDP`) and `provider_id`. **Every column on that
  entity is currently `updatable = false` with factory-only construction** — so IdP-derived grants
  are reconciled by insert/delete, never update, and need their own factory.
- **A mapping that could grant `SYSTEM` scope is rejected at save time**, not filtered at login —
  an IdP group must never mint an Instance Admin.
- **A mapping may not name a non-assignable role.** Three rows have `assignable = false`
  (`Company Member` `V15`, `Agent` `V40`, `Automation Service` `V41`) and `RoleGrantService` is
  the only path that may read that flag. A claim mapping is a fourth grant path and must ask the
  same question.
- Reconciliation on login **must bump `permission_version`**, or the session cache serves the old
  grant set until expiry.
- Entra group-claim overage past ~150–200 groups substitutes a Graph pointer for the `groups`
  claim — handle it explicitly or document the limitation, because unhandled it silently stops
  mapping **for exactly the users in the most groups**.

## Plan 3 (sketch) — LDAP / Active Directory

- ~~**`ldap://` is refused unless an explicit `allowInsecure` flag is set** for lab use.~~ →
  **superseded 2026-08-15 by `docs/plans/2026-08-15-m4-external-identity-ldap-ad.md` D2: there is no
  such flag.** A URL must be `ldaps://`, or `ldap://` with StartTLS. The exposure a plain bind
  creates is the *user's own directory password* — the credential to the customer's AD, and very
  often to everything else they own — which is a strictly larger blast radius than §219's http issuer
  question, where what was at risk was the signing-key URL. The lab needs no exception because
  UnboundID's in-memory server serves LDAPS, so the tests drive the encrypted path rather than one
  production never takes.
- **Key on `objectGUID` (AD) / `entryUUID` (OpenLDAP), never the DN.** A DN encodes tree position,
  so moving someone between OUs orphans the account and its authorship. Store the DN as a mutable
  display/re-bind attribute.
- Slots into `user_identity` unchanged as `(company, provider, objectGUID)` — **no schema change**,
  which is what makes it cheap.
- Groups come from a search (`memberOf`, or `member=<dn>`), configurable — directories differ and
  `memberOf` is not always populated. **Nested groups need bounded recursive resolution**;
  unbounded recursion on a large directory is a real outage.
- Referrals and paging: AD caps result sizes, and unhandled this **silently truncates group
  resolution for exactly the largest groups**.
- Deprovisioning is a scheduled poll, not a push — and it **deactivates, never deletes** (§6j
  authorship).
- Needs `spring-boot-starter-ldap`; plan 1 adds no dependency, this one does.

---

## Self-review

**Spec coverage.** §6r "Identity providers" → Tasks 2–7. "Provider configuration is per company"
→ Task 2 (`company_id` + RLS), Task 7 (CRUD gated on `company:idp`). "Local accounts never go
away" → `AuthenticationService` and `SecurityConfiguration` explicitly unmodified. "Flow" →
Tasks 5–6 (PKCE, TCMS-issued session, no IdP token to the SPA). "Account identity, linking,
provisioning" → Task 4. "Claim → role mapping" → **plan 2, deliberately deferred**. LDAP →
**plan 3**. "Outbound integration credentials" → **out of scope for all three plans**; it is a
different lifecycle from a login session and belongs with the `IssueTracker` adapters.

**Gaps deliberately left, and why.** RP-initiated logout at the IdP is opt-in per provider (§6r)
and is not in plan 1 — local session invalidation already works and the opt-in is a provider
column added when the UI needs it. Per-company `/{companySlug}/login` URLs are §6r's "better path
where clients expect their own entry point" but are additive over `POST /auth/providers`; not
needed to ship a working sign-in. The frontend login-screen work is a `fe-react` lane, not this
one.

**The plain-HTTP issuer decision, taken 2026-08-15 (§217 phase 7 raised it and deferred it).**
`IdentityProviderService.validate` keeps refusing any `issuerUri` that is not `https://`, and the
compose Keycloak keeps being registered by SQL. A development-only escape hatch was considered and
rejected: the gate protects the URL the `jwks_uri` is read from, so an instance that can be talked
into an http issuer is an instance where a machine on the path nominates the signing keys — and a
property that is only safe when a profile is set is one restart away from being set in production.
~~Plan 3's `allowInsecure` sketch is for an LDAP *bind*, which is a different exposure with a different
blast radius, and it is not a precedent for this.~~ → **the deferral closed 2026-08-15 the stricter
way**: argued on its own ground, the bind exposure is *larger*, so plan 3 ships no such flag at all
(D2). The reasoning above is unaffected — it was never resting on the sketch. The cost is one documented `INSERT` for the one
issuer that is deliberately not production, and `deploy/keycloak/README.md` carries it.

**Placeholder scan.** No TBD/TODO. Task 5 steps 5 and Task 7 step 2 describe implementations
without full listings — they are the two mechanical, well-trodden pieces (an HTTP GET with a cache,
and AES encryption behind an interface), and every rule that constrains them is stated. Every
step that carries a *decision* carries its code.

**Type consistency.** `FederatedIdentityService.resolve(IdentityProvider, Claims, UUID)` returns
`Resolution` throughout Tasks 4 and 6. `UserIdentity.link(...)` is the only constructor and is used
in Task 4 step 3. `IdentityProviderKind` has `OIDC`/`LDAP` in Task 3 and matches
`ck_identity_provider_kind` in Task 2. `tcms_resolve_identity_provider` is named identically in
Task 2 and Task 6 step 3.

**Numbering.** `§NNN` in the commit messages is a placeholder for the handoff section number, which
is assigned when the slice starts — it is the one thing that cannot be known while the org-chart
lane is in flight, since sections are allocated in landing order.
