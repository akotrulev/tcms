# §49. Backend M1, slice 15 + frontend — the case timeline, and the parameter nobody had declared

**Date:** 2026-08-01 · **Branch:** `master` · **Sequential**, the fourth slice since §45.

`GET /projects/{projectId}/cases/{caseId}/history` is live. The case detail's **"Execution history"**
button — rendered since Phase 1, at `CaseDetailPage.tsx:79` — led to a route that answered 404 in live
mode. It now opens the screen the version pin exists for.

## Why this slice

The orientation pass asked §28's question — *which of these has a consumer?* — of every unserved
endpoint. This was the only one with a **visible affordance already wired to it**. `/suites/{id}/history`
has a page and a route and **no link anywhere in the app**, so it was left; teams and memberships
(recorded as "next" in §39, §45, §46, §47 and §48) need a contract change *and* a UI and remain open.

The second argument is that it only became *possible* three slices ago. §42 wrote the first
`case_version_id`. Before that, a timeline could not attribute an old failure to the text that was
actually executed, and every version column on it would have been a guess.

## Two defects found before a line was written

**1. The SPA has always sent a query parameter the contract never declared.** `useCaseHistory`
sends `?scope=CURRENT_VERSION|ALL_VERSIONS` (`api/queries/cases.ts:228`); `openapi.yaml` declared only
`caseVersionNo`, `releaseVersion` and `configurationId`. **Spring silently ignores an unbound query
parameter**, so the "Current version" chip would have returned *every* version's results under a
heading reading "Only results recorded against the version currently saved."

This is **§47's `ownerId` defect one screen over**, and §48's new check does not reach it — that one
covers dashboard filter keys, not every parameter the SPA sends. The two scopes are now asserted to
give *different* answers over the same case, so the parameter cannot be dropped again silently.

**2. `VersionMarker.author` and `ExecutionHistoryEntry.executedBy` were typed non-null and are not.**
`test_case_version.created_by` is nullable **with no foreign key** (§28, finding 1) and
`test_execution.executed_by` is nullable with no cascade. `repository`'s own version list has shipped
null there since §35 by **diverging from the contract silently**. Declared properly here, in §48's
`allOf` + `nullable` spelling that the generator honours.

The tightened types then failed `npm run build` on `marker.author.displayName` — **an unguarded
dereference that would have crashed the timeline** on any version whose author is gone. The contract
repair found a live crash in the page it was written for.

## The rule that needed a new field: §6p on deleted runs

> "Deleted runs must not orphan history — soft delete only, with the entry marked rather than
> vanishing. History that quietly loses rows is worse than no history." (plan §6p, line 1527)

`ExecutionHistoryEntry` had **no field capable of expressing that mark**, so the endpoint could only
have violated the rule or linked into a run that answers 404. `runDeleted` is now required — and
required rather than optional because the alternative reading of an absent flag is "not deleted",
which is the dangerous default: a withdrawn failure would read as a live one.

**The contrast is the rule.** `TestExecutionRepository.findForCase` deliberately does *not* filter
`deletedAt`; its sibling `findForPlan` does. A timeline records what happened; a plan's statistics
claim where things stand, and counting a retracted result there would restore a verdict somebody
withdrew. Both queries now carry the other's name in their javadoc so the asymmetry cannot read as an
oversight. Asserted from both ends: the entry survives **and** is marked, and `caseA`'s live runs are
all `runDeleted: false` — a blanket `true` would pass the first assertion alone.

## The two derived figures, and how each is easy to get plausibly wrong

**Flakiness is scoped to one version AND one configuration** (§6p). The scoping *is* the measurement:
alternation across versions is just change — a test that failed, was fixed, and now passes alternated
once, and calling that flake accuses the test of being unreliable when somebody repaired it.
Alternation across configurations is "passes on Chrome, fails on Safari", which is a finding, and
averaging it into one number is how it stops being one.

The fixture crosses each boundary **exactly once**, so the three implementations give three different
numbers: naive alternation over the whole timeline is 2/5 = 0.4, scoping by version alone is 1/4 =
0.25, and the correct double scoping is 1/3. A test asserting the wrong one still looks plausible.

**First-failed dates the *current* failing streak**, not the earliest failure ever (§6p). The SPA
renders it as `Alert severity="warning"` — a claim about now. Read as "earliest failure", a case that
failed once in March and has passed every night since carries a permanent warning banner dated March.

## The fixture caught a design error the gates could not

Running the test the first time returned entries with `executedAt: null` — the rows a run
materialises for cases nobody has recorded yet. **The contract marks `executedAt` required**, so such
a row has no representation on the wire at all, and the SPA sorts the timeline by that field.

So an execution that has not happened is not history, and `recorded()` drops those rows **once**,
before the summary, the flakiness grouping, the streak walk and the entry list — four consumers that
must agree about which rows exist. Un-started work is already reported, by the plan's coverage figure
and its not-run list. This was found by the endpoint's own test before anything reached a browser.

