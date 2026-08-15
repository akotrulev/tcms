# M4 External Identity — Plan 3: LDAP / Active Directory Implementation Plan

**Date:** 2026-08-15 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task, or `ep-delegate-to-subagents` to run its tasks through subagents. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** A company whose people live in Active Directory or another LDAP directory can register
that directory as an identity provider and sign in through it — `IdentityProviderKind.LDAP` stops
being a schema member with no implementation, and the admin dialog's `Kind` select stops being a
one-option control that is disabled because there is nothing else to choose.

**Architecture:** One kind added to a surface that was built for two. The row, the CRUD, the view,
the discovery call, the account-resolution policy and the session mint are all already there and are
reused unchanged; what is new is a **credential** flow beside the **redirect** flow — a bind against
the directory, in `com.tcms.auth`, behind one new public endpoint that answers exactly what
`POST /auth/login` answers. `identity` gains columns and validation and **not one line of network
code**: the module may not reach the network (§6r trap 5, ArchUnit), which is the same seam that put
`OidcClient` in `auth` and left `FederatedIdentityService` as pure policy.

**Tech Stack:** Java 21 · Spring Boot 3.5.6 · `spring-boot-starter-ldap` (Spring LDAP `LdapTemplate`
over JNDI — new dependency, the first this M4 lane adds) · UnboundID `InMemoryDirectoryServer` for
tests (ships with the starter, so it is a test-scope declaration and not a new artifact to source) ·
Flyway `V57` · Testcontainers Postgres 17 · Vite 8 · React 19 · TypeScript strict · MUI v7 ·
TanStack Query · Vitest + RTL.

---

## Scope check — one lane, and the two things it deliberately is not

This plan ships one thing that works on its own: **sign-in through a directory**. Registration,
discovery, resolution, provisioning and the session are one path end to end, and every task below is
on it.

Two neighbours are named here so nobody reads their absence as an oversight:

