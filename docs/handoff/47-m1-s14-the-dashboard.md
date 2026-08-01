# §47. Backend M1, slice 14 + frontend — the repository dashboard, and the tile that linked to the wrong list

**Date:** 2026-08-01 · **Branch:** `master` · **Sequential**, the second slice since §45 ended the
parallel experiment.

`GET /projects/{projectId}/dashboard` is live. It is the **project's landing page** — the first
screen after picking a project, and until now the last big read surface still served by MSW. The
`repository` module is now read-complete.

## The orientation pass paid for itself twice

§46's new question — *"is this still blocked?"* — was asked of all three remaining MSW endpoints, and
one of them was not blocked at all. `/dashboard` is tagged `reporting` in the contract, and three
sessions' notes treated that tag as a dependency. **Every figure on it is a `repository` rollup**:
lifecycle and automation are columns on `test_case`, the breakdowns are `case_classification` and
`owner_id`, and the activity chart is `created_at` on the case and on its versions. Nothing needed a
`reporting` module; the tag names the reader's concern, the module names where the data lives.

The other question — *"does it have a consumer?"* (§28) — was what picked this endpoint over the two
history paths: `DashboardPage.tsx` has been rendering `useDashboard` against the mock since Phase 1.

## The defect the design produces, and the parameter that fixes it

§6o's rule is that **every count carries the query that reproduces it** — a tile is the entrance to
the list behind it, not a number to look at. That rule has an edge nobody had hit:

> **A filter key the case list does not bind produces a tile that silently opens a *wider* set than
> it promised.**

The by-owner breakdown emits `{ ownerId, lifecycleStatus }`. **`GET /cases` had no `ownerId`
parameter.** Spring ignores an unbound query parameter, so "Ana · Approved · 34" would have opened
*everyone's* 210 approved cases, under a heading naming Ana, with a correct number on the tile behind
it. Nothing throws and the page looks right.

The fix is the parameter, not the link. Dropping the owner from the link — the smaller change — is
§6o's own anti-pattern: a count that cannot be drilled into. So `ownerId` was added to the contract,
`CaseQuery`, `CaseSpecifications`, the controller, `CaseFilters` and `useCaseFilters`.

**The test that catches this family calls the link.** Every count assertion in
`DashboardEndpointTest` is made against `GET /cases` *invoked with that tile's own emitted filter*,
never against a literal — a literal agreeing with a wrong implementation is how both screens end up
wrong together (§32, §46). Deleting the `ownerId` predicate now fails a test; before this slice it
would have failed nothing.

### It also needed a UI half nobody would have asked for

`ownerId` is the only filter with **no control in the filter bar** — it arrives solely from a
dashboard link. So the case list rendered narrowed with nothing saying whose cases these were, and
"Clear filters" as the only hint it was narrowed at all. A filter the UI applies but never states is
the same defect class as one it drops. There is now a removable `Owner: …` chip, its name resolved
from the loaded rows (every row shares the owner by construction) and falling back to "one owner"
when the filter matches nothing.

## Two panels that would have lied, caught before shipping

**`case_health` has no writer and never has.** So the "Needs attention" panel is empty in every
deployment — and **empty there is a claim**: it reads as *nothing needs attention*, when the truth is
*nobody has looked*. This is §46's `met: null` exactly, one screen over.

The contract grew `healthComputedAt: string | null`, `case_health.computed_at` is now mapped, and the
panel renders **three** states rather than two. `CaseHealth`'s javadoc previously argued the column
should *not* be mapped; that reasoning is struck through in place with `→ superseded` and why it held
while the only reader was a per-case chip list.

**The activity chart had no honest `approved` series.** Nothing in the schema records *when* a case
was approved — `lifecycle_status` is current state, and `audit_event.changes` deliberately stores the
version number rather than a field diff. A permanently-zero line labelled "Approved" states that
nobody approved anything this month, which is a claim about the client's work rather than an
admission that it is not measured. **The field was narrowed out of the contract** and the mock
projected down to match (§46's `PlanCaseRef` precedent: a mock richer than the contract lets a page
read a field the server never sends). It returns when the review workflow (§6g) gives approval a
timestamp.

