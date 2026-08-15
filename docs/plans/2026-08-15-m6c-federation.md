# M6 Plan C — Federation: Providers, Identities, Claim Mapping and Login Routing

**Date:** 2026-08-15 · **Written by:** `ep-writing-plans` · **Master plan:** `docs/plans/2026-08-15-m6-multi-company-activation.md`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans`. **Checkpoint after every
> task.** Load `/be-java`, plus `be-java-database-code` for Tasks 2–3, `data-postgresql` and
> `data-migrations` for the migration, and **`auth-oidc-concepts`** before Task 5 — it is the
> library's own reference for the protocol and this plan does not restate it. `ep-code-comments`
> governs every comment.

**Depends on:** Plan A (`CompanyRef`, `GET /me/companies`, the switch endpoint — Task 9's
disambiguation reuses all three).

**Goal:** Build §6r's federation seam and the M6 login routing on top of it, so a company can
authenticate its own people against its own identity provider and a person whose email domain maps
to a company reaches that company's login without any pre-authentication screen ever listing a
company.

**Architecture:** One generic OIDC client driven by per-provider configuration rows — **not seven
integrations**. Identity is `(company_id, issuer, subject)`, never email. TCMS mints its own session
after the callback and never forwards the IdP token to the SPA, so §6b's permission model is
untouched. IdP groups map to **roles or teams, never permissions**, and an unmapped claim grants
nothing.

**Tech Stack:** Spring Boot 3.5.6 · **`spring-boot-starter-oauth2-client` (a new dependency — see
Task 1)** · Java 21 · Flyway (**V55**, pre-assigned) · React 19 · Vitest + RTL

**Fixture UUID prefix for this plan:** `d2000000-…`. **Slug namespace for new tests:** `m6c-`.

---

## Read this before Task 1: the problem that shapes the whole plan

**Every table this plan adds is company-owned, and every one of them must be read before a company
context can exist.**

That is not a detail. `identity_provider` carries `company_id` (§6r: *"a Company Admin configures
their own and cannot see another's"*), so it is RLS-forced like every other company-owned table. But
the two moments it is read are:

1. **Login routing** — an unauthenticated person types an email, and the server must resolve the
   domain to a company and that company's providers. There is no session, so no `co` claim, so
   `CompanyContextFilter` has opened nothing, so `app.current_company` is unset and **every read
   returns zero rows**. Fail-closed, silently, exactly as `V4`'s `nullif` note describes.
2. **The OIDC callback** — the `state` comes back before any TCMS session exists.

This repository has met this problem once before and the answer is on record. `V7` solved the
identical shape (`tcms_companies_for_user`) with `SECURITY DEFINER` and **returned nothing in
production**, because `FORCE ROW LEVEL SECURITY` covers the table owner too. `V8` is the fix, and it
is not a bypass: it is a `SECURITY INVOKER` function that opens each candidate company's context in
turn with `set_config(..., is_local => true)`, asks the ordinary fully-policed question, and
**restores the GUC at the end** — because a function that silently repoints its caller's tenancy
discriminator is a cross-company read that looks completely ordinary afterwards.

**Do not invent a third approach.** Read `V8__companies_for_user_without_definer_rights.sql`'s header
in full before writing Task 2's migration, and follow its shape for `tcms_providers_for_email_domain`.
If you find yourself reaching for `SECURITY DEFINER`, `BYPASSRLS`, or a second datasource, stop and
report — all three have been considered and one of them shipped a defect that 171 green tests missed.

**The alternative worth weighing, and my recommendation.** `company_email_domain` could be made an
**instance-level table** on the ERD §0.1 exception list, exactly as `membership_invitation` is, on
the identical reasoning — *the accept path runs before authentication, so no company context can
exist*. That makes routing a plain indexed lookup and removes the function entirely.
`identity_provider` **cannot** take that route: it holds client secrets and per-company claim
mappings, and putting it outside the company boundary to save a function is the wrong trade. So:

- **`company_email_domain` → instance-level, §0.1 exception list, one more row on the list with the
  same reason as its neighbour.** Requires a decision, per §0.1, and Task 2 Step 1 asks for it.
- **`identity_provider` / `user_identity` / `claim_role_mapping` → company-owned, RLS-forced**, read
  through a `V8`-shaped function on the pre-authentication paths only.

---

## Scope — what this plan does not build, and why

Carried from the master plan's D1, restated here because this is the file an implementer reads:

- **No LDAP / Active Directory.** Different protocol (a bind, not a redirect), different stable key
  (`objectGUID`/`entryUUID`, never the DN), different group resolution (a search with nested-group
  recursion and AD referrals/paging), different deprovisioning (a scheduled poll). It reuses
  `user_identity` and `claim_role_mapping` **unchanged**, so it is additive later at no retrofit cost.
- **No SAML 2.0.** §6r already says it is *"a second code path — build only on demand, and say so
  rather than implying parity."*
- **No outbound integration credentials** (`integration_credential`, Jira/Slack/GitHub 3LO). §6r's
  own first table separates them: they answer *"may TCMS act against Jira"*, not *"who is this
  human"*, and they belong with the integrations they authorize.
- **No SCIM.** §6r: deprovisioning in v1 is deactivation, not deletion, and SCIM is a later addition.

---

## File Structure

**Backend — create**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V55__federation.sql` | Four tables, one function, one column on `role_assignment`. |
| `identity/IdentityProvider.java` · `IdentityProviderRepository.java` | The per-company provider config. |
| `identity/UserIdentity.java` · `UserIdentityRepository.java` | `(company_id, issuer, subject) → app_user`. |
| `identity/ClaimRoleMapping.java` · `ClaimRoleMappingRepository.java` | `(provider, claim, value) → role \| team, scope`. |
| `identity/CompanyEmailDomain.java` · `CompanyEmailDomainRepository.java` | The routing map. Instance-level. |
| `identity/IdentityProviderService.java` · `IdentityProviderController.java` | Provider CRUD, gated on `company:idp`. |
| `auth/oidc/OidcLoginService.java` | Authorization request, state/PKCE, callback, token exchange. |
| `auth/oidc/OidcClaims.java` | Issuer, `token_use`, `aud`, `hd`, `nonce` validation — the provider traps, in one place. |
| `auth/oidc/ClaimRoleResolver.java` | Claims → grants. The seam where SSO meets §6b. |
| `auth/oidc/JitProvisioning.java` | Create-or-link, per-provider policy. |
| `auth/LoginRoutingService.java` · `LoginRoutingController.java` | `POST /api/v1/auth/login/routing`. |

