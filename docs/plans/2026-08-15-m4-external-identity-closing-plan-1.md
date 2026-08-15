# M4 External Identity — Closing Plan 1 Implementation Plan

**Date:** 2026-08-15 · **Written by:** `ep-writing-plans`

> **For agentic workers:** REQUIRED SUB-SKILL: Use `ep-executing-plans` to implement this plan
> task-by-task, or `ep-delegate-to-subagents` to run its tasks through subagents. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** A person can complete an external sign-in **and get into the product** — the SPA grows the
sign-in buttons, the `/sso/callback` route and the provider admin screen — and the three observers
§217 left unwritten exist: an integration test that crosses the wire, a test over the provider CRUD,
and a rule that fails the build when a credential appears in a response schema.

**Architecture:** Four lanes over one already-shipped backend. **No new endpoint, no migration and
no `openapi.yaml` change** — §217 shipped the whole contract and nothing consumes it. Lane A adds a
contract rule in `packages/`, lane B adds backend observers, lane C is the `fe-react` lane that
closes the product gap, lane D is two decisions and two deferrals.

**Tech Stack:** Vite 8 · React 19 · TypeScript strict · MUI v7 · TanStack Query · React Router 7 ·
Vitest + RTL · Java 21 · Spring Boot 3.5.6 · Testcontainers Postgres 17 · `com.sun.net.httpserver`
(JDK, no new dependency) · Nimbus JOSE · `yaml` (already a `packages/client` dependency).

---

## Scope check — four lanes, three of them independently shippable

`ep-writing-plans` requires each plan to produce working, testable software on its own. These four
lanes each do, and they are in one file because three of them are small and because they share one
subject: the things §217 recorded as *not done*.

| Lane | Covers | Ships | Depends on |
|---|---|---|---|
| **A** — the credential rule | `§217 FD-15` / `§218 NC-5`, the same missing rule recorded twice | A red build when a credential-shaped string reaches a response schema | nothing |
| **B** — the backend observers | `IdentityProviderServiceTest`; an integration test against a stub issuer | `FD-10`…`FD-12`, `FD-14` stop being "expected green because nothing drives them" | Task 1's decision only for the *shape* of the fixture |
| **C** — the sign-in lane | Provider buttons · `/sso/callback` · the provider admin screen | **A person can sign in through their own issuer and land in the app** | nothing (the contract is already shipped) |
| **D** — decisions & deferrals | The plain-HTTP escape hatch; RP-initiated logout; `/{slug}/login`; plans 2 and 3 | Written decisions, not code | — |

**Lane C is the one that matters** and it is the largest single gap in plan 1: today the browser
lands on `/sso/callback#token=…` and the SPA renders not-found, so *a completed external sign-in
does not let anybody in*. If only one lane is executed, execute C.

**Run the lanes in the order A → B → C**, or C first if the sign-in gap is the priority. They touch
disjoint files, but the "one subagent at a time" rule in CLAUDE.md still binds: this is a sequential
plan, not four parallel ones.

---

## What §217 shipped, and the exact contract lane C implements

This is not a design question. The backend already decided it, and `§217 SO-3` recorded the bytes.

| Fact | Where it is fixed |
|---|---|
| Landing path | `tcms.oidc.spa-callback-path`, default **`/sso/callback`** (`application.yml`) |
| Landing origin | `tcms.invitation.base-url` — the **SPA's** origin, not the API's (`SsoLoginService.spaOrigin`) |
| Success fragment | `#token=<urlencoded>&expiresAt=<urlencoded ISO-8601>` (`SsoLoginService.landingUrl`) |
| Failure fragment | `#error=sso_failed` — **one marker, every cause** (`SsoLoginService.failureUrl`) |
| Discovery | `POST /auth/providers` `{ email }` → `{ providers: [{ id, displayName }] }`, **always 200** |
| Begin | `GET /api/v1/auth/sso/{providerId}/authorize` — a **browser navigation**, never a `fetch` |
| Provider CRUD | `GET`/`POST /identity-providers`, `PUT /identity-providers/{providerId}`, `GET`/`PUT /identity-providers/email-domains`, all gated on `company:idp` |

Everything above is already in `docs/specs/openapi.yaml` and in both `schema.d.ts` copies. **This
plan regenerates nothing and holds no contract lock.**

---

## Lane ownership (concurrency)

- **No `openapi.yaml` change, no Flyway version, no `schema.d.ts` regeneration.** Whatever else is
  in flight, this plan does not contend for the contract or the migration sequence. Say so when
  starting, because the reflex from §217 is to claim both.
- **One writer for `docs/agent-context/*.md`.** Lane C writes `fe-react.md`; lanes A and B write
  `be-java.md`. Re-read before editing; update in the lane's own commit.
- **Fixture UUID prefix `f2`** (`f2000000-…`) for lane B's integration fixtures. Hand-managed with
  no check behind it, collided five times (§19…§62). State it in the fixture's own header.
- **Handoff sections are allocated in landing order.** `§NNN` below is a placeholder; §218 is the
  highest at the time of writing, so expect §219 upward, one section per lane.

---

## Design decisions already locked — do not re-litigate

| Decision | Source | Consequence here |
|---|---|---|
| One refusal, no reason, for every SSO failure | §6r, `AuthenticationService` | `#error=sso_failed` renders **one sentence**. Never "provider disabled", never "no account" |
| Discovery is not an oracle | §6r, §6s | An empty provider list renders **identically** to a list that was never asked for. No "no SSO for that domain" |
| TCMS issues its own session; the IdP token never reaches the SPA | §6r | The fragment carries a **TCMS** session. Nothing in the SPA knows an issuer exists after the redirect |
| A provider is always created disabled | `IdentityProvider.register` | The admin screen's Save cannot enable; enabling is a second act on the edit form |
| There is no delete | `openapi.yaml`, `/identity-providers/{providerId}` | The admin screen has no Delete. `enabled: false` is the off switch; `user_identity` rows reference the provider |
| The secret leaves by one door and comes back never | §6r, `IdentityProviderView` | The form shows `clientSecretSet` and says "leave blank to keep". There is no reveal, no copy button |
| `/login` and `/accept-invitation` stay public and un-prefixed | M6d plan | `/sso/callback` joins them as a **third public route**, un-prefixed for the same reason: nobody in the fragment has a company yet |

---

## Task 1 is a decision, not code — read this before starting lane B

**Can a provider point at a plain-HTTP issuer, ever?** §217 phase 7 found the collision and
deliberately did not resolve it: `IdentityProviderService.validate` refuses any `issuerUri` that is
not `https://`, and the compose Keycloak serves `http://keycloak.localtest.me:8081`. So **the
reference issuer cannot be registered through the product**, and the documented workaround is an
`INSERT` run as the container superuser (`deploy/keycloak/README.md`).

The gate is right: the discovery document — and therefore the `jwks_uri` every signature is checked
against — is fetched from that URL, so over plain HTTP anyone on the path nominates the keys that
decide who signs in. That is not a style rule and it is not negotiable in a deployment.

Task 1 records the decision. **The recommendation is to keep the gate and keep the SQL recipe**, and
lane B is written on that basis: its integration test inserts the provider through the *repository*,
never through the service, which is the same fact arriving from the test side. If the decision goes
the other way — a `tcms.oidc.allow-insecure-issuer` property, defaulting false, refused entirely
when `spring.profiles.active` has no dev profile — Task 1 says so and lane B's fixture changes shape
but not size.

---

## The seven traps

Read this section before writing any code. Five of the seven are silent when wrong.

### 1. The fragment is gone before a naive effect reads it

`SsoCallbackPage` must wipe the fragment with `history.replaceState` — a live session in the address
bar survives a copy-paste, a screen share and the browser's own history. But React 19 runs effects
**twice** under StrictMode in development, so an effect that reads `window.location.hash` *and* wipes
it sees an empty hash on the second run and concludes the sign-in failed.

**Capture the hash during the first render** (a `useRef` initialiser), wipe it in the effect, and
guard the adoption with a second ref so a double-invoked effect adopts once. This is the same class
of defect as `AuthContext`'s `ignore` flag and is a real dev-only-looking bug that also fires in any
test that renders the route twice.

### 2. There is no seam to hand a token to, and duplicating `login()` is the wrong fix

`AuthProvider.login` is the **only** writer of `tcms.token`, and it also writes the user cache, sets
`status`, and publishes on the cross-tab channel. A callback page that calls `safeStorage.set`
directly leaves `user` null, `status` stale and sibling tabs uncorrected — a signed-in app rendering
a signed-out shell.

Add **one** new seam, `adoptSession(token)`, beside `login`, and let it do the same four things. Do
not export `TOKEN_KEY`.

### 3. The fragment carries `expiresAt` and the SPA must ignore it

It is there because `AuthResult` carries it and the two paths mint the same session. **Nothing in
this SPA has ever stored a session expiry** — `login` stores the token and the user, and expiry is
the server's to enforce (8h/30d, plus the `pv` epoch). Storing it here creates a second, client-side
notion of when a session ends that nothing enforces and that drifts from the server's on any clock
skew. Parse it, ignore it, and say why in the file.

### 4. The user is not in the fragment — `/me` is the only source

`POST /auth/login` answers a user; the callback answers a token and nothing else. So `adoptSession`
**must** round-trip `/me` before it can render a name, and a failed `/me` must clear the token rather
than leave the app holding a credential it could not confirm. `AuthProvider` already documents this
as "restoring a session is a server question" — reuse the argument, do not re-derive it.

### 5. Discovery must be silent about its own emptiness

`POST /auth/providers` is deliberately not an oracle (§6r), and a screen that renders *"No single
sign-on for that domain"* hands back exactly the fact the server spent its whole design withholding.
The buttons appear when the list is non-empty and **nothing at all appears otherwise** — no message,
no empty state, no spinner that resolves into a shrug.

### 6. `authorize` is a navigation, not a fetch

`GET /api/v1/auth/sso/{providerId}/authorize` answers `302` to a **third-party origin**. A `fetch`
would follow it, hit CORS at the issuer, and fail with something unreadable; and the point is to put
the *browser* at the issuer's login page. Use `window.location.assign(...)` against the API origin,
not the SPA's, and not `api.get`.

### 7. The credential rule must read response-reachable schemas only, and strings only

`IdentityProviderInput.clientSecret` and `NotificationChannelInput.secret` are legitimate **inbound**
credentials; `IdentityProvider.clientSecretSet` and `InvitationPreview.passwordRequired` are booleans
that cannot carry one. A rule that scans every schema, or every property regardless of type, reddens
on all four and its by-decision map fills up with the wrong entries — at which point the map is
noise and the next real finding lands in it without anyone noticing.

