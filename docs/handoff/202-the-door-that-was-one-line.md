# §202 — The door that was one line, and the eight tests behind it

**One phase, `frontend/` only: `src/test/queryClient.ts`, `src/test/renderFailures.ts`, its own test
file, and **58 hatch calls across 33 test files** (58 rather than 60 because one of them is an
`it.each` of three). No migration, nothing in `backend/src`, no contract change, no `schema.d.ts`
regeneration, and not one file under `src/` that ships.**

One phase for §196's, §197's, §198's, §199's, §200's and §201's reason, restated because it applies
unchanged: the check and the repairs it demands are the same change, and a commit holding only one
half is a commit whose gates say nothing about the other. Here the repair set turned out to be
opt-outs rather than fixes — see *Every one of the 61 was deliberate* below, which is a result rather
than a shortcut.

§201's own largest opened item, taken verbatim:

> **A region whose *fetch* failed is still invisible, and the reason is one line of
> `createTestQueryClient`.** The measurement is above; the population is unknown because nothing can
> count it yet. Closing it is: report query and mutation failures from the test client into the same
> sink, flip the two `Record` entries, measure how many tests redden, and give the deliberate ones a
> hatch of their own. The thirteen `unmocked read` throws are the part of that population which needs
> no decision at all — a test author who writes one has already declared that reaching it is a bug.

## The door was one sentence doing two jobs

§201 stated the blocker as a design constraint and quoted the reason approvingly:
`createTestQueryClient` installs **no global error handlers**, because *"wiring that here would
require the notification provider in every test tree and assert nothing about the unit under test."*

That sentence is true about **raising a notification** and false about **reporting**. They had been
one bullet in that file's header since it was written, and the conflation is the whole finding: the
app's `onError` does two things in sequence — `reportClientError(error, 'query', …)`, then
`handlers.notify.error(…)` — and only the second needs a provider. The test client can do the first
with no tree, no provider and no behaviour change, because a report goes to `window.__tcmsErrors`
and nowhere else.

So the change to `src/test/queryClient.ts` is a `QueryCache` and a `MutationCache` whose `onError`
reports and stops. It carries **the same context the app carries** — the query key, the mutation key
— because a failure message that cannot name the request names nothing; that is what turns
*"something failed"* into `` the read `["users"]` failed ``, the same way `context.region` is what let
§201 name a panel. `isAbort` is deliberately **not** re-checked here: `reportClientError` drops
cancellations itself, and a second copy of that decision in the harness is a place for the two to
disagree.

## Every one of the 61 was deliberate, and the number is the reason for three hatches

Flipping the two entries reddened **61 tests across 34 files**, and reading all 61 gives the same
answer §201's own half got: **not one of them was an accident.** They split

| | |
|---|---|
| a refused **write** — a 400 landing on the control it names, a 409 the dialog must stay open for, a §6g gate refusing so the override can be offered | **44** |
| a failed **read** — `useCan` falling open, a 404 read as *"no such team"*, a drifted model that must read as failed rather than empty | **16** |
| `renderFailures.test.tsx`'s own case, which had pinned the old classification | **1** |

