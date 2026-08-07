# §119. The ingestion history and the unmatched-test binder — the `Location` that pointed at a 404, and the second implementation that is legal because it is asserted

**Date:** 2026-08-07 · **Milestone:** M3, slice 4 · Backend (phase 1) + frontend (phase 2) ·
Plan: [`docs/plans/2026-08-07-m3-ingestion-history.md`](../plans/2026-08-07-m3-ingestion-history.md)

**Gates:** `./gradlew build` **BUILD SUCCESSFUL — 1953 tests across 408 result classes, 0 failures,
0 skipped** (up from 1946/407: seven new). `npm run build` clean after `gen:api`, `npm run lint`
0 warnings, `npm run test:run` **1018/1018 across 76 files**.

> **The frontend baseline was 1002, not the 992 this handoff carried.** Measured by stashing the
> slice and re-running. That figure has now been wrong in the same direction four times; read it off
> a run.

**No migration.** `V36` is still the head, `V37` still free, 61 policies. Nothing this slice reads
was added — the whole slice is two `GET`s over a table that has had a writer since §117 and a
`JSONB` column that has never been read back.

## Why this slice

§117 and §118 both close by naming the same two unbuilt screens, and §117 said the sharper version
of it: declining §6c's auto-created case stubs *"is only defensible for a caller who can read
JSON"* until the binder exists. Three things were dead in `src/` on arrival and this slice is what
gives each one a caller:

| Dead thing | Since |
|---|---|
| `IngestionJobRepository.findByProjectIdOrderByCreatedAtDesc` — *"the history a screen would list"* | §117 |
| `GET`/`POST`/`DELETE /automation-mappings` — no consumer in `src/` | §117 |
| **The `Location` header on `POST /ingestions`** — it named a resource that 404s | §117 |

The third is worth stating alone. The endpoint has answered `201 Location: …/ingestions/{jobId}`
since the day it shipped and there has never been a `GET` at that path. That is not a missing
feature; it is **a claim the API made and could not honour**, and the test that closes it *follows
the header* rather than rebuilding the URL, so a test that passed while the header named something
else is not possible.

## The list and the detail serve different shapes, and that is the slice's main decision

`IngestionJobView.unmatched` is not a column. The names live inside `raw_payload` — one node per
test, megabytes for a 5,000-test build — so a history list that carried them would select every blob
in a project's history to render one screen.

So `GET /ingestions` serves **`IngestionJobSummary`, which has no `unmatched` field at all**, read
through a **JPQL constructor projection** so the blob is never selected rather than selected and
dropped. The tempting version — reuse `IngestionJobView` and send `[]` — lies in exactly the way
this contract already worries about: `unmatchedCount` exists so *"a caller can always tell that
`unmatched` was cut"*, and an empty array beside a count of 12 **reads as cut** when the truth is
"not served on this endpoint". A shape with no such field cannot be misread.

`IngestionJobRow` exists because the projection is columns of one table and `runKey` is not one of
them — it is resolved for the whole page in a single query (`findRunKeys`), which is
`AutomationMappingService.render`'s recorded N+1 rule.

## The replay defect the re-read repaired

`UnmatchedTests` reads the names back out of the payload. It was written for the detail endpoint,
and it fixed something else on the way: **a replay served `unmatchedCount: 12` beside
`unmatched: []`**. `IngestionService`'s idempotent path called `viewOf(job, List.of())` because the
names were computed once, in memory, and never stored as such.

The caller that gets the replay is a **CI retry** — the second time one build has failed to bind the
same tests, by which point the first response is long gone from the log. It was the caller with the
least information and the most need for it.

**It is a second implementation of `unmatchedIn`'s rule** — a node is unmatched when it has no
`caseId` or carries `recorded: false`, which is that method read backwards — and this codebase
normally refuses those. It is legal here on the same standing §111 gave R6's second policy in SQL:
`theDetailAgreesWithThePost` asserts the two against each other over one ingestion, so they cannot
drift without going red.

`normalizedName` is **re-derived, never stored**. It is a pure function of the name the payload
does keep, and a stored copy would be a second spelling of the one string that must agree exactly
with the parser (§117) — which is the same argument, one level down, for why the *client* must not
normalize either.

## The gate is membership, and the fixture grew a second project to prove the other half