**Backend — modify**

`backend/build.gradle.kts` + `gradle/libs.versions.toml` (the OAuth2 client starter),
`auth/SecurityConfiguration.java` (permit the callback path), `tenancy/SchemaConventionTest`'s
exception list, `docs/specs/2026-07-30-tcms-erd.md` §0.1, `docs/specs/openapi.yaml`.

**Frontend — create**

`src/features/auth/CompanyLoginPage.tsx` (`/{slug}/login`), `src/features/auth/ProviderButtons.tsx`,
`src/features/auth/ChooseCompanyPage.tsx`, `src/features/settings/IdentityProvidersPage.tsx`, each
with a `.test.tsx`.

**Frontend — modify**

`src/features/auth/LoginPage.tsx` (the domain-routing step), `src/app/router.tsx`,
`src/api/queries/keys.ts`, a new `src/api/queries/identity.ts`.

---

## Task 1: The OAuth2 client dependency, and a boot that proves nothing changed

The project has `spring-boot-starter-oauth2-resource-server` and **not** `oauth2-client`
(`build.gradle.kts:29`). Adding the client starter brings a large autoconfiguration surface into a
security configuration that is currently hand-assembled, and the realistic failure is that it
silently reorders or supplements the filter chain. So it lands **alone**, with a test whose whole job
is to say the existing chain is unchanged.

**Files:**
- Modify: `gradle/libs.versions.toml`, `backend/build.gradle.kts`
- Test: `backend/src/test/java/com/tcms/auth/SecurityChainShapeTest.java`

- [ ] **Step 1: Write the failing test first — against the CURRENT chain**

