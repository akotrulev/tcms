# §217 — Sign-in through somebody else's issuer, and the counterparty that cannot be configured

**Seven phases**, mostly `backend/`: `V53` and two lines of `SchemaConventionTest` (phase 1), the
federation entities (phase 2), `FederatedIdentityService` (phase 3), the OIDC protocol half
(phase 4), three endpoints plus `openapi.yaml` and both `schema.d.ts` copies (phase 5), the provider
CRUD (phase 6), and Keycloak in compose plus the documents (phase 7, this one). **No permission was
added and none moved** — `company:idp` has been seeded since `V3` and this slice is its **first
consumer, five migrations later** — so the catalogue is still 55 keys, the roles spec's §A3 is
untouched, and neither matrix copy came into it. **No `packages/` surface and no `frontend/` byte**,
and the second of those is the slice's largest open item rather than a decision.

The plan is `docs/plans/2026-08-15-m4-external-identity-oidc.md`. It is **plan 1 of three**; plans 2
(claim → role mapping) and 3 (LDAP/AD) are sketches at the bottom of that file and neither has been
started. §218 ran concurrently in the `worktree-notifications` worktree; this lane held `openapi.yaml`
and `V53`.

---

## The three places the plan was wrong, and each is recorded at its site

A plan this detailed being wrong three times is worth stating plainly, because all three were caught
by building rather than by reading:

1. **Trap 1 was right and one endpoint short.** `V53`'s header is emphatic that every SSO read
   happens before company context exists, and it built `tcms_resolve_identity_provider` for the
   authorize leg. Then the plan specified `POST /auth/providers` — email in, buttons out — resolved
   through `company_email_domain`, which is company-owned and FORCE RLS'd. Under RLS that read
   returns zero rows for an anonymous caller, so SSO would have been **undiscoverable, permanently,
   presenting as an empty button list rather than as an error.** The answer is
   `tcms_resolve_providers_for_domain`, appended to `V53` — **which amends an already-pushed
   migration**, acceptable only because nothing but Testcontainers had migrated it. It is `V8`'s
   shape and not `V7`'s: **`SECURITY INVOKER`**, walking the ACTIVE companies with `set_config` and
   asking the ordinary fully-policed question in each. `SECURITY DEFINER` does not work on these
   tables, and the reason is one this repository has already paid for — `FORCE ROW LEVEL SECURITY`
   subjects the table's owner to its own policies, so a definer function owned by `tcms_owner`
   matches an unset GUC and returns nothing. *Generalised: when a slice adds a pre-authentication
   surface, enumerate every read it makes, not the one the plan drew a box around.*
2. **The JIT test asserted a grant the invitation flow does not create.** The plan's step 3 says JIT
   creates "a **Viewer**, company-scope, no project access grant (the same default as an invitation)"
   and its step 1 wrote the test for it. `InvitationService.accept` creates **no `RoleAssignment` at
   all** — account, ACTIVE membership, `tcms_grant_company_read_baseline`, and nothing else. Building
   to the plan would have made SSO grant *more* capability than the flow it claims parity with, and
   the parenthetical is what made it look safe.
3. **The callback carried a caller-supplied copy of a fact the `state` already fixes.** The plan
   specified `GET /auth/sso/{providerId}/callback`. The `state` already binds the attempt, and
   therefore the provider and the company, server-side. A second copy in the path could only be
   ignored or trusted, and on a pre-authentication surface the second is how a callback gets pointed
   at somebody else's provider. It is `GET /auth/sso/callback`.

**The arrival state at phase 3 was RED**, and that is its own lesson: phase 2 landed
`IdentityProvider` and `UserIdentity` and ran `RepositoryTransactionTest`, which is what the plan
named. `everyMappedColumnHasAReader`, `everyMappedColumnHasAWriter`,
`everyDeclaredRepositoryMethodHasACaller` and `everyEntityIsWritableByTheProduct` all reported the
same absence from four angles, and **none of them is the class a persistence plan thinks to run.**
All four are closed. *Generalised: an entity that lands a phase ahead of its callers fails four
architecture rules; run the class the entity will be reported by, not the one the plan names.*

## Phase 7's own finding: the reference issuer cannot be registered through the product

