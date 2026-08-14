# §203 — The bodies `packages/` never compared, and the verdict no caller could send

**One phase, `packages/` plus one file in `frontend/`: five new files under
`packages/client/src/test/`, the three `fetch` doubles, eight test files, and one property of one MCP
tool's input schema. No migration, nothing in `backend/src`, no contract change, no `schema` copy
regenerated.** The `frontend/` file is `src/api/contractBodies.ts` and the edit is sentinels plus
three lines of strictness — no behaviour, and the reason is below.

One phase for §196–§202's reason, restated because it applies unchanged: the check and the repairs it
demands are the same change, and a commit holding only one half is a commit whose gates say nothing
about the other. Here the repair set is 118 tests.

§202's largest carried item, taken verbatim:

> **`packages/` still mocks its client with no comparison at all** (§196–§199, and the largest
> carried item)

## The population, measured before anything was repaired

The seam went in and **118 of 241 tests went red across 8 of 10 files.**

| | red | of |
|---|---|---|
| `@tcms/client` | 18 | 85 |
| `@tcms/cli` | 45 | 75 |
| `@tcms/mcp` | 55 | 81 |

That is a larger share than §197 found in the SPA (251 of ~1300), and it arrives through a different
door. Over there the fixtures were unconstrained because `as never` erased their shape; **here no
shape was ever claimed** — an `api(routes)` entry is `{ status: number; body?: unknown }`, so a body
is an object literal typed as nothing at all, and every one of them was a claim about the server that
nothing had ever read back.

## The one defect in shipped code

**`tcms_review_verdict` told every caller to send a verdict this API does not have.**
`mcp/src/writes.ts` declared

```ts
verdict: { type: 'string', description: 'For `verdict`: APPROVED or CHANGES_REQUESTED.' },
```

and `ReviewParticipationInput.verdict` is `enum: [LGTM, CHANGES_REQUESTED]`. The value is forwarded
verbatim — `requireStr(input, 'verdict')`, cast to the input type — so **the description was the
entire contract the caller had**, and the word a model would most obviously choose for *"I approve"*
earned a 400 from the server. The tool's own `description`, two lines above, says *"A verdict is LGTM
or changes-requested"*: the surface disagreed with itself in one file, and the test pinned the wrong
half.

Repaired as an `enum` rather than as corrected prose. An MCP host validates the input schema before
the call, so a wrong member is now refused at the host instead of at the server, and the next word
that drifts fails against the document rather than in a sentence nobody re-reads.

**This is §182's shape** — a surface naming a state the product cannot produce — reached by §199's
instrument, from the request side.

## What the fixtures were claiming

Everything else the check found is a fixture, and the interesting thing about them is how *reasonable*
each one looks:

- **`ME` carried `status: 'ACTIVE'`.** `CurrentUser` has never declared it. It sat in the MCP harness
  in front of **91 tests** — not a typo, a field somebody reasonably expected `/me` to have.
- **`stepFormat: 'STEPS'`**, in eight tests. `StepFormat` is `STRUCTURED | GHERKIN`. The sharp part is
  that this repo had **already sent that exact value at a real server and been refused**: the round-2
  `packages/` live pass (2026-08-09) records *"a `POST …/cases` carrying `"stepFormat": "STEPS"` (not
  a value this API has) answered 400"* as a note. The live pass found it, wrote it down, and the
  fixtures that were supposed to be the cheap way of noticing kept saying it for two more weeks.
- **`StatusKind` as `PASS` / `FAIL`.** Neither is a member; the members are `PASSED` / `FAILED`.
  `reads.ts` prints `entry.kind` verbatim, so the column would have read one word in the tests and a
  different one in the field.
- **`GET /me` answered `{ id, email, name, companyId }`** in `client.test.ts` — two properties the
  contract has never had and three of its required five missing.
- **Four list endpoints were served their own detail shape.** `/cases` served whole `TestCase`s where
  `TestCaseSummary` is declared (24 undeclared properties per row); `/doc-pages` served `DocPage`;
  `/runs` served `TestRunDetail`; and the run detail's `executions` served full `TestExecution`s where
  the contract says `TestExecutionSummary`. Each of those made a list look like something a caller
  could edit from.
