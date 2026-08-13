# §184 — The ordering only the client had ever asserted, and the second key nothing had ever seen

**Two phases: `backend/`, then `frontend/`; no migration, no contract change, nothing in
`packages/`.** §183's named next slice, taken as what it said it was: **a decision to re-take, not a
defect to fix.**

## The decision

> **`DashboardService.health` is the only ordering of health tiles. The panel renders what it is
> sent.**

§179 took this exact decision at the case grid — *"Do not sort here — a client-side sort would be
the second copy of a severity ranking"* — and `CaseGrid.tsx` has carried the comment ever since.
The dashboard panel did the opposite, thirty lines under the colour table §183 had just deleted from
the same file for being a second copy of the same enum. **Two screens showing one severity, giving
different answers to who ranks it.**

Both arguments were already written and they are the ones §183 said to weigh:

- **For the panel sorting** — `HealthPanel.test.tsx`'s own comment: *"the panel sorts again so the
  order is a property of the screen rather than of one server version."*
- **Against** — §179's: the ranking is a property of the *answer*, and a client-side copy of it is
  the thing that drifts.

**The defensive argument loses on its own terms.** It defends against a server that forgot to sort;
but the server's comparator has **two keys** and the SPA's copy had **one**, so against that server
the panel would have been *differently* wrong rather than right — severity restored, count tiebreak
gone, and nothing anywhere saying so. A defence that half-works against a failure the other side
already has a test for is not buying a property; it is buying a second implementation.

Two more things decided it, neither of them in §183's summary:

- **The copy was not exhaustive.** `severityRank` was a four-way **ternary chain with a fallback**,
  so a fifth `Severity` member on the wire would have ranked bottom silently. `severityVar` — added
  thirty lines away in §183 — is `satisfies Record<…>` and would have failed the build on the same
  change. The two copies of the same enum sat in one file with opposite failure modes.
- **The no-op was a coincidence.** `Array.prototype.sort` is stable and `severityRank`'s key was the
  server comparator's **first** key, so the server's count-descending tiebreak survived a sort that
  had never heard of it. The response and the screen agreed for a reason nobody had written down.

## What the slice found

**The tile order had no observer on the server at all, and the only one anywhere asserted the copy
about to be deleted.**

`HealthPanel.test.tsx`'s *"renders the tiles when signals were raised, worst first"* passed a
WARNING ahead of an ERROR and checked the panel swapped them. That is an assertion about
`severityRank`. `DashboardEndpointTest` writes no `case_health` row — its health assertions are that
the panel is *empty* — so **`DashboardService.health`'s sort would have stayed green if it had been
deleted**, and the SPA's test would have covered for it.

This is §153's finding (*a slice that moves a decision to the side that owns it and leaves its only
coverage behind*), which **§179 called out by name for this panel's neighbour** and which this slice
then had to apply to the panel. Hence the phase order: the backend test lands **first**, in its own
commit, so the copy is never removed while unobserved.

**The second comparator key had never been observed by anything, on either side.** Not by the SPA's
sort, which did not have it; not by a backend test, which did not exist. It has been shipping since
the panel was built.

## Changed

**Phase 1 — `backend/`, three files:**

- **`DashboardService.health`** — `private static` → `static`, with the widening and its cost
  recorded in the javadoc; the sort's comment rewritten, since the sentence it ended on (*"the SPA's
  own sort is then a no-op over an already-ordered list"*) was true, was true by accident, and is now
  simply not the case.
- **`HealthTileOrderTest`** — new. No database, no context (§150's lane, `RaisedSignalTest`'s
  neighbour): severity outranks count, bigger count first within a severity, count never lifts a
  tile past a worse one, a signal at zero and a signal absent are both no tile, and every raised
  signal is tiled exactly once carrying its own filter.
- `docs/agent-context/be-java.md`.

**Measured, by deleting the sort and re-running:** the three order assertions fail, the two
"nothing at zero" ones and the completeness one stay green. **`worstFirst` alone stays green** —
its triple happens to agree with declaration order — which is exactly why `notDeclarationOrder`
exists, and that measurement is written into the test so nobody deletes the second as a duplicate of
the first. `HealthSignal.values()` is the loop, so "forgot to sort" and "sorted correctly" are the
same code path for any input whose declaration order agrees.

**Phase 2 — `frontend/`, three files:**

- **`features/dashboard/DashboardPage.tsx`** — `severityRank` deleted, `data.health` rendered
  verbatim, and the do-not-sort comment in the same shape `CaseGrid.tsx` has carried since §179.
- **`features/dashboard/HealthPanel.test.tsx`** — the order test **inverted**. Its fixture still
  passes a WARNING ahead of an ERROR, and now expects that order on screen. It reads like a bug, so
  it says at length that it is not: an order the server cannot produce is the only input a sorting
  panel and a transparent one disagree about.
- `docs/agent-context/fe-react.md`.

## Gates

Run on `shanks`. Phase 1: `./gradlew build` green (**7m 22s**). Phase 2: `npm run build`,
`npm run lint` (0 warnings), **1271 tests across 97 files in 186.5 s** — unchanged count, since the
one test that changed changed its expectation rather than its existence.

Rows `§184 TO-1`…`TO-3` **recorded and not run** (§65). **No mutation rows**: `src/main` changed
only in a method's visibility and its comments, and no request produces a different byte.
**Built and unverified.**

**`TO-2` is recorded as possibly undrivable and says so.** The comparator's second key needs two
tiles of the *same* severity with different counts, and the live lane may not raise two ERROR
signals at once. §65's rule is that "not driven, and here is why" is a legitimate outcome; a row
that quietly cannot be executed is not.

## Housekeeping this slice took

**`HANDOFF.md`'s state section said the Flyway head was `V44` and `V45` the next free number.** It is
`V48`, and has been since §174. §183 noticed it, said it was not that slice's file to edit, and named
the reason it matters more than an ordinary stale line: **`out-of-order` is unset**, so a version
number taken from a stale doc lands as a migration that is *refused on any already-migrated
database* and passes every Testcontainers run, which migrates a fresh container each time. Corrected
here.

## The next slice

§183's list, minus the entry this one took:

- the **read-only-archive** decision §159 did not take;
- **serving the relabel audit** — §174's recorded absence;
- **`CurrentUser.teams` has no reader** — §175 kept the array on a permission argument;
- **a run has no edit endpoint at all** — §177 leaned on that twice and did not build it;
- **run the browser queue** — ~738 rows, thirteen lanes, one sitting. At the **bottom** at the
  user's direction (2026-08-12).

**A shape worth naming before it is found a fifth time.** §179, §180, §183 and §184 are four
consecutive instances of *one enum, two implementations of a rule about it, on two screens one
navigation apart* — a severity list, a colour map, a second colour map, an ordering. Three were
found by the slice standing next to them and none by either suite. The remaining copies of this
family are worth **deriving rather than reading for**: what the SPA computes from a contract enum
that the server also computes. `lib/labels.ts`, `lib/statusColors.ts` and `lib/results.ts` are the
places that are *supposed* to hold such tables; a table anywhere else is the candidate.