Walk **from responses**, follow `$ref` transitively, and flag **`type: string`** properties only.
Three real exceptions remain, and each is a decision with a reason (Task 2).

---

## File structure

**Lane A — `packages/client/`**
- Create: `src/test/responseCredentials.test.ts` — the rule
- Modify: `src/test/contractBodies.ts` — one exported accessor, **outside** the marked region

**Lane B — `backend/src/test/java/com/tcms/`**
- Create: `identity/IdentityProviderEndpointTest.java`
- Create: `auth/SsoFlowIntegrationTest.java`
- Create: `auth/StubIssuer.java` — an in-JVM OIDC issuer (discovery + JWKS + token endpoint)

**Lane C — `frontend/src/`**
- Modify: `api/queries/keys.ts` · `api/queries/identity.ts` · `api/queries/admin.ts` · `api/types.ts`
- Modify: `auth/AuthContext.tsx` · `auth/authContextValue.ts` — the `adoptSession` seam
- Create: `features/auth/SsoCallbackPage.tsx` (+ test)
- Modify: `features/auth/LoginPage.tsx` (+ test) — the provider buttons and the SSO failure sentence
- Create: `features/admin/SsoProvidersPage.tsx` · `features/admin/ProviderFormDialog.tsx` (+ tests)
- Modify: `app/router.tsx` · `app/AppShell.tsx` · `test/fixtures.ts`

**Docs (every lane)**
- Modify: `docs/verification/frontend-browser-checks.md` · `docs/verification/backend-mutation-checks.md`
- Modify: `docs/agent-context/fe-react.md` · `docs/agent-context/be-java.md`
- Modify: `docs/HANDOFF.md` + create `docs/handoff/NNN-<slug>.md` per lane

---

# Lane D (part 1) — the decision lane B depends on

## Task 1: Record the plain-HTTP issuer decision

**Files:**
- Modify: `docs/plans/2026-08-15-m4-external-identity-oidc.md` (append to the self-review's
  "Gaps deliberately left" paragraph)
- Modify: `deploy/keycloak/README.md`

- [ ] **Step 1: Write the decision into the plan file**

Append this to the plan's self-review section, verbatim:

```markdown
**The plain-HTTP issuer decision, taken 2026-08-15 (§217 phase 7 raised it and deferred it).**
`IdentityProviderService.validate` keeps refusing any `issuerUri` that is not `https://`, and the
compose Keycloak keeps being registered by SQL. A development-only escape hatch was considered and
rejected: the gate protects the URL the `jwks_uri` is read from, so an instance that can be talked
into an http issuer is an instance where a machine on the path nominates the signing keys — and a
property that is only safe when a profile is set is one restart away from being set in production.
Plan 3's `allowInsecure` sketch is for an LDAP *bind*, which is a different exposure with a different
blast radius, and it is not a precedent for this. The cost is one documented `INSERT` for the one
issuer that is deliberately not production, and `deploy/keycloak/README.md` carries it.
```

- [ ] **Step 2: Cross-reference from the Keycloak README**

Add one line under the recipe: *"Why this is an `INSERT` rather than a form: the https gate is
deliberate and stays — see `docs/plans/2026-08-15-m4-external-identity-oidc.md`, self-review."*

- [ ] **Step 3: Commit**

```bash
git add docs/plans/2026-08-15-m4-external-identity-oidc.md deploy/keycloak/README.md
git commit -m "§NNN — the https gate stays, and the reason the reference issuer is inserted by hand"
```

---

# Lane A — the rule two slices recorded and neither built

`§217 FD-15` and `§218 NC-5` are the same finding: *nothing in this repository asserts that a
credential is absent from a response schema.* `ContractConventionTest.pairedSchemasAndRecordsNameTheSameMembers`
compares the two sides **to each other**, so adding `clientSecret` to both the schema and the view
satisfies it. This lane builds the missing observer.

It lives in `packages/` for the reason §215 gave: a question two documents on disk can answer needs
no database, and `ci.yml` runs the `packages` suite while it does not run the backend's.

## Task 2: The response-credential rule

**Files:**
- Modify: `packages/client/src/test/contractBodies.ts`
- Create: `packages/client/src/test/responseCredentials.test.ts`

- [ ] **Step 1: Export a spec accessor, outside the marked region**

`contractBodies.ts`'s marked region is byte-identical to `frontend/src/api/contractBodies.ts` and is
pinned by `contractPairing.test.ts`. **This goes below it**, beside `successStatusesOf`, on the same
rule: a question only one stack can ask does not go in the region — the SPA has no reason to walk
every response in the contract, and putting it in the region would force an identical edit there.

Append to the end of the file:

```ts
/**
 * The parsed contract itself.
 *
 * Outside the shared region for `successStatusesOf`'s reason, one step further along: the SPA's
 * copy exists to check one exchange at a time and has no use for the whole document. `responseCredentials.test.ts`
 * walks every operation, so it needs the document rather than one schema, and re-reading 550 kB of
 * YAML in a second module would parse the contract twice per test run.
 */
export function contractDocument(): Document {
  return spec()
}
```

- [ ] **Step 2: Write the failing rule**

Create `packages/client/src/test/responseCredentials.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { contractDocument } from './contractBodies'

/**
 * **A credential may not appear in a response schema.**
 *
 * `§217 FD-15` and `§218 NC-5` are the same missing rule, recorded by two slices two days apart:
 * the pairing rule compares the schema and its Java record *to each other*, so adding `clientSecret`
 * to both satisfies it, and nothing else looks. Three prose statements and a type-level obstacle
 * stood in for a check.
 *
 * ## What is walked, and what is deliberately not
 *
 * **Responses only.** `IdentityProviderInput.clientSecret` and `NotificationChannelInput.secret` are
 * credentials travelling the direction they are supposed to; a rule over every schema reddens on
 * both and its by-decision map fills with entries that teach the reader nothing.
 *
 * **Strings only.** `IdentityProvider.clientSecretSet` and `InvitationPreview.passwordRequired` are
 * booleans, and a boolean cannot carry a secret. That is a structural exemption rather than a listed
 * one, which is why neither appears below.
 *
 * **Bodies only.** The one credential this API knowingly puts in a `Location` header is the SSO
 * session fragment, and it is a fragment precisely so it never reaches a server (§6r). `§217 SO-4`
 * is the observation that covers it, from the only place it is observable — a browser.
 */

/** Substrings that make a property name credential-shaped. Case-insensitive. */
const CREDENTIAL = ['secret', 'password', 'passphrase', 'credential', 'privatekey', 'apikey', 'token']

/**
 * The exceptions, each a decision with its reason. **A new entry here is a decision about
 * disclosure**, not a way to make the suite green.
 */
const BY_DECISION: Record<string, string> = {
  'AuthResult.token':
    'The session itself. This is the response that exists to issue one; withholding it would leave ' +
    'the caller with nothing to authenticate with.',
  'IssuedApiToken.secret':
    'The plaintext, shown once and recoverable never (§118). Its own type rather than a nullable ' +
    'field on ApiToken, so no read path is one forgotten null away from serving it.',
  'ApiToken.tokenPrefix':
    'The first characters only, so an administrator can tell two credentials apart in a list. ' +
    'Nothing accepts it — it authenticates nothing.',
  'NotificationChannel.secretHint':
    'Four end-anchored characters of a webhook URL (§218). Enough to answer "is this the right ' +
    'webhook", short enough to be useless as one.',
}

interface Node {
  [key: string]: unknown
}

function isNode(value: unknown): value is Node {
  return typeof value === 'object' && value !== null
}

/** Resolves a local `$ref` and returns the schema's component name alongside it. */
function resolve(node: Node, doc: Node): { schema: Node; name?: string } {
  const ref = node.$ref
  if (typeof ref !== 'string') return { schema: node }
  const name = ref.replace('#/components/schemas/', '')
  const schemas = (doc.components as Node | undefined)?.schemas as Node | undefined
  const target = schemas?.[name]
  return isNode(target) ? { schema: target, name } : { schema: {} }
}

/**
 * Every `(owner, property)` pair reachable from a schema, following `$ref`, `allOf`/`oneOf`/`anyOf`
 * and array items. `seen` is keyed on the component name so a self-referential schema — a doc page
 * with children, a team with a parent — terminates.
 */
function propertiesReachableFrom(
  start: Node,
  doc: Node,
  owner: string,
  seen: Set<string>,
  found: Map<string, Node>,
): void {
  const { schema, name } = resolve(start, doc)
  const under = name ?? owner
  if (name) {
    if (seen.has(name)) return
    seen.add(name)
  }

  for (const branch of ['allOf', 'oneOf', 'anyOf'] as const) {
    const members = schema[branch]
    if (Array.isArray(members)) {
      for (const member of members) {
        if (isNode(member)) propertiesReachableFrom(member, doc, under, seen, found)
      }
    }
  }

  const items = schema.items
  if (isNode(items)) propertiesReachableFrom(items, doc, under, seen, found)

  const properties = schema.properties
  if (!isNode(properties)) return
  for (const [property, definition] of Object.entries(properties)) {
    if (!isNode(definition)) continue
    found.set(`${under}.${property}`, definition)
    propertiesReachableFrom(definition, doc, under, seen, found)
  }
}

/** Every property any 2xx response body can carry, keyed `Schema.property`. */
function responseProperties(): Map<string, Node> {
  const doc = contractDocument() as unknown as Node
  const found = new Map<string, Node>()
  const paths = doc.paths as Node

  for (const [path, item] of Object.entries(paths)) {
    if (!isNode(item)) continue
    for (const [method, operation] of Object.entries(item)) {
      if (!isNode(operation) || !isNode(operation.responses)) continue
      for (const [status, response] of Object.entries(operation.responses)) {
        if (!status.startsWith('2') || !isNode(response) || !isNode(response.content)) continue
        for (const media of Object.values(response.content)) {
          if (!isNode(media) || !isNode(media.schema)) continue
          propertiesReachableFrom(media.schema, doc, `${method.toUpperCase()} ${path}`, new Set(), found)
        }
      }
    }
  }
  return found
}

function looksLikeACredential(property: string): boolean {
  const lowered = property.toLowerCase()
  return CREDENTIAL.some((needle) => lowered.includes(needle))
}

