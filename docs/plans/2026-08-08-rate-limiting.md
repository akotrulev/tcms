# Rate limiting — the MCP plan's phase 9

**Date:** 2026-08-08 · **Slice:** §141 · **Stacks:** `backend/` and `packages/` — **no migration**,
one **document-level** contract change, **no frontend code**. **SHIPPED** — see
[`docs/handoff/141-rate-limiting.md`](../handoff/141-rate-limiting.md) for what it found beyond this
plan: `clock.millis()` throws on this application's clock bean, and the counting bucket this
document assumed had to become a GCRA timestamp because a fractional accumulator refuses a caller
that obeys `Retry-After` exactly.

Phase 9 of [`2026-08-08-mcp-server-wip-plan.md`](2026-08-08-mcp-server-wip-plan.md): *"rate limiting
becomes required (an agent looping `search_cases` is real load on the box running Postgres)"* — §4,
under the costs that are not hosting. The policy was decided long before the server existed:
[`2026-07-31-ai-integration-mcp-grounded-generation-and-coverage.md`](2026-07-31-ai-integration-mcp-grounded-generation-and-coverage.md)
§ open question 6 — **"Per token, generous. An agent in a loop against `search_cases` is a realistic
accident."**

Everything §140 shipped is reachable over the public ingress the Caddyfile already records as
internet-facing. This is the slice that puts a bound on it.

## 0. The finding this slice is organised around

**A rate limit whose refusal does not say *when* to retry produces the loop it exists to prevent.**

The caller here is not a person watching a spinner. It is a model, and a model does with a failure
whatever the failure's text suggests. `packages/client`'s `describeRefusal` — the one place a
refusal becomes words for the MCP server — has branches for 403, 404 and 401 and sends everything
else to `default:`, which prints the server's `detail`. A 429 arriving there today reads as an
unexplained failure, and the *correct* recovery for an unexplained failure, in a model that was
asked to survey a project, is to try again. That is the accident, one retry later, with the
refusal in the transcript.

So the limit and its copy are one slice, not two. The server computes the wait, sends it in
`Retry-After` **and** says it in `detail`; the client's 429 branch turns it into an instruction with
a number in it. Neither half is worth shipping alone: a limit with no copy trains the loop, and copy
with no limit describes something that cannot happen.

**The second half of the copy is a fact the transport makes free.** This refusal is written by a
servlet filter that runs *before* dispatch, so a 429 is the one failure in this API that is
guaranteed to have changed nothing. A timed-out write is the opposite — §140's `result.ts` carries a
whole paragraph for it — and telling the two apart is worth a sentence, because "retry it" and "read
the resource first, then decide" are different instructions and the model follows the one it is
given.

## 1. What is limited, and what deliberately is not

| Caller | Limited | Why |
|---|---|---|
| An API token (`tcms_…`) | **yes**, per `api_token.id` | The only caller that can loop. §140's MCP server, §139's CLI and every CI job are all this. |
| A session (a person in the SPA) | **no** | The failure being prevented has a shape a person cannot produce; a person fast enough to trip a generous limit is a person the SPA has no 429 copy for, and adding that copy is a frontend slice this is not. |
| Unauthenticated | **no** | There is no key to count against that an attacker does not control. Volume in front of authentication is the ingress's job — Caddy terminates TLS for `/api/*` already — and doing it here would mean an in-process map keyed by something spoofable. |

**Keyed on the token, not the company and not the IP.** Per company, one agent's loop refuses its
colleagues' CI job — the blast radius of a mistake becomes everybody who shares a client
organisation. Per IP, a CI runner fleet behind one egress address shares a bucket with no
relationship to who issued what, and a runner that moves gets a fresh allowance. The token is the
thing that was issued, the thing that can be revoked, and the thing the loop belongs to.

## 2. The algorithm, and the two limits that were refused

**A token bucket per token**: capacity `burst`, refilling at `requests-per-minute`. A fixed window
was refused for the usual reason — it permits `2 ×` the limit across a boundary and refuses a caller
for where the clock happened to be, so the limit an operator configures is not the limit anybody
gets.

**In memory, in one process, and that is stated rather than hidden.** TCMS is one deployable; a
second replica multiplies every limit by the replica count. That is the known cost of not putting a
counter in Postgres — which would mean a write per request on the hot path, against the database
this limit exists to protect. Recorded in the properties file, not discovered later.

