# §204 — The status nothing compared, and the replay nobody could see

**One phase, `packages/` only: the contract seam and its test, `@tcms/client`'s transport options,
its client and its command core, and the CLI's five create arms. No migration, nothing in
`backend/src`, nothing under `frontend/`, no contract change and neither `schema` copy regenerated.**

One phase for §196–§203's reason: the instrument and what it turned up are the same change, and this
time the thing it turned up is not what it was built to find.

§203's first opened item, taken verbatim:

> **The seam does not compare the *status* a double answers with the ones the operation declares.** A
> stub answering `200` where the contract declares only `201` is compared against the right schema and
> reported as fine — several in this tree do exactly that.

## The check, and the sentence in it that was wrong

`contractExchange.ts` already had the status: `stubFetch` and the two `api(routes)` harnesses each
hand it one, and `checkServed` used it for a single purpose — skip a non-2xx, because a refusal is
not measured against the success schema. Nothing asked whether the 2xx itself was one the operation
declares.

It does now (`checkStatus`), and **the population is zero.** Across the **44 distinct
method/path/status triples** the three doubles produce, every 2xx is declared. The clause *"several in
this tree do exactly that"* is false, and the reason it is false was four minutes away from the
sentence:

| success set | operations |
|---|---|
| `200` | 78 |
| `204` | 22 |
| `201` | 15 |
| **`200` and `201`** | **16** |
| `200` and `204` | 1 |
| `202` | 1 |

133 operations, of which **116 declare one success status and 17 declare two**. Sixteen of those
seventeen are **creates declaring 200 *and* 201**, because a replayed `Idempotency-Key` answers 200
(§136, §144) — so a stub answering 200 on a create is answering something the server really sends.
(The seventeenth is `GET …/cases/{caseId}/draft`, 200 or 204, and the seam already skipped a 204.)
§156's shape one list forward, and §193's exactly: a claim carried into a candidate list without
re-reading the thing under it.

So the check is a **guard**, which is §201's outcome one instrument over, and it is mutation-tested
rather than merely green: `POST …/reviews/{reviewId}/comments` declares 201 alone, and a 200 on it
reports. Two design points are worth having:

- **Only the success half is asked.** The contract's *error* status sets are known incomplete — §162
  measured them as covering neither 403 nor 413 — so checking them would measure a gap in the
  document rather than a defect in a double, and a refusal is the *subject* of a good many tests here
  (a 502 of HTML from a proxy, the CLI harness's own unstubbed 500 for a route nobody declared).
- **A wrong status stops the body comparison** rather than adding to it, on `checkExchange`'s existing
  rule for an unresolvable path: the schema that would be reached describes a response the double is
  not claiming to be, so reporting both names one defect twice and sends the reader to the fixture
  instead of to the status.

`successStatusesOf` is **outside** the shared region, and that is the rule this slice adds to the
pairing arrangement: **a question only one stack can ask goes outside.** The SPA's double replaces a
method on the `api` object, which answers `{ data }` — there is no status there at all — so a copy of
it in `frontend/src/api/contractBodies.ts` would be an export with no possible caller, which is the
readerless shape §154 and §165 each paid for. `frontend/` therefore does not change in this slice at
all.

## What asking the question found

Asking a seam for the status is what exposed that **nothing in `packages/` reads one.**

`client.ts` ends every call with `return result.data`. openapi-fetch hands it `{ data, error,
response }`; the transport under it has already thrown on any non-2xx, so `error` is dead — and the
status went out with it. That is unremarkable for the 116 operations that declare one success code.
It is not unremarkable for the sixteen creates that declare two, where the contract says in as many
words what the second one means:

> **A replay.** The `Idempotency-Key` had already created this case, so nothing was created by this
> request.

The client dropped the only signal that distinguishes them, one line from where it had it. So a
replay and a create arrived as the same resource, through the same call, with nothing to tell them
apart — **and `@tcms/cli` printed `Created` for both.**

```
$ tcms suite create --name Smoke --idempotency-key batch-7
Created  TS-7 — Smoke
```

Run it again — which is exactly what `--idempotency-key` exists for, and what a retried CI job does —
and the same four words come back for a request that created nothing. It is §141's family: there, a
refusal that did not say *when* to retry produced the loop the limit existed to prevent; here, an
answer that does not say *what happened* costs the caller the one thing the mechanism was built to
give them. A CI log keeps the sentence.

Nothing was making a wrong *decision* on it — no branch anywhere read a success status, so there was
no defect downstream of the word. The word itself was the defect, and it is one a person reads.

## The repair, and where it stops

Three small pieces, each in the place that already owns the question:

1. **`TcmsRequestOptions.observeStatus`** — called with the status of a successful answer, once,
   before the body reaches the caller. A **callback** rather than a returned status because that is
   the shape that survives the spread every call site writes: `tcms: { ...request, ...createIntent(…) }`
   copies the options into a fresh object, so a *field* written by the client would be written onto
   the copy and read off the original. A closure cannot be broken that way.
2. **`createIntent` returns a `CreateIntent`** — the same options it always returned, plus
   `wasReplay()`. On the intent rather than on the response because the intent is the thing that has
   the question: a caller who supplied no key cannot meaningfully be replayed (the CLI generates one
   per invocation, so a second run is a second intent and gets a second suite), and a caller who
   supplied one is by construction retrying. It is a **method** rather than a field so that reading it
   before the answer gives `false` — an intent not yet carried out has not been replayed either —
   rather than `undefined`, which is what a caller would then print.
3. **`outcome(intent, verb)` in `cli/src/writes.ts`** — the first row's label, `Replayed` or the verb.
   Five arms: `case propose` (`Filed`), `suite create`, `doc create`, `plan create`, `run create`.

**`--json` is deliberately untouched.** It prints the resource, and the resource is byte-identical on
a replay — which is the fact the word exists to state, not a gap to fill. Inventing a `replayed` key
in that body would be this client adding a property to a contract-shaped payload, §165's divergence
arriving from the client side, and the machine reader consumes ids anyway.

**The MCP server is deliberately untouched too, and the boundary is measurable rather than
convenient.** Its eight create arms answer `ok(resource)` — pretty-printed JSON and no prose, by
`result.ts`'s argument that the reader is a model composing its next call — so a model gets the same
resource on a replay as on a create, correctly, and is told nothing false. The CLI is the surface that
states a *word*. `mcp/src/writes.ts` carries the note, including what it would have to do if a tool
ever grows prose.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses.

| | |
|---|---|
| `packages`: `npm run build` | ✅ client → cli → mcp, in that order |
| `packages`: `npm run lint --workspaces` | ✅ 0 warnings, 0 errors |
| `packages`: `npm run test:run --workspaces` | ✅ **269 across 13 files** (111 client / 77 cli / 81 mcp), +9 |

**`backend` and `frontend` were not run, and that is a decision rather than an omission** — §197's,
§198's, §201's, §202's and §203's, unchanged. Nothing under `backend/src` or `frontend/` changed, no
migration, no `openapi.yaml`, neither `schema` copy.

Three mutations, each reverted:

| mutation | result |
|---|---|
| `outcome` returns the verb unconditionally | 1 red — the CLI's replay test |
| `client.ts` drops the `observeStatus` call | 1 red — the client's status test |
| a 200 on `POST …/reviews/{reviewId}/comments` | reports, with the operation's declared set named |

The third is how a zero-population guard is shown to work at all, and it is the one that matters:
without it the only evidence would be that nothing failed, which is what a check that reads nothing
also looks like.

## Verification

**Two rows, and they go where §203's went** — `packages/` still has no persistent queue, so they are
here beside `§203 PL-1`, which is still ⬜ NOT RUN.

> **`§204 PL-2`** — against a real instance, run
> `tcms suite create --name Smoke --idempotency-key $(uuidgen)` and confirm `Created`; run the
> **identical command with the same key** and confirm `Replayed`, the same `TS-…` key, and that
> `tcms suite list` shows one suite rather than two. The second half is the assertion — a stub can
> answer 200 to anything, and only a real server decides to. ⬜ NOT RUN
>
> **`§204 PL-3`** — the same pair with `--json` on both, confirming the two bodies are byte-identical.
> The decision not to put the replay in the JSON is only defensible if the resource really is the
> same resource. ⬜ NOT RUN

No browser row: neither a CLI nor an MCP server is a browser lane (surface spec §9). No backend
mutation row — nothing in `backend/` moved.

## The next slice

§203's list, minus what this took, plus what this opened.

**Opened here:**

- **`observeStatus` has exactly one reader and the other fifteen creates in `packages/` ignore it.**
  The MCP's eight create arms are a *decision* (above, with its reason in the file). The remaining
  ones are not a decision, they are simply not asked: `doc link`, `run clone`, `work-item create` and
  `report generate` on the MCP side declare `kind: 'create'` and none of them says a word about what
  happened either way. If a tool ever answers in prose, this is the fact it has to carry — and
  nothing enumerates the arms that would need to.
- **`WriteKind` says a create carries an `Idempotency-Key`, and sixteen operations of the seventeen
  say what a replay answers — but the registry cannot see which.** `writeConformance.test.ts` asserts
  each arm *sends* the header; nothing asserts the arm can *read* the answer. The surface spec §8.3
  asked for coverage over the registry rather than per tool, and this is the half of it that arrived
  after the check was written.
- **The success-status question has an unasked twin: `Location`.** The contract's 201s are the arms
  that could carry one, and this slice looked at none of them. Whether they do, and whether anything
  reads it, is the same shape one header over — and §143's `201 with a Location that 404s` note in
  `schema.ts` says somebody has already thought about it once.

**Carried forward:** the two `api(routes)` harnesses are still near-copies and the MCP one's header
still says it *is* the CLI's (§203); two fixture modules over one contract and nothing pairs them
(§203); nothing counts how many tests mount a boundary (§201); `app/queryClient.ts` and
`test/queryClient.ts` both report and nothing pairs them (§202); a hatch is a per-test declaration and
nothing reads the 44 that say *"this test refuses a write"* (§202); resume the browser queue at
`A2 — plans`; **a third `packages/` live pass**, which now has `§203 PL-1`, `§204 PL-2` and
`§204 PL-3` waiting in it; the snapshot has no reader (§193); a clone cannot file the copy nowhere
(§195); nothing pairs the two tsconfig lists (§196); the undeclared-property question is off under
`additionalProperties` (§198); `NEEDS_A_SAVE` has no producer and never had a test (§200); a stub is
`INCOMPLETE` only once approved and nothing tells its author before then (§200); the read-only archive
decision §159 did not take; serving the relabel audit (§174); `CurrentUser.teams` has no reader
(§175); a run has no edit endpoint (§177); §184's, §185's, §188's, §189's and §190's items.