describe('response schemas', () => {
  it('carry no credential-shaped string that is not a recorded decision', () => {
    const offenders: string[] = []

    for (const [key, definition] of responseProperties()) {
      const property = key.slice(key.lastIndexOf('.') + 1)
      if (!looksLikeACredential(property)) continue
      if (definition.type !== 'string') continue
      if (key in BY_DECISION) continue
      offenders.push(key)
    }

    expect(
      offenders,
      'A credential-shaped string reached a response schema. If serving it is deliberate, add it to ' +
        'BY_DECISION with the reason it is safe to disclose — a masked hint, a one-time secret, the ' +
        'session itself. If it is not deliberate, this is the disclosure §217 FD-15 and §218 NC-5 ' +
        'both predicted.',
    ).toEqual([])
  })

  it('does not carry a by-decision entry for something no response serves', () => {
    const served = new Set(responseProperties().keys())
    const stale = Object.keys(BY_DECISION).filter((key) => !served.has(key))

    expect(
      stale,
      'A recorded exception names a property no response carries any more. Delete it — an exception ' +
        'nobody can reach reads as a standing permission for a shape that no longer exists.',
    ).toEqual([])
  })
})
```

- [ ] **Step 3: Run it against the contract as it stands**

```bash
cd packages && npm run test:run --workspaces
```

Expected: **PASS**, 322 tests (320 + 2). If it fails naming something other than the four
by-decision entries, that finding is real and predates this plan — record it and stop.

- [ ] **Step 4: Drive it with the mutation the rule exists for**

This is the step that proves the rule is an observer rather than a comment. Apply `§217 FD-15` by
hand: in `docs/specs/openapi.yaml`, add to the `IdentityProvider` schema's properties:

```yaml
        clientSecret: { type: string }
```

```bash
cd packages && npm run test:run --workspaces
```

Expected: **FAIL**, naming `IdentityProvider.clientSecret`. Then revert the edit:

```bash
git checkout docs/specs/openapi.yaml
```

Re-run and expect PASS. **Do not commit the mutation.**

- [ ] **Step 5: Mark both queue rows run**

In `docs/verification/backend-mutation-checks.md`, change `§217 FD-15` and `§218 NC-5` from
*expected green* to **`✅ RUN — now RED`**, each with one line naming
`packages/client/src/test/responseCredentials.test.ts` as the observer that closed it. These are the
first two rows in either queue that a *test* closes rather than a person, and that is worth saying.

- [ ] **Step 6: Commit**

```bash
git add packages/client/src/test/responseCredentials.test.ts \
        packages/client/src/test/contractBodies.ts \
        docs/verification/backend-mutation-checks.md
git commit -m "§NNN — the credential nothing kept out of a response, and the two rows that predicted it"
git push
```

---

# Lane B — the two observers §217 recorded as absent

## Task 3: `IdentityProviderEndpointTest` — the CRUD is gated, validated, audited and undriven

`IdentityProviderService` boots a Spring context (it takes `AuditEventRepository` and opens
`CompanyContext`), so per CLAUDE.md this is the **`qa-api-java`** lane, not the context-free
`be-java` one. `ApiTokenEndpointTest` is the shape to copy.

**Files:**
- Create: `backend/src/test/java/com/tcms/identity/IdentityProviderEndpointTest.java`

- [ ] **Step 1: Write the failing tests**

```java
package com.tcms.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/**
 * The provider CRUD, driven. §217 shipped it gated, validated and audited, and nothing asked it a
 * question — which is why the slice's own handoff lists this class by name under "what is not done".
 *
 * <p>The secret assertions are the point. {@code company:idp}'s first consumer is also the first
 * surface in this product that accepts a recoverable credential — {@link ProviderSecrets} encrypts
 * where {@code ApiTokenSecrets} hashes, because a token exchange needs the plaintext back — so
 * "never on a read path" is a property a test must hold rather than a sentence a header states.
 */
class IdentityProviderEndpointTest extends IntegrationTestBase {

    @Test
    void registeringAlwaysProducesADisabledProvider() {
        var view = asCompanyAdmin(() -> service.register(anInput().enabled(true).build()));

        assertThat(view.enabled())
                .describedAs("register() forces enabled=false whatever the caller sends: an issuer "
                        + "typed wrong is a route that refuses everybody")
                .isFalse();
    }

    @Test
    void theClientSecretIsOnNoReadPath() {
        var registered = asCompanyAdmin(() -> service.register(anInput().clientSecret("s3cret").build()));

        var listed = asCompanyAdmin(() -> service.list());
        var reread = asCompanyAdmin(() -> service.update(registered.id(), anInput().build()));

        assertThat(listed.toString()).doesNotContain("s3cret");
        assertThat(reread.toString()).doesNotContain("s3cret");
        assertThat(reread.clientSecretSet()).isTrue();
    }

    @Test
    void omittingTheSecretOnAnEditKeepsTheStoredOne() {
        var registered = asCompanyAdmin(() -> service.register(anInput().clientSecret("s3cret").build()));

        var edited = asCompanyAdmin(() ->
                service.update(registered.id(), anInput().clientSecret(null).displayName("Renamed").build()));

        assertThat(edited.displayName()).isEqualTo("Renamed");
        assertThat(edited.clientSecretSet())
                .describedAs("no read path can echo the secret, so an edit form has nothing to resend "
                        + "— omission must be a keep, not a clear")
                .isTrue();
    }

