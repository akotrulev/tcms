# §220 — The CRUD that was gated, validated, audited and undriven

**One commit, one new test class, no production code.** Lane B task 3 of
`docs/plans/2026-08-15-m4-external-identity-closing-plan-1.md`.

§217 shipped the whole `/identity-providers` surface — five operations, a `company:idp` gate on
every one, validation, an audit row per write — and recorded in its own *What is NOT done* list that
**`IdentityProviderServiceTest` does not exist**. `§217 FD-14`'s mutation row said the same thing
from the other side: *expected GREEN, and that is the finding*.

`IdentityProviderEndpointTest` is the 17 tests that close it.

## It is an endpoint test, not a service test, and the plan was corrected on this

The plan named it `IdentityProviderServiceTest` and wrote its assertions with
`assertThatThrownBy`. **The service throws nothing** — it returns a sealed `Outcome`, and that
`Outcome` only becomes a status code in the controller. A service-level test would therefore assert
that a refusal is a `Denied` value and say nothing at all about whether the endpoint answers 403,
404 or 200 with an empty list — which is exactly the class of defect `§218 NC-2` records as an open
gap on the neighbouring surface. Driven over `MockMvc` instead, which is why it belongs to the
`qa-api-java` lane and why the class is named for the endpoint.

The plan also asserted `422` for validation failures. **The contract and the controller both say
400.**

## What the 17 assert

**The gate, on all five operations** — a plain member is refused everywhere, and the list is
**refused rather than filtered to empty**: an empty array would tell somebody who may not ask that
this company has no providers. The gate runs *before* validation on register, so a caller without
the key cannot probe the validator.

**The lifecycle** — registration always produces `enabled = false`, a disabled provider is
**listed** (a list that hid them could not be used to enable one), and enabling is a second act on
the edit path.

**The credential, from four directions** — absent from every read path, `clientSecretSet` standing
in for it, absent from the **audit row**, and omission-on-an-edit being a *keep* rather than a clear.
That last one is the line whose failure has no error and no symptom: the provider goes on reading as
configured and every sign-in through it stops.

**The refusals** — a plain-HTTP issuer, a malformed key, and another company's provider answering
**404 rather than 403** on both read and edit.

**The domains** — lower-cased and replaced wholesale, an address refused where a bare domain is
wanted, and **a domain may be claimed twice**, by two companies, deliberately: a rejection would leak
that somebody else already had it.

## What it does not reach

Nothing here drives the *sign-in* flow — that is §221. And `§217 FD-15`'s mutation (adding
`clientSecret` to the view and the schema) is still not observed by this class; it is observed by
§219's rule in `packages/`, which is a different stack and a different job.
