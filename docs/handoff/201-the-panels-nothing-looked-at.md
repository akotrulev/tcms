# §201 — The panels nothing looked at, and the door the query layer holds shut

**One phase, `frontend/` only: two new files in `src/test/`, one line of `src/test/setup.ts` and two
lines of `src/components/DataRegion.test.tsx`. No migration, nothing in `backend/src`, no contract
change, no `schema.d.ts` regeneration, and not one file under `src/` that ships.**

One phase for §196's, §197's, §198's, §199's and §200's reason, restated because it applies
unchanged: the check and the repairs it demands are the same change, and a commit holding only one
half is a commit whose gates say nothing about the other. Here the repair set turned out to be two
lines — see *The population is zero* below, which is a result rather than a shortcut.

§198's own largest opened item, taken verbatim and still open at §200 as *"still the largest
instrument item"*:

> **A panel can render its error branch for a hundred slices and no gate says so.** The 24 tests above
> are the measured instance; the *population* is every `DataRegion` in the tree whose children throw
> on a body the fixture got wrong, and nothing enumerates it. A candidate shape: fail a test whose
> render tripped a region-level `ErrorBoundary` unless it says it meant to — the same
> escape-hatch-as-a-function design `servingADriftedBody()` uses, one layer up. That is a slice, and
> it is the one that would have found this without a fixture defect to lead it there.

## The gap is a property of the design, not of anybody's assertions

§198 found it by accident: a fixture answered `/cases/{id}/versions` with a case, `rows.map` threw,
`DataRegion`'s own `RegionErrorBoundary` caught it, and 24 tests across two files had passed for as
long as those files existed with the version panel beside them showing *"We couldn't display this
content."* Not one of those 24 assertions was wrong. Nothing was looking at the panel.

That is not a fixture problem with an instrument problem behind it. The boundary tiers exist **so
that** one broken region does not take the page down — a tripped boundary is *by design* invisible to
everything except an assertion aimed at it — and a test asserts on the subtree it is about. There is
no reading of a green run that distinguishes *"the other six regions rendered"* from *"the other six
regions caught a throw."*

So the check cannot be "assert the region rendered", written 1330 times and skippable by file 1331.
It has to be a property of the whole render, collected where the app already collects it.

## Why the sink and not the DOM

The obvious observation point is the fallback's own `data-testid` — `<testId>.RenderError`,
`Route.Error`, `Root.Error` — queried after the test. It is worse in two ways, and both are about
*when*:

- a boundary that tripped and was then reset by a `resetKeys` change leaves **no trace in the DOM**,
  so a DOM read reports on what survived rather than on what happened;
- RTL's `cleanup` runs in an `afterEach` of its own, so a DOM read races a hook order this module does
  not own.

`reportClientError` has neither problem. **Every tier reports through it** — that is the sink's whole
stated purpose, written in its header long before anything read it — it is called at the moment of the
throw, and it carries the tier plus the region's `testId` in `context`. That last part is what makes
the failure message name a *panel*: the measured red below says `the region CaseDetail.Versions.Region
caught a throw`, which no amount of contract checking could have said.

## Two seams that had no caller, and both were written for this

Nothing in the suite called either of the two things this module stands on, and both say in their own
comments what they are for:

| Seam | Its own words |
|---|---|
| `window.__tcmsErrors` (`send()`) | *"kept on `window` so a report can be read back after the fact — a console object that has to be expanded by hand is no use to an **automated check**"* |
| `resetReportedErrors()` | *"Test-only seam — a fresh test must not inherit another's fingerprints"* |

The first is the array this check reads. The second is load-bearing rather than tidy: the sink dedupes
on `source:name:message` and never forgets, so **without a per-test reset the second test in a file to
hit the same broken panel would collect nothing** — the check would redden whichever test ran first
and call the other twenty-three clean, which is the exact reading failure it exists to end. That is
pinned by a test which throws the same `TypeError` from the same tier a second time.

A reader arriving after the seam is this repo's recurring shape, usually as a defect (§116, §117,
§121, §132, §172, and §121's seam that was *deleted* because its consumer could never have existed
where it was filed). This is the benign instance: the seam guessed right and the reader is late.

**`__tcmsErrors` is filled only on the `import.meta.env.DEV` branch of `send()`**, which is the one
way this module could go silently dark. So every case in `renderFailures.test.tsx` renders a component
that throws inside the boundary the app really uses, rather than pushing a hand-made report onto the
array — `contractDoubles.test.ts`'s vacuity argument in the same shape, because an instrument whose
working state is silence has to have its silence measured.

## The source list is a `Record`, not a `Set`, and the compiler is the reason

