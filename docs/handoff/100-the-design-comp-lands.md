# §100 — the design comp lands: three proposals, and three divergences found on the way

**Date:** 2026-08-05
**Source:** `TCMS.dc.html` in the Claude Design project *Test Case Management System*
(`c8274d0c-ca5d-4b95-a5a7-2788463d04e2`), imported through the design MCP.
**Commits:** `f97b5c1` (phase A) · `7a4214c` (phase B) · `0e58651` (phase C)

---

## 0. What the comp actually was, because it changes what "implement" means

The comp is **mostly a portrait of what this repo already has.** Its own design notes say so — the
first one is headed *"Now matches the code"* — and reading it against `frontend/src` confirmed it
line for line: the shell (`RAIL_WIDTH = 232`, the 52px header, the four nav groups), the tokens,
IBM Plex, the status vocabulary, `criterionState`'s three states, `PassRateSummary`, the health
chips in the case grid, the precondition checklist that gates Blocked. All built, all matching.

**The implementable delta was the four notes headed "Proposed", plus one headed "Open question"
listing four things the comp admits it invented** (a ⌘K search, an AI narrative summary on
Reports, a milestone column, and the dual pass-rate headline on the plan detail). The user scoped
the slice to proposals 1, 2 and 3 and explicitly dropped 4 — *health signals totalled in Reports* —
because `DashboardPage` already totals them, and does it more honestly than the comp: it
distinguishes `healthComputedAt: null` ("nobody has looked") from an empty list ("nothing is
wrong"), which the comp's version does not.

**Reading a design comp against the code before building it is the transferable part.** Four of the
comp's screens would have been rebuilt from scratch by anyone who took it at face value.

## 1. Three divergences, none of which a gate could see

Each was found by reading the contract against both implementations, and each had survived because
**both sides agreed and both were wrong** — the shape §52 and §81 already named.

### 1a. `GET /plans` declared a shape no implementation served

`openapi.yaml` declared the list item as `TestPlanSummary`, which has **no `exitCriteria` field at
all**. Meanwhile `TestPlanView` serves the full `TestPlan`, the mock's `present()` serves the full
`TestPlan`, and `usePlans` has been typed `TestPlan[]` since Phase 1. `PlanListPage` had been
reading `plan.exitCriteria` off a field the contract said did not exist.

The sharpest detail: **`TestPlanView`'s own javadoc already claimed "the contract now declares the
wider shape and no consumer changes."** That edit was never made. A comment asserting a fact about
another file, with nothing checking it — §58's E1 and §70's lesson, in the one place that would
have caught this.

Widened the declaration, regenerated. No server changed and no client changed.

### 1b. The mock evaluated criteria the server never evaluates

`PlanCriteria.parse` — the only path `PlanService.view` uses, for the list *and* the detail — builds
every `CriterionView` with `met` and `actual` **null**; its own comment says "Never evaluated in
this slice". `PlanCriteria.evaluate` fills them, and it runs on **`/statistics` alone**, because
resolving a plan's gates means resolving its results.

The fixtures store `met: false` on the plan rows and `present()` handed them straight back. So mock
mode showed a plan list with red and green gates while **every real deployment showed grey ones**.

`lib/results.test.ts` already carries a comment about exactly this: *"it survived every gate and
every browser pass for one reason: the mock always supplied a boolean, so the null branch was
unreachable."* That was written about `criterionState` in §73. The same sentence was still true one
layer up, about the presenter, and nobody had gone looking. `present()` now nulls both fields;
`/statistics` still serves them evaluated.

### 1c. `caseKey.localeCompare` sorts TC-10 ahead of TC-9

`RunDetailPage`'s tiebreaker. `lib/format.ts`'s `compareReleaseVersions` had *already named this*
— "the same class of bug as ordering by case key" — without anyone checking whether the case
existed. It does not show on any fixture set whose keys stay under ten; on a real run it reads as
the list being corrupt. `compareCaseKeys` now sits beside it and both call sites use it.

**A named defect class with no check behind it is a comment, not a guard.** Third instance in this
repo (§19…§62's fixture prefixes, §58's E1, this).

## 2. What shipped

### Phase A — the plan list stops claiming gates were met

`CriteriaStrip` replaces a chip that could say only "All met" or "N unmet". On a list where nothing
is evaluated, "All met" is a release-readiness claim with nothing behind it.

The strip says **"5 not evaluated"**, and distinguishes it from **"0 of 5 met"** — which read
identically to a tired lead at 6pm and mean opposite things. Where some criteria *are* evaluated it
denominates on what was measured and states the rest separately ("2 of 3 met · 2 not evaluated"),
rather than folding unmeasured gates into a failure.

`criterionColor` / `criterionStateLabel` join `criterionState` in `lib/results.ts`. The colour map
was inline in `PlanStatisticsPage` and correct there; a second inline copy on the list is how the
two would come to draw the same unevaluated gate in different colours.

### Phase B — step marks become keys, and they propose a verdict

The step buttons rendered `statusLabels[kind].slice(0, 4)` — **"Bloc"**, four times per step row.
Now the initial, derived from the shared label so a rename carries, with the full label already on
the tooltip and the `aria-label`.

`suggestedVerdict.ts` names the verdict the marks point at. It is its own module for
`stepReorder.ts`'s reason: four short branches whose *interaction* is the part that can be wrong,
and none reachable from a component test without mounting a permission gate, three queries and a
mutation to assert one word. The three that would do damage inverted, each pinned in a test:

- a **half-filled** checklist proposes nothing, but a half-filled checklist with one failure
  proposes Failed — completeness and failure point opposite ways and both are right;
- a **skipped** step proposes nothing rather than falling through to Passed;
- a case with **no steps** proposes nothing — `[].every()` is vacuously true, so the naive version
  proposed Passed from no evidence at all, on exactly the exploratory cases where the tester's
  judgement is the only input there is.

It preselects nothing and disables nothing. A case can legitimately pass with a cosmetic step
deviation and legitimately fail with every step green: step marks are evidence about steps, the
verdict is a statement about the case.

### Phase C — three modes over one queue

Focus · Split (default) · Grid, chosen in `?mode=`. **No new endpoint and no new request** — the
queue is `useRun`'s `executions`, which this screen has read since §72 for the one bit of whether
the run is closed.

The rail sorts **by case key and not by what still needs doing**, which is where it parts company
with `RunDetailPage`. That screen is triage, opened to ask "what is left", and nothing moves while
it is read. The rail is read *while marking*, and a needs-doing rank re-ranks the row the tester is
standing on the instant they record a result. Two views of one run, two orders, deliberately.

**Four rules Grid could have quietly dropped**, each kept and each commented where it is kept:

1. **Blocked still routes through the precondition dialog.** `BlockedDialog` moved to its own file
   and learned to *render* a checklist from the case's own preconditions, because Grid has none on
   screen. Grid's `B` is the one verdict there that is not a single click.
2. **The permission is per row** — `result:submit` for a first result, `result:amend` for changing
   one already recorded. Half a run may be each.
3. **Every write carries `If-Match`.** `lockVersion` is on `TestExecution`, not on the summary a row
   holds, so a mark **reads the execution before it writes**. Bulk marking is precisely where
   overwriting a second tester's result is easiest.
4. **A mark does not erase the comment.** `PATCH` sets `comment` to whatever it is sent and an
   omitted comment **clears** it — server and mock alike — so the read in (3) supplies it back.
   `stepResults` is omitted, which the endpoint reads as "leave them alone".

## 3. Verified — and the two ways this pass was weaker than it looks

**The browser pass was run inline, at the user's explicit direction, overriding §65 for this slice
only.** Rows 101–110 in `docs/verification/frontend-browser-checks.md` are `✅ RUN` with results;
111–113 stay queued. §65's default is unchanged for the next slice.

Two limits, stated rather than glossed:

- **It was mock mode, not live** — and `CLAUDE.md` says live is the one that counts. `.env.local`
  holds `VITE_API_MODE=live` against a backend on :8080 this session had no credentials for, so a
  second vite ran with `VITE_API_MODE=mock` on 5199. Everything observed is therefore evidence about
  the SPA and the mock agreeing, which is the exact pairing §64, §54 and §52 each found a shared
  wrong assumption in. Row 112 (the per-row permission) is **unobservable** in mock mode, which
  holds every key.
- **The responsive claim is unverified.** `resize_window` had no effect on the rendered viewport, so
  the sub-`lg` collapse of the Split rail — the one width-dependent behaviour in the slice — was
  never driven. Row 111.

What the pass did find, that no gate would have: the header's status chip changing in step with a
Grid row's write (proving the shared query key), and the Blocked comment surviving the round trip
(proving rule 4 above, which is the one that silently destroys data if wrong).

## 4. Lessons

1. **Read a design comp against the code before implementing it.** Four of this one's screens were
   already built exactly as drawn; the deliverable was four notes, one of which the user correctly
   dropped as a worse version of something that exists.
2. **A comment asserting a fact about another file is not a check.** `TestPlanView`'s javadoc said
   the contract had been widened. It had not, for an unknown number of slices.
3. **A named defect class with nothing behind it stays open.** `compareReleaseVersions` named the
   case-key sort bug in a doc comment and the codebase had it anyway.
4. **The mock's fixtures decide which branches are reachable.** §73 learned this about
   `criterionState`; the same mock was still hiding the same null branch one layer up. When a
   fixture supplies a value the server never computes, the honest branch is dead code.
5. **Adding a bulk surface re-asks every per-row rule.** Grid mode had to re-derive permission,
   concurrency, comment preservation and the Blocked gate — none of which the single-case screen had
   to think about twice, and all four of which are invisible when wrong.
