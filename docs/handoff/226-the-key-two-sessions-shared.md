# §226 — The key two sessions shared

**Date:** 2026-08-16 · **Branch:** `master` · **Scope:** `packages/mcp/` plus one entry in
`packages/client/src/test/citedFiles.test.ts`. **No backend code, no frontend code, no contract
change, no migration.** One review pass over the MCP server (§140, §210–§213), one defect, five
hardenings, all committed together.

## 1. The defect: the idempotency key was scoped to nothing

`server.ts` derived every create arm's `Idempotency-Key` from the JSON-RPC request id alone —
`mcp-<requestId>` — and a request id is a **per-session counter starting near 1**. The server's
dedupe scope is `(company_id, project_id, key)` with no caller in it (`V11`'s index and its
siblings; `CaseCreateService.findByIdempotencyKey(projectId, key)` answers the existing row as a
replay). So the key collided in exactly the two ways that matter:

- **Two concurrent HTTP sessions in one project** — the deployment's normal shape — reach the same
  small integer, and the second caller's create is answered 200 with the **first caller's row**,
  silently: another user's case, presented to the model as the result of its own create. No error
  anywhere, which is the worst grade of failure this repo names.
- **A fresh stdio session counts from 1 again**, so today's create replays yesterday's instead of
  creating anything.

The fix is `Session.idempotencyScope` — one `randomUUID()` minted at connect, prefixed into the
key: `mcp-<scope>-<requestId>`. Constant within a session, so the transport-retry dedupe the header
exists for is untouched (a re-delivered JSON-RPC request keeps its id); unique across sessions, so
the key space is finally per-caller. Backend cap is 200 chars; the composed key is ~45.
`server.test.ts` drives two real clients through the same call sequence — which is precisely the
collision, same request id on both — and asserts the keys differ.

**Why three slices of conformance testing never saw it**: `writeConformance.test.ts` and
`writes.test.ts` inject `callId` directly (`'mcp-7'`) and assert the header *arrives*; nothing ever
asked where the value came from or whether two sessions could mint the same one. A header can be
present, well-formed, conformant — and shared.

## 2. Five hardenings from the same pass

- **`notifications/tools/list_changed` now keys on descriptions, not names** (`server.ts`).
  `describeFor` renders the caller's qualifiers into each tool description, so a re-probe that
  keeps every key but narrows one (`ALL` → `OWN`) changed what a host should show while changing
  nothing a names-only comparison could see. The digest is name + per-session description;
  `listedNames` in `whoami.ts` lost its only caller and is deleted. Tested through a real client's
  notification handler: same keys, one qualifier narrowed, notification received.
- **An empty POST body answered nothing at all** (`http.ts`). `bodyOf` returned bare `undefined`
  for a zero-length body and the initialize path's `if (body === undefined) return` ended the
  handler without writing a response — a request that simply hung. Empty now takes the
  JSON-parse-failure branch: 400. Tested.
- **The oversized-body branch on an existing session answered twice** (`http.ts`). After
  `bodyOf`'s 413 the handler still passed the consumed request to `transport.handleRequest` on an
  ended response; guarded with `res.headersSent`. Tested.
- **The consent prompt now travels with the call's cancellation** (`server.ts`). The delete-arm
  elicitation ran outside the `try` and without `extra.signal`, so a host cancelling the call got
  a protocol error and the prompt stayed pending on a person's screen for an operation nobody was
  waiting on. The gate moved inside the `try` (an abort is now `Cancelled.`, a result, per
  `result.ts`'s rule) and the signal is threaded into `elicitInput`.
- **`--http` shuts down on SIGTERM** (`main.ts`) — the deployment is a container (§142) and a
  container stops with SIGTERM; previously the signal killed the process mid-request. Close the
  listener, let in-flight requests finish, unref'd 5s ceiling for held-open SSE streams.
  **The probe also parallelises `/me` and `/projects`** (`session.ts`) — independent reads on the
  per-connect hot path.

Plus one stale symbol: `tool.ts` cited `consentedTo` in `server.ts`, a function that has been
`withheldConsent` for as long as it has existed — the class of drift §205's rule cannot see
(it checks paths, not symbols).

## 3. Collateral: a citation the LDAP lane wrote ahead of itself

`citedFiles.test.ts` was red on master before this slice: `LdapDirectory.java` (§223 phase 4,
the concurrent LDAP lane's commit) cites `deploy/README.md`, which the LDAP plan's own step 4
lists as an **unwritten deliverable**. Entered in `CITED_BUT_ABSENT_BY_DECISION` with that reason —
the map is pinned both ways, so the moment the LDAP lane writes the file this entry fails and gets
removed, which is the reminder working. The LDAP lane's files themselves are untouched.

## 4. What was recommended and deliberately not built here

From the same review, deferred as a hardening slice of their own because they change observable
HTTP behaviour: **re-verifying the bearer on every request of an existing session** (today the
`mcp-session-id` alone reaches the stored client — possession of the id is possession of the
token's capability until the session ends), and **an idle-TTL + cap on the session map** (a client
that crashes leaks a `Server` per session for the life of the process, on the box that also runs
Postgres). Also recorded: report-generate idempotency is still the known §205 gap — closing it is
a `report_job` column plus replay lookup plus contract change, and it is what makes the keys this
slice fixed worth storing on that table too.

## Gates

On shanks per the standing directive: `packages` build clean, lint 0 warnings across all three
workspaces, tests **78 cli / 128 client / 123 mcp** — mcp +4 (two protocol tests, two HTTP tests),
client's one red (the LDAP citation) turned green by §3. No browser surface and no backend
mutation-behaviour change, so no §65 queue rows.
