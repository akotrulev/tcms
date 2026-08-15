# §221 — The wire nothing crossed, and the nonce nothing carried

**One commit, three new test files, no production code.** Lane B task 4 of
`docs/plans/2026-08-15-m4-external-identity-closing-plan-1.md`.

§217 shipped the OIDC sign-in flow and recorded that **no test crosses the wire**: `§217 FD-10`
through `FD-14` were all *expected GREEN, and that is the finding*. Nothing drove one callback twice,
nothing drove a token from the wrong issuer, and — the one the queue called the worst of the six —
**nothing proved `SsoLoginService` handed the verifier the attempt's own nonce.**

`StubIssuer` is an in-JVM OIDC issuer on a loopback port: discovery document, JWKS, token endpoint.
`SsoFlowIntegrationTest` drives the whole flow against it in 9 tests — a real session issued for a
real signature, the attempt's nonce required of the token, a token from another issuer refused, a
token for another audience refused, a replayed `state` refused, a provider disabled mid-flow
refused, a disabled provider unable to start, **another company's issuer asserting the same `sub`
not reaching this company's user**, and the session landing in the URL **fragment**.

## Two findings the plan did not have

**There are TWO https gates, not one.** The plan knew about `IdentityProviderService.validate` and
designed around it — insert the provider through the *repository*, never through the service.
That was not sufficient: **`OidcDiscovery.forIssuer` refuses a plain-HTTP issuer at runtime**, so a
row inserted straight into the table still could not complete a flow. A test-only loopback exemption
now exists and is **confined to `127.0.0.1`**; every other issuer takes the real gate.

**`OidcDiscovery` had no test at all** — not the gate, not the self-declared-issuer refusal, not the
completeness check, not the cache. `OidcDiscoveryTest` is the 8 tests that close it, and it is what
keeps the loopback exemption honest: a widened exemption reddens there rather than passing silently
in a helper nobody reads.

## The gap that stays

Two **concurrent** callbacks racing one `state` — as opposed to the sequential replay this suite
covers — need two request threads and a shared database, which a stub-issuer test does not arrange.
The conditional `UPDATE` is what really guards it. Stated at `§217 SO-7`'s row rather than dropped.
