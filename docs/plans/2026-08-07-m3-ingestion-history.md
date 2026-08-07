# M3 slice 4 — the ingestion history and the unmatched-test binder

**Date:** 2026-08-07 · **Milestone:** M3, slice 4 · Backend (phase 1) then frontend (phase 2).

## Why this slice

§117 and §118 both close by naming the same two unbuilt screens. §117 declined §6c's auto-created
case stubs on the argument that `POST /automation-mappings` is the supported way to bind an unmatched
test — and then said plainly that *"until the binder exists, declining §6c's stubs is only defensible
for a caller who can read JSON"*. This slice builds the binder, which means it first has to build the
read the binder is a view of.

Three things are dead in `src/` today and this slice is what makes them alive:

| Dead thing | Since |
|---|---|
| `IngestionJobRepository.findByProjectIdOrderByCreatedAtDesc` — *"the ingestion history a screen would list"*, no caller | §117 |
| `GET`/`POST`/`DELETE /automation-mappings` — no consumer in `src/` | §117 |
| The `Location` header `POST /ingestions` returns — **it names a resource that 404s** | §117 |

The third is the one worth stating on its own: `IngestionController.ingest` answers 201 with
`Location: /api/v1/projects/{id}/ingestions/{jobId}`, and there has never been a `GET` there. A
`Location` pointing at a 404 is a claim the API makes and cannot honour.

## Phase 1 — the read (backend)

### The gate is membership, and there is no new key

There is no `run:read` or `result:read` in the catalogue: every read in `execution` is gated on
project **visibility** alone (`ProjectService.readVisible`), because a project member may read that
project's runs and their verdicts. An ingestion job is the record of how some of those verdicts got
there, so it is gated exactly as `RunService.list` is — §67's argument, the twelfth time.

### The list and the detail are DIFFERENT shapes, because of `raw_payload`

`IngestionJobView.unmatched` is not a column. The names live inside `raw_payload`, a `JSONB` blob
holding one node per test in the report — which for a 5,000-test build is megabytes. So:

- **`GET /ingestions` serves `IngestionJobSummary`** — no `unmatched` array at all, and a **JPQL
  constructor projection** so the blob is never selected. A list that carried an empty `unmatched`
  would be lying in the one way this contract already worries about (`unmatchedCount` is *"the true
  total, so a caller can always tell that the list was cut"*): a list row carrying `[]` and a count
  of 12 reads as "cut", when the truth is "not served here".
- **`GET /ingestions/{jobId}` serves `IngestionJob`** — the full shape, its `unmatched` list
  re-derived from `raw_payload`.

### The re-derivation, and the replay defect it also fixes

`IngestionService.viewOf(job, List.of())` is what a **replay** answers with — a CI retry under an
already-used `Idempotency-Key` gets `unmatchedCount: 12` and `unmatched: []`. The names were computed
once, served once, and are recoverable only from the blob. The retry is the caller who most needs
them: it is the second time the same build has failed to bind the same tests.

So `UnmatchedTests` reads them back out of `raw_payload`, and **both** paths use it — the detail read
and the replay. A test is unmatched in the payload exactly when its node has no `caseId`, or has
`recorded: false`; that is `IngestionService.unmatchedIn`'s rule read back, and the two are asserted
against each other rather than trusted to agree.

`normalizedName` is **re-derived**, not stored: `IngestedResult.normalizedName()` is a pure function
of `fullName()`, which the payload does store. Storing it would be a second copy of the one string
that must agree exactly with the parser (§117), and a copy is how it stops agreeing.

### N+1, and the ordering that must not be arbitrary

The run key is resolved for the whole list in **one** query (`findRunKeys`), mirroring `findPlanKeys`
— `AutomationMappingService.render`'s recorded rule.

Ordering is `createdAt DESC, id DESC`. The tiebreak is not decoration: two CI jobs can post inside one
microsecond, and a list whose order is arbitrary between two rows moves them under the cursor of the
person reading it.

## Phase 2 — the screens (frontend)

- **The ingestion history**, project-scoped, newest first: format, when, matched/unmatched counts, and
  a link to the run. A row with no run is the report that matched nothing, and it must read that way
  rather than as a broken link.
- **The binder**: open a job, see its unmatched names with the outcome that says how urgent each one
  is (§117 — an unmatched failure is a red test nobody is being told about), choose a case, confirm.
  `POST /automation-mappings` sends the name **raw**; the client never normalizes.
- **The mappings list**, with delete — the other half of `/automation-mappings`' first consumer.
- Gated on `case:edit` for the writes, `case:read` for the list; the history itself needs only
  membership.

## Out of scope, stated

- **Async ingestion** (`PENDING`/`PROCESSING`/`FAILED`, `raw_storage_key`).
- **Drift detection** — §6c's stale-automation flag and cases marked MANUAL receiving results.
- **A `rawPayload` download.** It is the per-test record and there is a case for serving it; there is
  no consumer yet, which is the shape this slice opens by closing.
