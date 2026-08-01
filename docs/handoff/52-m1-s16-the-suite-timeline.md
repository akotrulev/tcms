# §52. Backend M1, slice 16 + frontend — the suite timeline, and the field the two sides spelled differently

**Date:** 2026-08-01 · **Branch:** `master` · **Sequential**, the seventh slice since §45, and it collided with a concurrent strand: the section that
landed as §51 while this one was being written is the open-question decision round, so this is
§52. That is §10 happening again, and the convention held — two files, two index rows, no
append-conflict.

`GET /projects/{projectId}/suites/{suiteId}/history` is live, the page it feeds is reachable for
the first time, and three defects were found on the way — one by the orientation pass, two by
driving the app.

## Why this slice, and what the orientation pass found before any code

`SuiteHistoryPage` had existed since Phase 1 with a route, a hook and no server. The orientation
pass asked §28's two questions — *which of these has a consumer*, *is this still blocked* — and
turned up something neither: **the SPA and the contract disagreed about the shape of the response,
and nothing could have noticed.**

```ts
// the hook, since Phase 1                       // openapi.yaml, since the first draft
api.get<{ runs: SuiteRunSummary[]; … }>(…)       entries: { type: array, items: SuiteRunSummary }
```

The MSW handler was written from the hook's shape, so **mock mode agreed with itself perfectly**.
Live mode would have rendered `data.runs` — `undefined` — on the first load. This is §47/§49/§50's
family one level up: those were query *parameters*, this is a response *field*, which §50's *Not
done* listed as unchecked.

**The compiler cannot catch this on its own, and that is the actual finding.** `api.get<T>` casts
the body to `T`; a type argument is an assertion, not a comparison. Only a *generated* type puts
the contract on the other side of the equals sign. So the contract gained a named
`SuiteHistoryResponse` schema, the hook names it, and repairing the field became a build error at
the one consumer — which is the behaviour that was missing all along.

`frontend/src/api/apiResponseTypes.test.ts` makes the rule mechanical: **no inline object literal
as a type argument to an `api.*` call**, over all 45 call sites. The rule is structural rather than
semantic on purpose — a checker that could decide "this literal matches that schema" would be
re-implementing `openapi-typescript`. Requiring the generated name costs one line in `types.ts` per
response shape and admits no way to be wrong.

## The endpoint

In `execution`, for `CaseHistoryController`'s reason: the path names a suite and the contract tags
the operation `[repository, reporting]`, but every row of the answer is a `test_execution` joined to
a `test_run`. `CaseFacts` — already the one class allowed to name a `repository` table — gains the
two facts that live on the other side (`caseIdsInSuiteSubtree`, `suiteVersionMarkers`), so the
crossing is still one file.

Four decisions worth the reader's time:

**Scope is the subtree.** A folder's history means the folder's, and §6p's rollup rule is to sum
leaf counts. Own-cases-only makes every intermediate folder report an empty history, which reads as
*never executed* for a subtree that runs nightly. Stated in the contract, not just in the code.

**An unrecorded execution row is `notRun` here and dropped from the case timeline** — the one place
this endpoint deliberately disagrees with §49. A timeline is a record of what happened, so a row
nobody recorded is not history; a suite entry is a statement about *coverage*, so the same row is
work outstanding. Both rules are asserted in both test classes, because the pair is easy to make
consistent in the wrong direction.

**Grouped in the database.** A case's timeline names every execution and is bounded by one case; a
suite's input is every execution of every case beneath it, and it renders none of them individually.
`tallyForCases` returns (run × status) counts, and the service folds them into the six buckets —
the status→bucket mapping is a project-configurable vocabulary SQL has no access to.

**`versionAt` resolves the composition in force at the run, not today's.** Reading
`suite.current_version_no` passes every other assertion in the endpoint's test class and fails that
one, which is why it is there. Every mark is examined rather than breaking at the first one in the
future: version order and authoring order are not enforced to agree, and a wrong break returns the
version *before* a gap, silently.

**The `versionNo` filter was deleted, not implemented** — §50's precedent exactly. Nothing sends it,
and §50's binding check refuses a declared parameter no handler binds.

**Stated rather than hidden:** the version-marker rail is structurally empty in every deployment
today. `SuiteVersions` writes v1 at creation and nothing since, so every entry resolves to v1 and
the SPA's "the membership changed during this period" notice never fires — which is honest, because
it has not. The wiring is there so the day a case move writes v2, the screen shows it with no
backfill. The endpoint's test writes a synthetic v2 by SQL to pin the resolution rule now.

## The page was unreachable, and now has a way in

Nothing anywhere linked to `/suites/{id}/history` — §50 recorded it, and it had been true since
Phase 1. It is the first item in the suite tree's kebab menu, ungated: it is the only *read* there,
and a folder's history needs no permission beyond seeing the project, which is already true of
anyone looking at the tree. A `PermissionGate` around it would gate it on nothing and imply
otherwise.

