# §140 — The MCP server, and the capability summary that cannot be shared

**Date:** 2026-08-08 · **Branch:** `master` · **Commits:** `2bf73ea`, `8f32201`, `268c8e8`, plus this
file's.

The MCP plan's **phase 8**, in three phases. **`packages/` only — no migration, no contract change,
no backend and no frontend code.** A fourth package on the second stack: `packages/mcp`
(`@tcms/mcp`), 21 tools over the client §137 built, with the session, the permission-filtered tool
list and the capability summary §6.2 asked for.

Two front ends now sit on one client and one command core, which is what
[the surface spec](../specs/2026-08-08-mcp-tool-and-command-surface.md) §1 specified: neither
adapter contains a request, and every rule whose divergence would be a defect — an edit that forgot
`If-Match`, a refusal that named a role it should not have — lives in `@tcms/client` once.

---

## 1. The finding: the server object cannot be a singleton

`instructions` is a **constructor argument** of the MCP SDK's `Server`. It is sent once, inside the
`initialize` response, and never re-sent.

This surface puts the capability summary there, and §6.2 gives the reason: a host loads server
instructions at session start *even when tool search defers the tool definitions*, so it is the one
carrier that is present before any tool is discovered. And the deployment is **remote HTTPS** — WIP
plan §4, which reverses §6w open question 5's stdio-first answer, because *"local STDIO servers
cannot be connected directly"* by the Messages API connector.

Those two facts had not been put beside each other. One process, many credentials, and a field
built from one credential that is sent once and never corrected. **A process-wide server object
would hand every later caller the first caller's capability summary** — their name, their email,
their project's key and name, the size of their reach — through a field that reads like
documentation and that nobody would think to audit for tenancy. `tools/list` has the same shape of
problem one level down: it is filtered by what *this* credential holds.

So the session is probed per connection (`session.ts`) and the server is built around the answer
(`server.ts`). The cost is one small object per session. `http.test.ts` puts the server on a real
socket with a TCMS whose answers depend on the bearer, which is the only arrangement in which the
failure shows up as a failing assertion instead of as a plausible-looking string.

It is worth naming what made this cheap to get right and expensive to get wrong: **nothing about a
shared server would have failed a test written the obvious way.** Every tool would work, the
protocol would be correct, and the leak would be in a string that is only wrong when a second
person connects.

---

## 2. The permission answer has two halves and §137 kept one

`GET /me/permissions` answers `{key, qualifier}` pairs. It has since §65, and the contract records
why in as many words: a Test Engineer holds `artifact:edit` at `PLAN_SCOPE`, so a keys-only answer
says *held*, the project-scope control enables, and the server refuses the submit —
*"disable-don't-hide is not expressible against an answer that cannot say 'held, but not here'."*

**`tools/list` is that decision one client over**, and the surface spec reasons about it entirely
in keys. §6w.3 filters the list because *"an agent shown a tool it cannot use will call it, be
refused, and retry"* — §31's disable-don't-hide arriving at the opposite affordance, since an agent
reads a list rather than a screen. A filter computed from keys alone therefore lists a tool for
rows the caller cannot touch and produces exactly the refuse-and-retry loop the filtering exists to
prevent.

**The resolution is not to hide.** `OWN` still lets the caller act on their own rows, so withholding
the tool would withhold something they can do. The tool is listed and its *description* — composed
per session, because the answer is a property of the credential and not of the build — says what it
is narrowed to: *"case:edit covers only rows this user authored. Anything outside that will be
refused, so check before offering it."* That is disable-don't-hide's actual shape rendered for a
reader that reads a list.

### The same endpoint, one package over, printing `[object Object]`

`@tcms/cli`'s `whoami` did this:

```ts
out.line([...permissions.permissions].sort().join('  '));
```

`permissions.permissions` is an array of objects. The default `sort` compares their string forms and
`join` calls `String()` on each, so the command printed `[object Object]` once per permission —
and **exited 0**. It shipped in §137 with **no test at all**; there was no assertion anywhere in
`packages/` that touched `/me/permissions`. It is fixed here, with a test, and it prints the
qualifier too, because `case:edit` and `case:edit (OWN)` are different answers to the question the
command exists to answer.