## Where it lives, and the seam it widened

In `execution`, not `repository`, though the path names a case and the contract tags it
`[repository, reporting]`. Every row of the answer is a `test_execution` joined to a `test_run` —
this module's own tables. Serving it from `repository` would have opened a crossing that exists in
neither direction; serving it here needed only `CaseFacts`, already the one file allowed to name a
`repository` table. **A URL is a client-facing shape, not a module boundary.**

`CaseFacts` gained two crossings, both recorded in its ordered list: `currentVersionNo` (which
doubles as the existence check, so an unknown case is a 404 rather than a 200 of zeros) and
`versionMarkers`. Reading the version rows here rather than calling `repository`'s `GET /versions` is
the point of the seam: this module reads rows it holds ids into, it does not call the other module.

## The live browser pass, and the defect only it found

Driven in **live mode against a real provisioned company** — six runs across a version edit, one of
them withdrawn. The timeline read, top to bottom:

```
Failed   TR-6  [Run deleted]  Ad-hoc smoke, withdrawn   v2     ← marked, and NOT a link
Failed   TR-5  Nightly 5                                v2
Passed   TR-4  Nightly 4                                v2
Failed   TR-3  Nightly 3                                v2
         [Edited to v2]  H. Admin — "Cover tax in the refund"
Passed   TR-2  Nightly 2                                v1
Passed   TR-1  Nightly 1                                v1
         [Created at v1]
```

Green, green, **the edit**, red — §6p's shape, live. The two scopes visibly differ (50% of 6 with 50%
flakiness against 25% of 4 with 67%), which is what proves `scope` is bound rather than ignored.

**The defect: the bottom marker read "Edited to v1".** v1 is the case being *authored* — since §29 a
created case gets its v1 written alongside it — so every timeline in the product ended with a change
nobody made, on the screen whose entire job is answering "what changed?". No gate could see it: it
compiled, linted, and 261 frontend tests passed. Now "Created at v1".

## Gate evidence

```
backend:   ./gradlew build → BUILD SUCCESSFUL in 2m 25s
           898 tests, 0 failures, 0 skipped          (873 + 25)
frontend:  npm run gen:api → regenerated
           npm run build   → ✓ built in 313ms
           npm run lint    → oxlint, exit 0, no output
           npm run test:run→ 16 files, 261 tests passed
browser:   live mode, real company, console clean — no errors, no warnings
```

**Four mutations, each killed by the test that names it:** dropping the configuration from the
flakiness grouping key; reading first-failed as the earliest failure rather than the current streak;
filtering deleted runs out of `findForCase` as `findForPlan` does; and ignoring `scope` — the last
killing four tests at once, which is what the real defect would have looked like.

## Deliberate limits, stated

- **Unpaginated, and §6p asks for 50-at-a-time with infinite scroll.** The contract declares no paging
  parameters here and no consumer sends any, so a server-side cap would silently truncate a timeline
  whose value is completeness. A nightly-automated case with thousands of executions is what breaks
  it; the repair is contract-first, not a magic number in the service.
- **Computed on read**, where §6p specifies an `ExecutionFact` rollup owned by the `reporting` module
  that does not exist — the same deviation `PlanStatisticsService` records, with the same benefit (a
  figure recomputed per request cannot be stale against a result recorded a second ago).
- **`executedByToken` is always null** until M3 ingestion issues tokens. Declared because `source` can
  already say `INGESTED`.
- **`evidenceCount` is queried, not assumed zero.** Nothing uploads evidence yet, but a count nobody
  looked up is a claim rather than a lookup — the argument `findDefectLinks` already makes.
- **Stability-by-configuration and the duration trend** (§6p's other two derived insights) are not
  built; nothing on the SPA renders them.

## Not done

- **`/suites/{id}/history` is still MSW** — and still has **no link anywhere in the app**, which is
  the reason it was not done here. Building the endpoint before the affordance would repeat the
  dead-hook pattern §28 and §35 both paid for.
- **Teams and memberships** remain the next write surface with no endpoints (§39, §45, §46, §47, §48).
- **`documentation`, `reporting` and `work` writes still run on MSW** — `/doc-pages`, `/artifacts`,
  `/reports`, `/work-items`, plus `cases/bulk`.
- **`DELETE /runs/{id}` does not exist**, so a withdrawn run is only reachable by SQL. The endpoint
  handles the state correctly and its test writes the row directly, which is what let §6p's rule be
  asserted before the writer that produces it is built.
- **The undeclared-parameter class is still not machine-checked.** §48's check covers dashboard
  filter keys; this slice found the same defect in a hand-written query string. A computed check
  comparing every parameter the SPA sends against the contract's declared set would have caught both,
  and is the obvious next contract-hygiene rung.
