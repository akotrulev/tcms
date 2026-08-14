# §197 — The bodies nothing compared, and the endpoint nine doubles answered instead

**One phase, `frontend/` only: 33 test files, the test harness, one line of `vitest.config.ts`, and
three new files. No migration, nothing in `backend/src`, no contract change, no `schema.d.ts`
regeneration, and not one file under `src/` that ships.**

One phase for §196's reason, restated because it applies unchanged: the check and the 629 repairs it
demands are the same change, and a commit holding only one half is a commit whose gates say nothing
about the other.

§196's own largest opened item, taken verbatim: *"`as never` is the population this instrument
cannot reach… That is a real slice, and it is where the remaining fixture drift lives."*

## The cast §196 could not remove, and why the instrument had to change kind

§196 deleted every `{ … } as TestCase` in the tree and left one shape standing:

```ts
vi.spyOn(api, 'get').mockImplementation((async (path: string) => { … }) as never)
```

229 of those, across 76 files. **The cast is not the defect and cannot be deleted.** `api.get<T>`
resolves `T` at the call site, a spy has no call site, and `never` is what satisfies the resulting
signature. So the compiler is structurally unable to ask the question here, and `contractFixtures`'
program — which resolves a *symbol* to `api/types.ts` — has nothing to resolve: the body is an
argument to a mock, not an assertion to a type.

What can still be compared is the **value**, at the moment the double hands it over. That makes this
a runtime instrument where every other contract check in this repo is a compile-time one, and the
difference is the whole design: `src/test/contractDoubles.ts` wraps `vi.spyOn` once, globally, and
any spy it returns **for a member of the `api` object** gets its `mockImplementation` /
`mockResolvedValue` / `mockReturnValue` (and the `…Once` forms) wrapped so the body passes through
`src/api/contractResponses.ts` on the way out.

**A helper each test opts into was the obvious alternative and is the wrong one.** `mockGet('/cases',
body)` is a check a new file skips by not calling it, which is a hand-maintained list, and the
failure mode of a hand-maintained list is silence — §196's two tsconfig lists, §162's dead
exemptions, §53's undeclared operations. It would also have been a 76-file migration whose entire
content is opting in. The seam costs nothing at the call sites and covers a test written tomorrow
without being told to.

The failure lands in `afterEach`, not at the call. Throwing inside the double would be caught by
TanStack Query, which renders the region's error branch — so a bad fixture would surface as a
component that failed to load, three assertions away from the fixture. That is the reading failure
§65 and §196 both paid for.

## What it found: 34 of 99 files, 251 tests

Green suite before, 251 red tests across **34 of 99 files** the moment the seam was installed. The
distinct violations — measured with a recording build of the same analyzer, because Vitest prints
one assertion per test rather than per finding — are **629 (endpoint, field) pairs across 33
files**, 31 of them a property the contract does not declare and the rest a required one absent.

Five are worth reading rather than counting.

**1. Nine files answered `GET /projects/{id}/settings` with the *project*.** The arm is
`if (path.startsWith('/projects/')) return { id: PROJECT, name: 'Banking' }`, and it is written last
as a fallthrough, so every sub-resource with no earlier arm lands on it. `ProjectSettings` and
`Project` share **no** property, so the review-gating flags all read `undefined` — falsy, and
therefore *indistinguishable from a project that turned review off*. Eight plan and case files were
in that state; `ReviewPanel.test.tsx` is a ninth with a hand-written settings body that predates
`requireApprovedCaseForRun`. This is the finding the undeclared-property half exists for: the
missing fields alone read as an incomplete fixture, and `id` and `name` arriving *as well* say it is
a different shape entirely.

**2. `counts.retest` is a vocabulary the API has never had.** Four plan files served it and
`GenerateReportDialog` served `executed`, `retest` and `untested`. `ResultCounts` declares six
fields whose own comment says the parts sum to the total, and `ExecutionViews` folds `UNTESTED`,
`RETEST` and `WIP` into `notRun` on the way out. The status kinds are real — `RETEST` is in `V1`'s
check constraint and in `statusColors.ts` — which is exactly why an invented *count* named after one
reads as plausible.

**3. Two suite fixtures still carried `parentId` and `path`.** The tree §98 flattened, alive in a
fixture twelve sections later.