That is the third §137 defect found by a later slice, after §139's two. All three share a shape:
**the code was exercised by nothing, and the thing it produced was plausible.**

---

## 3. Two arms the spec lists as **Buildable** cannot exist on this transport

Neither is a shortfall in the implementation. Both are decisions taken elsewhere meeting a transport
the spec reasoned about separately.

**Evidence.** §3 specifies `tcms_record_result` as `run:execute` + `result:submit` +
`evidence:attach`. `POST …/evidence` accepts **`multipart/form-data` only** — it takes a file — and
MCP tool arguments are JSON. A model has no bytes to send. So the tool ships the two arms it can and
**declares the two permissions it uses**. Declaring the third would be worse than cosmetic: it would
hide the tool from a caller who can record results and cannot attach evidence, which is the failure
§2's merge rule exists to prevent, arriving through a permission the tool never exercises.

**Assigning a reviewer.** `POST …/reviews/{id}/assignees` needs a `userId` or a `teamId`. Nothing on
this surface can obtain either: §3.3 excludes everything under `/users` and `/teams` because they
are `COMPANY`-scope, and `V40` §6 withholds `company:read` from `Agent` deliberately — *a token is
not a member*. A tool taking a UUID nobody on this surface can look up is a tool that can only be
called with a UUID pasted in by a human, which is the CLI's job. So `tcms_review_verdict` records
verdicts and resolves threads, and does not assign.

This is the **second** time `V40` §6's withholding of `company:read` has removed something the
surface spec asked for; §137 found the first, where §4.5's refusal copy cannot name a role because
the role→permission catalogue is behind the same key. The pattern is worth stating: *the `Agent`
grant was drawn to bound what a credential may do, and it also bounds what a credential may know —
including the things a helpful client wants to know in order to be helpful.*

---

## 4. Smaller findings, each of which changed a line

**The gate is a listing rule and it is not authorization.** `tool.ts` says so at the top, because
the field is one rename away from being read as one. Two live reasons: qualifiers narrow within a
held key, and the permissions are resolved **once, at connect**, so a grant revoked mid-session
leaves a tool listed that the server will refuse. Every handler calls the endpoint and lets the
server decide. A client-side gate that *short-circuited* the call would be a second implementation
of the permission model.

**The connect-time snapshot is a cache with no invalidation, and `tcms_whoami` is its only one.**
§6.2 says the tool *"returns the same summary, **live**"*; the live is load-bearing rather than a
nicety. The tool re-probes, replaces the session's reach, and `server.ts` turns a change in the
*listed set* into `notifications/tools/list_changed`. The capability is advertised because it is
actually emitted — advertising one nothing emits is a claim a host plans around.

**A refusal is a result, not a protocol error.** MCP distinguishes them and the distinction decides
whether the model ever reads the text. A JSON-RPC error means *the call could not be made*; a 403
means the server answered and the answer is what the user should be told. So every refusal comes
back as `isError: true` with content `core/refusal.ts` composed, and the protocol error is reserved
for a request that could not be turned into a call at all.

**The refusal names the permission of the arm that ran.** `tcms_write_case`'s gate is
`[case:create, case:edit]`; an edit refused on `case:create` would send the user to an
administrator for the wrong thing. The dispatcher resolves the arm from the `action` and passes its
permission; `result.ts` takes it as a parameter precisely so it cannot be guessed at the call site.

**A hidden tool refuses without confirming it exists.** Answering *"you lack `case:edit`"* to a call
on an unlisted tool restores exactly the enumeration hiding it removes.

**The idempotency key from the tool-call id deduplicates a transport retry, not a model retry.**
§4.2 says to derive it from the tool-call id and this does. A host re-delivering the same JSON-RPC
request carries the same id, so the create is recognised as a replay; a model that decides to
*call the tool again* issues a new id and the server sees a new intent — which is correct, because a
second call after a visible failure may genuinely be a second intent and the client cannot tell.
What it buys is the case that actually bites an agent: a write whose response was lost.

