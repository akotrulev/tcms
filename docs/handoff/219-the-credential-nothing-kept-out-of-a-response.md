# §219 — The credential nothing kept out of a response, and the gate that stays

**Two commits, no production code.** Lane A and lane D-part-1 of
`docs/plans/2026-08-15-m4-external-identity-closing-plan-1.md`. One builds a rule two slices had
recorded and neither had written; the other records a decision §217 raised and deferred.

## 1. The rule two slices recorded and neither built

`§217 FD-15` and `§218 NC-5` are **the same finding, filed twice, two days apart**: *nothing in this
repository asserts that a credential is absent from a response schema.*

The thing that looks like the observer is not one.
`ContractConventionTest.pairedSchemasAndRecordsNameTheSameMembers` compares the OpenAPI schema and
its Java record **to each other**, so adding `clientSecret` to *both* satisfies it. What actually
stood in for a check was three prose statements — `NotificationChannelView`'s header,
`NotificationChannel`'s schema description, `IdentityProviderView`'s — and one type-level obstacle,
the entity having no plaintext accessor.

`packages/client/src/test/responseCredentials.test.ts` is the rule. It walks **from responses**,
follows `$ref` transitively, and fails on a credential-shaped **`type: string`** property.

**Three narrowings, and each one is what keeps the by-decision map readable:**

- **Responses only.** `IdentityProviderInput.clientSecret` and `NotificationChannelInput.secret` are
  credentials travelling the direction they are *supposed* to. A rule over every schema reddens on
  both, and a map full of entries that teach the reader nothing is a map the next real finding lands
  in unnoticed.
- **Strings only.** `IdentityProvider.clientSecretSet` and `InvitationPreview.passwordRequired` are
  booleans and cannot carry a secret. That is a **structural** exemption, not a listed one — which
  is why neither appears in the map at all.
- **Bodies only.** The one credential this API knowingly puts outside a body is the SSO session in
  the URL fragment, and it is a fragment *precisely* so it never reaches a server (§6r). `§217 SO-4`
  is the observation that covers it, from the only place it is observable — a browser.

Five entries survive as decisions with reasons: `AuthResult.token` (the response that exists to
issue one), `IssuedApiToken.secret` (shown once, recoverable never — its own type so no read path is
one forgotten null from serving it), `ApiToken.tokenPrefix` and
`ExecutionHistoryEntry.executedByToken` (both authenticate nothing), and
`NotificationChannel.secretHint` (four end-anchored characters).

It lives in `packages/` for §215's reason: a question two documents on disk can answer needs no
database, and **`ci.yml` runs the `packages` suite and does not run the backend's.**

`contractBodies.ts` grew one exported accessor, `contractDocument()`, **outside** the marked
byte-identical region — for `successStatusesOf`'s reason taken one step further: the SPA's copy
checks one exchange at a time and has no use for the whole document.

**Both mutation rows were driven and both went red**, and the queue says so in place rather than
being rewritten: `§217 FD-15` and `§218 NC-5` are now `✅ RUN 2026-08-15 — now RED`.

## 2. The plain-HTTP issuer decision

§217 phase 7 found the collision and deliberately did not resolve it:
`IdentityProviderService.validate` refuses any `issuerUri` that is not `https://`, and the compose
Keycloak serves `http://keycloak.localtest.me:8081` — so **the reference issuer cannot be registered
through the product**, and the documented workaround is an `INSERT` run as the container superuser.

**The decision is to keep the gate and keep the SQL recipe.** The gate protects the URL the
`jwks_uri` is read from, so an instance that can be talked into an http issuer is one where a machine
on the path nominates the signing keys that decide who signs in. A development-only escape hatch was
considered and rejected: a property that is only safe when a profile is set is one restart away from
being set in production. Plan 3's `allowInsecure` sketch is for an LDAP **bind**, a different
exposure with a different blast radius, and it is not a precedent for this.

The cost is one documented `INSERT` for the one issuer that is deliberately not production, and
`deploy/keycloak/README.md` now carries the cross-reference.

## What this does not do

No production code changed in either commit. The rule is a build gate, not a runtime one — it says
nothing about what a *handler* serves, only about what the contract permits it to. A response
carrying a field the contract does not declare is `contractExchange`'s question, not this one.