#37 made Keycloak the reference IdP specifically so this lane's counterparty is real rather than a
mock, and the plan's Task 8 asks for it "runnable from a clean `docker compose up`". It is —
`compose.yaml` gains the service, `deploy/keycloak/tcms-realm.json` is imported on every start, and
the issuer, the client and both users come up with no manual step.

**But a Company Admin cannot point TCMS at it.** `IdentityProviderService.validate` refuses any
`issuerUri` that does not begin `https://`, and it is right to: the discovery document — and
therefore the `jwks_uri` every signature is checked against — is fetched from that URL, so over
plain HTTP anyone on the path nominates the keys that decide who signs in. `§217 FD-14` is the
mutation row that pins the gate. Phase 6 shipped it and Task 8 asked for a plain-HTTP counterparty;
**neither is wrong on its own and the plan never noticed they collide.**

Three things follow, and the third is the one to carry:

- **The local provider row is inserted by SQL**, and the recipe is in
  `deploy/keycloak/README.md` beside the reason. It runs as `postgres` rather than `tcms_owner`,
  because FORCE RLS subjects the owner to its own policies and an `INSERT` with no `WITH CHECK` is
  checked against the `USING` predicate — so the owner is refused with no context open. The
  superuser is the one identity that bypasses it. This is the same fact as trap 1, arriving from the
  write side.
- **The realm's client is PUBLIC, not confidential, and that is what makes the recipe possible.**
  `client_secret_encrypted` is AES-GCM ciphertext produced by `ProviderSecrets`, which nothing
  outside the running application can generate, so a directly-inserted row could never carry one. A
  public client is first-class on every path already — `ProviderSecrets.decrypt` returns `null` for
  it and `OidcClient.exchange` omits the `Authorization` header — and for a browser-driven
  authorization-code flow PKCE is the protection that actually matters. The realm enforces `S256`.
- **Whether `validate` should grow a development-only escape is a DECISION and is deliberately not
  taken here.** Plan 3's LDAP sketch already reserves `allowInsecure` for exactly this shape, so
  there is a house precedent and a name. Adding a security-gate bypass in a documentation phase is
  how one ships.

## One hostname, or the `iss` claim splits in two

The compose service is worth reading before it is edited, because its three settings are one
argument. `KC_HOSTNAME` fixes the `iss` claim and every endpoint the discovery document advertises.
Left to `start-dev`'s per-request resolution, a **browser** arriving on `localhost:8081` and the
**backend** arriving on the compose network would be told two different issuers, and
`OidcIdTokenVerifier`'s `JwtIssuerValidator` would refuse the `id_token` — after a sign-in that
looked like it worked.

So one URL has to work from both sides, and it does: the container carries a **network alias** equal
to the published hostname, and `keycloak.localtest.me` resolves to 127.0.0.1 in public DNS, so the
browser reaches the published port with no `/etc/hosts` edit. Verified end to end — the discovery
document served on the published port and the one served on the compose network are the same bytes
and carry `issuer: http://keycloak.localtest.me:8081/realms/tcms`. Two consequences that look like
style and are not: **the host port and the container port are the same number**, because the port is
inside the `iss` claim, and `sslRequired` is `none`, because Keycloak's default exempts loopback and
the backend does not arrive on loopback. If a resolver blocks rebinding to 127.0.0.1, one variable
(`KEYCLOAK_HOSTNAME`) moves the alias, the issuer and the published port together.

There is **no healthcheck and no `depends_on`**, deliberately: discovery is lazy and per-provider, so
nothing in the application touches the issuer at boot, and a gate there would only slow
`docker compose up` for everyone not testing SSO. The cost of the service being in the default set
rather than behind a `profile` is one more container for every developer, accepted because #37's
whole point is a counterparty that is present without a flag somebody forgets.

## Two collateral repairs in the environment files

Both were found by reading the files this phase edits, and both are older than this slice:

- **`.env.lane-b.example` was missing `TCMS_IDENTITY_SECRET_KEY`.** `compose.yaml` requires it with
  `${VAR:?}`, so **lane B did not start at all** from the moment phase 6 landed — compose fails
  before pulling an image. It also lacked `TCMS_API_BASE_URL`, so lane B would have built its
  `redirect_uri` from lane A's port. Both added; the realm registers `:8080` and `:9080` so one
  export serves both lanes.