**§139's `If-Match` rule lands in exactly one place.** Almost every arm here is a delta of named
fields, so a self-read version is truthful; `lockVersion` is accepted anyway and is strictly better,
because the model has one and it also catches a change between the read that informed the edit and
the edit itself. **`blocks` on a doc page is the exception and is *demanded*:** a block omitted from
that array is deleted, so a caller sending blocks is necessarily echoing back content it read
earlier — §139's whole-body case, arriving through a field rather than through a flag.

**Submitting a case for review goes through `POST …/cases/bulk`.** There is no per-case status
endpoint and `TestCaseInput` carries no `lifecycleStatus`, deliberately. §3.3 refuses a tool that
*wraps bulk* because its gate is decided by its payload — this is not that: the action is fixed at
`SUBMIT_FOR_REVIEW`, whose gate is `case:edit` unconditionally, so nothing about the permission
depends on what the caller sent.

**Four of the six list tools page over a response that already arrived whole.** §4.6 asks for a
default page size of 25 on every list tool; suites, plans, runs and doc pages have **no pagination
on the wire at all** (`core/resolve.ts` records the same gap from the other side). So `page`/`size`
there is a client-side window: it bounds the tokens the model sees, which is what the budget is for,
and buys the server nothing. The result says `truncated` rather than `more`, because implying a
next page the endpoint does not have would be worse than saying so.

---

## 5. What shipped

| Phase | Commit | Contents |
|---|---|---|
| 1 | `2bf73ea` | The package, the session and its probe, the permission-filtered listing, the capability summary, `tcms_whoami`, the ten read tools, both transports. Plus the CLI `whoami` fix. |
| 2 | `8f32201` | The ten write tools, and §8.3's header conformance over the registry. |
| 3 | `268c8e8` | The protocol end to end over the SDK's in-memory pair, §8.4's refusal-copy check, and the isolation claim under a real socket. |

**Transports:** HTTP (`--http`) is the deployment and stdio is the developer's loop. Both build the
same server over the same session, so a tool that behaved differently in one of them would be a
defect rather than a mode. TLS is deliberately not in this process — it sits behind the Caddy that
already terminates it for `/api/*` — and it binds loopback by default, because a process that binds
`0.0.0.0` is reachable without the proxy the moment a firewall rule is wrong, and this one carries
other people's credentials.

**Gates:** `cd packages && npm run build` (the root script, which now names `mcp` **third** —
alphabetical order would have put it before the client it is built on, which is §139's defect
waiting to recur), `npm run lint --workspaces` 0 warnings, `npm run test:run --workspaces`
**209 tests** (73 client, 63 cli, 73 mcp).

---

## 6. What is not done

**Built and unverified.** Ten mutation rows `MS-1` … `MS-10` are in
[`docs/verification/backend-mutation-checks.md`](../verification/backend-mutation-checks.md); none
has been run. No browser rows — an MCP server is not a browser lane.

**Nothing here has spoken to a real TCMS, and the gap is now three packages wide.** Every test in
`packages/` drives a stubbed `fetch`, and a stub that agrees with the contract is the same shape of
observer as the mock layer §107 deleted: it cannot see the server disagreeing with its own
`openapi.yaml`. §137 opened this gap with nine read commands, §139 widened it with sixteen writes,
and this adds 21 tools with ten write tools among them. **One setup still closes all of it** —
`docker compose up`, `provision-company`, issue an `Agent` token, export `TCMS_URL` and
`TCMS_TOKEN`, then run the CLI and point an MCP host at `--http`. A person is needed only because a
company must be provisioned.

**Phases 9 and 10 remain**: rate limiting on the REST side (an agent looping `tcms_search_cases` is
real load on the box that also runs Postgres), and the deploy — image, compose, Caddy route,
runbook. `MAX_BODY_BYTES` here is the one limit that could not wait for phase 9, because without it
a single unauthenticated POST holds as much of the box's memory as it likes.

**OAuth 2.1 is not here and §4 says it is a slice of its own.** Auth is a static bearer, forwarded
rather than validated: this server builds a client with it and asks TCMS who it is, which is what
keeps *"is this token usable"* and *"what may it do"* in the one place that owns them, and is why a
revoked token and an unknown one are indistinguishable here.