**4. `PATCH …/external-links/{linkId}` was served a `DocLink` where the server answers an
`ExternalLink`.** The two differ by the whole page/block half and by `stale`. It matters because
`useRelabelCaseExternalLink` puts the response into the cached case **through `asDocLink`** — so the
one test covering that path was handing it something already adapted, and the adapter was the part
not being exercised. Confirmed against `ExternalLinkView` before changing the fixture rather than
the contract: the server sends nine fields and none of them is a page.

**5. `GET …/plans` returns the full `TestPlan`, not `TestPlanSummary`.** §100 decided that and wrote
the reason into the spec; `PlanForm`'s picker fixture was a three-field row.

## What the check does not do, and one defect that proves it

**It compares structure, not values.** A `number` where the schema says `string`, and an enum member
the schema does not list, are both invisible to it. `RunCreateDialog`'s plan fixture carried
`status: 'ACTIVE'` — `PlanStatus` has three members and that is not one of them — and it was found
by *reading* while repairing the file beside it, which is precisely the thing this repo keeps
learning not to rely on. It is repaired and named here as the next rung rather than smuggled in as
if the instrument had caught it.

The reason for the limit is that the population is structural and a value check would arrive as
`date-time` and `uuid` format noise in 76 files at once. `oneOf`/`anyOf` schemas are skipped for a
different reason: reporting the union's intersection of required properties is wrong in one
direction and reporting one arm's is wrong in the other.

## `src/test/fixtures.ts`, and why the defaults are boring

Seventeen factories — `aProject`, `projectSettings`, `aCase`, `aCaseSummary`, `aSuite`, `aPlan`,
`aPlanSummary`, `aRun`, `aConfiguration`, `noCounts`, `planStatistics`, `aWorkItem`, `aTeam`,
`aDocPageSummary`, `anExternalLink`, `aCurrentUser`, `anAuthResult` — each typed to its contract type
and **never cast**, so §196's rule keeps them complete: the day a required field arrives, this file
is the build error and nothing else is.

Every default is the least interesting body the contract admits — empty arrays, nulls where a field
is nullable, `DRAFT`, zero counts. **A plausible default is a claim about the domain that no test
asserts and every test inherits**, and when one of them starts depending on it the dependency is
invisible. A test says what it is about by overriding, so a reader can tell the load-bearing values
from the scenery.

`CaseEditorPage.test.tsx`'s `loadedCase` — the factory §196 built there — now delegates to `aCase`
and keeps only what *that file* means by a saved case (filed in a suite, saved once).

## Two things the seam itself had to be taught

**`vi.spyOn(window, 'location', 'get')`.** The first wrapper forwarded two arguments; the accessor
form takes three, and dropping the third turns it into an attempt to spy on an object, which throws.
`versionSkew.test.ts` failed the moment the seam was installed. It forwards everything now.

**The spec is parsed on first use, not on import.** The module is reached through the harness, so it
loads once per test file; parsing 550 kB of YAML measures at **~110 ms** (parse alone, on the Mac).
About forty files never build an `api` double at all, and the lazy parse is the difference between
charging them for it and not. **Measured on shanks, both ways**: the suite's `setup` phase is
**44.4 s** across 99 files with the parse on import and **17.9 s** with it lazy. Total wall clock is
unchanged either way — 234 s then 232 s, against the 231 s CLAUDE.md records for 1251 tests — because
setup runs inside the per-file worker, in parallel with everything else. The parse is worth
deferring; it was never worth a `globalSetup`.

## The `?raw` import, and the tsconfig question it answers

`contractResponses.ts` reads `openapi.yaml` through Vite's `?raw` import rather than `node:fs`, and
this is not a style choice. Every other contract check needs `types: ["node"]`, so it is excluded
from `tsconfig.app.json` and included in `tsconfig.node.json`. **This module cannot make that
trade**: it is imported by the harness, which is imported by every test, and `exclude` drops a file
from the *root set*, not from the graph of what the roots import — so the app project would compile
it either way and every `node:` import would be a `TS2591`. `?raw` needs no node types.

The cost is one line in `vitest.config.ts`: `server: { fs: { allow: ['..'] } }`, because the spec
lives above the Vite root. It is in the **test** config, not `vite.config.ts`, so the dev server's
own fs allow-list is untouched — widening that one would let a browser fetch `.env` over `/@fs`.

§196's third opened item — that the two tsconfig lists have nothing enforcing their pairing — is
**still open**, and this slice added nothing to either list.

## What shipped