    @Test
    void aPlainHttpIssuerIsRefused() {
        assertThatThrownBy(() -> asCompanyAdmin(() ->
                service.register(anInput().issuerUri("http://keycloak.localtest.me:8081/realms/tcms").build())))
                .describedAs("the jwks_uri is read from this URL, so plain HTTP lets anyone on the "
                        + "path nominate the keys that decide who signs in")
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aCallerWithoutCompanyIdpIsRefusedOnEveryOperation() {
        assertThatThrownBy(() -> asTestLead(() -> service.list())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> asTestLead(() -> service.register(anInput().build())))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> asTestLead(() -> service.replaceEmailDomains(new EmailDomains(List.of("acme.test")))))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anotherCompanysProviderIsNotVisibleAndNotEditable() {
        var theirs = asOtherCompanyAdmin(() -> service.register(anInput().build()));

        assertThat(asCompanyAdmin(() -> service.list())).extracting("id").doesNotContain(theirs.id());
        assertThatThrownBy(() -> asCompanyAdmin(() -> service.update(theirs.id(), anInput().build())))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void emailDomainsAreLowercasedAndReplacedWholesale() {
        asCompanyAdmin(() -> service.replaceEmailDomains(new EmailDomains(List.of("ACME.test", "b.test"))));
        var second = asCompanyAdmin(() -> service.replaceEmailDomains(new EmailDomains(List.of("b.test"))));

        assertThat(second.domains()).containsExactly("b.test");
    }
}
```

**Adapt the helper names to the harness as it actually is.** `asCompanyAdmin` / `asTestLead` /
`anInput()` above stand for whatever `ApiTokenEndpointTest` already uses to open a `CompanyContext`
with a principal and to build an input; **read that class first and reuse its helpers rather than
adding a second set**. The exception types likewise: use the ones
`IdentityProviderService` actually throws — read the class, do not assume.

- [ ] **Step 2: Run and verify they fail**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.identity.IdentityProviderEndpointTest'
```

Expected: FAIL — the class does not compile until the helper names are right, then real failures
only if the service disagrees with a rule above. **A failure here is a finding, not a fixture
problem — investigate before adjusting the assertion.**

- [ ] **Step 3: Make them pass without touching production code**

Every behaviour asserted is already implemented. If one genuinely is not, stop and record it: that
is the defect this task exists to find, and it is worth a handoff paragraph rather than a quiet fix.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/tcms/identity/IdentityProviderEndpointTest.java
git commit -m "§NNN — the CRUD that was gated, validated, audited and undriven"
```

## Task 4: `SsoFlowIntegrationTest` — the wire nothing crosses

`§217 FD-11` is the sharpest gap in the queue: `OidcIdTokenVerifierTest` proves the verifier refuses
a wrong nonce, and **nothing proves `SsoLoginService` passes the attempt's nonce to it.** Two green
tests either side of nothing.

The counterparty is a **stub issuer inside the test JVM**, not Testcontainers Keycloak. Three
reasons, and the third is the deciding one: it adds no dependency and no container; it starts in
milliseconds; and it can be made to misbehave on demand — a wrong `nonce`, a token from another
issuer, an endpoint that accepts and never answers — which is exactly what a real Keycloak cannot
easily be told to do. Keycloak stays the manual counterparty for the browser queue, where being real
is the point.

**Files:**
- Create: `backend/src/test/java/com/tcms/auth/StubIssuer.java`
- Create: `backend/src/test/java/com/tcms/auth/SsoFlowIntegrationTest.java`

- [ ] **Step 1: Write the stub issuer**

```java
package com.tcms.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An OIDC issuer, in this JVM, on a loopback port.
 *
 * <p>Serves the three documents the flow reads — {@code openid-configuration}, the JWK set, and the
 * token endpoint — signed by a key generated per test. It exists because §217 shipped a flow that no
 * test drives over HTTP against an issuer no test stands up, and because the interesting cases are
 * the ones a real issuer will not produce on request: an {@code id_token} carrying the wrong
 * {@code nonce}, one from a different {@code iss}, one for a different {@code aud}.
 *
 * <p><strong>It speaks plain HTTP, and that is why the provider row is written through the
 * repository rather than through {@code IdentityProviderService}.</strong> The service refuses a
 * non-https issuer and is right to; see the decision in the plan's Task 1. Registering by hand here
 * is the same fact as {@code deploy/keycloak/README.md}'s SQL recipe, arriving from the test side.
 *
 * <p>Uses {@code com.sun.net.httpserver}, in the JDK since 6 — no dependency, no container.
 */
final class StubIssuer implements AutoCloseable {

    private final HttpServer server;
    private final RSAKey key;
    private final String issuer;

    /** What the next token exchange will answer with. Set per test. */
    private final AtomicReference<JWTClaimsSet.Builder> nextClaims = new AtomicReference<>();

    StubIssuer() throws Exception {
        this.key = new RSAKeyGenerator(2048).keyID("stub").generate();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.issuer = "http://127.0.0.1:" + server.getAddress().getPort();

        server.createContext("/.well-known/openid-configuration", exchange -> respond(exchange, """
                {
                  "issuer": "%s",
                  "authorization_endpoint": "%s/authorize",
                  "token_endpoint": "%s/token",
                  "jwks_uri": "%s/jwks"
                }""".formatted(issuer, issuer, issuer, issuer)));

        server.createContext("/jwks", exchange ->
                respond(exchange, "{\"keys\":[" + key.toPublicJWK().toJSONString() + "]}"));

        server.createContext("/token", exchange -> {
            String idToken = sign(nextClaims.get());
            respond(exchange, """
                    {"access_token":"stub","token_type":"Bearer","id_token":"%s"}"""
                    .formatted(idToken));
        });

        server.start();
    }

    String issuer() {
        return issuer;
    }

    /** The claim set the next exchange answers with — mutate it to drive a refusal. */
    void willIssue(String clientId, String subject, String email, boolean emailVerified, String nonce) {
        nextClaims.set(new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(clientId)
                .subject(subject)
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .claim("nonce", nonce)
                .issueTime(Date.from(Instant.now()))
                .expirationTime(Date.from(Instant.now().plusSeconds(300))));
    }

    /** Overrides one claim on the pending token — how a wrong nonce or a wrong issuer is driven. */
    void override(Map<String, Object> claims) {
        JWTClaimsSet.Builder builder = nextClaims.get();
        claims.forEach(builder::claim);
    }

    private String sign(JWTClaimsSet.Builder claims) throws RuntimeException {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("the stub issuer could not sign", e);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
```

**`nonce` is the claim the whole class exists for.** `willIssue` takes it as a parameter so a test
must decide what to echo, and the passing case has to read it out of the authorize redirect — which
is the only way `FD-11` can be closed.

- [ ] **Step 2: Write the failing flow tests**

```java
package com.tcms.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.tcms.IntegrationTestBase;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The flow, end to end, against an issuer this test stands up. Closes §217 FD-10, FD-11 and FD-12,
 * all three of which were recorded as expected-green for one reason: the HTTP path had no observer.
 *
 * <p>Fixture UUIDs take prefix {@code f2} — hand-managed, no check behind it, collided five times
 * (§19…§62).
 */
class SsoFlowIntegrationTest extends IntegrationTestBase {

    @Test
    void aSignedInPersonGetsATcmsSessionAndTheIdpTokenStopsHere() throws Exception {
        try (StubIssuer issuer = new StubIssuer()) {
            var provider = registerProviderDirectly(issuer.issuer(), "tcms-client");
            linkIdentity(provider, "sub-1", KIM);

            var redirect = URI.create(sso.authorize(provider.getId()).orElseThrow());
            var query = parseQuery(redirect);
            issuer.willIssue("tcms-client", "sub-1", "kim@acme.test", true, query.get("nonce"));

            var signedIn = sso.callback(query.get("state"), "any-code").orElseThrow();

            assertThat(signedIn.userId()).isEqualTo(KIM);
            assertThat(signedIn.session().token())
                    .describedAs("a TCMS session, not the issuer's — the id_token never leaves this method")
                    .isNotBlank();
        }
    }

    @Test
    void theNonceFromTheAttemptIsTheOneRequiredOfTheToken() throws Exception {
        try (StubIssuer issuer = new StubIssuer()) {
            var provider = registerProviderDirectly(issuer.issuer(), "tcms-client");
            linkIdentity(provider, "sub-1", KIM);

            var query = parseQuery(URI.create(sso.authorize(provider.getId()).orElseThrow()));
            issuer.willIssue("tcms-client", "sub-1", "kim@acme.test", true, "a-different-nonce");

            assertThat(sso.callback(query.get("state"), "any-code"))
                    .describedAs("§217 FD-11: the verifier is proved to refuse a wrong nonce, and "
                            + "until this test nothing proved the service handed it the right one")
                    .isEmpty();
        }
    }

    @Test
    void aTokenFromAnotherIssuerIsRefusedEvenWhenTheSubjectIsLinked() throws Exception {
        try (StubIssuer issuer = new StubIssuer(); StubIssuer other = new StubIssuer()) {
            var provider = registerProviderDirectly(issuer.issuer(), "tcms-client");
            linkIdentity(provider, "sub-1", KIM);

            var query = parseQuery(URI.create(sso.authorize(provider.getId()).orElseThrow()));
            issuer.willIssue("tcms-client", "sub-1", "kim@acme.test", true, query.get("nonce"));
            issuer.override(Map.of("iss", other.issuer()));

            assertThat(sso.callback(query.get("state"), "any-code")).isEmpty();
        }
    }

    @Test
    void aReplayedStateIsRefused() throws Exception {
        try (StubIssuer issuer = new StubIssuer()) {
            var provider = registerProviderDirectly(issuer.issuer(), "tcms-client");
            linkIdentity(provider, "sub-1", KIM);

            var query = parseQuery(URI.create(sso.authorize(provider.getId()).orElseThrow()));
            issuer.willIssue("tcms-client", "sub-1", "kim@acme.test", true, query.get("nonce"));

            assertThat(sso.callback(query.get("state"), "any-code")).isPresent();
            assertThat(sso.callback(query.get("state"), "any-code"))
                    .describedAs("§217 FD-10: single-use, claimed by a conditional UPDATE that commits "
                            + "before the exchange")
                    .isEmpty();
        }
    }

    @Test
    void aProviderDisabledBetweenTheRedirectAndTheCallbackRefuses() throws Exception {
        try (StubIssuer issuer = new StubIssuer()) {
            var provider = registerProviderDirectly(issuer.issuer(), "tcms-client");
            linkIdentity(provider, "sub-1", KIM);

            var query = parseQuery(URI.create(sso.authorize(provider.getId()).orElseThrow()));
            issuer.willIssue("tcms-client", "sub-1", "kim@acme.test", true, query.get("nonce"));
            disable(provider);

            assertThat(sso.callback(query.get("state"), "any-code"))
                    .describedAs("the lifecycle check happens inside the company context, against the "
                            + "RLS-protected row — not against the pre-authentication lookup")
                    .isEmpty();
        }
    }

    @Test
    void anotherCompanysIssuerAssertingTheSameSubjectDoesNotReachThisUser() throws Exception {
        try (StubIssuer ours = new StubIssuer(); StubIssuer theirs = new StubIssuer()) {
            var provider = registerProviderDirectly(ours.issuer(), "tcms-client");
            linkIdentity(provider, "sub-1", KIM);
            var otherCompanys = registerProviderInOtherCompany(theirs.issuer(), "tcms-client");

            var query = parseQuery(URI.create(sso.authorize(otherCompanys.getId()).orElseThrow()));
            theirs.willIssue("tcms-client", "sub-1", "kim@acme.test", true, query.get("nonce"));

            var signedIn = sso.callback(query.get("state"), "any-code");

            assertThat(signedIn.map(SsoLoginService.SignedIn::userId))
                    .describedAs("THE account-takeover vector: identity is (company, issuer, subject), "
                            + "so a different client's IdP asserting sub-1 must never resolve to KIM")
                    .isNotEqualTo(java.util.Optional.of(KIM));
        }
    }
}
```

`registerProviderDirectly`, `linkIdentity`, `disable`, `registerProviderInOtherCompany`,
`parseQuery` and `KIM` are this class's own private helpers — write them against
`IdentityProviderRepository` / `UserIdentityRepository` inside a `CompanyContext`, in the shape the
neighbouring endpoint tests already use for fixtures. **`registerProviderDirectly` must set
`enabled = true` and go through the repository**, for the reason `StubIssuer`'s header gives.

- [ ] **Step 3: Run and verify they fail**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.auth.SsoFlowIntegrationTest'
```

Expected: FAIL — `StubIssuer` compiles, the helpers do not exist yet.

- [ ] **Step 4: Write the helpers, run, verify green**

```bash
~/bin/tcms-remote backend ./gradlew test --tests 'com.tcms.auth.SsoFlowIntegrationTest'
```

Expected: PASS, 6 tests. **Any red here is a product defect, not a test defect** — every assertion
restates something `SsoLoginService`'s own javadoc claims.

- [ ] **Step 5: Run the whole backend gate**

```bash
~/bin/tcms-remote backend ./gradlew build
```

Expected: BUILD SUCCESSFUL, 2791 + ~13 tests, 0 failures.

- [ ] **Step 6: Mark the queue rows**

In `docs/verification/backend-mutation-checks.md`, annotate `§217 FD-10`, `FD-11`, `FD-12` and
`FD-14` with the class that now observes them. Leave `FD-13` alone: which separator the fragment uses
is observable in a browser and nowhere else, and `§217 SO-4` is its row.

- [ ] **Step 7: Commit**

```bash
git add backend/src/test/java/com/tcms/auth/StubIssuer.java \
        backend/src/test/java/com/tcms/auth/SsoFlowIntegrationTest.java \
        docs/verification/backend-mutation-checks.md
git commit -m "§NNN — the wire nothing crossed, and the nonce nothing carried"
git push
```

---

# Lane C — the sign-in screen, and the largest gap in plan 1

> Load **`/fe-react`** before starting this lane, then the current phase's skills. Do not improvise a
> pattern a skill owns.

## Task 5: Types, query keys and the four hooks

**Files:**
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/queries/keys.ts`
- Modify: `frontend/src/api/queries/identity.ts`
- Modify: `frontend/src/api/queries/admin.ts`
- Modify: `frontend/src/test/fixtures.ts`

- [ ] **Step 1: Export the contract types**

In `api/types.ts`, beside the existing exports:

```ts
export type SsoProviderChoice = S['SsoProviderChoice'];
export type SsoProviderDiscovery = S['SsoProviderDiscovery'];
export type IdentityProvider = S['IdentityProvider'];
export type IdentityProviderInput = S['IdentityProviderInput'];
export type EmailDomains = S['EmailDomains'];
```

- [ ] **Step 2: Add the query keys**

In `api/queries/keys.ts`, beside `apiTokens`:

```ts
  /**
   * The company's identity providers (§NNN). Company-scoped like `apiTokens` and for the same
   * reason: a provider belongs to the company, and no project segment narrows it.
   */
  identityProviders: ['identity-providers'] as const,

  /** The domains the company claims. A sub-resource, but its own key: the two are edited apart. */
  emailDomains: ['identity-providers', 'email-domains'] as const,
```

- [ ] **Step 3: Add the discovery hook**

In `api/queries/identity.ts` — it is a **mutation**, not a query, because the operation is a `POST`
and because it is driven by a blur rather than by a mount:

```ts
/**
 * Which sign-in buttons an address reaches.
 *
 * **A mutation for a read**, deliberately. The operation is a `POST` because an email address in a
 * query string lands in access logs, browser history and `Referer` headers — and this one is typed
 * by somebody who has not signed in and may never do so. Nothing about it is cacheable per key
 * either: the key would *be* the address.
 *
 * `meta: { handled: true }`: the sign-in form owns every outcome. A failure here must be silent —
 * the buttons simply do not appear — because a notification saying single sign-on could not be
 * checked tells an anonymous caller something about this instance and helps nobody.
 */
export function useSsoProviders(): UseMutationResult<SsoProviderDiscovery, Error, string> {
  return useMutation({
    mutationFn: (email: string) => api.post<SsoProviderDiscovery>('/auth/providers', { email }),
    meta: { handled: true },
  });
}
```

- [ ] **Step 4: Add the three admin hooks**

In `api/queries/admin.ts`, beside the API-token hooks — and copy `useApiTokens`' `enabled` argument
exactly, for the reason its header gives (the read **403s** rather than answering `[]`, so an ungated
fetch raises a global failure over a screen that is working as designed):

```ts
/**
 * The company's identity providers. `enabled` is the gate's verdict, not a convenience: `GET
 * /identity-providers` refuses a caller without `company:idp` rather than answering an empty list,
 * so firing it ungated turns a correct 403 into "Something didn't load" across the page.
 *
 * **Disabled providers are in the list and stay in it.** A list that hid them could not be used to
 * enable one, and registration always produces a disabled provider.
 */
export function useIdentityProviders(enabled: boolean): UseQueryResult<IdentityProvider[]> {
  return useQuery({
    queryKey: keys.identityProviders,
    queryFn: ({ signal }) => api.get<IdentityProvider[]>('/identity-providers', { signal }),
    enabled,
    staleTime: 30_000,
  });
}

export function useEmailDomains(enabled: boolean): UseQueryResult<EmailDomains> {
  return useQuery({
    queryKey: keys.emailDomains,
    queryFn: ({ signal }) => api.get<EmailDomains>('/identity-providers/email-domains', { signal }),
    enabled,
    staleTime: 30_000,
  });
}

/**
 * Registers or reconfigures a provider. One hook for both, because the screen has one form: the
 * only difference is whether an id exists, and `key`/`kind` are ignored on the edit anyway.
 *
 * **Omitting `clientSecret` is a keep, not a clear** — no read path can echo the secret back, so an
 * administrator renaming a provider has nothing to resend. The form sends `undefined` rather than
 * `''` for an untouched secret field; both are a keep on the server, and sending `undefined` keeps
 * the intent legible in a network log.
 */
export function useSaveIdentityProvider(): UseMutationResult<
  IdentityProvider,
  Error,
  { providerId?: string; input: IdentityProviderInput }
> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ providerId, input }) =>
      providerId
        ? api.put<IdentityProvider>(`/identity-providers/${encodeURIComponent(providerId)}`, input)
        : api.post<IdentityProvider>('/identity-providers', input),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.identityProviders });
    },
  });
}