Through `readVisible`, exactly as `RunService.list` is gated. There is no `run:read` or
`result:read` in the catalogue — a member may read this project's runs and their verdicts, and a job
is the record of how some of those verdicts got there. A new `ingestion:read` key would be §67's
refused shape for the twelfth time.

What that does *not* grant is the interesting half: the binder **reads** here and **writes** through
`AutomationMappingService`, which costs `case:edit`. The people who can see which tests failed to
bind are a superset of the people who may decide what they mean, which is the right way round — and
Automation Service holds neither, so a leaked CI token still cannot re-point where results land.

The detail read layers a `projectId` predicate over RLS, which is **one of the seven files §117's
closing note names** as having nothing behind it. The `d7` fixture now seeds a **second project** in
the same company and asserts that its job id is a 404 here — so one of the seven is checked, and the
two-project fixture that IN-11's fix wants generalized now exists in this suite.

## The frontend, and the four decisions worth not re-litigating

- **`/projects/:projectId/ingestions` and `/…/ingestions/:jobId` — two routes, not a dialog.** The
  two endpoints serve different shapes, so opening a job is a *fetch* and not an expansion of a row
  the client already has. A dialog fed from the list row would render an empty binder over a job
  with twelve unmatched tests, and nothing on screen would say so.
- **The rail item is in Execution, beside Test runs, and is ungated.** An ingested report is a run's
  results arriving by another door, and the person hunting "why did my CI results not show up" is
  looking at the run that should have them. Hiding the entry from someone without `case:edit` would
  teach them this instance has no CI integration — §31 at rail altitude, §118's argument again.
- **`runId: null` and `runKey: null` are rendered as different sentences.** No run means the report
  matched nothing (a designed outcome — a run of zero cases reports 0 of 0, 100%, complete); a run
  id with no key means the run was **deleted**, and the server withholds the key rather than offering
  a link that 404s. One dash for both is the obvious rendering and it tells somebody whose run was
  deleted that their report matched nothing, which is a different problem with a different fix.
- **A mapping whose case was deleted stays in the list, labelled.** It still consumes the test name,
  so every build still resolves that test to a case that is gone. Filtering it out would hide the
  only row that can be removed to fix it.

**The binder closes on success, which is deliberately the opposite of §118's dialog.** Nothing here
is unrecoverable: the mapping is in the list afterwards and can be re-pointed at any time. §118's
reveal refuses Esc because a credential cannot be produced twice; copying that shape here would be
ceremony.

**`IngestionFormat` is the frontend's name for the schema's `ReportFormat`.** The contract has two
unrelated things called a report format — the CI dialect and a generated report's export format —
and `ReportFormat` was already taken in `api/types.ts` by the second. The collision was a build
error, which is the good version of finding it; the alias is renamed and the reason recorded on both
sides.

## Verified how

**Eleven browser rows (`A3-4`…`A3-14`) and eight mutation rows (`IH-1`…`IH-8`), none run** — §65 as
restated. §117's eight-run deviation was that slice's, not a new rule.

`A3-8` is the row that matters most and it asserts a **request body**, not a screen: a client that
normalized `testName` looks identical in every pixel. The unit test asserts the same thing
(`posts the test name EXACTLY as the report wrote it`), on a fixture name carrying both capitals and
a double space, because those are precisely what a normalizer would eat.

Two things are recorded as **not** driveable rather than faked: the capped-list banner needs a report
with more than 50 unmatched tests (a scripted body, not a browser action), and `IH-3`/`IH-6`/`IH-7`
each need state the automated fixture does not build.

## What is next

- **Drift detection** — §6c's stale-automation flag, and cases receiving results while marked
  `MANUAL`. §117 named it, §118 named it, and the fixture that exposes it (three `MANUAL` cases
  taking ingested results) has existed since §117.
- **Async ingestion**, which is what `PENDING`/`PROCESSING`/`FAILED` and `raw_storage_key` are for.
  `ingestionStatusLabels` already words all four.
- **`lastUsedAt` needs a writer** (§118), still.
- **The project-predicate-over-RLS gap is now six files**, not seven: `CaseFacts.caseIdsByAutomationKey`
  is still unchecked, but this slice built the two-project fixture the standing fix wants, and
  `IngestionJobRepository.findOneInProject` is the first of the family with a test behind it.
- **`raw_payload` has no download.** It is the per-test record — which rung placed each test, what
  the runner said — and the case for serving it is real. There is no consumer yet, which is the
  shape this slice opened by closing three times over.
