# §141 — The per-token limit, and two ways a limit becomes the loop it prevents

**Date:** 2026-08-08 · **Branch:** `master` · **Commits:** `0149ac8`, `37c3104`, plus this file's.

The MCP plan's **phase 9**, in two phases. **`backend/` and `packages/` — no migration, one
document-level contract change, no frontend code.** The plan for it is
[`docs/plans/2026-08-08-rate-limiting.md`](../plans/2026-08-08-rate-limiting.md).

Everything §140 shipped is reachable over the public ingress `deploy/shanks/Caddyfile` already
records as internet-facing, and the caller on the other side of it is a model. The policy was
decided a week before the server existed — the AI plan's open question 6, *"per token, generous. An
agent in a loop against `search_cases` is a realistic accident"* — and this is that sentence,
built.

---

## 1. The finding: a refusal that does not say *when* produces the loop it exists to stop

The caller is not a person watching a spinner. It is a model, and a model does with a failure
whatever the failure's text suggests.

`packages/client`'s `describeRefusal` is the one place a refusal becomes words for the MCP server
(§140 §2 records why it is one place). It had branches for 403, 404 and 401 and sent everything else
to `default:`, which prints the server's `detail`. **A 429 arriving there reads as an unexplained
failure — and the recovery a model picks for an unexplained failure, halfway through surveying a
project, is to try again.** That is the accident, one retry later, with the refusal already in the
transcript.

So the limit and its copy are one slice. The server computes the wait, sends it in `Retry-After`
*and* says it in `detail`; the client's 429 branch turns it into an instruction with a number in it,
**read from the header rather than from the prose** so that the two clients cannot end up sleeping
for different lengths of time. Neither half was worth shipping alone: a limit with no copy trains
the loop, and copy with no limit describes something that cannot happen.

**The second half of the copy is a fact the transport makes free.** This refusal is written by a
servlet filter that runs *before* dispatch, so a 429 is the one failure in this API guaranteed to
have changed nothing. A timed-out write is the opposite — §140's `result.ts` carries a paragraph
for it — and the two instructions are opposite too: *"repeat the call"* against *"read the resource
before deciding"*. A model follows the one it is given.

No wait is invented when the server did not state one. A number here **would be obeyed**, which is
what makes an invented one worse than none.

---

## 2. The second finding: `clock.millis()` throws on this application's clock bean

Not "is imprecise". **Throws — `ArithmeticException: / by zero`, on every call.**

`TcmsApplication.clock()` is `Clock.tick(Clock.systemUTC(), Duration.ofNanos(1000))`, the
microsecond truncation §58 added so that an in-memory `Instant` equals the one Postgres round-trips.
The JDK's `Clock.TickClock.millis()` computes `floorMod(millis, tickNanos / 1_000_000)`, and a
**sub-millisecond tick makes that divisor zero**.

So a bean every module injects has carried, since §58, a method that cannot be called. It was
unreachable because nothing had ever asked the injected clock for milliseconds — every call site
wants an `Instant` to store. The first caller in the codebase found it as three red integration
tests; in production it would have been a **500 on every request past the rate limit**, which is to
say on exactly the requests the slice exists to answer politely.

`ArchitectureRulesTest` bans the call now, beside the `Instant.now()` rule and for a sharper reason:
the older rule prevents a *wrong value*, this one prevents an *exception*, and the correct
replacement is one method call (`clock.instant().toEpochMilli()`).

This is the second time §58's fix has had a consequence nobody saw: the first was that the
truncation is a no-op on macOS, which is why the original defect could only be observed by CI.

---

## 3. The bucket is a timestamp, because the obvious implementation is a floating-point accumulator

The first version of `TokenRateLimiter.Bucket` kept a fractional `available` and added
`elapsed × rate` on every request. It is what almost everybody writes, and **its own test caught it
inside ten minutes**: a caller polling ten times a second earns ten increments of `0.1`, whose sum
is `0.9999999999999999`, so the permit it waited exactly one second for does not arrive.

Read that failure as a behaviour rather than as an arithmetic curiosity: **a caller that obeys
`Retry-After` exactly is refused again.** It is the loop this filter exists to interrupt, produced
from inside the filter, and it would have been invisible under any test that advanced the clock by
a whole second at a time. `fractionsAccumulate` was written for the *truncation* bug in the same
line and failed on the drift instead.

The state is now `nextFreeNanos` — GCRA's theoretical arrival time, one long, integer arithmetic
throughout. It also collapses three questions into one comparison: whether to allow, how long to
wait (the overshoot **is** the wait, so there is no second formula to disagree with the first), and
whether a bucket is idle enough to evict.

---

## 4. What is limited, and the two limits that were designed and refused