## One rule, applied in both directions, on purpose

| Panel | Zero-count entries | Why |
|---|---|---|
| Lifecycle tiles | **Kept** | A distribution over a closed set of four. The zero *is* the information — no endpoint can move a case out of `DRAFT` today, so "0 approved" is the most useful number on the page, and dropping it shows three tiles and looks complete. |
| Health tiles | **Dropped** | An open-ended set of things that are *wrong*. "0 stale approvals" is a filter that opens an empty grid, and nine of them bury the two that matter. |
| Bar segments | **Dropped** | A zero-width sliver is not a clickable link. |
| Case-list facets (existing) | Dropped | Unchanged — a chip promising 0 is a filter yielding nothing. |

The lifecycle tiles are the deliberate exception to the facet rule the module already had, and the
difference is stated in both places because the two sit one click apart.

## Counted over the same predicate as the list it links to

Every tally reuses `CaseSpecifications` through a new `CaseQuery.unfiltered()`, so a tile's count and
the grid behind it **cannot** disagree about what counts: both exclude soft-deleted cases and both
exclude cases hanging off a soft-deleted folder, and neither can learn one of those rules without the
other. Two hand-written predicates would agree only on the day they were written — which is §25's
picker-vs-list defect, found by running the app.

`DashboardTallies` holds the four aggregates `CaseSearch` cannot express (by owner, by classification,
and the two activity reads). Criteria throughout, never native SQL, so the Hibernate company filter
applies on top of RLS rather than RLS becoming the only boundary.

## The live pass found what no gate did — again

Driven in the browser in `VITE_API_MODE=live` against `docker compose`, on the real `acme` company
(4 cases, all `DRAFT`, all `MANUAL`, two carrying genuine v2 edits from §28/§29).

**1. The activity chart drew a full month of uniform activity for a project with one busy day.**
The server data was exactly right — 30 contiguous days, `4 created / 2 modified` on 2026-07-31,
zeros elsewhere, v1 rows correctly excluded. **The chart rendered every zero-day bar at full
height**, because MUI's sizing system transforms a unitless `sx` number ≤ 1 into a *percentage*: the
existing `minHeight: 1` meant `minHeight: 100%`. Fixed with `'1px'` as a string.

This was **pre-existing and had survived three browser passes**, because the mock fixture has
activity on most days — a chart that was always wrong looked like a chart with data in it. It took a
project with one day of history to make it obvious, and it is precisely the failure this chart exists
to prevent: the shape lying about the tempo.

**2. "By feature" rendered as an empty bordered box** — a heading with nothing under it, which reads
as a failed load rather than as "nobody has classified anything yet". A project's Feature vocabulary
starts empty *by design* (inventing a client's taxonomy is how a controlled vocabulary gets worked
around), so this is the **first** state of the panel on every real project, not an edge case.

Also confirmed live: the by-owner segment navigates to `?ownerId=…&lifecycleStatus=DRAFT` and returns
4 of 4 with the `Owner: A. Admin` chip; an unknown `ownerId` returns **0 cases** and the chip falls
back to "one owner" — so the filter genuinely narrows rather than being ignored. Console clean.

## Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL in 1m 50s
           869 tests, 0 failures, 0 skipped        (843 + 26)
frontend:  npm run build   → ✓ built
           npm run lint    → oxlint, exit 0, no output
           npm run test:run→ 16 files, 261 tests passed   (255 + 6)