```java
package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.FilterChainProxy;

/**
 * The security filter chain is the shape this application assembled, and nothing else.
 *
 * <p>Written when {@code spring-boot-starter-oauth2-client} was added (§M6C). That starter brings
 * autoconfiguration into a chain this project builds by hand, and the realistic failure is not a
 * compile error — it is an extra filter appearing, or {@code CompanyContextFilter} moving relative
 * to the one that authenticates. Both would be silent, and both would be a tenancy defect rather
 * than an authentication one, because everything downstream reads the company this chain resolves.
 */
@DisplayName("The security filter chain")
class SecurityChainShapeTest extends IntegrationTestBase {

    @Autowired private FilterChainProxy chains;

    @Test
    @DisplayName("carries our four filters, in this order, relative to the bearer filter")
    void carriesOurFiltersInOrder() {
        // Assert on the ORDER of the four this project registers, and on the relative
        // position of CompanyContextFilter and SuspendedCompanyFilter (Plan B). Do NOT
        // assert the full list — Spring's own filters change between minor versions and a
        // full-list assertion would be a false alarm every upgrade.
    }
}
```

> **Fill in the body against what the chain actually contains** — run it once printing the class
> names, then write the assertion. The comment above is the part that must not be lost.

- [ ] **Step 2: Run it and watch it pass** — this is the baseline, so it must be green *before* the
  dependency lands. Record the printed order in the test's javadoc.

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.SecurityChainShapeTest'
```

- [ ] **Step 3: Add the dependency**

In `gradle/libs.versions.toml`, beside `spring-boot-starter-oauth2-resource-server`, add the
`spring-boot-starter-oauth2-client` alias (same Spring Boot BOM version — do not pin a separate one).
Then in `backend/build.gradle.kts`, after line 29:

```kotlin
    implementation(libs.spring.boot.starter.oauth2.client)
```

- [ ] **Step 4: Run the test again**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.SecurityChainShapeTest'
```
Expected: **PASS, unchanged.** If it fails, the starter has altered the chain and that is the finding
this task exists to surface — report it before continuing, do not adjust the assertion to match.

- [ ] **Step 5: Run the whole suite**

```bash
cd backend && ./gradlew build
```
Expected: BUILD SUCCESSFUL. Watch for `EndpointAccessTest` — it is the class that would notice a
newly-permitted path.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml backend/build.gradle.kts \
        backend/src/test/java/com/tcms/auth/SecurityChainShapeTest.java
git commit -m "build(auth): add the OAuth2 client starter, and pin the chain shape first

The starter brings autoconfiguration into a hand-assembled chain. The test lands
before it and asserts the four filters this project registers are still in order,
because a filter moving relative to CompanyContextFilter is a tenancy defect that
would not look like one.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Task 2: `V55` — the four tables, and the §0.1 decision

**Files:**
- Modify: `docs/specs/2026-07-30-tcms-erd.md` (§0.1 exception list, and the `identity_provider` section at `:164`)
- Create: `backend/src/main/resources/db/migration/V55__federation.sql`
- Test: `backend/src/test/java/com/tcms/tenancy/SchemaConventionTest.java` (existing rule; its exception list is edited)
- Test: `backend/src/test/java/com/tcms/identity/FederationSchemaTest.java` (new)

- [ ] **Step 1: Take the §0.1 decision, in writing, first**

Add `company_email_domain` to the ERD §0.1 RLS exception list with this reason:

> **`company_email_domain`** — instance-level, for the same reason `membership_invitation` is: it is
> read **before authentication**, by the login form resolving an email domain to a company, so no
> company context can exist at the moment it must answer. Making it company-owned would mean either a
> `V8`-shaped per-company probe on every keystroke of a login form, or a bypass. It holds no client
> content: a domain and a company id. The disclosure it *could* create — "which companies exist" — is
> closed on the read side instead, because the routing endpoint answers identically whether the
> domain maps to one company, several, or none (§6r).

**`SchemaConventionTest` derives its list from §0.1, so §0.1 is edited first and the test follows.**

- [ ] **Step 2: Write the failing schema test**