**A separate, tighter limit for writes was designed and refused.** It is the obvious refinement: a
read costs a query and a write costs a permanent row, so an agent looping `tcms_write_case` at the
read rate fills a human's review queue. The reason it is refused is that **the fastest legitimate
writer in this product is a writer**: result submission during a CI run is one `POST` per test, and
a write ceiling tuned to make proposal-spam uncomfortable refuses the one high-volume caller the
product was built for. A limit that breaks ingestion to slow down a queue that a person already
gates by hand is the wrong trade. The single bucket bounds the total load, which is the thing the
box running Postgres actually feels; proposal volume is bounded by `ai_proposal`'s review step
(§138), which is a person, and by nothing on the wire.

**One number for every role.** Not a per-role or per-token allowance — that is the shape of §8
question 3's per-token permission picker, which is recorded there as a future refinement and would
be the natural place for a per-token quota to live too.

## 3. The refusal

`429`, with:

- **`Retry-After`, in delta-seconds, rounded up**, never below `1`. Rounding down is the version
  where a compliant client retries into a second 429 through no fault of its own.
- **An RFC-7807 body** — the same `Problem` shape everything else answers with, naming the wait in
  its `detail` as well as in the header. `@tcms/cli` prints `error.detail` verbatim (§139), so the
  sentence is what a person at a terminal sees.
- **No `X-RateLimit-*` headers.** They are a second, undocumented contract for a client that would
  have to be written to use them, and nothing in `packages/` reads them.

**This is a third copy of the error envelope.** `ApiProblem` (`repository`) and
`InvitationController.ProblemBody` are the first two, and `ApiProblem`'s own header already records
the duplication as deliberate and names the extraction — a `shared` module — as the follow-up. The
third copy is forced twice over: a filter's response never reaches Spring MVC's exception handling,
and `auth` may not import `repository` in any case. It is written down in the same terms rather than
quietly added.

## 4. Where it sits in the chain

```
apiTokenFilter → BearerTokenAuthenticationFilter → permissionVersionFilter
              → rateLimitFilter → companyContextFilter
```

After `PermissionVersionFilter`, so a token whose owner has been deactivated is 401'd rather than
counted; before `CompanyContextFilter`, so a refused request never opens a company or a transaction.
The key comes from `TokenContext`, which `ApiTokenAuthenticationFilter` has already opened around
the rest of the chain — **the fourth reader of that context, and the first that is squarely inside
the purpose its header names**: this reads it to *bound*.

## 5. The contract change is document-level, and that is an argument, not a shortcut

`ContractConventionTest` requires every operation to declare at least one `4xx`, and the convention
that produced it is *declare the honest set per operation*. A 429 is not honest per operation:
**this response is written before routing**, so there is no operation that returns it — the API
refuses before it has decided which operation the caller meant. Enumerating it on all ~88 operations
would assert an operation-level property that does not exist, produce a diff nothing could review,
and leave the 89th operation silently undocumented.

So: a `components.responses.TooManyRequests` naming the shape, and a Conventions bullet in
`info.description` saying which callers can receive it and what the header means. `npm run gen:api`
in both copies, per the working agreement.

## 6. Phases

**Phase 1 — the limiter.** `RateLimitProperties`, the bucket, `RateLimitFilter`, the chain wiring,
`application.yml`, the contract's document-level 429, and the tests: the bucket's refill and
rounding as a plain unit test with a controllable clock, and the wiring over HTTP in
`RateLimitTest` (a token is refused at its ceiling and told how long to wait; a second token is
unaffected; a session is never counted). Gate: `cd backend && ./gradlew build`.

**Phase 2 — the copy.** A `429` branch in `packages/client`'s `describeRefusal`, computing the wait
from `retryAfterMs` and saying that nothing was changed. Gate: `cd packages && npm run build`,
`npm run lint --workspaces`, `npm run test:run --workspaces`.

**Phase 3 — the handoff.** §141's section file, the index row, the orientation cache, this plan's
status, the WIP plan's phase 9 row, and the mutation-check queue rows §65 requires.

## 7. Standing obligations

- **§65.** The rows go in `docs/verification/backend-mutation-checks.md`. Neither the CLI nor a
  remote MCP server is a browser lane, so nothing goes in the frontend queue.
- **Orientation cache.** `docs/agent-context/be-java.md`, in the slice's own commit.
- **Contract.** `openapi.yaml` first, then `npm run gen:api` in `frontend/` **and**
  `packages/client/`, then both sides in one commit.