- **`POST /cases/bulk` answered `{ changed: 1 }`**, where `changed` is an array of keys. The only
  question the check asks that catches a *type*, and it caught the one that would have made a
  caller's `.length` read `undefined`.

## The seam is simpler here, and it can ask one question the SPA's cannot

Over there the double replaces a method on the `api` object, so `contractDoubles.ts` has to hijack
`vi.spyOn` to find out what was served. **Here the double is a `fetch`** — URL, method, request body
and response body are one argument list — so `checkExchange` is called from three stubs and that is
the whole installation.

What that buys is the **status**. A 400 does not carry the success schema, so only a 2xx answer is
compared against it; a refusal is left alone. Without that rule every error-path test in the tree
would report as a defect, which is the failure mode that gets a check switched off. `checkServed` also
passes on a 204, and *reports* rather than skips an operation whose success answer declares no JSON
body at all — a double that served one for it is answering a question the contract says has no answer.

## One hatch, and the second one the tree turned out not to need

`drivingTheTransport()` suspends the comparison for a test whose subject is the request **envelope**:
an `Idempotency-Key`, an `If-Match` of zero, a timeout, a cancellation. **Four tests take it**, all in
`http.test.ts`, each naming what it asserts on.

`frontend/`'s `servingADriftedBody()` has **no counterpart here, and the population is zero rather
than the hatch being missing**: every drift test in this package answers a non-2xx — a 401 with an
empty body, a 502 of HTML, a 400 whose `errors` map is the assertion — and the status rule already
passes those. A hatch with no callers is a way to disarm the check that nothing has yet had a reason
to use.

§202's argument is why the one that exists is named for its population rather than for the act of
excusing. A general-purpose `skipTheContractCheck()` would let the dullest group in the tree turn the
instrument off in exactly the place it was built for: a test asserting on a header has said *nothing
whatever* about whether the body beside it was one the server could send.

## The walker exists twice and something now pairs them

The comparison is `frontend/src/api/contractBodies.ts`, and neither stack can import the other's:
separate npm roots, a Vite-only `?raw` import on that side, and two CI jobs each installing a tree the
other never sees. An import in either direction breaks the other stack's build rather than sharing
anything.

So it is copied — and **paired**. Both files carry a `SHARED REGION` sentinel pair, the text between
them is identical character for character, and `contractPairing.test.ts` compares the two slices line
by line and names the first that differs. That is the trade the two `schema.d.ts` copies already make,
checked the same way (`schemaFreshness.test.ts`, one directory up), and it is here because **a copy
nothing pairs is §196's two tsconfig lists** — the shape this repo keeps paying for. Mutation-tested
both directions: changing `depth > 8` to `depth > 9` in the SPA copy reddens it, reverted.

Making that possible cost the SPA copy three lines, none of them behaviour: the region has to compile
under `packages/`'s `noUncheckedIndexedAccess` and `exactOptionalPropertyTypes`, which the frontend
project does not set. What is deliberately **outside** the region is how each side obtains the
document and — here only — the `/api/v1` prefix every URL this stack sends carries. Getting that
second one wrong would not look like a failure: every URL would resolve to nothing, every request
would report *"resolves to no path in openapi.yaml"*, and a reader would conclude the contract had
moved. It has its own test.

## The guard this slice created the need for

`contractExchange.test.ts` is two halves. The first drains the collection and asserts on it directly,
because everything the seam does it does by **failing**, which is the one outcome a green suite cannot
contain — without it the only evidence the instrument works would be that 118 tests once went red,
which is a fact about a moment rather than a property of the tree.

The second is §202's `createTestQueryClient` guard, one instrument over: a test file that builds its
own `fetch` is invisible to the seam and looks **exactly** like a file in which every body was fine.
So the list of test files doing that must be empty — asserted as a list rather than a count, so the
failure names the file — and the three doubles must each still call `checkExchange`.

## `fixtures.ts`, and why the repair was not 118 edits

A factory per resource, complete by construction, `Partial<T>` in and `T` out, return-typed against the
generated contract type. The alternative was repairing each literal where it stood, which would have
produced the same population again next slice: the reason they were incomplete is that writing a whole
resource by hand is tedious and a test only reads three fields of it.

