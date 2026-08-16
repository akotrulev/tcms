# Flow review — cases, steps, suites, plans, runs, documents, artifacts, reports

**Date:** 2026-08-15 · **Method:** three read-only traces (authoring, execution, documentation)
over `docs/specs/openapi.yaml`, `backend/src/main/java/com/tcms/`, `frontend/src/features/` and
the handoff sections. Observations verified against source; each improvement names its evidence.
~~This is a review, not a plan — nothing here has been implemented.~~ → **implementation started
2026-08-16; see the progress ledger below.**

---

## 0. Progress ledger — what has been implemented

**Scope authorised 2026-08-16: A1–A8 (every P1).** B, C, D and E are **not** in that scope and are
untouched unless a row below says otherwise. Gates run on `shanks` per `CLAUDE.md`.

Status marks used in the tables: **✅ DONE** · **🔨 IN PROGRESS** · **⬜ NOT STARTED** ·
**⛔ OUT OF SCOPE** (this session).

| # | Status | Landed as | Notes |
|---|---|---|---|
| A2 | ✅ DONE | §227 phase 1 | `GET …/reports/jobs/{jobId}/model`, `report:view`. `ReportJob.modelUrl` now points at it; the artifact route keeps `report:export` and keeps `downloadUrl`. |
| A3 | ✅ DONE | §227 phase 1 | `GET …/reports/jobs` (project's jobs, newest first, top 100, `report:view`) **+ the reaper**: `tcms.reporting.stuck-job-timeout` (PT15M), `ReportJobRunner.failStuckJobs`, swept per company per tick. **The separate "per-project artifact listing" was folded in rather than built**: `report_artifact` has no `project_id` and every artifact is reachable through the job that produced it, so the job list *is* that listing. |
| A5 | ✅ DONE | §227 phase 1 | `useSaveReportDefinition` / `useDeleteReportDefinition` / `useReportJobs`; a "Save these settings as" field on the generate dialog, and per-row **Generate** (regenerate through `definitionId`) and **Delete** on the saved-reports panel. |
| A1 | ✅ DONE | §227 phase 2 | `PUT /projects/{projectId}/cases/{caseId}/status` (`CaseStatusService`, the same `LifecycleLadder` the suite uses, per-act audit verbs) + a **Status** menu on `CaseDetailPage`. `ReviewPanel` was **already** mounted there — the audit's "mount `ReviewPanel` affordances" half was already true, and what was missing was the thing that moves the case. `POST /cases/bulk` is unchanged: a row whose edge costs more than the caller holds is *skipped* there and **403** here, and that difference is asserted against itself. |
| A4 | ✅ DONE | §229 phase 1 | `PUT …/executions/{executionId}/assignee` (`ExecutionAssignmentService`, gated on **`run:execute`** — the one key `Automation Service` does not hold, so a CI credential cannot reorganise a team's workload) + an **Assignment** panel on `ExecutionPage`. **`GET /projects/{projectId}/configurations` came with it and was not in the audit's text**: the environment list lived only on `TestPlanDetail`, so a run scoped by suites or tags (§99) had nowhere to read it from — which is the whole of `RunCloneDialog`'s recorded *"there is no configuration picker anywhere in this SPA"*. Both dialogs now carry **Configuration** and **Assign to**; the clone's two recorded absences are struck through in place rather than deleted, because one of them named the slice that closed it. |
| A6 | ⬜ NOT STARTED | — | `PATCH /artifacts/{id}` + a delete control. |
| A7 | ✅ DONE | §227 phase 2 | `SuitePanel` — above the case grid whenever a suite is selected — carrying the suite's key, name, lifecycle chip, membership count, an execution-history link, and **`ReviewPanel` for `SUITE`**, which had never been mounted anywhere. **A rail-side panel rather than a suite detail page**, of the audit's two options: a suite is flat since §98 and every write it has hangs off the rail, so a page of its own would be a header plus this panel reached by navigating away from the list you pick the suite in. Not *in* the rail either — 260px renders assignees, verdicts and a comment thread as a stack of ellipses. |
| A8 | ⬜ NOT STARTED | — | Cross-navigation between the two link-creation surfaces. |
| E2 | ⛔ OUT OF SCOPE | — | `Idempotency-Key` on `POST /reports/generate`. Pairs naturally with A3 and was deliberately **not** taken: it is a P2, the authorised scope is A1–A8, and it is a contract change to a write. Cheapest remaining reports work; do it next if the reports area is revisited. |

**Also changed in phase 2:** `CaseBulkEndpointTest` is renamed in spirit rather than on disk — its
`@DisplayName` and header now say it covers **both** writers of `test_case.lifecycle_status`,
because the fixture it already had (a case in each ladder state, the three personas the gate turns
on) is exactly what the new endpoint needed and a second class would have been a copy of its seeding
under an instruction to keep it in step. The file name is unchanged so the citations to it resolve.

**Also changed while doing phase 1, and worth knowing before re-reading those files:**

- `ReportJob` gained a required `requestedAt` (the list is ordered by it). Both `schema.d.ts`
  copies regenerated; three frontend fixtures and one `packages/mcp` fixture updated.
- `ReportJobCard` **stopped fetching** — it takes a `job` row and the page holds one poll. The
  polling pair test moved from `ReportJobCard.test.tsx` to `ReportsPage.test.tsx`.
- Stale copy deleted where it described gaps that are now closed: the card's "leaving this page
  loses the link", `ReportsPage`'s "no operation lists them", `ReportViewPage`'s and
  `useReportModel`'s `report:export` finding, and the two contract descriptions that claimed
  `modelUrl` pointed at the artifact route.

---

## 1. The flows as shipped (condensed)

### Cases & steps
Create (`POST /cases`, always `DRAFT`, v1 written in-transaction) → edit (`PATCH`, "PATCH by verb
only": absent nullable scalars *clear*, `If-Match` on `lockVersion`) → lifecycle moves **only via
`POST /cases/bulk`** on the shared `DRAFT → IN_REVIEW → APPROVED / DEPRECATED` ladder
~~(`LifecycleLadder`, edge-keyed permissions: `case:edit` vs `case:approve`).~~ → **since §227 also
via `PUT /cases/{caseId}/status`**, the same ladder and the same edge-keyed permissions; the bulk
endpoint keeps its skip-with-a-reason contract and the single-case one answers 403 (A1). Review apparatus
(`ReviewService`) is per-project opt-in, off by default; author-cannot-approve and min-peer-LGTM
bind only on the `→ APPROVED` edge when an open review exists. Steps belong to their case (§94);
two authoring aids coexist — the shared-step library (`library:manage`, declared vestigial in §94)
and the derived suggestion corpus (`GET /step-suggestions`, text-copy, no link). Autosave buffer is
one row per case (not per person); create mode has no buffer.

### Suites
Flat since §98: membership = filed (`test_case.suite_id`) ∪ added (`suite_case`) ∪ tagged
(`suite_tag_rule`, union semantics). All suite writes hang off the case-list rail
(`SuiteList.tsx`); the only dedicated route is `/suites/:suiteId/history`. ~~A suite's review
record has no visible surface.~~ → **§227: `SuitePanel` renders lifecycle + `ReviewPanel` above the
grid whenever a suite is selected** (A7). Same lifecycle ladder
via `PUT /suites/{id}/status`. Conditions inherit to the **filed arm only**. `suite_version` is
written once (v1) and never again, yet reviews pin to `currentVersionNo`.

### Plans
`DRAFT → IN_REVIEW → APPROVED`, no terminal state since `V32` dropped `CLOSED`. Scope = unsectioned
arm + live-selector sections (§97); `test_plan_version` written only on scope moves. Create takes
6 fields; criteria/prose/sections are edit-only. Statistics computed live; only `pass_rate` and
`coverage` of the five criterion metrics evaluate. UI is the seven-tab `PlanStatisticsPage` with
one Save; case chips resolve via a client-side join over the first 200 cases (no by-ids read).

### Runs & results
`POST /runs` materialises one `test_execution` per case **once**, pinned to the case version;
arms (`caseIds`/`suiteIds`/`tags`) *replace* the plan's scope; `test_run` stores no scope. Statuses
`NOT_STARTED → IN_PROGRESS` (implicit on first result) → `COMPLETED/ABORTED` via `run:close`;
never auto-completes; closing freezes results *and* evidence. Runner has three layouts
(focus/split/grid), per-step marks + case verdict, Blocked requires naming the unmet precondition,
`If-Match` on save. ~~`assignee_id` and `configurationId` are displayed but have **no UI writer**~~
→ **both closed 2026-08-16 (A4, §229 phase 1)**: `PUT …/executions/{id}/assignee` under
`run:execute`, `GET /configurations` under the project read, and Configuration + Assign to on both
run dialogs;
defect links are read-schemas-only (M4). Ingestion (`POST /ingestions`, 4 formats, required
`Idempotency-Key`, four-rung matcher) is synchronous; parse failures leave **no row**.

### Documents & artifacts
Doc pages: block-structured editor, `LTREE` hierarchy, `DRAFT → PUBLISHED → ARCHIVED` via
`doc:publish`; versions hash-gated but **write-only** (no versions endpoint). Links: `VERIFIES` is
the coverage claim; created from the doc side (`doc:edit`) or the case side
(`case:edit`, external targets only), mirrored via `case_doc_link` on an event. Coverage matrix
walks a page's headings with `LATEST` fixed. Artifacts: four-kind register (`FILE`, `DOC_PAGE_REF`,
`EXTERNAL_URL`, `GENERATED_REPORT`), PROJECT/PLAN scope, ACTIVE/ARCHIVED/SUPERSEDED — but **no
`PATCH` endpoint** despite `artifact:edit`, no UI delete control, and no MCP surface.

### Reports
Three types (`run_summary` live · `aggregate_execution` fact-based, ≤15 min stale ·
`coverage_matrix`), generated async (202, polling), rendered MD/HTML (`PDF` in the enum, always
400). ~~**No `GET /reports/jobs`** — the job list is component state and a reload orphans every
artifact. `report:view` cannot open the on-screen model (served through the `report:export`-gated
artifact route). Saved definitions: backend complete, frontend has no Save button. No reaper for
jobs stranded in `RUNNING`.~~ → **all four closed 2026-08-16 (A2/A3/A5, §227 phase 1)**: the job
list is a server operation, the model has its own `report:view` route, saved definitions save and
delete and regenerate from the SPA, and a claim older than `tcms.reporting.stuck-job-timeout` is
swept to `FAILED`. `PDF` is still in the enum and still a guaranteed 400 (B5, out of scope).

---

## 2. Improvements

Grouped by theme, prioritized within. **P1** = broken or dead-ends a real journey ·
**P2** = incoherence/asymmetry that costs users or callers · **P3** = finish-or-remove decisions
on inert machinery.

### A. Complete the broken journeys (P1)

Each row carries its status. The ledger in §0 is the summary; these are the same facts and must
move together.

| # | Status | Improvement | Evidence |
|---|---|---|---|
| A1 | ✅ | **Give a single case a status action.** Add `PUT /cases/{caseId}/status` (the ladder already exists in `LifecycleLadder`; suites and plans have this exact endpoint) and mount lifecycle actions + `ReviewPanel` affordances on `CaseDetailPage`. Today submitting *one* case for review means: leave the detail page, find the row in the list, select it, open the bulk dialog. | No status path under `/cases/{caseId}` in openapi.yaml; `CaseBulkService` is the only `reviews.moved` caller for cases |
| A2 | ✅ | **Fix `report:view` vs the on-screen model.** Serve the report *model* under `report:view` and keep only the downloadable artifact under `report:export`. The §6e persona ("may read, may not carry off") is currently refused the report it watched being generated. | `ReportViewPage.tsx:38-46` documents it as a server-side gap |
| A3 | ✅ | **Add `GET /reports/jobs` (+ per-project artifact listing).** The job set lives in component state; a reload loses every download link permanently. Pair with **a reaper/retry for jobs stranded in `RUNNING`** — today a worker death after claim leaves a job no client or operator can resolve. | `ReportsPage.tsx:20-31`; `ReportJobRunner` header (no reaper by decision) |
| A4 | ✅ | **Make execution assignment writable.** `test_execution.assignee_id` is rendered on the run detail and the queue but the only writer is `POST /runs`'s whole-checklist `assigneeId` — which itself has no control in `RunCreateDialog`. Add a `PATCH` for per-execution assignee and surface `assigneeId` + `configurationId` in the create/clone dialogs. In the shipped UI every execution is permanently unassigned. | `RunCreateDialog.tsx` (documented omissions ~167, 272); `ExecutionResultInput` carries no assignee |
| A5 | ✅ | **Wire saved report definitions in the SPA.** `POST/DELETE /reports/{definitionId}` are implemented and the panel renders — with no Save button and always `[]`. One hook + one button turns an inert backend feature on. | No `useCreateReportDefinition` in `api/queries/` |
| A6 | ⬜ | **Give artifacts an edit path and a delete control.** There is no `PATCH /artifacts/{id}` despite `artifact:edit` (a typo can only be fixed by supersede-or-recreate), and `DELETE` — with both 409 guards — has no UI caller. `ArtifactTable.tsx:236` says so itself. | openapi.yaml artifact paths; `ArtifactTable.tsx:236` |
| A7 | ✅ | **Give suites a home.** `suite:review`/`suite:approve`/`reviewEnabledSuite` all exist, yet a suite's review record has no visible surface — `ReviewPanel` mounts on cases and plans only, and all suite writes live in the case-list rail. Either a suite detail page or a rail-side panel that shows lifecycle + review standing. | `SuiteList.tsx`; only suite route is `/suites/:id/history` |
| A8 | ⬜ | **Cross-navigate the two link-creation surfaces.** A `DOC_BLOCK` link is only creatable from the doc page; the case's Traceability panel writes external targets only. The permission asymmetry is argued and fine — but a user on a case who wants to claim a requirement paragraph gets no path there. Minimum: a "link from documentation" jump that lands on the page with the case pre-selected in `LinkBlockDialog`. | `DocLinksPanel` vs `LinkBlockDialog`; openapi.yaml:2101 rationale |

### B. Heal the asymmetries (P2)

| # | Improvement | Evidence |
|---|---|---|
| B1 | **Unify `PATCH` semantics — or at least the case's.** Case `PATCH`: absent nullable scalar = *clear*, three enums required. Plan `PATCH`: absent = *leave alone*. Same verb, opposite defaults, two screens a user moves between; the case form is the footgun (a partial writer silently erases fields). Plan-style leave-alone with explicit-null-clears is the safer target. | Authoring trace §1 vs §4; `@tcms/client` `core/rewrite.ts` exists to paper over it |
| B2 | **Fix the case round-trip.** `POST` accepts a stepless `STRUCTURED` case; `PATCH` refuses it — so a title-only stub read back and saved unchanged is a 400. `ownerId`/`automationTags` are served on `GET` and refused on write. Read-modify-write should be a no-op. | §200 arm 1; `CaseEdit.ProcedureRule` |
| B3 | **Say which tag semantics apply, everywhere tags appear.** Grid filter ANDs; suite tag rules and the run `tags` arm OR. All three are deliberate — none is labeled in the UI. One line of helper text per surface ("matches **any** of these tags" / "**all** of these tags") removes the trap; unifying is likely not worth the migration. | §98; §99 |
| B4 | **Close the `Idempotency-Key` gaps.** Artifact registration and the multipart file upload have none — a retried upload duplicates a register row (and orphans bytes). Doc pages got theirs in `V42`; artifacts should follow. Shared steps too, or document why a visible twin is acceptable. | Doc trace §5 |
| B5 | **Remove `PDF` from the format enum** (or capability-discover it). It appears in every generated client's dropdown and is a guaranteed 400 in every deployment. | `ReportRenderers` registers MD+HTML only |
| B6 | **Record ingestion failures without poisoning the retry key.** Today a parse failure leaves no row (deliberately, so the CI-generated key isn't consumed) — so CI teams see nothing for a rejected report except the original 400 body. Record failures keyed separately from the success-idempotency key (e.g. failure rows carry the key but replay checks only `COMPLETED` rows), then either use `PENDING/PROCESSING/FAILED` or delete them from the enum. | `IngestionService`; `IngestionStatus` has 3 unreachable values |
| B7 | **Store the run's scope request.** `test_run` keeps no arms, so nothing can answer "why is this case in this run", and cloning a suite-scoped run silently omits cases added since. Persist the request (`planId`+arms) as explanatory metadata — materialisation-once stays the law; this is provenance, not re-resolution. Offer clone-by-original-scope as an explicit option. | `RunCreateService`; execution trace friction 15 |
| B8 | **Add a by-ids case read** (`GET /cases?id=...` or `POST /cases/lookup`). `PlanCasesTab` joins client-side over the first 200 cases; ids beyond render as unresolved chips. The same primitive would serve future pickers. | `PlanCasesTab.tsx` |
| B9 | **Let `POST /plans` express what clone copies.** `TestPlanInput` has six fields; criteria, prose scope, tags and sections are edit-only, so creating a complete plan is structurally two requests and clone > create in expressiveness. | openapi.yaml:2732 vs 2933 |
| B10 | **Preview destructive suite operations.** Deleting a suite blocks only on filed cases — added/tagged members are dropped with no preview; detaching a suite condition cascades away every case's opt-out. Show the counts in the confirm dialog (the membership query already exists). | `SuiteMembership`; `case_condition_optout ON DELETE CASCADE` |
| B11 | **Filter the criterion-metric vocabulary in the UI** to the two that evaluate (`pass_rate`, `coverage`), or mark the other three "not yet evaluated" at selection time — today a user can build an exit gate that will read `null` forever (until M4). | `PlanStatisticsService`; execution trace friction 20 |

### C. Finish or retire the inert machinery (P3 — each is a decision, not a task)

| # | Decision to make | Evidence |
|---|---|---|
| C1 | **Versioning story.** Four resources, four behaviours: cases (full table + panel, no restore/diff), suites (v1 then never), plans (scope-only, no endpoint), doc pages (hash-gated, write-only, `pinned`/`change_comment` never set). Either ship one read+diff(+restore) surface across them, or trim the write paths that nothing reads. The review apparatus pinning `Suite.currentVersionNo` to a number that never moves is the sharpest edge. | `SuiteVersions`, `PageVersions` headers |
| C2 | **Quarantine.** The flag is recorded (`PUT /cases/{id}/quarantine`, reason required) and nothing downstream reads it — runs, reports, pass rates and flake all include quarantined cases. Decide the consumer (exclude from `aggregate_execution` denominators? badge in the runner?) or it is a note field with ceremony. | Authoring trace friction 16 |
| C3 | **Search.** `doc_page.search_vector` (and `V2`'s triggers) are maintained, GIN-indexed, and no endpoint queries them — while `PlanDocumentPanel` justifies its design on pages being "found by everybody who searches documentation". A docs search endpoint is the cheapest visible win from machinery already paid for. | Doc trace friction 8 |
| C4 | **Shared-step library.** §94 declared it vestigial next to the suggestion corpus, and the picker's insert writes the entry's *title* as the action while the body lives elsewhere. Either retire it (migrate `sharedStepId` refs to copied text) or make it real (picker inserts the body; suggestions offer "promote to library"). Half-alive is the one wrong state. | §94; `SharedStepPicker.tsx` |
| C5 | **Defects pre-M4.** `DefectLink` renders on run detail, `defect:create` is seeded, `defect_link` is in the ERD — and no endpoint writes one. Either hide the read-only column until M4 or ship the minimal external-link write (the key and table already exist; it is the same shape as case external links). | Execution trace friction 7 |
| C6 | **`GENERATED_REPORT` artifacts.** The reporting module now exists and `artifact.report_artifact_id` is waiting — "save this report to the register" from `ReportViewPage` would close the loop between two shipped features (and give reports a durable home, reinforcing A3). | `ArtifactKind.java` prose is stale on this |
| C7 | **`write_once` has no writer.** The delete guard honours it; nothing sets it. Expose it on sign-off-kind registrations or drop the column. | Doc trace friction 16 |
| C8 | **Dead permission keys.** `doc:read` and `artifact:read` have no evaluation site (both services gate on project read, with recorded rationale). Fine — but they sit in the catalogue, the matrix, and every copy-checking test as keys that can never 403. Worth an explicit "checked-by-project-read" annotation in the roles spec so the next matrix sweep doesn't re-litigate them. | `DocPageService.java:19`, `ArtifactService.java:29` |
| C9 | **Step-level evidence.** The API takes `stepId`, the hook forwards it, `EvidencePanel` never passes one. Either wire the runner's step rows to it or note it as UI-deferred in the verification queue. | Execution trace friction 8 |
| C10 | **MCP artifact tools.** The register has no MCP surface at all (self-documented in `writes.ts` §211 as waiting for the register to get a surface). A6 (edit+delete UI) is the prerequisite; after it, a read tool at minimum makes the register visible to the agent lane. | `packages/mcp/src/writes.ts` header |
| C11 | **Stale prose sweep.** `useCan.ts:47`, `ArtifactDialogs.tsx:190,262`, `ArtifactTable.tsx:227` still assert the pre-`V52` `PLAN_SCOPE` grant as current; `ArtifactKind.java` says the reporting module "does not exist". The tier-claims rule (§212) catches key-naming claims — these escape it because they name a *qualifier*, not a tier. Hand-fix, or extend the rule to qualifier claims. | Doc trace frictions 6, 13 |

### D. Data-loss guards (P2, small)

| # | Improvement | Evidence |
|---|---|---|
| D1 | **Autosave per person, and for create mode.** The draft buffer is one row per case (last keystroke wins across two editors; `updatedBy` is the only tell) and the New Case form has no buffer at all (PK is an FK to `test_case`). Key by `(case_id, user_id)`; give create mode a client-side draft at minimum. | `test_case_draft` PK; authoring trace friction 8 |
| D2 | **Plan deletion after execution.** A plan named by any run is a permanent 409 and there is no terminal state since `V32`. If `CLOSED` stays deleted, consider an `ARCHIVED`-style visibility flag so old plans leave the working list. | Authoring trace friction 12 |
| D3 | **Run-level concurrency.** `test_run.lock_version` exists and is deliberately unmapped; there is also no run-edit endpoint (name/release/build fixed at create). If run edit ever ships, map the lock in the same slice — note the pairing now so they don't land separately. | `TestRun.java:58-70` |

### E. Reporting coherence (P2)

| # | Improvement | Evidence |
|---|---|---|
| E1 | **Stamp freshness on every pass-rate surface.** Three surfaces answer "pass rate" with different freshness and denominators: run summary (live), plan statistics (live, policy echoed), `aggregate_execution` (≤15 min stale, no "not run" rows). The fact-based report should carry its projection watermark ("as of HH:MM") the way plan statistics already echoes its aggregation policy. | Execution trace friction 13 |
| E2 | **Consider `Idempotency-Key` on `POST /reports/generate`.** The one create of eleven that takes none (found by the §205 conformance sweep as a header-declaration defect); a retried 202 queues the report twice. Cheap once A3's job listing exists. | §205; `tcms_report.generate` |

---

## 3. Suggested sequencing

1. **A2 + A3 + A5 (+ E2)** — one reports slice: list jobs, fix the view gate, wire saved
   definitions. Turns the weakest feature area into a coherent one.
2. **A1 + A7** — lifecycle parity: single-case status endpoint + a visible home for suite
   lifecycle/review. Both reuse `LifecycleLadder`/`ReviewService` as-is.
3. **A4 + A6** — the two "rendered but unwritable" surfaces (execution assignment, artifact
   edit/delete).
4. **B1/B2** — case `PATCH` semantics; contract change, so one slice with both `gen:api`
   regenerations and the §197/§203 harnesses will police it.
5. **C-items** — one decision session over the finish-or-retire list; several (C3, C6, C9) are
   small once decided.
