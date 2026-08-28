<!-- Copied verbatim from be-shared-api-surface-plan references/validation-catalog.md on 2026-08-27 for initiative 001. api-surface.md §4 states which choices this API makes. -->
# The Standard Validation & Error Catalog

The set every endpoint inherits. Defined **once** per API, referenced per endpoint as **"standard set, plus —"**. An endpoint block lists only what is *additional* or *deliberately omitted*.

Two reasons this is a catalog and not per-endpoint prose: an API where 40 endpoints each describe their own 401 will have 40 slightly different 401s, and a catalog is directly usable as a negative-test matrix by `qa-api-<stack>`.

---

## 0. The error body — one shape for everything

**RFC 9457 `application/problem+json`.** Every failure, including field validation. Never a bare string, never a 200 with `{"success": false}`, never a stack trace.

```json
{
  "type": "https://api.example.com/problems/validation-failed",
  "title": "One or more fields are invalid.",
  "status": 400,
  "detail": "See 'errors' for per-field messages.",
  "instance": "/api/v1/orders",
  "traceId": "00-4bf92f...-01",
  "errors": {
    "lines[0].quantity": ["Must be between 1 and 999."],
    "currency": ["'XYZ' is not a supported currency."]
  }
}
```

Rules:

- **`type` is a stable slug**, versioned with the API. Clients branch on `type`, never on `title` or `detail` text.
- **`traceId` on every error**, taken from the incoming `traceparent` — this is the whole logs↔traces handshake with `observability-engineer-<stack>`.
- **`detail` never leaks internals**: no SQL, no stack traces, no file paths, no upstream vendor error text, no other tenant's data. The internal cause goes to the log, correlated by `traceId`.
- **`errors` is keyed by the client-facing field path** (`lines[0].quantity`), not by the internal property name.
- **One error per failure class, all fields at once.** Return every field error in one response; do not fail fast on the first. A client that has to submit five times to discover five problems is a UX defect that starts here.

---

## 1. Request shape — 400 / 415 / 413