/** Replaces the whole claimed set — the unit an administrator thinks in, and there is no lifecycle
 *  on a claim to preserve. */
export function useSaveEmailDomains(): UseMutationResult<EmailDomains, Error, string[]> {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (domains: string[]) =>
      api.put<EmailDomains>('/identity-providers/email-domains', { domains }),
    meta: { handled: true },
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: keys.emailDomains });
    },
  });
}
```

Re-export all five from `api/queries/index.ts` in the file's existing style.

- [ ] **Step 5: Add the fixtures**

Since §197–§199 the test harness compares every body a mocked `api.*` call serves against
`openapi.yaml`, **and every payload a write double is called with**. A hand-rolled object literal in
a test will fail on the properties it forgot. In `frontend/src/test/fixtures.ts`:

```ts
export function anIdentityProvider(overrides: Partial<IdentityProvider> = {}): IdentityProvider {
  return {
    id: 'f2000000-0000-4000-8000-000000000001',
    key: 'acme-sso',
    kind: 'OIDC',
    displayName: 'Acme Corporate SSO',
    issuerUri: 'https://issuer.acme.test/realms/acme',
    clientId: 'tcms',
    clientSecretSet: true,
    jitProvisioning: false,
    autoLinkVerifiedEmail: false,
    groupClaim: null,
    enabled: false,
    createdAt: AT,
    updatedAt: AT,
    ...overrides,
  }
}

export function anSsoProviderChoice(overrides: Partial<SsoProviderChoice> = {}): SsoProviderChoice {
  return { id: 'f2000000-0000-4000-8000-000000000001', displayName: 'Acme Corporate SSO', ...overrides }
}
```

- [ ] **Step 6: Gate**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
```

Expected: build clean, 0 lint warnings, 1349 tests still green (this task adds no test).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/api frontend/src/test/fixtures.ts
git commit -m "§NNN phase 1 — the five hooks the contract has been waiting for since §217"
```

## Task 6: `adoptSession` — the seam that does not exist

**Files:**
- Modify: `frontend/src/auth/authContextValue.ts`
- Modify: `frontend/src/auth/AuthContext.tsx`
- Test: `frontend/src/auth/AuthContext.test.tsx` (create if absent; otherwise extend)

- [ ] **Step 1: Write the failing tests**

```tsx
import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { ApiError, api } from '../api/client'
import { aCurrentUser } from '../test/fixtures'

describe('adoptSession', () => {
  it('confirms the token against /me before it claims a session', async () => {
    const get = vi.spyOn(api, 'get').mockResolvedValue(aCurrentUser())

    const { result } = renderHook(() => useAuth(), { wrapper: withAuthProvider })
    await result.current.adoptSession('a-session-token')

    expect(get).toHaveBeenCalledWith('/me')
    await waitFor(() => expect(result.current.user).not.toBeNull())
  })

  it('discards a token /me rejects rather than holding a credential it could not confirm', async () => {
    vi.spyOn(api, 'get').mockRejectedValue(new ApiError(401, ''))

    const { result } = renderHook(() => useAuth(), { wrapper: withAuthProvider })
    await expect(result.current.adoptSession('stale')).rejects.toBeInstanceOf(ApiError)

    expect(result.current.user).toBeNull()
    expect(localStorage.getItem('tcms.token')).toBeNull()
  })

  it('tells sibling tabs, so a second tab does not keep rendering signed out', async () => {
    const published: unknown[] = []
    vi.spyOn(api, 'get').mockResolvedValue(aCurrentUser({ email: 'kim@acme.test' }))
    subscribeSession((message) => published.push(message))

    const { result } = renderHook(() => useAuth(), { wrapper: withAuthProvider })
    await result.current.adoptSession('a-session-token')

    await waitFor(() => expect(published).toContainEqual({ type: 'signed-in', email: 'kim@acme.test' }))
  })
})
```

- [ ] **Step 2: Run and verify they fail**

```bash
cd frontend && npm run test:run -- src/auth/AuthContext.test.tsx
```

Expected: FAIL — `adoptSession` is not on the context value.

- [ ] **Step 3: Add it to the context type**

In `auth/authContextValue.ts`, on `AuthValue`:

```ts
  /**
   * Adopts a session this app did not mint the credential for — today, the one that arrives in the
   * `#token=` fragment after an external sign-in (§6r).
   *
   * Separate from {@link login} rather than a second call path into it: `login` posts a password and
   * receives a user, this receives a token and must go and ask who it belongs to. What the two share
   * is everything after that, which is why the token is written in exactly one place either way.
   */
  adoptSession: (token: string) => Promise<CurrentUser>;
```

- [ ] **Step 4: Implement it**

In `AuthContext.tsx`, beside `login`:

```tsx
  /**
   * The external-sign-in landing (§6r). The fragment carries a token and nothing else, so unlike
   * `login` this has to ask the server who it is — which is the mechanism this provider already
   * calls correct: restoring a session is a server question.
   *
   * A rejected `/me` **clears the token**. Holding a credential the server would not confirm is how
   * an app renders a shell that cannot make one successful request.
   *
   * The fragment also carries `expiresAt`, and nothing reads it — here or anywhere. The session's
   * end is the server's to enforce (8h/30d, plus the `pv` epoch); a second copy of it on the client
   * is a number that drifts on clock skew and that nothing acts on.
   */
  const adoptSession = useCallback(
    async (token: string) => {
      safeStorage.set(TOKEN_KEY, token);
      setStatus('restoring');
      try {
        const fresh = await api.get<CurrentUser>('/me');
        safeStorage.setJSON(USER_KEY, fresh);
        setUser(fresh);
        // After storage is written, never before — a sibling adopts by re-confirming, and a
        // broadcast that overtook the write would have it confirm the session it already had.
        publishSession({ type: 'signed-in', email: fresh.email });
        return fresh;
      } catch (error) {
        clearSession();
        throw error;
      } finally {
        setStatus('ready');
      }
    },
    [clearSession],
  );
```

Add `adoptSession` to the `useMemo` value and its dependency array.

- [ ] **Step 5: Run and verify green**

```bash
cd frontend && npm run test:run -- src/auth/AuthContext.test.tsx
```

Expected: PASS, 3 tests.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/auth/AuthContext.tsx frontend/src/auth/authContextValue.ts \
        frontend/src/auth/AuthContext.test.tsx
git commit -m "§NNN phase 2 — one more way in, and still one writer of the token"
```

## Task 7: `/sso/callback` — the route the session has been landing on since §217

**Files:**
- Create: `frontend/src/features/auth/SsoCallbackPage.tsx`
- Create: `frontend/src/features/auth/SsoCallbackPage.test.tsx`
- Modify: `frontend/src/app/router.tsx`

- [ ] **Step 1: Write the failing tests**

```tsx
import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { SsoCallbackPage } from './SsoCallbackPage'

/** The fragment is on `window.location`, not on the router, so tests set it there. */
function landingOn(hash: string) {
  window.history.replaceState(null, '', `/sso/callback${hash}`)
}

describe('SsoCallbackPage', () => {
  it('adopts the session in the fragment and lands on the app', async () => {
    landingOn('#token=a-session-token&expiresAt=2026-08-16T09%3A00%3A00Z')
    const adopt = vi.fn().mockResolvedValue(aCurrentUser())

    render(<SsoCallbackPage />, { wrapper: withAuth({ adoptSession: adopt }) })

    await waitFor(() => expect(adopt).toHaveBeenCalledWith('a-session-token'))
    await waitFor(() => expect(screen.getByTestId('Projects.Page')).toBeInTheDocument())
  })

  it('wipes the fragment, so a live session does not survive in the address bar', async () => {
    landingOn('#token=a-session-token')

    render(<SsoCallbackPage />, { wrapper: withAuth({ adoptSession: vi.fn().mockResolvedValue(aCurrentUser()) }) })

    await waitFor(() => expect(window.location.hash).toBe(''))
  })

  it('adopts once when the effect runs twice', async () => {
    landingOn('#token=a-session-token')
    const adopt = vi.fn().mockResolvedValue(aCurrentUser())

    const { rerender } = render(<SsoCallbackPage />, { wrapper: withAuth({ adoptSession: adopt }) })
    rerender(<SsoCallbackPage />)

    await waitFor(() => expect(adopt).toHaveBeenCalledTimes(1))
  })

  it('renders one sentence for the failure marker, naming no cause', async () => {
    landingOn('#error=sso_failed')

    render(<SsoCallbackPage />, { wrapper: withAuth({ adoptSession: vi.fn() }) })

    await waitFor(() => expect(screen.getByTestId('Login.Form')).toBeInTheDocument())
    expect(screen.getByTestId('Login.Error')).toHaveTextContent("We couldn't sign you in with single sign-on.")
    expect(screen.getByTestId('Login.Error')).not.toHaveTextContent(/provider|account|disabled|expired/i)
  })

  it('treats an empty fragment as a failure rather than as a pending sign-in', async () => {
    landingOn('')

    render(<SsoCallbackPage />, { wrapper: withAuth({ adoptSession: vi.fn() }) })

    await waitFor(() => expect(screen.getByTestId('Login.Form')).toBeInTheDocument())
  })

  it('sends a token /me rejects back to sign-in with the same one sentence', async () => {
    landingOn('#token=stale')
    const adopt = vi.fn().mockRejectedValue(new ApiError(401, ''))

    render(<SsoCallbackPage />, { wrapper: withAuth({ adoptSession: adopt }) })

    await waitFor(() => expect(screen.getByTestId('Login.Error')).toBeInTheDocument())
  })
})
```