| Not in this plan | Why |
|---|---|
| **Group search, nested-group recursion, group→role mapping** | The consumer does not exist. Plan 2 (`claim → role mapping`) owns the mapping table, the `assignable` question at save time and the `permission_version` bump. Adding four LDAP group-search columns here would be four columns nothing reads, chosen before the model that has to read them — and unlike `group_claim`, whose single column plan 2 can consume as-is, the LDAP shape is a base, a filter, a membership attribute and a recursion bound whose form is plan 2's decision. |
| **Deprovisioning poll** | A scheduled reconciliation that deactivates accounts is its own slice with its own failure mode (an unreachable directory must never deactivate a company's staff), and it needs the `CompanyScopedEvent` / `CompanyContextTaskDecorator` discipline rather than a login path. It is not needed to sign anybody in. |

Both stay in the M4 sketch at `docs/plans/2026-08-15-m4-external-identity-oidc.md` § "Plan 3
(sketch)", and Task 12 rewrites that sketch to point here and to record these two as deferred rather
than dropped.

---

## Lane ownership (concurrency)

- **This plan HOLDS THE CONTRACT.** `docs/specs/openapi.yaml` changes in Task 2, and **both**
  `schema.d.ts` copies are regenerated in the same task and the same commit. `schemaFreshness.test.ts`
  and `contractPairing.test.ts` fail if they diverge. Say so before starting: no other lane may edit
  `openapi.yaml` while this one is in flight, and the output is never hand-merged.
- **Flyway version `V57` is pre-assigned here.** The highest applied version on `master` is `V56`
  (`V56__role_scope_agrees_with_the_grant_shape.sql`, §225 — this plan was renumbered
  from `V56` to `V57` at integration, 2026-08-15, per the landing-order rule). `out-of-order` is unset, so a `V57` landing after
  somebody else's `V57`/`V58` is refused **on an already-migrated database and nowhere else** —
  Testcontainers migrates a fresh container every run and structurally cannot see it. There are three
  worktrees under `.claude/worktrees/`; check their `db/migration/` directories before writing the
  file, and renumber at integration in landing order if one of them has taken 57.
- **Fixture UUID prefix `f3`** (`f3000000-0000-0000-0000-0000000000NN`) for this plan's fixtures.
  Hand-managed with no check behind it; it has collided five times (§19 … §62). Plan 1 took `f2`.
  State the prefix in the fixture file's own header.
- **One writer for `docs/agent-context/*.md`.** This plan writes `be-java.md` (Tasks 3–9) and
  `fe-react.md` (Tasks 10–11). Re-read each before editing and update it in that task's own commit.
- **Handoff sections are allocated in landing order.** §222 is the highest at the time of writing, so
  expect §223 upward. `§NNN` below is a placeholder.

---

## Decisions taken before any code — do not re-litigate these

### D1. LDAP is a credential flow, and that is the whole shape of the work

OIDC redirects a browser to a third party that collects the password. **LDAP does not**: TCMS
collects the person's *directory* password and binds with it. Every consequence in this plan follows
from that one sentence — no `state`, no `nonce`, no PKCE, no `sso_login_attempt` row, no
`/sso/callback`, no fragment. The endpoint is a JSON `POST` that answers what `POST /auth/login`
answers, and the SPA drives it with a form rather than `window.location.assign`.

It also means TCMS now handles a credential that is **not its own and that it cannot rotate**. That
raises the bar on the transport (D2), on the refusal shape (D6) and on what gets logged (D7), and
it is why this plan is longer than "add an enum arm".

### D2. `ldaps://`, or `ldap://` with StartTLS. There is no `allowInsecure` flag

The sketch in the OIDC plan proposed `allowInsecure` "for lab use", and §219 recorded that the
plain-HTTP *issuer* decision does not settle it because a bind is a different exposure. Argued on its
own ground, **the answer is the stricter one**, and for a reason the https gate does not even have:

- The OIDC gate protects the URL the **signing keys** are fetched from. Getting it wrong lets
  somebody on the path nominate who may sign in.
- A plain LDAP bind puts **the user's own directory password** on the wire in cleartext — the
  credential to the customer's Active Directory, which is very often the credential to everything
  else they own. There is no third party absorbing that, and TCMS is the party that chose to send it.

So `IdentityProviderService.validate` refuses any LDAP URL that is not `ldaps://` unless
`startTls = true`, and there is no property, profile or column that turns the check off. The lab is
not an exception, because the lab does not need to be one: UnboundID's `InMemoryDirectoryServer`
serves LDAPS and StartTLS with a generated certificate, so **the tests drive the encrypted path that
production drives** rather than a plaintext path production never takes.

**Certificate trust is a deployment concern, not a column.** The JVM default trust store is what
validates the directory's certificate; an internal CA is added to a mounted truststore
(`deploy/`-level), documented in Task 12. A per-provider PEM column was considered and rejected: it
is a second trust store, editable through a web form, by an administrator, for a host they name — and
its only user is the case where the correct fix is to trust the CA once.

### D3. The identity key is `objectGUID` / `entryUUID`, never the DN

`user_identity.subject` holds the directory's immutable identifier, and
`identity_provider.user_id_attribute` names which attribute that is (`objectGUID` on AD,
`entryUUID` on OpenLDAP). A DN encodes tree position, so moving somebody between OUs would orphan
their account, their memberships and their authorship — silently, on the first sign-in after an
IT reorganisation.

`(provider, subject)` is already `uq_user_identity_subject`, so **there is no schema change on
`user_identity`**, which is what makes the identity half of this plan cheap.

### D4. The identifier a person types is their **email address**, always

The sign-in screen already asks for an email and discovery is already keyed on its domain. The LDAP
provider's `user_search_filter` maps that address to a DN — `(|(userPrincipalName={0})(mail={0}))`
is the AD default this plan ships as a placeholder in the form's helper text.

**The stated limit:** a directory that holds no mail-like attribute for its people cannot be used
through this product, because there would be nothing for a person to type that discovery can also
route on. Recorded rather than worked around; a second "username" field would make the sign-in screen
ask two different questions depending on a provider the person has not chosen yet.

### D5. `emailVerified` is `true` for an LDAP assertion, and `autoLinkVerifiedEmail` still gates linking

`FederatedIdentityService.Claims` carries `emailVerified` because an OIDC issuer may or may not have
checked. A directory is authoritative for the addresses it publishes in its own tree, so the LDAP
path passes `true`. The gate that decides whether an existing TCMS account may be taken over by an
external assertion is `autoLinkVerifiedEmail`, which is off by default and is an explicit act by the
company's own administrator. **That is the gate; `emailVerified` was never it.**

### D6. One refusal, no reason — and the bind failure is not the interesting one

Unknown provider, disabled provider, unreachable directory, no such entry, bad password, no TCMS
account, JIT off, deactivated account, membership removed: **401 with no body**, exactly as
`AuthController.login` answers. `AuthenticationService`'s rule, on a surface open to the internet.

The refusal must also cost roughly the same time in every arm. Local login verifies a **dummy bcrypt
hash** on a miss so "no such account" and "wrong password" cannot be told apart with a stopwatch. The
LDAP equivalent is a **synthetic bind**: when the search finds no entry, bind anyway as
`uid=<random>,<user_search_base>` and discard the result, so a miss and a wrong password both cost one
search plus one bind against the same server. Written down because the natural implementation —
`return empty` after the search — reinstates exactly the oracle the shared 401 removes, and it is
invisible in every test that does not measure.

### D7. Nothing about the credential reaches a log, and referrals are not followed

- The bind password is a request field and appears in **no** log line, audit document or exception
  message. `IdentityProviderService.changes` already omits the client secret for this reason; the
  bind password gets the same treatment and the same reason (a client's own administrators read the
  audit screen).
- **Referral chasing is off.** An AD server may answer a search with a referral to another host; a
  client that follows it will bind — with the configured service credential, and then with the
  person's password — against a server chosen by the response rather than by configuration.
  `LdapTemplate` is configured `setIgnorePartialResultException(true)` and the context source
  `setReferral("ignore")`.

### D8. Filter interpolation is escaped, and the escaping is not optional

`user_search_filter` is administrator-supplied and the typed address goes into it. Interpolating it
with string concatenation gives an attacker `*)(objectClass=*` — LDAP filter injection, whose best
case is a search that matches the wrong person and whose worst case is a match TCMS then binds
against. Spring LDAP's `LdapQueryBuilder`/`filter(String, Object...)` substitutes `{0}` with RFC 4515
escaping applied; **the placeholder form is the only form this plan writes.** A filter without `{0}`
is refused at save time with a field error.

### D9. Bind attempts are throttled per (provider, identifier), unlike local login

`RateLimitFilter` counts **API tokens only** and explicitly cannot count an unauthenticated request —
that is stated in its own header and it is right for the shape it bounds. Local login has no lockout
either, and that is a decision this product has already taken.

**LDAP is different in a way that changes the answer:** every failed bind increments the *customer's
directory* bad-password counter. With AD's default lockout policy, an attacker who does not care
about getting in can lock out a company's entire staff through this endpoint, from the internet, in
minutes — and the harm lands on a system TCMS does not own. So this plan ships a small bounded
in-process throttle keyed on `(providerId, lowercased identifier)`: 5 attempts, then refuse **without
binding** for 60 seconds. The refusal is the same empty 401 as every other, so it discloses nothing;
what it protects is the directory, not TCMS.

---

## The seven traps

Read these before writing any code. Five are silent when wrong.

### 1. `objectGUID` is binary, little-endian in its first three fields

AD returns `objectGUID` as a 16-byte octet string, and its first three fields are **little-endian** —
so the canonical GUID a Windows administrator sees is a byte-swapped rendering of what the wire
carries. Two failure modes, both permanent once rows exist:

- `new String(bytes)` produces mojibake that varies with the platform charset. The subject is then a
  key that is not stable across a JVM upgrade, and every affected person silently forks a second
  account on their next sign-in.
- Rendering the bytes in wire order gives a GUID that does not match anything the customer's
  administrator can look up, which makes every support conversation about the wrong string.

`LdapDirectory` therefore reads the attribute as `byte[]` when it is 16 bytes long and formats it
with the AD field swap (Task 6, `guidOf`). `entryUUID` arrives as a plain string and is used as-is.

### 2. `CREATE OR REPLACE FUNCTION` cannot change a return type

`tcms_resolve_providers_for_domain` must start returning `kind`, and Postgres refuses to replace a
function whose `RETURNS TABLE` shape changed (`42P13 — cannot change return type of existing
function`). V57 must `DROP FUNCTION` first and then `CREATE`, and the **`REVOKE ALL … FROM PUBLIC`
and the `GRANT … TO tcms_app` must be repeated** — a dropped function takes its grants with it, and
the failure mode of forgetting is a `permission denied for function` on the login screen of a
migrated deployment while every fresh-container test stays green.

### 3. The pre-authentication lookup yields a company and nothing else

`tcms_resolve_identity_provider` (V53) exists because `identity_provider` is FORCE RLS-protected and
sign-in happens with no company context. The LDAP path asks it for **`company_id` and `enabled`
only**, opens the context, and then reads the full row — bind DN, search base, filter — through
`IdentityProviderRepository` inside that context. Do not widen the function's return list to carry
the LDAP configuration: that would move an RLS-protected read into the one place RLS is being walked
around, for no gain.

### 4. `identity` may not reach the network

`ArchitectureRulesTest` enforces it and §6r trap 5 records why. `LdapDirectory`, `LdapProperties` and
`LdapLoginService` live in `com.tcms.auth`, exactly as `OidcClient` and `OidcDiscovery` do.
`FederatedIdentityService` stays pure policy over the database and is called with the same
`Claims` record it already takes — **this plan does not modify it at all**, which is the test that
the seam was drawn in the right place.

### 5. Relaxing a `NOT NULL` relaxes it for the rows that already have it

`client_id` must become nullable so an LDAP row can exist, and the moment it does, **the OIDC rows
lose the guarantee they were shipped with** — an OIDC provider with a null client id would fail at
the token exchange, in the lab, days later. The replacement is a `CHECK` that is conditional on
`kind` (Task 3), which is stronger than the original because it also pins the LDAP columns' presence.
`SchemaConventionTest` checks `company_id` and RLS; it knows nothing about this, so Task 3 writes the
assertion itself.

### 6. An LDAP server that accepts a connection and never answers holds a request thread

`OidcProperties.readTimeout`'s lesson, one protocol over: the host was chosen by somebody outside
this deployment. `com.sun.jndi.ldap.connect.timeout` and `com.sun.jndi.ldap.read.timeout` are the two
JNDI properties that bound it, they are **strings of milliseconds**, and a JNDI context with neither
set blocks for ever. Both are set from `LdapProperties` in Task 5.

### 7. A search that returns more than one entry is a refusal, not a "take the first"

A filter like `(mail={0})` can match two entries in a directory that has a stale duplicate. Binding
as "the first one" picks a person by result ordering. `LdapDirectory.findEntry` returns empty when
the result set size is not exactly 1, and the refusal is the ordinary one.

---

## File structure

**Backend — created**

| File | Responsibility |
|---|---|
| `backend/src/main/resources/db/migration/V57__ldap_identity_provider.sql` | The LDAP columns, the per-kind `CHECK`, and the discovery function regaining `kind` |
| `backend/src/main/java/com/tcms/auth/LdapProperties.java` | Timeouts and the throttle's numbers |
| `backend/src/main/java/com/tcms/auth/LdapDirectory.java` | The only class that talks LDAP: search, bind, attribute reading |
| `backend/src/main/java/com/tcms/auth/LdapLoginService.java` | The flow — resolve, open context, search, bind, resolve identity, mint session |
| `backend/src/main/java/com/tcms/auth/BindThrottle.java` | D9's bounded per-(provider, identifier) counter |
| `backend/src/main/java/com/tcms/auth/LdapController.java` | `POST /auth/ldap/{providerId}/login` |

**Backend — modified**

| File | Change |
|---|---|
| `backend/build.gradle.kts`, `backend/gradle/libs.versions.toml` | `spring-boot-starter-ldap`, `unboundid-ldapsdk` (test) |
| `identity/IdentityProvider.java` | Eight LDAP columns, a second factory, a widened `applyEdit` |
| `identity/IdentityProviderInput.java` · `IdentityProviderView.java` | The same fields on the contract's two sides |
| `identity/IdentityProviderService.java` | Kind-aware validation; the bind password through `ProviderSecrets` |
| `auth/SsoLoginService.java` | `ProviderChoice` gains `kind`; provider resolution extracted so both flows share it |
| `auth/SsoController.java` | Nothing — listed so nobody edits it by reflex |

**Backend — tests**

`auth/LdapDirectoryTest.java` · `auth/LdapLoginEndpointTest.java` · `auth/BindThrottleTest.java` ·
`identity/IdentityProviderEndpointTest.java` (extended) · `schema/LdapSchemaTest.java` ·
`test/resources/ldap/f3-directory.ldif`

**Frontend — modified**

`features/admin/ProviderFormDialog.tsx` (kind select live at registration; fields switch by kind) ·
`features/auth/LoginPage.tsx` (directory sign-in mode) · `api/queries/auth.ts` (the new mutation) ·
`api/types.ts` · both `schema.d.ts` copies · `test/fixtures.ts`.

---

## Task 1: Record the transport decision before anything depends on it

**Files:**
- Modify: `docs/plans/2026-08-15-m4-external-identity-oidc.md` (the "Plan 3 (sketch)" section)

- [x] **Step 1: Replace the sketch's first bullet**

Find the bullet reading ``**`ldap://` is refused unless an explicit `allowInsecure` flag is set**``
and replace that bullet with:

```markdown
- ~~**`ldap://` is refused unless an explicit `allowInsecure` flag is set** for lab use.~~ →
  **superseded 2026-08-15 by `docs/plans/2026-08-15-m4-external-identity-ldap-ad.md` D2: there is no
  such flag.** A URL must be `ldaps://`, or `ldap://` with StartTLS. The exposure a plain bind
  creates is the *user's own directory password* — the credential to the customer's AD, and very
  often to everything else they own — which is a strictly larger blast radius than §219's http issuer
  question, where what was at risk was the signing-key URL. The lab needs no exception because
  UnboundID's in-memory server serves LDAPS, so the tests drive the encrypted path rather than one
  production never takes.
```

- [x] **Step 2: Commit**

```bash
git add docs/plans/2026-08-15-m4-external-identity-oidc.md
git commit -m "§NNN — the LDAP transport decision, taken before the code that would depend on it"
```

---

## Task 2: The contract

**Files:**
- Modify: `docs/specs/openapi.yaml`
- Modify: `frontend/src/api/schema.d.ts` (generated)
- Modify: `packages/client/src/schema.ts` (generated)

- [x] **Step 1: `IdentityProviderKind`'s description stops saying LDAP is unimplemented**

Replace the `IdentityProviderKind` block's description with:

```yaml
    IdentityProviderKind:
      type: string
      enum: [OIDC, LDAP]
      description: |
        Which protocol this provider speaks, and therefore **which fields on this schema are
        populated and how a person signs in through it**. `OIDC` redirects the browser to a third
        party that collects the password; `LDAP` collects the directory password here and binds with
        it. **There is no SAML member** — resolved question #40 ruled it out of scope, and a value
        nothing can create is a state the code must branch on for no reason.
```

- [x] **Step 2: `IdentityProvider` gains the LDAP half**

In `IdentityProvider`, change the `required` list and the `issuerUri`/`clientId` descriptions, and add
seven properties:

```yaml
    IdentityProvider:
      type: object
      required:
        [id, key, kind, displayName, issuerUri, clientId, clientSecretSet, bindDn, bindPasswordSet,
         startTls, userSearchBase, userSearchFilter, userIdAttribute, emailAttribute,
         displayNameAttribute, jitProvisioning, autoLinkVerifiedEmail, groupClaim, enabled,
         createdAt, updatedAt]
```

```yaml
        issuerUri:
          type: string
          description: |
            Where the authority lives, and its meaning follows `kind`. For `OIDC` it is the issuer,
            always `https` — the signing keys every assertion is checked against come from it. For
            `LDAP` it is the directory URL, and it is `ldaps://…` or `ldap://…` with `startTls`:
            a plain bind puts the person's own directory password on the wire, and there is no flag
            that permits it.
        clientId:
          type: string
          nullable: true
          description: OIDC only. Null on an `LDAP` provider, which has no relying-party identity.
        bindDn:
          type: string
          nullable: true
          description: |
            LDAP only. The service account this instance searches as, before the person's own bind.
            Null means an anonymous search, which some directories permit and Active Directory
            does not.
        bindPasswordSet:
          type: boolean
          description: |
            Whether a bind password is stored — `clientSecretSet`'s counterpart, and a boolean for
            the same reason: no read path echoes the value back.
        startTls:
          type: boolean
          description: |
            LDAP only. Whether to upgrade an `ldap://` connection with StartTLS. An `ldaps://` URL
            is already encrypted and does not use it. False on an `OIDC` provider.
        userSearchBase:
          type: string
          nullable: true
          description: LDAP only. The subtree searched for the person signing in — `ou=people,dc=acme,dc=test`.
        userSearchFilter:
          type: string
          nullable: true
          description: |
            LDAP only. **Must contain `{0}`**, which is where the typed address is substituted with
            RFC 4515 escaping applied. A filter that interpolated it any other way would be an
            injection point on an unauthenticated surface.
        userIdAttribute:
          type: string
          nullable: true
          description: |
            LDAP only. Which attribute holds the directory's immutable identifier — `objectGUID` on
            Active Directory, `entryUUID` on OpenLDAP. **Never a DN**: a DN encodes tree position, so
            moving somebody between OUs would orphan their account and their authorship.
        emailAttribute:
          type: string
          nullable: true
          description: LDAP only. Which attribute carries the address — `mail` on both common directories.
        displayNameAttribute:
          type: string
          nullable: true
          description: LDAP only. Which attribute carries the person's name, for the membership this creates.
```

- [x] **Step 3: `IdentityProviderInput` gains the same fields plus the write-only credential**

```yaml
    IdentityProviderInput:
      type: object
      required:
        [key, kind, displayName, issuerUri, clientId, clientSecret, bindDn, bindPassword, startTls,
         userSearchBase, userSearchFilter, userIdAttribute, emailAttribute, displayNameAttribute,
         jitProvisioning, autoLinkVerifiedEmail, groupClaim, enabled]
```

with, added beside `clientSecret`:

```yaml
        bindDn: { type: string, nullable: true, maxLength: 500 }
        bindPassword:
          type: string
          nullable: true
          description: |
            The plaintext service-account password, sent when it is set or rotated. **Null or empty
            keeps the stored one** rather than clearing it — `clientSecret`'s rule exactly, for
            `clientSecret`'s reason: no read path can echo it back, so a caller has nothing to
            resend. Encrypted at rest, never hashed.
        startTls: { type: boolean, nullable: true }
        userSearchBase: { type: string, nullable: true, maxLength: 500 }
        userSearchFilter: { type: string, nullable: true, maxLength: 500 }
        userIdAttribute: { type: string, nullable: true, maxLength: 100 }
        emailAttribute: { type: string, nullable: true, maxLength: 100 }
        displayNameAttribute: { type: string, nullable: true, maxLength: 100 }
```

- [x] **Step 4: `SsoProviderChoice` gains `kind`**

```yaml
    SsoProviderChoice:
      type: object
      required: [id, displayName, kind]
```

```yaml
        kind:
          $ref: '#/components/schemas/IdentityProviderKind'
          description: |
            Which control the sign-in screen renders: `OIDC` is a button that navigates to the
            issuer, `LDAP` is a password field on this screen. **It is not a company identifier** —
            it says which protocol a route speaks, and nothing about who operates it or how many
            companies claim the domain, so it is disclosed on the same terms as `displayName`.
```

- [x] **Step 5: The one new operation**

Add after `/auth/sso/callback`:

```yaml
  /auth/ldap/{providerId}/login:
    post:
      tags: [auth]
      summary: Sign in against a directory
      description: |
        Binds to the provider's directory as the person signing in, and — exactly as
        `POST /auth/login` does — answers with a **TCMS** session. The directory credential is used
        for one bind and is never stored, never logged, and never returned.

        This is a `POST` with a body rather than a redirect because `LDAP` is a **credential** flow:
        there is no third party to send a browser to, which is the whole difference between this
        operation and `/auth/sso/{providerId}/authorize`.

        **`401` with no body for every failure** — unknown provider, disabled provider, unreachable
        directory, no such entry, wrong password, an entry matched more than once, no TCMS account,
        JIT off, deactivated account, membership since removed. `POST /auth/login`'s rule, on a
        surface a stranger can reach.

        Repeated failures are refused **without contacting the directory** after a small number of
        attempts for one identifier at one provider. That bound exists to protect the *customer's*
        directory: every failed bind increments its lockout counter, so an unthrottled endpoint here
        is a way to lock out a company's staff from the internet.
      security: []
      parameters:
        - name: providerId
          in: path
          required: true
          schema: { type: string, format: uuid }
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [email, password]
              properties:
                email: { type: string, format: email, maxLength: 320 }
                password: { type: string, maxLength: 400 }
                rememberMe: { type: boolean, default: false }
      responses:
        '200':
          description: Signed in.
          content:
            application/json:
              schema: { $ref: '#/components/schemas/AuthResult' }
        '400': { $ref: '#/components/responses/BadRequest' }
        '401': { description: Refused. No body, and no cause — see the description. }
```

- [x] **Step 6: Regenerate both copies and run the packages gate**

```bash
cd frontend && npm run gen:api
cd ../packages/client && npm run gen:api
cd .. && npm run build && npm run test:run --workspaces
```

Expected: green. If `responseCredentials.test.ts` fails, the failure is real — re-read D7: no
`bindPassword` may appear on a **response** schema, only `bindPasswordSet`.

- [x] **Step 7: Commit**

```bash
git add docs/specs/openapi.yaml frontend/src/api/schema.d.ts packages/client/src/schema.ts
git commit -m "§NNN phase 1 — the contract for a directory that is not an issuer"
```

---

## Task 3: V57 — the columns, the per-kind shape check, and the function that must be dropped

**Files:**
- Create: `backend/src/main/resources/db/migration/V57__ldap_identity_provider.sql`
- Create: `backend/src/test/java/com/tcms/schema/LdapSchemaTest.java`

- [x] **Step 1: Write the failing schema test**

```java
package com.tcms.schema;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tcms.IntegrationTestBase;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V57 relaxed a NOT NULL, and this is what replaced it.
 *
 * <p>{@code client_id} had to become nullable so an LDAP row could exist at all — and the moment it
 * did, every OIDC row lost the guarantee it shipped with. A provider with a null client id fails at
 * the token exchange, in a deployment, days later. {@code ck_identity_provider_shape} is the
 * conditional replacement, and it is stronger than what it replaced because it also pins the LDAP
 * columns' presence. {@code SchemaConventionTest} checks {@code company_id} and RLS and knows
 * nothing about either.
 */
class LdapSchemaTest extends IntegrationTestBase {

    @Test
    @DisplayName("an OIDC row still cannot exist without a client id")
    void oidcStillRequiresAClientId() throws SQLException {
        try (Statement statement = superuserConnection().createStatement()) {
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO identity_provider (id, company_id, key, kind, display_name,
                                                   issuer_uri, client_id, created_at, updated_at)
                    VALUES ('f3000000-0000-0000-0000-000000000001',
                            (SELECT id FROM company LIMIT 1),
                            'oidc-without-a-client', 'OIDC', 'Broken',
                            'https://issuer.acme.test', NULL, now(), now())
                    """))
                    .hasMessageContaining("ck_identity_provider_shape");
        }
    }

    @Test
    @DisplayName("an LDAP row cannot exist without a search base, a filter and an id attribute")
    void ldapRequiresItsOwnThreeColumns() throws SQLException {
        try (Statement statement = superuserConnection().createStatement()) {
            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO identity_provider (id, company_id, key, kind, display_name,
                                                   issuer_uri, created_at, updated_at)
                    VALUES ('f3000000-0000-0000-0000-000000000002',
                            (SELECT id FROM company LIMIT 1),
                            'ldap-without-a-base', 'LDAP', 'Broken',
                            'ldaps://dc.acme.test:636', now(), now())
                    """))
                    .hasMessageContaining("ck_identity_provider_shape");
        }
    }

    @Test
    @DisplayName("discovery answers with the kind, and the grant survived the drop")
    void discoveryCarriesTheKind() throws SQLException {
        try (Statement statement = appConnection().createStatement()) {
            statement.execute("SELECT * FROM tcms_resolve_providers_for_domain('nobody.test')");
            assertThat(statement.getResultSet().getMetaData().getColumnName(3)).isEqualTo("kind");
        }
    }
}
```

> `superuserConnection()` and `appConnection()` are `IntegrationTestBase`'s existing helpers — read
> that class before writing this file and use whatever it actually names them; the three tests need
> ground truth for the two `INSERT`s and the application role for the function call, because the
> grant is exactly what the third test is checking.

- [x] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.schema.LdapSchemaTest'
```

