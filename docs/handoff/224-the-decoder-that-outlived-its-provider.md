# §224 — The decoder that outlived its provider, and the deep link that died at the login form

**One slice, both stacks, no contract change, no migration.** Four fixes out of a review pass over
the whole SSO surface (§217–§222), requested as "look at the UI and BE for single sign-on, suggest
improvements" and then "implement the fixes". Ran **concurrently with the LDAP lane** (§223-labelled
commits), which owns `ProviderFormDialog.tsx` and the contract — nothing here touches either.

## The four fixes

### 1. `OidcIdTokenVerifier`'s cache keys on the configuration, not the row id

The decoder cache was `ConcurrentMap<UUID, JwtDecoder>` keyed by provider id and never evicted. A
provider is a database row an administrator edits — §222 shipped the screen that edits it — and
keyed by id, the decoder built on the first callback kept the **old issuer, old audience and old
`jwks_uri` until JVM restart**. It also defeated `OidcProperties.discoveryTtl` one class downstream
of where it is honoured: discovery dutifully re-fetched a moved `jwks_uri` and the verifier never
looked at the refreshed value. (Rotation at the *same* URI was fine — Nimbus refetches on an unknown
`kid` — a *changed* URI was not.) The key is now `(issuerUri, clientId, jwksUri)`; an edit arrives
as a key with no decoder yet, the stale entry goes unused, and the key space is bounded the way
`OidcDiscovery`'s cache is. `anEditedProviderIsNotServedItsOldChecks` pins it, driving the cache
with a malformed token that is refused **before any key is fetched**, so the test needs no network.

### 2. `sso_login_attempt` has a deleter — `SsoLoginAttemptPurge`

Nothing ever deleted a row: `consume` stamps, expiry merely stops `isUsable`, and the writer is
**unauthenticated** — every anonymous `GET …/authorize` that resolves an enabled provider inserts
one, so the table `V53`'s comment calls "empty in steady state" grew with crawler traffic. `V53`
even created `sso_login_attempt_expires_idx`, which read like the purge was intended. Hourly fixed
delay, `PT4M` initial (the siblings hold one, two and three minutes — the stagger only works if a
newcomer takes the next slot), one-hour grace so the only durable trace of a failed sign-in stays
readable while somebody is chasing it. **No company loop, unlike every sibling scheduler, and the
class header says why**: the table is instance-level by decision (ERD §0.1), so the one delete sees
every row — copying `OutboxScheduler`'s loop here would be cargo-culting a shape whose reason
points the other way.

### 3. The deep link survives the issuer round trip — `ssoReturnPath.ts`

Local sign-in returns people to `navState.from`; the SSO leg threw it away — the browser leaves the
app entirely, the router state dies with it, and the server-side `state` deliberately carries
nothing a caller supplied — so every SSO sign-in landed on `/` and a shared link to a run died at
the login form for exactly the people whose company uses SSO. `LoginPage` stashes `from` in
**`sessionStorage`** as the provider button hands the browser away; `SsoCallbackPage` consumes it
inside the `started` guard (consuming removes it, and a double-invoked render would read it gone —
the same StrictMode reasoning as the fragment ref, one line up). Two properties are load-bearing:
`sessionStorage` not `localStorage`, because the value belongs to this tab's round trip and must
not ambush an unrelated sign-in later; and the consume **guards for a path** — storage is writable
by anything same-origin, and a stored absolute URL fed to a redirect is an open redirect assembled
out of a convenience.

### 4. Discovery no longer needs a blur that never comes

Three arrivals at the sign-in screen never blur the email field, and blur was the only trigger:

- **the prefilled invitee** (`acceptedEmail`) — the one person guaranteed to arrive with the field
  full and no reason to leave it. Asked once on mount, ref-guarded for StrictMode's second pass;
- **the autofilled submit** — a password manager fills both fields and submits. At a company whose
  accounts exist only through its issuer every local attempt 401s, so the rejected sign-in now
  fires discovery: exactly when the person needs their buttons, they appear beside the one-sentence
  failure;
- and the inverse defect: buttons fetched for one address **persisted while a different address sat
  in the field**. Editing resets the mutation, so the buttons drop until the next blur asks again —
  a button offering the previous address's providers starts a sign-in nobody asked for.

All three go through the same endpoint with the same silence about every unhappy answer (§6r —
nothing new is rendered in any empty or failed case).

## Suggested and deliberately not built

- **Rate-limiting the authorize leg.** `RateLimitFilter` covers authenticated traffic by design and
  the ingress owns the rest; the in-flight LDAP contract promises in-app throttling for its login,
  which will set the house precedent. The purge caps the damage meanwhile.
- **Signing algorithms beyond RS256.** `NimbusJwtDecoder`'s default. An ES256 issuer fails as an
  opaque `sso_failed`; feeding discovery's `id_token_signing_alg_values_supported` into the decoder
  is the fix, and it is a behaviour widening that deserves its own decision.
- **A way to clear a stored secret** (confidential → public client). `applyEdit` keeps on `null`
  and `ProviderSecrets.encrypt` maps `""` to `null`, so the transition is unreachable —
  re-registering under a new key is the workaround. Recorded, not changed.
- **One false comment left in place, for the concurrency rule**: `ProviderFormDialog.tsx` claims the
  contract reads `''` as "a rotation to an empty credential"; the contract's PUT description and the
  server both make `''` a keep. The dialog never sends `''`, so behaviour is right and only the
  comment lies — but the file is the LDAP lane's mid-flight, so the repair is theirs to land.
  **LDAP lane: delete or correct that comment when you are in the file.**

## Gates

Both suites on `shanks` (the Linux lane), per the standing direction that tests run there:

- `frontend/` — build, lint 0, **`npm run test:run` 1420 / 0** (1411 + 9: four sign-in, two
  callback, three `ssoReturnPath`). The macOS run before it failed 8 in `CaseEditorPage.test.tsx` /
  `ReviewPanel.test.tsx` — files this slice does not touch, the §186-family load sensitivity — and
  all 8 are green on shanks.
- `backend/` — **`./gradlew build` is RED on master and was red before this slice**: three classes,
  `ContractConventionTest`, `ContractParameterBindingTest`, `LdapSchemaTest`, every failure naming
  the `POST /auth/ldap/{providerId}/login` surface — the LDAP lane's committed contract running
  ahead of the backend that pairs it, which is that lane's phase sequencing and not this slice's to
  close. The classes this slice touches are green on the same run: `OidcIdTokenVerifierTest` 9/9
  (+1), `SsoLoginAttemptPurgeTest` 2/2 (new), `SsoFlowIntegrationTest` 9/9 (untouched, still
  green).

**Built and unverified in the browser** (§65): `§224 SF-1`…`SF-3` in the browser queue, same lane
and sitting as `§222 SS-*` — note `SS-4`'s "lands on the project list" is now the degenerate case,
recorded there in place. `§224 SP-1`/`SP-2` in the mutation queue; `SP-1` was run by construction,
`SP-2`'s zero-reds prediction is the row.