```java
@Test
@DisplayName("user_identity is keyed per company, which is the account-takeover vector")
void userIdentityIsKeyedPerCompany() throws Exception {
    // §6r: "a DIFFERENT company's IdP asserting the same `sub` must never resolve to that
    // user. Key user_identity on (company_id, issuer, subject) and treat issuer trust as
    // company-local. Getting this wrong is the multi-company account-takeover vector."
    assertThat(uniqueConstraintColumnsOn("user_identity"))
            .containsExactly("company_id", "issuer", "subject");
}

@Test
@DisplayName("identity_provider is company-owned and RLS-forced like everything else")
void identityProviderIsCompanyOwned() throws Exception {
    assertThat(hasForcedRlsPolicyOn("identity_provider")).isTrue();
}

@Test
@DisplayName("a claim mapping cannot name a SYSTEM-scope role")
void aClaimMappingCannotMintAnOperator() throws Exception {
    // §6r: "a mapping rule that could grant SYSTEM scope is rejected at save time, not just
    // filtered at login — an IdP group must never be able to mint an Instance Admin."
    // The DB-level half is a CHECK; the service-level half is Task 6.
}
```

> `uniqueConstraintColumnsOn` / `hasForcedRlsPolicyOn` are helpers — **`SchemaConventionTest` already
> reads `pg_catalog` for exactly these questions across all 56 policies.** Open it and reuse its
> helpers rather than writing a second set.

- [ ] **Step 3: Write the migration**

`V55__federation.sql`. Every rule this repo enforces mechanically applies, and the header must say so:

- `company_id UUID NOT NULL` on `identity_provider`, `user_identity`, `claim_role_mapping`; **leading
  every composite index and every uniqueness constraint.**
- `ALTER TABLE … ENABLE ROW LEVEL SECURITY` **and `FORCE`** on all three — the owner bypasses plain
  `ENABLE`, which is why `FORCE` is not optional.
- The policy predicate is the one all 56 existing policies use, `nullif(current_setting(...), '')`
  wrapped — **copy it verbatim from a neighbouring policy in `V1`**, because an unset GUC must yield
  **zero** rows and there are two spellings of "unset", only one of which is NULL (`V4`).
- `company_email_domain` gets **no** `company_id`-based policy and **no** RLS, and its header says why
  and points at §0.1.
- `role_assignment` gains `source TEXT NOT NULL DEFAULT 'LOCAL' CHECK (source IN ('LOCAL','IDP'))` —
  §6r: *"mapped grants are marked IdP-derived and are read-only in the UI. Otherwise an admin edits a
  grant that the next login silently overwrites."*
- Client secrets: a `client_secret_encrypted BYTEA` column, **never plaintext**. §6r: *"never
  plaintext in Postgres, never in an audit event, never in an error message or log line."* The key
  comes from configuration — and per §108, a required Spring property is `${VAR}` **with no colon**;
  `${VAR:?msg}` is a shell form Spring reads as a default. `ConfigConventionTest` reads the text and
  will catch it.
- `tcms_providers_for_email_domain(text)` — the `V8`-shaped `SECURITY INVOKER` function. Its header
  must carry `V8`'s three lessons: why not `SECURITY DEFINER`, why the GUC is restored, and why the
  cost is acceptable (a handful of companies).

- [ ] **Step 4: Run the schema tests**

```bash
cd backend && ./gradlew test --tests 'com.tcms.tenancy.SchemaConventionTest' \
                            --tests 'com.tcms.identity.FederationSchemaTest'
```
Expected: PASS. `SchemaConventionTest` asserts `company_id NOT NULL` over every table not on the §0.1
list and pins the policy predicate on every policy — so four new tables either comply or the build
stays red, which is the mechanism.

- [ ] **Step 5: Update the ERD's own federation section**

`docs/specs/2026-07-30-tcms-erd.md:164` currently reads *"`identity_provider` / `user_identity` /
`claim_role_mapping` — **M4, not V1**"* and *"Not in `V1__initial_schema.sql` — external identity
ships with the integrations phase, and these tables arrive in that migration."* Rewrite it: they
arrive in `V55`, in M6, because M6's login routing had nothing to route to. Strike the old sentence
rather than deleting it — the working agreement.

Also `:837`'s *"Also not in V1, by phase decision"* paragraph names all four (`role_assignment.source`
included) and needs the same treatment.

- [ ] **Step 6: Full gates and commit.**

---