Use the file's existing render helpers and `createTestQueryClient` — **never build a `QueryClient` by
hand**, or the render-failure checks (§201/§202) cannot see this test at all.

- [ ] **Step 2: Run and verify they fail**

```bash
cd frontend && npm run test:run -- src/features/auth/SsoCallbackPage.test.tsx
```

Expected: FAIL — the module does not exist.

- [ ] **Step 3: Write the page**

```tsx
import { Box, CircularProgress, Stack, Typography } from '@mui/material';
import { useEffect, useRef, useState } from 'react';
import { Navigate } from 'react-router-dom';
import { Identifier } from '../../components/Identifier';
import { useAuth } from '../../auth/useAuth';

/**
 * Where an external sign-in lands (§6r, §217 SO-3).
 *
 * The backend redirects here with the session in the URL **fragment** — `#token=…&expiresAt=…` — or
 * with `#error=sso_failed`. A fragment is never sent to a server, so the token does not reach this
 * instance's access log, the reverse proxy's, or the `Referer` of the first outbound link this app
 * renders. That property is the reason the flow ends in a route rather than in a redirect the SPA
 * never sees, and it is why the first thing this component does is take the value out of the URL.
 *
 * ## Three things here are not stylistic
 *
 * **The fragment is read during the first render, not in the effect.** The effect wipes it, and
 * React runs effects twice in development — an effect that reads *and* wipes sees nothing the second
 * time and reports a failure for a sign-in that worked.
 *
 * **`expiresAt` is parsed and ignored.** It is in the fragment because `AuthResult` carries it and
 * both paths mint the same session. Nothing in this app has ever stored a session expiry: the
 * server enforces it, and a client-side copy is a number that drifts and that nothing acts on.
 *
 * **Every failure is one sentence.** Disabled provider, expired attempt, replayed `state`,
 * unverified email, deactivated account, no invitation — the server deliberately sends one opaque
 * marker for all of them (§6r), and a screen that guessed at causes would rebuild the oracle the
 * refusal design exists to close.
 */
