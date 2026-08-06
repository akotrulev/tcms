# §103 — The references show their cases: a live resolution, and the client-side filter it must not be

**Date:** 2026-08-06 · **Lane:** frontend only · **Migrations:** none · **Contract:** unchanged

The plan's **Test cases** tab drew what defines a group — `[Suite Payments ×] [Tag smoke ×]` — and
nothing else. §97's D7′ made that the deliberate model: a group is a *selector*, and the screen shows
the selector rather than an add-time snapshot of what it caught. The gap it left is the one question
an author actually has, *which cases does this catch?*, answerable only by leaving the screen and
rebuilding the query by hand. §102's closing section named this as the next ask and named the trap in
it. This slice ships the list.

## What changed

| File | Change |
|---|---|
| `lib/planScope.ts` | **new** — `resolveReferences`: unions N per-reference reads, dedupes by case id, records which references each case arrived through, sorts by `compareCaseKeys`, and reports a read the page cut short |
| `lib/planScope.test.ts` | **new** — 5 tests, all over properties invisible on a small fixture |
| `api/queries/cases.ts` | **new** `useReferencedCases` + `CaseReference` / `ReferenceCases` / `ReferencedCasesState`; the first `useQueries` in this codebase |
| `api/queries/index.ts` | the barrel line |
| `features/plans/ResolvedScope.tsx` | **new** — the read-only list, its caption, its `DataRegion` |
| `features/plans/PlanCasesTab.tsx` | `GroupCard` builds its collection references and mounts `ResolvedScope`; it now takes `dirty` as well as `count` |
| `features/plans/PlanCases.test.tsx` | 3 tests — the request shape, the live drop, the unsaved caption |

**No contract change, no `gen:api`, no migration, and no mock handler change.** Everything this
needs already existed: `GET /projects/{id}/cases` resolves `?suiteId=` to the suite's **union** since
§98 and takes a repeating `?tag=`, and the mock's handler has answered both since the same slice —
through `suiteMemberCaseIds`, the one function that serves both the count and the filter, which is
precisely what makes mock mode able to show this feature honestly. TP-1's fixture already carries an
unsectioned suite arm and a *Navigation* section referencing a suite **and** the `smoke` tag, so the
union, the dedupe and the `via` labels are all drivable with `npm run dev` and no backend.

## The decision the whole slice turns on: the server resolves, always

`cases.filter((c) => c.suiteId === id)` was available, free, and wrong.

A suite's membership has been **filed ∪ added ∪ tagged** since §98. `suite_id IN (…)` is the *filed*
arm alone. §96 shipped exactly that reasoning one screen over and produced a tag-defined suite
showing a **count of 2 over a grid of 0 rows** — the count was the union, the list was the filed arm,
and the two disagreed silently on the projects most likely to use tag rules. The list this slice adds
sits directly beneath the group's served `caseCount`, so the same defect here would put the
contradiction on **one line of one card**.

So every arm is a request. `?suiteId=` is the endpoint that answers the union and the only thing that
can; nothing in `lib/planScope.ts` or `ResolvedScope.tsx` looks at `suiteId` at all.

## One read per reference, not one read with every parameter

The references **union** — a section naming two suites wants both. `GET /cases` **narrows**, and its
`tag` parameter repeats and **ANDs** (the contract says so in as many words, and says why: a second
chip on a grid shortens a list, a second rule on a suite widens a collection). One request carrying
`?suiteId=x&tag=a&tag=b` therefore answers the *intersection* — usually empty, and empty in a way
that reads as "this section catches nothing", which is the worst available failure for a screen whose
whole purpose is to say what a section catches.

N references are N reads, unioned in `lib/planScope.ts`. The secondary benefit is attribution: a
truncation and a failure belong to the reference that caused them, which a blended read could not
express.

The key is **`keys.cases`**, shared with the grid: a resolution and a filtered case list asking the
same question are one cache entry, and `useCreateCase` / `useUpdateCase` / `useDeleteCase` already
invalidate the `['projects', p, 'cases']` prefix — so a case that gains a tag moves this list with no
new invalidation anywhere.

## This is not the snapshot D7 reversed, and the difference is testable