That distribution is the argument against one hatch. **44 of the 61 are a rejected `POST`**, and a
test that arranges one has said *nothing whatever* about whether the panels beside the dialog were
allowed to fail their reads — which is exactly where §198's defect lived. A single
`drivingAFailedRequest()` would therefore have let the largest group of exceptions in the suite
disarm the check in the place it was written for. So the classification is `render | query |
mutation`, `KIND_OF` maps `ErrorSource` onto it (still exhaustive, still a compile error when a
member is added, for §201's reason), and there are three hatches: `renderingIntoAnErrorBoundary`,
`drivingAFailedRead`, `drivingAFailedWrite`. Each excuses its own kind and nothing else, which is
pinned by `unexcused` — extracted as a pure function precisely because *"the check decided not to
fail"* is not something a green run can otherwise show.

## What it buys, measured on §201's own instance

A population of zero is what §201's render half also found, so the check was measured against the
thing it exists for rather than argued about. §201's measurement was to delete the `/users` arm of
`ApiTokensPage.test.tsx`'s double so the file's own `throw new Error('unmocked read: …')` fires; it
reported **no report at all**. Repeated here, twice, on the same tree:

| `KIND_OF` for `query`/`mutation` | Result |
|---|---|
| `null` — the state §201 shipped | **1 test red of 9** |
| `'query'` / `'mutation'` — this slice | **9 of 9**, each naming `` the read `["users"]` failed `` / `Error: unmocked read: /users` |

**Eight tests were passing beside a panel that could not load its owner names**, and the ninth is the
only one whose assertion happened to point at it. §201's prose said *"one was, so the seven tests
failed on their assertions"* — that was a reading of the file rather than a measurement, and it is
one, not seven. The correction matters in the direction that makes the check worth having: the
invisible share was larger than the section that recorded it thought.

## The guard this slice created the need for

The request half sees a failure **only** through the caches `createTestQueryClient` installs, so a
test file that builds its own `new QueryClient(…)` is invisible to it — silently, and looking
exactly like a file in which nothing failed. Nothing pinned that before, because before this slice
the helper's contents did not matter.

`still covers the suite it was installed for` is `contractDoubles.test.ts`'s vacuity guard applied
one instrument later: **59 of the 100 test files** reach the helper (floor 50), and the count of test
files constructing a client of their own must be **zero** — an `toEqual([])` on the list rather than a
count, so the failure names the file. That answers half of §201's second opened item; the render
half's *"nothing counts how many tests mount a boundary"* is still open, and is a harder question,
because every page test mounts one.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses.

| | |
|---|---|
| `frontend`: `npm run build` | ✅ |
| `frontend`: `npm run lint` | ✅ 0 warnings, 0 errors |
| `frontend`: `npm run test:run` | ✅ **1339 across 100 files, 0 failures** (+4, no new file — `renderFailures.test.tsx` replaced 1 hand-made case with 5 real ones) |

**`backend` and `packages` were not run, and that is a decision rather than an omission** — §197's,
§198's and §201's, unchanged. Nothing outside `frontend/src` changed: no Java, no migration, no
`openapi.yaml`, neither `schema.d.ts`.

The measured reds are kept above because they are the finding: **61 failed / 34 files** with the two
entries flipped and nothing repaired, and the 1-vs-9 pair on `ApiTokensPage.test.tsx`. All restored.

## Verification

**No rows in either queue**, on §198's and §201's precedent and for their reason. Every changed file
is a test, the test harness, or a module only the harness imports; `src/test/queryClient.ts` has no
importer outside `src/**/*.test.tsx`. No screen renders differently, nothing on the wire moved, and
the built chunk sizes are unmoved.

## The next slice

§201's list, minus what this took, plus what this opened.

**Opened here:**

- **A hatch is a per-test declaration, and 44 of them now say "this test refuses a write".** Nothing
  reads that. The suite can now be asked a question it could not be asked before — which screens have
  a test for the refused write, and which have none — and the answer is a coverage map of the error
  paths, derived rather than curated. `contractOperations.test.ts` already reads call sites
  statically, so the instrument exists.
- **`app/queryClient.ts` and `test/queryClient.ts` now both report, and nothing pairs them.** The
  test client reports the same two sources with the same two context keys as the app's, by hand, in a
  second file. A third source added to the app's caches would not appear here, and the check would go
  quiet on it — §196's two tsconfig lists in a new place.

**Carried forward:** nothing counts how many tests mount a boundary (§201, the render half of it);
resume the browser queue at `A2 — plans`; a third `packages/` live pass; the snapshot has no reader
(§193); a clone cannot file the copy nowhere (§195); **`packages/` still mocks its client with no
comparison at all** (§196–§199, and the largest carried item); nothing pairs the two tsconfig lists
(§196); the undeclared-property question is off under `additionalProperties` (§198); `NEEDS_A_SAVE`
has no producer and never had a test (§200); a stub is `INCOMPLETE` only once approved and nothing
tells its author before then (§200); the read-only archive decision §159 did not take; serving the
relabel audit (§174); `CurrentUser.teams` has no reader (§175); a run has no edit endpoint (§177);
§184's, §185's, §188's, §189's and §190's items.