Typing them is deliberate redundancy on top of the runtime check, and it is §196's rule one stack over:
the compiler catches a field the contract removes, the seam catches a *value* the contract does not
admit, and neither sees what the other does. Defaults are the server's answer for an **empty**
resource — arrays empty, nullable scalars null, counts zero — because a fixture that ships a populated
`healthSignals` makes every test that never mentions health assert against a case that has some.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses.

| | |
|---|---|
| `packages`: `npm run build` | ✅ client → cli → mcp, in that order |
| `packages`: `npm run lint` | ✅ 0 warnings, 0 errors, 57 files |
| `packages`: `npm run test:run --workspaces` | ✅ **260 across 13 files** (104 client / 75 cli / 81 mcp), +19 |
| `frontend`: `npm run build` | ✅ |
| `frontend`: `npm run lint` | ✅ 0 warnings, 0 errors |
| `frontend`: `npm run test:run` | ✅ **1339 across 100 files**, unchanged |

**`backend` was not run, and that is a decision rather than an omission** — §197's, §198's, §201's and
§202's, unchanged. Nothing under `backend/src` changed, no migration, no `openapi.yaml`, neither
`schema` copy.

The measured reds are kept above because they are the finding: **118 failed / 8 files** with the seam
installed and nothing repaired. The instrument was mutation-tested end to end afterwards by putting
`status: 'ACTIVE'` back on `ME` — 55 of 81 MCP tests red, reverted.

## Verification

**One row, and it has nowhere standing to go.** `packages/` has no persistent queue: each live pass
has been its own document (`docs/verification/2026-08-08-…`, `…-08-09-…-round-2.md`), and the third
pass is a carried item rather than a file. So the row is here:

> **`§203 PL-1`** — against a real instance, call `tcms_review_verdict` with `verdict: 'LGTM'` on an
> open review and confirm a 201 carrying the re-rendered `Review`; then call it with `'APPROVED'` and
> confirm the **host** refuses it against the input schema, without a request leaving. The first half
> is the repair; the second is the reason it was declared as an `enum` rather than fixed in prose.
> ⬜ NOT RUN

No browser row and no backend-mutation row. Every other changed file is a test, a test harness, or a
module only a harness imports; nothing under `frontend/src` that ships moved, and the SPA's built
chunk sizes are unmoved.

## The next slice

§202's list, minus what this took, plus what this opened.

**Opened here:**

- **The seam does not compare the *status* a double answers with the ones the operation declares.** A
  stub answering `200` where the contract declares only `201` is compared against the right schema and
  reported as fine — several in this tree do exactly that. It is a seventh question and a cheap one,
  and it was left out rather than added quietly because it would have reddened a set of tests this
  slice had no measurement for.
- **The two `api(routes)` harnesses are still near-copies.** `mcp/src/test/harness.ts` opens by saying
  *"The stub is the CLI's, one package over"* — it is not; it is a fork with an origin passthrough and
  a `set`. §152's shape, in a comment. What they now share is the one thing that matters for a body
  being right, which is why this slice did not merge them.
- **Two fixture modules over one contract, and nothing pairs them.** `frontend/src/test/fixtures.ts`
  and `packages/client/src/test/fixtures.ts` describe the same resources with the same defaults, by
  hand, in two trees. The *walker* is paired and these are not — a factory is legitimately per-stack
  (different ids, different framing), so the pairing question here is narrower than the walker's and
  has not been asked.

**Carried forward:** nothing counts how many tests mount a boundary (§201); `app/queryClient.ts` and
`test/queryClient.ts` both report and nothing pairs them (§202); a hatch is a per-test declaration and
nothing reads the 44 that say *"this test refuses a write"* (§202); resume the browser queue at
`A2 — plans`; **a third `packages/` live pass**, which now has `§203 PL-1` waiting in it; the snapshot
has no reader (§193); a clone cannot file the copy nowhere (§195); nothing pairs the two tsconfig
lists (§196); the undeclared-property question is off under `additionalProperties` (§198);
`NEEDS_A_SAVE` has no producer and never had a test (§200); a stub is `INCOMPLETE` only once approved
and nothing tells its author before then (§200); the read-only archive decision §159 did not take;
serving the relabel audit (§174); `CurrentUser.teams` has no reader (§175); a run has no edit endpoint
(§177); §184's, §185's, §188's, §189's and §190's items.
