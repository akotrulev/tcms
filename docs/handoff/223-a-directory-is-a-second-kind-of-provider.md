# §223 — A directory is a second kind of provider, and the whole difference is a POST

**One slice, both stacks, one migration (`V57`), one new operation:** LDAP / Active Directory as
the second `kind` of `identity_provider`, per `docs/plans/2026-08-15-m4-external-identity-ldap-ad.md`
(D1–D9 carry the decisions — read them there rather than re-deriving from this summary). An OIDC
provider is a *redirect*: the browser leaves and a third party absorbs the credential. A directory
is a *bind*: there is nobody to redirect to, so TCMS collects the person's directory password on
its own sign-in screen and presents it — which is why the transport has no escape hatch, why failed
attempts are throttled per identifier, and why the search filter is escaped. Everything in this
slice follows from that one difference.

## What landed, per phase

- **The transport decision first (D2), before any code depended on it.** No `allowInsecure` flag: a
  URL must be `ldaps://`, or `ldap://` with StartTLS. Argued on its own ground rather than by
  analogy to §219's http-issuer question — what a plain bind exposes is the person's own directory
  password, the credential to the customer's AD and very often to everything else they own. The lab
  needs no exception because UnboundID's in-memory server serves LDAPS, so the tests drive the
  encrypted path production drives.
- **Phase 1 — the contract.** Eight directory columns on `IdentityProvider`, a second credential
  that leaves by the same one door (`bindPasswordSet`, never `bindPassword`), `issuerUri` whose
  meaning follows `kind`, `SsoProviderChoice.kind` so the sign-in screen knows whether a route is a
  button or a password field, and `POST /auth/ldap/{providerId}/login` — a POST and not a redirect.
  Both schema copies regenerated and the frontend adapted in the same commit per the working
  agreement.
- **Phase 2 — `V57`.** `client_id` loses `NOT NULL` so an LDAP row can exist, and
  `ck_identity_provider_shape` replaces it conditionally on `kind` — see "worth carrying" below.
  Discovery (`tcms_resolve_providers_for_domain`) now returns `kind`; dropped and recreated, not
  replaced — also below. Renumbered `V56` → `V57` at integration: §225 landed `V56` while this lane
  was in flight, which is exactly the landing-order rule the plan pre-assigned against.
- **Phase 3 — the entity, validated on its own terms.** A second factory rather than a widened one
  (the two kinds share nine fields and disagree about six; one factory would compile an LDAP
  argument into an OIDC registration), one `applyEdit` (two would each stamp `updatedAt`), and the
  bind password taking the client secret's null-keeps rule for the client secret's reason. Kind-split
  validation; the two directory refusals with no escape hatch are the transport and a filter
  without `{0}`.