## Task 3: Provider configuration — entities, repositories, CRUD under `company:idp`

`company:idp` has been seeded since `V3:94` and granted to Company Admin at `V3:194`, and **nothing
enforces it**. This task gives the product's most-deferred permission its first consumer.

**Files:** the four entity/repository pairs, `IdentityProviderService`, `IdentityProviderController`,
`docs/specs/openapi.yaml`, and `backend/src/test/java/com/tcms/identity/IdentityProviderEndpointTest.java`.

- [ ] **Step 1: Write the failing tests** — owns `m6c-idp-`.

  - A Company Admin may create, list, update and delete a provider **in their own company**.
  - A Test Lead may not (403) — `company:idp` is COMPANY scope and Lead is PROJECT scope.
  - **A provider from another company is not listed and is a 404 by id**, not a 403. This is the RLS
    layer doing its job and it is worth asserting rather than assuming.
  - **The client secret never appears in any response**, including the create response and including
    the error body of a failed update. Assert on the whole serialized body, not on a field.
  - **`ldap://` is refused** unless an explicit `allowInsecure` flag is set — §6r makes this a hard
    rule for LDAP, and the same argument applies to an `http://` discovery URL here: refuse it.

- [ ] **Step 2: Run and watch fail.**

- [ ] **Step 3: Write the entities.** Every repository interface carries
  `@Transactional(readOnly = true)` — **non-negotiable**; without it derived and `@Query` methods run
  outside any transaction, so `app.current_company` is never set and every RLS-protected read returns
  **zero rows** while `CompanyContext` holds the right company. `RepositoryTransactionTest` derives
  its list from the live context and will fail the build on a new repository that forgets.

- [ ] **Step 4: Write the service and controller**, gated on `company:idp` through the same
  `PermissionGate` idiom as every other write service — copy a neighbour.

- [ ] **Step 5: Contract, regenerate both copies, gates, commit.**

---

## Task 4: `user_identity`, and the linking rules

**§6r's three hardest rules, and all three are one method:**

1. **Identity is `(provider, subject)` — never email.** Emails change, get reassigned, and are not
   unique across providers.
2. **Email is a linking hint, never the key.** Matching an existing user by email is allowed **only
   when the IdP reports the email verified**, and it is a configurable policy — *auto-linking on an
   unverified email is an account-takeover vector.*
3. **The key is company-local.** A different company's IdP asserting the same `sub` must never
   resolve to an existing user.

**Files:** `identity/UserIdentity.java`, `UserIdentityRepository.java`, `auth/oidc/JitProvisioning.java`,
and `backend/src/test/java/com/tcms/auth/oidc/JitProvisioningTest.java`.

- [ ] **Step 1: Write the failing tests** — one per rule, plus:

```java
@Test
@DisplayName("refuses to link on an unverified email even when the address matches exactly")
void doesNotAutoLinkOnAnUnverifiedEmail() { … }

@Test
@DisplayName("the same sub from two companies' providers is two identities and two people")
void issuerTrustIsCompanyLocal() { … }

@Test
@DisplayName("JIT provisioning defaults to invitation-required")
void jitIsOffByDefault() {
    // §6r: "Default to invitation-required; auto-create is correct only when the IdP is
    // authoritative for the whole company … and it is a PER-COMPANY choice, since one
    // company's IdP being authoritative says nothing about another's."
}

@Test
@DisplayName("a JIT-created user lands as a member with no project access")
void jitGrantsNothing() {
    // The same default as an invitation (§43), so SSO cannot be a side door to broader
    // capability than the invite flow grants.
}
```

- [ ] **Steps 2–5:** implement, run, gates, commit.

---

## Task 5: The OIDC client — one path, configuration for the rest

**Load `auth-oidc-concepts` before writing this task.** It is the library's reference for the
protocol and this plan deliberately does not restate PKCE, `state`, `nonce` or the discovery
document.

**Files:** `auth/oidc/OidcLoginService.java`, `auth/oidc/OidcClaims.java`,
`auth/SecurityConfiguration.java`, `backend/src/test/java/com/tcms/auth/oidc/OidcClaimsTest.java`,
`backend/src/test/java/com/tcms/auth/oidc/OidcLoginServiceTest.java`.