The header also lied, in the §40 way: it read `suite.caseCount` — the folder's **own** rows — above
a grid reporting the subtree, so a parent would announce "3 cases now" over runs covering 47.
`lib/suites.ts#subtreeCaseCount` is the shared rollup, and its test asserts it agrees with the
tree's structural one for every folder in the fixture.

## Two defects only the browser found

**1. Every table in the app was clipped, not scrollable.** The suite history grid's last column —
the date every row is sorted by — was simply absent at 810 px, with no scrollbar offering it. The
page itself did not scroll horizontally, which is the rule working; the content had nowhere to
scroll *inside*, which is the other half of the same rule. The cause is shared: `Panel` sets
`overflow: hidden` for its rounded corners, so this was true of **all eight tables** in the app, not
this one. `components/ScrollX.tsx` is the fix and it is applied at every one of them — sweep the
class, not the instance.

**2. Signing out and in as somebody else, in the same tab, kept the previous user's data.** A fresh
sign-in as another company's admin rendered the *previous* company's project list; only a reload
corrected it. §44 built exactly this protection and it was running for every tab except the one the
user was looking at, because **`BroadcastChannel` does not deliver to its sender** — `sessionChannel`
says so in a comment, and the consequence had not been drawn. `SessionReplayBridge` now clears the
cache on a local identity change too. Verified in the browser: the same flow now lands on "No
projects".

## Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL
           920 tests, 0 failures, 0 skipped     (900 + 20)
frontend:  npm run gen:api → SuiteHistoryResponse generated; runDeleted required
           npm run build   → ✓ built in 322ms
           npm run lint    → oxlint, exit 0
           npm run test:run→ 19 files, 327 tests passed   (272 + 55)
browser:   LIVE mode against a freshly provisioned company — project, three suites (one nested),
           four cases, four runs, eleven results, one run withdrawn by SQL. Parent folder reads
           3/3 · 1/3 · 2/3 over its subtree; the child reads 2/2 · 0/2 · 1/2 over its own. The
           withdrawn run keeps its row, loses its link, carries the chip. Console clean, zero
           client errors, no page-level horizontal scroll.
```

**Mutations, each killed:**

- subtree → own-cases-only in `CaseFacts` — 4 tests.
- `versionAt` ignoring the moment (i.e. "current version") — 1 test, and only that one.
- dropping unrecorded rows from `tallyForCases` — 3 tests.
- re-injecting the inline `{ runs: … }` type argument — `apiResponseTypes.test.ts` names the file
  and line. (This is the real defect, re-injected.)
- disabling the same-tab cache clear — 2 of the 3 new session tests, and not the third, which is
  the guard against over-correcting.

## Not done

- **Response *fields* are still only checked at the type-argument boundary.** The new check
  guarantees the SPA names a generated type; it does not verify that what the server actually
  serialises matches that type. That is the runtime-validation slice §5 has recorded as the largest
  remaining resilience gap since Phase 3.
- **`POST /work-items` and `PATCH /work-items/{id}` are called by the SPA and declared nowhere in
  the contract.** Found by the same orientation pass; this is the *operation*-level form of the
  §50 defect, and `contractParameters.test.ts` cannot see it because neither call carries a query
  string. The next rung is a check that every URL the SPA builds resolves to a declared operation
  **with that method** — and it should land with the `work` module, since the board is the only
  screen those two serve.
- **A case that moves between suites takes its whole execution history with it**, because
  `test_execution` pins the case *version* and not the suite. §6p's `execution_fact` read model
  stores `suite_path` per row for exactly this; until it has a writer, the limitation is recorded
  in `CaseFacts#caseIdsInSuiteSubtree` rather than hidden.
- **No pagination.** Same statement as §49: unpaginated, the contract declares no paging on this
  path, and a server-side cap would silently truncate. The repair is contract-first.
- **`documentation`, `reporting` and `work` writes still run on MSW** — `/doc-pages`, `/artifacts`,
  `/reports`, `/work-items`, plus `cases/bulk`. Teams and memberships remain the next write surface
  with no endpoints (§39, §45–§50).

## Two notes for the next reader

**The number collided.** A concurrent strand landed its own §51 (the open-question decision round)
while this slice was in flight, so this is §52. The convention held exactly as §10 designed it —
two files, two index rows, no conflict in a 276 KB append — and the only manual work was renumbering
this file and its references.

**That strand's "next slice" line was already wrong, and it is struck through rather than deleted.**
It read *"recording a result is the last hop still running on MSW"*. Recording a result has been
live since §38, and this slice's live pass drove `PATCH /runs/{runId}/executions/{executionId}`
eleven times against the real server to build its own fixture. This is §46's *"a 'Not done' note
decays"* happening a second time — inside a day, and in a note written the same day. The
orientation pass's second question ("is this still blocked") earned its place again.
