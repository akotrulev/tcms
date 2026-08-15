# The reference issuer

`tcms-realm.json` is imported on every `docker compose up` by the `keycloak` service
(`start-dev --import-realm`). Resolved question **#37** made Keycloak the reference IdP
specifically so this lane's counterparty is a real issuer rather than a mock — §217 ships an
HTTP flow that no test drives over HTTP, and six of its nine mutation rows
(`docs/verification/backend-mutation-checks.md` FD-7…FD-15) are expected green for exactly
that reason.

**This is a development fixture. Both passwords are in this file on purpose** — the realm is
bound to 127.0.0.1, holds nothing, and is recreated from the export every time the container
starts. Nothing here is a template for a server.

| | |
|---|---|
| Issuer | `http://keycloak.localtest.me:8081/realms/tcms` |
| Client id | `tcms-backend` — **public**, PKCE `S256` enforced, no secret |
| Users | `kim@acme.test` (email **verified**) · `sam@acme.test` (email **not verified**) |
| Both passwords | `keycloak` |
| Admin console | `http://keycloak.localtest.me:8081/admin` — `admin` / `admin`, needed only to inspect the realm by hand |

## ⚠️ The provider row cannot be created through the product, and that is a finding

`IdentityProviderService.validate` refuses any `issuerUri` that does not start with `https://`,
and it is right to: the discovery document — and therefore the `jwks_uri` every signature is
checked against — is fetched from that URL, so over plain HTTP anyone on the path nominates the
keys that decide who signs in. `§217 FD-14` is the mutation row that pins it.

So this local issuer, which is plain HTTP, **cannot be registered by a Company Admin through
`POST /identity-providers`.** §217 phase 6 shipped that gate and Task 8 of the plan asked for a
plain-HTTP counterparty; neither is wrong on its own and the plan never noticed they collide.
~~Until that is decided, insert the row directly.~~ → **decided 2026-08-15: the gate stays and this
recipe is permanent.** Insert the row directly. **As `postgres`, not as `tcms_owner`** — both
tables carry `FORCE ROW LEVEL SECURITY`, which subjects the owner to its own policies, and an
`INSERT` with no `WITH CHECK` clause is checked against the `USING` predicate, so an owner
session with no `app.current_company` open is refused. The container's superuser is the one
identity that bypasses it:

```bash
docker compose exec postgres psql -U postgres -d tcms
```

```sql
INSERT INTO identity_provider (
    id, company_id, key, kind, display_name, issuer_uri, client_id,
    client_secret_encrypted, jit_provisioning, auto_link_verified_email, enabled
)
SELECT gen_random_uuid(), c.id, 'keycloak', 'OIDC', 'Keycloak (local)',
       'http://keycloak.localtest.me:8081/realms/tcms', 'tcms-backend',
       NULL, FALSE, TRUE, TRUE
FROM company c WHERE c.slug = 'acme';

-- What POST /auth/providers answers from. Without it discovery returns an empty
-- list for kim@acme.test -- correctly, and indistinguishably from a domain no
-- company claims, which is the whole point of that endpoint (§6r).
INSERT INTO company_email_domain (id, company_id, domain)
SELECT gen_random_uuid(), c.id, 'acme.test'
FROM company c WHERE c.slug = 'acme';
```

Why this is an `INSERT` rather than a form: the https gate is deliberate and stays — see
`docs/plans/2026-08-15-m4-external-identity-oidc.md`, self-review.

`auto_link_verified_email` is `TRUE` and `jit_provisioning` is `FALSE` above, which is the
combination the queue rows assume: `kim@acme.test` links to an account that already exists,
`sam@acme.test` is refused for an unverified address, and nobody is provisioned by surprise
(#38's default).

**The client is public — no secret — and that is what makes this recipe possible at all.**
`client_secret_encrypted` is AES-GCM ciphertext produced by `ProviderSecrets`, which nothing
outside the running application can generate, so a directly-inserted row could never carry one.
A public client is a first-class configuration on every path (`ProviderSecrets.decrypt` returns
`null` for it, `OidcClient.exchange` omits the `Authorization` header), and for a browser-driven
authorization-code flow PKCE is the protection that actually matters.

**The decision this leaves open** is whether `validate` should grow a development-only escape —
plan 3's LDAP sketch already reserves `allowInsecure` for the same shape, so there is a house
precedent. That is a decision, not an implementation, and it is deliberately not taken here.

## The three things in the export that are decisions

**`sslRequired: "none"`.** Keycloak's default (`external`) exempts loopback and refuses plain
HTTP to anything else. The backend reaches this container over the compose network by its
alias, which is not loopback, so the default would refuse discovery and the token exchange with
an error that reads as a misconfigured provider rather than as a TLS policy.

**One hostname, used by both sides.** `KC_HOSTNAME` fixes the `iss` claim and the endpoints the
discovery document advertises. Left to `start-dev`'s per-request resolution, a browser arriving
on `localhost:8081` and a backend arriving on the container alias would be told two different
issuers, and `OidcIdTokenVerifier`'s `JwtIssuerValidator` would refuse the result. So compose
gives the container a network alias equal to the published hostname, and
`keycloak.localtest.me` resolves to 127.0.0.1 in public DNS — which is what makes the browser
half work with no `/etc/hosts` edit. **If your resolver strips rebinding to 127.0.0.1**, or you
are offline, set `KEYCLOAK_HOSTNAME` in `.env` to a name you have added to `/etc/hosts`
yourself; the alias, the issuer and the published port all follow that one variable.

**Two users, and the difference between them is `emailVerified`.** `kim@acme.test` is verified
and `sam@acme.test` is not, because the rule that separates them is the one worth a live
counterparty: `FederatedIdentityService` links to an existing account on a verified address and
refuses on an unverified one *even when the provider has `autoLinkVerifiedEmail` on*
(`§217 FD-8`). A realm whose users are all verified cannot drive that refusal, and it is a
refusal no unit test can prove reaches the wire.

Two redirect URIs are registered — `:8080` and `:9080` — so lane B
(`.env.lane-b.example`) works without editing the export.