`ErrorSource` has eight members and three of them mean *a render threw*. Written as
`new Set(['root-boundary', 'route-boundary', 'region-boundary'])` the check would name what it covers
and say nothing about what it does not, so a **fourth boundary tier** added later would be absent from
it and the check would go quiet on that tier while staying green — a hand-maintained list, whose
failure mode is silence (§196's two tsconfig lists, §162's exemptions, §53's undeclared operations).

`Record<ErrorSource, boolean>` makes a new member a **compile error at the list**, and forces the five
`false` entries to be written down as answers. It is `lib/labels.ts`'s `satisfies Record<Union, …>`
rule, applied to a predicate rather than to a vocabulary.

## The population is zero, and that is the result

**1335 tests across 100 files, green, with the check installed and nothing repaired.** Two tests
needed the escape hatch — `DataRegion.test.tsx`'s two, whose subject *is* a contained throw — and
nothing else in the suite trips a boundary. §198's repair of all eighteen doubles was complete.

A guard, then, not a repair — the outcome §198's four value checks had, and the expected one for a
check written after the slice that found its instance. **So it was measured against that instance
instead.** Restoring §198's own prefix arm in `CaseDetailPage.test.tsx` —
`if (path.includes('/cases/'))` in place of `` if (/\/cases\/[^/]+$/.test(path)) `` — reddens **10
tests**, and the check names the region:

```
This test rendered a region that had thrown:

  the region `CaseDetail.Versions.Region` caught a throw and rendered its failure state
    TypeError: Cannot read properties of undefined (reading 'key')
```

That is the claim the slice is for: §198 reached those 24 tests through a *fixture* defect that
happened to be visible to a contract check. This finds them from the other end, with no fixture defect
to lead the way, and names the panel rather than the body.

## The finding: the fetch door is held shut, and thirteen files think it is open

`DataRegion` renders `<testId>.Error` when the **fetch** fails, and that is the same defect one door
over: a secondary panel reading an endpoint the double has no arm for shows an alert, while the test's
assertions — all aimed at the primary panel — pass. The app reports those through this same sink
(`app/queryClient.ts`, source `query`), so the shape looks reachable from here.

It is not. **`createTestQueryClient` installs no error handlers**, and its comment gives a reason that
is right on its own terms (*"wiring that here would require the notification provider in every test
tree and assert nothing about the unit under test"*). No `query` report is ever collected under test.

Measured rather than reasoned about: breaking one arm of `ApiTokensPage.test.tsx`'s double so its own
`throw new Error('unmocked read: ${path}')` fires produced **no report at all**. **Thirteen test files
wrote that throw to be loud**, and the query layer swallows every one of them — the throw becomes a
rejected query, the region renders its error branch, and whether anybody notices depends entirely on
whether an assertion happens to be pointed at that region. In `ApiTokensPage`'s case one was, so the
seven tests failed on their assertions; in the peripheral-panel case that §198 found through the other
door, none is.

The two `query`/`mutation` entries in the `Record` are therefore **false by classification rather than
by necessity**, and the header says so, because the day the test client grows handlers is the day
those two entries stop being decorative.

Not taken here. Closing it means deciding what a *deliberate* failed query looks like — hundreds of
tests drive one on purpose to reach `DataRegion`'s error branch — and that is a second escape hatch
with its own population to measure. It is this slice's largest opened item.

## Gates

Run on **shanks** (`~/bin/tcms-remote`), the Linux shape `tests.yml` uses.

| | |
|---|---|
| `frontend`: `npm run build` | ✅ |
| `frontend`: `npm run lint` | ✅ 0 warnings, 0 errors |
| `frontend`: `npm run test:run` | ✅ **1335 across 100 files, 0 failures** (+5, **+1 file** — `renderFailures.test.tsx`) |

**`backend` and `packages` were not run, and that is a decision rather than an omission** — §197's and
§198's, unchanged. Nothing outside `frontend/src` changed: no Java, no migration, no `openapi.yaml`,
neither `schema.d.ts`.

The measured reds, kept because they are the finding: **10 failed / 1 file** with §198's prefix arm
restored in `CaseDetailPage.test.tsx`; **1 failed / 1 file** with the escape hatch removed from
`DataRegion.test.tsx`'s first contained-throw test. Both restored.

## Verification

**No rows in either queue**, on §198's precedent and for its reason. Every changed file is a test, the
test harness, or a module only the harness imports; `renderFailures.ts` has no importer outside
`src/test/` and `src/components/DataRegion.test.tsx`. No screen renders differently, nothing on the
wire moved, and the built chunk sizes are unmoved.

## The next slice

§200's list, minus what this took, plus what this opened.

**Opened here:**

- **A region whose *fetch* failed is still invisible, and the reason is one line of
  `createTestQueryClient`.** The measurement is above; the population is unknown because nothing can
  count it yet. Closing it is: report query and mutation failures from the test client into the same
  sink, flip the two `Record` entries, measure how many tests redden, and give the deliberate ones a
  hatch of their own. The thirteen `unmocked read` throws are the part of that population which needs
  no decision at all — a test author who writes one has already declared that reaching it is a bug.
- **Nothing counts how many tests render a boundary.** `contractDoubles.test.ts` guards its own reach
  by counting the files that build a double with `vi.spyOn(api, …)`; this check has no equivalent, so
  "zero trips" and "no test mounted a region" read identically. Cheap, and it is the same vacuity
  question one instrument later.

**Carried forward:** resume the browser queue at `A2 — plans`; a third `packages/` live pass; the
snapshot has no reader (§193); a clone cannot file the copy nowhere (§195); **`packages/` still mocks
its client with no comparison at all** (§196–§199, and the largest carried item now); nothing pairs the
two tsconfig lists (§196); the undeclared-property question is off under `additionalProperties` (§198);
`NEEDS_A_SAVE` has no producer and never had a test (§200); a stub is `INCOMPLETE` only once approved
and nothing tells its author before then (§200); the read-only archive decision §159 did not take;
serving the relabel audit (§174); `CurrentUser.teams` has no reader (§175); a run has no edit endpoint
(§177); §184's, §185's, §188's, §189's and §190's items.