| # | Rule | Status | `type` slug | Note |
|---|---|---|---|---|
| V1.1 | Body is not valid JSON / cannot be parsed | 400 | `malformed-body` | No field-level `errors` — the body could not be read |
| V1.2 | Body missing where required | 400 | `missing-body` | |
| V1.3 | `Content-Type` not `application/json` (or the endpoint's declared type) | 415 | `unsupported-media-type` | |
| V1.4 | Body exceeds the size limit | 413 | `payload-too-large` | State the limit in the plan |
| V1.5 | Unknown fields present | *policy* | `unknown-field` | **Pick one for the whole API:** reject (400, safer — catches client typos and renamed fields) or ignore (tolerant, better for rolling clients). Record the choice; do not mix |
| V1.6 | Path parameter is not the declared type (`{orderId}` is not a uuid) | 400 | `invalid-path-parameter` | **Not 404.** A malformed id is a bad request; a well-formed id that does not exist is a 404 |
| V1.7 | Unknown or malformed query parameter | *policy* | `invalid-query-parameter` | Same choice as V1.5, same answer |

---

## 2. Field validation — 400 (or 422)

Pick **one** status for field validation API-wide: `400` (common, unambiguous) or `422 Unprocessable Content` (reserves 400 for shape errors, as in V1). Record the choice once; never mix.

| # | Rule | Applies to | Message shape |
|---|---|---|---|
| V2.1 | Required field missing or null | every required field | `"<field> is required."` |
| V2.2 | Wrong type | every field | `"<field> must be a <type>."` |
| V2.3 | String length outside min/max | every string | Both bounds stated in the endpoint's field table |
| V2.4 | Number outside range | every numeric | Inclusive/exclusive bounds stated explicitly |
| V2.5 | Value not in the allowed set | every enum | `"'<value>' is not a valid <field>."` — **never** echo the value unescaped into HTML-rendered contexts |
| V2.6 | Format invalid | email, uuid, URL, ISO date, phone, currency code, postcode | Name the format standard in the field table |
| V2.7 | Collection size outside bounds | every array | Empty-array policy stated: is `[]` valid or is `null` required? |
| V2.8 | Date logically invalid | every date | Past-only / future-only / range order (`from <= to`) — a cross-field rule, keyed to the *later* field |
| V2.9 | Whitespace-only string in a required field | every required string | Trim then validate; `"   "` is not a value |
| V2.10 | Precision/scale exceeded | money, decimals | Money is minor units or a fixed scale, per `data-model.md` |
| V2.11 | Cross-field rule violated | stated per endpoint | e.g. `discount <= total`, `endsAt > startsAt`. Keyed to one field, not the whole object |

**Normalization happens before validation and is stated once:** trim strings, lowercase emails, strip formatting from phone numbers, canonicalize unicode. Otherwise `"Bob@x.com "` and `"bob@x.com"` become two accounts.

---

## 3. Authentication — 401

The user's "unauthorized format" set. Every one of these is a **401** with `WWW-Authenticate: Bearer`, and none of them says which one it was:

| # | Condition | `type` slug |
|---|---|---|
| V3.1 | No `Authorization` header | `unauthenticated` |
| V3.2 | Scheme is not `Bearer` | `unauthenticated` |
| V3.3 | Token is not a well-formed JWT | `unauthenticated` |
| V3.4 | Signature invalid, or `kid` not in JWKS | `unauthenticated` |
| V3.5 | Token expired (`exp`) or not yet valid (`nbf`) | `token-expired` — the **one** permitted distinction, because clients must know to refresh |
| V3.6 | `iss` or `aud` does not match the expected values | `unauthenticated` |
| V3.7 | Algorithm not in the allowlist (`none`, or a symmetric alg where asymmetric is expected) | `unauthenticated` |
| V3.8 | Token revoked / session ended | `unauthenticated` |

Rules:

- **401 is uniform on purpose.** "Invalid signature" vs "unknown issuer" tells an attacker how far they got. Expiry is the only useful exception.
- **The response body carries no token content**, not even echoed claims.
- **Clock skew tolerance** is stated once (60s is typical) rather than per endpoint.
- **Anonymous endpoints are listed explicitly** — health, readiness, the OpenAPI document, public webhooks (which have their own signature check instead, see `be-shared-integration-plan`). An endpoint that is anonymous by omission is a finding.

---

## 4. Authorization — 403

| # | Condition | `type` slug | Note |
|---|---|---|---|
| V4.1 | Authenticated, but lacks the endpoint's permission/scope | `forbidden` | Function-level check — **BFLA** |
| V4.2 | Has the permission, but the target object belongs to another tenant | *see V5.3* | Object-level check — **BOLA/IDOR**, the most-missed rule in any API |
| V4.3 | Has the permission, but not for *this* object (not the owner, not assigned) | `forbidden` | |
| V4.4 | Action not permitted in the caller's current state (unverified email, suspended account, expired subscription) | `account-restricted` | Distinct slug — the client can act on it |
| V4.5 | Token valid but issued to a client not allowed to call this endpoint (M2M scope) | `forbidden` | |

Rules:

- **Authorization is checked after the object is loaded**, never before — you cannot know if the caller owns something you have not fetched. Which means the not-found-vs-forbidden decision (V5.3) is unavoidable, not an oversight.
- **Tenant comes from the token, never from the request.** A `tenantId` in a body or query string is either ignored or a 400 — never trusted. Say which.
- **Every endpoint's permission is named in the endpoint table.** "Authenticated users" is a valid answer stated explicitly, never a default reached by omission.

---

## 5. Existence, visibility & state — 404 / 409

The user's "validations for existing data (ids and such)". Three checks, always in this order, for **every** id the request carries — path, query, and body alike:

| # | Rule | Status | `type` slug |
|---|---|---|---|
| V5.1 | **Exists** — the id resolves to a row | 404 (path) / 400 or 422 (body/query reference) | `not-found` / `unknown-reference` |
| V5.2 | **Visible** — the row is in the caller's tenant *and* not soft-deleted | see V5.3 | |
| V5.3 | **Disclosure policy** — an object that exists but is not visible | **404** (default, recommended) or 403 | `not-found` |
| V5.4 | **Valid state** — the row is in a state that permits this operation | 409 | `invalid-state` |
| V5.5 | Nested/child id does not belong to the parent in the path (`/orders/{a}/lines/{b}` where `b` belongs to another order) | 404 | `not-found` |

Rules:

- **V5.3 is one decision for the whole API.** Default to **404 for cross-tenant**: a 403 confirms the id exists, which is an enumeration oracle. Use 403 only where the object's existence is not sensitive and users need the clearer message. Record the choice and its reason.
- **The distinction between V5.1 in a path and in a body matters.** `GET /orders/{missingId}` → 404, the *resource* is absent. `POST /orders {"customerId": "<missing>"}` → 400/422 with a field error, the *request* is invalid — the resource being created is not the thing that is missing.
- **Batch requests state their policy**: all-or-nothing (one bad id fails the whole request) or partial (per-item results with a 207-style body). Never leave it implicit.
- **V5.4 is derived mechanically** from `data-model.md`'s state diagrams: every transition the diagram does *not* draw is a 409 with a message naming the current state and the allowed ones — `"Order is 'closed'; cancel requires 'pending' or 'paid'."`

---

## 6. Conflict & concurrency — 409 / 412 / 428

| # | Condition | Status | `type` slug |
|---|---|---|---|
| V6.1 | Unique constraint violated (duplicate email, order number, slug) | 409 | `duplicate-<resource>` |
| V6.2 | Optimistic-concurrency mismatch — `If-Match` ETag is stale | 412 | `stale-resource` |
| V6.3 | Concurrency required but `If-Match` absent | 428 | `precondition-required` |
| V6.4 | Idempotency key reused with a *different* request body | 409 | `idempotency-conflict` |
| V6.5 | Idempotency key reused with the *same* body | — | Return the original response, same status, same body. Not an error |
| V6.6 | Illegal state transition | 409 | `invalid-state` (V5.4) |
| V6.7 | Business invariant violated (would overdraw, oversell, exceed a quota) | 409 or 422 | Named slug per invariant |

**Every unique constraint in `data-model.md` produces a V6.1 rule** — that mapping is mechanical, and a missing one is a race between the check and the insert. Catch the DB violation and translate it; do not rely on a prior `SELECT` to prevent it.

---

## 7. Rate limiting & abuse — 429

| # | Rule | Note |
|---|---|---|
| V7.1 | Per-identity limit exceeded → 429 + `Retry-After` | State the identity: IP for anonymous, user or tenant or API key for authenticated |
| V7.2 | Sensitive endpoints get their own tighter limits | Login, password reset, token issuance, anything that sends email or SMS, anything expensive |
| V7.3 | Limit headers on every response | `RateLimit-Limit` / `RateLimit-Remaining` / `RateLimit-Reset` |
| V7.4 | Enumeration-sensitive endpoints respond uniformly | Password reset and "check if email exists" return the same response and the same latency whether or not the account exists |

---

## 8. Server-side — 500 / 502 / 503 / 504

| # | Condition | Status | Rule |
|---|---|---|---|
| V8.1 | Unhandled exception | 500 | Generic title, `traceId` present, **nothing else**. The detail is in the log |
| V8.2 | Upstream dependency failed | 502 | Never surface the vendor's error body |
| V8.3 | Dependency unavailable / circuit open | 503 + `Retry-After` | Ties to the resilience policy in `integrations.md` |
| V8.4 | Upstream timed out | 504 | The timeout budget is stated in `integrations.md`, not invented per call site |
| V8.5 | Shutting down / not ready | 503 | Readiness probe is separate from liveness |

**A validation failure is never a 500.** If an unmapped input can crash the handler, that is a missing rule from sections 1–2, not a server error.

---

## 9. List endpoints — the pagination contract

One contract for the whole API, not per endpoint:

| Parameter | Default | Max | Rule |
|---|---|---|---|
| `page` / `cursor` | `1` / absent | — | Page must be ≥ 1; an out-of-range page returns 200 with an empty `items`, **not** 404 |
| `pageSize` | 20 | 100 | Above max → clamp *or* 400. Pick one API-wide |
| `sort` | a stated default | — | Only allowlisted fields; unknown field → 400. **Always tie-break on a unique column** (`id`), or paging is nondeterministic and rows repeat or vanish between pages |
| Filters | none | — | Allowlisted; unknown filter follows the V1.7 policy; a filter on a non-indexed column at scale is a `data-model.md` gap |

Total counts: state whether `total` is exact, approximate, or omitted (cursor pagination usually omits it — an exact count on a large table is a full scan).

---

## 10. Applying the catalog

In `api-surface.md` section 4, state:

1. The choices this catalog leaves open — **V1.5/V1.7** unknown-field policy · **section 2** 400 vs 422 · **V5.3** not-found vs forbidden · **V7.1** rate-limit identity · **section 9** clamp vs reject.
2. The API's permission model (the claim, the naming scheme).
3. The mechanical derivations: every unique constraint → a V6.1 rule; every FK → a V5.1–V5.4 chain; every non-transition in a state diagram → a V5.4/V6.6 rule.

Then, per endpoint, write only **"standard set, plus —"** with a small table of the additions, and state any deliberate omission (a public health endpoint has no section 3).

## Handoff to testing

This catalog *is* the negative-test matrix. `qa-api-<stack>` gets, mechanically: one 401 case per section-3 row, one 403 case per section-4 row (V4.2 is `qa-security`'s BOLA suite), an exists/visible/state triple per id per endpoint, a duplicate case per unique constraint, an illegal-transition case per missing state arrow, and a boundary pair per numeric/length/collection bound (BVA at min−1/min and max/max+1). A rule with no test is either untestable — say so — or missing coverage.


---

## 11. Initiative 001 addendum — mechanical derivations this API adds

Kept beside the catalog rather than inside `api-surface.md` §4 because they are the rules a negative test is generated from.

| # | Derived from | Rule | Status / where it surfaces |
|---|---|---|---|
| I.1 | `condition_imported_key_idx` (data-model §3) | a race on the same Background-step hash inside `APPLY` | 409 `duplicate-imported-key`, whole request rolled back |
| I.2 | `test_case_automation_key_idx` | a race on the same `automationKey` inside `APPLY` | 409 `duplicate-automation-key`, whole request rolled back |
| I.3 | ownership (Q7) | an `UPDATE` row may replace only `PRE` attachments whose condition has a non-null `imported_key`; it may never change a `POST` attachment or a human-attached `PRE` one | not an error — a **test basis**: the negative test attaches a human PRE and a POST, re-imports with `UPDATE`, and asserts both survive at their relative positions |
| I.4 | `ck_test_step_one_data` | a step with two of `testData`/`docString`/`dataTable` | 400 `errors.steps` (DEC-2 R3), never the constraint |
| I.5 | batch policy (§5) | `REFUSE`/`SKIP` rows never fail the request; a constraint violation fails all of it | partial at plan level, all-or-nothing at transaction level |