- [ ] **Step 1: Write `OidcClaimsTest` first — the provider traps are the testable part**

Each of these is a named, specific failure from §6r's provider table, and each is one branch:

```java
@Test
@DisplayName("Cognito: an access token with no aud is accepted only with token_use=access")
void cognitoTokenUse() {
    // "Access tokens carry NO `aud` and must be checked for token_use=access — the standard
    // Cognito trap." An aud check alone would reject every Cognito access token; dropping the
    // aud check without adding token_use accepts a token minted for a different application.
}

@Test
@DisplayName("Google: the hd claim is enforced when the provider configures a domain")
void googleHostedDomain() {
    // "Use the `hd` claim to restrict to your domain, or anyone with a Google account can
    // attempt login."
}

@Test
@DisplayName("Entra: a groups overage claim is handled rather than read as no groups")
void entraGroupOverage() {
    // "Past roughly 150-200 groups, Entra drops the `groups` claim and substitutes a Graph API
    // pointer. Unhandled, role mapping silently stops working FOR EXACTLY THE USERS WHO ARE IN
    // THE MOST GROUPS — typically the senior people."
    //
    // This plan does NOT call Graph. It detects the overage indicator and REFUSES the login
    // with a message naming the limitation, because silently granting nothing to the most
    // senior people is the failure §6r describes and "grant nothing" is what unhandled looks
    // like. Refusing is loud; document it in the provider screen.
}

@Test
@DisplayName("the issuer must match the configured one exactly, not by prefix")
void issuerIsExact() { … }

@Test
@DisplayName("a replayed nonce is refused")
void nonceIsSingleUse() { … }
```

- [ ] **Step 2: Run, watch fail, implement `OidcClaims`.**

- [ ] **Step 3: The session that comes out is ours**

§6r, and it is the decision that keeps §6b intact:

> **TCMS issues its own session token after callback and does not forward the IdP token to the SPA.**
> An IdP access token is scoped for the IdP's APIs and not ours; its lifetime is wrong for our
> session; and keeping our own session means the entire §6b permission model stays unchanged.

So `OidcLoginService`'s success path ends at `SessionTokens.issue(...)` — the same call the password
path makes — and **refresh tokens are held server-side, never in the browser.** Write both sentences
into the service's javadoc.

- [ ] **Step 4: Permit the callback path in `SecurityConfiguration`**, and add a test to
  `EndpointAccessTest` (or its equivalent) asserting the callback is the **only** newly-permitted
  path. A permit-all that is one segment too wide is the failure shape here.

- [ ] **Step 5–6:** gates, commit.

---

## Task 6: Claim → role mapping — the seam where SSO can destroy §6b

**Files:** `identity/ClaimRoleMapping.java`, `auth/oidc/ClaimRoleResolver.java`,
`backend/src/test/java/com/tcms/auth/oidc/ClaimRoleResolverTest.java`.

- [ ] **Step 1: Write the failing tests — one per §6r rule, all five**

```java
@Test
@DisplayName("maps to roles and teams, never to permissions")
void mapsToRolesNeverPermissions() {
    // "Roles remain the only thing that carries permissions." There is no code path from a
    // claim to a permission key, and this test exists to keep it that way.
}

@Test
@DisplayName("an unmapped claim grants nothing")
void unmappedClaimsFailClosed() {
    // "A new group appearing in the IdP must never confer access by accident."
}

@Test
@DisplayName("a mapping to a SYSTEM-scope role is refused at SAVE time, not filtered at login")
void cannotMintAnOperator() {
    // §6r is explicit that filtering at login is not enough. RoleGrantService already refuses
    // SYSTEM-scope with a 400 and its header says there should be no HTTP path to a SYSTEM
    // grant until an instance-admin surface exists to hold it — that sentence stays true, and
    // this is a second, independent refusal at a second door.
}

@Test
@DisplayName("IdP-derived grants are marked and are read-only")
void derivedGrantsAreMarked() {
    // role_assignment.source = 'IDP' (V55). "Otherwise an admin edits a grant that the next
    // login silently overwrites — one of the most confusing failure modes in enterprise
    // software."
}

@Test
@DisplayName("effective permissions are still the union, with no new precedence rule")
void bothKindsOfGrantUnion() {
    // "A user may hold both IdP-derived and locally-granted roles; effective permissions remain
    // the union (§6b) with no new precedence rules. The 'no explicit deny' decision holds."
}

@Test
@DisplayName("maps on the immutable identifier and stores a label for display")
void mapsOnTheStableId() {
    // "Group names are unstable in some IdPs (Entra GUIDs); map on the immutable identifier and
    // store a human label for display."
}
```