| | |
|---|---|
| `src/api/contractResponses.ts` | the comparison: spec, path resolution, `allOf`-aware walk |
| `src/test/contractDoubles.ts` | the seam: wraps `vi.spyOn` for `api`, fails in `afterEach` |
| `src/test/contractDoubles.test.ts` | 13 tests — the vacuity guard, the population guard, seven pins on the seam, four on the comparison |
| `src/test/fixtures.ts` | the factories |
| 33 test files | the repairs |
| `src/test/setup.ts` · `vitest.config.ts` | one import, one `fs.allow` |

**The vacuity guard is the part to read before trusting any of it.** A green suite is what both
"every body is correct" and "nothing was ever compared" look like. Three things would silence it —
the spec failing to load, the wrapper losing `api`, the suite moving off `vi.spyOn(api, …)` — and
each has a test: `declaredPathCount() > 80`, a real `api.get` driven through a real spy, and a count
of test **sources** that still mock this way (`>= 45`, measured 56) read through `import.meta.glob`.
The last one is the one that will fail first, and it is meant to.

`servingADriftedBody()` is the escape hatch: a function called inside the test that needs it, so the
reason is written where the exception is taken and it cannot widen to a file, a directory or a
pattern. **Nothing in the suite calls it today** — it is there because a response whose shape drifted
is a behaviour this app has (`tcmsFaults.drift()`), and a test for it must be able to serve one.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses.

| | |
|---|---|
| `frontend`: `npm run build` | ✅ |
| `frontend`: `npm run lint` | ✅ 0 warnings, 0 errors |
| `frontend`: `npm run test:run` | **1314 across 99 files, 0 failures** (+13, one new file) |

**`backend` and `packages` were not run, and that is a decision rather than an omission.** Nothing
outside `frontend/src` changed — no Java, no migration, no `openapi.yaml`, neither `schema.d.ts`.
§196 ran the backend because it moved a schema in the contract and `ContractConventionTest` reads
that file; there is no such edge here.

One local full run reported a single unreproducible failure whose identity was not captured, and two
subsequent full local runs plus two on shanks were clean. Recorded rather than dismissed: this suite
has a known load-dependent timeout cliff (§129, `vitest.config.ts`'s comment), and the seam adds one
microtask per mocked call.

## Verification

**No rows in either queue.** Every changed file is a test, the test harness, or the test runner's
config; no screen renders differently and nothing on the wire moved. The behaviour under the
repaired fixtures is pinned by the 1314 tests that ran — and by rather more than before, since nine
files' review-gating assertions were previously running against `undefined` flags.

## The next slice

§196's list, minus what this took, plus what this opened:

- **resume the browser queue at `A2 — plans`** — unchanged since §186, sixteen `M2` rows.
- **a third `packages/` live pass** — §189's two write arms and three read commands are all
  stub-observed, and `§189 CN-3` cannot be closed by a stub.
- **the snapshot has no reader** — §193's larger item, still untouched.
- **a clone cannot file the copy nowhere** — §195's contract-side item, small, still open.
- **`packages/` has no equivalent check** (§196) and **nothing pairs the two tsconfig lists** (§196),
  both still open and both now with one more argument behind them.
- the **read-only-archive** decision §159 did not take; **serving the relabel audit** (§174);
  **`CurrentUser.teams` has no reader** (§175); **a run has no edit endpoint at all** (§177); §184's
  *what the SPA computes from a contract enum the server also computes*; §185's *a rule that fails
  when a test reads a path outside `src/`*; §188's `= null` writer-rule gap; §189's *a proposal
  cannot carry a decline*; §190's *whether "a comment whose argument expired" has any computable
  form at all*.

**And three this slice opened.**

**A value is still whatever a fixture says it is.** `status: 'ACTIVE'` sat in `RunCreateDialog` for
four slices and this instrument cannot see it — nor a `number` where the contract says string, nor a
malformed `date-time`. The walker already resolves the schema for every body it inspects, so the
question is which false positives a value check would bring rather than whether it is reachable. It
is the obvious next rung and it is a slice, not a patch.

**The request bodies are unchecked in the same way.** A double reports what it was *called* with and
the tests assert on it (`expect(post).toHaveBeenCalledWith(…)`), but nothing compares those bodies
against the operation's `requestBody` schema. Same walker, other direction, and the population is
every write test in the suite.

**`packages/` still mocks its client with no comparison at all**, and unlike §196's version of this
item there is now a runtime instrument that would transfer: the CLI and the MCP server build their
doubles the same way, over the same generated schema, and `contractResponses.ts` is a plain module
with one Vite-specific line in it.