| Caller | Limited | Why |
|---|---|---|
| An API token | **yes**, per `api_token.id` | The only caller that can loop: §140's MCP server, §139's CLI, every CI job. |
| A session | **no** | A person clicking cannot produce the shape being prevented, and a person fast enough to trip a generous limit meets a 429 the SPA has no copy for. |
| Unauthenticated | **no** | No key to count against that the caller does not control. That is the ingress's job, and Caddy already terminates TLS for `/api/*`. |

**Keyed on the token, not the company**: per company, one agent's loop refuses its colleagues' CI
job, so the blast radius of one person's mistake becomes everybody sharing a client organisation.
**Not on the address either**: a runner fleet behind one egress shares a bucket with no relationship
to who issued what.

**A tighter write sub-limit was designed and refused, and the reason is worth keeping.** It is the
obvious refinement — a read costs a query, a write costs a permanent row, and an agent looping
`tcms_write_case` fills a person's review queue. It is refused because **the fastest legitimate
writer in this product is a writer**: result submission during a CI run is one `POST` per test, and
a ceiling tuned to make proposal-spam uncomfortable refuses the caller the product was built for.
The single bucket bounds the load the box running Postgres actually feels; proposal volume is bounded
by `ai_proposal`'s review step (§138), which is a person.

**A per-role or per-token allowance was refused too** — that is the shape of the WIP plan's §8
question 3 (the per-token permission picker), and a quota belongs with it rather than ahead of it.

The counter is **in memory, in one process**, which is exact for one deployable and is written into
`application.yml` rather than left to be discovered: a second replica multiplies every limit by the
replica count. Keeping it in Postgres instead would mean a write per request on the hot path against
the database the limit exists to protect.

---

## 5. The contract change is document-level, and that is an argument

`ContractConventionTest` requires every operation to declare at least one `4xx`, and the convention
behind it is *declare the honest set per operation*. **A 429 is not honest per operation: it is
written before routing**, so there is no operation that returns it — the API refuses before it has
decided which operation the caller meant. Enumerating it on ~88 operations would assert an
operation-level property that does not exist, produce a diff nothing could review, and leave the
89th silently undocumented.

So it is `components.responses.TooManyRequests` — **referenced by no operation, deliberately, with
that stated in its own description** — plus a Conventions bullet in `info.description`. Both copies
of the generated schema were regenerated.

---

## 6. Where it sits, and the fourth reader of `TokenContext`

```
apiTokenFilter → BearerTokenAuthenticationFilter → permissionVersionFilter
              → rateLimitFilter → companyContextFilter
```

After `pv`, so a deactivated owner's token is 401'd rather than counted. Before the company filter,
so a refused request opens no company and starts no transaction.

The key comes from `TokenContext` — **the fourth reader, and the first ordinary one**. §132 read it
to attribute and §138 to refuse, both outside the purpose its header names; this reads it to
**bound**, which is the case the type was written for.

---

## 7. Gates

| Gate | State |
|---|---|
| `cd backend && ./gradlew build` | **BUILD SUCCESSFUL in 6m59s — 2302 tests across 487 result classes, 0 failures.** +18 tests, +5 classes: 13 in `TokenRateLimiterTest` (four nested groups), 4 in `RateLimitTest`, 1 ArchUnit rule. |
| `cd packages && npm run build` | clean (`client → cli → mcp`, in that order — §139) |
| `cd packages && npm run lint --workspaces` | 0 warnings |
| `cd packages && npm run test:run --workspaces` | **211 passed** — 75 client (+2), 63 cli, 73 mcp |
| `cd frontend && npm run build` | not run — **no frontend code changed**; the only frontend artifact is the regenerated `schema.d.ts`, which the backend contract test and `packages`' freshness test both cover |

## 8. What this leaves

- **Phase 10 — deploy** (image, compose, Caddy route, runbook) is the MCP plan's last phase and is
  now the only one open. OAuth 2.1 is §4's own slice.
- **The standing gap is unchanged and is still the most valuable thing outstanding**: nothing in
  `packages/` has been run against a real TCMS, and this slice adds the first behaviour a live pass
  would exercise *by accident* — any looping client meets it.
- **§65's rows are in the queue**: `RL-1` … `RL-8` in
  [`backend-mutation-checks.md`](../verification/backend-mutation-checks.md), the first batch there
  that spans both stacks. Nothing goes in the browser queue — neither a CLI nor a remote MCP server
  is a browser lane. **This slice is built and unverified.**
- **`RL-4` is a recorded known gap**: moving the filter after `CompanyContextFilter` reddens
  nothing, because the ordering is a hygiene property rather than a behavioural one and asserting it
  would mean observing a `ThreadLocal` from outside the filter that manages it.