- [ ] **Steps 2–5:** implement, run, gates, commit.

**Note for the executor:** `RoleGrantService` must not learn about IdP grants. The resolver writes
`role_assignment` rows with `source = 'IDP'` through its own path, and the **grant endpoint** keeps
refusing what it refuses. Two writers, one table, and the flag is what tells them apart — the
alternative (routing IdP grants through the HTTP grant service) would mean relaxing that service's
refusals, which is how the seam destroys the model.

---

## Task 7: `company_email_domain` and the routing endpoint

§6r's rule, and every clause of it is load-bearing:

> **Login-screen routing, with no pre-authentication company picker.** Because companies are separate
> clients, an unauthenticated caller must never be shown a list of companies — that leaks your client
> roster. The login form takes an email, resolves the domain to a company via a configured
> `email_domain → company` map, and redirects to that company's provider. When the domain maps to
> **several** companies, the user authenticates first and *then* chooses among their own memberships;
> when it maps to **none**, the response is identical to the mapped case, so login cannot be used as
> an oracle for which companies exist.

**Files:** `identity/CompanyEmailDomain.java` + repository, `auth/LoginRoutingService.java`,
`auth/LoginRoutingController.java`, `docs/specs/openapi.yaml`,
`backend/src/test/java/com/tcms/auth/LoginRoutingEndpointTest.java`.

- [ ] **Step 1: Write the failing tests. The third one is the whole task.**

```java
@Test
@DisplayName("a domain mapped to one company answers that company's providers")
void oneCompanyRoutes() throws Exception { … }

@Test
@DisplayName("a domain mapped to several companies answers no company at all")
void severalCompaniesDeferToAfterAuthentication() throws Exception {
    // Choosing among them happens AFTER authentication, from the caller's own memberships —
    // GET /me/companies (Plan A). Naming them here would publish which clients share a domain.
}

@Test
@DisplayName("an unmapped domain answers byte-identically to a mapped one")
void anUnmappedDomainIsIndistinguishable() throws Exception {
    var mapped = route("someone@m6c-route-a.test");
    var unmapped = route("someone@definitely-not-a-client.test");

    assertThat(mapped.getStatus()).isEqualTo(unmapped.getStatus());
    assertThat(mapped.getContentAsString())
            .as("login must not be an oracle for which companies exist on this instance (§6r)")
            .isEqualTo(unmapped.getContentAsString());
}
```

> **Read that third test carefully before implementing.** It is in tension with the first: a mapped
> domain must *route* and an unmapped one must not, while both must answer identically. The
> resolution §6r intends is that the **response body is generic in both cases** — "check your email /
> contact your administrator" — and the *routing* happens as a redirect the browser follows, not as a
> named company in a JSON body. If your implementation cannot satisfy both tests, the design question
> is real and worth stopping on rather than weakening a test.
>
> The shape that does satisfy both: answer `200` with **either** a provider list keyed by opaque
> provider id **or** an empty list, where an empty list is also what a several-company and a
> no-company domain return — so the only distinguishable case is "exactly one company, which has
> configured providers", and a company with no providers configured is indistinguishable from no
> company at all. Verify that against the tests before writing the service.

- [ ] **Step 2: Rate-limit it.** This endpoint takes an unauthenticated email address and does a
  lookup. `RateLimitFilter` and `RateLimitProperties` already exist — add this path to whatever they
  key on, and add a test. An unrate-limited pre-auth lookup is an enumeration tool even when its
  answers are identical, because timing is not.

- [ ] **Steps 3–6:** implement, contract, gates, commit.

---

## Task 8: `/{slug}/login` — the per-company entry point

§6r: *"Per-company login URLs are the better path where clients expect their own entry point —
`/{companySlug}/login` resolves one company's providers directly, with the generic form as fallback.
It also gives each client a brandable landing page without per-company custom domains."*