Expected: FAIL — the inserts succeed (no such constraint) and the function has two columns.

- [x] **Step 3: Write the migration**

```sql
-- =============================================================================
-- V57 — LDAP / Active Directory as a second kind of identity provider.
--
-- Source: docs/plans/2026-08-15-m4-external-identity-ldap-ad.md. V53 shipped
--         `kind IN ('OIDC','LDAP')` and an implementation of the first of them.
--
-- THE ONE THING TO UNDERSTAND BEFORE EDITING THIS FILE
--
--   client_id had to lose NOT NULL, because an LDAP provider has no relying
--   party identity to put in it. A dropped NOT NULL is dropped for EVERY row,
--   including the OIDC ones that were shipped with it -- and an OIDC provider
--   with a null client id does not fail here. It fails at the token exchange,
--   in a deployment, days later, as a sign-in that refuses everybody.
--
--   ck_identity_provider_shape is what replaces it: the same guarantee,
--   conditional on kind, plus the three LDAP columns that must be present for
--   the search to be constructible at all. It is strictly stronger than what it
--   replaced. LdapSchemaTest drives both arms.
--
-- Rejected: a separate ldap_identity_provider table. The company scoping, the
--           RLS policy, the CRUD, the audit events, the discovery function and
--           user_identity's (provider, subject) key are identical for both
--           kinds -- a second table would duplicate all of it to avoid eight
--           nullable columns, and would make `which providers does this domain
--           reach` a union.
-- =============================================================================

ALTER TABLE identity_provider
    ALTER COLUMN client_id DROP NOT NULL,
    ADD COLUMN bind_dn                 TEXT,
    ADD COLUMN bind_password_encrypted BYTEA,
    ADD COLUMN start_tls               BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN user_search_base        TEXT,
    ADD COLUMN user_search_filter      TEXT,
    ADD COLUMN user_id_attribute       TEXT,
    ADD COLUMN email_attribute         TEXT,
    ADD COLUMN display_name_attribute  TEXT;

ALTER TABLE identity_provider
    ADD CONSTRAINT ck_identity_provider_shape CHECK (
        (kind = 'OIDC'
            AND client_id IS NOT NULL
            AND user_search_base IS NULL
            AND user_search_filter IS NULL
            AND user_id_attribute IS NULL)
        OR
        (kind = 'LDAP'
            AND client_id IS NULL
            AND user_search_base IS NOT NULL
            AND user_search_filter IS NOT NULL
            AND user_id_attribute IS NOT NULL)
    );

COMMENT ON COLUMN identity_provider.issuer_uri IS
    'Where the authority lives, and what it means follows `kind`. OIDC: the issuer, always https, '
    'because the discovery document and therefore the jwks_uri is read from it. LDAP: the directory '
    'URL, ldaps:// or ldap:// with start_tls -- a plain bind puts the PERSON''S OWN directory '
    'password on the wire, which is a larger exposure than the https gate covers, and there is '
    'deliberately no flag that permits it.';
COMMENT ON COLUMN identity_provider.bind_password_encrypted IS
    'ENCRYPTED, not hashed, for client_secret_encrypted''s reason: a bind PRESENTS it. Never in a '
    'response, log, audit event or error message. Null means an anonymous search, which some '
    'directories permit and Active Directory does not.';
COMMENT ON COLUMN identity_provider.user_search_filter IS
    'Must contain {0}, which is where the typed address is substituted WITH RFC 4515 ESCAPING '
    'APPLIED. Concatenating it instead is LDAP filter injection on an unauthenticated surface, '
    'whose best case is matching the wrong person. IdentityProviderService refuses a filter '
    'without the placeholder.';
COMMENT ON COLUMN identity_provider.user_id_attribute IS
    'The directory''s immutable identifier -- objectGUID on AD, entryUUID on OpenLDAP -- and it is '
    'what lands in user_identity.subject. NEVER a DN: a DN encodes tree position, so moving '
    'somebody between OUs would orphan their account, their memberships and their authorship.';

-- -----------------------------------------------------------------------------
-- Discovery must now tell the sign-in screen WHICH CONTROL to render: a button
-- that navigates to an issuer, or a password field on the screen itself.
--
-- DROP-then-CREATE, not CREATE OR REPLACE: Postgres refuses to replace a
-- function whose RETURNS TABLE shape changed (42P13). The drop takes the grants
-- with it, so the REVOKE and the GRANT below are not decoration -- without them
-- a MIGRATED deployment answers `permission denied for function` on its login
-- screen while every fresh-container test stays green.
--
-- Everything else is V55's function unchanged, including the reason it is
-- SECURITY INVOKER (V8: FORCE ROW LEVEL SECURITY subjects the owner to its own
-- policies, so a definer function would return zero rows).
-- -----------------------------------------------------------------------------
DROP FUNCTION tcms_resolve_providers_for_domain(text);

CREATE FUNCTION tcms_resolve_providers_for_domain(p_domain TEXT)
    RETURNS TABLE (provider_id UUID, display_name TEXT, kind TEXT)
    LANGUAGE plpgsql
    VOLATILE
    SECURITY INVOKER
    SET search_path = pg_catalog, public
AS $$
DECLARE
    previous text := nullif(current_setting('app.current_company', true), '');
    candidate record;
BEGIN
    FOR candidate IN SELECT id FROM company WHERE status = 'ACTIVE' ORDER BY id LOOP
        PERFORM set_config('app.current_company', candidate.id::text, true);

        RETURN QUERY
            SELECT p.id, p.display_name, p.kind
              FROM identity_provider p
              JOIN company_email_domain d ON d.company_id = p.company_id
             WHERE p.enabled
               AND d.domain = p_domain::citext
             ORDER BY p.display_name;
    END LOOP;

    PERFORM set_config('app.current_company', coalesce(previous, ''), true);
END;
$$;

COMMENT ON FUNCTION tcms_resolve_providers_for_domain(text) IS
    'Which live sign-in routes an email domain reaches, for the pre-authentication discovery call '
    'where no session and no company context exist yet. Runs as the CALLER with RLS fully in force '
    '(V8''s shape). Returns the UNION across every company claiming the domain, carrying no company '
    'id and no company name, so the answer cannot be read as which clients exist on this instance '
    '(§6r). `kind` says which CONTROL to render and nothing about who operates the route.';

REVOKE ALL ON FUNCTION tcms_resolve_providers_for_domain(text) FROM PUBLIC;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tcms_app') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION tcms_resolve_providers_for_domain(text) TO tcms_app';
    END IF;
END $$;
```

- [x] **Step 4: Run the schema tests plus the two schema-wide rules**

```bash
cd backend && ./gradlew test --tests 'com.tcms.schema.LdapSchemaTest' \
    --tests 'com.tcms.tenancy.SchemaConventionTest'
```

Expected: PASS. `SchemaConventionTest` must stay green untouched — V57 adds no table, so neither the
`company_id` rule nor the RLS exception list changes.

- [x] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/V57__ldap_identity_provider.sql \
        backend/src/test/java/com/tcms/schema/LdapSchemaTest.java
git commit -m "§NNN phase 2 — V57, and the NOT NULL that had to be replaced rather than dropped"
```

---

## Task 4: The entity, the input, the view

**Files:**
- Modify: `backend/src/main/java/com/tcms/identity/IdentityProvider.java`
- Modify: `backend/src/main/java/com/tcms/identity/IdentityProviderInput.java`
- Modify: `backend/src/main/java/com/tcms/identity/IdentityProviderView.java`

- [x] **Step 1: Add the eight columns to the entity**

Beside the existing fields:

```java
    @Column(name = "bind_dn")
    private String bindDn;

    /** {@link #clientSecretEncrypted}'s rule and reason: a bind PRESENTS it, so it is encrypted. */
    @Column(name = "bind_password_encrypted")
    private byte[] bindPasswordEncrypted;

    @Column(name = "start_tls", nullable = false)
    private boolean startTls;

    @Column(name = "user_search_base")
    private String userSearchBase;

    /** Contains {@code {0}}; see the column comment in V57 and D8 in the plan. */
    @Column(name = "user_search_filter")
    private String userSearchFilter;

    /** {@code objectGUID} or {@code entryUUID} — never a DN. */
    @Column(name = "user_id_attribute")
    private String userIdAttribute;

    @Column(name = "email_attribute")
    private String emailAttribute;

    @Column(name = "display_name_attribute")
    private String displayNameAttribute;
```

- [x] **Step 2: A second factory rather than a twelve-argument one**

`register` stays exactly as it is and keeps its name for OIDC. Add beside it:

```java
    /**
     * Registers a directory provider — <strong>disabled</strong>, like {@link #register}, and for
     * the same reason: a search base typed wrong is a sign-in route that refuses everybody.
     *
     * <p>A second factory rather than a widened one. The two kinds share nine fields and disagree
     * about six, so one factory would take fourteen arguments of which five are null at every call
     * site, and a caller that passed an LDAP argument to an OIDC registration would compile.
     *
     * @param companyId taken from {@link com.tcms.tenancy.CompanyContext}, never from a request body
     */
    public static IdentityProvider registerDirectory(
            UUID id,
            UUID companyId,
            String key,
            String displayName,
            String directoryUrl,
            String bindDn,
            byte[] bindPasswordEncrypted,
            boolean startTls,
            String userSearchBase,
            String userSearchFilter,
            String userIdAttribute,
            String emailAttribute,
            String displayNameAttribute,
            UUID createdBy,
            Instant now) {

        IdentityProvider provider = new IdentityProvider();
        provider.id = id;
        provider.companyId = companyId;
        provider.key = key;
        provider.kind = IdentityProviderKind.LDAP;
        provider.displayName = displayName;
        provider.issuerUri = directoryUrl;
        provider.bindDn = bindDn;
        provider.bindPasswordEncrypted = bindPasswordEncrypted;
        provider.startTls = startTls;
        provider.userSearchBase = userSearchBase;
        provider.userSearchFilter = userSearchFilter;
        provider.userIdAttribute = userIdAttribute;
        provider.emailAttribute = emailAttribute;
        provider.displayNameAttribute = displayNameAttribute;
        provider.jitProvisioning = false;
        provider.autoLinkVerifiedEmail = false;
        provider.enabled = false;
        provider.createdAt = now;
        provider.createdBy = createdBy;
        provider.updatedAt = now;
        provider.updatedBy = createdBy;
        return provider;
    }
```

- [x] **Step 3: One mutator, widened — not a second one**

`applyEdit` keeps its single-mutator contract (the header already says why two would stamp
`updatedAt` twice). Add the parameters, and give the bind password `clientSecret`'s null-keeps rule:

```java
    public void applyEdit(
            String newDisplayName,
            String newIssuerUri,
            String newClientId,
            byte[] newClientSecretEncrypted,
            String newBindDn,
            byte[] newBindPasswordEncrypted,
            boolean newStartTls,
            String newUserSearchBase,
            String newUserSearchFilter,
            String newUserIdAttribute,
            String newEmailAttribute,
            String newDisplayNameAttribute,
            boolean newJitProvisioning,
            boolean newAutoLinkVerifiedEmail,
            String newGroupClaim,
            boolean newEnabled,
            UUID editedBy,
            Instant now) {

        this.displayName = newDisplayName;
        this.issuerUri = newIssuerUri;
        this.clientId = newClientId;
        if (newClientSecretEncrypted != null) {
            this.clientSecretEncrypted = newClientSecretEncrypted;
        }
        this.bindDn = newBindDn;
        // Null KEEPS, exactly as the client secret does: no read path echoes it back, so an edit
        // that did not retype it has nothing to resend and must not blank it.
        if (newBindPasswordEncrypted != null) {
            this.bindPasswordEncrypted = newBindPasswordEncrypted;
        }
        this.startTls = newStartTls;
        this.userSearchBase = newUserSearchBase;
        this.userSearchFilter = newUserSearchFilter;
        this.userIdAttribute = newUserIdAttribute;
        this.emailAttribute = newEmailAttribute;
        this.displayNameAttribute = newDisplayNameAttribute;
        this.jitProvisioning = newJitProvisioning;
        this.autoLinkVerifiedEmail = newAutoLinkVerifiedEmail;
        this.groupClaim = newGroupClaim;
        this.enabled = newEnabled;
        this.updatedBy = editedBy;
        this.updatedAt = now;
    }
```

Add the eight getters in the file's existing style (`getBindDn`, `getBindPasswordEncrypted`,
`isStartTls`, `getUserSearchBase`, `getUserSearchFilter`, `getUserIdAttribute`, `getEmailAttribute`,
`getDisplayNameAttribute`).

- [x] **Step 4: Input and view**

`IdentityProviderInput` gains, in contract order: `String bindDn`, `String bindPassword`,
`Boolean startTls`, `String userSearchBase`, `String userSearchFilter`, `String userIdAttribute`,
`String emailAttribute`, `String displayNameAttribute` — each with the javadoc `@param` the file's
style requires; `bindPassword`'s says *"null keeps the stored one, for `clientSecret`'s reason"*.

`IdentityProviderView` gains `boolean bindPasswordSet`, `String bindDn`, `boolean startTls`,
`String userSearchBase`, `String userSearchFilter`, `String userIdAttribute`, `String emailAttribute`,
`String displayNameAttribute`, and `of` fills them:

```java
                provider.getBindDn(),
                provider.getBindPasswordEncrypted() != null,
                provider.isStartTls(),
                provider.getUserSearchBase(),
                provider.getUserSearchFilter(),
                provider.getUserIdAttribute(),
                provider.getEmailAttribute(),
                provider.getDisplayNameAttribute(),