export function SsoCallbackPage() {
  const { adoptSession } = useAuth();

  /** Captured before anything can wipe it. See the header. */
  const fragment = useRef(new URLSearchParams(window.location.hash.replace(/^#/, '')));
  const started = useRef(false);
  const [outcome, setOutcome] = useState<'pending' | 'signed-in' | 'failed'>('pending');

  useEffect(() => {
    if (started.current) return;
    started.current = true;

    // Out of the address bar first, and unconditionally: a live session survives a copy-paste, a
    // screen share and the browser's own history until it is gone from here.
    window.history.replaceState(null, '', window.location.pathname);

    const token = fragment.current.get('token');
    if (!token) {
      setOutcome('failed');
      return;
    }

    adoptSession(token).then(
      () => setOutcome('signed-in'),
      () => setOutcome('failed'),
    );
  }, [adoptSession]);

  if (outcome === 'signed-in') return <Navigate to="/" replace />;

  if (outcome === 'failed') {
    // `replace`, so the browser's back button does not return to a callback whose `state` the
    // server has already consumed — that lands on a second, identical failure and reads as a loop.
    return <Navigate to="/login" replace state={{ ssoFailed: true }} />;
  }

  return (
    <Box
      component="main"
      role="status"
      aria-busy="true"
      data-testid="Sso.Completing"
      sx={{ minHeight: '100vh', display: 'grid', placeItems: 'center', bgcolor: 'background.default' }}
    >
      <Stack sx={{ alignItems: 'center', gap: 2 }}>
        <Identifier variant="strong" sx={{ fontSize: '1.5rem', letterSpacing: '0.06em' }}>
          TCMS
        </Identifier>
        <CircularProgress size={20} aria-hidden />
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Signing you in…
        </Typography>
      </Stack>
    </Box>
  );
}
```

- [ ] **Step 4: Route it, publicly**

In `app/router.tsx`, beside `/accept-invitation`:

```tsx
      {/*
        Where an external sign-in lands (§6r). Public, and un-prefixed for the reason `/login` is:
        the person arriving here has a token in a fragment and no company — the segment M6d adds
        cannot be known until `/me` has answered.

        The path is `tcms.oidc.spa-callback-path` on the server side and the two must agree; the
        server builds the URL, so a rename here silently sends every federated sign-in to the
        not-found page, which is exactly the state §217 shipped in.
      */}
      <Route path="/sso/callback" element={<SsoCallbackPage />} />
```

Not lazy: it is 60 lines, and a code-split chunk between the issuer and the session is one more thing
that can fail while somebody is signing in.

- [ ] **Step 5: Run and verify green**

```bash
cd frontend && npm run test:run -- src/features/auth/SsoCallbackPage.test.tsx
```

Expected: PASS, 6 tests.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/auth/SsoCallbackPage.tsx \
        frontend/src/features/auth/SsoCallbackPage.test.tsx frontend/src/app/router.tsx
git commit -m "§NNN phase 3 — the route the session has been landing on unread since §217"
```

## Task 8: The provider buttons on the sign-in screen

**Files:**
- Modify: `frontend/src/features/auth/LoginPage.tsx`
- Modify: `frontend/src/features/auth/LoginPage.test.tsx`

- [ ] **Step 1: Write the failing tests**

```tsx
it('offers a button per provider once the address is complete', async () => {
  vi.spyOn(api, 'post').mockResolvedValue({ providers: [anSsoProviderChoice({ displayName: 'Acme SSO' })] })

  render(<LoginPage />, { wrapper: withProviders() })
  await user.type(screen.getByTestId('Login.EmailField'), 'kim@acme.test')
  await user.tab()

  expect(await screen.findByRole('button', { name: /continue with acme sso/i })).toBeInTheDocument()
})

it('says nothing at all when the domain reaches no provider', async () => {
  vi.spyOn(api, 'post').mockResolvedValue({ providers: [] })

  render(<LoginPage />, { wrapper: withProviders() })
  await user.type(screen.getByTestId('Login.EmailField'), 'kim@nowhere.test')
  await user.tab()

  await waitFor(() => expect(api.post).toHaveBeenCalled())
  expect(screen.queryByTestId('Login.Providers')).not.toBeInTheDocument()
  // The property the server pays for: an empty answer must be indistinguishable from no answer.
  expect(screen.queryByText(/single sign-on|no provider|not available/i)).not.toBeInTheDocument()
})

it('says nothing when discovery fails either', async () => {
  vi.spyOn(api, 'post').mockRejectedValue(new ApiError(500, ''))

  render(<LoginPage />, { wrapper: withProviders() })
  await user.type(screen.getByTestId('Login.EmailField'), 'kim@acme.test')
  await user.tab()

  await waitFor(() => expect(api.post).toHaveBeenCalled())
  expect(screen.queryByTestId('Login.Providers')).not.toBeInTheDocument()
})

it('navigates the browser to the authorize endpoint rather than fetching it', async () => {
  vi.spyOn(api, 'post').mockResolvedValue({ providers: [anSsoProviderChoice({ id: PROVIDER_ID })] })
  const assign = vi.spyOn(window.location, 'assign').mockImplementation(() => {})

  render(<LoginPage />, { wrapper: withProviders() })
  await user.type(screen.getByTestId('Login.EmailField'), 'kim@acme.test')
  await user.tab()
  await user.click(await screen.findByTestId(`Login.Provider.${PROVIDER_ID}`))

  expect(assign).toHaveBeenCalledWith(`/api/v1/auth/sso/${PROVIDER_ID}/authorize`)
})

it('renders the single sign-on failure as one sentence with no cause', () => {
  render(<LoginPage />, { wrapper: withProviders({ locationState: { ssoFailed: true } }) })

  expect(screen.getByTestId('Login.Error')).toHaveTextContent("We couldn't sign you in with single sign-on.")
})
```

- [ ] **Step 2: Run and verify they fail**

```bash
cd frontend && npm run test:run -- src/features/auth/LoginPage.test.tsx
```

Expected: FAIL — no discovery call, no buttons, no SSO error.

- [ ] **Step 3: Implement**

Add to `LoginPage`:

```tsx
  const discovery = useSsoProviders();
  const providers = discovery.data?.providers ?? [];

  /** Set when `SsoCallbackPage` sent them back. One sentence, no cause — see that file. */
  const ssoFailed = (navState as { ssoFailed?: boolean } | null)?.ssoFailed === true;

  /**
   * Discovery runs on blur, not per keystroke: the endpoint takes a whole address, and asking on
   * every character posts a stranger's half-typed address to the server a dozen times.
   */
  const onEmailBlur = (event: React.FocusEvent<HTMLInputElement>) => {
    const email = event.target.value.trim();
    if (email.includes('@')) discovery.mutate(email);
  };
```

Wire `onEmailBlur` alongside `register('email')`'s own `onBlur` (call both — react-hook-form needs
its own to fire for `mode: 'onBlur'` validation), render the SSO error in the existing
`Login.Error` alert region, and add the button block **below** the password form:

```tsx
        {providers.length > 0 && (
          <Stack sx={{ gap: 1, mt: 2 }} data-testid={`${TestIdPrefix}.Providers`}>
            <Typography variant="caption" sx={{ color: 'text.secondary' }}>
              Or continue with
            </Typography>
            {providers.map((provider) => (
              <Button
                key={provider.id}
                variant="outlined"
                size="large"
                fullWidth
                data-testid={`${TestIdPrefix}.Provider.${provider.id}`}
                /*
                  A navigation, not a fetch. The endpoint answers 302 to a THIRD-PARTY origin; a
                  `fetch` would follow it into a CORS failure, and the point is to put the browser
                  itself at the issuer's login page. Against the API origin — the same relative
                  prefix `api/client.ts` uses, so the dev proxy and the deployment both work.
                */
                onClick={() => window.location.assign(`/api/v1/auth/sso/${encodeURIComponent(provider.id)}/authorize`)}
              >
                Continue with {provider.displayName}
              </Button>
            ))}
          </Stack>
        )}
```

And **delete** the standing caption — it has been wrong since §217 shipped:

```diff
-        <Typography variant="caption" sx={{ display: 'block', mt: 2, color: 'text.secondary' }}>
-          Local account. Single sign-on arrives with the integrations release.
-        </Typography>
```

Nothing renders in the empty and failed cases. That is the whole of trap 5, and it is one `&&`.

- [ ] **Step 4: Run and verify green**

```bash
cd frontend && npm run test:run -- src/features/auth/LoginPage.test.tsx
```

Expected: PASS, existing tests plus 5.

- [ ] **Step 5: Full frontend gate**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
```

Expected: clean build, 0 warnings, 1349 + ~11 tests green.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/features/auth/LoginPage.tsx frontend/src/features/auth/LoginPage.test.tsx
git commit -m "§NNN phase 4 — the buttons, and the silence that is the point of them"
```

## Task 9: The provider admin screen

**Files:**
- Create: `frontend/src/features/admin/SsoProvidersPage.tsx` (+ `.test.tsx`)
- Create: `frontend/src/features/admin/ProviderFormDialog.tsx` (+ `.test.tsx`)
- Modify: `frontend/src/app/router.tsx` · `frontend/src/app/AppShell.tsx`

- [ ] **Step 1: Write the failing page tests**

```tsx
it('does not fetch for a caller without company:idp, and says who can', async () => {
  render(<SsoProvidersPage />, { wrapper: withProviders({ companyPermissions: [] }) })

  expect(api.get).not.toHaveBeenCalled()
  expect(await screen.findByText(/company administrator/i)).toBeInTheDocument()
})

it('lists disabled providers rather than hiding them', async () => {
  mockGet('/identity-providers', [anIdentityProvider({ displayName: 'Acme SSO', enabled: false })])

  render(<SsoProvidersPage />, { wrapper: withProviders({ companyPermissions: ['company:idp'] }) })

  expect(await screen.findByText('Acme SSO')).toBeInTheDocument()
  expect(screen.getByTestId('Admin.Sso.Row.Status')).toHaveTextContent(/disabled/i)
})

it('offers no delete, because there is none', async () => {
  mockGet('/identity-providers', [anIdentityProvider()])

  render(<SsoProvidersPage />, { wrapper: withProviders({ companyPermissions: ['company:idp'] }) })

  await screen.findByText('Acme Corporate SSO')
  expect(screen.queryByRole('button', { name: /delete|remove/i })).not.toBeInTheDocument()
})

it('never shows the secret and never offers to reveal it', async () => {
  mockGet('/identity-providers', [anIdentityProvider({ clientSecretSet: true })])

  render(<SsoProvidersPage />, { wrapper: withProviders({ companyPermissions: ['company:idp'] }) })

  await screen.findByText('Acme Corporate SSO')
  expect(screen.queryByRole('button', { name: /reveal|show secret|copy secret/i })).not.toBeInTheDocument()
})
```

And for the dialog:

```tsx
it('omits clientSecret entirely when the field is left blank on an edit', async () => {
  const put = vi.spyOn(api, 'put').mockResolvedValue(anIdentityProvider())

  render(<ProviderFormDialog provider={anIdentityProvider({ clientSecretSet: true })} onClose={vi.fn()} />, {
    wrapper: withProviders({ companyPermissions: ['company:idp'] }),
  })
  await user.clear(screen.getByTestId('Admin.Sso.DisplayName'))
  await user.type(screen.getByTestId('Admin.Sso.DisplayName'), 'Renamed')
  await user.click(screen.getByTestId('Admin.Sso.Save'))

  const body = vi.mocked(put).mock.calls[0][1] as Record<string, unknown>
  expect(body).not.toHaveProperty('clientSecret')
})

it('says the secret is kept rather than asking for it again', () => {
  render(<ProviderFormDialog provider={anIdentityProvider({ clientSecretSet: true })} onClose={vi.fn()} />, {
    wrapper: withProviders({ companyPermissions: ['company:idp'] }),
  })

  expect(screen.getByTestId('Admin.Sso.ClientSecret')).toHaveAccessibleDescription(/leave blank to keep/i)
})

it('offers no Enable on registration, because registration cannot enable', () => {
  render(<ProviderFormDialog provider={null} onClose={vi.fn()} />, {
    wrapper: withProviders({ companyPermissions: ['company:idp'] }),
  })

  expect(screen.queryByTestId('Admin.Sso.Enabled')).not.toBeInTheDocument()
})
```

- [ ] **Step 2: Run and verify they fail**

```bash
cd frontend && npm run test:run -- src/features/admin/SsoProvidersPage.test.tsx src/features/admin/ProviderFormDialog.test.tsx
```

Expected: FAIL — neither module exists.

- [ ] **Step 3: Write the page**

```tsx
import { Button, Chip, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { useState } from 'react';
import { useEmailDomains, useIdentityProviders } from '../../api/queries';
import type { IdentityProvider } from '../../api/types';
import { useCanInCompany } from '../../auth/useCan';
import { DataRegion } from '../../components/DataRegion';
import { Identifier } from '../../components/Identifier';
import { PageHeader, Panel } from '../../components/PageHeader';
import { ScrollX } from '../../components/ScrollX';
import { formatDateTime } from '../../lib/format';
import { EmailDomainsPanel } from './EmailDomainsPanel';
import { ProviderFormDialog } from './ProviderFormDialog';

/**
 * A company's external issuers — `company:idp`'s first *screen*, one slice after its first consumer.
 *
 * ## The read is refused, not narrowed, so this screen asks before it fetches
 *
 * `GET /identity-providers` 403s without `company:idp` rather than answering `[]`. `ApiTokensPage`'s
 * reasoning applies unchanged: an ungated read turns a correct refusal into "Something didn't load"
 * over a page that is working as designed, so the gate's verdict drives `enabled` and the refusal is
 * rendered here as a sentence. Deferred while the verdict is pending, never rendered as a denial —
 * `Gate.can` is fail-closed while loading, which is right for a button and wrong for a page.
 *
 * ## There is no delete, and its absence is the design
 *
 * `user_identity` rows reference a provider, so removing one would orphan or cascade away every
 * federated account in the company. `enabled: false` is the off switch, and a company that turned
 * SSO off for a week gets its people back with their authorship intact.
 *
 * ## Disabled rows stay, and registration cannot enable
 *
 * A list that hid disabled providers could not be used to enable one — and registration always
 * produces a disabled provider, because an issuer URL typed wrong is a sign-in route that refuses
 * everybody. Going live is a second, deliberate act on the edit form.
 */
const TestId = 'Admin.Sso';

export function SsoProvidersPage() {
  const gate = useCanInCompany();
  const allowed = gate.can('company:idp');
  const providers = useIdentityProviders(allowed);
  const domains = useEmailDomains(allowed);
  const [editing, setEditing] = useState<IdentityProvider | null | undefined>(undefined);

  if (gate.pending) return <PageHeader title="Single sign-on" />;

  if (!allowed) {
    return (
      <>
        <PageHeader title="Single sign-on" />
        <Panel>
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
            Configuring identity providers is a company administrator's job. Ask one of yours to set
            this up.
          </Typography>
        </Panel>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title="Single sign-on"
        actions={
          <Button variant="contained" onClick={() => setEditing(null)} data-testid={`${TestId}.Register`}>
            Register a provider
          </Button>
        }
      />

      <Panel>
        <DataRegion
          testId={`${TestId}.List`}
          state={{ ...providers, refetch: () => void providers.refetch() }}
          isEmpty={(rows) => rows.length === 0}
          empty={{
            kind: 'no-records',
            title: 'No provider yet',
            body: 'Register your issuer to let this company sign in with its own accounts.',
          }}
        >
          {(rows) => (
            <ScrollX>
              <Table size="small">
                <TableHead>
                  <TableRow>
                    <TableCell>Name</TableCell>
                    <TableCell>Key</TableCell>
                    <TableCell>Issuer</TableCell>
                    <TableCell>Status</TableCell>
                    <TableCell>Updated</TableCell>
                    <TableCell />
                  </TableRow>
                </TableHead>
                <TableBody>
                  {rows.map((provider) => (
                    <TableRow key={provider.id} data-testid={`${TestId}.Row`}>
                      <TableCell>{provider.displayName}</TableCell>
                      <TableCell>
                        <Identifier>{provider.key}</Identifier>
                      </TableCell>
                      <TableCell>
                        <Identifier>{provider.issuerUri}</Identifier>
                      </TableCell>
                      <TableCell data-testid={`${TestId}.Row.Status`}>
                        <Chip
                          size="small"
                          label={provider.enabled ? 'Live' : 'Disabled'}
                          color={provider.enabled ? 'success' : 'default'}
                        />
                      </TableCell>
                      <TableCell>{formatDateTime(provider.updatedAt)}</TableCell>
                      <TableCell align="right">
                        <Button size="small" onClick={() => setEditing(provider)}>
                          Configure
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </ScrollX>
          )}
        </DataRegion>
      </Panel>

      <EmailDomainsPanel query={domains} />

      {editing !== undefined && <ProviderFormDialog provider={editing} onClose={() => setEditing(undefined)} />}
    </>
  );
}
```

`EmailDomainsPanel` is a small sibling component in the same file's directory: it renders
`domains.data.domains` as removable chips plus a "Add a domain" text field, and saves the whole array
through `useSaveEmailDomains`. It carries one comment worth having — *a claim, not proof of
ownership; several companies may claim one domain, and a rejection would leak that somebody else
already had* — quoted from the contract.

- [ ] **Step 4: Write the dialog**

`ProviderFormDialog` takes `{ provider: IdentityProvider | null; onClose: () => void }` — `null`
means register. React Hook Form + Zod, matching `IssueTokenDialog`'s shape. Four rules, each of which
has a test above:

```tsx
const ProviderSchema = z.object({
  key: z.string().regex(/^[a-z0-9][a-z0-9-]{0,58}[a-z0-9]$/, 'Lowercase letters, digits and hyphens.'),
  displayName: z.string().min(1, 'Name this provider.').max(120),
  issuerUri: z
    .string()
    .url('Enter the issuer URL, like https://issuer.acme.test/realms/acme.')
    // The server refuses anything else, and it is the URL the signing keys are read from — so an
    // http issuer means whoever is on the path chooses who may sign in. Checked here so the message
    // says that rather than arriving as a 400 field error with no explanation.
    .refine((value) => value.startsWith('https://'), 'The issuer must be https.'),
  clientId: z.string().min(1, 'Enter the client id this provider issued you.').max(500),
  clientSecret: z.string(),
  jitProvisioning: z.boolean(),
  autoLinkVerifiedEmail: z.boolean(),
  groupClaim: z.string(),
});
```

- `key` and `kind` are **disabled on edit** — both are set once at registration, and the server
  ignores them anyway; a live field the server discards is a lie the form tells.
- `clientSecret` is sent as `undefined` when blank, never `''`. Its helper text is *"Leave blank to
  keep the stored secret."* when `clientSecretSet`, and *"Public clients have none."* otherwise.
- `enabled` renders **only on edit**, with the sentence *"Turning this on makes it a live sign-in
  route for everyone at your claimed domains."*
- `jitProvisioning` and `autoLinkVerifiedEmail` each carry their safe-direction sentence: *"Anyone
  your issuer authenticates gets an account here"* and *"Your issuer's word about an email address is
  enough to reach an account that already exists."* Both default off and both say why.

- [ ] **Step 5: Route it and put it in the rail**

`app/router.tsx`, beside `/admin/tokens`:

```tsx
          {/*
            Single sign-on (§NNN). Company-scoped like `/admin/teams` and `/admin/tokens`, and for
            the same reason: a provider belongs to the company, so a project segment would make the
            rail's switcher appear to change a list it cannot change.
          */}
          <Route
            path="/admin/sso"
            element={
              <Suspense fallback={<RouteFallback />}>
                <SsoProvidersPage />
              </Suspense>
            }
          />
```

Lazy, like its two neighbours, with the same `lazy(() => import(...))` form. In `AppShell.tsx`, add
`{ label: 'Single sign-on', to: '/admin/sso', icon: <LoginOutlinedIcon fontSize="small" /> }` to the
company group beside "API tokens". **Do not gate the rail entry** — the page renders its own refusal,
which is `NotificationSettingsPage`'s precedent and the reason a pasted link behaves the same for
everybody.

- [ ] **Step 6: Run and verify green**

```bash
cd frontend && npm run test:run -- src/features/admin/SsoProvidersPage.test.tsx src/features/admin/ProviderFormDialog.test.tsx
```

Expected: PASS, 7 tests.

- [ ] **Step 7: Full gate**

```bash
cd frontend && npm run build && npm run lint && npm run test:run
```

Expected: clean build, 0 warnings, ~1367 tests green.

- [ ] **Step 8: Commit**

```bash
git add frontend/src/features/admin frontend/src/app/router.tsx frontend/src/app/AppShell.tsx
git commit -m "§NNN phase 5 — the screen that can point a company at its own issuer"
```

## Task 10: The queue rows, the caches and the handoff

**Files:**
- Modify: `docs/verification/frontend-browser-checks.md`
- Modify: `docs/agent-context/fe-react.md`
- Modify: `docs/HANDOFF.md` + create `docs/handoff/NNN-<slug>.md`

- [ ] **Step 1: Re-point the `§217 SO-*` rows rather than rewriting them**

Ids are quoted from handoff sections and are **never renumbered**. `SO-1` and `SO-3` were written to
record an absence; both now have something to observe. Append to each row, in place:

- `§217 SO-1` — *"§NNN built the caller. The expectation is now: a button labelled from the
  provider's `displayName`, one `POST /auth/providers` on blur, and **nothing rendered at all** for a
  domain that reaches none. A message naming the emptiness is a regression against §6r."*
- `§217 SO-3` — *"§NNN built the route. The expectation is now: the fragment is gone from the address
  bar before the first paint completes, and the browser lands on the project list signed in. The
  fragment appearing in any request line is `§217 FD-13` failing and is a live-credential
  disclosure."*
- `§217 SO-6` and `SO-7` — *"§NNN: the landing is now the sign-in screen showing one sentence,
  'We couldn't sign you in with single sign-on.' Any second sentence, and any wording that differs
  between the two rows, is the oracle §6r closes."*

- [ ] **Step 2: Add the new rows for what §NNN built**

Append `§NNN SS-1`…`SS-5` to the same live pass, in the lane whose state they need:

- `SS-1` — Register a provider through `/admin/sso` as Company Admin against a real https issuer;
  assert it lands **disabled** and that no response and no rendered pixel contains the secret.
- `SS-2` — Claim `acme.test` on the same screen, sign out, and type an address at that domain on the
  sign-in form; the button appears. Type one at an unclaimed domain; **nothing appears**, and
  devtools shows the request answered `200 {"providers":[]}`.
- `SS-3` — Open `/admin/sso` as a Test Lead: no fetch fires, and the page explains rather than 404s.
- `SS-4` — Sign in through the button, then check the address bar and the network log: `#token=` gone
  from the URL, present in no request line, and the app shows your name.
- `SS-5` — Press the browser's Back button from the signed-in app; the callback route does **not**
  re-run and the app does not sign you out.

- [ ] **Step 3: Update the orientation caches**

`docs/agent-context/fe-react.md`: add the seams this lane created — `adoptSession` as the second and
last writer of `tcms.token`, `/sso/callback` as the third public route, the discovery mutation's
deliberate silence, `SsoProvidersPage` as the `company:idp` screen. Re-stamp `Last verified` and
`Baseline commit`, prepend one session-log line. Re-read the file first.

- [ ] **Step 4: Write the handoff sections and one index row each**

One file per lane under `docs/handoff/NNN-<slug>.md`, one row each in `docs/HANDOFF.md`. Never append
to `HANDOFF.md` itself.

- [ ] **Step 5: Commit and push**

```bash
git add docs/
git commit -m "§NNN phase 6 — the handoff sections, the index rows, both caches and the queue rows"
git push
```

---

# Lane D (part 2) — what stays deferred, and why

No task. These are recorded here so the next reader does not re-derive them.

**RP-initiated logout at the IdP.** §6r: opt-in *per provider*, because signing a tester out of every
corporate application because they closed a test run is rarely what anyone wants. It needs one
nullable column (`end_session_opt_in`), the discovery document's `end_session_endpoint`, and a
redirect on logout instead of the current local clear. It is additive over everything this plan
builds and it changes no shape here. **Deferred: the opt-in has no consumer until a client asks for
it, and shipping an off-by-default column is how a migration lands with nothing behind it.**

**Per-company `/{companySlug}/login`.** §6r calls it "the better path where clients expect their own
entry point" — and it now **conflicts with a later decision**, which is the thing to carry: the M6d
slug-routing plan says explicitly that *"`/login` and `/accept-invitation` stay public and stay
un-prefixed"*, because somebody redeeming an invitation has no company to name. `/sso/callback` joins
them on the same argument. So a per-company login URL is no longer additive over
`POST /auth/providers`; it is a decision *against* M6d's route topology, and it belongs in M6d's plan
rather than in M4's. **Deferred, and re-filed.**

**Plan 2 — claim → role mapping.** Sketched at the bottom of
`docs/plans/2026-08-15-m4-external-identity-oidc.md`, unchanged by this plan except in one respect
worth writing down before it starts: §217 corrected the plan's claim that JIT provisioning creates a
Viewer grant — `InvitationService.accept` creates **no `RoleAssignment` at all**. So plan 2 is not
adding a *second* grant path for federated users; it is adding the **first**, which makes the
`assignable` question `RoleGrantService` owns more load-bearing than the sketch says, not less.
Three rows are non-assignable (`Company Member` `V15`, `Agent` `V40`, `Automation Service` `V41`) and
a claim mapping must ask the same question, at **save** time.

**Plan 3 — LDAP / AD.** Unchanged. Note only that Task 1's decision does **not** settle plan 3's
`allowInsecure`: that flag is about a *bind*, this one was about a *discovery URL*, and the two have
different blast radii. Plan 3 argues it on its own ground.

---

## Self-review

**Spec coverage.** §217's "What is NOT done" list, item by item: *no sign-in screen* → Tasks 5–9;
*the reference issuer cannot be configured through the product* → Task 1 (decided, not built);
*no integration test crosses the wire* → Task 4; *`IdentityProviderServiceTest` does not exist* →
Task 3, as `IdentityProviderEndpointTest` because it boots a context and therefore belongs to the
`qa-api-java` lane; *`§217 FD-15` / `§218 NC-5`* → Task 2; *plans 2 and 3 are sketches* → lane D,
deliberately not re-planned here — each gets its own file when its turn comes, and lane D records the
one thing §217 learned that changes plan 2's sketch; *RP-initiated logout and `/{slug}/login`* → lane
D, with the M6d conflict recorded. *The Linux lane has not seen this slice as a set* → not a task:
`tests.yml` runs nightly on the default branch and the lanes' pushes are what feed it.

**Gaps deliberately left.** Two of §217's browser rows stay a person's job and cannot be automated
here: `SO-4` (the fragment in no request line) is observable in a browser and nowhere else, and two
**concurrent** callbacks racing one `state` — as opposed to the sequential replay Task 4 covers —
need two request threads and a shared database, which the stub-issuer test does not arrange. Both are
stated at their rows rather than silently dropped.

**Placeholder scan.** No TBD/TODO. Three steps describe an implementation without a full listing —
Task 9 step 4's dialog body, Task 9 step 3's `EmailDomainsPanel`, and Task 4 step 4's fixture helpers
— and each states every rule that constrains it plus the neighbouring file to copy the shape from
(`IssueTokenDialog`, `ApiTokensPage`, `ApiTokenEndpointTest`). Every step carrying a *decision*
carries its code.

**Type consistency.** `adoptSession(token: string) => Promise<CurrentUser>` is declared in Task 6
step 3 and used in Tasks 6 and 7. `useSsoProviders` returns `SsoProviderDiscovery` in Task 5 and is
read as `.data?.providers` in Task 8. `useSaveIdentityProvider` takes
`{ providerId?: string; input: IdentityProviderInput }` in Task 5 and is called from the dialog in
Task 9. `useIdentityProviders(enabled: boolean)` matches `useApiTokens`' signature exactly.
`contractDocument()` is added in Task 2 step 1 and imported in step 2. `StubIssuer.willIssue(clientId,
subject, email, emailVerified, nonce)` is defined in Task 4 step 1 and called with those five
arguments in step 2.

**Numbering.** `§NNN` is a placeholder for the handoff section number, assigned when each lane lands,
because sections are allocated in landing order and three lanes here can land in any order.