- **Phase 4 — the protocol.** `auth/LdapDirectory`, the only class that speaks LDAP — in `auth`
  and not `identity` because `identity` may not reach the network (ArchUnit). It refuses three
  things that look like successes: an empty-password bind, a filter matching twice, a referral. Its
  `environmentFor` is the trust seam (JVM default trust store; tests override; deliberately no
  per-provider PEM column — the truststore note in `deploy/README.md` is the deployment's half).
- **Phase 5 — `auth/BindThrottle`.** Five failures per `(provider, identifier)`, then refuse
  *without binding*. The bound protects the **customer's** directory, not this instance: every
  forwarded wrong password increments their AD lockout counter, so an internet-facing sign-in form
  that forwards freely is a remote lockout tool for the customer's whole staff. `RateLimitFilter`
  cannot do this — it counts API tokens only.
- **Phase 6 — the sign-in.** `LdapController` + `LdapLoginService`: resolve the provider
  (`auth/ProviderResolution`, extracted from `SsoLoginService` because both flows ask the same
  pre-authentication question), throttle-check, bind, resolve-or-provision through the same
  `FederatedIdentityService` the OIDC lane uses, answer `AuthResult`. **Every way it cannot sign
  somebody in is one refusal** — the same empty 401 as a wrong TCMS password, because anything
  naming the directory, the provider or a bind is an oracle for which accounts are federated.
  `TlsTestDirectory` is the test recipe (below), `LdapLoginEndpointTest` drives the whole flow over
  real LDAPS.
- **Phase 7 — the seams recorded, nothing run** (§65): four seams into `be-java.md`, four mutation
  rows (`§223 LD-1`…`LD-4`) into the backend queue.
- **Phase 8 — the Kind select stops being a one-option control.** `ProviderFormDialog` renders the
  directory half: kind is live on a registration and read-only on an edit (§31 disable-don't-hide —
  changing a row's kind is a different provider), the transport and `{0}` checks are duplicated
  client-side *on purpose* so each message can say what a 400 field error cannot, `bindPassword`
  takes `clientSecret`'s never-prefilled / null-keeps discipline, and `clientId` is sent as `null`
  on a directory because `ck_identity_provider_shape` requires it.
- **Phase 9 — one screen, two ways in.** `SsoProviderChoice.kind` does its one job: an OIDC button
  navigates, an LDAP button **switches the form** — heading gains *"Signing in with your …
  password."*, the password label becomes **Directory password**, one pair of inputs reused (the
  email is what discovery routed on), "Use my TCMS password instead" switches back, and editing the
  address drops the mode for the same reason it drops the buttons. `useDirectoryLogin` posts and
  hands the token to `adoptSession`, so `tcms.token` still has exactly one writer (§222). A wrong
  directory password gets the identical one sentence a wrong local password gets.

## The things worth carrying

- **The empty-password bind.** RFC 4513 makes a bind with a name and an empty password an
  *unauthenticated* bind, and most directories answer it with **success**. A sign-in form that
  forwards an empty password therefore authenticates anybody as anybody whose address resolves.
  `LdapDirectory.authenticate` refuses it before any connection is opened, and the test pins the
  refusal against a live server that would have said yes.
- **`objectGUID` is 16 binary bytes, little-endian in its first three fields.** Read as a string it
  is platform-dependent mojibake, and a subject that differs by byte order is a second `app_user`
  on the next sign-in. `guidOf` decodes it to the canonical text form AD's own tools print. This is
  the working half of D3 (key on the directory's immutable identifier, **never** a DN — a DN encodes
  tree position, so an OU move would orphan the account and its authorship).
- **`DROP FUNCTION` before `CREATE`, and the grants go with it.** Postgres refuses to
  `CREATE OR REPLACE` a function whose `RETURNS TABLE` shape changed (42P13), and the drop takes
  the function's grants with it — so `V57` repeats the `REVOKE`/`GRANT`, and forgetting shows up
  only on a **migrated** deployment's login screen (`permission denied for function`) while every
  fresh-container test stays green.
- **A `NOT NULL` is replaced, not dropped.** `client_id` had to admit null for LDAP rows — but a
  dropped `NOT NULL` is dropped for the OIDC rows too, and an OIDC provider with a null client id
  does not fail at insert; it fails at the token exchange, in a deployment, days later, as a
  sign-in that refuses everybody. `ck_identity_provider_shape` restores the guarantee conditionally
  on `kind` and is strictly stronger than what it replaced (it also pins the three columns without
  which a directory search is not constructible). `LdapSchemaTest` drives both arms.
- **Six green tests against a server nothing had ever connected to.** Every refusal in
  `LdapDirectory.authenticate` is an empty `Optional`, and an *unreachable directory* is one of
  them — so a broken test harness is indistinguishable from a correct refusal, and six of seven
  tests were green while every TLS handshake failed. Two silent harness failures stacked: a
  trust-manager-only `SSLUtil` gives the in-memory server **no certificate**, and the convenience
  certificate generator silently drops `localhost` from its SAN list, so JDK LDAPS endpoint
  identification refused the host. `TlsTestDirectory` calls `ManageCertificates` directly with
  explicit `localhost` / `127.0.0.1` SANs. When touching this area, the positive test
  (`aCorrectBindResolvesAnEntry`) is the only one that proves the harness — the negatives prove
  nothing on their own.
- **Boot's `LdapAutoConfiguration` is excluded.** `spring-ldap-core` on the classpath makes Boot
  wire one application-wide `ContextSource` defaulting to `localhost:389`, whose actuator health
  indicator reports the whole instance DOWN for a server this product never talks to. Context
  sources here are per provider row, built in `LdapDirectory` at sign-in time. Do not "fix" a DOWN
  health probe by configuring `spring.ldap.urls`.
- **`spring-boot-starter-ldap` does not exist.** The sketch assumed it; Boot ships
  `spring-boot-starter-data-ldap`, which is the wrong shape (one global directory). The dependency
  is `org.springframework.ldap:spring-ldap-core` alone.

## Deferred, with reasons at the decision

Group search / nested-group resolution (plan 2 owns the model that would read those columns) and
the deprovisioning poll (its own slice; an unreachable directory must never deactivate a company's
staff) — recorded in the plan's scope check, in §6r, and in the OIDC plan's Plan-3 pointer.

## Gates, measured on the Linux lane (shanks)

- **Backend:** `./gradlew build` **2864 tests / 0 failures** — closing the red §224 recorded on
  master (`ContractConventionTest`, `ContractParameterBindingTest`, `LdapSchemaTest`, every failure
  naming `POST /auth/ldap/{providerId}/login` — this lane's committed contract running ahead of its
  backend). The last green master figure before the slice was 2791.
- **Frontend:** `npm run test:run` **1434 across 107** (from 1420 across 107 at §224), build and
  lint (0 warnings) clean.
- **Packages:** build clean, lint 0 warnings, **329 tests** (128 client / 78 cli / 123 mcp); the
  docs phase also removed `deploy/README.md` from `citedFiles.test.ts`'s by-decision map, which
  §226 pinned to fail the moment the file was written — the reminder working, not a defect.

**Built and unverified** (§65): four browser rows (`§223 LD-1`…`LD-4`, frontend queue) and four
mutation drives (`§223 LD-1`…`LD-4`, backend queue) are recorded, not executed — and both row sets
need an LDAP server the compose stack does not have. The options (an `osixia/openldap` service in
`compose.yaml` beside Keycloak, or the lab directory) are recorded at the rows; choosing is the
verification pass's charter, and "not driven, and here is why" is the legitimate outcome where a
silent skip is not.