```

**There is no `bindPassword` on the view.** `responseCredentials.test.ts` will fail the build if one
appears, which is §219's rule doing the job it was built for.

- [x] **Step 5: Compile**

```bash
cd backend && ./gradlew build -x test
```

Expected: FAIL at `IdentityProviderService`, which still calls the old `applyEdit`. That is Task 5.

---

## Task 5: Kind-aware validation, and the bind password through `ProviderSecrets`

**Files:**
- Modify: `backend/src/main/java/com/tcms/identity/IdentityProviderService.java`
- Modify: `backend/src/test/java/com/tcms/identity/IdentityProviderEndpointTest.java`

- [x] **Step 1: Write the failing tests**

Add to `IdentityProviderEndpointTest` (§220's file — read its existing helpers and reuse them; these
four use whatever `postProvider`/`asCompanyAdmin` seam that file already has):

```java
    @Test
    @DisplayName("a directory provider is refused unless its URL is encrypted")
    void aPlainLdapUrlIsRefused() {
        var refusal = registerDirectory(input -> input.withIssuerUri("ldap://dc.acme.test:389")
                .withStartTls(false));

        assertThat(refusal.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(fieldErrors(refusal)).containsKey("issuerUri");
    }

    @Test
    @DisplayName("ldap:// with StartTLS is accepted")
    void startTlsMakesAPlainUrlAcceptable() {
        var created = registerDirectory(input -> input.withIssuerUri("ldap://dc.acme.test:389")
                .withStartTls(true));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a search filter without {0} is refused")
    void aFilterMustCarryThePlaceholder() {
        var refusal = registerDirectory(input -> input.withUserSearchFilter("(mail=*)"));

        assertThat(fieldErrors(refusal)).containsKey("userSearchFilter");
    }

    @Test
    @DisplayName("the bind password is stored, and no read path can see it")
    void theBindPasswordLeavesByOneDoor() {
        var created = registerDirectory(input -> input.withBindPassword("s3rvice"));

        assertThat(created.getBody().bindPasswordSet()).isTrue();
        assertThat(created.getBody()).extracting(Object::toString).asString()
                .doesNotContain("s3rvice");
    }
```

- [x] **Step 2: Run them and watch them fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.identity.IdentityProviderEndpointTest'
```

Expected: FAIL — four tests, plus compilation errors from Task 4's widened `applyEdit`.

- [x] **Step 3: Split `validate` by kind**

Replace `validate` and add the two arms plus the shared fields:

```java
    private static final Pattern LDAPS_URL = Pattern.compile("^ldaps://.+");
    private static final Pattern LDAP_URL = Pattern.compile("^ldaps?://.+");

    private Map<String, List<String>> validate(IdentityProviderInput input, boolean registering) {
        Map<String, List<String>> errors = new LinkedHashMap<>();

        if (registering) {
            String key = input.key() == null ? "" : input.key().trim();
            if (!KEY.matcher(key).matches() || key.length() > MAX_KEY) {
                errors.put("key", List.of(
                        "A key is lowercase letters, digits and hyphens, up to " + MAX_KEY
                                + " characters."));
            }
            if (input.kind() == null) {
                errors.put("kind", List.of("Choose what kind of provider this is."));
            }
        }

        String displayName = input.displayName() == null ? "" : input.displayName().trim();
        if (displayName.isEmpty() || displayName.length() > MAX_DISPLAY_NAME) {
            errors.put("displayName", List.of(
                    "A display name is required, up to " + MAX_DISPLAY_NAME + " characters."));
        }

        if (input.kind() == IdentityProviderKind.LDAP) {
            validateDirectory(input, errors);
        } else {
            validateIssuer(input, errors);
        }
        return errors;
    }

    private static void validateIssuer(IdentityProviderInput input,
                                       Map<String, List<String>> errors) {
        String issuerUri = trimmed(input.issuerUri());
        if (!issuerUri.startsWith("https://") || issuerUri.length() > MAX_URI) {
            // Not a style rule. The discovery document, and therefore the jwks_uri every signature
            // is verified against, is fetched from this URL — over http, anyone on the path
            // nominates the keys that decide who signs in.
            errors.put("issuerUri", List.of("The issuer must be an https:// URL."));
        }
        String clientId = trimmed(input.clientId());
        if (clientId.isEmpty() || clientId.length() > MAX_URI) {
            errors.put("clientId", List.of("A client id is required."));
        }
    }

    /**
     * The directory arm. <strong>Two of these three are the security-relevant ones</strong> and
     * both are refusals with no escape hatch: an unencrypted bind puts the person's own directory
     * password on the wire, and a filter without the placeholder is an injection point on a surface
     * an anonymous caller reaches.
     */
    private static void validateDirectory(IdentityProviderInput input,
                                          Map<String, List<String>> errors) {
        String url = trimmed(input.issuerUri());
        boolean startTls = Boolean.TRUE.equals(input.startTls());
        if (!LDAP_URL.matcher(url).matches() || url.length() > MAX_URI
                || (!LDAPS_URL.matcher(url).matches() && !startTls)) {
            errors.put("issuerUri", List.of(
                    "A directory URL must be ldaps://, or ldap:// with StartTLS — a plain bind puts "
                            + "your people's directory passwords on the wire."));
        }
        if (trimmed(input.userSearchBase()).isEmpty()) {
            errors.put("userSearchBase", List.of(
                    "A search base is required — 'ou=people,dc=acme,dc=test'."));
        }
        String filter = trimmed(input.userSearchFilter());
        if (filter.isEmpty() || !filter.contains("{0}")) {
            errors.put("userSearchFilter", List.of(
                    "A search filter is required and must contain {0}, where the address is "
                            + "substituted — '(|(userPrincipalName={0})(mail={0}))'."));
        }
        if (trimmed(input.userIdAttribute()).isEmpty()) {
            errors.put("userIdAttribute", List.of(
                    "Name the attribute holding the directory's own identifier — 'objectGUID' on "
                            + "Active Directory, 'entryUUID' on OpenLDAP. Not a DN: a DN changes "
                            + "when somebody moves between OUs."));
        }
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
```

- [x] **Step 4: Branch `register` on the kind**

```java
        UUID companyId = CompanyContext.require();
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();

        IdentityProvider provider = providers.saveAndFlush(
                input.kind() == IdentityProviderKind.LDAP
                        ? IdentityProvider.registerDirectory(
                                id,
                                companyId,
                                input.key().trim(),
                                input.displayName().trim(),
                                input.issuerUri().trim(),
                                blankToNull(input.bindDn()),
                                secrets.encrypt(input.bindPassword()),
                                Boolean.TRUE.equals(input.startTls()),
                                input.userSearchBase().trim(),
                                input.userSearchFilter().trim(),
                                input.userIdAttribute().trim(),
                                blankToNull(input.emailAttribute()),
                                blankToNull(input.displayNameAttribute()),
                                callerId,
                                now)
                        : IdentityProvider.register(
                                id,
                                companyId,
                                input.key().trim(),
                                input.kind(),
                                input.displayName().trim(),
                                input.issuerUri().trim(),
                                input.clientId().trim(),
                                secrets.encrypt(input.clientSecret()),
                                callerId,
                                now));
```

- [x] **Step 5: Widen the `update` call and the audit document**

```java
        provider.applyEdit(
                input.displayName().trim(),
                input.issuerUri().trim(),
                provider.getKind() == IdentityProviderKind.LDAP ? null : input.clientId().trim(),
                secrets.encrypt(input.clientSecret()),
                blankToNull(input.bindDn()),
                secrets.encrypt(input.bindPassword()),
                Boolean.TRUE.equals(input.startTls()),
                blankToNull(input.userSearchBase()),
                blankToNull(input.userSearchFilter()),
                blankToNull(input.userIdAttribute()),
                blankToNull(input.emailAttribute()),
                blankToNull(input.displayNameAttribute()),
                Boolean.TRUE.equals(input.jitProvisioning()),
                Boolean.TRUE.equals(input.autoLinkVerifiedEmail()),
                blankToNull(input.groupClaim()),
                Boolean.TRUE.equals(input.enabled()),
                callerId,
                now);
```

And in `changes`, after `clientId`, add the directory fields that are **not** credentials:

```java
                .append(",\"bindDn\":").append(AuditEvent.jsonString(provider.getBindDn()))
                .append(",\"startTls\":").append(provider.isStartTls())
                .append(",\"userSearchBase\":")
                .append(AuditEvent.jsonString(provider.getUserSearchBase()))
                .append(",\"userSearchFilter\":")
                .append(AuditEvent.jsonString(provider.getUserSearchFilter()))
                .append(",\"userIdAttribute\":")
                .append(AuditEvent.jsonString(provider.getUserIdAttribute()))
```

**No bind password, and not even a placeholder for one** — `changes`'s existing header already says
why, and the reason applies unchanged: the client's own administrators read that table.

Update the class javadoc's "The client secret leaves by exactly one door" heading to say *"Two
credentials leave by exactly one door"* and name `bindPassword` beside `clientSecret`.

- [x] **Step 6: Run the endpoint tests**

```bash
cd backend && ./gradlew test --tests 'com.tcms.identity.IdentityProviderEndpointTest'
```

Expected: PASS, including everything §220 wrote.

- [x] **Step 7: Commit**

```bash
git add backend/src/main/java/com/tcms/identity/ backend/src/test/java/com/tcms/identity/
git commit -m "§NNN phase 3 — a provider that is a directory, validated on its own terms"
```

---

## Task 6: `LdapDirectory` — the only class that speaks LDAP

**Files:**
- Modify: `backend/gradle/libs.versions.toml`, `backend/build.gradle.kts`
- Create: `backend/src/main/java/com/tcms/auth/LdapProperties.java`
- Create: `backend/src/main/java/com/tcms/auth/LdapDirectory.java`
- Create: `backend/src/test/java/com/tcms/auth/LdapDirectoryTest.java`
- Create: `backend/src/test/resources/ldap/f3-directory.ldif`

- [x] **Step 1: Add the dependency**

`libs.versions.toml`:

```toml
spring-boot-starter-ldap = { module = "org.springframework.boot:spring-boot-starter-ldap" }
unboundid-ldapsdk = { module = "com.unboundid:unboundid-ldapsdk" }
```

`build.gradle.kts`:

```kotlin
    implementation(libs.spring.boot.starter.ldap)
    testImplementation(libs.unboundid.ldapsdk)
```

Both are version-managed by the Spring Boot BOM, so neither entry carries a `version.ref` — the same
arrangement every other starter here uses.

- [x] **Step 2: The fixture directory**

`backend/src/test/resources/ldap/f3-directory.ldif` — **fixture UUID prefix `f3`, hand-managed, see
this plan's lane-ownership section:**

```ldif
dn: dc=acme,dc=test
objectClass: top
objectClass: domain
dc: acme

dn: ou=people,dc=acme,dc=test
objectClass: top
objectClass: organizationalUnit
ou: people

dn: uid=dana,ou=people,dc=acme,dc=test
objectClass: top
objectClass: person
objectClass: organizationalPerson
objectClass: inetOrgPerson
uid: dana
cn: Dana Director
sn: Director
mail: dana@acme.test
userPassword: directory-password

dn: uid=twin-a,ou=people,dc=acme,dc=test
objectClass: top
objectClass: person
objectClass: organizationalPerson
objectClass: inetOrgPerson
uid: twin-a
cn: Twin A
sn: A
mail: twin@acme.test
userPassword: twin-password

dn: uid=twin-b,ou=people,dc=acme,dc=test
objectClass: top
objectClass: person
objectClass: organizationalPerson
objectClass: inetOrgPerson
uid: twin-b
cn: Twin B
sn: B
mail: twin@acme.test
userPassword: twin-password
```

- [x] **Step 3: Write the failing test**

```java
package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.unboundid.ldap.listener.InMemoryDirectoryServer;
import com.unboundid.ldap.listener.InMemoryDirectoryServerConfig;
import com.unboundid.ldap.listener.InMemoryListenerConfig;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import java.time.Duration;
import java.util.Optional;
import javax.net.ssl.SSLSocketFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The LDAP half, driven against a real directory server in-process.
 *
 * <p>UnboundID's {@code InMemoryDirectoryServer} rather than a container: it speaks the protocol,
 * it serves LDAPS with a generated certificate — so these tests drive the ENCRYPTED path production
 * drives, which is the whole reason D2 needs no {@code allowInsecure} escape hatch — and it starts
 * in milliseconds without Docker.
 */
class LdapDirectoryTest {

    private static InMemoryDirectoryServer server;
    private static LdapDirectory directory;

    @BeforeAll
    static void startDirectory() throws Exception {
        InMemoryDirectoryServerConfig config =
                new InMemoryDirectoryServerConfig("dc=acme,dc=test");
        config.addAdditionalBindCredentials("cn=service,dc=acme,dc=test", "service-password");
        SSLUtil serverSsl = new SSLUtil(new TrustAllTrustManager());
        config.setListenerConfigs(InMemoryListenerConfig.createLDAPSConfig(
                "ldaps", null, 0, serverSsl.createSSLServerSocketFactory(),
                (SSLSocketFactory) SSLSocketFactory.getDefault()));
        config.setSchema(null);

        server = new InMemoryDirectoryServer(config);
        server.importFromLDIF(true,
                LdapDirectoryTest.class.getResource("/ldap/f3-directory.ldif").getFile());
        server.startListening();

        directory = new LdapDirectory(new LdapProperties(
                Duration.ofSeconds(3), Duration.ofSeconds(5), 5, Duration.ofSeconds(60)));
    }

    @AfterAll
    static void stopDirectory() {
        server.shutDown(true);
    }

    private LdapDirectory.Target target() {
        return new LdapDirectory.Target(
                "ldaps://localhost:" + server.getListenPort("ldaps"),
                false,
                "cn=service,dc=acme,dc=test",
                "service-password",
                "ou=people,dc=acme,dc=test",
                "(mail={0})",
                "entryUUID",
                "mail",
                "cn");
    }

    @Test
    @DisplayName("the right password authenticates and the subject is the directory's own id")
    void aCorrectBindResolvesAnEntry() {
        Optional<LdapDirectory.Entry> entry =
                directory.authenticate(target(), "dana@acme.test", "directory-password");

        assertThat(entry).isPresent();
        assertThat(entry.get().email()).isEqualTo("dana@acme.test");
        assertThat(entry.get().displayName()).isEqualTo("Dana Director");
        // entryUUID, not the DN: D3. Whatever it is, it is stable and it is not "uid=dana,…".
        assertThat(entry.get().subject()).isNotBlank().doesNotContain("uid=dana");
    }

    @Test
    @DisplayName("the wrong password resolves nothing")
    void aWrongPasswordRefuses() {
        assertThat(directory.authenticate(target(), "dana@acme.test", "not-it")).isEmpty();
    }

    @Test
    @DisplayName("an address matching two entries refuses rather than picking one")
    void anAmbiguousMatchRefuses() {
        assertThat(directory.authenticate(target(), "twin@acme.test", "twin-password")).isEmpty();
    }

    @Test
    @DisplayName("a filter metacharacter is escaped rather than interpolated")
    void theFilterIsEscaped() {
        // Concatenation would make this `(mail=*)(objectClass=*)` and match the whole subtree.
        assertThat(directory.authenticate(target(), "*)(objectClass=*", "anything")).isEmpty();
    }

    @Test
    @DisplayName("an empty password is refused without reaching the directory")
    void anEmptyPasswordNeverBinds() {
        // An empty password is an UNAUTHENTICATED bind in the protocol, which most directories
        // answer with success — so a client that passes it through treats "no password" as "signed
        // in". RFC 4513's own warning, and the single most common LDAP authentication defect.
        assertThat(directory.authenticate(target(), "dana@acme.test", "")).isEmpty();
    }
}
```

- [x] **Step 4: Run it and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.LdapDirectoryTest'
```

Expected: FAIL — `LdapDirectory` and `LdapProperties` do not exist.

- [x] **Step 5: Write `LdapProperties`**

```java
package com.tcms.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * How this instance talks to a customer's directory, and how much it will take from a stranger.
 *
 * <p>{@link OidcProperties}' lesson one protocol over: <strong>the host was chosen by somebody
 * outside this deployment.</strong> A directory that accepts a connection and never answers holds a
 * request thread until the container's own limit, so an unreachable or hostile server is a denial
 * of service against the whole instance rather than a failed sign-in.
 *
 * @param connectTimeout how long to wait for the directory to accept a connection
 * @param readTimeout    how long to wait for it to answer once it has. The one that matters
 * @param maxBindAttempts how many failures for one identifier at one provider before this instance
 *                       refuses <strong>without binding</strong>. Unlike local login, which has no
 *                       lockout by decision, every failed bind here increments the
 *                       <em>customer's</em> directory counter — so an unthrottled endpoint is a way
 *                       to lock a company's staff out of Active Directory from the internet, and
 *                       the harm lands on a system this product does not own
 * @param bindLockout    how long that refusal lasts
 */
@ConfigurationProperties(prefix = "tcms.ldap")
@Validated
public record LdapProperties(
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Positive int maxBindAttempts,
        @NotNull Duration bindLockout) {
}
```

Register it in `application.yml` beside `tcms.oidc` (required-property form — `${VAR}` with **no
colon**, per the working agreement; these four are literals, not environment reads):

```yaml
  ldap:
    connect-timeout: 3s
    read-timeout: 5s
    max-bind-attempts: 5
    bind-lockout: 60s
```

and add `LdapProperties.class` to the `@EnableConfigurationProperties` list that already carries
`OidcProperties.class` (in `OidcConfiguration`).

- [x] **Step 6: Write `LdapDirectory`**

```java
package com.tcms.auth;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.naming.directory.SearchControls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.AttributesMapper;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.filter.EqualsFilter;
import org.springframework.ldap.support.LdapEncoder;
import org.springframework.stereotype.Component;

/**
 * The one class in this codebase that speaks LDAP.
 *
 * <p>It lives in {@code auth} rather than {@code identity} for {@link OidcClient}'s reason and by
 * the same rule: {@code identity} may not reach the network (§6r trap 5, enforced by
 * {@code ArchitectureRulesTest}), so the protocol lives here and
 * {@link com.tcms.identity.FederatedIdentityService} stays pure policy over the database.
 *
 * <h2>Search, then bind — and the search is escaped</h2>
 *
 * <p>The typed address goes into an administrator-supplied filter. Concatenating it gives an
 * attacker {@code *)(objectClass=*} on an unauthenticated surface, whose best case is matching the
 * wrong person and whose worst case is an entry this class then binds against. The substitution is
 * {@link LdapEncoder#filterEncode}, and the filter is refused at save time unless it carries
 * {@code {0}}.
 *
 * <h2>Three refusals that look like successes if you write them naively</h2>
 *
 * <ul>
 *   <li><strong>An empty password is an unauthenticated bind</strong> in the protocol, and most
 *       directories answer it with success. A client that passes it through treats "no password
 *       given" as "signed in". Refused here before a connection is opened.</li>
 *   <li><strong>More than one match is a refusal</strong>, not a "take the first" — a stale
 *       duplicate in a directory would otherwise let result ordering choose a person.</li>
 *   <li><strong>Referrals are not followed.</strong> An AD server may answer with a referral to
 *       another host; following it binds the service credential, and then the person's password,
 *       against a server nominated by the response rather than by configuration.</li>
 * </ul>
 */
@Component
public class LdapDirectory {

    private static final Logger log = LoggerFactory.getLogger(LdapDirectory.class);

    /** JNDI reads these as STRINGS OF MILLISECONDS; a context with neither blocks for ever. */
    private static final String CONNECT_TIMEOUT = "com.sun.jndi.ldap.connect.timeout";
    private static final String READ_TIMEOUT = "com.sun.jndi.ldap.read.timeout";

    private final LdapProperties properties;

    public LdapDirectory(LdapProperties properties) {
        this.properties = properties;
    }

    /** A provider's directory configuration, flattened — no entity crosses into this package. */
    public record Target(
            String url,
            boolean startTls,
            String bindDn,
            String bindPassword,
            String userSearchBase,
            String userSearchFilter,
            String userIdAttribute,
            String emailAttribute,
            String displayNameAttribute) {
    }

    /** What the directory said about somebody who has just proved they are them. */
    public record Entry(String dn, String subject, String email, String displayName) {
    }

    /**
     * Searches for the address, then binds as the entry it found.
     *
     * @return the entry, or empty for every failure — no entry, several entries, wrong password,
     *         unreachable directory, missing identifier attribute. The caller must not tell them
     *         apart (§6r)
     */
    public Optional<Entry> authenticate(Target target, String identifier, String password) {
        if (password == null || password.isEmpty()) {
            return Optional.empty();
        }
        try {
            Optional<Entry> found = findEntry(target, identifier);
            if (found.isEmpty()) {
                // A synthetic bind, so a miss and a wrong password cost the same round trips.
                // AuthenticationService's dummy-hash rule, one protocol over: without it, "no such
                // person" answers in milliseconds and "wrong password" in a network round trip, and
                // the difference is readable with a stopwatch by anyone.
                bind(target, "uid=" + java.util.UUID.randomUUID() + "," + target.userSearchBase(),
                        password);
                return Optional.empty();
            }
            Entry entry = found.get();
            return bind(target, entry.dn(), password) ? Optional.of(entry) : Optional.empty();
        } catch (RuntimeException failure) {
            // No identifier, no DN, no password — nothing about the credential reaches a log (D7).
            log.info("Directory lookup at {} did not resolve", target.url());
            return Optional.empty();
        }
    }

    private Optional<Entry> findEntry(Target target, String identifier) {
        LdapTemplate template = templateFor(target, target.bindDn(), target.bindPassword());
        String filter = target.userSearchFilter()
                .replace("{0}", LdapEncoder.filterEncode(identifier));

        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        controls.setCountLimit(2);
        controls.setReturningAttributes(new String[] {
                target.userIdAttribute(), target.emailAttribute(), target.displayNameAttribute()});

        List<Entry> matches = template.search(
                target.userSearchBase(), filter, controls,
                (AttributesMapper<Entry>) attributes -> new Entry(
                        null,
                        subjectOf(attributes.get(target.userIdAttribute())),
                        stringOf(attributes.get(target.emailAttribute())),
                        stringOf(attributes.get(target.displayNameAttribute()))),
                (name, entry) -> new Entry(
                        name.toString(), entry.subject(), entry.email(), entry.displayName()));

        if (matches.size() != 1 || matches.getFirst().subject() == null) {
            return Optional.empty();
        }
        return Optional.of(matches.getFirst());
    }
```

> **Note for the implementer.** Spring LDAP has no two-mapper `search` overload; the block above
> shows *what must be produced* — a DN **and** the three attributes. Implement it with
> `template.search(base, filter, controls, new ContextMapper<Entry>() { … })`, reading the DN from
> `DirContextAdapter.getNameInNamespace()` and the attributes from the same adapter. The
> `countLimit(2)` is load-bearing: it is what makes "more than one" cheap to detect without pulling a
> whole subtree back on a filter an administrator got wrong.

```java
    private boolean bind(Target target, String dn, String password) {
        try {
            templateFor(target, dn, password).lookup(dn);
            return true;
        } catch (RuntimeException refused) {
            return false;
        }
    }

    private LdapTemplate templateFor(Target target, String userDn, String password) {
        LdapContextSource source = new LdapContextSource();
        source.setUrl(target.url());
        if (userDn != null && !userDn.isBlank()) {
            source.setUserDn(userDn);
            source.setPassword(password);
        } else {
            source.setAnonymousReadOnly(true);
        }
        // D7: a referral is a server the RESPONSE chose, and following it would bind there.
        source.setReferral("ignore");

        Map<String, Object> environment = new HashMap<>();
        environment.put(CONNECT_TIMEOUT, String.valueOf(properties.connectTimeout().toMillis()));
        environment.put(READ_TIMEOUT, String.valueOf(properties.readTimeout().toMillis()));
        source.setBaseEnvironmentProperties(environment);
        source.afterPropertiesSet();

        LdapTemplate template = new LdapTemplate(target.startTls()
                ? new StartTlsContextSource(source)
                : source);
        template.setIgnorePartialResultException(true);
        return template;
    }

    /**
     * The identifier, in the form the customer's administrator can look up.
     *
     * <p><strong>{@code objectGUID} is a 16-byte octet string whose first three fields are
     * little-endian</strong> — the canonical GUID a Windows administrator sees is a byte-swapped
     * rendering of what the wire carries. Two ways to get this wrong and both are permanent once
     * rows exist: {@code new String(bytes)} produces platform-dependent mojibake, so the key stops
     * being stable and every affected person forks a second account on their next sign-in; and
     * rendering in wire order gives a GUID that matches nothing anyone can search for.
     * {@code entryUUID} arrives as text and is used as-is.
     */
    private static String subjectOf(javax.naming.directory.Attribute attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            Object value = attribute.get();
            if (value instanceof byte[] bytes && bytes.length == 16) {
                return guidOf(bytes);
            }
            return value == null ? null : value.toString();
        } catch (javax.naming.NamingException unreadable) {
            return null;
        }
    }

    private static String guidOf(byte[] bytes) {
        int[] order = {3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15};
        StringBuilder guid = new StringBuilder(36);
        for (int index = 0; index < order.length; index++) {
            if (index == 4 || index == 6 || index == 8 || index == 10) {
                guid.append('-');
            }
            guid.append(String.format("%02x", bytes[order[index]]));
        }
        return guid.toString();
    }

    private static String stringOf(javax.naming.directory.Attribute attribute) {
        try {
            if (attribute == null || attribute.get() == null) {
                return null;
            }
            Object value = attribute.get();
            return value instanceof byte[] bytes
                    ? new String(bytes, StandardCharsets.UTF_8)
                    : value.toString();
        } catch (javax.naming.NamingException unreadable) {
            return null;
        }
    }
}
```

> `StartTlsContextSource` above is Spring LDAP's own
> `org.springframework.ldap.core.support.LdapContextSource` configured with a
> `DefaultTlsDirContextAuthenticationStrategy`. Implement it that way — set
> `source.setAuthenticationStrategy(new DefaultTlsDirContextAuthenticationStrategy())` on the same
> source when `target.startTls()` — rather than introducing a wrapper class; the branch is one line
> and a wrapper would be a type whose only job is to hold a flag. Delete the ternary accordingly.
> `EqualsFilter` in the imports is unused once the filter is built from the administrator's string —
> drop it; `-Werror` will not catch an unused import, but the review will.

- [x] **Step 7: Run the directory tests**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.LdapDirectoryTest'
```

Expected: PASS, five tests.

- [x] **Step 8: Commit**

```bash
git add backend/build.gradle.kts backend/gradle/libs.versions.toml \
        backend/src/main/java/com/tcms/auth/LdapDirectory.java \
        backend/src/main/java/com/tcms/auth/LdapProperties.java \
        backend/src/main/resources/application.yml \
        backend/src/test/java/com/tcms/auth/LdapDirectoryTest.java \
        backend/src/test/resources/ldap/f3-directory.ldif
git commit -m "§NNN phase 4 — the protocol, escaped, timed out, and refusing three things that look like successes"
```

---

## Task 7: `BindThrottle` — the bound that protects somebody else's system

**Files:**
- Create: `backend/src/main/java/com/tcms/auth/BindThrottle.java`
- Create: `backend/src/test/java/com/tcms/auth/BindThrottleTest.java`

- [x] **Step 1: Write the failing test**

```java
package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BindThrottleTest {

    private static final UUID PROVIDER = UUID.fromString("f3000000-0000-0000-0000-000000000010");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-15T10:00:00Z"));
    private final BindThrottle throttle =
            new BindThrottle(new LdapProperties(Duration.ofSeconds(3), Duration.ofSeconds(5), 3,
                    Duration.ofSeconds(60)), clock);

    @Test
    @DisplayName("the fourth failure is refused without reaching the directory")
    void itStopsAtTheConfiguredCount() {
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(throttle.mayBind(PROVIDER, "dana@acme.test")).isTrue();
            throttle.recordFailure(PROVIDER, "dana@acme.test");
        }
        assertThat(throttle.mayBind(PROVIDER, "dana@acme.test")).isFalse();
    }

    @Test
    @DisplayName("the identifier is compared case-insensitively")
    void caseDoesNotBuyMoreAttempts() {
        for (int attempt = 0; attempt < 3; attempt++) {
            throttle.recordFailure(PROVIDER, "dana@acme.test");
        }
        assertThat(throttle.mayBind(PROVIDER, "DANA@ACME.TEST")).isFalse();
    }

    @Test
    @DisplayName("a success clears the count, so a person who mistypes twice is not locked out")
    void successForgets() {
        throttle.recordFailure(PROVIDER, "dana@acme.test");
        throttle.recordFailure(PROVIDER, "dana@acme.test");
        throttle.recordSuccess(PROVIDER, "dana@acme.test");
        throttle.recordFailure(PROVIDER, "dana@acme.test");

        assertThat(throttle.mayBind(PROVIDER, "dana@acme.test")).isTrue();
    }

    @Test
    @DisplayName("the lockout expires")
    void itForgetsAfterTheWindow() {
        for (int attempt = 0; attempt < 3; attempt++) {
            throttle.recordFailure(PROVIDER, "dana@acme.test");
        }
        clock.advance(Duration.ofSeconds(61));

        assertThat(throttle.mayBind(PROVIDER, "dana@acme.test")).isTrue();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
```

- [x] **Step 2: Run it and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.BindThrottleTest'
```

Expected: FAIL — no such class.

- [x] **Step 3: Implement it**

```java
package com.tcms.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * How many times one identifier may fail against one provider before this instance stops asking
 * the directory.
 *
 * <h2>Why this exists when local login has no lockout</h2>
 *
 * <p>Not having one is a decision this product already took, and it is right for a credential this
 * product owns: an attacker guessing a TCMS password costs a bcrypt verification and nothing else.
 * <strong>A failed LDAP bind increments the customer's own directory counter</strong> — with Active
 * Directory's default policy, an endpoint with no bound here is a way to lock a company's entire
 * staff out of everything, from the internet, in minutes. The harm lands on a system this product
 * does not own and cannot unlock.
 *
 * <p>{@link RateLimitFilter} cannot do this job: it counts API tokens, and its own header explains
 * why an unauthenticated request has no key that the caller does not control. This one is keyed on
 * something the caller <em>does</em> control, which is normally the objection — but here the key is
 * exactly the thing being protected, so an attacker who rotates the identifier is no longer
 * attacking any one directory account.
 *
 * <p>In memory, in one process, like {@link TokenRateLimiter}: TCMS is one deployable, and a second
 * replica multiplies the bound by the replica count.
 */
@Component
public class BindThrottle {

    private record Key(UUID providerId, String identifier) {
    }

    private record Failures(int count, Instant first) {
    }

    /** Bounded so a stranger cannot grow the map: 10k identifiers, oldest entry evicted. */
    private static final int MAX_TRACKED = 10_000;

    private final Map<Key, Failures> failures = new ConcurrentHashMap<>();
    private final LdapProperties properties;
    private final Clock clock;

    public BindThrottle(LdapProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Whether a bind may be attempted at all. A false here never reaches the directory. */
    public boolean mayBind(UUID providerId, String identifier) {
        Failures held = failures.get(keyFor(providerId, identifier));
        if (held == null) {
            return true;
        }
        if (clock.instant().isAfter(held.first().plus(properties.bindLockout()))) {
            failures.remove(keyFor(providerId, identifier));
            return true;
        }
        return held.count() < properties.maxBindAttempts();
    }

    public void recordFailure(UUID providerId, String identifier) {
        if (failures.size() >= MAX_TRACKED) {
            failures.entrySet().removeIf(entry ->
                    clock.instant().isAfter(entry.getValue().first().plus(properties.bindLockout())));
        }
        Instant now = clock.instant();
        failures.compute(keyFor(providerId, identifier), (key, held) ->
                held == null || now.isAfter(held.first().plus(properties.bindLockout()))
                        ? new Failures(1, now)
                        : new Failures(held.count() + 1, held.first()));
    }

    /** A success forgets, so somebody who mistyped twice and then got it right is not locked out. */
    public void recordSuccess(UUID providerId, String identifier) {
        failures.remove(keyFor(providerId, identifier));
    }

    private static Key keyFor(UUID providerId, String identifier) {
        return new Key(providerId, identifier == null ? "" : identifier.toLowerCase(Locale.ROOT));
    }
}
```

- [x] **Step 4: Run it**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.BindThrottleTest'
```

Expected: PASS, four tests.

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java/com/tcms/auth/BindThrottle.java \
        backend/src/test/java/com/tcms/auth/BindThrottleTest.java
git commit -m "§NNN phase 5 — the bound that protects the customer's directory rather than this instance"
```

---

## Task 8: `LdapLoginService` and the endpoint

**Files:**
- Create: `backend/src/main/java/com/tcms/auth/LdapLoginService.java`
- Create: `backend/src/main/java/com/tcms/auth/LdapController.java`
- Modify: `backend/src/main/java/com/tcms/auth/SsoLoginService.java`
- Modify: `backend/src/test/java/com/tcms/api/EndpointAccessTest.java`

- [x] **Step 1: Extract provider resolution from `SsoLoginService`**

`SsoLoginService.resolve` and its `ResolvedProvider` record are private and both flows need them.
Move them to a package-private component, unchanged in behaviour:

```java
package com.tcms.auth;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Which company a provider id belongs to, asked before any session or company context exists.
 *
 * <p>Extracted from {@link SsoLoginService} in the LDAP slice, unchanged: both sign-in flows have to
 * ask it and only one of them can own it. {@code tcms_resolve_identity_provider} is V53's function
 * and its header carries the reason it is SECURITY INVOKER — {@code identity_provider} carries FORCE
 * ROW LEVEL SECURITY, so a definer function would be subject to its own policy and return zero rows
 * in production while every superuser-migrated test stayed green (V7's defect, V8's fix).
 *
 * <p><strong>It answers a company and a lifecycle flag, and nothing else.</strong> The directory
 * configuration is read afterwards, through the repository, INSIDE the opened context — widening
 * this to carry it would move an RLS-protected read into the one place RLS is being walked around.
 */
@Component
class ProviderResolution {

    /** {@code enabled} is RETURNED rather than filtered on, so refusal happens in Java (§6r). */
    record Resolved(UUID companyId, String issuerUri, String clientId, boolean enabled) {
    }

    private final EntityManager entityManager;
    private final TransactionTemplate transactions;

    ProviderResolution(EntityManager entityManager, TransactionTemplate transactions) {
        this.entityManager = entityManager;
        this.transactions = transactions;
    }

    Optional<Resolved> forProvider(UUID providerId) {
        List<?> rows = transactions.execute(status -> entityManager
                .createNativeQuery(
                        "SELECT * FROM tcms_resolve_identity_provider(CAST(:providerId AS uuid))")
                .setParameter("providerId", providerId.toString())
                .getResultList());
        if (rows == null || rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.getFirst();
        return Optional.of(new Resolved(
                (UUID) row[0], (String) row[1], (String) row[2], (Boolean) row[3]));
    }
}
```

In `SsoLoginService`: delete the private `resolve` and `ResolvedProvider`, take `ProviderResolution`
as a constructor dependency, and change `authorize`'s first lines to
`Optional<ProviderResolution.Resolved> resolved = resolution.forProvider(providerId);`. Its
`EntityManager` field goes if nothing else in the class uses it.

- [x] **Step 2: `ProviderChoice` carries the kind**

```java
    /** One live sign-in route, as a button or as a password field — see {@code kind}. */
    public record ProviderChoice(UUID id, String displayName, IdentityProviderKind kind) {
    }
```

and in `providersFor`, the mapper reads the third column V57 added:

```java
                .map(row -> new ProviderChoice(
                        (UUID) row[0], (String) row[1], IdentityProviderKind.valueOf((String) row[2])))
```

- [x] **Step 3: Write the failing endpoint test**

`backend/src/test/java/com/tcms/auth/LdapLoginEndpointTest.java` — an `IntegrationTestBase`
subclass that starts the same `InMemoryDirectoryServer` as `LdapDirectoryTest`, inserts an **enabled**
LDAP provider through `IdentityProviderRepository` inside a `CompanyContext.open(...)` block (the
repository, not the service — the service would be a second subject in a test about sign-in), and
drives the endpoint:

```java
    @Test
    @DisplayName("an invited person signs in with their directory password")
    void aLinkedDirectoryAccountSignsIn() {
        var response = post("/api/v1/auth/ldap/" + providerId + "/login",
                Map.of("email", "dana@acme.test", "password", "directory-password"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().token()).isNotBlank();
        assertThat(response.getBody().user().email()).isEqualTo("dana@acme.test");
    }

    @Test
    @DisplayName("the second sign-in resolves the same account, and the address is never consulted")
    void theSubjectIsTheKey() {
        post("/api/v1/auth/ldap/" + providerId + "/login",
                Map.of("email", "dana@acme.test", "password", "directory-password"));
        renameInDirectory("uid=dana,ou=people,dc=acme,dc=test", "dana.director@acme.test");

        var second = post("/api/v1/auth/ldap/" + providerId + "/login",
                Map.of("email", "dana.director@acme.test", "password", "directory-password"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(userIdentityCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a wrong password, a disabled provider and an unknown provider are one refusal")
    void everyRefusalIsTheSameRefusal() {
        assertThat(post("/api/v1/auth/ldap/" + providerId + "/login",
                Map.of("email", "dana@acme.test", "password", "wrong")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        disable(providerId);
        assertThat(post("/api/v1/auth/ldap/" + providerId + "/login",
                Map.of("email", "dana@acme.test", "password", "directory-password")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        assertThat(post("/api/v1/auth/ldap/f3000000-0000-0000-0000-0000000000ff/login",
                Map.of("email", "dana@acme.test", "password", "directory-password")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("nobody is provisioned when the provider does not provision")
    void jitOffRefusesAStranger() {
        var response = post("/api/v1/auth/ldap/" + providerId + "/login",
                Map.of("email", "stranger@acme.test", "password", "directory-password"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(appUserCount()).isEqualTo(usersBefore);
    }

    @Test
    @DisplayName("no refusal names its cause, in the body or in the headers")
    void theRefusalCarriesNothing() {
        var response = post("/api/v1/auth/ldap/" + providerId + "/login",
                Map.of("email", "dana@acme.test", "password", "wrong"));

        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders()).doesNotContainKey("X-Reason");
    }
```

> `renameInDirectory`, `disable`, `userIdentityCount`, `appUserCount` and `post` are this file's own
> helpers — write them beside the tests. `post` is the `TestRestTemplate` this suite already uses for
> unauthenticated calls; copy the shape from `IdentityProviderEndpointTest`, which was written in
> §220 for the same reason. `renameInDirectory` is `server.modify(...)` on the in-memory server.

- [x] **Step 4: Run it and watch it fail**

```bash
cd backend && ./gradlew test --tests 'com.tcms.auth.LdapLoginEndpointTest'
```

Expected: FAIL — 404, because the endpoint does not exist yet.

- [x] **Step 5: Write `LdapLoginService`**

```java
package com.tcms.auth;

import com.tcms.identity.AppUser;
import com.tcms.identity.AppUserRepository;
import com.tcms.identity.FederatedIdentityService;
import com.tcms.identity.IdentityProvider;
import com.tcms.identity.IdentityProviderKind;
import com.tcms.identity.IdentityProviderRepository;
import com.tcms.identity.ProviderSecrets;
import com.tcms.tenancy.CompanyContext;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Signing in against a directory — the credential half of external identity.
 *
 * <h2>What is different from {@link SsoLoginService}, and what is deliberately identical</h2>
 *
 * <p>Different: there is no third party to redirect to, so there is no {@code state}, no
 * {@code nonce}, no PKCE and no {@code sso_login_attempt} row. TCMS collects the person's directory
 * password and binds with it, which is a credential this product does not own and cannot rotate.
 *
 * <p>Identical, and not by accident: the ordering, the refusal, and the session.
 *
 * <ol>
 *   <li>the provider is resolved with <strong>no context open</strong>, through V53's function,
 *       which answers a company and a lifecycle flag and nothing else;</li>
 *   <li>the company context is opened from that answer — server-side data resolved from a presented
 *       id, not a company taken from a request parameter;</li>
 *   <li>the full configuration is read <em>inside</em> that context, against the RLS-protected row,
 *       so a provider disabled a moment ago refuses;</li>
 *   <li>the directory proves who they are, and only then is any account question asked;</li>
 *   <li>{@link FederatedIdentityService} answers whose account it is — the same class, with the
 *       same policy, that the OIDC path uses. It is unmodified by this slice, which is the test
 *       that the seam between protocol and policy was drawn in the right place.</li>
 * </ol>
 *
 * <p>{@code emailVerified} is passed as <strong>true</strong>: a directory is authoritative for the
 * addresses it publishes in its own tree. The gate on taking over an existing TCMS account is
 * {@code autoLinkVerifiedEmail}, which is off by default and is an explicit act by the company's
 * own administrator — that was always the gate, and this claim was never it.
 */
@Service
public class LdapLoginService {

    private static final Logger log = LoggerFactory.getLogger(LdapLoginService.class);

    private final ProviderResolution resolution;
    private final IdentityProviderRepository providers;
    private final AppUserRepository users;
    private final FederatedIdentityService identities;
    private final LdapDirectory directory;
    private final BindThrottle throttle;
    private final ProviderSecrets secrets;
    private final SessionTokens tokens;
    private final TransactionTemplate transactions;

    public LdapLoginService(
            ProviderResolution resolution,
            IdentityProviderRepository providers,
            AppUserRepository users,
            FederatedIdentityService identities,
            LdapDirectory directory,
            BindThrottle throttle,
            ProviderSecrets secrets,
            SessionTokens tokens,
            TransactionTemplate transactions) {

        this.resolution = resolution;
        this.providers = providers;
        this.users = users;
        this.identities = identities;
        this.directory = directory;
        this.throttle = throttle;
        this.secrets = secrets;
        this.tokens = tokens;
        this.transactions = transactions;
    }

    /** A session, exactly as local sign-in produces one. */
    public record SignedIn(UUID userId, UUID companyId, SessionTokens.Session session) {
    }

    /**
     * @return the session, or empty for every failure. The caller must not tell them apart (§6r)
     */
    public Optional<SignedIn> signIn(UUID providerId, String email, String password,
                                     boolean rememberMe) {

        if (providerId == null || email == null || email.isBlank()
                || password == null || password.isEmpty()) {
            return Optional.empty();
        }
        if (!throttle.mayBind(providerId, email)) {
            // Refused WITHOUT contacting the directory: every failed bind increments the customer's
            // own lockout counter, and this endpoint is reachable by anyone.
            log.info("Directory sign-in refused for provider {} before binding: too many failures",
                    providerId);
            return Optional.empty();
        }

        Optional<ProviderResolution.Resolved> resolved = resolution.forProvider(providerId);
        if (resolved.isEmpty() || !resolved.get().enabled()) {
            log.info("Directory sign-in refused for provider {}", providerId);
            return Optional.empty();
        }

        try (var ignored = CompanyContext.open(resolved.get().companyId())) {
            return complete(providerId, resolved.get().companyId(), email, password, rememberMe);
        }
    }

    private Optional<SignedIn> complete(UUID providerId, UUID companyId, String email,
                                        String password, boolean rememberMe) {

        IdentityProvider provider = transactions.execute(status ->
                providers.findByIdAndEnabledTrue(providerId).orElse(null));
        if (provider == null || provider.getKind() != IdentityProviderKind.LDAP) {
            return Optional.empty();
        }

        Optional<LdapDirectory.Entry> entry = directory.authenticate(
                new LdapDirectory.Target(
                        provider.getIssuerUri(),
                        provider.isStartTls(),
                        provider.getBindDn(),
                        secrets.decrypt(provider.getBindPasswordEncrypted()),
                        provider.getUserSearchBase(),
                        provider.getUserSearchFilter(),
                        provider.getUserIdAttribute(),
                        provider.getEmailAttribute(),
                        provider.getDisplayNameAttribute()),
                email,
                password);

        if (entry.isEmpty()) {
            throttle.recordFailure(providerId, email);
            return Optional.empty();
        }
        throttle.recordSuccess(providerId, email);

        FederatedIdentityService.Resolution resolution = identities.resolve(
                provider,
                new FederatedIdentityService.Claims(
                        entry.get().subject(),
                        entry.get().email(),
                        // D5: a directory is authoritative for the addresses in its own tree, and
                        // the gate on reaching an existing account is autoLinkVerifiedEmail.
                        true,
                        entry.get().displayName()));

        if (!(resolution instanceof FederatedIdentityService.Resolution.Resolved signedIn)) {
            return Optional.empty();
        }
        return Optional.ofNullable(transactions.execute(status ->
                issueFor(signedIn.userId(), companyId, rememberMe)));
    }

    private SignedIn issueFor(UUID userId, UUID companyId, boolean rememberMe) {
        AppUser user = users.findById(userId).orElse(null);
        if (user == null) {
            return null;
        }
        log.info("Signed in user {} via directory with active company {}", userId, companyId);
        return new SignedIn(userId, companyId,
                tokens.issue(userId, companyId, user.getPermissionVersion(), rememberMe));
    }
}
```

- [x] **Step 6: Write `LdapController`**

```java
package com.tcms.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /auth/ldap/{providerId}/login} — the fourth public surface of external sign-in, and
 * the only one that is not a browser redirect.
 *
 * <p>It answers {@link AuthController.LoginResponse}: the same body, the same session, the same
 * empty 401. A directory proves who somebody is; the credential it proves it with never becomes a
 * credential for this API, exactly as an {@code id_token} does not.
 *
 * <p>Added to {@code EndpointAccessTest}'s public set in the same commit — the enumerated matcher in
 * {@code SecurityConfiguration} already opens {@code /api/v1/auth/**}, so listing it there is the
 * deliberate act that keeps widening the unauthenticated surface a decision rather than a
 * consequence of choosing a path.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class LdapController {

    private final LdapLoginService ldap;
    private final CurrentUserService currentUser;

    public LdapController(LdapLoginService ldap, CurrentUserService currentUser) {
        this.ldap = ldap;
        this.currentUser = currentUser;
    }

    /** @param rememberMe Boolean rather than boolean: the contract defaults it, absent is not false */
    public record DirectoryLoginRequest(@NotBlank String email, @NotBlank String password,
                                        Boolean rememberMe) {
    }

    @PostMapping("/ldap/{providerId}/login")
    public ResponseEntity<AuthController.LoginResponse> login(
            @PathVariable UUID providerId,
            @Valid @RequestBody DirectoryLoginRequest request) {

        Optional<LdapLoginService.SignedIn> result = ldap.signIn(
                providerId, request.email(), request.password(),
                Boolean.TRUE.equals(request.rememberMe()));

        if (result.isEmpty()) {
            // No body, and no cause. AuthController.login's rule, on a surface a stranger reaches.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        LdapLoginService.SignedIn signedIn = result.get();
        return ResponseEntity.ok(new AuthController.LoginResponse(
                signedIn.session().token(),
                signedIn.session().expiresAt(),
                currentUser.describe(signedIn.userId(), signedIn.companyId())));
    }
}
```

- [x] **Step 7: Add the route to the public set**

In `EndpointAccessTest`, beside the three §217 added, add
`"POST /api/v1/auth/ldap/{providerId}/login"` in whatever form that file's public set takes.

- [x] **Step 8: Run the whole backend suite**

```bash
cd backend && ./gradlew build
```

Expected: PASS. `ArchitectureRulesTest` is the one to watch — if it fails, something LDAP-shaped
landed in `identity`, which trap 4 is about.

- [x] **Step 9: Commit**

```bash
git add backend/src/main/java/com/tcms/auth/ backend/src/test/java/com/tcms/auth/ \
        backend/src/test/java/com/tcms/api/EndpointAccessTest.java
git commit -m "§NNN phase 6 — a directory can sign somebody in, and every way it cannot is one refusal"
```

---

## Task 9: The orientation cache and the verification queues

**Files:**
- Modify: `docs/agent-context/be-java.md`
- Modify: `docs/verification/backend-mutation-checks.md`

- [x] **Step 1: Add the seams to the cache**

Re-read `be-java.md` first (another lane may have written it), then add to its seam list:

```markdown
- **`auth/LdapDirectory`** — the only class that speaks LDAP. In `auth` and not `identity` because
  `identity` may not reach the network (ArchUnit). Prevents: filter injection (escaped `{0}`),
  binding as an empty password (an unauthenticated bind that most directories answer with success),
  picking a person by result ordering when a filter matches twice, following a referral to a server
  the response chose, and a subject that is platform-dependent mojibake (`objectGUID` is 16 binary
  bytes, little-endian in its first three fields).
- **`auth/BindThrottle`** — 5 failures per (provider, identifier), then refuse without binding.
  Prevents: locking out a customer's Active Directory accounts from the internet. `RateLimitFilter`
  cannot do this — it counts API tokens only.
- **`auth/ProviderResolution`** — V53's pre-authentication lookup, extracted from `SsoLoginService`
  because both sign-in flows ask it. Answers a company and `enabled`, never the configuration.
- **`ck_identity_provider_shape` (V57)** — the conditional replacement for `client_id NOT NULL`,
  which had to be dropped so an LDAP row could exist. Prevents: an OIDC provider with no client id,
  which fails at the token exchange days later rather than at the insert.
```

- [x] **Step 2: Add the mutation rows**

Append to `docs/verification/backend-mutation-checks.md`, in the file's existing row format, ids
continuing from the highest present:

| What to drive | Expected |
|---|---|
| Register an LDAP provider through `POST /identity-providers` against the compose stack, enable it, sign in with `POST /auth/ldap/{id}/login` | 200 with a session; `user_identity` gains one row whose `subject` is the directory's own identifier and not a DN |
| Rename the person's `mail` in the directory and sign in again with the new address | 200, **the same** `user_identity` row, no second `app_user` |
| Six wrong passwords in a row | The sixth is refused with no LDAP connection opened — check the directory's own log, not TCMS's |
| Point a provider at `ldap://` without StartTLS through the API | 400 with a `issuerUri` field error, and no row written |

- [x] **Step 3: Commit**

```bash
git add docs/agent-context/be-java.md docs/verification/backend-mutation-checks.md
git commit -m "§NNN phase 7 — the four seams and the four mutations nobody has driven"
```

---

## Task 10: The admin dialog stops being an OIDC form

**Files:**
- Modify: `frontend/src/features/admin/ProviderFormDialog.tsx`
- Modify: `frontend/src/features/admin/ProviderFormDialog.test.tsx`

- [ ] **Step 1: Write the failing tests**

```tsx
  it('offers both kinds when registering and neither when editing', async () => {
    renderDialog({ provider: null });

    expect(screen.getByTestId('Admin.Sso.Kind')).toBeEnabled();
    await userEvent.click(screen.getByTestId('Admin.Sso.Kind'));
    expect(screen.getByRole('option', { name: /directory/i })).toBeInTheDocument();
  });

  it('asks for a search base and a filter once LDAP is chosen, and for no client id', async () => {
    renderDialog({ provider: null });
    await pickKind('LDAP');

    expect(screen.getByTestId('Admin.Sso.UserSearchBase')).toBeInTheDocument();
    expect(screen.queryByTestId('Admin.Sso.ClientId')).not.toBeInTheDocument();
  });

  it('refuses a plain ldap:// URL before it reaches the server', async () => {
    renderDialog({ provider: null });
    await pickKind('LDAP');
    await fillDirectoryForm({ issuerUri: 'ldap://dc.acme.test:389', startTls: false });
    await userEvent.click(screen.getByTestId('Admin.Sso.Save'));

    expect(await screen.findByText(/directory passwords on the wire/i)).toBeInTheDocument();
    expect(saveSpy).not.toHaveBeenCalled();
  });

  it('accepts ldap:// once StartTLS is on', async () => {
    renderDialog({ provider: null });
    await pickKind('LDAP');
    await fillDirectoryForm({ issuerUri: 'ldap://dc.acme.test:389', startTls: true });
    await userEvent.click(screen.getByTestId('Admin.Sso.Save'));

    expect(saveSpy).toHaveBeenCalledWith(
      expect.objectContaining({ input: expect.objectContaining({ kind: 'LDAP', startTls: true }) }),
    );
  });

  it('sends a null bind password when the field is left blank on an edit', async () => {
    renderDialog({ provider: ldapProvider({ bindPasswordSet: true }) });
    await userEvent.click(screen.getByTestId('Admin.Sso.Save'));

    expect(saveSpy).toHaveBeenCalledWith(
      expect.objectContaining({ input: expect.objectContaining({ bindPassword: null }) }),
    );
  });
```

- [ ] **Step 2: Run them and watch them fail**

```bash
cd frontend && npm run test:run -- ProviderFormDialog
```

Expected: FAIL — the Kind select is disabled and there are no directory fields.

- [ ] **Step 3: Make `kind` state, and switch the form on it**

```tsx
  const [kind, setKind] = useState<IdentityProviderKind>(provider?.kind ?? 'OIDC');
  const directory = kind === 'LDAP';

  const [bindDn, setBindDn] = useState(provider?.bindDn ?? '');
  /* ALWAYS EMPTY on mount, for `clientSecret`'s reason — the value has never left the server. */
  const [bindPassword, setBindPassword] = useState('');
  const [startTls, setStartTls] = useState(provider?.startTls ?? false);
  const [userSearchBase, setUserSearchBase] = useState(provider?.userSearchBase ?? '');
  const [userSearchFilter, setUserSearchFilter] = useState(
    provider?.userSearchFilter ?? '(|(userPrincipalName={0})(mail={0}))',
  );
  const [userIdAttribute, setUserIdAttribute] = useState(
    provider?.userIdAttribute ?? 'objectGUID',
  );
  const [emailAttribute, setEmailAttribute] = useState(provider?.emailAttribute ?? 'mail');
  const [displayNameAttribute, setDisplayNameAttribute] = useState(
    provider?.displayNameAttribute ?? 'displayName',
  );
```

The three defaults are Active Directory's, because AD is what a company registering a directory
almost always has — and a form pre-filled with a working AD configuration is the difference between
an administrator who succeeds and one who reads a specification. They are editable, which is what
makes OpenLDAP (`entryUUID`, `(mail={0})`) a two-field change rather than an unsupported case.

- [ ] **Step 4: The kind-aware local refusal**

```tsx
  const localRefusal = (): Partial<Record<FieldName, string>> => {
    const refusals: Partial<Record<FieldName, string>> = {};
    if (!editing && !KEY_PATTERN.test(key.trim())) {
      refusals.key = 'Lowercase letters, digits and hyphens, starting and ending with one of those.';
    }
    const url = issuerUri.trim();
    if (directory) {
      /*
        Client-side as well as server-side, and the duplication is on purpose — the same reason the
        https check is duplicated one branch down. The server refuses this for a reason a 400 field
        error cannot carry, so it is checked here to say it: an unencrypted bind puts the person's
        own directory password on the wire, which is the credential to everything else the customer
        owns. There is deliberately no flag that permits it.
      */
      if (!/^ldaps:\/\/./.test(url) && !(/^ldap:\/\/./.test(url) && startTls)) {
        refusals.issuerUri =
          'A directory URL must be ldaps://, or ldap:// with StartTLS — a plain bind puts your people’s directory passwords on the wire.';
      }
      if (!userSearchFilter.includes('{0}')) {
        refusals.userSearchFilter =
          'The filter must contain {0} — that is where the typed address is substituted.';
      }
      if (userSearchBase.trim() === '') {
        refusals.userSearchBase = 'A search base is required — “ou=people,dc=acme,dc=test”.';
      }
      if (userIdAttribute.trim() === '') {
        refusals.userIdAttribute =
          'Name the attribute holding the directory’s own identifier — “objectGUID” on Active Directory.';
      }
    } else if (!url.startsWith('https://')) {
      refusals.issuerUri =
        'The issuer must be https — the signing keys every sign-in is checked against are fetched from this URL.';
    }
    return refusals;
  };
```

- [ ] **Step 5: One payload, both kinds**

```tsx
    const trimmedSecret = clientSecret.trim();
    const trimmedBindPassword = bindPassword.trim();
    const input: IdentityProviderInput = {
      key: editing ? provider.key : key.trim(),
      kind,
      displayName: displayName.trim(),
      issuerUri: issuerUri.trim(),
      // The server refuses a client id on a directory provider — ck_identity_provider_shape
      // requires it to be null — and refuses its absence on an issuer.
      clientId: directory ? null : clientId.trim(),
      clientSecret: directory || trimmedSecret === '' ? null : trimmedSecret,
      /*
        `null` — never `''` — for the client secret's reason: the contract reads null as "keep what
        is stored" and an empty string as a rotation to an empty credential.
      */
      bindDn: directory && bindDn.trim() !== '' ? bindDn.trim() : null,
      bindPassword: directory && trimmedBindPassword !== '' ? trimmedBindPassword : null,
      startTls: directory ? startTls : false,
      userSearchBase: directory ? userSearchBase.trim() : null,
      userSearchFilter: directory ? userSearchFilter.trim() : null,
      userIdAttribute: directory ? userIdAttribute.trim() : null,
      emailAttribute: directory && emailAttribute.trim() !== '' ? emailAttribute.trim() : null,
      displayNameAttribute:
        directory && displayNameAttribute.trim() !== '' ? displayNameAttribute.trim() : null,
      jitProvisioning,
      autoLinkVerifiedEmail,
      groupClaim: directory || groupClaim.trim() === '' ? null : groupClaim.trim(),
      enabled,
    };
```

- [ ] **Step 6: The controls**

The `Kind` select becomes `disabled={editing}` — **not** unconditionally disabled — with the header
comment updated to say that a kind is set once, which is why it is disabled on an edit and live on a
registration. Add the second option:

```tsx
              <MenuItem value="OIDC">OpenID Connect</MenuItem>
              <MenuItem value="LDAP">Directory (LDAP / Active Directory)</MenuItem>
```

The `Issuer URL` field's label and helper text switch on `directory`
(`'Directory URL'` / `'Like ldaps://dc.acme.test:636. A plain ldap:// URL is only accepted with
StartTLS, because a bind sends your people’s own passwords.'`). `Client id`, `Client secret` and
`Group claim` render only when `!directory`; the seven directory fields render only when `directory`,
each with `data-testid` in the file's `${TestId}.<Name>` convention, and the bind-password field
carries the same "leave blank to keep" sentence, keyed on `provider?.bindPasswordSet`.

Add `FieldName` members and `isFieldName` arms for the six new server-keyed fields: `bindDn`,
`bindPassword`, `userSearchBase`, `userSearchFilter`, `userIdAttribute`, `emailAttribute`.

- [ ] **Step 7: Run the tests and the gates**

```bash
cd frontend && npm run test:run -- ProviderFormDialog && npm run build && npm run lint
```

Expected: PASS, 0 warnings.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/features/admin/
git commit -m "§NNN phase 8 — the Kind select stops being a one-option control"
```

---

## Task 11: The sign-in screen grows a directory mode

**Files:**
- Modify: `frontend/src/features/auth/LoginPage.tsx`
- Modify: `frontend/src/api/queries/auth.ts`
- Modify: `frontend/src/features/auth/LoginPage.test.tsx`
- Modify: `frontend/src/test/fixtures.ts`

- [ ] **Step 1: Write the failing tests**

```tsx
  it('renders a directory provider as a mode switch, not a navigation', async () => {
    stubDiscovery([{ id: 'p1', displayName: 'Acme Directory', kind: 'LDAP' }]);
    renderLogin();
    await typeEmail('dana@acme.test');

    await userEvent.click(await screen.findByTestId('Login.Provider'));

    expect(assignSpy).not.toHaveBeenCalled();
    expect(screen.getByText(/signing in with your Acme Directory password/i)).toBeInTheDocument();
  });

  it('posts the directory password to the provider’s own endpoint', async () => {
    stubDiscovery([{ id: 'p1', displayName: 'Acme Directory', kind: 'LDAP' }]);
    renderLogin();
    await typeEmail('dana@acme.test');
    await userEvent.click(await screen.findByTestId('Login.Provider'));
    await userEvent.type(screen.getByTestId('Login.PasswordInput'), 'directory-password');
    await userEvent.click(screen.getByTestId('Login.SubmitButton'));

    expect(directoryLogin).toHaveBeenCalledWith({
      providerId: 'p1',
      email: 'dana@acme.test',
      password: 'directory-password',
      rememberMe: false,
    });
  });

  it('an OIDC provider still navigates', async () => {
    stubDiscovery([{ id: 'p2', displayName: 'Acme SSO', kind: 'OIDC' }]);
    renderLogin();
    await typeEmail('dana@acme.test');
    await userEvent.click(await screen.findByTestId('Login.Provider'));

    expect(assignSpy).toHaveBeenCalledWith(expect.stringContaining('/auth/sso/p2/authorize'));
  });

  it('a refused directory sign-in says one sentence and names no cause', async () => {
    directoryLogin.mockRejectedValue(new ApiError(401, {}));
    stubDiscovery([{ id: 'p1', displayName: 'Acme Directory', kind: 'LDAP' }]);
    renderLogin();
    await typeEmail('dana@acme.test');
    await userEvent.click(await screen.findByTestId('Login.Provider'));
    await userEvent.type(screen.getByTestId('Login.PasswordInput'), 'wrong');
    await userEvent.click(screen.getByTestId('Login.SubmitButton'));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      /that email address and password did not match/i,
    );
  });
```

- [ ] **Step 2: Run them and watch them fail**

```bash
cd frontend && npm run test:run -- LoginPage
```

Expected: FAIL — every provider is currently a navigation.

- [ ] **Step 3: The mutation**

In `api/queries/auth.ts`, beside the existing sign-in seam:

```ts
/**
 * Signing in against a company's own directory.
 *
 * <p>A `POST` and not a navigation, which is the whole difference between the two kinds of
 * provider: an LDAP route has no third party to send a browser to, so the password is collected
 * here. The answer is `AuthResult` — the same body `POST /auth/login` returns — so the session is
 * adopted by `AuthProvider.login`'s own path and there is still exactly one writer of `tcms.token`.
 */
export function useDirectoryLogin(): UseMutationResult<
  AuthResult,
  unknown,
  { providerId: string; email: string; password: string; rememberMe: boolean }
> {
  return useMutation({
    mutationFn: ({ providerId, ...credentials }) =>
      api.post<AuthResult>(
        `/auth/ldap/${encodeURIComponent(providerId)}/login`,
        credentials,
      ),
  });
}
```

- [ ] **Step 4: The mode switch**

`LoginPage` grows one piece of state — `const [directory, setDirectory] = useState<SsoProviderChoice | null>(null)` —
and three consequences:

- the provider button's `onClick` branches: `provider.kind === 'LDAP' ? setDirectory(provider) : window.location.assign(...)`;
- when `directory` is set, the form's heading gains *"Signing in with your {directory.displayName} password"*,
  the password field's label becomes *"Directory password"*, and a `Use my TCMS password instead`
  text button clears it back to `null`;
- submit dispatches to `useDirectoryLogin` with `directory.id` instead of the local sign-in mutation,
  and hands the result to the same `login(...)` seam.

**One pair of inputs, not two.** The email is already typed and is what discovery routed on (D4), so
the directory mode reuses both controls rather than adding a second password field to a screen where
the two would differ only by a label nobody reads.

The refusal copy is the existing one, unchanged and deliberately: a directory refusal and a local
refusal must read identically, or the pair of them tells a stranger which accounts are federated.

- [ ] **Step 5: Run the tests and the gates**

```bash
cd frontend && npm run test:run && npm run build && npm run lint
```

Expected: PASS, 0 warnings. `renderFailures.test.tsx` sweeps for hand-built query clients — use
`createTestQueryClient`.

- [ ] **Step 6: Add the browser-queue rows**

Append to `docs/verification/frontend-browser-checks.md`, in the lane whose state has a provisioned
company with an enabled provider, with stable ids continuing the file's sequence:

| Row | Drive | Expect |
|---|---|---|
| Directory button | Type an address at a claimed domain whose company has an enabled LDAP provider | A button; clicking it switches the form rather than navigating |
| Directory refusal | Submit a wrong password in directory mode | One sentence, identical to a wrong TCMS password |
| Back out | Click "Use my TCMS password instead" | Local sign-in works from the same screen with no reload |
| Mixed providers | A company with both an OIDC and an LDAP provider enabled | Two buttons; one navigates, one switches |

**This row set needs a fixture no lane creates today: a company with an enabled LDAP provider and a
reachable directory.** The compose stack has Keycloak and no LDAP server. State it in the queue rather
than skipping it silently — the options are an `osixia/openldap` service in `compose.yaml` beside
Keycloak, or driving these four rows against a customer directory in the lab, and it is the browser
pass's charter to choose.

- [ ] **Step 7: Commit**

```bash
git add frontend/src/ docs/verification/frontend-browser-checks.md
git commit -m "§NNN phase 9 — one screen, two ways in, and the same sentence when neither works"
```

---

## Task 12: The documents

**Files:**
- Modify: `docs/specs/2026-07-30-tcms-erd.md`
- Modify: `docs/plans/2026-07-30-tcms-high-level-plan.md` (§6r)
- Modify: `docs/plans/2026-08-15-m4-external-identity-oidc.md`
- Modify: `docs/agent-context/fe-react.md`
- Create: `docs/handoff/NNN-<slug>.md`
- Modify: `docs/HANDOFF.md` (one index row)
- Create/modify: `deploy/README.md` — the truststore note

- [ ] **Step 1: The ERD**

In the `identity_provider` entry, record that the row now serves two kinds, that `client_id` is
conditional on `kind` behind `ck_identity_provider_shape`, and that `user_identity` is **unchanged** —
`(provider, subject)` already carries a directory identifier as well as it carries an OIDC `sub`,
which is what made the identity half of this free.

- [ ] **Step 2: §6r**

Add under the identity-provider section:

```markdown
**LDAP / Active Directory, shipped §NNN.** The second kind, and it is a *credential* flow: there is
no third party to redirect to, so TCMS collects the directory password and binds with it. Three
things follow that are not true of the OIDC lane — the transport must be encrypted with no escape
hatch (`ldaps://`, or `ldap://` with StartTLS), failed attempts are throttled per identifier because
each one increments the *customer's* lockout counter, and the search filter is escaped because an
administrator-supplied filter takes an anonymous caller's input. Group search, nested-group
resolution and the deprovisioning poll are deferred with reasons in
`docs/plans/2026-08-15-m4-external-identity-ldap-ad.md`.
```

- [ ] **Step 3: The sketch becomes a pointer**

Replace the body of "Plan 3 (sketch) — LDAP / Active Directory" with a line pointing at this plan,
keeping the two deferred bullets (group search and the deprovisioning poll) struck through as
*deferred rather than dropped*, each with the reason from this plan's scope check.

- [ ] **Step 4: The truststore note**

In `deploy/README.md`, under the identity-provider configuration:

```markdown
**A directory with an internal CA.** The JVM's default trust store validates the certificate an
`ldaps://` provider presents, so an internally-issued one must be trusted at the deployment: mount a
truststore and point the container at it. It is deliberately **not** a per-provider field — that
would be a second trust store, editable through a web form, for a host the same form names, and its
only user is the case where trusting the CA once is the correct fix.
```

- [ ] **Step 5: The handoff section and the index row**

`docs/handoff/NNN-<slug>.md`, in the house format: what landed per phase, the measured test counts
before and after on the Linux lane, and **the things worth carrying** — the empty-password bind, the
little-endian `objectGUID`, `DROP FUNCTION` before `CREATE`, and the `NOT NULL` that had to be
replaced rather than dropped. Then one row in `docs/HANDOFF.md`'s index table. **Never append to
`HANDOFF.md` itself.**

- [ ] **Step 6: The full gates, on the Linux observer**

```bash
~/bin/tcms-remote backend ./gradlew build
~/bin/tcms-remote frontend npm run test:run
~/bin/tcms-remote packages npm run build
```

Expected: all three green. Run the backend one locally too — `ci.yml` is `-x test`, so nothing
downstream runs a backend test until 01:17 UTC.

- [ ] **Step 7: Commit and push**

```bash
git add docs/
git commit -m "§NNN phase 10 — the ERD, §6r, the sketch that is now a pointer, and the handoff"
git push
```

---

## Self-review

**Spec coverage.** The sketch's eight bullets, item by item: *`ldap://` refused unless
`allowInsecure`* → **D2 and Task 1**, decided the other way and written down as a reversal rather
than a silent drop. *Key on `objectGUID`/`entryUUID`, never the DN* → D3, Task 6 (`subjectOf`,
`guidOf`), asserted in `LdapDirectoryTest.aCorrectBindResolvesAnEntry` and in the endpoint test's
`theSubjectIsTheKey`. *Slots into `user_identity` unchanged* → confirmed: V57 touches
`identity_provider` only. *Groups from a configurable search, nested groups bounded* → **deferred**,
scope check, with the reason. *Referrals and paging* → D7 (referrals ignored) and Task 6
(`countLimit(2)`, which is the paging question in the only form this plan's search can meet it).
*Deprovisioning poll* → **deferred**, scope check. *Needs `spring-boot-starter-ldap`* → Task 6 step 1.
The user-visible gap the request named — *"the dialog's Kind select is hardcoded to OIDC and
disabled"* — is Task 10.

**Gaps deliberately left.** Four browser rows in Task 11 need an LDAP server the compose stack does
not have, and the plan says so at the rows with the two options rather than implying they will be
driven. Paging beyond "more than one match refuses" is not implemented: the only search this plan
performs expects exactly one entry, so an AD size cap cannot truncate a result this code would have
used — that changes the moment group search arrives, which is plan 2's problem and is stated there.

**Placeholder scan.** No TBD/TODO. Three steps describe an implementation without a complete listing
and each names the exact shape and the neighbouring file to copy it from: Task 6 step 6's
`ContextMapper` (flagged inline, with what it must produce and why `countLimit(2)` is load-bearing),
Task 8 step 3's endpoint-test helpers (named individually, with `IdentityProviderEndpointTest` as the
shape), and Task 11 step 4's mode switch (three consequences enumerated, with the one-pair-of-inputs
decision stated). Every step carrying a *decision* carries its code.

**Type consistency.** `LdapDirectory.Target` has nine components and is constructed once, in
`LdapLoginService.complete`, in the same order it is declared. `LdapDirectory.Entry` is
`(dn, subject, email, displayName)` at both its construction sites and its three readers.
`LdapProperties` is `(connectTimeout, readTimeout, maxBindAttempts, bindLockout)` in the record, in
both tests that construct it, and in `application.yml`'s kebab-case. `IdentityProvider.applyEdit`
takes eighteen parameters in Task 4 and is called with eighteen in Task 5, in that order.
`ProviderChoice` gains `kind` in Task 8 step 2 and is read as `provider.kind` in Task 11.
`BindThrottle`'s three methods are `mayBind` / `recordFailure` / `recordSuccess` in the test, the
implementation and the caller.

**One thing this plan cannot check for you.** `V57` is pre-assigned against `master` as of writing.
Three worktrees exist; `out-of-order` is unset, and Testcontainers structurally cannot see a
collision. Check before writing the file.

---

**Plan complete and saved to `docs/plans/2026-08-15-m4-external-identity-ldap-ad.md`. Ready to
implement it task-by-task with checkpoints for review?**