D7 wrote the resolved case ids into the plan at add time; D7′ reversed it because a plan must grow
when a referenced suite grows. It would be easy to read a rendered list of cases as the return of the
snapshot, so the property is worth stating precisely: **nothing on this screen or on the server
remembers a single one of these case ids.** They are derived from a read on every render. Taking the
reference off takes the rows with it, before any save — which is the second of the two tests added to
`PlanCases.test.tsx`, and browser row M3-55.

## What the list deliberately does not show

- **Cases named directly.** There is no by-ids read on the contract (`?q=` matches a *key*, not an
  id), and the only thing that resolves a case id on this tab today is the 200-row catalogue behind
  the chips — a recorded, accepted gap for a chip that can still be removed, and a **lie inside a
  list presented as complete**. Those cases are already drawn in full, key and title, by their own
  chip. Where a group has both, the caption says so rather than leaving the reader to notice an
  absence: *"The 3 cases named directly above are not repeated here."*
- **A count.** The group heading already carries the server's `caseCount` over all three arms. A
  second number computed here would mean something different, sit beside it, and be read as a
  contradiction — §52's two-numbers-on-one-screen and §65's two percentages. **The rows are the
  evidence; the count stays the server's.** The one number that does appear is a truncation notice,
  which is a statement about a read and not a total of anything.
- **New case rows.** They do not exist yet. The rows below already say they are created on Save.

## The dirty tab: the count hides and the list does not

The tab hides a served `caseCount` the moment the draft moves, because that number is the server's
arithmetic over the plan **it** holds and would be wrong in the direction of the work just done.

The resolved list reacts in the **opposite** direction and that is the honest answer, not an
inconsistency: it is computed from the **draft's** references, so a suite the author added ten
seconds ago resolves correctly — which is the behaviour the original report asked for. What it must
not do is claim a state of the plan that does not exist. So the caption switches:

- clean → *"The cases these references catch right now, recomputed on every read."*
- dirty → *"What these references **would** catch — including the ones not saved yet. Nothing is in
  the plan until you press Save."*

`GroupCard` now takes `dirty` **as well as** `count` for exactly this reason, and the prop's doc
comment says so, because collapsing them back into one is the obvious tidy-up and it would make one
of the two wrong.

## The combination is pessimistic in both directions, on purpose

`useReferencedCases` folds N query results into one region state:

- **pending while any arm is pending** — a half-arrived union rendered as a list is a set of rows
  that is wrong and looks complete;
- **failed if any arm failed** — the union of the arms that *did* answer is a shorter list with
  nothing on screen saying so, the same defect one layer down.

`data` is still handed back when a *refetch* fails, so `DataRegion` keeps the rows and marks them
stale rather than blanking them. `dataUpdatedAt` is the **oldest** arm, not the newest: a union is
only as current as the arm that last refreshed successfully.

## Two smaller notes

- `ReferencedCasesState` is spelled out in `api/queries/cases.ts` rather than imported from
  `components/DataRegion` — the data module does not depend on the component layer. `DataRegion`
  takes it structurally, so a drift between the two is a build error at the call site.
- `ResolvedScope` does **not** memoize the merge. The parent re-renders on every keystroke in the
  draft and `labelFor` is a fresh closure each time, so a `useMemo` keyed on it never hits and one
  keyed on `state.data` alone would go stale the moment a suite name arrived. Merging a few hundred
  rows is cheaper than a dependency array that is wrong.

## Verification

`docs/verification/frontend-browser-checks.md` rows **M3-52 … M3-62**, none run (§65 standing
policy). **M3-54 is the one to drive hardest** — a suite whose membership comes from a tag rule is
the only configuration in which the client-side-filter defect is visible, and it needs a backend.
M3-59/60 drive the resilience path with `tcmsFaults`.

No mutation rows: this slice writes nothing.

## Gate

`npm run build` ✅ · `npm run lint` ✅ (0 warnings) · `npm run test:run` — **936/936 across 68 files**.

**+8 tests: 5 in the new `lib/planScope.test.ts`, 3 in `PlanCases.test.tsx`.** Which makes the
baseline **928/67**, not the **925/67** the orientation cache recorded at §102 — a three-test drift
in a number nothing checks. Corrected in the cache with this slice; noted here because it is the
second time that figure has been carried forward by hand rather than read off a run.

## Found and not fixed

Nothing new. The 200-row case catalogue behind the case *chips* is still the accepted gap §97 phase 6
recorded, and this slice deliberately did not build on it — see "What the list deliberately does not
show".