- **`compose.yaml`'s header still said `npm run dev` with MSW needs no backend.** The mock layer was
  deleted on 2026-08-06 along with `VITE_API_MODE`. Corrected in place rather than left, per the
  comment discipline: a stale comment is the defect.

## Gates

| Phase | Gate | Result |
|---|---|---|
| 3 | `~/bin/tcms-remote backend ./gradlew build` | **2791 tests / 0 skipped / 0 failures** (+17), the four `ArchitectureRulesTest` failures closed |
| 7 | `~/bin/tcms-remote backend ./gradlew build` | **2791 / 0 / 0**, unchanged — this phase adds no Java byte |
| 7 | `docker compose up keycloak` on an isolated project, then the discovery document from the host **and** from a container on the compose network | realm `tcms` imported, Keycloak 26.4.7 listening, **both sides answer the same `issuer`** |

No `frontend/` or `packages/` gate was run for phase 7: it changes no code in either stack, and
running them would imply the documents were what passed.

## Verification — written and not run (§65)

`docs/verification/backend-mutation-checks.md` **`§217 FD-1`…`FD-15`** and
`docs/verification/frontend-browser-checks.md` **`§217 SO-1`…`SO-7`** (phase 7, lane `L4`).

**Six of the nine phase-3–6 mutation rows are expected GREEN and they are one shape**: this slice
ships an HTTP flow that no test drives over HTTP, against an issuer no test stands up.
`FederatedIdentityServiceTest` (9) and `OidcIdTokenVerifierTest` (8) are real and pure and they sit
either side of an unobserved wire. **`FD-11` is the sharpest**: the verifier is proved to refuse a
wrong nonce, and nothing proves `SsoLoginService` passes the attempt's nonce to it. Two green tests
either side of nothing.

**Keycloak in compose starts to change that and does not finish it.** What it buys is that the flow
is now *drivable* — `SO-2`…`SO-7` exist because there is something to redirect to. What it does not
buy is an automated observer: the integration test that would pin `FD-10`, `FD-11` and `FD-12`
against a real issuer is not written, and a queue row is a person, later.

**`§217 SO-3` is the row to read first.** It is expected to look broken and it is not: the browser
ends at `/sso/callback#token=…` on an SPA with no such route, so a valid session sits in the address
bar unread. That fragment is the specification for the `fe-react` lane.

**`§217 FD-15` and `§218 NC-5` are the same missing rule recorded twice in two days**: nothing here
asserts that a credential is absent from a *response* schema. The pairing rule compares the two sides
to each other, so adding `clientSecret` to both the schema and the view satisfies it. It belongs in
`packages/`, where `client/src/test/` already parses `openapi.yaml` and where `ci.yml` actually runs
the suite. Two slices have now recorded it; the third should build it.

## What is NOT done

- **There is no sign-in screen, and no plan covers one.** `grep -rl` over `frontend/src` finds
  `identity-provider` and `/auth/sso` in **`schema.d.ts` alone**. No provider buttons, no
  `/sso/callback` route, no admin screen for the provider CRUD. The plan's self-review says this is a
  `fe-react` lane and stops there. **Until it exists, a person can complete an external sign-in and
  the product will not let them in** — which is the largest single gap in plan 1.
- **The reference issuer cannot be configured through the product.** See above; the decision is open.
- **No integration test crosses the wire.** `FD-10`…`FD-14` are all recorded as gaps for one reason.
- **`IdentityProviderServiceTest` does not exist.** The CRUD is gated, validated and audited and none
  of it is driven.
- **Plans 2 and 3 are sketches.** Claim → role mapping and LDAP/AD both build on `user_identity` and
  the provider config **with no schema change to them**, which is the property that made this
  sequencing safe — but neither has a written plan, and plan 2 carries at least one thing plan 1
  cannot anticipate: a claim mapping is a **fourth grant path** and must ask the `assignable` question
  `RoleGrantService` owns.
- **RP-initiated logout at the IdP, and per-company `/{companySlug}/login` URLs**, both §6r and both
  deliberately out of plan 1.
- **The Linux lane has not seen this slice as a set.** `tests.yml` runs nightly on the default branch.