```

**Mutation-verified.** Dropping the `ownerId` predicate, counting every version row rather than
`versionNo > 1`, and removing the feature-vocabulary bound each kill tests (1, 1 and 23). Ignoring
`healthComputedAt` in the panel kills its test.

**One mutation SURVIVES and is recorded rather than papered over:** deleting the `created_at >= since`
bound in `DashboardTallies.creationTimes` changes nothing, because `DashboardService.activity` builds
its buckets by *looking up* the thirty days it intends to plot, so an older timestamp lands on a key
nothing reads. The bound is a **cost control, not a correctness one**, and both the method header and
the test now say so — the alternative is a predicate every future reader assumes is load-bearing.
(Same family as `CaseSpecifications`' `projectId` line.) A first attempt at the frontend `ownerId`
test also survived its mutation, because the fixture URL carried a second filter that kept
`hasActiveFilters` true either way; split into its own case.

## Deliberate limits, stated

- **`byFeature` bars do not sum to the case count, and must not.** Feature is the one MULTI dimension
  (§6d) — a case covering checkout and refunds belongs in both bars. It is a breakdown, not a
  partition; attributing each case to one feature would need a rule invented here that disagreed with
  every other reading of the same rows.
- **Unowned cases are in no by-owner bar.** An "unassigned" bar would be the one row on the panel
  whose own link could not reproduce it — `ownerId=` empty means "no opinion" over HTTP, so no value
  of the filter selects it. The count is not lost; the lifecycle tiles count every case.
- **Computed on read, where §6o describes a maintained rollup** — `PlanStatisticsService`'s argument
  (§46), unchanged: a figure recomputed per request cannot go stale relative to a case authored a
  second ago, and this is the page the author lands on straight afterwards.
- **Activity is bucketed in Java, in UTC.** `date_trunc` through Criteria needs a hand-declared return
  type and pushes the time zone into SQL, where a mistake is a chart silently shifted by a day and
  nothing that fails. The cost is one row per case *created in the last thirty days*, stated in the
  method header.
- **Tile labels are on the wire**, uniquely on this endpoint — everywhere else the SPA labels enums
  from `lib/labels.ts`. A tile is a generic `(key, label, count, filter)` shape precisely so a new
  server-side signal renders as a working, drillable tile with no frontend change; that property is
  worth four duplicated English words, and the test pins them.

## The `* 2.*` detection command misses conflict-copy DIRECTORIES

§45's warning box says to check `find . -name "* 2.*"` before trusting a build failure. **That
command returned zero matches while `:test` hung for over ten minutes with no output at all** — the
§45 symptom exactly. A `jstack` on the daemon showed it parked in `DirectorySnapshotter`, and the
cause was four conflict-copy **directories** the pattern cannot match because they have no dot:

```
build/classes 2   build/generated 2   build/reports 2   build/resources 2
```

`rm -rf build` and the same task ran in 13 seconds. **The check is
`find . -name "* 2*" -not -path "./.git/*"`** — no trailing dot. Also worth knowing: two concurrent
`./gradlew` invocations block on the build lock and produce *no output while waiting*, which looks
identical to the hang and sent this session chasing the wrong cause first.

## Not done

- **The contract-hygiene computed checks are still not built** — "every path declares at least one
  4xx" and "every `$ref` at a nullable column is an `allOf`". **Fifth session running.** This slice
  hand-added the `404` to its own path, which is what the previous four also did. The evidence for
  building them is now overwhelming: the `ownerId` defect in this very slice is the same shape — an
  invariant across a catalogue that reading cannot verify (the working agreement's own rule).
  A check that **every `filter` key a dashboard emits is a parameter the case list declares** would
  have caught it mechanically, and belongs in the same family.
- **`documentation`, `reporting` and `work` writes still run on MSW.** With this endpoint live,
  `/doc-pages`, `/artifacts`, `/reports` and `/work-items` are the remaining MSW-only endpoints, plus
  `cases/bulk`, `cases/{id}/history` and `suites/{id}/history` — all three of which have SPA
  consumers and so pass the §28 test.
- **Teams and memberships** remain the next write surface with no endpoints (§39, §45, §46).
- **`data-testid` on MUI `TextField` lands on the root `<div>`** (§44) — untouched.
- **`health` will start returning tiles the day anything writes `case_health`**, with no change to
  this endpoint. `DashboardEndpointTest` says so at the two assertions that would then fail, and names
  its own replacement rather than leaving a permanently-empty panel behind.