**This is the one place a company is named in a URL before authentication, and it is safe for one
reason: the caller supplied the slug.** It does not enumerate anything — a wrong slug must answer
exactly what a right slug with no providers answers.

**Files:** `frontend/src/features/auth/CompanyLoginPage.tsx` + test,
`frontend/src/app/router.tsx`, `backend` — a public `GET /api/v1/auth/companies/{slug}/login`
returning the company's **display name, logo and colours** (Plan B's branding) plus its providers.

- [ ] **Step 1: Write the failing frontend test** — the page renders the company's branding, and an
  unknown slug renders the generic login form with no error naming the company.

- [ ] **Step 2: Decide what a wrong slug does, and write it down.** A 404 page saying "no such
  company" is an enumeration oracle. Rendering the generic login form is not. **Render the generic
  form.**

- [ ] **Steps 3–6:** implement, contract, gates, commit.

---

## Task 9: Choosing among your own memberships, after authentication

The several-companies case from Task 7. After a successful sign-in, if the account has more than one
ACTIVE membership, the SPA shows a chooser built from **`GET /me/companies`** (Plan A) and switches
with **`POST /auth/session/company`** (Plan A). No new endpoint.

**Files:** `frontend/src/features/auth/ChooseCompanyPage.tsx` + test, `frontend/src/app/router.tsx`.

- [ ] **Step 1: Write the failing test** — two companies → the chooser; one company → straight
  through, no chooser; zero companies (an operator) → straight to `/instance` if `isSystemAdmin`, and
  to an explanatory page otherwise.

- [ ] **Steps 2–5:** implement, gates, commit.

---

## Task 10: Write the slice down

Same shape as Plan A's Task 7. This one is large enough that its handoff section should say what was
**not** built and why — LDAP, SAML, SCIM, integration credentials, and the Entra Graph call Task 5
Step 1 refuses rather than makes.

Verification-queue rows (**write, run nothing**): a full OIDC round trip against a local Keycloak;
the unverified-email link refusal; a claim mapping that names a SYSTEM role; the byte-identical
routing responses; `/{slug}/login` with a wrong slug.

---

## Self-review

**Spec coverage against §6r and the master plan's D1 table:** tables and key shape → Task 2 ·
generic OIDC client with PKCE and server-held refresh → Tasks 1, 5 · provider traps (Cognito
`token_use`, Google `hd`, Entra overage) → Task 5 Step 1 · claim→role mapping with all five rules →
Task 6 · JIT with invitation-required default → Task 4 · email-domain routing with no pre-auth
picker → Task 7 · `/{slug}/login` → Task 8 · post-auth disambiguation → Task 9 · `role_assignment.source`
→ Task 2. **Not built, by decision:** LDAP/AD, SAML, SCIM, integration credentials — named in Scope.

**Placeholder scan.** Tasks 3–9 name every file, every gate and the specific assertion each test must
make, and defer the *local idiom* (the `PermissionGate` shape, the `RateLimitFilter` key, the
`SchemaConventionTest` helpers) to a named neighbour rather than restating it wrongly. Task 1 Step 1
deliberately ships a test body to be filled from a printed run — that is the only honest way to pin a
chain whose Spring-owned members change between versions, and the step says so.

**Type consistency.** `IdentityProvider` / `UserIdentity` / `ClaimRoleMapping` / `CompanyEmailDomain`
are declared in Task 2 and used under those names in Tasks 3, 4, 6 and 7. `OidcClaims` is declared in
Task 5 and consumed by `OidcLoginService` in the same task. `role_assignment.source` is created in
Task 2 and read in Task 6. `GET /me/companies` and `POST /auth/session/company` are **Plan A's** and
are reused rather than redeclared (Task 9).

**The one thing most likely to be wrong in this plan.** Task 7's three tests may not be
simultaneously satisfiable by the shape I sketched. §6r states the requirement and does not state the
mechanism, and I have written the tension into the task rather than hiding it. If it turns out a
mapped and an unmapped domain genuinely cannot answer identically while routing still works, that is
a **spec question for the user**, not a test to weaken — and the plan says to stop there.
